package dev.hendrikhoemberg.webtesthelper.reporting;

import dev.hendrikhoemberg.webtesthelper.catalog.AppSettings;
import dev.hendrikhoemberg.webtesthelper.catalog.SiteService;
import dev.hendrikhoemberg.webtesthelper.catalog.SiteSummary;
import dev.hendrikhoemberg.webtesthelper.findings.FindingService;
import dev.hendrikhoemberg.webtesthelper.findings.OpenFindingCounts;
import dev.hendrikhoemberg.webtesthelper.runner.CapacityService;
import dev.hendrikhoemberg.webtesthelper.runner.LastRun;
import dev.hendrikhoemberg.webtesthelper.runner.RunService;
import dev.hendrikhoemberg.webtesthelper.runner.SystemCapacity;
import dev.hendrikhoemberg.webtesthelper.scheduling.Schedule;
import dev.hendrikhoemberg.webtesthelper.scheduling.ScheduleService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The dashboard read model (D61, spec 14). Assembles one tile per site by joining the site list
 * against the open-finding counts, the last terminal run and the next scheduled occurrence, then
 * decides the light. The query count is constant regardless of how many sites there are: the site
 * list, open counts, last runs, next fires and in-flight count are one call each, and the two
 * fixed reads — the scheduling flag and {@link CapacityService} — do not scale with site count.
 *
 * <p>The header totals count only <em>enabled</em> sites — a disabled site's stale findings are
 * noise, not a reason anyone opens the app — and {@code nextFireAt} is the earliest occurrence
 * across sites, or null while scheduling is paused (D41: a countdown to a run that will not start
 * is the worst kind of wrong).
 */
@Service
public class DashboardService {

    private final SiteService sites;
    private final FindingService findings;
    private final RunService runs;
    private final ScheduleService schedules;
    private final AppSettings appSettings;
    private final CapacityService capacityService;
    private final OutboxService outbox;

    public DashboardService(SiteService sites, FindingService findings, RunService runs,
                            ScheduleService schedules, AppSettings appSettings,
                            CapacityService capacityService, OutboxService outbox) {
        this.sites = sites;
        this.findings = findings;
        this.runs = runs;
        this.schedules = schedules;
        this.appSettings = appSettings;
        this.capacityService = capacityService;
        this.outbox = outbox;
    }

    @Transactional(readOnly = true)
    public DashboardView overview() {
        List<SiteSummary> siteList = sites.summaries();
        Map<Long, OpenFindingCounts> countsBySite = findings.openCountsBySite();
        Map<Long, LastRun> lastRuns = runs.lastTerminalPerSite();
        Map<Long, Schedule> nextFires = schedules.nextFirePerSite();
        int runsInFlight = runs.runsInFlight();

        boolean schedulingPaused = appSettings.schedulingPaused();
        SystemCapacity capacity = capacityService.current(outbox.failedCount());

        List<SiteTile> tiles = siteList.stream()
                .map(site -> tile(site, countsBySite.get(site.id()), lastRuns.get(site.id()),
                        nextFires.get(site.id())))
                .toList();

        OpenFindingCounts totals = totalsOverEnabled(siteList, countsBySite);
        Instant nextFireAt = schedulingPaused ? null : earliest(nextFires);

        return new DashboardView(tiles, totals, runsInFlight, nextFireAt, schedulingPaused, capacity);
    }

    private SiteTile tile(SiteSummary site, OpenFindingCounts counts, LastRun lastRun, Schedule nextRun) {
        OpenFindingCounts effective = counts != null ? counts : OpenFindingCounts.none();
        return new SiteTile(site.id(), site.name(), site.baseUrl(), site.enabled(),
                TrafficLight.of(site.enabled(), lastRun, effective),
                lastRun, effective, nextRun);
    }

    private OpenFindingCounts totalsOverEnabled(List<SiteSummary> siteList, Map<Long, OpenFindingCounts> countsBySite) {
        int errors = 0;
        int warnings = 0;
        int infos = 0;
        int untriaged = 0;
        for (SiteSummary site : siteList) {
            if (!site.enabled()) {
                continue;
            }
            OpenFindingCounts counts = countsBySite.get(site.id());
            if (counts == null) {
                continue;
            }
            errors += counts.errors();
            warnings += counts.warnings();
            infos += counts.infos();
            untriaged += counts.untriaged();
        }
        return new OpenFindingCounts(errors, warnings, infos, untriaged);
    }

    private Instant earliest(Map<Long, Schedule> nextFires) {
        return nextFires.values().stream()
                .map(Schedule::nextFireAt)
                .filter(Objects::nonNull)
                .min(Comparator.naturalOrder())
                .orElse(null);
    }
}
