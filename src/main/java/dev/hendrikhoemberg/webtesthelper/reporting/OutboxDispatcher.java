package dev.hendrikhoemberg.webtesthelper.reporting;

import dev.hendrikhoemberg.webtesthelper.catalog.AppSettings;
import dev.hendrikhoemberg.webtesthelper.reporting.persistence.OutboxClaimJdbcRepository;
import dev.hendrikhoemberg.webtesthelper.reporting.persistence.OutboxClaimJdbcRepository.ClaimedNotification;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Sends what the outbox holds (spec 11.3). One cycle claims a batch, then delivers each mail and
 * records the outcome immediately.
 *
 * <p>Deliberately <em>not</em> transactional. Delivery is an external side effect that cannot be
 * rolled back, so a transaction spanning the sends can only lie: unwinding it would restore rows
 * to {@code PENDING} for mail that had already gone out, and the next cycle would send it twice.
 * Exclusivity comes from the durable lease in
 * {@link OutboxClaimJdbcRepository#claimDue(int)} instead, which is what a rollback cannot undo.
 */
@Component
public class OutboxDispatcher {

    private final OutboxClaimJdbcRepository claimRepository;
    private final Notifier notifier;
    private final AppSettings appSettings;
    private final ReportingProperties properties;

    public OutboxDispatcher(
            OutboxClaimJdbcRepository claimRepository,
            Notifier notifier,
            AppSettings appSettings,
            ReportingProperties properties
    ) {
        this.claimRepository = claimRepository;
        this.notifier = notifier;
        this.appSettings = appSettings;
        this.properties = properties;
    }

    public int dispatchCycle() {
        List<ClaimedNotification> due = claimRepository.claimDue(20);
        for (ClaimedNotification item : due) {
            String targetRecipient = appSettings.redirectAllMailTo().orElse(item.recipient());
            OutboundMail mail = new OutboundMail(
                    targetRecipient,
                    item.subject(),
                    item.bodyHtml(),
                    item.bodyText()
            );

            try {
                notifier.deliver(mail);
                claimRepository.markSent(item.id(), Instant.now());
            } catch (Exception e) {
                String error = (e.getMessage() != null && !e.getMessage().isBlank()) ? e.getMessage() : e.toString();
                int maxAttempts = (properties != null) ? properties.maxAttempts() : 5;
                if (item.attempts() >= maxAttempts) {
                    claimRepository.markFailed(item.id(), error);
                } else {
                    Duration wait = OutboxService.calculateBackoff(item.attempts());
                    claimRepository.markRetry(item.id(), Instant.now().plus(wait), error);
                }
            }
        }
        return due.size();
    }
}
