package dev.hendrikhoemberg.webtesthelper.runner;

import dev.hendrikhoemberg.webtesthelper.findings.FindingService;
import dev.hendrikhoemberg.webtesthelper.model.CheckFinding;
import dev.hendrikhoemberg.webtesthelper.model.CheckType;
import dev.hendrikhoemberg.webtesthelper.model.Evidence;
import dev.hendrikhoemberg.webtesthelper.model.NormalizedUrl;
import dev.hendrikhoemberg.webtesthelper.model.RunCoverage;
import dev.hendrikhoemberg.webtesthelper.model.RunScope;
import dev.hendrikhoemberg.webtesthelper.model.RunTrigger;
import dev.hendrikhoemberg.webtesthelper.model.Severity;
import dev.hendrikhoemberg.webtesthelper.support.AbstractPostgresTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RunServiceTest extends AbstractPostgresTest {

    @Autowired
    RunService runs;

    @Autowired
    FindingService findings;

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
    void theThreeTiersQueueSideBySideForOneSite() {
        // Spec 9 fires pulse, full and deep in the same 03:00 window; on the first Sunday of a
        // month all three land on one site. Deduping them by site would silently drop the deep
        // run, the only tier that submits forms and verifies mail. One run at a time per site
        // (spec 5.3) constrains RUNNING, not QUEUED — claimNext still serialises execution.
        long pulse = runs.enqueue(siteId, RunTrigger.SCHEDULED, RunScope.PULSE);
        long full = runs.enqueue(siteId, RunTrigger.SCHEDULED, RunScope.FULL);
        long deep = runs.enqueue(siteId, RunTrigger.SCHEDULED, RunScope.DEEP);

        assertThat(List.of(pulse, full, deep)).doesNotHaveDuplicates();
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM run WHERE site_id = ? AND status = 'QUEUED'",
                Integer.class, siteId)).isEqualTo(3);
    }

    @Test
    void enqueuingTheSameTierTwiceReusesTheQueuedRun() {
        long first = runs.enqueue(siteId, RunTrigger.MANUAL, RunScope.FULL);

        assertThat(runs.enqueue(siteId, RunTrigger.MANUAL, RunScope.FULL)).isEqualTo(first);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM run WHERE site_id = ? AND status = 'QUEUED'",
                Integer.class, siteId)).isEqualTo(1);
    }

    @Test
    void parallelEnqueuesAllReturnTheSameRunAndLeaveOneQueued() throws Exception {
        int workers = 8;
        List<Long> ids = inParallel(workers,
                () -> runs.enqueue(siteId, RunTrigger.MANUAL, RunScope.FULL));

        assertThat(new HashSet<>(ids)).hasSize(1);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM run WHERE site_id = ? AND status = 'QUEUED'",
                Integer.class, siteId)).isEqualTo(1);
    }

    @Test
    void acceptBaselineStampsBaselineAcceptedAtAndFlipsTheSummary() {
        long runId = jdbc.queryForObject(
                "INSERT INTO run (site_id, status, trigger_type, scope) VALUES (?, 'COMPLETED', 'MANUAL', 'FULL') RETURNING id",
                Long.class, siteId);
        NormalizedUrl page = new NormalizedUrl("https", "a.example.com", 443, "/x", null);
        CheckFinding finding = new CheckFinding(CheckType.DEAD_LINK, Severity.ERROR, "dead:/x",
                page, "m", List.of(), Evidence.NONE);
        findings.record(runId, siteId, List.of(finding),
                RunCoverage.of(RunScope.FULL, RunScope.FULL.checkTypes().stream().map(CheckType::name).toList(),
                        List.of("https://a.example.com/x"), List.of(), false),
                Instant.now().truncatedTo(ChronoUnit.MICROS));

        assertThat(runs.summary(runId).baselineAccepted()).isFalse();

        int moved = runs.acceptBaseline(runId);

        assertThat(moved).isEqualTo(1);
        assertThat(runs.summary(runId).baselineAccepted()).isTrue();
        assertThat(jdbc.queryForObject(
                "SELECT baseline_accepted_at FROM run WHERE id = ?", java.sql.Timestamp.class, runId))
                .isNotNull();
    }

    @Test
    void acceptBaselineOfAnUnknownRunIdRaises() {
        assertThatThrownBy(() -> runs.acceptBaseline(9_999_999L))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private <T> List<T> inParallel(int threads, Callable<T> work) throws Exception {
        try (ExecutorService pool = Executors.newFixedThreadPool(threads)) {
            List<Future<T>> futures = pool.invokeAll(java.util.Collections.nCopies(threads, work));
            List<T> results = new java.util.ArrayList<>();
            for (Future<T> future : futures) {
                results.add(future.get());
            }
            return results;
        }
    }
}