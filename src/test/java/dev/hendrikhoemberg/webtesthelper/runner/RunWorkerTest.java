package dev.hendrikhoemberg.webtesthelper.runner;

import dev.hendrikhoemberg.webtesthelper.model.RunScope;
import dev.hendrikhoemberg.webtesthelper.model.RunStatus;
import dev.hendrikhoemberg.webtesthelper.model.RunTrigger;
import dev.hendrikhoemberg.webtesthelper.runner.persistence.RunLeaseJdbcRepository;
import dev.hendrikhoemberg.webtesthelper.support.AbstractPostgresTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

class RunWorkerTest extends AbstractPostgresTest {

    @Autowired
    RunWorker worker;

    @Autowired
    RunService runs;

    @Autowired
    RunLeaseJdbcRepository leases;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    RunExecutor defaultExecutor;

    private long siteId;

    @BeforeEach
    void setUpSite() {
        worker.withExecutorForTest(defaultExecutor);
        jdbc.update("DELETE FROM run");
        jdbc.update("DELETE FROM site_check_setting");
        jdbc.update("DELETE FROM site");
        siteId = jdbc.queryForObject(
                "INSERT INTO site (name, base_url) VALUES ('Test', 'https://t.example.com/') RETURNING id",
                Long.class);
    }

    @Test
    void aQueuedRunIsClaimedExecutedAndCompleted() {
        long runId = runs.enqueue(siteId, RunTrigger.MANUAL, RunScope.FULL);

        assertThat(worker.workOnce()).isTrue();

        assertThat(jdbc.queryForObject("SELECT status FROM run WHERE id = ?", String.class, runId))
                .isEqualTo("COMPLETED");
        assertThat(jdbc.queryForObject("SELECT lease_owner FROM run WHERE id = ?", String.class, runId))
                .isNull();
        assertThat(jdbc.queryForObject("SELECT finished_at IS NOT NULL FROM run WHERE id = ?",
                Boolean.class, runId)).isTrue();
    }

    @Test
    void aFailedExecutionMarksTheRunFailed() {
        long runId = runs.enqueue(siteId, RunTrigger.MANUAL, RunScope.FULL);
        worker.withExecutorForTest(lease -> { throw new RuntimeException("Kaputt"); });

        assertThat(worker.workOnce()).isTrue();

        assertThat(jdbc.queryForObject("SELECT status FROM run WHERE id = ?", String.class, runId))
                .isEqualTo("FAILED");
        assertThat(jdbc.queryForObject("SELECT error_message FROM run WHERE id = ?", String.class, runId))
                .isEqualTo("Kaputt");
    }

    @Test
    void aRunCancelledMidExecutionIsFinishedAsCancelled() {
        long runId = runs.enqueue(siteId, RunTrigger.MANUAL, RunScope.FULL);
        worker.withExecutorForTest(lease -> {
            // The cancel lands while the run executes.
            jdbc.update("UPDATE run SET status = 'CANCELLED', finished_at = now(), "
                    + "lease_owner = NULL, lease_expires_at = NULL WHERE id = ?", lease.runId());
            throw new RunCancelledException("Lauf " + lease.runId() + " wurde abgebrochen");
        });

        assertThat(worker.workOnce()).isTrue();

        assertThat(jdbc.queryForObject("SELECT status FROM run WHERE id = ?", String.class, runId))
                .isEqualTo("CANCELLED");
        assertThat(jdbc.queryForObject("SELECT error_message FROM run WHERE id = ?", String.class, runId))
                .isNull();
    }

    @Test
    void anEmptyQueueIsANoOp() {
        assertThat(worker.workOnce()).isFalse();
    }

    @Test
    void theRunsLoggingContextIsSetForTheWholeExecution() {
        // Spec 14: structured logging with runId and siteId in the MDC, so one run's complete
        // history is greppable. The crawl sets it for itself; verification, the check passes,
        // re-verification and materialisation all run under this worker and need it too.
        long runId = runs.enqueue(siteId, RunTrigger.MANUAL, RunScope.FULL);
        java.util.Map<String, String> seen = new java.util.HashMap<>();
        worker.withExecutorForTest(lease -> {
            seen.put("runId", org.slf4j.MDC.get("runId"));
            seen.put("siteId", org.slf4j.MDC.get("siteId"));
        });

        assertThat(worker.workOnce()).isTrue();

        assertThat(seen).containsEntry("runId", String.valueOf(runId))
                .containsEntry("siteId", String.valueOf(siteId));
    }

    @Test
    void theLoggingContextIsClearedWhenTheRunIsDone() {
        // The poller thread is reused for the next run; a leftover runId would mislabel it.
        runs.enqueue(siteId, RunTrigger.MANUAL, RunScope.FULL);

        assertThat(worker.workOnce()).isTrue();

        assertThat(org.slf4j.MDC.get("runId")).isNull();
        assertThat(org.slf4j.MDC.get("siteId")).isNull();
    }
}
