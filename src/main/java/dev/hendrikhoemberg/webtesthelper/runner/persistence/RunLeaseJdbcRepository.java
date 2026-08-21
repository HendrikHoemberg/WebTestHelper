package dev.hendrikhoemberg.webtesthelper.runner.persistence;

import dev.hendrikhoemberg.webtesthelper.model.RunScope;
import dev.hendrikhoemberg.webtesthelper.model.RunStatus;
import dev.hendrikhoemberg.webtesthelper.model.RunTrigger;
import dev.hendrikhoemberg.webtesthelper.runner.RunLease;
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

    private static final String RECLAIM_SQL = """
            UPDATE run
               SET status           = 'QUEUED',
                   lease_owner      = NULL,
                   lease_expires_at = NULL
             WHERE status = 'RUNNING' AND lease_expires_at < now()
         RETURNING id
            """;

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
            // ux_run_single_active_per_site fired: another transaction committed a RUNNING
            // run for this site between our NOT EXISTS check and our UPDATE. Nothing to
            // claim right now; the caller polls again.
            return Optional.empty();
        }
    }

    public boolean heartbeat(long runId, String owner, Duration extendBy) {
        return jdbc.update(HEARTBEAT_SQL, (double) extendBy.toSeconds(), runId, owner) == 1;
    }

    public boolean finish(long runId, String owner, RunStatus status, String errorMessage) {
        return jdbc.update(FINISH_SQL, status.name(), errorMessage, runId, owner) == 1;
    }

    /** Startup and timer sweep: requeue everything whose worker died holding the lease. */
    public List<Long> reclaimExpiredLeases() {
        return jdbc.queryForList(RECLAIM_SQL, Long.class);
    }
}
