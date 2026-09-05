package dev.hendrikhoemberg.webtesthelper.challenger;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.Playwright;
import dev.hendrikhoemberg.webtesthelper.reporting.PdfRenderer;
import dev.hendrikhoemberg.webtesthelper.reporting.PdfReportService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Empirical stress test harness challenging PdfRenderer lifecycle, concurrency,
 * failure recovery, clean shutdown, and architectural isolation from BrowserPool (ARCH-03 / C1 / C2).
 */
@Tag("browser")
@ResourceLock("browser")
class PdfRendererLifecycleStressTest {

    private Browser getInternalBrowser(PdfRenderer renderer) throws Exception {
        Field f = PdfRenderer.class.getDeclaredField("browser");
        f.setAccessible(true);
        return (Browser) f.get(renderer);
    }

    private Playwright getInternalPlaywright(PdfRenderer renderer) throws Exception {
        Field f = PdfRenderer.class.getDeclaredField("playwright");
        f.setAccessible(true);
        return (Playwright) f.get(renderer);
    }

    private ExecutorService getInternalExecutor(PdfRenderer renderer) throws Exception {
        Field f = PdfRenderer.class.getDeclaredField("executor");
        f.setAccessible(true);
        return (ExecutorService) f.get(renderer);
    }

    @Test
    @DisplayName("Architectural isolation: Neither PdfRenderer nor PdfReportService couples to BrowserPool or crawler package")
    void architecturalIsolation_noBrowserPoolCoupling() {
        for (Field f : PdfRenderer.class.getDeclaredFields()) {
            assertThat(f.getType().getName())
                    .as("PdfRenderer must not reference BrowserPool or crawler classes")
                    .doesNotContain("BrowserPool")
                    .doesNotContain("dev.hendrikhoemberg.webtesthelper.crawler");
        }

        for (Field f : PdfReportService.class.getDeclaredFields()) {
            assertThat(f.getType().getName())
                    .as("PdfReportService must not reference BrowserPool or crawler classes")
                    .doesNotContain("BrowserPool")
                    .doesNotContain("dev.hendrikhoemberg.webtesthelper.crawler");
        }
    }

    @Test
    @DisplayName("Rapid concurrent rendering requests execute sequentially on worker without race conditions")
    void concurrentRendering_succeedsWithoutErrors() throws Exception {
        try (PdfRenderer renderer = new PdfRenderer(true)) {
            int concurrentRequests = 5;
            CountDownLatch latch = new CountDownLatch(1);
            List<Future<byte[]>> futures = new ArrayList<>();

            try (ExecutorService pool = Executors.newFixedThreadPool(concurrentRequests)) {
                for (int i = 0; i < concurrentRequests; i++) {
                    final int idx = i;
                    futures.add(pool.submit(() -> {
                        latch.await();
                        String html = "<!DOCTYPE html><html><body><h1>Stress Test #" + idx + "</h1><p>Contention</p></body></html>";
                        return renderer.render(html);
                    }));
                }
                latch.countDown();

                for (Future<byte[]> f : futures) {
                    byte[] pdf = f.get(60, TimeUnit.SECONDS);
                    assertThat(pdf).isNotNull();
                    assertThat(pdf.length).isGreaterThan(100);
                    // Standard PDF file header check
                    String header = new String(Arrays.copyOfRange(pdf, 0, 4), StandardCharsets.US_ASCII);
                    assertThat(header).isEqualTo("%PDF");
                }
            }
        }
    }

    @Test
    @DisplayName("Simulated renderer crash: browser disconnect is detected and automatically recovered on next request")
    void simulatedCrash_autoRecovery() throws Exception {
        try (PdfRenderer renderer = new PdfRenderer(true)) {
            // First render warms up the browser
            byte[] initialPdf = renderer.render("<html><body>Initial Warmup</body></html>");
            assertThat(initialPdf).isNotEmpty();

            Browser originalBrowser = getInternalBrowser(renderer);
            Playwright originalPlaywright = getInternalPlaywright(renderer);
            assertThat(originalBrowser).isNotNull();
            assertThat(originalBrowser.isConnected()).isTrue();

            // Simulate catastrophic browser termination
            originalBrowser.close();
            assertThat(originalBrowser.isConnected()).isFalse();

            // Subsequent render must detect disconnected browser, restart cleanly, and succeed
            byte[] recoveredPdf = renderer.render("<html><body>Post Crash Recovery</body></html>");
            assertThat(recoveredPdf).isNotEmpty();
            assertThat(new String(Arrays.copyOfRange(recoveredPdf, 0, 4), StandardCharsets.US_ASCII))
                    .isEqualTo("%PDF");

            Browser newBrowser = getInternalBrowser(renderer);
            assertThat(newBrowser).isNotNull();
            assertThat(newBrowser).isNotSameAs(originalBrowser);
            assertThat(newBrowser.isConnected()).isTrue();
        }
    }

    @Test
    @DisplayName("Calling thread interruption during render throws IllegalStateException with interrupted cause and restores flag")
    void callingThreadInterruption_handledProperly() throws Exception {
        try (PdfRenderer renderer = new PdfRenderer(true)) {
            // Warm up
            renderer.render("<html><body>Warmup</body></html>");

            AtomicReference<Throwable> thrown = new AtomicReference<>();
            AtomicReference<Boolean> wasInterrupted = new AtomicReference<>();

            Thread caller = new Thread(() -> {
                Thread.currentThread().interrupt(); // Pre-interrupt
                try {
                    renderer.render("<html><body>Interrupt Test</body></html>");
                } catch (Throwable t) {
                    thrown.set(t);
                    wasInterrupted.set(Thread.currentThread().isInterrupted());
                }
            });

            caller.start();
            caller.join(5000);

            assertThat(thrown.get())
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("unterbrochen");
            assertThat(wasInterrupted.get()).isTrue();

            // Renderer remains healthy for subsequent calls
            byte[] normalPdf = renderer.render("<html><body>Normal After Interrupt</body></html>");
            assertThat(normalPdf).isNotEmpty();
        }
    }

    @Test
    @DisplayName("Clean lifecycle: close() terminates worker executor and cleans up Playwright and Browser references")
    void cleanLifecycle_noProcessLeak() throws Exception {
        PdfRenderer renderer = new PdfRenderer(true);
        byte[] pdf = renderer.render("<html><body>Clean Shutdown Check</body></html>");
        assertThat(pdf).isNotEmpty();

        ExecutorService executor = getInternalExecutor(renderer);
        assertThat(executor.isShutdown()).isFalse();

        // Close renderer
        renderer.close();

        assertThat(executor.isShutdown()).isTrue();
        assertThat(executor.isTerminated()).isTrue();
        assertThat(getInternalBrowser(renderer)).isNull();
        assertThat(getInternalPlaywright(renderer)).isNull();
    }
}
