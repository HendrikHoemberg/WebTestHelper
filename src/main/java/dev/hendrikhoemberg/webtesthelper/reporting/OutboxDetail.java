package dev.hendrikhoemberg.webtesthelper.reporting;

import java.time.Instant;

public record OutboxDetail(
        long id,
        String recipient,
        String subject,
        NotificationState state,
        int attempts,
        Instant createdAt,
        Instant sentAt,
        Instant nextAttemptAt,
        String lastError,
        String bodyHtml,
        String bodyText
) {
}
