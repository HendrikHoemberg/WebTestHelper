package dev.hendrikhoemberg.webtesthelper.reporting;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.options.Margin;
import com.microsoft.playwright.options.WaitUntilState;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Component
public class PdfRenderer implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(PdfRenderer.class);

    private final boolean noSandbox;
    private final ExecutorService executor;
    private Playwright playwright;
    private Browser browser;

    public PdfRenderer() {
        this(resolveNoSandbox());
    }

    public PdfRenderer(@Value("${webtesthelper.crawler.no-sandbox:false}") boolean noSandbox) {
        this.noSandbox = noSandbox || resolveNoSandbox();
        this.executor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "pdf-renderer-worker");
            thread.setDaemon(true);
            return thread;
        });
    }

    static boolean resolveNoSandbox() {
        String env = System.getenv("WTH_CHROMIUM_NO_SANDBOX");
        if (env != null && (env.equalsIgnoreCase("true") || env.equals("1"))) {
            return true;
        }
        return Boolean.getBoolean("wth.chromium.no-sandbox");
    }

    static BrowserType.LaunchOptions launchOptions(boolean headless, boolean noSandbox) {
        var options = new BrowserType.LaunchOptions().setHeadless(headless);
        if (noSandbox) {
            options.setArgs(List.of("--no-sandbox"));
        }
        return options;
    }

    public byte[] render(String html) {
        Future<byte[]> future = executor.submit(() -> {
            ensureBrowser();
            try (BrowserContext context = browser.newContext();
                 Page page = context.newPage()) {
                page.setContent(html, new Page.SetContentOptions().setWaitUntil(WaitUntilState.LOAD));
                return page.pdf(new Page.PdfOptions()
                        .setFormat("A4")
                        .setPrintBackground(true)
                        .setMargin(new Margin()
                                .setTop("15mm")
                                .setBottom("15mm")
                                .setLeft("15mm")
                                .setRight("15mm")));
            }
        });

        try {
            return future.get(30, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            log.warn("PDF-Rendering überschritt das Zeitlimit von 30 Sekunden");
            throw new IllegalStateException("PDF-Rendering überschritt das Zeitlimit von 30 Sekunden", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("PDF-Rendering unterbrochen", e);
        } catch (ExecutionException e) {
            throw new IllegalStateException("PDF-Rendering fehlgeschlagen", e.getCause());
        }
    }

    private void ensureBrowser() {
        if (playwright != null && browser != null && browser.isConnected()) {
            return;
        }
        if (playwright != null) {
            log.warn("PDF-Renderer startet Chromium neu");
            closeQuietly();
        }
        playwright = Playwright.create();
        browser = playwright.chromium().launch(launchOptions(true, noSandbox));
    }

    @Override
    @PreDestroy
    public void close() {
        close(5, TimeUnit.SECONDS);
    }

    void close(long timeout, TimeUnit unit) {
        long deadline = System.nanoTime() + unit.toNanos(timeout);
        try {
            executor.submit(this::closeQuietly).get(timeout, unit);
        } catch (TimeoutException e) {
            log.warn("PDF-Renderer schloss nicht innerhalb von {} {}, erzwinge Abbruch", timeout, unit);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException e) {
            log.debug("PDF-Renderer beim Schließen: {}", e.getCause() != null ? e.getCause().toString() : e.toString());
        } finally {
            executor.shutdownNow();
            long remainingNanos = deadline - System.nanoTime();
            if (remainingNanos > 0) {
                try {
                    executor.awaitTermination(remainingNanos, TimeUnit.NANOSECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }
    }

    private void closeQuietly() {
        try {
            if (browser != null && browser.isConnected()) {
                browser.close();
            }
        } catch (RuntimeException e) {
            log.debug("Browser liess sich nicht schliessen: {}", e.toString());
        }
        try {
            if (playwright != null) {
                playwright.close();
            }
        } catch (RuntimeException e) {
            log.debug("Playwright liess sich nicht schliessen: {}", e.toString());
        }
        browser = null;
        playwright = null;
    }
}
