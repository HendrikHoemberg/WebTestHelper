package dev.hendrikhoemberg.webtesthelper.web;

import dev.hendrikhoemberg.webtesthelper.catalog.ImapSettings;
import dev.hendrikhoemberg.webtesthelper.catalog.SmtpSettings;
import dev.hendrikhoemberg.webtesthelper.catalog.TlsMode;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class SettingsFormTest {

    @Test
    void defaultsUseStarttlsForSmtpAndSslForImap() {
        SettingsForm form = new SettingsForm();

        assertThat(form.getPort()).isEqualTo(587);
        assertThat(form.getTls()).isEqualTo(TlsMode.STARTTLS);
        assertThat(form.getImapPort()).isEqualTo(993);
        assertThat(form.getImapTls()).isEqualTo(TlsMode.SSL);
        assertThat(form.getImapFolder()).isEqualTo("INBOX");
    }

    @Test
    void fromUsesSslFallbackWhenImapTlsIsNull() {
        SmtpSettings smtp = new SmtpSettings("smtp.example.com", 0, null, "user", "secret", "from@example.com");
        ImapSettings imap = new ImapSettings("imap.example.com", 0, null, "imap-user", "imap-secret", null, "verify@example.com");

        SettingsForm form = SettingsForm.from(smtp, imap, "https://example.com", Optional.empty(), false, List.of());

        assertThat(form.getPort()).isEqualTo(587);
        assertThat(form.getTls()).isEqualTo(TlsMode.STARTTLS);
        assertThat(form.getImapPort()).isEqualTo(993);
        assertThat(form.getImapTls()).isEqualTo(TlsMode.SSL);
        assertThat(form.getImapFolder()).isEqualTo("INBOX");
    }
}
