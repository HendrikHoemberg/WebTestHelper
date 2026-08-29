package dev.hendrikhoemberg.webtesthelper.crawler;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Playwright;
import org.slf4j.Logger;
import org.slf4j.MDC;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * A fixed set of thread-confined browser workers (spec 5.4). Playwright's Java API is not
 * thread-safe: a {@code Playwright} instance and every object created from it may only be
 * touched on the creating thread. Each worker therefore owns a single-thread executor, creates
 * its {@code Playwright} and {@code Browser} on that thread, and runs every task there.
 *
 * <p>Platform threads, not virtual ones: Playwright pins native resources and blocks in JNI,
 * which is exactly the workload virtual threads are wrong for.
 *
 * <p>Callers hand in a {@link BrowserTask} and receive its return value. Anything derived from
 * the {@code Browser} — contexts, pages, responses — must be created and closed inside the
 * task. A {@code PageSnapshot} may leave; a {@code Page} may not.
 */
@Component
public class BrowserPool implements AutoCloseable {

    @FunctionalInterface
    public interface BrowserTask<T> {
        T run(Browser browser) throws Exception;
    }

    private static final Logger log = LoggerFactory.getLogger(BrowserPool.class);

    private final List<Worker> workers = new ArrayList<>();
    private final BlockingQueue<Worker> available;

    /** Chromium launch flags; {@code --no-sandbox} is added only for container use (WTH_CHROMIUM_NO_SANDBOX). */
    static BrowserType.LaunchOptions launchOptions(boolean headless, boolean noSandbox) {
        var options = new BrowserType.LaunchOptions().setHeadless(headless);
        if (noSandbox) {
            options.setArgs(List.of("--no-sandbox"));
        }
        return options;
    }

    public BrowserPool(CrawlerProperties properties) {
        int size = Math.max(1, properties.browserWorkers());
        this.available = new ArrayBlockingQueue<>(size);
        for (int i = 0; i < size; i++) {
            Worker worker = new Worker(i, properties.headless(), properties.noSandbox());
            workers.add(worker);
            available.add(worker);
        }
    }

    public int size() {
        return workers.size();
    }

    /** Workers currently lent out. Saturation is this against {@link #size()} (spec 14). */
    public int busy() {
        return workers.size() - available.size();
    }

    /** Borrows a worker, runs the task on its thread, and returns the worker. Blocks if busy. */
    public <T> T submit(BrowserTask<T> task) {
        Worker worker;
        try {
            worker = available.take();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Warten auf Browser-Worker unterbrochen", e);
        }
        try {
            return worker.call(task);
        } finally {
            available.add(worker);
        }
    }

    @Override
    public void close() {
        workers.forEach(Worker::close);
        workers.clear();
        available.clear();
    }

    private static final class Worker {

        private final int index;
        private final boolean headless;
        private final boolean noSandbox;
        private final ExecutorService thread;
        private Playwright playwright;
        private Browser browser;

        private Worker(int index, boolean headless, boolean noSandbox) {
            this.index = index;
            this.headless = headless;
            this.noSandbox = noSandbox;
            this.thread = Executors.newSingleThreadExecutor(runnable -> {
                Thread t = new Thread(runnable, "browser-worker-" + index);
                t.setDaemon(true);
                return t;
            });
        }

        <T> T call(BrowserTask<T> task) {
            // Spec 14: runId/siteId must reach every worker thread, or the per-page log lines —
            // the ones worth grepping — carry no run. MDC is a ThreadLocal, so the caller's map
            // is copied in and cleared again: a pooled thread outlives the run that borrowed it,
            // and stale context would file the next run's pages under this one.
            Map<String, String> callerContext = MDC.getCopyOfContextMap();
            Future<T> future = thread.submit(() -> {
                if (callerContext != null) {
                    MDC.setContextMap(callerContext);
                }
                try {
                    ensureBrowser();
                    return task.run(browser);
                } finally {
                    MDC.clear();
                }
            });
            try {
                return future.get();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Browser-Aufgabe unterbrochen", e);
            } catch (ExecutionException e) {
                throw new IllegalStateException("Browser-Aufgabe fehlgeschlagen", e.getCause());
            }
        }

        /** Runs on the worker thread only. Restarts a browser that died mid-run (spec 14). */
        private void ensureBrowser() {
            if (playwright != null && browser != null && browser.isConnected()) {
                return;
            }
            if (playwright != null) {
                log.warn("Browser-Worker {} startet Chromium neu", index);
                closeQuietly();
            }
            playwright = Playwright.create();
            browser = playwright.chromium().launch(launchOptions(headless, noSandbox));
        }

        private void close() {
            try {
                thread.submit(this::closeQuietly).get();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (ExecutionException e) {
                log.debug("Browser-Worker {} beim Schließen: {}", index, e.getCause().toString());
            } finally {
                thread.shutdownNow();
            }
        }

        /** Must run on the worker thread — closing from elsewhere violates the confinement. */
        private void closeQuietly() {
            try {
                if (browser != null && browser.isConnected()) {
                    browser.close();
                }
            } catch (RuntimeException e) {
                log.debug("Browser {} liess sich nicht schliessen: {}", index, e.toString());
            }
            try {
                if (playwright != null) {
                    playwright.close();
                }
            } catch (RuntimeException e) {
                log.debug("Playwright {} liess sich nicht schliessen: {}", index, e.toString());
            }
            browser = null;
            playwright = null;
        }
    }
}