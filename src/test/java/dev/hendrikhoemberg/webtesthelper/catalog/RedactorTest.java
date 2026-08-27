package dev.hendrikhoemberg.webtesthelper.catalog;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import dev.hendrikhoemberg.webtesthelper.support.AbstractPostgresTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@Transactional
class RedactorTest extends AbstractPostgresTest {

    @Autowired
    CredentialService credentialService;

    @Autowired
    SiteService siteService;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    jakarta.persistence.EntityManager entityManager;

    private long siteA;
    private long siteB;

    @BeforeEach
    void setUp() {
        siteA = siteService.create(new SiteForm("Site A", "https://a.test/", 100, 3,
                Duration.ofMinutes(10), List.of(), List.of(), true, null, true));
        siteB = siteService.create(new SiteForm("Site B", "https://b.test/", 100, 3,
                Duration.ofMinutes(10), List.of(), List.of(), true, null, true));
    }

    @Test
    void secretInsideSentenceIsReplacedByMask() {
        Redactor redactor = Redactor.of(List.of("secret123"));
        String result = redactor.redact("Passwort secret123 ist falsch");
        assertThat(result).isEqualTo("Passwort " + Redactor.MASK + " ist falsch");
    }

    @Test
    void twoOccurrencesAreBothReplaced() {
        Redactor redactor = Redactor.of(List.of("secret123"));
        String result = redactor.redact("first secret123 and second secret123");
        assertThat(result).isEqualTo("first " + Redactor.MASK + " and second " + Redactor.MASK);
    }

    @Test
    void longerSecretMaskedFirstLeavingNoTail() {
        Redactor redactor = Redactor.of(List.of("geheim", "geheim123"));
        String result = redactor.redact("geheim123");
        assertThat(result).isEqualTo(Redactor.MASK);
    }

    @Test
    void blankSecretInCollectionIsIgnoredAndTextSurvivesIntact() {
        Redactor redactor = Redactor.of(List.of("", "   ", "\t", "\n"));
        assertThat(redactor.isEmpty()).isTrue();
        assertThat(redactor.redact("hello world")).isEqualTo("hello world");
    }

    @Test
    void maskIsSameSixCharactersRegardlessOfSecretLength() {
        Redactor shortRedactor = Redactor.of(List.of("1234"));
        Redactor longRedactor = Redactor.of(List.of("1234567890123456789012345678901234567890"));

        assertThat(Redactor.MASK).isEqualTo("••••••");
        assertThat(Redactor.MASK.length()).isEqualTo(6);
        assertThat(shortRedactor.redact("1234")).isEqualTo(Redactor.MASK);
        assertThat(longRedactor.redact("1234567890123456789012345678901234567890")).isEqualTo(Redactor.MASK);
    }

    @Test
    void noneReturnsIdenticalInstance() {
        String text = "some unchanged text";
        assertThat(Redactor.NONE.redact(text)).isSameAs(text);
        assertThat(Redactor.NONE.isEmpty()).isTrue();
    }

    @Test
    void nullInYieldsNullOut() {
        Redactor redactor = Redactor.of(List.of("secret"));
        assertThat(redactor.redact(null)).isNull();
        assertThat(Redactor.NONE.redact(null)).isNull();
    }

    @Test
    void logbackAppenderReceivesRedactedMessageWithoutPassword() {
        Logger logger = (Logger) LoggerFactory.getLogger("dev.hendrikhoemberg.webtesthelper.test.redactor");
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);

        try {
            String password = "superSecretPassword123";
            Redactor redactor = Redactor.of(List.of(password));
            String pageText = "Fehler: Passwort superSecretPassword123 ist ungültig.";

            logger.warn("Antwort der Seite: {}", redactor.redact(pageText));

            assertThat(appender.list).hasSize(1);
            String formattedMessage = appender.list.get(0).getFormattedMessage();
            assertThat(formattedMessage)
                    .contains(Redactor.MASK)
                    .doesNotContain(password);
        } finally {
            logger.detachAppender(appender);
        }
    }

    @Test
    void logbackAppenderReceivesSecretTextShowingTemplateNotPlaintext() {
        Logger logger = (Logger) LoggerFactory.getLogger("dev.hendrikhoemberg.webtesthelper.test.secrettext");
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);

        try {
            SecretText secretText = SecretText.of("superSecretPlaintext", "{{cred:login.password}}");

            logger.info("FILL {} = {}", "pw", secretText);

            assertThat(appender.list).hasSize(1);
            String formattedMessage = appender.list.get(0).getFormattedMessage();
            assertThat(formattedMessage)
                    .contains("{{cred:login.password}}")
                    .doesNotContain("superSecretPlaintext");
        } finally {
            logger.detachAppender(appender);
        }
    }

    @Test
    void redactorForSiteWithTwoCredentialsRedactsBoth() {
        credentialService.create(siteA, "cred1", "user1", "firstPass");
        credentialService.create(siteA, "cred2", "user2", "secondPass");

        Redactor redactor = credentialService.redactorFor(siteA);
        assertThat(redactor.isEmpty()).isFalse();

        String result = redactor.redact("Echo firstPass and secondPass here");
        assertThat(result).isEqualTo("Echo " + Redactor.MASK + " and " + Redactor.MASK + " here");
    }

    @Test
    void redactorForSiteWithNoneReturnsEmptyRedactor() {
        Redactor redactor = credentialService.redactorFor(siteB);
        assertThat(redactor.isEmpty()).isTrue();
        assertThat(redactor).isSameAs(Redactor.NONE);
    }

    @Test
    void redactorForSiteWithCorruptSecretReturnsEmptyRedactorWithoutThrowing() {
        long id = credentialService.create(siteA, "cred1", "user1", "somePassword");
        jdbcTemplate.update("UPDATE credential SET secret = 'corrupted-ciphertext' WHERE id = ?", id);
        entityManager.flush();
        entityManager.clear();

        Redactor redactor = credentialService.redactorFor(siteA);
        assertThat(redactor.isEmpty()).isTrue();
        assertThat(redactor.redact("some text")).isEqualTo("some text");
    }
}
