package dev.hendrikhoemberg.webtesthelper.runner;

import dev.hendrikhoemberg.webtesthelper.catalog.SiteService;
import dev.hendrikhoemberg.webtesthelper.crawler.ProbeEvidence;
import dev.hendrikhoemberg.webtesthelper.crawler.SetupProbe;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SetupProbeServiceTest {

    private final SetupProbe probe = mock(SetupProbe.class);
    private final SiteService sites = mock(SiteService.class);
    private final SetupProbeService service = new SetupProbeService(probe, sites);

    @AfterEach
    void shutDown() {
        service.shutdown();
    }

    @Test
    void startWhileAProbeIsRunningRunsItOnlyOnce() throws InterruptedException {
        CountDownLatch probeEntered = new CountDownLatch(1);
        CountDownLatch releaseProbe = new CountDownLatch(1);
        when(probe.probe(any()))
                .thenAnswer(invocation -> {
                    probeEntered.countDown();
                    releaseProbe.await();
                    return evidence();
                });

        service.start(7);
        service.start(7);

        assertThat(probeEntered.await(2, TimeUnit.SECONDS)).isTrue();
        verify(probe, times(1)).probe(any());
        releaseProbe.countDown();
        awaitStatus(7, ProbeStatus.FERTIG);
    }

    @Test
    void aThrowingProbeLandsInFailedWithTheMessage() {
        when(probe.probe(any())).thenThrow(new IllegalStateException("Kaputt"));

        service.start(9);

        ProbeState state = awaitStatus(9, ProbeStatus.FEHLGESCHLAGEN);
        assertThat(state.error()).isEqualTo("Kaputt");
        assertThat(state.status()).isEqualTo(ProbeStatus.FEHLGESCHLAGEN);
    }

    @Test
    void stateOfAnUnknownSiteIsEmpty() {
        assertThat(service.stateOf(123)).isEmpty();
    }

    @Test
    void anEntryOlderThanTheTtlIsGoneAfterTheNextStart() {
        SetupProposal stale = proposal();
        service.putStateForTest(5, new ProbeState(ProbeStatus.FERTIG,
                Instant.now().minus(Duration.ofHours(2)), stale, null));
        service.putStateForTest(6, new ProbeState(ProbeStatus.FERTIG, Instant.now(), proposal(), null));
        when(probe.probe(any())).thenReturn(evidence());

        service.start(99);

        assertThat(service.stateOf(5)).isEmpty();
        assertThat(service.stateOf(6)).isPresent();
    }

    @Test
    void clearRemovesTheState() {
        when(probe.probe(any())).thenReturn(evidence());

        service.start(8);
        awaitStatus(8, ProbeStatus.FERTIG);

        service.clear(8);

        assertThat(service.stateOf(8)).isEmpty();
    }

    @Test
    void concurrentStartsRunTheProbeOnlyOnce() throws Exception {
        CountDownLatch probeEntered = new CountDownLatch(1);
        CountDownLatch releaseProbe = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        when(probe.probe(any())).thenAnswer(invocation -> {
            calls.incrementAndGet();
            probeEntered.countDown();
            releaseProbe.await();
            return evidence();
        });

        int threads = 8;
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Future<?>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    go.await();
                    service.start(42);
                    return null;
                }));
            }
            assertThat(ready.await(2, TimeUnit.SECONDS)).isTrue();
            go.countDown();
            for (Future<?> future : futures) {
                future.get(2, TimeUnit.SECONDS);
            }
            assertThat(probeEntered.await(2, TimeUnit.SECONDS)).isTrue();
            releaseProbe.countDown();
            awaitStatus(42, ProbeStatus.FERTIG);
        } finally {
            releaseProbe.countDown();
            pool.shutdownNow();
        }

        assertThat(calls.get()).isEqualTo(1);
    }

    private ProbeState awaitStatus(long siteId, ProbeStatus status) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (System.nanoTime() < deadline) {
            Optional<ProbeState> state = service.stateOf(siteId);
            if (state.isPresent() && state.get().status() == status) {
                return state.get();
            }
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError("Poll unterbrochen", e);
            }
        }
        throw new AssertionError("Kein Status " + status + " für Website " + siteId);
    }

    private static ProbeEvidence evidence() {
        return new ProbeEvidence(true, null,
                List.of("https://example.com/"), List.of(), List.of(), List.of(),
                Set.of(), List.of(), false, false);
    }

    private static SetupProposal proposal() {
        return new SetupProposal(evidence(), List.of());
    }
}
