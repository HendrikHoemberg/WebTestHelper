package dev.hendrikhoemberg.webtesthelper.web;

import dev.hendrikhoemberg.webtesthelper.catalog.AppSettings;
import dev.hendrikhoemberg.webtesthelper.catalog.RecipientService;
import dev.hendrikhoemberg.webtesthelper.catalog.SiteService;
import dev.hendrikhoemberg.webtesthelper.checks.CheckRegistry;
import dev.hendrikhoemberg.webtesthelper.model.CheckSetting;
import dev.hendrikhoemberg.webtesthelper.model.CheckType;
import dev.hendrikhoemberg.webtesthelper.model.CrawlBudget;
import dev.hendrikhoemberg.webtesthelper.model.RunScope;
import dev.hendrikhoemberg.webtesthelper.model.RunStatus;
import dev.hendrikhoemberg.webtesthelper.model.RunTrigger;
import dev.hendrikhoemberg.webtesthelper.model.SiteContext;
import dev.hendrikhoemberg.webtesthelper.model.UrlNormalizer;
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
import java.util.Map;
import java.util.Set;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
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
    AppSettings appSettings;

    @MockitoBean
    AppUserService appUserService;

    private List<Schedule> defaultSchedules() {
        return List.of(
                new Schedule(11L, 42L, RunScope.PULSE, "0 0 3 * * *", "Europe/Berlin",
                        true, null, Instant.parse("2026-08-26T01:00:00Z")),
                new Schedule(12L, 42L, RunScope.FULL, "0 0 3 * * SUN", "Europe/Berlin",
                        true, null, Instant.parse("2026-08-30T01:00:00Z")),
                new Schedule(13L, 42L, RunScope.DEEP, "0 0 3 1 * *", "Europe/Berlin",
                        true, null, Instant.parse("2026-09-01T01:00:00Z")));
    }

    @Test
    @WithMockUser(roles = "USER")
    void getSiteDetailRendersBudgetPatternsHistoryAndActiveChecks() throws Exception {
        Map<CheckType, CheckSetting> settings = new EnumMap<>(CheckType.class);
        settings.put(CheckType.PAGE_STATUS, new CheckSetting(true, null, Map.of()));
        settings.put(CheckType.DEAD_LINK, new CheckSetting(true, null, Map.of()));
        settings.put(CheckType.CONSOLE_ERRORS, new CheckSetting(false, null, Map.of()));

        SiteContext context = new SiteContext(
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

        RunSummary runSummary = new RunSummary(
                101L,
                42L,
                RunStatus.COMPLETED,
                RunTrigger.MANUAL,
                RunScope.FULL,
                Instant.parse("2026-08-25T10:00:00Z"),
                Instant.parse("2026-08-25T10:00:05Z"),
                Instant.parse("2026-08-25T10:02:30Z"),
                85,
                0,
                3,
                1,
                2,
                false,
                null,
                false,
                null,
                Set.of(CheckType.PAGE_STATUS, CheckType.DEAD_LINK)
        );

        when(siteService.contextFor(42L)).thenReturn(context);
        when(runService.recentForSite(42L, 20)).thenReturn(List.of(runSummary));
        when(checkRegistry.all()).thenReturn(CheckRegistry.standard().all());
        when(scheduleService.forSite(42L)).thenReturn(defaultSchedules());

        mvc.perform(get("/websites/42"))
                .andExpect(status().isOk())
                .andExpect(view().name("websites/detail"))
                .andExpect(model().attributeExists("site", "recentRuns", "activeChecks"))
                .andExpect(content().string(containsString("Acme Shop")))
                .andExpect(content().string(containsString("https://acme.example.com/")))
                .andExpect(content().string(containsString("120")))
                .andExpect(content().string(containsString("3")))
                .andExpect(content().string(containsString("/katalog/*")))
                .andExpect(content().string(containsString("/angebote/*")))
                .andExpect(content().string(containsString("/intern/*")))
                .andExpect(content().string(containsString("/warenkorb/*")))
                .andExpect(content().string(containsString("Seitenstatus")))
                .andExpect(content().string(containsString("Tote Links")))
                .andExpect(content().string(containsString("101")))
                .andExpect(content().string(containsString("Abgeschlossen")));
    }

    @Test
    @WithMockUser(roles = "USER")
    void getSiteDetailRendersThreeTiersWithoutRawCronInProse() throws Exception {
        SiteContext context = new SiteContext(
                42L,
                "Acme Shop",
                UrlNormalizer.normalize("https://acme.example.com/").orElseThrow(),
                new CrawlBudget(120, 3, Duration.ofMinutes(15)),
                List.of(), List.of(), List.of(),
                true, "AcmeBot/2.0", Map.of()
        );
        when(siteService.contextFor(42L)).thenReturn(context);
        when(runService.recentForSite(42L, 20)).thenReturn(List.of());
        when(checkRegistry.all()).thenReturn(CheckRegistry.standard().all());
        when(scheduleService.forSite(42L)).thenReturn(defaultSchedules());

        mvc.perform(get("/websites/42"))
                .andExpect(status().isOk())
                .andExpect(view().name("websites/detail"))
                .andExpect(content().string(containsString("Puls-Prüfung")))
                .andExpect(content().string(containsString("Vollständige Prüfung")))
                .andExpect(content().string(containsString("Tiefenprüfung")))
                .andExpect(content().string(containsString("Zeitpläne")))
                // §13.1: the raw cron is an internal identifier and must not reach the reader's
                // prose. The common case is a time of day, so no cron literal appears anywhere.
                .andExpect(content().string(not(containsString("0 0 3 * *"))))
                .andExpect(content().string(not(containsString("0 0 3 * * SUN"))))
                .andExpect(content().string(not(containsString("0 0 3 1 * *"))))
                // A USER may read the schedule but not change it: POST /websites/*/zeitplaene is
                // ADMIN-only, so offering the controls would be a form that answers 403. The tier
                // state stays visible as prose instead.
                .andExpect(content().string(not(containsString("Zeitpläne speichern"))))
                .andExpect(content().string(not(containsString("Erweitert"))))
                .andExpect(content().string(containsString("Automatische Prüfung eingeschaltet")));
    }

    @Test
    @WithMockUser(roles = "USER")
    void aDisabledTierReadsAsSwitchedOffForAUser() throws Exception {
        SiteContext context = new SiteContext(
                42L,
                "Acme Shop",
                UrlNormalizer.normalize("https://acme.example.com/").orElseThrow(),
                new CrawlBudget(120, 3, Duration.ofMinutes(15)),
                List.of(), List.of(), List.of(),
                true, "AcmeBot/2.0", Map.of()
        );
        when(siteService.contextFor(42L)).thenReturn(context);
        when(runService.recentForSite(42L, 20)).thenReturn(List.of());
        when(checkRegistry.all()).thenReturn(CheckRegistry.standard().all());
        when(scheduleService.forSite(42L)).thenReturn(List.of(
                new Schedule(13L, 42L, RunScope.DEEP, "0 0 3 1 * *", "Europe/Berlin",
                        false, null, Instant.parse("2026-09-01T01:00:00Z"))));

        // Without the controls, this sentence is the only thing telling a USER that the tier is off.
        mvc.perform(get("/websites/42"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Automatische Prüfung ausgeschaltet")))
                .andExpect(content().string(not(containsString("Automatische Prüfung eingeschaltet"))));
    }

    /**
     * Guards the Thymeleaf SpringSecurityDialect itself, not one screen's markup. Thymeleaf only
     * understands the {@code sec:} namespace when {@code thymeleaf-extras-springsecurity6} is on
     * the classpath; without that jar it treats {@code sec:authorize} as an unknown attribute and
     * copies it into the output, so every gated element renders for everybody and nothing fails.
     * That is exactly how the admin nav links, the Bearbeiten button and the schedule controls were
     * silently visible to a USER. A literal {@code sec:} in the response body is the tell.
     */
    @Test
    @WithMockUser(roles = "USER")
    void adminOnlyAffordancesAreHiddenFromAUserAndTheSecNamespaceIsNeverEmitted() throws Exception {
        SiteContext context = new SiteContext(
                42L,
                "Acme Shop",
                UrlNormalizer.normalize("https://acme.example.com/").orElseThrow(),
                new CrawlBudget(120, 3, Duration.ofMinutes(15)),
                List.of(), List.of(), List.of(),
                true, "AcmeBot/2.0", Map.of()
        );
        when(siteService.contextFor(42L)).thenReturn(context);
        when(runService.recentForSite(42L, 20)).thenReturn(List.of());
        when(checkRegistry.all()).thenReturn(CheckRegistry.standard().all());
        when(scheduleService.forSite(42L)).thenReturn(defaultSchedules());

        mvc.perform(get("/websites/42"))
                .andExpect(status().isOk())
                // The dialect processed the attributes rather than passing them through.
                .andExpect(content().string(not(containsString("sec:authorize"))))
                .andExpect(content().string(not(containsString("sec:authentication"))))
                // The routes behind these are already ADMIN-only in SecurityConfig; the point here
                // is that a USER is not offered a door that answers 403.
                .andExpect(content().string(not(containsString("/einstellungen"))))
                .andExpect(content().string(not(containsString("/postausgang"))))
                .andExpect(content().string(not(containsString("/websites/42/bearbeiten"))));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void getSiteDetailOffersTheScheduleControlsToAnAdmin() throws Exception {
        SiteContext context = new SiteContext(
                42L,
                "Acme Shop",
                UrlNormalizer.normalize("https://acme.example.com/").orElseThrow(),
                new CrawlBudget(120, 3, Duration.ofMinutes(15)),
                List.of(), List.of(), List.of(),
                true, "AcmeBot/2.0", Map.of()
        );
        when(siteService.contextFor(42L)).thenReturn(context);
        when(runService.recentForSite(42L, 20)).thenReturn(List.of());
        when(checkRegistry.all()).thenReturn(CheckRegistry.standard().all());
        when(scheduleService.forSite(42L)).thenReturn(defaultSchedules());

        mvc.perform(get("/websites/42"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Zeitpläne speichern")))
                .andExpect(content().string(containsString("Erweitert")))
                .andExpect(content().string(containsString("Zeitplan aktiviert")))
                // Even for the admin the raw cron stays out of the prose: it is pre-filled only in
                // the Erweitert input, and only when the stored expression does not fit the tier.
                .andExpect(content().string(not(containsString("0 0 3 * * SUN"))));
    }

    @Test
    @WithMockUser(roles = "USER")
    void getSiteDetailWithUnknownIdReturns404() throws Exception {
        when(siteService.contextFor(999L)).thenThrow(new IllegalArgumentException("Site existiert nicht: 999"));

        mvc.perform(get("/websites/999"))
                .andExpect(status().isNotFound());
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
    void postPruefenTwiceCallsEnqueueTwiceAndRedirectsToSameRun() throws Exception {
        when(runService.enqueue(42L, RunTrigger.MANUAL, RunScope.FULL)).thenReturn(101L);

        mvc.perform(post("/websites/42/pruefen").with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/laeufe/101"));

        mvc.perform(post("/websites/42/pruefen").with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/laeufe/101"));

        verify(runService, times(2)).enqueue(42L, RunTrigger.MANUAL, RunScope.FULL);
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
