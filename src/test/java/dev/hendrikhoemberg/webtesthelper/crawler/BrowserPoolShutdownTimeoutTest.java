package dev.hendrikhoemberg.webtesthelper.crawler;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class BrowserPoolShutdownTimeoutTest {

    @Test
    void workerCloseTimesOutAndForcesShutdownWhenTaskIsBlocked() throws Exception {
        BrowserPool.Worker worker = new BrowserPool.Worker(0, true, false);
        ExecutorService blocker = Executors.newSingleThreadExecutor();
        CountDownLatch started = new CountDownLatch(1);

        blocker.submit(() -> {
            try {
                worker.call(browser -> {
                    started.countDown();
                    try {
                        Thread.sleep(10_000);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    return null;
                });
            } catch (Exception ignored) {
            }
        });

        assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();

        long start = System.currentTimeMillis();
        worker.close(200, TimeUnit.MILLISECONDS);
        long elapsed = System.currentTimeMillis() - start;

        assertThat(elapsed).isLessThan(2000);

        blocker.shutdownNow();
    }
}
