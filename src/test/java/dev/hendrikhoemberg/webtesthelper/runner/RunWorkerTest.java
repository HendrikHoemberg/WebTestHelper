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
    void anEmptyQueueIsANoOp() {
        assertThat(worker.workOnce()).isFalse();
    }
}