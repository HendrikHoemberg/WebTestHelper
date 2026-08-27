package dev.hendrikhoemberg.webtesthelper.catalog;

import dev.hendrikhoemberg.webtesthelper.catalog.persistence.AppSettingEntity;
import dev.hendrikhoemberg.webtesthelper.catalog.persistence.AppSettingRepository;
import dev.hendrikhoemberg.webtesthelper.support.AbstractPostgresTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;

@Transactional
class AppSettingsImapTest extends AbstractPostgresTest {

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
    void saveImapThenImapRoundTripsAllFields() {
        ImapSettings original = new ImapSettings(
                "imap.example.com",
                993,
                TlsMode.SSL,
                "imap-user",
                "secret-imap-password-123",
                "INBOX",
                "verify@example.com"
        );

        appSettings.saveImap(original);

        ImapSettings loaded = appSettings.imap();
        assertThat(loaded.host()).isEqualTo("imap.example.com");
        assertThat(loaded.port()).isEqualTo(993);
        assertThat(loaded.tls()).isEqualTo(TlsMode.SSL);
        assertThat(loaded.username()).isEqualTo("imap-user");
        assertThat(loaded.password()).isEqualTo("secret-imap-password-123");
        assertThat(loaded.folder()).isEqualTo("INBOX");
        assertThat(loaded.verificationAddress()).isEqualTo("verify@example.com");
        assertThat(loaded.configured()).isTrue();
    }

    @Test
    void storedPasswordIsEncryptedAndOtherKeysAreNot() {
        ImapSettings original = new ImapSettings(
                "imap.example.com",
                993,
                TlsMode.SSL,
                "my-imap-user",
                "PlaintextImapPasswordToEncrypt",
                "INBOX",
                "verify@example.com"
        );

        appSettings.saveImap(original);

        AppSettingEntity passwordRow = appSettingRepository.findById("imap.password").orElseThrow();
        assertThat(passwordRow.isEncrypted()).isTrue();
        assertThat(passwordRow.getSettingValue()).isNotEqualTo("PlaintextImapPasswordToEncrypt");
        assertThat(passwordRow.getSettingValue()).doesNotContain("PlaintextImapPasswordToEncrypt");

        AppSettingEntity hostRow = appSettingRepository.findById("imap.host").orElseThrow();
        assertThat(hostRow.isEncrypted()).isFalse();
        assertThat(hostRow.getSettingValue()).isEqualTo("imap.example.com");

        AppSettingEntity portRow = appSettingRepository.findById("imap.port").orElseThrow();
        assertThat(portRow.isEncrypted()).isFalse();
        assertThat(portRow.getSettingValue()).isEqualTo("993");

        AppSettingEntity tlsRow = appSettingRepository.findById("imap.tls").orElseThrow();
        assertThat(tlsRow.isEncrypted()).isFalse();
        assertThat(tlsRow.getSettingValue()).isEqualTo("SSL");

        AppSettingEntity userRow = appSettingRepository.findById("imap.username").orElseThrow();
        assertThat(userRow.isEncrypted()).isFalse();
        assertThat(userRow.getSettingValue()).isEqualTo("my-imap-user");

        AppSettingEntity folderRow = appSettingRepository.findById("imap.folder").orElseThrow();
        assertThat(folderRow.isEncrypted()).isFalse();
        assertThat(folderRow.getSettingValue()).isEqualTo("INBOX");

        AppSettingEntity addressRow = appSettingRepository.findById("imap.verification-address").orElseThrow();
        assertThat(addressRow.isEncrypted()).isFalse();
        assertThat(addressRow.getSettingValue()).isEqualTo("verify@example.com");
    }

    @Test
    void imapOnEmptyTableReturnsUnconfiguredRecordWithoutException() {
        ImapSettings loaded = appSettings.imap();

        assertThat(loaded).isNotNull();
        assertThat(loaded.configured()).isFalse();
    }

    @Test
    void saveImapWithBlankPasswordKeepsExistingPassword() {
        ImapSettings original = new ImapSettings(
                "imap.example.com",
                993,
                TlsMode.SSL,
                "imap-user",
                "initial-secret-pass",
                "INBOX",
                "verify@example.com"
        );
        appSettings.saveImap(original);

        ImapSettings updated = new ImapSettings(
                "imap.example.com",
                993,
                TlsMode.SSL,
                "imap-user",
                "",
                "INBOX",
                "verify@example.com"
        );
        appSettings.saveImap(updated);

        ImapSettings loaded = appSettings.imap();
        assertThat(loaded.password()).isEqualTo("initial-secret-pass");
    }
}
