package dev.hendrikhoemberg.webtesthelper.catalog;

import dev.hendrikhoemberg.webtesthelper.catalog.persistence.AppSettingEntity;
import dev.hendrikhoemberg.webtesthelper.catalog.persistence.AppSettingRepository;
import dev.hendrikhoemberg.webtesthelper.support.AbstractPostgresTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;

@Transactional
class SettingsBootstrapTest extends AbstractPostgresTest {

    @Autowired
    AppSettingRepository appSettingRepository;

    @Autowired
    SecretBox secretBox;

    @Autowired
    AppSettings appSettings;

    @BeforeEach
    @AfterEach
    void cleanUp() {
        appSettingRepository.deleteAll();
    }

    @Test
    void bootstrapsSettingsWhenKeysAreAbsent() throws Exception {
        MockEnvironment env = new MockEnvironment()
                .withProperty("WTH_SMTP_HOST", "smtp.bootstrapped.org")
                .withProperty("WTH_SMTP_PORT", "2525")
                .withProperty("WTH_SMTP_TLS", "STARTTLS")
                .withProperty("WTH_SMTP_USER", "boot-user")
                .withProperty("WTH_SMTP_PASSWORD", "boot-pass-456")
                .withProperty("WTH_SMTP_FROM", "boot@bootstrapped.org")
                .withProperty("WTH_BASE_URL", "https://bootstrapped.org/")
                .withProperty("WTH_IMAP_HOST", "imap.bootstrapped.org")
                .withProperty("WTH_IMAP_PORT", "1993")
                .withProperty("WTH_IMAP_TLS", "SSL")
                .withProperty("WTH_IMAP_USER", "boot-imap-user")
                .withProperty("WTH_IMAP_PASSWORD", "boot-imap-pass-789")
                .withProperty("WTH_IMAP_FOLDER", "INBOX")
                .withProperty("WTH_IMAP_ADDRESS", "verify@bootstrapped.org");

        SettingsBootstrap bootstrap = new SettingsBootstrap(appSettingRepository, secretBox, env);
        bootstrap.run(new DefaultApplicationArguments());

        SmtpSettings smtp = appSettings.smtp();
        assertThat(smtp.host()).isEqualTo("smtp.bootstrapped.org");
        assertThat(smtp.port()).isEqualTo(2525);
        assertThat(smtp.tls()).isEqualTo(TlsMode.STARTTLS);
        assertThat(smtp.username()).isEqualTo("boot-user");
        assertThat(smtp.password()).isEqualTo("boot-pass-456");
        assertThat(smtp.fromAddress()).isEqualTo("boot@bootstrapped.org");

        AppSettingEntity passwordRow = appSettingRepository.findById("smtp.password").orElseThrow();
        assertThat(passwordRow.isEncrypted()).isTrue();
        assertThat(passwordRow.getSettingValue()).isNotEqualTo("boot-pass-456");

        ImapSettings imap = appSettings.imap();
        assertThat(imap.host()).isEqualTo("imap.bootstrapped.org");
        assertThat(imap.port()).isEqualTo(1993);
        assertThat(imap.tls()).isEqualTo(TlsMode.SSL);
        assertThat(imap.username()).isEqualTo("boot-imap-user");
        assertThat(imap.password()).isEqualTo("boot-imap-pass-789");
        assertThat(imap.folder()).isEqualTo("INBOX");
        assertThat(imap.verificationAddress()).isEqualTo("verify@bootstrapped.org");

        AppSettingEntity imapPasswordRow = appSettingRepository.findById("imap.password").orElseThrow();
        assertThat(imapPasswordRow.isEncrypted()).isTrue();
        assertThat(imapPasswordRow.getSettingValue()).isNotEqualTo("boot-imap-pass-789");

        assertThat(appSettings.baseUrl()).isEqualTo("https://bootstrapped.org");
    }

    @Test
    void doesNotOverwriteExistingKeysEvenIfEmpty() throws Exception {
        // User previously cleared username in UI (so key exists with value "")
        appSettingRepository.save(new AppSettingEntity("smtp.username", "", false));
        appSettingRepository.save(new AppSettingEntity("smtp.host", "custom.smtp.org", false));

        MockEnvironment env = new MockEnvironment()
                .withProperty("WTH_SMTP_HOST", "env.smtp.org")
                .withProperty("WTH_SMTP_USER", "env-user");

        SettingsBootstrap bootstrap = new SettingsBootstrap(appSettingRepository, secretBox, env);
        bootstrap.run(new DefaultApplicationArguments());

        // Keys should NOT have been overwritten
        AppSettingEntity usernameRow = appSettingRepository.findById("smtp.username").orElseThrow();
        assertThat(usernameRow.getSettingValue()).isEqualTo("");

        AppSettingEntity hostRow = appSettingRepository.findById("smtp.host").orElseThrow();
        assertThat(hostRow.getSettingValue()).isEqualTo("custom.smtp.org");
    }
}
