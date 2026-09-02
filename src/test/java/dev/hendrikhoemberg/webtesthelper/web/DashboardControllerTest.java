package dev.hendrikhoemberg.webtesthelper.web;

import dev.hendrikhoemberg.webtesthelper.findings.OpenFindingCounts;
import dev.hendrikhoemberg.webtesthelper.model.RunScope;
import dev.hendrikhoemberg.webtesthelper.model.RunStatus;
import dev.hendrikhoemberg.webtesthelper.reporting.DashboardService;
import dev.hendrikhoemberg.webtesthelper.reporting.DashboardView;
import dev.hendrikhoemberg.webtesthelper.reporting.SiteTile;
import dev.hendrikhoemberg.webtesthelper.reporting.TrafficLight;
import dev.hendrikhoemberg.webtesthelper.runner.LastRun;
import dev.hendrikhoemberg.webtesthelper.runner.DashboardProperties;
import dev.hendrikhoemberg.webtesthelper.runner.SystemCapacity;
import dev.hendrikhoemberg.webtesthelper.scheduling.Schedule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

/**
 * The dashboard screen, asserted on its rendered body rather than its model (plan 7's lesson:
 * a controller test that only inspects model attributes proves nothing about the screen). Each
 * assertion is something a user would see or rely on — a label, a link, a poll cadence — not a
 * value the controller stuffed into {@code Model}.
 */
@org.junit.jupiter.api.parallel.ResourceLock("spring-context")
@WebMvcTest(DashboardController.class)
class DashboardControllerTest {

    @Autowired
    MockMvc mvc;

    @MockitoBean
    DashboardService dashboardService;

    @MockitoBean
    DashboardProperties dashboardProperties;

    @MockitoBean
    AppUserService appUserService;

    @BeforeEach
    void pollIntervalIsThirtySeconds() {
        when(dashboardProperties.pollInterval()).thenReturn(Duration.ofSeconds(30));
    }

    private DashboardView sampleView() {
        SiteTile red = new SiteTile(1L, "Alpha", "https://alpha.example.com/", true, TrafficLight.ROT,
                new LastRun(1L, 11L, RunStatus.COMPLETED, Instant.parse("2026-08-26T09:00:00Z"), false),
                new OpenFindingCounts(3, 1, 0, 2),
                new Schedule(10L, 1L, RunScope.FULL, "0 3 * * *", "Europe/Berlin", true,
                        Instant.parse("2026-08-26T03:00:00Z"), Instant.parse("2026-08-27T03:00:00Z")));

        SiteTile green = new SiteTile(2L, "Beta", "https://beta.example.com/", true, TrafficLight.GRUEN,
                new LastRun(2L, 12L, RunStatus.COMPLETED, Instant.parse("2026-08-26T08:00:00Z"), false),
                new OpenFindingCounts(0, 0, 0, 0),
                new Schedule(11L, 2L, RunScope.PULSE, "0 */6 * * *", "Europe/Berlin", true,
                        Instant.parse("2026-08-26T06:00:00Z"), Instant.parse("2026-08-26T12:00:00Z")));

        // Disabled -> GRAU. It still has open findings upstream, but neither those nor a next run
        // may reach the screen: a switched-off site's stale numbers are noise, and it fires nothing.
        SiteTile grau = new SiteTile(3L, "Gamma", "https://gamma.example.com/", false, TrafficLight.GRAU,
                new LastRun(3L, 31L, RunStatus.FAILED, Instant.parse("2026-08-26T08:00:00Z"), false),
                new OpenFindingCounts(5, 3, 1, 0),
                null);

        return new DashboardView(List.of(red, green, grau),
                new OpenFindingCounts(3, 1, 0, 2),
                2,
                Instant.parse("2026-08-27T03:00:00Z"),
                false,
                new SystemCapacity(2, 1, 4, 1, Duration.ofSeconds(30), 5));
    }

    @Test
    @WithMockUser(roles = "USER")
    void dashboardRendersTilesAndNeverLeaksRawEnumConstants() throws Exception {
        when(dashboardService.overview()).thenReturn(sampleView());

        mvc.perform(get("/"))
                .andExpect(status().isOk())
                .andExpect(view().name("uebersicht/index"))
                .andExpect(model().attributeExists("uebersicht"))
                .andExpect(content().string(containsString("Alpha")))
                .andExpect(content().string(containsString("3 Fehler")))
                // The disabled site's stale counts must not appear anywhere: not in its tile,
                // not in the enabled-sites-only header totals.
                .andExpect(content().string(not(containsString("5 Fehler"))))
                // The grid polls the fragment, not itself.
                .andExpect(content().string(containsString("hx-get=\"/uebersicht/kacheln\"")))
                .andExpect(content().string(containsString("hx-trigger=\"every 30s\"")))
                // Spec 13.1: no raw enum constant may reach the browser as text.
                .andExpect(content().string(not(containsString("ROT"))))
                .andExpect(content().string(not(containsString("COMPLETED"))))
                .andExpect(content().string(not(containsString("PULSE"))));
    }

    @Test
    @WithMockUser(roles = "USER")
    void dashboardShowsFriendlyCapacityStatusWithoutJargon() throws Exception {
        when(dashboardService.overview()).thenReturn(sampleView());

        // sampleView: 1 of 2 browser workers busy -> capacity available -> "Bereit für Prüfungen".
        mvc.perform(get("/"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Bereit für Prüfungen")))
                .andExpect(content().string(not(containsString("Browser-Arbeiter belegt"))))
                .andExpect(content().string(not(containsString("Hintergrund-Aufgaben"))))
                .andExpect(content().string(not(containsString("Warteschlange"))));
    }

    @Test
    @WithMockUser(roles = "USER")
    void dashboardShowsBusyStatusWhenAllWorkersBusy() throws Exception {
        DashboardView busy = new DashboardView(
                sampleView().tiles(),
                sampleView().totals(),
                sampleView().runsInFlight(),
                sampleView().nextFireAt(),
                false,
                new SystemCapacity(2, 2, 4, 1, Duration.ofSeconds(30), 5));

        when(dashboardService.overview()).thenReturn(busy);

        mvc.perform(get("/"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Ausgelastet")));
    }

    @Test
    @WithMockUser(roles = "USER")
    void grauTileRendersNeitherCountsNorNextRun() throws Exception {
        when(dashboardService.overview()).thenReturn(new DashboardView(
                List.of(new SiteTile(3L, "Gamma", "https://gamma.example.com/", false, TrafficLight.GRAU,
                        new LastRun(3L, 31L, RunStatus.FAILED, Instant.parse("2026-08-26T08:00:00Z"), false),
                        new OpenFindingCounts(5, 3, 1, 0),
                        null)),
                new OpenFindingCounts(0, 0, 0, 0),
                0,
                null,
                false,
                new SystemCapacity(2, 1, 4, 1, Duration.ofSeconds(30), 5)));

        mvc.perform(get("/"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Gamma")))
                .andExpect(content().string(containsString("Website ist deaktiviert.")))
                // A switched-off site is not a failure: its stale findings and its (non-existent)
                // next run stay off the screen.
                .andExpect(content().string(not(containsString("5 Fehler"))))
                .andExpect(content().string(not(containsString("3 Warnungen"))))
                .andExpect(content().string(not(containsString("Nächster Lauf"))));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void emptyDashboardRendersWelcomeCardAndAdminCta() throws Exception {
        when(dashboardService.overview()).thenReturn(new DashboardView(
                List.of(), new OpenFindingCounts(0, 0, 0, 0), 0, null, false,
                new SystemCapacity(2, 1, 4, 1, Duration.ofSeconds(30), 5)));

        mvc.perform(get("/"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Willkommen bei WebTestHelper")))
                .andExpect(content().string(containsString("/websites/neu")));
    }

    @Test
    @WithMockUser(roles = "USER")
    void emptyDashboardHidesTheAdminCtaFromNonAdmins() throws Exception {
        when(dashboardService.overview()).thenReturn(new DashboardView(
                List.of(), new OpenFindingCounts(0, 0, 0, 0), 0, null, false,
                new SystemCapacity(2, 1, 4, 1, Duration.ofSeconds(30), 5)));

        mvc.perform(get("/"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Willkommen bei WebTestHelper")))
                .andExpect(content().string(not(containsString("/websites/neu"))))
                .andExpect(content().string(containsString("Administrator")));
    }

    @Test
    @WithMockUser(roles = "USER")
    void kachelnFragmentIsAFragmentNotAPage() throws Exception {
        when(dashboardService.overview()).thenReturn(sampleView());

        mvc.perform(get("/uebersicht/kacheln"))
                .andExpect(status().isOk())
                .andExpect(view().name("fragments/kacheln :: kacheln"))
                .andExpect(content().string(containsString("Alpha")))
                .andExpect(content().string(containsString("3 Fehler")))
                // A document wrapper is markup HTMX would have to strip before swapping the div.
                .andExpect(content().string(not(containsString("<nav"))));
    }

    @Test
    @WithMockUser(roles = "USER")
    void nextRunOneMinuteAwayRendersGermanSingular() throws Exception {
        when(dashboardService.overview()).thenReturn(einzelView(90));

        mvc.perform(get("/"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("in 1 Minute")))
                .andExpect(content().string(not(containsString("in 1 Minuten"))));
    }

    @Test
    @WithMockUser(roles = "USER")
    void nextRunTwoMinutesAwayRendersGermanPlural() throws Exception {
        when(dashboardService.overview()).thenReturn(einzelView(150));

        mvc.perform(get("/"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("in 2 Minuten")));
    }

    // One enabled tile whose next occurrence lands `sekundenBisNaechsterLauf` seconds after the
    // test runs — mid-bucket values (90 s -> 1 minute, 150 s -> 2 minutes) so the displayed unit
    // can't shift across a boundary during the sub-second request.
    private DashboardView einzelView(long sekundenBisNaechsterLauf) {
        SiteTile tile = new SiteTile(5L, "Delta", "https://delta.example.com/", true, TrafficLight.GRUEN,
                new LastRun(5L, 41L, RunStatus.COMPLETED, Instant.now().minusSeconds(300), false),
                new OpenFindingCounts(0, 0, 0, 0),
                new Schedule(50L, 5L, RunScope.PULSE, "0 */6 * * *", "Europe/Berlin", true,
                        Instant.now().minusSeconds(3600), Instant.now().plusSeconds(sekundenBisNaechsterLauf)));
        return new DashboardView(List.of(tile),
                new OpenFindingCounts(0, 0, 0, 0),
                0,
                Instant.now().plusSeconds(sekundenBisNaechsterLauf),
                false,
                new SystemCapacity(2, 1, 4, 1, Duration.ofSeconds(30), 5));
    }
}
