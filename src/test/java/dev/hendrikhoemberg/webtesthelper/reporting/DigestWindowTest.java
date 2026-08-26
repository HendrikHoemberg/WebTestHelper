package dev.hendrikhoemberg.webtesthelper.reporting;

import dev.hendrikhoemberg.webtesthelper.model.RunScope;
import dev.hendrikhoemberg.webtesthelper.model.RunStatus;
import dev.hendrikhoemberg.webtesthelper.model.RunTrigger;
import dev.hendrikhoemberg.webtesthelper.runner.RunSummary;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class DigestWindowTest {

    private static final Duration SETTLE = Duration.ofMinutes(5);
    private static final Duration MAX_WAIT = Duration.ofHours(6);
    private static final Instant NOW = Instant.parse("2026-08-26T10:00:00Z");

    @Test
    void noUndigestedRunsProducesEmptyWindow() {
        Optional<DigestWindow> window = DigestWindow.close(
                RunScope.PULSE, List.of(), false, NOW, SETTLE, MAX_WAIT);

        assertThat(window).isEmpty();
    }

    @Test
    void runInFlightWithoutOverdueRunsProducesEmptyWindow() {
        RunSummary run1 = summary(1L, RunScope.PULSE, RunStatus.COMPLETED, NOW.minus(Duration.ofMinutes(10)));
        Optional<DigestWindow> window = DigestWindow.close(
                RunScope.PULSE, List.of(run1), true, NOW, SETTLE, MAX_WAIT);

        assertThat(window).isEmpty();
    }

    @Test
    void newestFinishWithinSettleDurationProducesEmptyWindow() {
        RunSummary run1 = summary(1L, RunScope.PULSE, RunStatus.COMPLETED, NOW.minus(Duration.ofMinutes(1)));
        Optional<DigestWindow> window = DigestWindow.close(
                RunScope.PULSE, List.of(run1), false, NOW, SETTLE, MAX_WAIT);

        assertThat(window).isEmpty();
    }

    @Test
    @SuppressWarnings("OptionalGetWithoutIsPresent")
    void nothingInFlightAndNewestFinishOlderThanSettleProducesWindowWithAllRuns() {
        RunSummary run1 = summary(1L, RunScope.PULSE, RunStatus.COMPLETED, NOW.minus(Duration.ofMinutes(10)));
        RunSummary run2 = summary(2L, RunScope.PULSE, RunStatus.FAILED, NOW.minus(Duration.ofMinutes(6)));

        Optional<DigestWindow> window = DigestWindow.close(
                RunScope.PULSE, List.of(run1, run2), false, NOW, SETTLE, MAX_WAIT);

        assertThat(window).isPresent();
        DigestWindow dw = window.get();
        assertThat(dw.scope()).isEqualTo(RunScope.PULSE);
        assertThat(dw.closedAt()).isEqualTo(NOW);
        assertThat(dw.runs()).containsExactly(run1, run2);
        assertThat(dw.runIds()).containsExactly(1L, 2L);
    }

    @Test
    @SuppressWarnings("OptionalGetWithoutIsPresent")
    void runInFlightWithOldestFinishOlderThanMaxWaitProducesWindowAnyway() {
        RunSummary oldRun = summary(1L, RunScope.PULSE, RunStatus.COMPLETED, NOW.minus(Duration.ofHours(7)));
        RunSummary freshRun = summary(2L, RunScope.PULSE, RunStatus.COMPLETED, NOW.minus(Duration.ofMinutes(1)));

        Optional<DigestWindow> window = DigestWindow.close(
                RunScope.PULSE, List.of(oldRun, freshRun), true, NOW, SETTLE, MAX_WAIT);

        assertThat(window).isPresent();
        DigestWindow dw = window.get();
        assertThat(dw.scope()).isEqualTo(RunScope.PULSE);
        assertThat(dw.closedAt()).isEqualTo(NOW);
        assertThat(dw.runs()).containsExactly(oldRun, freshRun);
        assertThat(dw.runIds()).containsExactly(1L, 2L);
    }

    private static RunSummary summary(long id, RunScope scope, RunStatus status, Instant finishedAt) {
        return new RunSummary(
                id,
                100L,
                status,
                RunTrigger.SCHEDULED,
                scope,
                finishedAt.minus(Duration.ofMinutes(5)),
                finishedAt.minus(Duration.ofMinutes(4)),
                finishedAt,
                10,
                0,
                0,
                0,
                0,
                false,
                null,
                false,
                null,
                Set.of()
        );
    }
}
