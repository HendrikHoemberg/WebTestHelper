package dev.hendrikhoemberg.webtesthelper.reporting.persistence;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

@Repository
public class OutboxClaimJdbcRepository {

    public record ClaimedNotification(
            long id,
            String recipient,
            String subject,
            String bodyHtml,
            String bodyText,
            int attempts
    ) {
    }

    /**
     * Claims due mail and <em>leases</em> it: {@code next_attempt_at} moves out of the due window
     * in the same statement that increments {@code attempts}, so the claim survives the commit.
     *
     * <p>A row lock alone would not: it lasts only as long as the transaction, which would have
     * to stay open for the whole SMTP conversation, and a rollback would then un-record mail that
     * had already reached the relay. Leasing instead is the same shape as a run lease (spec 14) —
     * a dispatcher that dies mid-send leaves the row claimed until the lease lapses, at which
     * point it is retried with its attempt already counted, so {@code maxAttempts} still bounds it.
     */
    private static final String CLAIM_SQL = """
            WITH due AS (
                SELECT id FROM notification
                 WHERE state = 'PENDING' AND next_attempt_at <= now()
                 ORDER BY next_attempt_at
                 LIMIT ? FOR UPDATE SKIP LOCKED
            )
            UPDATE notification n
               SET attempts = n.attempts + 1,
                   next_attempt_at = now() + make_interval(secs => ?)
              FROM due WHERE n.id = due.id
             RETURNING n.id, n.recipient, n.subject, n.body_html, n.body_text, n.attempts
            """;

    /** How long a claimed mail stays out of the due window while its delivery is in flight. */
    private static final Duration CLAIM_LEASE = Duration.ofMinutes(5);

    private static final RowMapper<ClaimedNotification> ROW_MAPPER = (rs, rowNum) -> new ClaimedNotification(
            rs.getLong("id"),
            rs.getString("recipient"),
            rs.getString("subject"),
            rs.getString("body_html"),
            rs.getString("body_text"),
            rs.getInt("attempts")
    );

    private final JdbcTemplate jdbc;

    public OutboxClaimJdbcRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<ClaimedNotification> claimDue(int limit) {
        return jdbc.query(CLAIM_SQL, ROW_MAPPER, limit, CLAIM_LEASE.toSeconds());
    }

    public void markSent(long id, Instant sentAt) {
        jdbc.update(
                "UPDATE notification SET state = 'SENT', sent_at = ?, last_error = NULL WHERE id = ?",
                Timestamp.from(sentAt),
                id
        );
    }

    public void markRetry(long id, Instant nextAttemptAt, String lastError) {
        jdbc.update(
                "UPDATE notification SET next_attempt_at = ?, last_error = ? WHERE id = ?",
                Timestamp.from(nextAttemptAt),
                lastError,
                id
        );
    }

    public void markFailed(long id, String lastError) {
        jdbc.update(
                "UPDATE notification SET state = 'FAILED', last_error = ? WHERE id = ?",
                lastError,
                id
        );
    }
}
