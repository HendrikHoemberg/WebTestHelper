package dev.hendrikhoemberg.webtesthelper.recorder;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Empirical adversarial stress tests for RecorderWorker lifecycle and timeout behavior.
 */
class RecorderLifecycleEmpiricalTest {

    @Test
    @DisplayName("C2: RecorderWorker close times out cleanly on stuck task and interrupts thread")
    void recorderWorkerCloseTimesOutCleanly() throws Exception {
        RecorderWorker worker = new RecorderWorker(0, true, false);
        ExecutorService taskRunner = Executors.newSingleThreadExecutor();
        CountDownLatch taskRunning = new CountDownLatch(1);
        CountDownLatch taskInterrupted = new CountDownLatch(1);

        taskRunner.submit(() -> {
            try {
                worker.submit(browser -> {
                    taskRunning.countDown();
                    try {
                        Thread.sleep(10_000);
                    } catch (InterruptedException e) {
                        taskInterrupted.countDown();
                        Thread.currentThread().interrupt();
                    }
                    return null;
                });
            } catch (Exception ignored) {
            }
        });

        assertThat(taskRunning.await(5, TimeUnit.SECONDS)).isTrue();

        long start = System.currentTimeMillis();
        worker.close(200, TimeUnit.MILLISECONDS);
        long elapsed = System.currentTimeMillis() - start;

        assertThat(elapsed)
                .as("Recorder worker close must complete near timeout (200ms) and not block for 10s")
                .isGreaterThanOrEqualTo(180)
                .isLessThan(2000);

        assertThat(taskInterrupted.await(2, TimeUnit.SECONDS))
                .as("Worker task must receive interrupt signal from shutdownNow within 2s")
                .isTrue();

        taskRunner.shutdownNow();
    }

    @Test
    @DisplayName("C2: RecorderWorker close times out cleanly on uninterruptible CPU loop")
    void recorderWorkerCloseTimesOutOnUninterruptibleTask() throws Exception {
        RecorderWorker worker = new RecorderWorker(0, true, false);
        ExecutorService taskRunner = Executors.newSingleThreadExecutor();
        CountDownLatch taskRunning = new CountDownLatch(1);
        AtomicBoolean stopSpinning = new AtomicBoolean(false);

        taskRunner.submit(() -> {
            try {
                worker.submit(browser -> {
                    taskRunning.countDown();
                    while (!stopSpinning.get()) {
                        // CPU spin
                    }
                    return null;
                });
            } catch (Exception ignored) {
            }
        });

        assertThat(taskRunning.await(5, TimeUnit.SECONDS)).isTrue();

        long start = System.currentTimeMillis();
        worker.close(200, TimeUnit.MILLISECONDS);
        long elapsed = System.currentTimeMillis() - start;

        assertThat(elapsed)
                .as("Recorder worker close must return after timeout without deadlocking")
                .isGreaterThanOrEqualTo(180)
                .isLessThan(2000);

        stopSpinning.set(true);
        taskRunner.shutdownNow();
    }

    @Test
    @DisplayName("C2: RecorderWorker thread is daemon thread")
    void recorderWorkerThreadIsDaemon() throws Exception {
        RecorderWorker worker = new RecorderWorker(0, true, false);
        Field threadField = RecorderWorker.class.getDeclaredField("thread");
        threadField.setAccessible(true);
        ExecutorService executor = (ExecutorService) threadField.get(worker);

        CountDownLatch latch = new CountDownLatch(1);
        AtomicBoolean isDaemon = new AtomicBoolean(false);

        executor.submit(() -> {
            isDaemon.set(Thread.currentThread().isDaemon());
            latch.countDown();
        });

        assertThat(latch.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(isDaemon.get())
                .as("Recorder worker thread must be daemon thread")
                .isTrue();

        worker.close(100, TimeUnit.MILLISECONDS);
    }
}
