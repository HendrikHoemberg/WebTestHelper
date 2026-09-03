package dev.hendrikhoemberg.webtesthelper.catalog;

import dev.hendrikhoemberg.webtesthelper.catalog.persistence.AppSettingEntity;
import dev.hendrikhoemberg.webtesthelper.catalog.persistence.AppSettingRepository;
import dev.hendrikhoemberg.webtesthelper.support.AbstractPostgresTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@Transactional
class AppSettingsTest extends AbstractPostgresTest {

    @Autowired
    AppSettings appSettings;

    @Autowired
    AppSettingRepository appSettingRepository;

    @BeforeEach
    @AfterEach
    void cleanUp() {
        appSettingRepository.deleteAll();
    }

    @Test
    void saveSmtpThenSmtpRoundTripsAllFields() {
        SmtpSettings original = new SmtpSettings(
                "mail.example.com",
                587,
                TlsMode.STARTTLS,
                "smtp-user",
                "secret-smtp-password-123",
                "monitor@example.com"
        );

        appSettings.saveSmtp(original);

        SmtpSettings loaded = appSettings.smtp();
        assertThat(loaded.host()).isEqualTo("mail.example.com");
        assertThat(loaded.port()).isEqualTo(587);
        assertThat(loaded.tls()).isEqualTo(TlsMode.STARTTLS);
        assertThat(loaded.username()).isEqualTo("smtp-user");
        assertThat(loaded.password()).isEqualTo("secret-smtp-password-123");
        assertThat(loaded.fromAddress()).isEqualTo("monitor@example.com");
        assertThat(loaded.configured()).isTrue();
    }

    @Test
    void storedPasswordIsEncryptedAndOtherKeysAreNot() {
        SmtpSettings original = new SmtpSettings(
                "mail.example.com",
                465,
                TlsMode.SSL,
                "my-user",
                "PlaintextPasswordToEncrypt",
                "noreply@example.com"
        );

        appSettings.saveSmtp(original);

        AppSettingEntity passwordRow = appSettingRepository.findById("smtp.password").orElseThrow();
        assertThat(passwordRow.isEncrypted()).isTrue();
        assertThat(passwordRow.getSettingValue()).isNotEqualTo("PlaintextPasswordToEncrypt");
        assertThat(passwordRow.getSettingValue()).doesNotContain("PlaintextPasswordToEncrypt");

        AppSettingEntity hostRow = appSettingRepository.findById("smtp.host").orElseThrow();
        assertThat(hostRow.isEncrypted()).isFalse();
        assertThat(hostRow.getSettingValue()).isEqualTo("mail.example.com");

        AppSettingEntity portRow = appSettingRepository.findById("smtp.port").orElseThrow();
        assertThat(portRow.isEncrypted()).isFalse();
        assertThat(portRow.getSettingValue()).isEqualTo("465");

        AppSettingEntity tlsRow = appSettingRepository.findById("smtp.tls").orElseThrow();
        assertThat(tlsRow.isEncrypted()).isFalse();
        assertThat(tlsRow.getSettingValue()).isEqualTo("SSL");

        AppSettingEntity userRow = appSettingRepository.findById("smtp.username").orElseThrow();
        assertThat(userRow.isEncrypted()).isFalse();
        assertThat(userRow.getSettingValue()).isEqualTo("my-user");

        AppSettingEntity fromRow = appSettingRepository.findById("smtp.from").orElseThrow();
        assertThat(fromRow.isEncrypted()).isFalse();
        assertThat(fromRow.getSettingValue()).isEqualTo("noreply@example.com");
    }

    @Test
    void smtpOnEmptyTableReturnsUnconfiguredRecordWithoutException() {
        SmtpSettings loaded = appSettings.smtp();

        assertThat(loaded).isNotNull();
        assertThat(loaded.configured()).isFalse();
    }

    @Test
    void saveBaseUrlNormalisesTrailingSlashAway() {
        appSettings.saveBaseUrl("https://monitor.example.com/");
        assertThat(appSettings.baseUrl()).isEqualTo("https://monitor.example.com");

        appSettings.saveBaseUrl("https://monitor.example.com///");
        assertThat(appSettings.baseUrl()).isEqualTo("https://monitor.example.com");

        appSettings.saveBaseUrl("https://monitor.example.com");
        assertThat(appSettings.baseUrl()).isEqualTo("https://monitor.example.com");
    }

    @Test
    void redirectAllMailToReturnsEmptyForBlankValue() {
        assertThat(appSettings.redirectAllMailTo()).isEmpty();

        appSettings.saveRedirectAllMailTo("  ");
        assertThat(appSettings.redirectAllMailTo()).isEmpty();

        appSettings.saveRedirectAllMailTo("");
        assertThat(appSettings.redirectAllMailTo()).isEmpty();

        appSettings.saveRedirectAllMailTo("dev-team@example.com");
        assertThat(appSettings.redirectAllMailTo()).contains("dev-team@example.com");

        appSettings.saveRedirectAllMailTo(null);
        assertThat(appSettings.redirectAllMailTo()).isEmpty();
    }

    @Test
    void saveFallbackRecipientsSplitsNormalisesAndDeduplicates() {
        appSettings.saveFallbackRecipients("A@x.test; b@x.test , b@X.test");
        assertThat(appSettings.fallbackRecipients()).containsExactly("a@x.test", "b@x.test");

        appSettings.saveFallbackRecipients("  ");
        assertThat(appSettings.fallbackRecipients()).isEmpty();

        appSettings.saveFallbackRecipients(null);
        assertThat(appSettings.fallbackRecipients()).isEmpty();
    }

    @Test
    void fallbackRecipientsOnEmptyTableReturnsEmptyList() {
        assertThat(appSettings.fallbackRecipients()).isEmpty();
    }

    @Test
    void saveAndGetWebhookSettings() {
        assertThat(appSettings.webhookUrl()).isEmpty();
        assertThat(appSettings.webhookEnabled()).isFalse();

        appSettings.saveWebhookUrl("https://hooks.slack.com/services/test");
        appSettings.saveWebhookEnabled(true);

        assertThat(appSettings.webhookUrl()).isEqualTo("https://hooks.slack.com/services/test");
        assertThat(appSettings.webhookEnabled()).isTrue();
    }
}
