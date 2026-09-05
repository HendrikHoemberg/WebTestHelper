package dev.hendrikhoemberg.webtesthelper.recorder;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class RecorderWorkerShutdownTimeoutTest {

    @Test
    void workerCloseTimesOutAndForcesShutdownWhenTaskIsBlocked() throws Exception {
        RecorderWorker worker = new RecorderWorker(0, true, false);
        ExecutorService blocker = Executors.newSingleThreadExecutor();
        CountDownLatch started = new CountDownLatch(1);

        blocker.submit(() -> {
            try {
                worker.submit(browser -> {
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
