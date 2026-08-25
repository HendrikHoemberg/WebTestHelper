package dev.hendrikhoemberg.webtesthelper.reporting;

import dev.hendrikhoemberg.webtesthelper.catalog.AppSettings;
import dev.hendrikhoemberg.webtesthelper.reporting.persistence.NotificationRepository;
import dev.hendrikhoemberg.webtesthelper.support.AbstractPostgresTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

/**
 * Spec 11.3: a mail that was delivered must be recorded as delivered. A dispatch cycle that
 * writes its bookkeeping inside one transaction spanning the SMTP conversations cannot promise
 * that — anything that unwinds the cycle takes the already-committed-looking {@code SENT} rows
 * with it, and the next cycle sends those mails a second time.
 *
 * <p>The {@link Notifier} is stubbed because the failure being reproduced is the process coming
 * apart mid-cycle, which no real relay can be asked to do. Everything else — the dispatcher bean
 * with its proxies, the claim repository, the database — is real; a hand-constructed dispatcher
 * would be unproxied and would prove nothing about transaction behaviour.
 */
class OutboxDispatcherDurabilityTest extends AbstractPostgresTest {

    @Autowired
    OutboxDispatcher dispatcher;

    @Autowired
    OutboxService outboxService;

    @Autowired
    NotificationRepository notificationRepository;

    @Autowired
    AppSettings appSettings;

    @Autowired
    JdbcTemplate jdbc;

    @MockitoBean
    Notifier notifier;

    private final List<String> delivered = new ArrayList<>();

    @BeforeEach
    void setUp() {
        jdbc.execute("DELETE FROM notification");
        appSettings.saveRedirectAllMailTo("");
        delivered.clear();
        doAnswer(invocation -> {
            OutboundMail mail = invocation.getArgument(0);
            delivered.add(mail.recipient());
            if (delivered.size() > 1) {
                // An Error, not an Exception: the cycle's own catch does not see it. Spec 16
                // names the OOM killer on this host, so this is not a hypothetical unwind.
                throw new StackOverflowError("Prozess stirbt mitten im Zyklus");
            }
            return null;
        }).when(notifier).deliver(any());
    }

    @Test
    void aMailAlreadyHandedToTheRelayStaysSentWhenTheCycleDiesOnTheNextOne() {
        long first = outboxService.enqueue(
                new OutboundMail("erste@example.com", "Erste", "<p>1</p>", "1"));
        outboxService.enqueue(new OutboundMail("zweite@example.com", "Zweite", "<p>2</p>", "2"));

        assertThatThrownBy(dispatcher::dispatchCycle).isInstanceOf(StackOverflowError.class);

        assertThat(delivered).containsExactly("erste@example.com", "zweite@example.com");
        assertThat(notificationRepository.findById(first).orElseThrow().getState())
                .isEqualTo(NotificationState.SENT);
    }
}
