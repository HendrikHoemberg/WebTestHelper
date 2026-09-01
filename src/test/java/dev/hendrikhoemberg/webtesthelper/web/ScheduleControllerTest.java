package dev.hendrikhoemberg.webtesthelper.web;

import dev.hendrikhoemberg.webtesthelper.catalog.AppSettings;
import dev.hendrikhoemberg.webtesthelper.catalog.CredentialService;
import dev.hendrikhoemberg.webtesthelper.catalog.RecipientService;
import dev.hendrikhoemberg.webtesthelper.catalog.SiteService;
import dev.hendrikhoemberg.webtesthelper.catalog.SiteSummary;
import dev.hendrikhoemberg.webtesthelper.checks.CheckRegistry;
import dev.hendrikhoemberg.webtesthelper.findings.FindingService;
import dev.hendrikhoemberg.webtesthelper.model.CheckSetting;
import dev.hendrikhoemberg.webtesthelper.model.CheckType;
import dev.hendrikhoemberg.webtesthelper.model.CrawlBudget;
import dev.hendrikhoemberg.webtesthelper.model.RunScope;
import dev.hendrikhoemberg.webtesthelper.model.SiteContext;
import dev.hendrikhoemberg.webtesthelper.model.UrlNormalizer;
import dev.hendrikhoemberg.webtesthelper.reporting.FindingViewFactory;
import dev.hendrikhoemberg.webtesthelper.runner.RunService;
import dev.hendrikhoemberg.webtesthelper.scheduling.Schedule;
import dev.hendrikhoemberg.webtesthelper.scheduling.ScheduleService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

@org.junit.jupiter.api.parallel.ResourceLock("spring-context")
@WebMvcTest(ScheduleController.class)
@Import(SiteDetailModel.class)
class ScheduleControllerTest {

    @Autowired
    MockMvc mvc;

    @MockitoBean
    ScheduleService scheduleService;

    @MockitoBean
    SiteService siteService;

    @MockitoBean
    RunService runService;

    @MockitoBean
    CheckRegistry checkRegistry;

    @MockitoBean
    AppUserService appUserService;

    @MockitoBean
    RecipientService recipientService;

    @MockitoBean
    CredentialService credentialService;

    @MockitoBean
    AppSettings appSettings;

    @MockitoBean
    FindingService findingService;

    @MockitoBean
    FindingViewFactory findingViewFactory;

    private final Instant fixedNow = Instant.parse("2026-08-25T10:00:00Z");

    @BeforeEach
    void stubDetailPage() {
        // A site that exists, so the error re-render has everything the konfiguration page needs.
        var settings = new EnumMap<CheckType, CheckSetting>(CheckType.class);
        settings.put(CheckType.PAGE_STATUS, new CheckSetting(true, null, Map.of()));
        when(siteService.contextFor(1L)).thenReturn(new SiteContext(
                1L,
                "Acme Shop",
                UrlNormalizer.normalize("https://acme.example.com/").orElseThrow(),
                new CrawlBudget(120, 3, Duration.ofMinutes(15)),
                List.of(), List.of(), List.of(),
                true, "AcmeBot/2.0", settings));
        when(siteService.summary(1L)).thenReturn(new SiteSummary(1L, "Acme Shop", "https://acme.example.com/", true, 0));
        when(runService.recentForSite(1L, 20)).thenReturn(List.of());
        when(runService.recentForSite(1L, 1)).thenReturn(List.of());
        when(findingService.openCountsBySite()).thenReturn(Map.of());
        when(checkRegistry.all()).thenReturn(CheckRegistry.standard().all());
        when(checkRegistry.category(any())).thenAnswer(inv -> CheckRegistry.standard().category(inv.getArgument(0)));
        when(recipientService.list(1L)).thenReturn(List.of());
        when(appSettings.fallbackRecipients()).thenReturn(List.of());
    }

    private List<Schedule> defaultSchedules() {
        return List.of(
                new Schedule(11L, 1L, RunScope.PULSE, "0 0 3 * * *", "Europe/Berlin",
                        true, null, Instant.parse("2026-08-26T01:00:00Z")),
                new Schedule(12L, 1L, RunScope.FULL, "0 0 3 * * SUN", "Europe/Berlin",
                        true, null, Instant.parse("2026-08-30T01:00:00Z")),
                new Schedule(13L, 1L, RunScope.DEEP, "0 0 3 1 * *", "Europe/Berlin",
                        true, null, Instant.parse("2026-09-01T01:00:00Z")));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void postThreeValidTimesComposesCronsAndRedirects() throws Exception {
        when(scheduleService.forSite(1L)).thenReturn(defaultSchedules());

        mvc.perform(post("/websites/1/zeitplaene")
                        .with(csrf())
                        .param("zeitplaene[0].scope", "PULSE")
                        .param("zeitplaene[0].zeit", "03:00")
                        .param("zeitplaene[0].timezone", "Europe/Berlin")
                        .param("zeitplaene[0].enabled", "true")
                        .param("zeitplaene[1].scope", "FULL")
                        .param("zeitplaene[1].zeit", "09:30")
                        .param("zeitplaene[1].timezone", "Europe/Berlin")
                        .param("zeitplaene[1].enabled", "true")
                        .param("zeitplaene[2].scope", "DEEP")
                        .param("zeitplaene[2].zeit", "02:15")
                        .param("zeitplaene[2].timezone", "Europe/Berlin")
                        .param("zeitplaene[2].enabled", "true"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/websites/1/konfiguration"));

        verify(scheduleService, times(3)).update(anyLong(), anyString(), anyString(), anyBoolean(), any());
        verify(scheduleService).update(eq(11L), eq("0 0 3 * * *"), eq("Europe/Berlin"), eq(true), any());
        verify(scheduleService).update(eq(12L), eq("0 30 9 * * SUN"), eq("Europe/Berlin"), eq(true), any());
        verify(scheduleService).update(eq(13L), eq("0 15 2 1 * *"), eq("Europe/Berlin"), eq(true), any());
    }

    @Test
    @WithMockUser(roles = "USER")
    void postAsUserIsForbidden() throws Exception {
        mvc.perform(post("/websites/1/zeitplaene")
                        .with(csrf())
                        .param("zeitplaene[0].scope", "PULSE")
                        .param("zeitplaene[0].zeit", "03:00")
                        .param("zeitplaene[0].timezone", "Europe/Berlin")
                        .param("zeitplaene[0].enabled", "true"))
                .andExpect(status().isForbidden());

        verify(scheduleService, never()).update(anyLong(), anyString(), anyString(), anyBoolean(), any());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void postWithoutCsrfTokenIsForbidden() throws Exception {
        mvc.perform(post("/websites/1/zeitplaene")
                        .param("zeitplaene[0].scope", "PULSE")
                        .param("zeitplaene[0].zeit", "03:00")
                        .param("zeitplaene[0].timezone", "Europe/Berlin")
                        .param("zeitplaene[0].enabled", "true"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void invalidTimeRerendersWithFieldErrorAndCallsUpdateZeroTimes() throws Exception {
        when(scheduleService.forSite(1L)).thenReturn(defaultSchedules());

        mvc.perform(post("/websites/1/zeitplaene")
                        .with(csrf())
                        .param("zeitplaene[0].scope", "PULSE")
                        .param("zeitplaene[0].zeit", "03:00")
                        .param("zeitplaene[0].timezone", "Europe/Berlin")
                        .param("zeitplaene[0].enabled", "true")
                        .param("zeitplaene[1].scope", "FULL")
                        .param("zeitplaene[1].zeit", "25:00")
                        .param("zeitplaene[1].timezone", "Europe/Berlin")
                        .param("zeitplaene[1].enabled", "true")
                        .param("zeitplaene[2].scope", "DEEP")
                        .param("zeitplaene[2].zeit", "02:15")
                        .param("zeitplaene[2].timezone", "Europe/Berlin")
                        .param("zeitplaene[2].enabled", "true"))
                .andExpect(status().isOk())
                .andExpect(view().name("websites/konfiguration"))
                .andExpect(model().attributeHasFieldErrors("zeitplaene", "zeitplaene[1].zeit"));

        verify(scheduleService, never()).update(anyLong(), anyString(), anyString(), anyBoolean(), any());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void aFilledAdvancedCronIsTakenVerbatimAndIgnoresTheTime() throws Exception {
        when(scheduleService.forSite(1L)).thenReturn(defaultSchedules());

        mvc.perform(post("/websites/1/zeitplaene")
                        .with(csrf())
                        .param("zeitplaene[0].scope", "PULSE")
                        .param("zeitplaene[0].zeit", "03:00")
                        .param("zeitplaene[0].timezone", "Europe/Berlin")
                        .param("zeitplaene[0].enabled", "true")
                        .param("zeitplaene[1].scope", "FULL")
                        .param("zeitplaene[1].zeit", "25:00")
                        .param("zeitplaene[1].cron", "0 0 3 * * SUN")
                        .param("zeitplaene[1].timezone", "Europe/Berlin")
                        .param("zeitplaene[1].enabled", "true")
                        .param("zeitplaene[2].scope", "DEEP")
                        .param("zeitplaene[2].zeit", "02:15")
                        .param("zeitplaene[2].timezone", "Europe/Berlin")
                        .param("zeitplaene[2].enabled", "true"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/websites/1/konfiguration"));

        verify(scheduleService).update(eq(12L), eq("0 0 3 * * SUN"), eq("Europe/Berlin"), eq(true), any());
        verify(scheduleService, times(3)).update(anyLong(), anyString(), anyString(), anyBoolean(), any());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void blankTimezoneRerendersWithFieldErrorAndCancelsTheWholeSave() throws Exception {
        when(scheduleService.forSite(1L)).thenReturn(defaultSchedules());

        mvc.perform(post("/websites/1/zeitplaene")
                        .with(csrf())
                        .param("zeitplaene[0].scope", "PULSE")
                        .param("zeitplaene[0].zeit", "03:00")
                        .param("zeitplaene[0].timezone", "Europe/Berlin")
                        .param("zeitplaene[0].enabled", "true")
                        .param("zeitplaene[1].scope", "FULL")
                        .param("zeitplaene[1].zeit", "09:30")
                        .param("zeitplaene[1].timezone", "")
                        .param("zeitplaene[1].enabled", "true")
                        .param("zeitplaene[2].scope", "DEEP")
                        .param("zeitplaene[2].zeit", "02:15")
                        .param("zeitplaene[2].timezone", "Europe/Berlin")
                        .param("zeitplaene[2].enabled", "true"))
                .andExpect(status().isOk())
                .andExpect(view().name("websites/konfiguration"))
                .andExpect(model().attributeHasFieldErrors("zeitplaene", "zeitplaene[1].timezone"));

        verify(scheduleService, never()).update(anyLong(), anyString(), anyString(), anyBoolean(), any());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void anUnparseableAdvancedCronIsAFieldErrorOnThatRowNotAnException() throws Exception {
        when(scheduleService.forSite(1L)).thenReturn(defaultSchedules());

        mvc.perform(post("/websites/1/zeitplaene")
                        .with(csrf())
                        .param("zeitplaene[0].scope", "PULSE")
                        .param("zeitplaene[0].zeit", "03:00")
                        .param("zeitplaene[0].timezone", "Europe/Berlin")
                        .param("zeitplaene[0].enabled", "true")
                        .param("zeitplaene[1].scope", "FULL")
                        .param("zeitplaene[1].cron", "not a cron")
                        .param("zeitplaene[1].timezone", "Europe/Berlin")
                        .param("zeitplaene[1].enabled", "true"))
                .andExpect(status().isOk())
                .andExpect(view().name("websites/konfiguration"))
                .andExpect(model().attributeHasFieldErrors("zeitplaene", "zeitplaene[1].cron"));

        verify(scheduleService, never()).update(anyLong(), anyString(), anyString(), anyBoolean(), any());
    }

}
