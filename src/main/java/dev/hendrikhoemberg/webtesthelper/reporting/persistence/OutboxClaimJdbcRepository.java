package dev.hendrikhoemberg.webtesthelper.reporting.persistence;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
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

    private static final String CLAIM_SQL = """
            WITH due AS (
                SELECT id FROM notification
                 WHERE state = 'PENDING' AND next_attempt_at <= now()
                 ORDER BY next_attempt_at
                 LIMIT ? FOR UPDATE SKIP LOCKED
            )
            UPDATE notification n SET attempts = n.attempts + 1
              FROM due WHERE n.id = due.id
             RETURNING n.id, n.recipient, n.subject, n.body_html, n.body_text, n.attempts
            """;

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
        return jdbc.query(CLAIM_SQL, ROW_MAPPER, limit);
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
