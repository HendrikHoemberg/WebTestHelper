package dev.hendrikhoemberg.webtesthelper.catalog;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import dev.hendrikhoemberg.webtesthelper.support.AbstractPostgresTest;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.RecordComponent;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Task 5 Acceptance Test: The complete Credential Store narrative.
 * Proves that stored secrets are encrypted at rest, resolved templates safely retain template
 * strings in toString(), logs never leak plaintext secrets through Logback appenders, rotation
 * cleanly replaces credentials and updates redactors, and site deletion cascades without leaking stale plaintext.
 */
@Transactional
class CredentialAcceptanceTest extends AbstractPostgresTest {

    @Autowired
    CredentialService credentialService;

    @Autowired
    SiteService siteService;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    EntityManager entityManager;

    @Test
    void thePasswordSurvivesStorageResolutionAndEveryLogLine() {
        // 1. A site is created and a colleague stores login / redakteur@kunde-mueller.de with a password.
        long siteId = siteService.create(new SiteForm("Kunde Müller", "https://kunde-mueller.de/", 100, 3,
                Duration.ofMinutes(10), List.of(), List.of(), true, null, true));
        String password = "CorrectHorseBatteryStaple!2026";
        long credId = credentialService.create(siteId, "login", "redakteur@kunde-mueller.de", password);
        entityManager.flush();

        // 2. The raw secret column, read through JdbcTemplate, contains neither the password nor the
        //    username-in-clear reversed into it — and list returns a Credential with readable == true
        //    and no way to ask for the password.
        String rawSecret = jdbcTemplate.queryForObject(
                "SELECT secret FROM credential WHERE id = ?", String.class, credId);
        assertThat(rawSecret)
                .isNotNull()
                .doesNotContain(password)
                .doesNotContain("redakteur@kunde-mueller.de");

        List<Credential> list = credentialService.list(siteId);
        assertThat(list).hasSize(1);
        Credential cred = list.get(0);
        assertThat(cred.id()).isEqualTo(credId);
        assertThat(cred.siteId()).isEqualTo(siteId);
        assertThat(cred.name()).isEqualTo("login");
        assertThat(cred.username()).isEqualTo("redakteur@kunde-mueller.de");
        assertThat(cred.readable()).isTrue();

        List<String> recordComponents = Arrays.stream(Credential.class.getRecordComponents())
                .map(RecordComponent::getName)
                .toList();
        assertThat(recordComponents).doesNotContain("secret", "password", "secretText");

        // 3. A journey step value — {{cred.login.username}} and {{cred.login.password}} — resolves:
        //    expose() yields the two stored values, toString() yields the two templates unchanged.
        SecretText resolvedUsername = credentialService.resolve(siteId, "{{cred.login.username}}");
        SecretText resolvedPassword = credentialService.resolve(siteId, "{{cred.login.password}}");

        assertThat(resolvedUsername.expose()).isEqualTo("redakteur@kunde-mueller.de");
        assertThat(resolvedPassword.expose()).isEqualTo(password);
        assertThat(resolvedUsername.toString()).isEqualTo("{{cred.login.username}}");
        assertThat(resolvedPassword.toString()).isEqualTo("{{cred.login.password}}");

        // 4. Both resolved values are logged through a Logback ListAppender, once via {} and once inside
        //    a longer message, together with a redacted page echo ("Passwort " + password + " ist falsch"
        //    through redactorFor(siteId)). No captured log event contains the password — asserted over every
        //    event in appender.list, not just the last one.
        Logger logger = (Logger) LoggerFactory.getLogger("dev.hendrikhoemberg.webtesthelper.catalog.CredentialAcceptanceTest");
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);

        try {
            // Logged via {}
            logger.info("Credentials injected into step: user={}, password={}", resolvedUsername, resolvedPassword);
            // Logged inside a longer message
            logger.info("Form submission payload: user=" + resolvedUsername + ", pass=" + resolvedPassword);

            // Redacted page echo
            Redactor redactor = credentialService.redactorFor(siteId);
            String pageEcho = "Passwort " + password + " ist falsch";
            logger.warn("Server response received: {}", redactor.redact(pageEcho));

            assertThat(appender.list).hasSize(3);
            for (ILoggingEvent event : appender.list) {
                String formattedMessage = event.getFormattedMessage();
                assertThat(formattedMessage)
                        .as("Log event message '%s' must not contain the plaintext password", formattedMessage)
                        .doesNotContain(password);
            }
            assertThat(appender.list.get(0).getFormattedMessage())
                    .contains("{{cred.login.username}}")
                    .contains("{{cred.login.password}}");
            assertThat(appender.list.get(1).getFormattedMessage())
                    .contains("{{cred.login.username}}")
                    .contains("{{cred.login.password}}");
            assertThat(appender.list.get(2).getFormattedMessage())
                    .contains("Passwort " + Redactor.MASK + " ist falsch");
        } finally {
            logger.detachAppender(appender);
        }

        // 5. The password is rotated through update. Resolution returns the new value; the old value no
        //    longer appears anywhere, and the redactor built after the rotation masks the new one.
        String rotatedPassword = "NewRotatedPassword#2026!";
        credentialService.update(siteId, credId, "redakteur@kunde-mueller.de", rotatedPassword);
        entityManager.flush();

        SecretText rotatedResolved = credentialService.resolve(siteId, "{{cred.login.password}}");
        assertThat(rotatedResolved.expose()).isEqualTo(rotatedPassword);
        assertThat(rotatedResolved.toString()).isEqualTo("{{cred.login.password}}");

        String rotatedRawSecret = jdbcTemplate.queryForObject(
                "SELECT secret FROM credential WHERE id = ?", String.class, credId);
        assertThat(rotatedRawSecret)
                .doesNotContain(rotatedPassword)
                .doesNotContain(password);

        Redactor rotatedRedactor = credentialService.redactorFor(siteId);
        String newPageEcho = "Passwort " + rotatedPassword + " ist falsch";
        assertThat(rotatedRedactor.redact(newPageEcho)).isEqualTo("Passwort " + Redactor.MASK + " ist falsch");
        String oldPageEcho = "Passwort " + password + " ist falsch";
        assertThat(rotatedRedactor.redact(oldPageEcho)).isEqualTo("Passwort " + password + " ist falsch");

        // 6. The site is deleted. The credential row is gone (JdbcTemplate count), and resolve on the
        //    dead site id throws credential.reference.unknown rather than returning stale plaintext.
        siteService.delete(siteId);
        entityManager.flush();
        entityManager.clear();

        Integer remainingRows = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM credential WHERE site_id = ?", Integer.class, siteId);
        assertThat(remainingRows).isEqualTo(0);

        assertThatThrownBy(() -> credentialService.resolve(siteId, "{{cred.login.password}}"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("credential.reference.unknown")
                .hasMessageContaining("{{cred.login.password}}");
    }

    @Test
    void siteDeletionCascadesAndRemovesCredentialRows() {
        long siteId = siteService.create(new SiteForm("Site To Delete", "https://todelete.test/", 100, 3,
                Duration.ofMinutes(10), List.of(), List.of(), true, null, true));
        credentialService.create(siteId, "login", "admin", "secret1");
        credentialService.create(siteId, "api_token", "admin", "secret2");
        entityManager.flush();

        siteService.delete(siteId);
        entityManager.flush();
        entityManager.clear();

        Integer count = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM credential WHERE site_id = ?", Integer.class, siteId);
        assertThat(count).isEqualTo(0);
    }

    @Test
    void resolveOnDeletedSiteThrowsUnknownReference() {
        long siteId = siteService.create(new SiteForm("Site To Delete", "https://todelete2.test/", 100, 3,
                Duration.ofMinutes(10), List.of(), List.of(), true, null, true));
        credentialService.create(siteId, "login", "admin", "secret1");
        entityManager.flush();

        siteService.delete(siteId);
        entityManager.flush();
        entityManager.clear();

        assertThatThrownBy(() -> credentialService.resolve(siteId, "{{cred.login.password}}"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("credential.reference.unknown")
                .hasMessageContaining("{{cred.login.password}}");
    }
}
