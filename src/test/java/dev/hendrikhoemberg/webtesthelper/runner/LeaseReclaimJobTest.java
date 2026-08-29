package dev.hendrikhoemberg.webtesthelper.runner;

import dev.hendrikhoemberg.webtesthelper.runner.persistence.RunLeaseJdbcRepository;
import dev.hendrikhoemberg.webtesthelper.support.AbstractPostgresTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class LeaseReclaimJobTest extends AbstractPostgresTest {

    @Autowired
    RunLeaseJdbcRepository leases;

    @Autowired
    JdbcTemplate jdbc;

    private LeaseReclaimJob job;
    private long siteA;

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM run");
        jdbc.update("DELETE FROM site_check_setting");
        jdbc.update("DELETE FROM site");
        siteA = jdbc.queryForObject(
                "INSERT INTO site (name, base_url) VALUES (?, ?) RETURNING id",
                Long.class, "https://a.example.com/", "https://a.example.com/");
        job = new LeaseReclaimJob(leases);
    }

    private long queueRun(long siteId) {
        return jdbc.queryForObject("""
                INSERT INTO run (site_id, trigger_type, scope, status)
                VALUES (?, 'MANUAL', 'FULL', 'QUEUED') RETURNING id
                """, Long.class, siteId);
    }

    @Test
    void reclaimingAnExpiredLeaseRequeuesTheRun() {
        long runId = queueRun(siteA);
        leases.claimNext("dead-worker", Duration.ofMinutes(5)).orElseThrow();
        jdbc.update("UPDATE run SET lease_expires_at = now() - interval '1 minute' WHERE id = ?", runId);

        job.reclaimExpiredLeases();

        assertThat(jdbc.queryForObject("SELECT status FROM run WHERE id = ?", String.class, runId))
                .isEqualTo("QUEUED");
        assertThat(jdbc.queryForObject("SELECT lease_owner FROM run WHERE id = ?", String.class, runId))
                .isNull();
    }

    @Test
    void reclaimingSupersedesAStaleRunWhenAQueuedRunExists() {
        long stale = queueRun(siteA);
        leases.claimNext("worker-1", Duration.ofMinutes(5)).orElseThrow();
        jdbc.update("UPDATE run SET lease_expires_at = now() - interval '1 minute' WHERE id = ?", stale);
        long queued = queueRun(siteA);

        job.reclaimExpiredLeases();

        assertThat(jdbc.queryForObject("SELECT status FROM run WHERE id = ?", String.class, stale))
                .isEqualTo("CANCELLED");
        assertThat(jdbc.queryForObject("SELECT status FROM run WHERE id = ?", String.class, queued))
                .isEqualTo("QUEUED");
    }

    @Test
    void sweepingNothingLeavesTheRunTableUntouched() {
        // No runs exist at all: a sweep with nothing expired must be a quiet no-op.
        job.reclaimExpiredLeases();

        assertThat(jdbc.queryForObject("SELECT count(*) FROM run", Integer.class)).isZero();
    }

    @Test
    void aTransientRepositoryFailureDoesNotStallTheSweep() {
        // scheduleWithFixedDelay suppresses further executions once the task throws, so a single
        // transient DB error must not escape — it is logged and the next tick runs.
        RunLeaseJdbcRepository throwing = mock(RunLeaseJdbcRepository.class);
        when(throwing.reclaimExpiredLeases()).thenThrow(new RuntimeException("Verbindung getrennt"));
        LeaseReclaimJob failing = new LeaseReclaimJob(throwing);

        assertThatCode(() -> failing.reclaimExpiredLeases()).doesNotThrowAnyException();
    }
}
