package dev.hendrikhoemberg.webtesthelper.web;

import dev.hendrikhoemberg.webtesthelper.catalog.AppSettings;
import dev.hendrikhoemberg.webtesthelper.catalog.EmailAddresses;
import dev.hendrikhoemberg.webtesthelper.catalog.ImapSettings;
import dev.hendrikhoemberg.webtesthelper.catalog.SmtpSettings;
import dev.hendrikhoemberg.webtesthelper.catalog.TlsMode;
import dev.hendrikhoemberg.webtesthelper.reporting.DeliveryResult;
import dev.hendrikhoemberg.webtesthelper.reporting.MailRenderer;
import dev.hendrikhoemberg.webtesthelper.reporting.OutboundMail;
import dev.hendrikhoemberg.webtesthelper.reporting.OutboxService;
import dev.hendrikhoemberg.webtesthelper.runner.CapacityService;
import jakarta.mail.Folder;
import jakarta.mail.Session;
import jakarta.mail.Store;
import jakarta.validation.Valid;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.Properties;


@Controller
@RequestMapping("/einstellungen")
public class SettingsController {

    private final AppSettings appSettings;
    private final MailRenderer mailRenderer;
    private final OutboxService outboxService;
    private final CapacityService capacityService;

    public SettingsController(
            AppSettings appSettings,
            MailRenderer mailRenderer,
            OutboxService outboxService,
            CapacityService capacityService
    ) {
        this.appSettings = appSettings;
        this.mailRenderer = mailRenderer;
        this.outboxService = outboxService;
        this.capacityService = capacityService;
    }

    @GetMapping
    public String index(Model model) {
        SmtpSettings smtp = appSettings.smtp();
        ImapSettings imap = appSettings.imap();
        SettingsForm form = SettingsForm.from(
                smtp,
                imap,
                appSettings.baseUrl(),
                appSettings.redirectAllMailTo(),
                appSettings.schedulingPaused(),
                appSettings.fallbackRecipients()
        );
        model.addAttribute("form", form);
        model.addAttribute("tlsModes", TlsMode.values());
        model.addAttribute("smtpConfigured", smtp != null && smtp.configured());
        model.addAttribute("imapConfigured", imap != null && imap.configured());
        model.addAttribute("systemlast", capacityService.current(outboxService.failedCount()));
        return "einstellungen/index";
    }

    @PostMapping
    public String save(@Valid @ModelAttribute("form") SettingsForm form,
                       BindingResult bindingResult,
                       Model model,
                       RedirectAttributes redirectAttributes) {
        if (form.getBaseUrl() == null || form.getBaseUrl().isBlank()) {
            bindingResult.rejectValue("baseUrl", "ui.einstellungen.fehler.baseUrl.blank", "Die Basis-URL darf nicht leer sein.");
        } else {
            String trimmed = form.getBaseUrl().strip();
            if (!trimmed.startsWith("http://") && !trimmed.startsWith("https://")) {
                bindingResult.rejectValue("baseUrl", "ui.einstellungen.fehler.baseUrl.scheme", "Die Basis-URL muss mit http:// oder https:// beginnen.");
            }
        }

        if (!EmailAddresses.allValid(form.getFallbackRecipients())) {
            bindingResult.rejectValue("fallbackRecipients", "ui.einstellungen.fehler.fallbackRecipients.invalid",
                    "Mindestens eine der angegebenen E-Mail-Adressen ist ungültig.");
        }

        if (bindingResult.hasErrors()) {
            SmtpSettings currentSmtp = appSettings.smtp();
            ImapSettings currentImap = appSettings.imap();
            model.addAttribute("tlsModes", TlsMode.values());
            model.addAttribute("smtpConfigured", currentSmtp != null && currentSmtp.configured());
            model.addAttribute("imapConfigured", currentImap != null && currentImap.configured());
            model.addAttribute("systemlast", capacityService.current(outboxService.failedCount()));
            return "einstellungen/index";
        }

        String password = form.getPassword();
        if (password == null || password.isBlank()) {
            SmtpSettings currentSmtp = appSettings.smtp();
            password = (currentSmtp != null) ? currentSmtp.password() : null;
        }

        SmtpSettings smtp = new SmtpSettings(
                form.getHost() != null ? form.getHost().strip() : null,
                form.getPort(),
                form.getTls() != null ? form.getTls() : TlsMode.STARTTLS,
                form.getUsername() != null ? form.getUsername().strip() : null,
                password,
                form.getFromAddress() != null ? form.getFromAddress().strip() : null
        );

        String imapPassword = form.getImapPassword();
        if (imapPassword == null || imapPassword.isBlank()) {
            ImapSettings currentImap = appSettings.imap();
            imapPassword = (currentImap != null) ? currentImap.password() : null;
        }

        ImapSettings imap = new ImapSettings(
                form.getImapHost() != null ? form.getImapHost().strip() : null,
                form.getImapPort(),
                form.getImapTls() != null ? form.getImapTls() : TlsMode.STARTTLS,
                form.getImapUsername() != null ? form.getImapUsername().strip() : null,
                imapPassword,
                form.getImapFolder() != null && !form.getImapFolder().isBlank() ? form.getImapFolder().strip() : "INBOX",
                form.getImapVerificationAddress() != null ? form.getImapVerificationAddress().strip() : null
        );

        appSettings.saveSmtp(smtp);
        appSettings.saveImap(imap);
        appSettings.saveBaseUrl(form.getBaseUrl());
        appSettings.saveRedirectAllMailTo(form.getRedirectAllMailTo());
        appSettings.saveFallbackRecipients(form.getFallbackRecipients());
        appSettings.saveSchedulingPaused(Boolean.TRUE.equals(form.getSchedulingPaused()));

        redirectAttributes.addFlashAttribute("gespeichert", true);
        return "redirect:/einstellungen";
    }

    @PostMapping("/testmail")
    public String sendTestMail(RedirectAttributes redirectAttributes) {
        SmtpSettings smtp = appSettings.smtp();
        if (smtp == null || !smtp.configured()) {
            redirectAttributes.addFlashAttribute("testmailFehler", "Der SMTP-Server ist nicht konfiguriert.");
            return "redirect:/einstellungen";
        }

        String recipient = smtp.fromAddress();
        String baseUrl = appSettings.baseUrl();
        OutboundMail mail = mailRenderer.testMail(recipient, baseUrl);
        long id = outboxService.enqueue(mail);
        DeliveryResult result = outboxService.sendNow(id);

        if (result.success()) {
            redirectAttributes.addFlashAttribute("testmailErfolg", true);
        } else {
            redirectAttributes.addFlashAttribute("testmailFehler", result.error());
        }

        return "redirect:/einstellungen";
    }

    @PostMapping("/postfach-test")
    public String testPostfach(RedirectAttributes redirectAttributes) {
        ImapSettings imap = appSettings.imap();
        if (imap == null || !imap.configured()) {
            redirectAttributes.addFlashAttribute("postfachFehler", "Das Prüfpostfach ist nicht konfiguriert.");
            return "redirect:/einstellungen";
        }

        try {
            Properties props = new Properties();
            String protocol;
            int defaultPort;
            if (imap.tls() == TlsMode.SSL) {
                protocol = "imaps";
                defaultPort = 993;
                props.put("mail.imaps.ssl.enable", "true");
                props.put("mail.imaps.connectiontimeout", "10000");
                props.put("mail.imaps.timeout", "10000");
            } else {
                protocol = "imap";
                defaultPort = 143;
                if (imap.tls() == TlsMode.STARTTLS) {
                    props.put("mail.imap.starttls.enable", "true");
                    props.put("mail.imap.starttls.required", "true");
                } else {
                    props.put("mail.imap.starttls.enable", "false");
                }
                props.put("mail.imap.connectiontimeout", "10000");
                props.put("mail.imap.timeout", "10000");
            }

            Session session = Session.getInstance(props);
            int port = imap.port() > 0 ? imap.port() : defaultPort;
            Store store = session.getStore(protocol);
            try {
                store.connect(imap.host(), port, imap.username(), imap.password());
                String folderName = imap.folder() != null && !imap.folder().isBlank() ? imap.folder() : "INBOX";
                Folder folder = store.getFolder(folderName);
                try {
                    folder.open(Folder.READ_ONLY);
                    int count = folder.getMessageCount();
                    redirectAttributes.addFlashAttribute("postfachErfolg", count);
                } finally {
                    if (folder.isOpen()) {
                        folder.close(false);
                    }
                }
            } finally {
                if (store.isConnected()) {
                    store.close();
                }
            }
        } catch (Exception e) {
            String msg = e.getMessage() != null && !e.getMessage().isBlank() ? e.getMessage() : e.getClass().getSimpleName();
            redirectAttributes.addFlashAttribute("postfachFehler", msg);
        }

        return "redirect:/einstellungen";
    }
}
