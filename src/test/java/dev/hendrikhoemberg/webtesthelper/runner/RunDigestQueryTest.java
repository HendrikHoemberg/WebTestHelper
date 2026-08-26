package dev.hendrikhoemberg.webtesthelper.runner;

import dev.hendrikhoemberg.webtesthelper.model.RunScope;
import dev.hendrikhoemberg.webtesthelper.model.RunStatus;
import dev.hendrikhoemberg.webtesthelper.model.RunTrigger;
import dev.hendrikhoemberg.webtesthelper.runner.persistence.RunEntity;
import dev.hendrikhoemberg.webtesthelper.runner.persistence.RunRepository;
import dev.hendrikhoemberg.webtesthelper.support.AbstractPostgresTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RunDigestQueryTest extends AbstractPostgresTest {

    @Autowired
    RunService runService;

    @Autowired
    RunRepository runRepository;

    @Autowired
    JdbcTemplate jdbc;

    private long siteId;

    @BeforeEach
    void resetTables() {
        jdbc.update("DELETE FROM run");
        jdbc.update("DELETE FROM site_check_setting");
        jdbc.update("DELETE FROM site");
        siteId = jdbc.queryForObject(
                "INSERT INTO site (name, base_url) VALUES (?, ?) RETURNING id",
                Long.class, "https://a.example.com/", "https://a.example.com/");
    }

    @Test
    void undigestedReturnsCompletedAndFailedRunsOrderedByFinishedAtAscExcludingCancelledAndOtherScopes() {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);

        // Seed PULSE runs
        long r1 = seedRun(RunScope.PULSE, RunStatus.COMPLETED, now.minusSeconds(200));
        long r2 = seedRun(RunScope.PULSE, RunStatus.FAILED, now.minusSeconds(300));
        long r3 = seedRun(RunScope.PULSE, RunStatus.CANCELLED, now.minusSeconds(100));

        // Seed FULL run
        seedRun(RunScope.FULL, RunStatus.COMPLETED, now.minusSeconds(250));

        List<RunSummary> pulseUndigested = runService.undigested(RunScope.PULSE);

        // r2 finished at -300s, r1 finished at -200s (oldest finished first)
        assertThat(pulseUndigested).extracting(RunSummary::id).containsExactly(r2, r1);
        assertThat(pulseUndigested).extracting(RunSummary::status)
                .containsExactly(RunStatus.FAILED, RunStatus.COMPLETED);
    }

    @Test
    void hasRunsInFlightDetectsQueuedOrRunningRuns() {
        assertThat(runService.hasRunsInFlight(RunScope.PULSE)).isFalse();

        long queuedId = seedRun(RunScope.PULSE, RunStatus.QUEUED, null);
        assertThat(runService.hasRunsInFlight(RunScope.PULSE)).isTrue();
        assertThat(runService.hasRunsInFlight(RunScope.FULL)).isFalse();

        // Change to RUNNING
        RunEntity queued = runRepository.findById(queuedId).orElseThrow();
        queued.setStatus(RunStatus.RUNNING);
        queued = runRepository.save(queued);

        assertThat(runService.hasRunsInFlight(RunScope.PULSE)).isTrue();

        // Change to COMPLETED
        queued.setStatus(RunStatus.COMPLETED);
        queued.setFinishedAt(Instant.now());
        runRepository.save(queued);

        assertThat(runService.hasRunsInFlight(RunScope.PULSE)).isFalse();
    }

    @Test
    void markDigestedUpdatesOnlyNullDigestSentAtAndRemovesRunsFromUndigested() {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        long r1 = seedRun(RunScope.PULSE, RunStatus.COMPLETED, now.minusSeconds(100));
        long r2 = seedRun(RunScope.PULSE, RunStatus.FAILED, now.minusSeconds(50));

        assertThat(runService.undigested(RunScope.PULSE)).hasSize(2);

        int updatedFirst = runService.markDigested(List.of(r1, r2), now);
        assertThat(updatedFirst).isEqualTo(2);

        // Runs leave undigested
        assertThat(runService.undigested(RunScope.PULSE)).isEmpty();

        // Second call on same ids returns 0 because digest_sent_at is no longer NULL
        int updatedSecond = runService.markDigested(List.of(r1, r2), now);
        assertThat(updatedSecond).isZero();
    }

    private long seedRun(RunScope scope, RunStatus status, Instant finishedAt) {
        RunEntity run = new RunEntity();
        run.setSiteId(siteId);
        run.setTriggerType(RunTrigger.SCHEDULED);
        run.setScope(scope);
        run.setStatus(status);
        run.setFinishedAt(finishedAt);
        return runRepository.save(run).getId();
    }
}
