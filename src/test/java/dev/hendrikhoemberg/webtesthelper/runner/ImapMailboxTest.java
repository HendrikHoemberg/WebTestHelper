package dev.hendrikhoemberg.webtesthelper.runner;

import com.icegreen.greenmail.util.GreenMail;
import com.icegreen.greenmail.util.GreenMailUtil;
import com.icegreen.greenmail.util.ServerSetup;
import dev.hendrikhoemberg.webtesthelper.catalog.AppSettings;
import dev.hendrikhoemberg.webtesthelper.catalog.ImapSettings;
import dev.hendrikhoemberg.webtesthelper.catalog.TlsMode;
import dev.hendrikhoemberg.webtesthelper.model.Mailbox;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ImapMailboxTest {

    private GreenMail greenMail;
    private AppSettings appSettings;
    private ImapMailbox mailbox;

    @BeforeEach
    void setUp() {
        ServerSetup smtpSetup = new ServerSetup(0, "127.0.0.1", ServerSetup.PROTOCOL_SMTP);
        ServerSetup imapSetup = new ServerSetup(0, "127.0.0.1", ServerSetup.PROTOCOL_IMAP);
        greenMail = new GreenMail(new ServerSetup[]{smtpSetup, imapSetup});
        greenMail.start();

        greenMail.setUser("verify@example.com", "verifyuser", "secretpass");

        appSettings = mock(AppSettings.class);
        when(appSettings.imap()).thenReturn(new ImapSettings(
                "127.0.0.1",
                greenMail.getImap().getPort(),
                TlsMode.NONE,
                "verifyuser",
                "secretpass",
                "INBOX",
                "verify@example.com"
        ));

        mailbox = new ImapMailbox(appSettings);
    }

    @AfterEach
    void tearDown() {
        if (greenMail != null) {
            greenMail.stop();
        }
    }

    @Test
    void tokenSentBeforeCallIsFoundOnFirstPoll() {
        GreenMailUtil.sendTextEmail(
                "verify@example.com",
                "sender@example.com",
                "Test Subject",
                "Message with token WTH-EARLY-12345 inside",
                greenMail.getSmtp().getServerSetup()
        );

        Instant start = Instant.now();
        Mailbox.Result result = mailbox.awaitToken("WTH-EARLY-12345", Duration.ofSeconds(5));
        Duration elapsed = Duration.between(start, Instant.now());

        assertThat(result).isEqualTo(Mailbox.Result.FOUND);
        // Measured: ~250ms end to end, slack per CLAUDE.md timing rule
        assertThat(elapsed).isLessThan(Duration.ofSeconds(2));
    }

    @Test
    void tokenNeverSentReturnsNotFoundWithinBudget() {
        Instant start = Instant.now();
        Mailbox.Result result = mailbox.awaitToken("WTH-NONEXISTENT", Duration.ofSeconds(2));
        Duration elapsed = Duration.between(start, Instant.now());

        assertThat(result).isEqualTo(Mailbox.Result.NOT_FOUND);
        // Returns within the 2s budget (with timing slack)
        assertThat(elapsed)
                .isGreaterThanOrEqualTo(Duration.ofMillis(1800))
                .isLessThan(Duration.ofMillis(3500));
    }

    @Test
    void wrongPasswordIsUnavailableNotNotFound() {
        when(appSettings.imap()).thenReturn(new ImapSettings(
                "127.0.0.1",
                greenMail.getImap().getPort(),
                TlsMode.NONE,
                "verifyuser",
                "wrong-password",
                "INBOX",
                "verify@example.com"
        ));

        Mailbox.Result result = mailbox.awaitToken("WTH-ANY", Duration.ofSeconds(2));

        // D89: UNAVAILABLE must never reach a finding (wrong password is not NOT_FOUND)
        assertThat(result).isEqualTo(Mailbox.Result.UNAVAILABLE);
    }

    @Test
    void unconfiguredMailboxIsUnavailableWithoutOpeningSocket() {
        when(appSettings.imap()).thenReturn(new ImapSettings(
                null,
                993,
                TlsMode.SSL,
                null,
                null,
                "INBOX",
                null
        ));

        Mailbox.Result result = mailbox.awaitToken("WTH-ANY", Duration.ofSeconds(2));

        assertThat(result).isEqualTo(Mailbox.Result.UNAVAILABLE);
        assertThat(mailbox.address()).isEmpty();
    }

    @Test
    void unconfiguredConstantReturnsEmptyAddressAndUnavailable() {
        assertThat(Mailbox.UNCONFIGURED.address()).isEmpty();
        assertThat(Mailbox.UNCONFIGURED.awaitToken("WTH-ANY", Duration.ofSeconds(2)))
                .isEqualTo(Mailbox.Result.UNAVAILABLE);
    }

    @Test
    void tokenArrivingDuringWaitIsFound() {
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        try {
            scheduler.schedule(() -> {
                GreenMailUtil.sendTextEmail(
                        "verify@example.com",
                        "sender@example.com",
                        "Late Subject",
                        "Token arriving late: WTH-LATE-99999",
                        greenMail.getSmtp().getServerSetup()
                );
            }, 1200, TimeUnit.MILLISECONDS);

            Instant start = Instant.now();
            Mailbox.Result result = mailbox.awaitToken("WTH-LATE-99999", Duration.ofSeconds(10));
            Duration elapsed = Duration.between(start, Instant.now());

            assertThat(result).isEqualTo(Mailbox.Result.FOUND);
            // Missed poll at 0s, hit poll at 3s
            assertThat(elapsed)
                    .isGreaterThanOrEqualTo(Duration.ofMillis(2500))
                    .isLessThan(Duration.ofMillis(6000));
        } finally {
            scheduler.shutdown();
        }
    }

    @Test
    void addressReturnsConfiguredVerificationAddress() {
        assertThat(mailbox.address()).isEqualTo("verify@example.com");
    }
}
