package dev.hendrikhoemberg.webtesthelper.recorder;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

class IntentCaptureConcurrencyTest {

    @Test
    void concurrentRecordAndDrainDoesNotDropEventsOrThrow() throws Exception {
        IntentCapture capture = IntentCapture.createForTesting();
        int producerThreads = 8;
        int eventsPerProducer = 500;
        int totalEvents = producerThreads * eventsPerProducer;

        ExecutorService pool = Executors.newFixedThreadPool(producerThreads + 2);
        CountDownLatch startLatch = new CountDownLatch(1);
        AtomicBoolean producersDone = new AtomicBoolean(false);
        List<CapturedEvent> allDrainedEvents = Collections.synchronizedList(new ArrayList<>());
        List<Future<?>> futures = new ArrayList<>();

        // 2 drainer threads
        for (int i = 0; i < 2; i++) {
            futures.add(pool.submit(() -> {
                try {
                    startLatch.await();
                    while (!producersDone.get()) {
                        allDrainedEvents.addAll(capture.drain());
                        Thread.yield();
                    }
                    allDrainedEvents.addAll(capture.drain());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }));
        }

        // Producer threads
        for (int p = 0; p < producerThreads; p++) {
            final int producerId = p;
            futures.add(pool.submit(() -> {
                try {
                    startLatch.await();
                    for (int i = 0; i < eventsPerProducer; i++) {
                        capture.recordForTesting(new CapturedEvent(
                                CapturedEvent.EventKind.CLICK,
                                "button",
                                "btn-" + producerId + "-" + i,
                                null, null, null, null, "Click", null, "#btn"
                        ));
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }));
        }

        startLatch.countDown();

        // Wait for producers to finish
        for (int i = 2; i < futures.size(); i++) {
            futures.get(i).get(5, TimeUnit.SECONDS);
        }
        producersDone.set(true);

        // Wait for drainers to finish
        futures.get(0).get(5, TimeUnit.SECONDS);
        futures.get(1).get(5, TimeUnit.SECONDS);

        pool.shutdown();
        assertThat(pool.awaitTermination(2, TimeUnit.SECONDS)).isTrue();

        // Final drain in case any events remain
        allDrainedEvents.addAll(capture.drain());

        assertThat(allDrainedEvents).hasSize(totalEvents);
    }
}
