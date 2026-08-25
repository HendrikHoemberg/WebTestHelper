package dev.hendrikhoemberg.webtesthelper.runner;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RunPollerTest {

    @Test
    void startCallsWorkOnceAtLeastOnce() throws InterruptedException {
        RunWorker worker = mock(RunWorker.class);
        CountDownLatch latch = new CountDownLatch(1);
        when(worker.workOnce()).thenAnswer(inv -> {
            latch.countDown();
            return false;
        });

        RunnerProperties properties = new RunnerProperties(Duration.ofMillis(20), true, 10, 12, true);
        RunPoller poller = new RunPoller(worker, properties);

        poller.start();
        try {
            boolean called = latch.await(2, TimeUnit.SECONDS);
            assertThat(called).isTrue();
            assertThat(poller.isRunning()).isTrue();
        } finally {
            poller.stop();
        }
    }

    @Test
    void drainsConsecutiveRunsImmediatelyWithoutSleeping() throws InterruptedException {
        RunWorker worker = mock(RunWorker.class);
        CountDownLatch latch = new CountDownLatch(3);
        when(worker.workOnce()).thenAnswer(inv -> {
            long remaining = latch.getCount();
            latch.countDown();
            return remaining > 1;
        });

        // Set a long poll interval to prove that consecutive runs do not wait for the interval
        RunnerProperties properties = new RunnerProperties(Duration.ofSeconds(10), true, 10, 12, true);
        RunPoller poller = new RunPoller(worker, properties);

        poller.start();
        try {
            boolean completedQuickly = latch.await(2, TimeUnit.SECONDS);
            assertThat(completedQuickly).isTrue();
        } finally {
            poller.stop();
        }
    }

    @Test
    void continuesLoopWhenWorkOnceThrows() throws InterruptedException {
        RunWorker worker = mock(RunWorker.class);
        CountDownLatch latch = new CountDownLatch(2);
        AtomicInteger callCount = new AtomicInteger(0);
        when(worker.workOnce()).thenAnswer(inv -> {
            int count = callCount.incrementAndGet();
            latch.countDown();
            if (count == 1) {
                throw new RuntimeException("Simulierter Verarbeitungsfehler");
            }
            return false;
        });

        RunnerProperties properties = new RunnerProperties(Duration.ofMillis(20), true, 10, 12, true);
        RunPoller poller = new RunPoller(worker, properties);

        poller.start();
        try {
            boolean calledTwice = latch.await(2, TimeUnit.SECONDS);
            assertThat(calledTwice).isTrue();
            assertThat(callCount.get()).isGreaterThanOrEqualTo(2);
            assertThat(poller.isRunning()).isTrue();
        } finally {
            poller.stop();
        }
    }

    @Test
    void aPersistentlyThrowingWorkOnceBacksOffInsteadOfSpinning() throws InterruptedException {
        // A database that is down makes workOnce() throw on every call. Retrying with no pause
        // burns a core and floods the log with the same stack trace thousands of times a second.
        RunWorker worker = mock(RunWorker.class);
        AtomicInteger callCount = new AtomicInteger(0);
        when(worker.workOnce()).thenAnswer(inv -> {
            callCount.incrementAndGet();
            throw new IllegalStateException("Datenbank nicht erreichbar");
        });

        RunnerProperties properties = new RunnerProperties(Duration.ofSeconds(10), true, 10, 12, true);
        RunPoller poller = new RunPoller(worker, properties);

        poller.start();
        try {
            Thread.sleep(300);
            assertThat(callCount.get())
                    .as("a failing workOnce must wait the poll interval before retrying")
                    .isLessThanOrEqualTo(2);
        } finally {
            poller.stop();
        }
    }

    @Test
    void stopTerminatesLoopAndNoFurtherCallsHappen() throws InterruptedException {
        RunWorker worker = mock(RunWorker.class);
        CountDownLatch firstCall = new CountDownLatch(1);
        AtomicInteger totalCalls = new AtomicInteger(0);
        when(worker.workOnce()).thenAnswer(inv -> {
            totalCalls.incrementAndGet();
            firstCall.countDown();
            return false;
        });

        RunnerProperties properties = new RunnerProperties(Duration.ofMillis(10), true, 10, 12, true);
        RunPoller poller = new RunPoller(worker, properties);

        poller.start();
        assertThat(firstCall.await(2, TimeUnit.SECONDS)).isTrue();

        poller.stop();
        assertThat(poller.isRunning()).isFalse();

        int snapshot = totalCalls.get();
        Thread.sleep(50);
        assertThat(totalCalls.get()).isEqualTo(snapshot);
    }
}
