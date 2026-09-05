package dev.hendrikhoemberg.webtesthelper.web;

import dev.hendrikhoemberg.webtesthelper.catalog.AppSettings;
import dev.hendrikhoemberg.webtesthelper.catalog.Credential;
import dev.hendrikhoemberg.webtesthelper.catalog.CredentialService;
import dev.hendrikhoemberg.webtesthelper.catalog.Recipient;
import dev.hendrikhoemberg.webtesthelper.catalog.RecipientService;
import dev.hendrikhoemberg.webtesthelper.catalog.SiteService;
import dev.hendrikhoemberg.webtesthelper.checks.CheckDescriptor;
import dev.hendrikhoemberg.webtesthelper.checks.CheckRegistry;
import dev.hendrikhoemberg.webtesthelper.findings.FindingPage;
import dev.hendrikhoemberg.webtesthelper.findings.FindingQuery;
import dev.hendrikhoemberg.webtesthelper.findings.FindingService;
import dev.hendrikhoemberg.webtesthelper.findings.OpenFindingCounts;
import dev.hendrikhoemberg.webtesthelper.model.CheckCategory;
import dev.hendrikhoemberg.webtesthelper.model.CheckSetting;
import dev.hendrikhoemberg.webtesthelper.model.Severity;
import dev.hendrikhoemberg.webtesthelper.model.SiteContext;
import dev.hendrikhoemberg.webtesthelper.reporting.FindingView;
import dev.hendrikhoemberg.webtesthelper.reporting.FindingViewFactory;
import dev.hendrikhoemberg.webtesthelper.reporting.TrafficLight;
import dev.hendrikhoemberg.webtesthelper.runner.LastRun;
import dev.hendrikhoemberg.webtesthelper.runner.RunService;
import dev.hendrikhoemberg.webtesthelper.runner.RunSummary;
import dev.hendrikhoemberg.webtesthelper.scheduling.Schedule;
import dev.hendrikhoemberg.webtesthelper.scheduling.ScheduleService;
import org.springframework.stereotype.Component;
import org.springframework.ui.Model;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Everything the website tabs ({@code websites/uebersicht}, {@code websites/laeufe} and
 * {@code websites/konfiguration}) need, in one place.
 *
 * <p>Three controllers render that template — {@link SiteController} on GET,
 * {@link RecipientController} when it re-renders the panel with a field error, and
 * {@link CredentialController} on error — and an attribute
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
    private final CredentialService credentialService;
    private final AppSettings appSettings;
    private final FindingService findingService;
    private final FindingViewFactory findingViewFactory;

    public SiteDetailModel(SiteService siteService, RunService runService, CheckRegistry checkRegistry,
                           ScheduleService scheduleService, RecipientService recipientService,
                           CredentialService credentialService, AppSettings appSettings,
                           FindingService findingService, FindingViewFactory findingViewFactory) {
        this.siteService = siteService;
        this.runService = runService;
        this.checkRegistry = checkRegistry;
        this.scheduleService = scheduleService;
        this.recipientService = recipientService;
        this.credentialService = credentialService;
        this.appSettings = appSettings;
        this.findingService = findingService;
        this.findingViewFactory = findingViewFactory;
    }

    public void populate(long siteId, Model model) {
        populate(siteService.contextFor(siteId), model);
    }

    private void populate(SiteContext site, Model model) {
        long siteId = site.siteId();
        List<RunSummary> recentRuns = runService.recentForSite(siteId, RECENT_RUNS);
        List<Schedule> schedules = scheduleService.forSite(siteId);
        List<Recipient> recipients = recipientService.list(siteId);
        List<String> fallbackRecipients = appSettings.fallbackRecipients();
        List<Credential> credentials = credentialService.list(siteId);

        model.addAttribute("site", site);
        model.addAttribute("recentRuns", recentRuns);
        model.addAttribute("checkRows", checkRows(site));
        model.addAttribute("zeitplaene", ScheduleFormModel.of(schedules));
        model.addAttribute("zeitplaeneDetail", ScheduleView.detailByScope(schedules));
        model.addAttribute("recipients", recipients);
        model.addAttribute("fallbackRecipients", fallbackRecipients);
        model.addAttribute("credentials", credentials);
    }

    /** Administrative view: budget, patterns, key pages, grouped checks and the panel forms. */
    public void populateConfig(long siteId, Model model) {
        SiteContext site = siteService.contextFor(siteId);
        populate(site, model);
        model.addAttribute("checkCategories", checkRegistry.categories());
        model.addAttribute("trafficLight", trafficLight(site));
    }

    /**
     * Everything the {@code konfiguration} screen needs except the schedule form, which a caller
     * keeps bound so its field errors survive a re-render ({@link ScheduleController}).
     */
    public void populateConfigContext(long siteId, Model model) {
        SiteContext site = siteService.contextFor(siteId);
        model.addAttribute("site", site);
        model.addAttribute("checkRows", checkRows(site));
        model.addAttribute("checkCategories", checkRegistry.categories());
        model.addAttribute("recipients", recipientService.list(siteId));
        model.addAttribute("fallbackRecipients", appSettings.fallbackRecipients());
        model.addAttribute("credentials", credentialService.list(siteId));
        model.addAttribute("trafficLight", trafficLight(site));
    }

    /** The run history tab: the site plus its recent runs. */
    public void populateRuns(long siteId, Model model) {
        SiteContext site = siteService.contextFor(siteId);
        model.addAttribute("site", site);
        model.addAttribute("trafficLight", trafficLight(site));
        model.addAttribute("recentRuns", runService.recentForSite(siteId, RECENT_RUNS));
    }

    /** Monitoring-first tab: site health, last/next run, counts and a top-findings preview. */
    public void populateOverview(long siteId, Model model, Locale locale) {
        SiteContext site = siteService.contextFor(siteId);
        model.addAttribute("site", site);

        List<RunSummary> runs = runService.recentForSite(siteId, 1);
        RunSummary lastRun = runs.isEmpty() ? null : runs.get(0);
        OpenFindingCounts counts = findingService.openCountsBySite().getOrDefault(siteId, OpenFindingCounts.none());

        model.addAttribute("lastRun", lastRun);
        model.addAttribute("openCounts", counts);
        model.addAttribute("trafficLight", trafficLight(site));

        Schedule nextRun = scheduleService.forSite(siteId).stream()
                .filter(Schedule::enabled)
                .filter(s -> s.nextFireAt() != null)
                .min(Comparator.comparing(Schedule::nextFireAt))
                .orElse(null);
        model.addAttribute("nextRun", nextRun);

        FindingPage page = findingService.search(new FindingQuery(siteId, Set.of(), Set.of(), null, Set.of(), 1, 5));
        model.addAttribute("topFindings", page.findings().stream()
                .map(finding -> findingViewFactory.of(finding, locale))
                .toList());
    }

    private TrafficLight trafficLight(SiteContext site) {
        List<RunSummary> runs = runService.recentForSite(site.siteId(), 1);
        RunSummary last = runs.isEmpty() ? null : runs.get(0);
        LastRun lr = last == null ? null
                : new LastRun(site.siteId(), last.id(), last.status(), last.finishedAt(), last.partialCoverage());
        boolean enabled = siteService.summary(site.siteId()).enabled();
        return TrafficLight.of(enabled, lr,
                findingService.openCountsBySite().getOrDefault(site.siteId(), OpenFindingCounts.none()));
    }

    private List<CheckRowView> checkRows(SiteContext site) {
        return checkRegistry.all().stream()
                .map(check -> {
                    CheckSetting setting = site.checkSettings().get(check.type());
                    return new CheckRowView(check,
                            setting != null && setting.enabled(),
                            setting == null ? null : setting.severityOverride(),
                            checkRegistry.category(check.type()));
                })
                .toList();
    }

    /** One editable row of the per-check configuration: the check, its site state and its group. */
    public record CheckRowView(CheckDescriptor check, boolean enabled, Severity severityOverride,
                               CheckCategory category) {
    }
}
