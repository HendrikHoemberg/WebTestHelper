package dev.hendrikhoemberg.webtesthelper.reporting;

import dev.hendrikhoemberg.webtesthelper.catalog.AppSettings;
import dev.hendrikhoemberg.webtesthelper.reporting.persistence.NotificationEntity;
import dev.hendrikhoemberg.webtesthelper.reporting.persistence.NotificationRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Service
public class OutboxService {

    private final NotificationRepository notificationRepository;
    private final Notifier notifier;
    private final AppSettings appSettings;
    private final ReportingProperties properties;
    private final TransactionTemplate transactionTemplate;

    @org.springframework.beans.factory.annotation.Autowired
    public OutboxService(
            NotificationRepository notificationRepository,
            Notifier notifier,
            AppSettings appSettings,
            ReportingProperties properties,
            PlatformTransactionManager transactionManager
    ) {
        this.notificationRepository = notificationRepository;
        this.notifier = notifier;
        this.appSettings = appSettings;
        this.properties = properties;
        this.transactionTemplate = transactionManager != null ? new TransactionTemplate(transactionManager) : null;
    }

    public OutboxService(
            NotificationRepository notificationRepository,
            Notifier notifier,
            AppSettings appSettings,
            ReportingProperties properties
    ) {
        this(notificationRepository, notifier, appSettings, properties, null);
    }

    private void executeInTransaction(Runnable action) {
        if (transactionTemplate != null) {
            transactionTemplate.executeWithoutResult(status -> action.run());
        } else {
            action.run();
        }
    }

    private void recordSuccess(long id) {
        executeInTransaction(() -> {
            notificationRepository.findById(id).ifPresent(entity -> {
                entity.setState(NotificationState.SENT);
                entity.setSentAt(Instant.now());
                entity.setLastError(null);
                notificationRepository.save(entity);
            });
        });
    }

    private void recordFailure(long id, int attempt, String error) {
        executeInTransaction(() -> {
            notificationRepository.findById(id).ifPresent(entity -> {
                entity.setAttempts(attempt);
                entity.setLastError(error);
                int maxAttempts = (properties != null) ? properties.maxAttempts() : 5;
                if (attempt >= maxAttempts) {
                    entity.setState(NotificationState.FAILED);
                } else {
                    Duration wait = calculateBackoff(attempt);
                    entity.setNextAttemptAt(Instant.now().plus(wait));
                }
                notificationRepository.save(entity);
            });
        });
    }

    private void resetToPending(long id) {
        executeInTransaction(() -> {
            notificationRepository.findById(id).ifPresent(entity -> {
                entity.setState(NotificationState.PENDING);
                entity.setNextAttemptAt(Instant.now());
                entity.setLastError(null);
                notificationRepository.save(entity);
            });
        });
    }

    @Transactional
    public long enqueue(OutboundMail mail) {
        NotificationEntity entity = new NotificationEntity(
                mail.recipient(),
                mail.subject(),
                mail.html(),
                mail.text()
        );
        entity = notificationRepository.save(entity);
        return entity.getId();
    }

    public DeliveryResult sendNow(long id) {
        NotificationEntity entity = notificationRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Notification not found: " + id));

        if (entity.getState() == NotificationState.SENT) {
            return DeliveryResult.successful();
        }

        int attempt = entity.getAttempts() + 1;

        String targetRecipient = appSettings.redirectAllMailTo().orElse(entity.getRecipient());
        OutboundMail mail = new OutboundMail(
                targetRecipient,
                entity.getSubject(),
                entity.getBodyHtml(),
                entity.getBodyText()
        );

        try {
            notifier.deliver(mail);
            recordSuccess(id);
            return DeliveryResult.successful();
        } catch (Exception e) {
            String error = (e.getMessage() != null && !e.getMessage().isBlank()) ? e.getMessage() : e.toString();
            recordFailure(id, attempt, error);
            return DeliveryResult.failed(error);
        }
    }

    @Transactional(readOnly = true)
    public List<OutboxEntry> recent(int limit) {
        return notificationRepository.findByOrderByCreatedAtDesc(PageRequest.of(0, limit))
                .stream()
                .map(this::toEntry)
                .toList();
    }

    /** Backlog: mails accepted but not yet delivered, whatever their next attempt is (spec 14). */
    @Transactional(readOnly = true)
    public int backlogCount() {
        return notificationRepository.countByState(NotificationState.PENDING);
    }

    @Transactional(readOnly = true)
    public int failedCount() {
        return notificationRepository.countByState(NotificationState.FAILED);
    }

    @Transactional(readOnly = true)
    public Optional<String> lastError() {
        return notificationRepository.findFirstLastErrorByState(NotificationState.FAILED);
    }

    public DeliveryResult retry(long id) {
        resetToPending(id);
        return sendNow(id);
    }

    public int retryAllFailed() {
        List<Long> failedIds = notificationRepository.findByState(NotificationState.FAILED)
                .stream()
                .map(NotificationEntity::getId)
                .toList();
        int count = 0;
        for (Long id : failedIds) {
            resetToPending(id);
            sendNow(id);
            count++;
        }
        return count;
    }

    @Transactional
    public void delete(long id) {
        notificationRepository.deleteById(id);
    }

    @Transactional
    public int deleteAllFailed() {
        List<NotificationEntity> failed = notificationRepository.findByState(NotificationState.FAILED);
        int count = failed.size();
        notificationRepository.deleteAll(failed);
        return count;
    }

    @Transactional(readOnly = true)
    public Optional<OutboxDetail> findDetail(long id) {
        return notificationRepository.findById(id)
                .map(e -> new OutboxDetail(
                        e.getId(),
                        e.getRecipient(),
                        e.getSubject(),
                        e.getState(),
                        e.getAttempts(),
                        e.getCreatedAt(),
                        e.getSentAt(),
                        e.getNextAttemptAt(),
                        e.getLastError(),
                        e.getBodyHtml(),
                        e.getBodyText()
                ));
    }

    public static Duration calculateBackoff(int attempts) {
        Duration wait = Duration.ofMinutes(1).multipliedBy(1L << Math.min(Math.max(attempts - 1, 0), 6));
        if (wait.compareTo(Duration.ofHours(1)) > 0) {
            wait = Duration.ofHours(1);
        }
        return wait;
    }

    private OutboxEntry toEntry(NotificationEntity e) {
        return new OutboxEntry(
                e.getId(),
                e.getRecipient(),
                e.getSubject(),
                e.getState(),
                e.getAttempts(),
                e.getCreatedAt(),
                e.getSentAt(),
                e.getNextAttemptAt(),
                e.getLastError()
        );
    }
}
