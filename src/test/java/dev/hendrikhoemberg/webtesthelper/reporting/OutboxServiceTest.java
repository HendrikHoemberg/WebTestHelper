package dev.hendrikhoemberg.webtesthelper.reporting;

import dev.hendrikhoemberg.webtesthelper.reporting.persistence.NotificationEntity;
import dev.hendrikhoemberg.webtesthelper.reporting.persistence.NotificationRepository;
import dev.hendrikhoemberg.webtesthelper.support.AbstractPostgresTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class OutboxServiceTest extends AbstractPostgresTest {

    @Autowired
    OutboxService outboxService;

    @Autowired
    NotificationRepository notificationRepository;

    @Autowired
    JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        jdbc.execute("DELETE FROM notification");
    }

    @Test
    void enqueueCreatesPendingNotificationWithZeroAttemptsAndImmediateNextAttempt() {
        OutboundMail mail = new OutboundMail(
                "user@example.com",
                "Test Subject",
                "<p>HTML</p>",
                "Text"
        );

        long id = outboxService.enqueue(mail);

        assertThat(id).isGreaterThan(0);

        NotificationEntity entity = notificationRepository.findById(id).orElseThrow();
        assertThat(entity.getRecipient()).isEqualTo("user@example.com");
        assertThat(entity.getSubject()).isEqualTo("Test Subject");
        assertThat(entity.getBodyHtml()).isEqualTo("<p>HTML</p>");
        assertThat(entity.getBodyText()).isEqualTo("Text");
        assertThat(entity.getState()).isEqualTo(NotificationState.PENDING);
        assertThat(entity.getAttempts()).isZero();
        assertThat(entity.getNextAttemptAt()).isNotNull().isBeforeOrEqualTo(Instant.now());
        assertThat(entity.getCreatedAt()).isNotNull();
        assertThat(entity.getSentAt()).isNull();
        assertThat(entity.getLastError()).isNull();
    }

    @Test
    void recentReturnsEntriesOrderedByCreatedAtDesc() throws InterruptedException {
        long id1 = outboxService.enqueue(new OutboundMail("u1@example.com", "S1", "<p>1</p>", "1"));
        Thread.sleep(10);
        long id2 = outboxService.enqueue(new OutboundMail("u2@example.com", "S2", "<p>2</p>", "2"));
        Thread.sleep(10);
        long id3 = outboxService.enqueue(new OutboundMail("u3@example.com", "S3", "<p>3</p>", "3"));

        List<OutboxEntry> entries = outboxService.recent(20);

        assertThat(entries).hasSize(3);
        assertThat(entries.get(0).id()).isEqualTo(id3);
        assertThat(entries.get(1).id()).isEqualTo(id2);
        assertThat(entries.get(2).id()).isEqualTo(id1);

        assertThat(entries.get(0).recipient()).isEqualTo("u3@example.com");
        assertThat(entries.get(0).state()).isEqualTo(NotificationState.PENDING);
    }

    @Test
    void failedCountCountsOnlyFailedNotificationsAndLastErrorReturnsRecentFailedError() {
        long pId = outboxService.enqueue(new OutboundMail("pending@example.com", "S1", "<p>1</p>", "1"));
        long sId = outboxService.enqueue(new OutboundMail("sent@example.com", "S2", "<p>2</p>", "2"));
        long fId1 = outboxService.enqueue(new OutboundMail("failed1@example.com", "S3", "<p>3</p>", "3"));
        long fId2 = outboxService.enqueue(new OutboundMail("failed2@example.com", "S4", "<p>4</p>", "4"));

        NotificationEntity sent = notificationRepository.findById(sId).orElseThrow();
        sent.setState(NotificationState.SENT);
        sent.setSentAt(Instant.now());
        notificationRepository.save(sent);

        NotificationEntity failed1 = notificationRepository.findById(fId1).orElseThrow();
        failed1.setState(NotificationState.FAILED);
        failed1.setAttempts(5);
        failed1.setLastError("Older relay connection error");
        notificationRepository.save(failed1);

        NotificationEntity failed2 = notificationRepository.findById(fId2).orElseThrow();
        failed2.setState(NotificationState.FAILED);
        failed2.setAttempts(5);
        failed2.setLastError("Newer relay connection timeout");
        notificationRepository.save(failed2);

        assertThat(outboxService.failedCount()).isEqualTo(2);

        Optional<String> lastError = outboxService.lastError();
        assertThat(lastError).isPresent().contains("Newer relay connection timeout");
    }
}
