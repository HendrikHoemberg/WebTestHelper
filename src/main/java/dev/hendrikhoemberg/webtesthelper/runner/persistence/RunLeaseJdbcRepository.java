package dev.hendrikhoemberg.webtesthelper.runner.persistence;

import dev.hendrikhoemberg.webtesthelper.model.RunScope;
import dev.hendrikhoemberg.webtesthelper.model.RunStatus;
import dev.hendrikhoemberg.webtesthelper.model.RunTrigger;
import dev.hendrikhoemberg.webtesthelper.runner.RunLease;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * Lease management for runs. Every statement here is deliberately raw SQL: the semantics
 * depend on {@code FOR UPDATE SKIP LOCKED} and on a partial unique index, neither of which
 * JPA can express.
 */
@Repository
public class RunLeaseJdbcRepository {

    /**
     * Claims the next eligible run.
     *
     * <p>Eligible means QUEUED, or RUNNING with an expired lease — the second case is how
     * a run orphaned by a container crash gets picked back up (spec 14). The NOT EXISTS
     * guard enforces one run per site (spec 5.3) and deliberately does <em>not</em> test
     * {@code lease_expires_at}: a site with a stale RUNNING run must have that run
     * reclaimed rather than a fresh QUEUED one started beside it. The ORDER BY puts stale
     * RUNNING runs first for the same reason.
     *
     * <p>SKIP LOCKED is what lets several workers poll simultaneously without blocking:
     * a row another transaction has locked is passed over instead of waited on.
     */
    private static final String CLAIM_SQL = """
            UPDATE run
               SET status           = 'RUNNING',
                   lease_owner      = ?,
                   lease_expires_at = now() + make_interval(secs => ?),
                   started_at       = COALESCE(started_at, now())
             WHERE id = (
                   SELECT r.id
                     FROM run r
                    WHERE (r.status = 'QUEUED'
                           OR (r.status = 'RUNNING' AND r.lease_expires_at < now()))
                      AND NOT EXISTS (SELECT 1
                                        FROM run other
                                       WHERE other.site_id = r.site_id
                                         AND other.status  = 'RUNNING'
                                         AND other.id     <> r.id)
                    ORDER BY (r.status = 'RUNNING') DESC, r.queued_at, r.id
                    LIMIT 1
                    FOR UPDATE SKIP LOCKED)
         RETURNING id, site_id, scope, trigger_type, lease_expires_at
            """;

    private static final String HEARTBEAT_SQL = """
            UPDATE run
               SET lease_expires_at = now() + make_interval(secs => ?)
             WHERE id = ? AND lease_owner = ? AND status = 'RUNNING'
            """;

    private static final String FINISH_SQL = """
            UPDATE run
               SET status           = ?,
                   finished_at      = now(),
                   lease_owner      = NULL,
                   lease_expires_at = NULL,
                   error_message    = ?
             WHERE id = ? AND lease_owner = ?
            """;

    /**
     * Requeues stale RUNNING runs whose site has no QUEUED run. A site with a QUEUED run
     * must not be requeued into it — that would violate ux_run_single_queued_per_site — so
     * such stale runs are superseded (CANCELLED) by {@link #SWEEP_SUPERSEDE_SQL} instead.
     */
    private static final String SWEEP_REQUEUE_SQL = """
            UPDATE run r
               SET status           = 'QUEUED',
                   lease_owner      = NULL,
                   lease_expires_at = NULL
             WHERE r.status = 'RUNNING'
               AND r.lease_expires_at < now()
               AND NOT EXISTS (SELECT 1
                                 FROM run q
                                WHERE q.site_id = r.site_id
                                  AND q.status = 'QUEUED'
                                  AND q.id <> r.id)
         RETURNING r.id
            """;

    /**
     * Supersedes stale RUNNING runs whose site already has a QUEUED run. CANCELLED rather
     * than requeued (and CANCELLED rather than FAILED: supersession is a cancellation, and
     * FAILED would trigger a failure notification once the notification path lands).
     */
    private static final String SWEEP_SUPERSEDE_SQL = """
            UPDATE run r
               SET status           = 'CANCELLED',
                   finished_at      = now(),
                   error_message    = 'durch neueren Lauf ersetzt',
                   lease_owner      = NULL,
                   lease_expires_at = NULL
             WHERE r.status = 'RUNNING'
               AND r.lease_expires_at < now()
               AND EXISTS (SELECT 1
                             FROM run q
                            WHERE q.site_id = r.site_id
                              AND q.status = 'QUEUED'
                              AND q.id <> r.id)
         RETURNING r.id
            """;

    private static final Logger log = LoggerFactory.getLogger(RunLeaseJdbcRepository.class);

    private static final RowMapper<RunLease> LEASE_MAPPER = (rs, row) -> new RunLease(
            rs.getLong("id"),
            rs.getLong("site_id"),
            RunScope.valueOf(rs.getString("scope")),
            RunTrigger.valueOf(rs.getString("trigger_type")),
            rs.getTimestamp("lease_expires_at").toInstant());

    private final JdbcTemplate jdbc;

    public RunLeaseJdbcRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<RunLease> claimNext(String owner, Duration leaseFor) {
        try {
            return jdbc.query(CLAIM_SQL, LEASE_MAPPER, owner, (double) leaseFor.toSeconds())
                    .stream().findFirst();
        } catch (DuplicateKeyException raceLostToAnotherWorker) {
            // Invariant: the only unique index CLAIM_SQL can violate is
            // ux_run_single_active_per_site (it touches nothing else and it is a single
            // UPDATE, so the only duplicate key possible is another transaction's committed
            // RUNNING row for the same site). Any DuplicateKeyException raised here is
            // therefore the per-site race — nothing to claim right now, the caller polls again.
            // If a future unique constraint is added to `run`, revisit this catch.
            log.debug("Lost the one-run-per-site race while claiming; will retry on next poll", raceLostToAnotherWorker);
            return Optional.empty();
        }
    }

    public boolean heartbeat(long runId, String owner, Duration extendBy) {
        return jdbc.update(HEARTBEAT_SQL, (double) extendBy.toSeconds(), runId, owner) == 1;
    }

    public boolean finish(long runId, String owner, RunStatus status, String errorMessage) {
        return jdbc.update(FINISH_SQL, status.name(), errorMessage, runId, owner) == 1;
    }

    /**
     * Startup and timer sweep: requeue everything whose worker died holding the lease, or
     * cancel it when a queued run already supersedes it (one run at a time per site, spec
     * 5.3, is what makes the two states mutually exclusive).
     *
     * <p>The requeue statement can race with an enqueue committing a QUEUED run for the
     * same site between this sweep's snapshot and its UPDATE (READ COMMITTED). The retry
     * makes that harmless: the racing QUEUED row is visible on the second attempt, so the
     * stale run no longer matches the NOT EXISTS guard and is superseded instead.
     */
    public List<Long> reclaimExpiredLeases() {
        List<Long> requeued = requeueExpiredLeases();
        requeued.addAll(jdbc.queryForList(SWEEP_SUPERSEDE_SQL, Long.class));
        return requeued;
    }

    private List<Long> requeueExpiredLeases() {
        try {
            return jdbc.queryForList(SWEEP_REQUEUE_SQL, Long.class);
        } catch (DuplicateKeyException racingEnqueue) {
            // An enqueue committed a QUEUED run for one of these sites between our snapshot
            // and the UPDATE, violating ux_run_single_queued_per_site. The statement rolled
            // back atomically; retrying once is enough — the affected site now has a visible
            // QUEUED run, so the stale run falls through to the supersede statement.
            log.warn("Requeue collided with a racing enqueue; the stale run will be superseded instead",
                    racingEnqueue);
            return jdbc.queryForList(SWEEP_REQUEUE_SQL, Long.class);
        }
    }
}
