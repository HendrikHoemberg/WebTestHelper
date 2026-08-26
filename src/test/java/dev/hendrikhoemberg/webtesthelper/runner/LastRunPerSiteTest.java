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
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class LastRunPerSiteTest extends AbstractPostgresTest {

    @Autowired
    RunService runService;

    @Autowired
    RunRepository runs;

    @Autowired
    JdbcTemplate jdbc;

    private long siteA;
    private long siteB;

    @BeforeEach
    void resetTables() {
        jdbc.update("DELETE FROM run");
        jdbc.update("DELETE FROM site_check_setting");
        jdbc.update("DELETE FROM site");
        siteA = insertSite("https://a.example.com/");
        siteB = insertSite("https://b.example.com/");
    }

    private long insertSite(String baseUrl) {
        return jdbc.queryForObject(
                "INSERT INTO site (name, base_url) VALUES (?, ?) RETURNING id",
                Long.class, baseUrl, baseUrl);
    }

    private long insertRun(long siteId, RunStatus status, Instant queuedAt,
                           Instant finishedAt, boolean partialCoverage) {
        RunEntity run = new RunEntity();
        run.setSiteId(siteId);
        run.setTriggerType(RunTrigger.MANUAL);
        run.setScope(RunScope.FULL);
        run.setStatus(status);
        run.setQueuedAt(queuedAt);
        run.setFinishedAt(finishedAt);
        run.setPartialCoverage(partialCoverage);
        return runs.save(run).getId();
    }

    @Test
    void mapsTheNewestTerminalRunEvenWhenAnOlderOneSucceeded() {
        long oldCompleted = insertRun(siteA, RunStatus.COMPLETED,
                Instant.parse("2026-08-20T03:00:00Z"), Instant.parse("2026-08-20T03:05:00Z"), false);
        long recentFailed = insertRun(siteA, RunStatus.FAILED,
                Instant.parse("2026-08-21T03:00:00Z"), Instant.parse("2026-08-21T03:04:00Z"), true);

        Map<Long, LastRun> bySite = runService.lastTerminalPerSite();

        assertThat(bySite).containsOnlyKeys(siteA);
        assertThat(bySite.get(siteA).runId()).isEqualTo(recentFailed);
        assertThat(bySite.get(siteA).status()).isEqualTo(RunStatus.FAILED);
        assertThat(bySite.get(siteA).finishedAt()).isEqualTo(Instant.parse("2026-08-21T03:04:00Z"));
        assertThat(bySite.get(siteA).partialCoverage()).isTrue();
        assertThat(oldCompleted).isNotEqualTo(recentFailed);
    }

    @Test
    void fallsBackToTheOlderTerminalRunWhenTheNewestIsRunning() {
        long terminal = insertRun(siteA, RunStatus.COMPLETED,
                Instant.parse("2026-08-20T03:00:00Z"), Instant.parse("2026-08-20T03:05:00Z"), false);
        insertRun(siteA, RunStatus.RUNNING,
                Instant.parse("2026-08-21T03:00:00Z"), null, false);

        assertThat(runService.lastTerminalPerSite().get(siteA).runId()).isEqualTo(terminal);
    }

    @Test
    void aSiteWhoseOnlyRunIsQueuedIsAbsentBecauseNothingHasFinished() {
        insertRun(siteA, RunStatus.QUEUED, Instant.parse("2026-08-21T03:00:00Z"), null, false);

        assertThat(runService.lastTerminalPerSite()).doesNotContainKey(siteA);
    }

    @Test
    void partialCoverageIsCarriedThroughForCompletedRuns() {
        insertRun(siteA, RunStatus.COMPLETED,
                Instant.parse("2026-08-20T03:00:00Z"), Instant.parse("2026-08-20T03:05:00Z"), true);

        assertThat(runService.lastTerminalPerSite().get(siteA).partialCoverage()).isTrue();
    }

    @Test
    void runsInFlightCountsQueuedAndRunningAcrossAllSitesAndScopes() {
        insertRun(siteA, RunStatus.QUEUED, Instant.parse("2026-08-21T03:00:00Z"), null, false);
        insertRun(siteB, RunStatus.RUNNING, Instant.parse("2026-08-21T03:01:00Z"), null, false);

        assertThat(runService.runsInFlight()).isEqualTo(2);
    }
}
