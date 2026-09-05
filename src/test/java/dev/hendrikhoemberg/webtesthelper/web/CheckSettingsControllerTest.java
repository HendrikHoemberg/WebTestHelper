package dev.hendrikhoemberg.webtesthelper.web;

import dev.hendrikhoemberg.webtesthelper.auth.AppUserService;
import dev.hendrikhoemberg.webtesthelper.catalog.AppSettings;
import dev.hendrikhoemberg.webtesthelper.catalog.CredentialService;
import dev.hendrikhoemberg.webtesthelper.catalog.RecipientService;
import dev.hendrikhoemberg.webtesthelper.catalog.SiteService;
import dev.hendrikhoemberg.webtesthelper.catalog.SiteSummary;
import dev.hendrikhoemberg.webtesthelper.checks.CheckRegistry;
import dev.hendrikhoemberg.webtesthelper.findings.FindingService;
import dev.hendrikhoemberg.webtesthelper.model.CheckType;
import dev.hendrikhoemberg.webtesthelper.model.CrawlBudget;
import dev.hendrikhoemberg.webtesthelper.model.Severity;
import dev.hendrikhoemberg.webtesthelper.model.SiteContext;
import dev.hendrikhoemberg.webtesthelper.model.UrlNormalizer;
import dev.hendrikhoemberg.webtesthelper.reporting.FindingViewFactory;
import dev.hendrikhoemberg.webtesthelper.runner.RunService;
import dev.hendrikhoemberg.webtesthelper.scheduling.ScheduleService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

@org.junit.jupiter.api.parallel.ResourceLock("spring-context")
@WebMvcTest(CheckSettingsController.class)
@Import(SiteDetailModel.class)
class CheckSettingsControllerTest {

    private static final long SITE_ID = 42L;

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

    private SiteContext testSite;

    @BeforeEach
    void setUp() {
        testSite = new SiteContext(
                SITE_ID,
                "Acme Shop",
                UrlNormalizer.normalize("https://acme.example.com/").orElseThrow(),
                new CrawlBudget(120, 3, Duration.ofMinutes(15)),
                List.of(), List.of(), List.of(),
                true, "AcmeBot/2.0", Map.of());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void savePersistsEnabledStateAndSeverityOverrideForEveryCheck() throws Exception {
        when(siteService.summary(SITE_ID)).thenReturn(new SiteSummary(SITE_ID, "Acme Shop",
                "https://acme.example.com/", true, 17));
        when(checkRegistry.all()).thenReturn(CheckRegistry.standard().all());

        mvc.perform(post("/websites/42/pruefungen")
                        .with(csrf())
                        .param("aktiv", "PAGE_STATUS")
                        .param("aktiv", "DEAD_LINK")
                        .param("schweregrad[PAGE_STATUS]", "WARN")
                        .param("schweregrad[DEAD_LINK]", ""))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/websites/42/konfiguration"))
                .andExpect(flash().attributeExists("flashMessage"));

        verify(siteService).updateCheckSetting(SITE_ID, CheckType.PAGE_STATUS, true, Severity.WARN);
        verify(siteService).updateCheckSetting(SITE_ID, CheckType.DEAD_LINK, true, null);
        verify(siteService).updateCheckSetting(eq(SITE_ID), eq(CheckType.TLS_CERT), eq(false), isNull());
        verify(siteService, times(CheckRegistry.standard().all().size()))
                .updateCheckSetting(eq(SITE_ID), any(CheckType.class), anyBoolean(), nullable(Severity.class));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void emptyFormDisablesAllChecksWithoutOverrides() throws Exception {
        when(siteService.summary(SITE_ID)).thenReturn(new SiteSummary(SITE_ID, "Acme Shop",
                "https://acme.example.com/", true, 17));
        when(checkRegistry.all()).thenReturn(CheckRegistry.standard().all());

        mvc.perform(post("/websites/42/pruefungen").with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/websites/42/konfiguration"));

        verify(siteService).updateCheckSetting(eq(SITE_ID), eq(CheckType.PAGE_STATUS), eq(false), isNull());
        verify(siteService).updateCheckSetting(eq(SITE_ID), eq(CheckType.TLS_CERT), eq(false), isNull());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void anInvalidSeverityReRendersTheDetailPageWithAnErrorAndSavesNothing() throws Exception {
        when(siteService.summary(SITE_ID)).thenReturn(new SiteSummary(SITE_ID, "Acme Shop",
                "https://acme.example.com/", true, 17));
        when(checkRegistry.all()).thenReturn(CheckRegistry.standard().all());
        when(siteService.contextFor(SITE_ID)).thenReturn(testSite);
        when(runService.recentForSite(SITE_ID, 20)).thenReturn(List.of());
        when(scheduleService.forSite(SITE_ID)).thenReturn(List.of());
        when(recipientService.list(SITE_ID)).thenReturn(List.of());
        when(credentialService.list(SITE_ID)).thenReturn(List.of());
        when(appSettings.fallbackRecipients()).thenReturn(List.of());

        mvc.perform(post("/websites/42/pruefungen")
                        .with(csrf())
                        .param("aktiv", "PAGE_STATUS")
                        .param("schweregrad[PAGE_STATUS]", "KATASTROPHAL"))
                .andExpect(status().isOk())
                .andExpect(view().name("websites/konfiguration"))
                .andExpect(content().string(containsString("ungültig")))
                .andExpect(content().string(not(containsString("KATASTROPHAL"))));

        verify(siteService, never()).updateCheckSetting(anyLong(), any(), anyBoolean(), any());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void unknownSiteReturns404() throws Exception {
        when(siteService.summary(999L)).thenThrow(new IllegalArgumentException("Site existiert nicht: 999"));

        mvc.perform(post("/websites/999/pruefungen").with(csrf()))
                .andExpect(status().isNotFound());
    }
}
