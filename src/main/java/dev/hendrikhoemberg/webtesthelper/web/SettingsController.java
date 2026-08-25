package dev.hendrikhoemberg.webtesthelper.web;

import dev.hendrikhoemberg.webtesthelper.catalog.AppSettings;
import dev.hendrikhoemberg.webtesthelper.catalog.SmtpSettings;
import dev.hendrikhoemberg.webtesthelper.catalog.TlsMode;
import dev.hendrikhoemberg.webtesthelper.reporting.DeliveryResult;
import dev.hendrikhoemberg.webtesthelper.reporting.MailRenderer;
import dev.hendrikhoemberg.webtesthelper.reporting.OutboundMail;
import dev.hendrikhoemberg.webtesthelper.reporting.OutboxService;
import jakarta.validation.Valid;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
@RequestMapping("/einstellungen")
public class SettingsController {

    private final AppSettings appSettings;
    private final MailRenderer mailRenderer;
    private final OutboxService outboxService;

    public SettingsController(
            AppSettings appSettings,
            MailRenderer mailRenderer,
            OutboxService outboxService
    ) {
        this.appSettings = appSettings;
        this.mailRenderer = mailRenderer;
        this.outboxService = outboxService;
    }

    @GetMapping
    public String index(Model model) {
        SmtpSettings smtp = appSettings.smtp();
        SettingsForm form = SettingsForm.from(
                smtp,
                appSettings.baseUrl(),
                appSettings.redirectAllMailTo(),
                appSettings.schedulingPaused()
        );
        model.addAttribute("form", form);
        model.addAttribute("tlsModes", TlsMode.values());
        model.addAttribute("smtpConfigured", smtp != null && smtp.configured());
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

        if (bindingResult.hasErrors()) {
            SmtpSettings currentSmtp = appSettings.smtp();
            model.addAttribute("tlsModes", TlsMode.values());
            model.addAttribute("smtpConfigured", currentSmtp != null && currentSmtp.configured());
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

        appSettings.saveSmtp(smtp);
        appSettings.saveBaseUrl(form.getBaseUrl());
        appSettings.saveRedirectAllMailTo(form.getRedirectAllMailTo());
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
}
