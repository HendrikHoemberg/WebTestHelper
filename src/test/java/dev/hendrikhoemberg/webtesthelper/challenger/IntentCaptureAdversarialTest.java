package dev.hendrikhoemberg.webtesthelper.challenger;

import dev.hendrikhoemberg.webtesthelper.recorder.CapturedEvent;
import dev.hendrikhoemberg.webtesthelper.recorder.IntentCapture;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Empirical stress test harness challenging IntentCapture concurrency and thread-safety (C3).
 */
class IntentCaptureAdversarialTest {

    @Test
    @DisplayName("C3 Stress: 16 producers and 8 consumers running concurrently (16,000 events) yield zero lost events and zero exceptions")
    void highLoadProducersAndConsumersZeroLostEvents() throws Exception {
        IntentCapture capture = IntentCapture.createForTesting();
        int producerCount = 16;
        int eventsPerProducer = 1000;
        int totalExpectedEvents = producerCount * eventsPerProducer;
        int drainerCount = 4;
        int awaiterCount = 4;

        ExecutorService pool = Executors.newFixedThreadPool(producerCount + drainerCount + awaiterCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        AtomicBoolean producersRunning = new AtomicBoolean(true);

        List<CapturedEvent> collectedEvents = Collections.synchronizedList(new ArrayList<>());
        List<Throwable> errors = Collections.synchronizedList(new ArrayList<>());
        List<Future<?>> futures = new ArrayList<>();

        // Drainer threads
        for (int i = 0; i < drainerCount; i++) {
            futures.add(pool.submit(() -> {
                try {
                    startLatch.await();
                    while (producersRunning.get()) {
                        List<CapturedEvent> drained = capture.drain();
                        collectedEvents.addAll(drained);
                        Thread.yield();
                    }
                    collectedEvents.addAll(capture.drain());
                } catch (Throwable t) {
                    errors.add(t);
                }
            }));
        }

        // Awaiter threads (testing awaitEvents and awaitEvent concurrently with drain and record)
        for (int i = 0; i < awaiterCount; i++) {
            final int index = i;
            futures.add(pool.submit(() -> {
                try {
                    startLatch.await();
                    while (producersRunning.get()) {
                        if (index % 2 == 0) {
                            List<CapturedEvent> awaited = capture.awaitEvents(5, Duration.ofMillis(5));
                            collectedEvents.addAll(awaited);
                        } else {
                            List<CapturedEvent> awaited = capture.awaitEvent(CapturedEvent.EventKind.SUBMIT, Duration.ofMillis(5));
                            collectedEvents.addAll(awaited);
                        }
                    }
                    collectedEvents.addAll(capture.drain());
                } catch (Throwable t) {
                    errors.add(t);
                }
            }));
        }

        // Producer threads
        for (int p = 0; p < producerCount; p++) {
            final int producerId = p;
            futures.add(pool.submit(() -> {
                try {
                    startLatch.await();
                    for (int seq = 0; seq < eventsPerProducer; seq++) {
                        CapturedEvent.EventKind kind;
                        if (seq % 10 == 0) {
                            kind = CapturedEvent.EventKind.SUBMIT;
                        } else if (seq % 3 == 0) {
                            kind = CapturedEvent.EventKind.INPUT;
                        } else if (seq % 2 == 0) {
                            kind = CapturedEvent.EventKind.CHANGE;
                        } else {
                            kind = CapturedEvent.EventKind.CLICK;
                        }

                        capture.recordForTesting(new CapturedEvent(
                                kind,
                                "input",
                                "producer-" + producerId + "-seq-" + seq,
                                "test-id-" + producerId,
                                "textbox",
                                "label-" + producerId,
                                "Field " + producerId,
                                "Text " + seq,
                                "val-" + seq,
                                "#field-" + producerId
                        ));
                    }
                } catch (Throwable t) {
                    errors.add(t);
                }
            }));
        }

        // Release the hounds
        startLatch.countDown();

        // Wait for producers to finish
        int producerStartIndex = drainerCount + awaiterCount;
        for (int i = producerStartIndex; i < futures.size(); i++) {
            futures.get(i).get(15, TimeUnit.SECONDS);
        }
        producersRunning.set(false);

        // Wait for consumers to finish
        for (int i = 0; i < producerStartIndex; i++) {
            futures.get(i).get(15, TimeUnit.SECONDS);
        }

        pool.shutdown();
        assertThat(pool.awaitTermination(5, TimeUnit.SECONDS)).isTrue();

        // Final cleanup drain
        collectedEvents.addAll(capture.drain());

        // Assertions
        assertThat(errors).isEmpty();
        assertThat(collectedEvents).hasSize(totalExpectedEvents);

        // Verify per-producer ordering and zero loss
        Map<Integer, List<Integer>> producerSequences = new ConcurrentHashMap<>();
        for (CapturedEvent event : collectedEvents) {
            String id = event.id();
            assertThat(id).startsWith("producer-");
            String[] parts = id.split("-");
            int pId = Integer.parseInt(parts[1]);
            int seq = Integer.parseInt(parts[3]);
            producerSequences.computeIfAbsent(pId, k -> new ArrayList<>()).add(seq);
        }

        List<Integer> expectedSeqs = java.util.stream.IntStream.range(0, eventsPerProducer).boxed().toList();
        for (int p = 0; p < producerCount; p++) {
            List<Integer> seqs = producerSequences.get(p);
            assertThat(seqs)
                    .as("Producer %d should have produced exactly %d events with zero loss", p, eventsPerProducer)
                    .isNotNull()
                    .hasSize(eventsPerProducer)
                    .containsExactlyInAnyOrderElementsOf(expectedSeqs);
        }
    }

    @Test
    @DisplayName("C3 Concurrency: Single drainer with concurrent producers guarantees strict FIFO arrival order per producer")
    void singleDrainerPreservesStrictFifoOrder() throws Exception {
        IntentCapture capture = IntentCapture.createForTesting();
        int producerCount = 8;
        int eventsPerProducer = 500;
        int totalExpectedEvents = producerCount * eventsPerProducer;

        ExecutorService pool = Executors.newFixedThreadPool(producerCount + 1);
        CountDownLatch startLatch = new CountDownLatch(1);
        AtomicBoolean producersRunning = new AtomicBoolean(true);
        List<CapturedEvent> collectedEvents = new ArrayList<>();
        List<Future<?>> futures = new ArrayList<>();

        // Single drainer thread
        futures.add(pool.submit(() -> {
            try {
                startLatch.await();
                while (producersRunning.get()) {
                    collectedEvents.addAll(capture.drain());
                    Thread.yield();
                }
                collectedEvents.addAll(capture.drain());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }));

        // Producer threads
        for (int p = 0; p < producerCount; p++) {
            final int producerId = p;
            futures.add(pool.submit(() -> {
                try {
                    startLatch.await();
                    for (int seq = 0; seq < eventsPerProducer; seq++) {
                        capture.recordForTesting(new CapturedEvent(
                                CapturedEvent.EventKind.CLICK, "button",
                                "p-" + producerId + "-s-" + seq,
                                null, null, null, null, null, null, "#btn"
                        ));
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }));
        }

        startLatch.countDown();

        for (int i = 1; i < futures.size(); i++) {
            futures.get(i).get(10, TimeUnit.SECONDS);
        }
        producersRunning.set(false);
        futures.get(0).get(10, TimeUnit.SECONDS);

        pool.shutdown();
        assertThat(pool.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        collectedEvents.addAll(capture.drain());

        assertThat(collectedEvents).hasSize(totalExpectedEvents);

        Map<Integer, List<Integer>> producerSequences = new HashMap<>();
        for (CapturedEvent event : collectedEvents) {
            String[] parts = event.id().split("-");
            int pId = Integer.parseInt(parts[1]);
            int seq = Integer.parseInt(parts[3]);
            producerSequences.computeIfAbsent(pId, k -> new ArrayList<>()).add(seq);
        }

        for (int p = 0; p < producerCount; p++) {
            List<Integer> seqs = producerSequences.get(p);
            assertThat(seqs).hasSize(eventsPerProducer);
            for (int s = 0; s < eventsPerProducer; s++) {
                assertThat(seqs.get(s)).isEqualTo(s);
            }
        }
    }

    @Test
    @DisplayName("C3 Concurrency: Listener registration, execution, and exception containment during concurrent dispatch")
    void listenersWithExceptionsDoNotPoisonProducersOrEvents() throws Exception {
        IntentCapture capture = IntentCapture.createForTesting();
        int producerCount = 8;
        int eventsPerProducer = 500;
        int totalEvents = producerCount * eventsPerProducer;

        AtomicInteger validListenerCalls = new AtomicInteger(0);
        AtomicInteger failingListenerCalls = new AtomicInteger(0);

        // Faulty listener that throws exceptions
        capture.addListener(event -> {
            failingListenerCalls.incrementAndGet();
            throw new RuntimeException("Simulated listener exception");
        });

        // Valid listener
        capture.addListener(event -> {
            validListenerCalls.incrementAndGet();
        });

        ExecutorService pool = Executors.newFixedThreadPool(producerCount + 2);
        CountDownLatch latch = new CountDownLatch(1);

        List<Future<?>> futures = new ArrayList<>();
        for (int p = 0; p < producerCount; p++) {
            final int pId = p;
            futures.add(pool.submit(() -> {
                try {
                    latch.await();
                    for (int i = 0; i < eventsPerProducer; i++) {
                        capture.recordForTesting(new CapturedEvent(
                                CapturedEvent.EventKind.CLICK, "button", "b-" + pId + "-" + i,
                                null, null, null, null, null, null, "#b"
                        ));
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }));
        }

        // Add dynamically registered listeners during production
        futures.add(pool.submit(() -> {
            try {
                latch.await();
                for (int i = 0; i < 20; i++) {
                    capture.addListener(event -> {});
                    Thread.sleep(2);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }));

        latch.countDown();

        for (Future<?> f : futures) {
            f.get(10, TimeUnit.SECONDS);
        }

        pool.shutdown();
        assertThat(pool.awaitTermination(5, TimeUnit.SECONDS)).isTrue();

        List<CapturedEvent> drained = capture.drain();
        assertThat(drained).hasSize(totalEvents);
        assertThat(validListenerCalls.get()).isEqualTo(totalEvents);
        assertThat(failingListenerCalls.get()).isEqualTo(totalEvents);
    }
}
