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

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

class RunLeaseJdbcRepositoryTest extends AbstractPostgresTest {

    @Autowired
    RunLeaseJdbcRepository leases;

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

    private long queueRun(long siteId) {
        return jdbc.queryForObject("""
                INSERT INTO run (site_id, trigger_type, scope, status)
                VALUES (?, 'MANUAL', 'FULL', 'QUEUED') RETURNING id
                """, Long.class, siteId);
    }

    @Test
    void claimsAQueuedRunAndMarksItRunning() {
        long runId = queueRun(siteA);

        RunLease lease = leases.claimNext("worker-1", Duration.ofMinutes(5)).orElseThrow();

        assertThat(lease.runId()).isEqualTo(runId);
        assertThat(lease.siteId()).isEqualTo(siteA);
        assertThat(lease.scope()).isEqualTo(RunScope.FULL);
        assertThat(lease.trigger()).isEqualTo(RunTrigger.MANUAL);
        assertThat(jdbc.queryForObject("SELECT status FROM run WHERE id = ?", String.class, runId))
                .isEqualTo("RUNNING");
        assertThat(jdbc.queryForObject("SELECT started_at IS NOT NULL FROM run WHERE id = ?",
                Boolean.class, runId)).isTrue();
    }

    @Test
    void returnsEmptyWhenNothingIsQueued() {
        assertThat(leases.claimNext("worker-1", Duration.ofMinutes(5))).isEmpty();
    }

    @Test
    void twoWorkersNeverClaimTheSameRun() throws Exception {
        long runA = queueRun(siteA);
        long runB = queueRun(siteB);

        List<Optional<RunLease>> results = inParallel(8,
                () -> leases.claimNext("worker-" + Thread.currentThread().threadId(), Duration.ofMinutes(5)));

        List<Long> claimed = results.stream().flatMap(Optional::stream).map(RunLease::runId).toList();
        assertThat(claimed).containsExactlyInAnyOrder(runA, runB);
    }

    @Test
    void parallelClaimsOfASingleQueuedRunYieldExactlyOneClaim() throws Exception {
        // The queued backstop (ux_run_single_queued_per_site) means a site can only ever have
        // a single QUEUED run, so the interesting race is several workers converging on that
        // one row: SKIP LOCKED hands it to exactly one, and the RUNNING backstop guarantees
        // at most one RUNNING row survives.
        queueRun(siteA);

        List<Optional<RunLease>> results = inParallel(6,
                () -> leases.claimNext("worker-" + Thread.currentThread().threadId(), Duration.ofMinutes(5)));

        assertThat(results.stream().flatMap(Optional::stream)).hasSize(1);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM run WHERE site_id = ? AND status = 'RUNNING'", Integer.class, siteA))
                .isEqualTo(1);
    }

    @Test
    void anExpiredLeaseIsReclaimedByTheNextClaim() {
        long runId = queueRun(siteA);
        leases.claimNext("dead-worker", Duration.ofMinutes(5)).orElseThrow();
        jdbc.update("UPDATE run SET lease_expires_at = now() - interval '1 minute' WHERE id = ?", runId);

        RunLease reclaimed = leases.claimNext("live-worker", Duration.ofMinutes(5)).orElseThrow();

        assertThat(reclaimed.runId()).isEqualTo(runId);
        assertThat(jdbc.queryForObject("SELECT lease_owner FROM run WHERE id = ?", String.class, runId))
                .isEqualTo("live-worker");
    }

    @Test
    void aQueuedRunIsNotClaimedWhileTheSameSiteHasAStaleRunningRun() {
        long stale = queueRun(siteA);
        leases.claimNext("dead-worker", Duration.ofMinutes(5)).orElseThrow();
        jdbc.update("UPDATE run SET lease_expires_at = now() - interval '1 minute' WHERE id = ?", stale);
        long queued = queueRun(siteA);

        RunLease lease = leases.claimNext("live-worker", Duration.ofMinutes(5)).orElseThrow();

        assertThat(lease.runId()).isEqualTo(stale).isNotEqualTo(queued);
    }

    @Test
    void aStaleRunningRunPlusAQueuedRunNeverBothBecomeRunning() throws Exception {
        long stale = queueRun(siteA);
        leases.claimNext("worker-1", Duration.ofMinutes(5)).orElseThrow();
        jdbc.update("UPDATE run SET lease_expires_at = now() - interval '1 minute' WHERE id = ?", stale);
        long queued = queueRun(siteA);

        List<Optional<RunLease>> results = inParallel(6,
                () -> leases.claimNext("worker-" + Thread.currentThread().threadId(), Duration.ofMinutes(5)));

        // At most one attempt claims anything — the index makes the losing UPDATE fail with a
        // DuplicateKeyException (swallowed) so the stale and queued runs can never both go RUNNING.
        List<RunLease> claimed = results.stream().flatMap(Optional::stream).toList();
        assertThat(claimed).hasSize(1);
        assertThat(claimed.get(0).runId()).isEqualTo(stale);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM run WHERE site_id = ? AND status = 'RUNNING'", Integer.class, siteA))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT status FROM run WHERE id = ?", String.class, queued)).isEqualTo("QUEUED");
    }

    @Test
    void heartbeatExtendsTheLeaseOnlyForTheOwner() {
        long runId = queueRun(siteA);
        leases.claimNext("worker-1", Duration.ofSeconds(30)).orElseThrow();

        assertThat(leases.heartbeat(runId, "worker-2", Duration.ofMinutes(5))).isFalse();
        assertThat(leases.heartbeat(runId, "worker-1", Duration.ofMinutes(5))).isTrue();
        assertThat(jdbc.queryForObject(
                "SELECT lease_expires_at > now() + interval '2 minutes' FROM run WHERE id = ?",
                Boolean.class, runId)).isTrue();
    }

    @Test
    void finishClearsTheLeaseAndRecordsTheOutcome() {
        long runId = queueRun(siteA);
        leases.claimNext("worker-1", Duration.ofMinutes(5)).orElseThrow();

        assertThat(leases.finish(runId, "worker-1", RunStatus.FAILED, "Browser gestorben")).isTrue();

        assertThat(jdbc.queryForObject("SELECT status FROM run WHERE id = ?", String.class, runId))
                .isEqualTo("FAILED");
        assertThat(jdbc.queryForObject("SELECT lease_owner FROM run WHERE id = ?", String.class, runId))
                .isNull();
        assertThat(jdbc.queryForObject("SELECT error_message FROM run WHERE id = ?", String.class, runId))
                .isEqualTo("Browser gestorben");
    }

    @Test
    void theStartupSweepRequeuesEveryExpiredLease() {
        long runId = queueRun(siteA);
        leases.claimNext("dead-worker", Duration.ofMinutes(5)).orElseThrow();
        jdbc.update("UPDATE run SET lease_expires_at = now() - interval '1 minute' WHERE id = ?", runId);

        assertThat(leases.reclaimExpiredLeases()).containsExactly(runId);
        assertThat(jdbc.queryForObject("SELECT status FROM run WHERE id = ?", String.class, runId))
                .isEqualTo("QUEUED");
    }

    @Test
    void theSweepResolvesAStaleRunWhenAQueuedOneExists() {
        long stale = queueRun(siteA);
        leases.claimNext("worker-1", Duration.ofMinutes(5)).orElseThrow();
        jdbc.update("UPDATE run SET lease_expires_at = now() - interval '1 minute' WHERE id = ?", stale);
        long queued = queueRun(siteA);

        assertThat(leases.reclaimExpiredLeases()).containsExactly(stale);
        assertThat(jdbc.queryForObject("SELECT status FROM run WHERE id = ?", String.class, stale))
                .isEqualTo("FAILED");
        assertThat(jdbc.queryForObject("SELECT error_message FROM run WHERE id = ?", String.class, stale))
                .isEqualTo("durch neueren Lauf ersetzt");
        assertThat(jdbc.queryForObject("SELECT status FROM run WHERE id = ?", String.class, queued))
                .isEqualTo("QUEUED");

        assertThat(leases.claimNext("worker-2", Duration.ofMinutes(5)).orElseThrow().runId())
                .isEqualTo(queued);
    }

    @Test
    void aDeposedOwnerCannotHeartbeatOrFinishAfterTheLeaseWasReclaimed() {
        long runId = queueRun(siteA);
        leases.claimNext("worker-1", Duration.ofMinutes(5)).orElseThrow();
        jdbc.update("UPDATE run SET lease_expires_at = now() - interval '1 minute' WHERE id = ?", runId);
        leases.claimNext("worker-2", Duration.ofMinutes(5)).orElseThrow();

        assertThat(leases.heartbeat(runId, "worker-1", Duration.ofMinutes(5))).isFalse();
        assertThat(leases.finish(runId, "worker-1", RunStatus.COMPLETED, null)).isFalse();

        assertThat(jdbc.queryForObject("SELECT status FROM run WHERE id = ?", String.class, runId))
                .isEqualTo("RUNNING");
        assertThat(jdbc.queryForObject("SELECT lease_owner FROM run WHERE id = ?", String.class, runId))
                .isEqualTo("worker-2");
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
