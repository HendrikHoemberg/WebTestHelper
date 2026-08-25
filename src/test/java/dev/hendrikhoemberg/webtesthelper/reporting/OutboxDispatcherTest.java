package dev.hendrikhoemberg.webtesthelper.reporting;

import com.icegreen.greenmail.configuration.GreenMailConfiguration;
import com.icegreen.greenmail.junit5.GreenMailExtension;
import com.icegreen.greenmail.util.ServerSetup;
import dev.hendrikhoemberg.webtesthelper.catalog.AppSettings;
import dev.hendrikhoemberg.webtesthelper.catalog.SmtpSettings;
import dev.hendrikhoemberg.webtesthelper.catalog.TlsMode;
import dev.hendrikhoemberg.webtesthelper.reporting.persistence.NotificationEntity;
import dev.hendrikhoemberg.webtesthelper.reporting.persistence.NotificationRepository;
import dev.hendrikhoemberg.webtesthelper.support.AbstractPostgresTest;
import jakarta.mail.BodyPart;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

class OutboxDispatcherTest extends AbstractPostgresTest {

    @RegisterExtension
    static GreenMailExtension greenMail = new GreenMailExtension(
            new ServerSetup(0, "127.0.0.1", ServerSetup.PROTOCOL_SMTP)
    ).withConfiguration(GreenMailConfiguration.aConfig().withDisabledAuthentication());

    @Autowired
    OutboxDispatcher dispatcher;

    @Autowired
    OutboxService outboxService;

    @Autowired
    NotificationRepository notificationRepository;

    @Autowired
    AppSettings appSettings;

    @Autowired
    ReportingProperties reportingProperties;

    @Autowired
    JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        jdbc.execute("DELETE FROM notification");
        greenMail.reset();

        int smtpPort = greenMail.getSmtp().getPort();
        appSettings.saveSmtp(new SmtpSettings(
                "127.0.0.1",
                smtpPort,
                TlsMode.NONE,
                null,
                null,
                "noreply@webtesthelper.example.com"
        ));
        appSettings.saveRedirectAllMailTo("");
    }

    @Test
    void oneDispatchOfPendingMailDeliversMultipartMessageAndMarksRowSent() throws Exception {
        long id = outboxService.enqueue(new OutboundMail(
                "recipient@example.com",
                "Betreff: Benachrichtigung",
                "<html><body><p>HTML-Inhalt</p></body></html>",
                "Text-Inhalt"
        ));

        int dispatched = dispatcher.dispatchCycle();
        assertThat(dispatched).isEqualTo(1);

        MimeMessage[] messages = greenMail.getReceivedMessages();
        assertThat(messages).hasSize(1);
        MimeMessage msg = messages[0];
        assertThat(msg.getSubject()).isEqualTo("Betreff: Benachrichtigung");
        assertThat(msg.getFrom()[0].toString()).contains("noreply@webtesthelper.example.com");
        assertThat(msg.getAllRecipients()[0].toString()).contains("recipient@example.com");

        Object content = msg.getContent();
        assertThat(content).isInstanceOf(MimeMultipart.class);
        MimeMultipart multipart = (MimeMultipart) content;

        StringBuilder textBody = new StringBuilder();
        StringBuilder htmlBody = new StringBuilder();
        extractParts(multipart, textBody, htmlBody);

        assertThat(textBody.toString()).contains("Text-Inhalt");
        assertThat(htmlBody.toString()).contains("HTML-Inhalt");

        NotificationEntity row = notificationRepository.findById(id).orElseThrow();
        assertThat(row.getState()).isEqualTo(NotificationState.SENT);
        assertThat(row.getSentAt()).isNotNull();
        assertThat(row.getAttempts()).isEqualTo(1);
        assertThat(row.getLastError()).isNull();
    }

    @Test
    void redirectAllMailToSendToRedirectAddressWhileRowKeepsOriginalRecipient() throws Exception {
        appSettings.saveRedirectAllMailTo("catchall@staging.example.com");

        long id = outboxService.enqueue(new OutboundMail(
                "intended-recipient@example.com",
                "Test Subject",
                "<p>HTML</p>",
                "Text"
        ));

        int dispatched = dispatcher.dispatchCycle();
        assertThat(dispatched).isEqualTo(1);

        MimeMessage[] messages = greenMail.getReceivedMessages();
        assertThat(messages).hasSize(1);
        assertThat(messages[0].getAllRecipients()[0].toString()).contains("catchall@staging.example.com");
        assertThat(messages[0].getAllRecipients()[0].toString()).doesNotContain("intended-recipient@example.com");

        NotificationEntity row = notificationRepository.findById(id).orElseThrow();
        assertThat(row.getRecipient()).isEqualTo("intended-recipient@example.com");
        assertThat(row.getState()).isEqualTo(NotificationState.SENT);
    }

    @Test
    void againstDeadPortIncrementsAttemptsSetsLastErrorAndAppliesExponentialBackoff() {
        // Point to a dead port
        appSettings.saveSmtp(new SmtpSettings(
                "127.0.0.1",
                65432,
                TlsMode.NONE,
                null,
                null,
                "noreply@webtesthelper.example.com"
        ));

        long id = outboxService.enqueue(new OutboundMail(
                "user@example.com",
                "Subject",
                "<p>HTML</p>",
                "Text"
        ));

        Instant beforeFirst = Instant.now();
        int dispatched = dispatcher.dispatchCycle();
        assertThat(dispatched).isEqualTo(1);

        NotificationEntity row1 = notificationRepository.findById(id).orElseThrow();
        assertThat(row1.getState()).isEqualTo(NotificationState.PENDING);
        assertThat(row1.getAttempts()).isEqualTo(1);
        assertThat(row1.getLastError()).isNotBlank();
        // roughly 1 minute out
        assertThat(row1.getNextAttemptAt())
                .isAfter(beforeFirst.plus(Duration.ofSeconds(50)))
                .isBefore(beforeFirst.plus(Duration.ofSeconds(70)));

        // Force next_attempt_at to past so second dispatch claims it
        row1.setNextAttemptAt(Instant.now().minusSeconds(1));
        notificationRepository.save(row1);

        Instant beforeSecond = Instant.now();
        dispatcher.dispatchCycle();

        NotificationEntity row2 = notificationRepository.findById(id).orElseThrow();
        assertThat(row2.getState()).isEqualTo(NotificationState.PENDING);
        assertThat(row2.getAttempts()).isEqualTo(2);
        // roughly 2 minutes out
        assertThat(row2.getNextAttemptAt())
                .isAfter(beforeSecond.plus(Duration.ofSeconds(110)))
                .isBefore(beforeSecond.plus(Duration.ofSeconds(130)));
    }

    @Test
    void reachingMaxAttemptsFlipsRowToFailedAndNeverClaimsAgain() {
        appSettings.saveSmtp(new SmtpSettings(
                "127.0.0.1",
                65432,
                TlsMode.NONE,
                null,
                null,
                "noreply@webtesthelper.example.com"
        ));

        long id = outboxService.enqueue(new OutboundMail(
                "user@example.com",
                "Subject",
                "<p>HTML</p>",
                "Text"
        ));

        NotificationEntity row = notificationRepository.findById(id).orElseThrow();
        // Set attempts to maxAttempts - 1
        row.setAttempts(reportingProperties.maxAttempts() - 1);
        row.setNextAttemptAt(Instant.now().minusSeconds(1));
        notificationRepository.save(row);

        dispatcher.dispatchCycle();

        NotificationEntity afterMax = notificationRepository.findById(id).orElseThrow();
        assertThat(afterMax.getState()).isEqualTo(NotificationState.FAILED);
        assertThat(afterMax.getAttempts()).isEqualTo(reportingProperties.maxAttempts());
        assertThat(afterMax.getLastError()).isNotBlank();

        // Further dispatch cycles will not claim this FAILED row
        int claimed = dispatcher.dispatchCycle();
        assertThat(claimed).isZero();
    }

    @Test
    void mailWithNextAttemptInFutureIsNotClaimed() {
        long id = outboxService.enqueue(new OutboundMail(
                "user@example.com",
                "Subject",
                "<p>HTML</p>",
                "Text"
        ));

        NotificationEntity row = notificationRepository.findById(id).orElseThrow();
        row.setNextAttemptAt(Instant.now().plus(Duration.ofHours(1)));
        notificationRepository.save(row);

        int dispatched = dispatcher.dispatchCycle();
        assertThat(dispatched).isZero();
        assertThat(greenMail.getReceivedMessages()).isEmpty();
    }

    @Test
    void twoDispatchersRunningConcurrentlyDeliverRowOnceNotTwice() throws Exception {
        long id = outboxService.enqueue(new OutboundMail(
                "concurrent@example.com",
                "Concurrent Subject",
                "<p>HTML</p>",
                "Text"
        ));

        ExecutorService pool = Executors.newFixedThreadPool(2);
        Callable<Integer> task = () -> dispatcher.dispatchCycle();

        Future<Integer> f1 = pool.submit(task);
        Future<Integer> f2 = pool.submit(task);

        int count1 = f1.get();
        int count2 = f2.get();
        pool.shutdown();

        assertThat(count1 + count2).isEqualTo(1);
        assertThat(greenMail.getReceivedMessages()).hasSize(1);

        NotificationEntity row = notificationRepository.findById(id).orElseThrow();
        assertThat(row.getState()).isEqualTo(NotificationState.SENT);
        assertThat(row.getAttempts()).isEqualTo(1);
    }

    private void extractParts(MimeMultipart multipart, StringBuilder text, StringBuilder html) throws Exception {
        for (int i = 0; i < multipart.getCount(); i++) {
            BodyPart part = multipart.getBodyPart(i);
            if (part.isMimeType("text/plain")) {
                text.append(part.getContent());
            } else if (part.isMimeType("text/html")) {
                html.append(part.getContent());
            } else if (part.getContent() instanceof MimeMultipart child) {
                extractParts(child, text, html);
            }
        }
    }
}
