package dev.hendrikhoemberg.webtesthelper.runner;

import dev.hendrikhoemberg.webtesthelper.catalog.SiteService;
import dev.hendrikhoemberg.webtesthelper.crawler.ProbeEvidence;
import dev.hendrikhoemberg.webtesthelper.crawler.SetupProbe;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

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
        when(probe.probe(any()))
                .thenAnswer(invocation -> {
                    probeEntered.countDown();
                    new CountDownLatch(1).await();
                    return evidence();
                });

        service.start(7);
        service.start(7);

        assertThat(probeEntered.await(2, TimeUnit.SECONDS)).isTrue();
        verify(probe, times(1)).probe(any());
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
