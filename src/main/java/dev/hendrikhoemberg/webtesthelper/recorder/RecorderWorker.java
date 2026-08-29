package dev.hendrikhoemberg.webtesthelper.recorder;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Playwright;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * A dedicated, thread-confined Chromium worker for recording sessions (spec 10.1, D109).
 *
 * <p>Playwright's Java API is not thread-safe: a {@code Playwright} instance and every object
 * created from it may only be touched on the creating thread. Each worker owns a single-thread
 * platform executor, creates its {@code Playwright} and {@code Browser} on that thread, and runs
 * every task there.
 */
public class RecorderWorker implements AutoCloseable {

    @FunctionalInterface
    public interface RecorderTask<T> {
        T run(Browser browser) throws Exception;
    }

    private static final Logger log = LoggerFactory.getLogger(RecorderWorker.class);

    /** Chromium launch flags; {@code --no-sandbox} is added only for container use (WTH_CHROMIUM_NO_SANDBOX). */
    static BrowserType.LaunchOptions launchOptions(boolean headless, boolean noSandbox) {
        var options = new BrowserType.LaunchOptions().setHeadless(headless);
        if (noSandbox) {
            options.setArgs(List.of("--no-sandbox"));
        }
        return options;
    }

    private final int index;
    private final boolean headless;
    private final boolean noSandbox;
    private final ExecutorService thread;
    private Playwright playwright;
    private Browser browser;

    RecorderWorker(int index, boolean headless, boolean noSandbox) {
        this.index = index;
        this.headless = headless;
        this.noSandbox = noSandbox;
        this.thread = Executors.newSingleThreadExecutor(runnable -> {
            Thread t = new Thread(runnable, "recorder-worker-" + index);
            t.setDaemon(true);
            return t;
        });
    }

    public int index() {
        return index;
    }

    /**
     * Executes a task on this worker's dedicated thread, passing the thread-confined {@link Browser}.
     */
    public <T> T submit(RecorderTask<T> task) {
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
            throw new IllegalStateException("Recorder-Aufgabe unterbrochen", e);
        } catch (ExecutionException e) {
            throw new IllegalStateException("Recorder-Aufgabe fehlgeschlagen", e.getCause());
        }
    }

    /** Runs on the worker thread only. Restarts a browser that died mid-session. */
    private void ensureBrowser() {
        if (playwright != null && browser != null && browser.isConnected()) {
            return;
        }
        if (playwright != null) {
            log.warn("Recorder-Worker {} startet Chromium neu", index);
            closeQuietly();
        }
        playwright = Playwright.create();
        browser = playwright.chromium().launch(launchOptions(headless, noSandbox));
    }

    @Override
    public void close() {
        try {
            thread.submit(this::closeQuietly).get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException e) {
            log.debug("Recorder-Worker {} beim Schließen: {}", index, e.getCause().toString());
        } finally {
            thread.shutdownNow();
        }
    }

    /** Must run on the worker thread — closing from elsewhere violates thread confinement. */
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
