package dev.hendrikhoemberg.webtesthelper.scheduling.persistence;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * The schedule claim: a compare-and-set lease, in the same family as the run lease and the
 * outbox claim. {@code next_fire_at} is both the "was this occurrence taken?" predicate and the
 * value being replaced, so two instances polling the same due set can never enqueue the same
 * occurrence twice.
 *
 * <p>Written out as raw SQL because the predicate is the whole concurrency argument: the
 * {@code WHERE id = ? AND next_fire_at = ?} makes the update succeed for exactly one of any
 * number of competing ticks, no {@code SELECT ... FOR UPDATE} required. A second claimant whose
 * expected value is stale silently updates zero rows and must skip the occurrence.
 */
@Repository
public class ScheduleClaimJdbcRepository {

    private static final String CLAIM_SQL = """
            UPDATE schedule
               SET next_fire_at = ?, last_fired_at = ?, updated_at = now(), version = version + 1
             WHERE id = ? AND next_fire_at = ?
            """;

    private final JdbcTemplate jdbc;

    public ScheduleClaimJdbcRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Claims the occurrence {@code expectedNextFire} by advancing it to {@code newNextFire} and
     * stamping {@code firedAt}. Returns {@code true} only if this caller advanced the row; a
     * {@code false} means another instance (or this one, in a previous tick still in flight)
     * had already claimed the occurrence, and the caller must not enqueue it.
     */
    public boolean claim(long scheduleId, Instant expectedNextFire, Instant newNextFire, Instant firedAt) {
        return jdbc.update(CLAIM_SQL, ts(newNextFire), ts(firedAt), scheduleId, ts(expectedNextFire)) == 1;
    }

    private Timestamp ts(Instant instant) {
        return Timestamp.from(instant.truncatedTo(ChronoUnit.MICROS));
    }
}
