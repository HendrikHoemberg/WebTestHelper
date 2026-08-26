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
import org.springframework.stereotype.Component;
import org.springframework.ui.Model;

import java.util.List;

/**
 * Everything {@code websites/detail} needs, in one place.
 *
 * <p>Two controllers render that template — {@link SiteController} on GET and
 * {@link RecipientController} when it re-renders the panel with a field error — and an attribute
 * added to one and not the other is a screen that works until someone types a bad address.
 */
@Component
public class SiteDetailModel {

    private static final int RECENT_RUNS = 20;

    private final SiteService siteService;
    private final RunService runService;
    private final CheckRegistry checkRegistry;
    private final ScheduleService scheduleService;
    private final RecipientService recipientService;
    private final AppSettings appSettings;

    public SiteDetailModel(SiteService siteService, RunService runService, CheckRegistry checkRegistry,
                           ScheduleService scheduleService, RecipientService recipientService,
                           AppSettings appSettings) {
        this.siteService = siteService;
        this.runService = runService;
        this.checkRegistry = checkRegistry;
        this.scheduleService = scheduleService;
        this.recipientService = recipientService;
        this.appSettings = appSettings;
    }

    public void populate(long siteId, Model model) {
        SiteContext site = siteService.contextFor(siteId);
        List<RunSummary> recentRuns = runService.recentForSite(siteId, RECENT_RUNS);
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
