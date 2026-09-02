package dev.hendrikhoemberg.webtesthelper.web;

import dev.hendrikhoemberg.webtesthelper.catalog.AppSettings;
import dev.hendrikhoemberg.webtesthelper.catalog.CredentialService;
import dev.hendrikhoemberg.webtesthelper.catalog.RecipientService;
import dev.hendrikhoemberg.webtesthelper.catalog.SiteService;
import dev.hendrikhoemberg.webtesthelper.catalog.SiteSummary;
import dev.hendrikhoemberg.webtesthelper.checks.CheckRegistry;
import dev.hendrikhoemberg.webtesthelper.findings.Finding;
import dev.hendrikhoemberg.webtesthelper.findings.FindingPage;
import dev.hendrikhoemberg.webtesthelper.findings.FindingQuery;
import dev.hendrikhoemberg.webtesthelper.findings.FindingService;
import dev.hendrikhoemberg.webtesthelper.findings.OpenFindingCounts;
import dev.hendrikhoemberg.webtesthelper.model.CheckSetting;
import dev.hendrikhoemberg.webtesthelper.model.CheckType;
import dev.hendrikhoemberg.webtesthelper.model.CrawlBudget;
import dev.hendrikhoemberg.webtesthelper.model.Evidence;
import dev.hendrikhoemberg.webtesthelper.model.ObservedStatus;
import dev.hendrikhoemberg.webtesthelper.model.RunScope;
import dev.hendrikhoemberg.webtesthelper.model.RunStatus;
import dev.hendrikhoemberg.webtesthelper.model.RunTrigger;
import dev.hendrikhoemberg.webtesthelper.model.Severity;
import dev.hendrikhoemberg.webtesthelper.model.SiteContext;
import dev.hendrikhoemberg.webtesthelper.model.TriageStatus;
import dev.hendrikhoemberg.webtesthelper.model.UrlNormalizer;
import dev.hendrikhoemberg.webtesthelper.reporting.FindingView;
import dev.hendrikhoemberg.webtesthelper.reporting.FindingViewFactory;
import dev.hendrikhoemberg.webtesthelper.runner.RunService;
import dev.hendrikhoemberg.webtesthelper.runner.RunSummary;
import dev.hendrikhoemberg.webtesthelper.scheduling.Schedule;
import dev.hendrikhoemberg.webtesthelper.scheduling.ScheduleService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

@org.junit.jupiter.api.parallel.ResourceLock("spring-context")
@WebMvcTest(SiteController.class)
@Import(SiteDetailModel.class)
class SiteDetailControllerTest {

    @Autowired
    MockMvc mvc;

    @MockitoBean
    SiteService siteService;

    @MockitoBean
    RunService runService;

    @MockitoBean
    CheckRegistry checkRegistry;

    @MockitoBean
    ScheduleService scheduleService;

    @MockitoBean
    RecipientService recipientService;

    @MockitoBean
    CredentialService credentialService;

    @MockitoBean
    AppSettings appSettings;

    @MockitoBean
    AppUserService appUserService;

    @MockitoBean
    FindingService findingService;

    @MockitoBean
    FindingViewFactory findingViewFactory;

    private SiteContext sampleContext() {
        Map<CheckType, CheckSetting> settings = new EnumMap<>(CheckType.class);
        settings.put(CheckType.PAGE_STATUS, new CheckSetting(true, null, Map.of()));
        settings.put(CheckType.DEAD_LINK, new CheckSetting(true, null, Map.of()));
        settings.put(CheckType.CONSOLE_ERRORS, new CheckSetting(false, null, Map.of()));
        return new SiteContext(
                42L,
                "Acme Shop",
                UrlNormalizer.normalize("https://acme.example.com/").orElseThrow(),
                new CrawlBudget(120, 3, Duration.ofMinutes(15)),
                List.of("/katalog/*", "/angebote/*"),
                List.of("/intern/*", "/warenkorb/*"),
                List.of(),
                true,
                "AcmeBot/2.0",
                settings
        );
    }

    private RunSummary sampleRun() {
        return new RunSummary(
                101L, 42L, RunStatus.COMPLETED, RunTrigger.MANUAL, RunScope.FULL,
                Instant.parse("2026-08-25T10:00:00Z"),
                Instant.parse("2026-08-25T10:00:05Z"),
                Instant.parse("2026-08-25T10:02:30Z"),
                85, 0, 3, 1, 2, false, null, false, null,
                Set.of(CheckType.PAGE_STATUS, CheckType.DEAD_LINK)
        );
    }

    private List<Schedule> defaultSchedules() {
        return List.of(
                new Schedule(11L, 42L, RunScope.PULSE, "0 0 3 * * *", "Europe/Berlin",
                        true, null, Instant.parse("2026-08-26T01:00:00Z")),
                new Schedule(12L, 42L, RunScope.FULL, "0 0 3 * * SUN", "Europe/Berlin",
                        true, null, Instant.parse("2026-08-30T01:00:00Z")),
                new Schedule(13L, 42L, RunScope.DEEP, "0 0 3 1 * *", "Europe/Berlin",
                        true, null, Instant.parse("2026-09-01T01:00:00Z")));
    }

    private Finding sampleFinding() {
        return new Finding(900L, 42L, "fp", CheckType.DEAD_LINK, "https://acme.example.com/katalog",
                "https://acme.example.com/katalog", Severity.ERROR, "check.DEAD_LINK.message",
                List.of("https://acme.example.com/katalog"), Evidence.NONE, ObservedStatus.ACTIVE,
                TriageStatus.UNTRIAGED, null, 1L, 1L, null, null, 1, 1,
                Instant.parse("2026-08-25T10:02:30Z"), Instant.parse("2026-08-25T10:02:30Z"));
    }

    private void stubCommon() {
        when(siteService.contextFor(42L)).thenReturn(sampleContext());
        when(siteService.summary(42L)).thenReturn(new SiteSummary(42L, "Acme Shop", "https://acme.example.com/", true, 3));
        when(runService.recentForSite(42L, 1)).thenReturn(List.of(sampleRun()));
        when(runService.recentForSite(42L, 20)).thenReturn(List.of(sampleRun()));
        when(scheduleService.forSite(42L)).thenReturn(defaultSchedules());
        when(findingService.openCountsBySite()).thenReturn(Map.of(42L, new OpenFindingCounts(0, 1, 2, 0)));
        when(findingService.search(any(FindingQuery.class))).thenReturn(new FindingPage(List.of(), 1, 5, 0));
        when(checkRegistry.all()).thenReturn(CheckRegistry.standard().all());
        when(checkRegistry.categories()).thenReturn(CheckRegistry.standard().categories());
        when(checkRegistry.category(any())).thenAnswer(inv -> CheckRegistry.standard().category(inv.getArgument(0)));
    }

    @Test
    @WithMockUser(roles = "USER")
    void getUebersichtRendersHealthCardsAndTopFindings() throws Exception {
        stubCommon();
        when(findingService.search(any(FindingQuery.class)))
                .thenReturn(new FindingPage(List.of(sampleFinding()), 1, 5, 1));
        when(findingViewFactory.of(any(Finding.class), any(Locale.class)))
                .thenReturn(new FindingView(900L, "Tote Links", "HTTP 404", "Linkziel prüfen",
                        "https://acme.example.com/katalog", false, 1, Severity.ERROR, TriageStatus.UNTRIAGED));

        mvc.perform(get("/websites/42"))
                .andExpect(status().isOk())
                .andExpect(view().name("websites/uebersicht"))
                .andExpect(model().attributeExists("site", "trafficLight", "openCounts", "lastRun", "nextRun", "topFindings"))
                .andExpect(content().string(containsString("Acme Shop")))
                .andExpect(content().string(containsString("https://acme.example.com/")))
                .andExpect(content().string(containsString("Offene Feststellungen")))
                .andExpect(content().string(containsString("Zu allen Feststellungen")))
                .andExpect(content().string(containsString("/websites/42/laeufe")))
                .andExpect(content().string(containsString("/websites/42/journeys")))
                .andExpect(content().string(containsString("/websites/42/konfiguration")))
                .andExpect(content().string(containsString("Tote Links")))
                .andExpect(content().string(containsString("Details")))
                // monitoring-first: the raw check toggle list stays off the overview
                .andExpect(content().string(not(containsString("Prüfungen speichern"))));
    }

    @Test
    @WithMockUser(roles = "USER")
    void getLaeufeRendersRunHistory() throws Exception {
        stubCommon();

        mvc.perform(get("/websites/42/laeufe"))
                .andExpect(status().isOk())
                .andExpect(view().name("websites/laeufe"))
                .andExpect(content().string(containsString("Verlauf der Prüfläufe")))
                .andExpect(content().string(containsString("101")))
                .andExpect(content().string(containsString("Abgeschlossen")));
    }

    @Test
    @WithMockUser(roles = "USER")
    void getKonfigurationRendersGroupedChecksAndPanels() throws Exception {
        stubCommon();

        mvc.perform(get("/websites/42/konfiguration"))
                .andExpect(status().isOk())
                .andExpect(view().name("websites/konfiguration"))
                .andExpect(content().string(containsString("Prüfumfang & Grenzen")))
                .andExpect(content().string(containsString("Prüfungen speichern")))
                .andExpect(content().string(containsString("name=\"aktiv\"")))
                .andExpect(content().string(containsString("schweregrad[PAGE_STATUS]")))
                .andExpect(content().string(containsString("Inhalt")))
                .andExpect(content().string(containsString("Technik")))
                .andExpect(content().string(containsString("Rechtliches")));
    }

    @Test
    @WithMockUser(roles = "USER")
    void getUebersichtRendersJourneysTabAndDropsTheSitesRoute() throws Exception {
        stubCommon();

        String html = mvc.perform(get("/websites/42"))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString("/sites/42/journeys"))))
                .andReturn().getResponse().getContentAsString();

        int tabBarStart = html.indexOf("class=\"site-tabs\"");
        String tabBar = html.substring(tabBarStart, html.indexOf("</div>", tabBarStart));
        assertThat(tabBar).contains("/websites/42/journeys").contains("Abläufe");
    }

    @Test
    @WithMockUser(roles = "USER")
    void adminOnlyAffordancesAreHiddenFromAUser() throws Exception {
        stubCommon();

        mvc.perform(get("/websites/42"))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString("sec:authorize"))))
                .andExpect(content().string(not(containsString("/websites/42/bearbeiten"))))
                .andExpect(content().string(not(containsString("/websites/42/loeschen"))));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void konfigurationOffersAdminAffordances() throws Exception {
        stubCommon();

        mvc.perform(get("/websites/42/konfiguration"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("/websites/42/bearbeiten")))
                .andExpect(content().string(containsString("/websites/42/loeschen")))
                .andExpect(content().string(not(containsString("sec:authorize"))));
    }

    @Test
    @WithMockUser(roles = "USER")
    void unknownSiteReturns404OnEveryTab() throws Exception {
        when(siteService.contextFor(999L)).thenThrow(new IllegalArgumentException("Site existiert nicht: 999"));
        mvc.perform(get("/websites/999")).andExpect(status().isNotFound());
        mvc.perform(get("/websites/999/laeufe")).andExpect(status().isNotFound());
        mvc.perform(get("/websites/999/konfiguration")).andExpect(status().isNotFound());
    }

    @Test
    @WithMockUser(roles = "USER")
    void postPruefenCallsEnqueueAndRedirectsToRunDetail() throws Exception {
        when(runService.enqueue(42L, RunTrigger.MANUAL, RunScope.FULL)).thenReturn(101L);

        mvc.perform(post("/websites/42/pruefen").with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/laeufe/101"));

        verify(runService).enqueue(42L, RunTrigger.MANUAL, RunScope.FULL);
    }

    @Test
    @WithMockUser(roles = "USER")
    void postPruefenWithUnknownIdReturns404() throws Exception {
        when(runService.enqueue(999L, RunTrigger.MANUAL, RunScope.FULL))
                .thenThrow(new IllegalArgumentException("Site 999 existiert nicht"));

        mvc.perform(post("/websites/999/pruefen").with(csrf()))
                .andExpect(status().isNotFound());
    }
}
