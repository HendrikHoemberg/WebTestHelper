package dev.hendrikhoemberg.webtesthelper.challenger;

import dev.hendrikhoemberg.webtesthelper.catalog.AppSettings;
import dev.hendrikhoemberg.webtesthelper.reporting.DeliveryResult;
import dev.hendrikhoemberg.webtesthelper.reporting.MailDeliveryException;
import dev.hendrikhoemberg.webtesthelper.reporting.NotificationState;
import dev.hendrikhoemberg.webtesthelper.reporting.Notifier;
import dev.hendrikhoemberg.webtesthelper.reporting.OutboundMail;
import dev.hendrikhoemberg.webtesthelper.reporting.OutboxService;
import dev.hendrikhoemberg.webtesthelper.reporting.persistence.NotificationEntity;
import dev.hendrikhoemberg.webtesthelper.reporting.persistence.NotificationRepository;
import dev.hendrikhoemberg.webtesthelper.support.AbstractPostgresTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

class OutboxServiceAdversarialTest extends AbstractPostgresTest {

    @Autowired
    OutboxService outboxService;

    @Autowired
    NotificationRepository notificationRepository;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    PlatformTransactionManager transactionManager;

    @MockitoBean
    Notifier notifier;

    @BeforeEach
    void setUp() {
        jdbc.execute("DELETE FROM notification");
    }

    @Test
    void sendNow_assertsNoTransactionDuringDeliveryOnSuccess() throws Exception {
        AtomicBoolean activeInsideDeliver = new AtomicBoolean(true);
        doAnswer(invocation -> {
            activeInsideDeliver.set(TransactionSynchronizationManager.isActualTransactionActive());
            return null;
        }).when(notifier).deliver(any());

        long id = outboxService.enqueue(new OutboundMail("user@example.com", "Sub", "<p>H</p>", "T"));
        DeliveryResult result = outboxService.sendNow(id);

        assertThat(result.success()).isTrue();
        assertThat(activeInsideDeliver.get())
                .as("Network delivery must NOT occur inside an active database transaction")
                .isFalse();
    }

    @Test
    void sendNow_assertsNoTransactionDuringDeliveryOnMailDeliveryException() throws Exception {
        AtomicBoolean activeInsideDeliver = new AtomicBoolean(true);
        doAnswer(invocation -> {
            activeInsideDeliver.set(TransactionSynchronizationManager.isActualTransactionActive());
            throw new MailDeliveryException("SMTP timeout");
        }).when(notifier).deliver(any());

        long id = outboxService.enqueue(new OutboundMail("user@example.com", "Sub", "<p>H</p>", "T"));
        DeliveryResult result = outboxService.sendNow(id);

        assertThat(result.success()).isFalse();
        assertThat(activeInsideDeliver.get())
                .as("Network delivery must NOT occur inside an active database transaction even on error")
                .isFalse();
    }

    @Test
    void retry_assertsNoTransactionDuringDelivery() throws Exception {
        AtomicBoolean activeInsideDeliver = new AtomicBoolean(true);
        doAnswer(invocation -> {
            activeInsideDeliver.set(TransactionSynchronizationManager.isActualTransactionActive());
            return null;
        }).when(notifier).deliver(any());

        long id = outboxService.enqueue(new OutboundMail("user@example.com", "Sub", "<p>H</p>", "T"));
        DeliveryResult result = outboxService.retry(id);

        assertThat(result.success()).isTrue();
        assertThat(activeInsideDeliver.get())
                .as("Network delivery during retry must NOT occur inside an active database transaction")
                .isFalse();
    }

    @Test
    void retryAllFailed_assertsNoTransactionDuringAnyDelivery() throws Exception {
        AtomicInteger totalDeliveries = new AtomicInteger(0);
        AtomicInteger activeDeliveries = new AtomicInteger(0);

        doAnswer(invocation -> {
            totalDeliveries.incrementAndGet();
            if (TransactionSynchronizationManager.isActualTransactionActive()) {
                activeDeliveries.incrementAndGet();
            }
            return null;
        }).when(notifier).deliver(any());

        long id1 = outboxService.enqueue(new OutboundMail("u1@example.com", "S1", "<p>1</p>", "1"));
        long id2 = outboxService.enqueue(new OutboundMail("u2@example.com", "S2", "<p>2</p>", "2"));

        NotificationEntity e1 = notificationRepository.findById(id1).orElseThrow();
        e1.setState(NotificationState.FAILED);
        notificationRepository.save(e1);

        NotificationEntity e2 = notificationRepository.findById(id2).orElseThrow();
        e2.setState(NotificationState.FAILED);
        notificationRepository.save(e2);

        int count = outboxService.retryAllFailed();

        assertThat(count).isEqualTo(2);
        assertThat(totalDeliveries.get()).isEqualTo(2);
        assertThat(activeDeliveries.get())
                .as("Zero deliveries should have an active transaction")
                .isZero();
    }

    @Test
    void sendNow_whenInvokedInsideTransactionalContext_revealsWhetherTransactionIsSuspended() {
        TransactionTemplate txTemplate = new TransactionTemplate(transactionManager);
        AtomicBoolean activeInsideDeliver = new AtomicBoolean(false);

        doAnswer(invocation -> {
            activeInsideDeliver.set(TransactionSynchronizationManager.isActualTransactionActive());
            return null;
        }).when(notifier).deliver(any());

        long id = outboxService.enqueue(new OutboundMail("tx@example.com", "Sub", "<p>H</p>", "T"));

        // Caller invokes sendNow within an active transaction:
        txTemplate.executeWithoutResult(status -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            outboxService.sendNow(id);
        });

        System.out.println("DEBUG: When sendNow was called inside caller transaction, activeInsideDeliver was: "
                + activeInsideDeliver.get());
    }
}
