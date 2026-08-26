package dev.hendrikhoemberg.webtesthelper.reporting;

import dev.hendrikhoemberg.webtesthelper.catalog.AppSettings;
import dev.hendrikhoemberg.webtesthelper.catalog.SiteService;
import dev.hendrikhoemberg.webtesthelper.catalog.SiteSummary;
import dev.hendrikhoemberg.webtesthelper.findings.FindingService;
import dev.hendrikhoemberg.webtesthelper.findings.OpenFindingCounts;
import dev.hendrikhoemberg.webtesthelper.model.RunScope;
import dev.hendrikhoemberg.webtesthelper.model.RunStatus;
import dev.hendrikhoemberg.webtesthelper.runner.CapacityService;
import dev.hendrikhoemberg.webtesthelper.runner.LastRun;
import dev.hendrikhoemberg.webtesthelper.runner.RunService;
import dev.hendrikhoemberg.webtesthelper.runner.SystemCapacity;
import dev.hendrikhoemberg.webtesthelper.scheduling.Schedule;
import dev.hendrikhoemberg.webtesthelper.scheduling.ScheduleService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The dashboard read model. The four querying collaborators are mocked; each test fixes the
 * maps the site list is joined against and asserts only what this service decides: which tiles
 * exist, which findings count toward the header, and when the whole thing next fires.
 */
class DashboardServiceTest {

    private SiteService sites;
    private FindingService findings;
    private RunService runs;
    private ScheduleService schedules;
    private AppSettings appSettings;
    private CapacityService capacityService;
    private OutboxService outbox;
    private DashboardService dashboard;

    @BeforeEach
    void setUp() {
        sites = mock(SiteService.class);
        findings = mock(FindingService.class);
        runs = mock(RunService.class);
        schedules = mock(ScheduleService.class);
        appSettings = mock(AppSettings.class);
        capacityService = mock(CapacityService.class);
        outbox = mock(OutboxService.class);
        dashboard = new DashboardService(sites, findings, runs, schedules, appSettings, capacityService, outbox);

        when(runs.runsInFlight()).thenReturn(3);
        when(appSettings.schedulingPaused()).thenReturn(false);
        when(outbox.failedCount()).thenReturn(2);
        when(capacityService.current(anyInt())).thenReturn(capacity());

        when(sites.summaries()).thenReturn(List.of(
                site(1, "Alpha", "https://alpha.example.com/", true),
                site(2, "Beta", "https://beta.example.com/", true),
                site(3, "Gamma", "https://gamma.example.com/", false)));
        when(findings.openCountsBySite()).thenReturn(Map.of(
                1L, new OpenFindingCounts(2, 1, 1, 2),
                3L, new OpenFindingCounts(5, 3, 1, 0)));
        when(runs.lastTerminalPerSite()).thenReturn(Map.of(
                1L, new LastRun(1L, 11L, RunStatus.COMPLETED, Instant.parse("2026-08-26T09:00:00Z"), false),
                3L, new LastRun(3L, 31L, RunStatus.FAILED, Instant.parse("2026-08-26T08:00:00Z"), false)));
        when(schedules.nextFirePerSite()).thenReturn(Map.of(
                1L, schedule(1, 10L, "0 0 3 * * *", Instant.parse("2026-08-26T10:00:00Z"))));
    }

    @Test
    void buildsATileForEverySiteEvenOneAbsentFromEveryReadModel() {
        DashboardView view = dashboard.overview();

        assertThat(view.tiles()).hasSize(3);

        SiteTile alpha = tileById(view, 1);
        assertThat(alpha.light()).isEqualTo(TrafficLight.ROT);
        assertThat(alpha.counts()).isEqualTo(new OpenFindingCounts(2, 1, 1, 2));
        assertThat(alpha.lastRun()).isEqualTo(
                new LastRun(1L, 11L, RunStatus.COMPLETED, Instant.parse("2026-08-26T09:00:00Z"), false));
        assertThat(alpha.nextRun()).isEqualTo(
                schedule(1, 10L, "0 0 3 * * *", Instant.parse("2026-08-26T10:00:00Z")));

        SiteTile beta = tileById(view, 2);
        assertThat(beta.light()).isEqualTo(TrafficLight.GELB);
        assertThat(beta.lastRun()).isNull();
        assertThat(beta.counts()).isEqualTo(OpenFindingCounts.none());
        assertThat(beta.nextRun()).isNull();

        SiteTile gamma = tileById(view, 3);
        assertThat(gamma.light()).isEqualTo(TrafficLight.GRAU);
    }

    @Test
    void totalsSumOnlyEnabledSitesSoADisabledSitesStaleFindingsDoNotInflateTheHeader() {
        DashboardView view = dashboard.overview();

        assertThat(view.totals()).isEqualTo(new OpenFindingCounts(2, 1, 1, 2));
    }

    @Test
    void nextFireAtIsTheEarliestAcrossSites() {
        when(sites.summaries()).thenReturn(List.of(
                site(1, "Alpha", "https://alpha.example.com/", true),
                site(2, "Beta", "https://beta.example.com/", true)));
        when(findings.openCountsBySite()).thenReturn(Map.of());
        when(runs.lastTerminalPerSite()).thenReturn(Map.of());
        when(schedules.nextFirePerSite()).thenReturn(Map.of(
                1L, schedule(1, 10L, "0 0 3 * * *", Instant.parse("2026-08-26T11:00:00Z")),
                2L, schedule(2, 20L, "0 0 3 * * *", Instant.parse("2026-08-26T10:00:00Z"))));

        assertThat(dashboard.overview().nextFireAt()).isEqualTo(Instant.parse("2026-08-26T10:00:00Z"));
    }

    @Test
    void nextFireAtIsNullWhenSchedulingIsPaused() {
        when(appSettings.schedulingPaused()).thenReturn(true);

        assertThat(dashboard.overview().nextFireAt()).isNull();
    }

    @Test
    void capacityReceivesTheFailedMailCountFromTheCaller() {
        dashboard.overview();

        verify(capacityService).current(2);
    }

    private static SiteTile tileById(DashboardView view, long siteId) {
        return view.tiles().stream().filter(t -> t.siteId() == siteId)
                .findFirst().orElseThrow();
    }

    private static SiteSummary site(long id, String name, String baseUrl, boolean enabled) {
        return new SiteSummary(id, name, baseUrl, enabled, 7);
    }

    private static Schedule schedule(long siteId, long id, String cron, Instant nextFireAt) {
        return new Schedule(id, siteId, RunScope.PULSE, cron, "Europe/Berlin", true, null, nextFireAt);
    }

    private static SystemCapacity capacity() {
        return new SystemCapacity(4, 1, 2, 0, java.time.Duration.ofSeconds(30), 5);
    }
}
