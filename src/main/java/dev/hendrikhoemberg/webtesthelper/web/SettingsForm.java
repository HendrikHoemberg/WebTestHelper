package dev.hendrikhoemberg.webtesthelper.web;

import dev.hendrikhoemberg.webtesthelper.catalog.ImapSettings;
import dev.hendrikhoemberg.webtesthelper.catalog.SmtpSettings;
import dev.hendrikhoemberg.webtesthelper.catalog.TlsMode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.List;
import java.util.Optional;

@Getter
@Setter
@NoArgsConstructor
public class SettingsForm {

    private String host;
    private int port = 587;
    private TlsMode tls = TlsMode.STARTTLS;
    private String username;
    private String password = "";
    private String fromAddress;
    private String imapHost;
    private int imapPort = 993;
    private TlsMode imapTls = TlsMode.STARTTLS;
    private String imapUsername;
    private String imapPassword = "";
    private String imapFolder = "INBOX";
    private String imapVerificationAddress;
    private String baseUrl;
    private String redirectAllMailTo;
    private Boolean schedulingPaused;
    private String fallbackRecipients = "";
    private String webhookUrl = "";
    private boolean webhookEnabled = false;
    private boolean webhookOnlyCritical = true;

    public static SettingsForm from(SmtpSettings smtp, ImapSettings imap, String baseUrl, Optional<String> redirectAllMailTo,
                                    boolean schedulingPaused, List<String> fallbackRecipients) {
        return from(smtp, imap, baseUrl, redirectAllMailTo, schedulingPaused, fallbackRecipients, "", false, true);
    }

    public static SettingsForm from(SmtpSettings smtp, ImapSettings imap, String baseUrl, Optional<String> redirectAllMailTo,
                                    boolean schedulingPaused, List<String> fallbackRecipients,
                                    String webhookUrl, boolean webhookEnabled) {
        return from(smtp, imap, baseUrl, redirectAllMailTo, schedulingPaused, fallbackRecipients, webhookUrl, webhookEnabled, true);
    }

    public static SettingsForm from(SmtpSettings smtp, ImapSettings imap, String baseUrl, Optional<String> redirectAllMailTo,
                                    boolean schedulingPaused, List<String> fallbackRecipients,
                                    String webhookUrl, boolean webhookEnabled, boolean webhookOnlyCritical) {
        SettingsForm form = new SettingsForm();
        if (smtp != null) {
            form.setHost(smtp.host());
            form.setPort(smtp.port() > 0 ? smtp.port() : 587);
            form.setTls(smtp.tls() != null ? smtp.tls() : TlsMode.STARTTLS);
            form.setUsername(smtp.username());
            // Password is intentionally left empty ("") on read/render
            form.setPassword("");
            form.setFromAddress(smtp.fromAddress());
        }
        if (imap != null) {
            form.setImapHost(imap.host());
            form.setImapPort(imap.port() > 0 ? imap.port() : 993);
            form.setImapTls(imap.tls() != null ? imap.tls() : TlsMode.STARTTLS);
            form.setImapUsername(imap.username());
            // Password is intentionally left empty ("") on read/render
            form.setImapPassword("");
            form.setImapFolder(imap.folder() != null && !imap.folder().isBlank() ? imap.folder() : "INBOX");
            form.setImapVerificationAddress(imap.verificationAddress());
        }
        form.setBaseUrl(baseUrl);
        form.setRedirectAllMailTo(redirectAllMailTo.orElse(""));
        form.setSchedulingPaused(schedulingPaused);
        form.setFallbackRecipients(fallbackRecipients != null ? String.join(", ", fallbackRecipients) : "");
        form.setWebhookUrl(webhookUrl != null ? webhookUrl : "");
        form.setWebhookEnabled(webhookEnabled);
        form.setWebhookOnlyCritical(webhookOnlyCritical);
        return form;
    }
}
