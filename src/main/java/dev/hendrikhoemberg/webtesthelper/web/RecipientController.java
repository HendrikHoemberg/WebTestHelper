package dev.hendrikhoemberg.webtesthelper.web;

import dev.hendrikhoemberg.webtesthelper.catalog.AppSettings;
import dev.hendrikhoemberg.webtesthelper.catalog.Recipient;
import dev.hendrikhoemberg.webtesthelper.catalog.RecipientService;
import dev.hendrikhoemberg.webtesthelper.catalog.SiteService;
import dev.hendrikhoemberg.webtesthelper.checks.CheckDescriptor;
import dev.hendrikhoemberg.webtesthelper.checks.CheckRegistry;
import dev.hendrikhoemberg.webtesthelper.model.SiteContext;
import dev.hendrikhoemberg.webtesthelper.runner.RunService;
import dev.hendrikhoemberg.webtesthelper.runner.RunSummary;
import dev.hendrikhoemberg.webtesthelper.scheduling.Schedule;
import dev.hendrikhoemberg.webtesthelper.scheduling.ScheduleService;
import org.springframework.context.MessageSource;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

@Controller
public class RecipientController {

    private static final Pattern EMAIL_PATTERN = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");

    private final RecipientService recipientService;
    private final SiteService siteService;
    private final RunService runService;
    private final CheckRegistry checkRegistry;
    private final ScheduleService scheduleService;
    private final AppSettings appSettings;
    private final MessageSource messageSource;

    public RecipientController(
            RecipientService recipientService,
            SiteService siteService,
            RunService runService,
            CheckRegistry checkRegistry,
            ScheduleService scheduleService,
            AppSettings appSettings,
            MessageSource messageSource) {
        this.recipientService = recipientService;
        this.siteService = siteService;
        this.runService = runService;
        this.checkRegistry = checkRegistry;
        this.scheduleService = scheduleService;
        this.appSettings = appSettings;
        this.messageSource = messageSource;
    }

    @PostMapping("/websites/{id}/empfaenger")
    public String add(
            @PathVariable("id") long id,
            @RequestParam(name = "email", required = false) String email,
            Model model,
            Locale locale) {
        if (email == null || email.isBlank() || !EMAIL_PATTERN.matcher(email.strip().toLowerCase(Locale.ROOT)).matches()) {
            populateDetailModel(id, model);
            model.addAttribute("email", email);
            model.addAttribute("recipientError", messageSource.getMessage("ui.websites.detail.empfaenger.fehler.invalid", null, locale));
            return "websites/detail";
        }

        try {
            recipientService.add(id, email);
            return "redirect:/websites/" + id;
        } catch (IllegalArgumentException e) {
            populateDetailModel(id, model);
            model.addAttribute("email", email);
            if ("recipient.email.duplicate".equals(e.getMessage())) {
                model.addAttribute("recipientError", messageSource.getMessage("ui.websites.detail.empfaenger.fehler.duplicate", null, locale));
            } else {
                model.addAttribute("recipientError", messageSource.getMessage("ui.websites.detail.empfaenger.fehler.invalid", null, locale));
            }
            return "websites/detail";
        }
    }

    @PostMapping("/websites/{id}/empfaenger/{rid}/loeschen")
    public String remove(@PathVariable("id") long id, @PathVariable("rid") long rid) {
        recipientService.remove(id, rid);
        return "redirect:/websites/" + id;
    }

    private void populateDetailModel(long siteId, Model model) {
        SiteContext site = siteService.contextFor(siteId);
        List<RunSummary> recentRuns = runService.recentForSite(siteId, 20);
        List<CheckDescriptor> activeChecks = checkRegistry.all().stream()
                .filter(check -> site.enabled(check.type()))
                .toList();
        List<Schedule> schedules = scheduleService.forSite(siteId);
        List<Recipient> recipients = recipientService.list(siteId);
        List<String> fallbackRecipients = appSettings.fallbackRecipients();

        model.addAttribute("site", site);
        model.addAttribute("recentRuns", recentRuns);
        model.addAttribute("activeChecks", activeChecks);
        model.addAttribute("zeitplaene", ScheduleFormModel.of(schedules));
        model.addAttribute("zeitplaeneDetail", ScheduleView.detailByScope(schedules));
        model.addAttribute("recipients", recipients);
        model.addAttribute("fallbackRecipients", fallbackRecipients);
    }
}
