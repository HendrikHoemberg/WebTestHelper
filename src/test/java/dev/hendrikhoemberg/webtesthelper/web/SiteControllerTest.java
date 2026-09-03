package dev.hendrikhoemberg.webtesthelper.web;

import dev.hendrikhoemberg.webtesthelper.catalog.AppSettings;
import dev.hendrikhoemberg.webtesthelper.catalog.CredentialService;
import dev.hendrikhoemberg.webtesthelper.catalog.RecipientService;
import dev.hendrikhoemberg.webtesthelper.catalog.SiteForm;
import dev.hendrikhoemberg.webtesthelper.catalog.SiteService;
import dev.hendrikhoemberg.webtesthelper.catalog.SiteSummary;
import dev.hendrikhoemberg.webtesthelper.findings.FindingService;
import dev.hendrikhoemberg.webtesthelper.model.CheckType;
import dev.hendrikhoemberg.webtesthelper.model.CrawlBudget;
import dev.hendrikhoemberg.webtesthelper.model.FormTestMode;
import dev.hendrikhoemberg.webtesthelper.model.NormalizedUrl;
import dev.hendrikhoemberg.webtesthelper.model.SiteContext;
import dev.hendrikhoemberg.webtesthelper.model.UrlNormalizer;
import dev.hendrikhoemberg.webtesthelper.checks.CheckRegistry;
import dev.hendrikhoemberg.webtesthelper.reporting.FindingViewFactory;
import dev.hendrikhoemberg.webtesthelper.runner.RunService;
import dev.hendrikhoemberg.webtesthelper.scheduling.ScheduleService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

@org.junit.jupiter.api.parallel.ResourceLock("spring-context")
@WebMvcTest(SiteController.class)
@Import(SiteDetailModel.class)
class SiteControllerTest {

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

    @Test
    @WithMockUser(roles = "USER")
    void getWebsitesAsUserReturnsSiteList() throws Exception {
        when(siteService.summaries()).thenReturn(List.of(
                new SiteSummary(1L, "Test Site", "https://example.com/", true, 10)
        ));

        mvc.perform(get("/websites"))
                .andExpect(status().isOk())
                .andExpect(view().name("websites/liste"))
                .andExpect(model().attributeExists("sites"))
                .andExpect(content().string(containsString("Öffnen →")));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void siteListOffersDeleteToAdmin() throws Exception {
        when(siteService.summaries()).thenReturn(List.of(
                new SiteSummary(42L, "Kunde A", "https://example.com/", true, 0)
        ));

        mvc.perform(get("/websites"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("/websites/42/loeschen")))
                .andExpect(content().string(containsString("Möchten Sie diese Website wirklich löschen?")));
    }

    @Test
    @WithMockUser(roles = "USER")
    void siteListHidesDeleteFromUser() throws Exception {
        when(siteService.summaries()).thenReturn(List.of(
                new SiteSummary(42L, "Kunde A", "https://example.com/", true, 0)
        ));

        mvc.perform(get("/websites"))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString("/websites/42/loeschen"))))
                .andExpect(content().string(not(containsString("Möchten Sie diese Website wirklich löschen?"))));
    }

    @Test
    @WithMockUser(roles = "USER")
    void getWebsitesNeuAsUserIsForbidden() throws Exception {
        mvc.perform(get("/websites/neu"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void getWebsitesNeuAsAdminReturnsForm() throws Exception {
        mvc.perform(get("/websites/neu"))
                .andExpect(status().isOk())
                .andExpect(view().name("websites/formular"))
                .andExpect(model().attributeExists("form"));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void getWebsitesNeuHidesExpertFieldsBehindCollapsedAdvancedToggle() throws Exception {
        mvc.perform(get("/websites/neu"))
                .andExpect(status().isOk())
                .andExpect(view().name("websites/formular"))
                .andExpect(content().string(containsString("Erweiterte Einstellungen (optional)")))
                .andExpect(content().string(containsString("{ erweitert: false }")))
                .andExpect(content().string(containsString("Seitenlimit")));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void getWebsitesEditShowsAdvancedSectionExpanded() throws Exception {
        SiteContext context = new SiteContext(
                42L,
                "Bestehender Kunde",
                UrlNormalizer.normalize("https://example.com/").orElseThrow(),
                new CrawlBudget(150, 2, Duration.ofMinutes(20)),
                List.of(), List.of(), List.of(),
                false,
                "MyBot",
                Map.of(),
                FormTestMode.SUBMIT
        );
        when(siteService.contextFor(42L)).thenReturn(context);
        when(siteService.summary(42L)).thenReturn(new SiteSummary(42L, "Bestehender Kunde", "https://example.com/", true, 2));

        mvc.perform(get("/websites/42/bearbeiten"))
                .andExpect(status().isOk())
                .andExpect(view().name("websites/formular"))
                .andExpect(content().string(containsString("Erweiterte Einstellungen (optional)")))
                .andExpect(content().string(containsString("{ erweitert: true }")));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void getWebsitesNeuShowsInitialCrawlInfo() throws Exception {
        mvc.perform(get("/websites/neu"))
                .andExpect(status().isOk())
                .andExpect(view().name("websites/formular"))
                .andExpect(content().string(containsString("Automatische Erstprüfung")))
                .andExpect(content().string(containsString("Vorschlag statt Konfigurationsaufwand")));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void getWebsitesEditHidesInitialCrawlInfoAndShowsEditGuidance() throws Exception {
        SiteContext context = new SiteContext(
                42L,
                "Bestehender Kunde",
                UrlNormalizer.normalize("https://example.com/").orElseThrow(),
                new CrawlBudget(150, 2, Duration.ofMinutes(20)),
                List.of(), List.of(), List.of(),
                false,
                "MyBot",
                Map.of(),
                FormTestMode.SUBMIT
        );
        when(siteService.contextFor(42L)).thenReturn(context);
        when(siteService.summary(42L)).thenReturn(new SiteSummary(42L, "Bestehender Kunde", "https://example.com/", true, 2));

        mvc.perform(get("/websites/42/bearbeiten"))
                .andExpect(status().isOk())
                .andExpect(view().name("websites/formular"))
                .andExpect(content().string(not(containsString("Automatische Erstprüfung"))))
                .andExpect(content().string(containsString("Einstellungen anpassen")));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void postWebsitesWithoutCsrfIsForbidden() throws Exception {
        mvc.perform(post("/websites")
                        .param("name", "Test")
                        .param("baseUrl", "https://example.com/")
                        .param("maxPages", "100")
                        .param("maxDepth", "3")
                        .param("maxDurationMinutes", "10"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void postWebsitesWithBlankNameReRendersFormWithErrorsAndDoesNotCallService() throws Exception {        mvc.perform(post("/websites")
                        .with(csrf())
                        .param("name", "")
                        .param("baseUrl", "https://example.com/")
                        .param("maxPages", "100")
                        .param("maxDepth", "3")
                        .param("maxDurationMinutes", "10"))
                .andExpect(status().isOk())
                .andExpect(view().name("websites/formular"))
                .andExpect(model().attributeHasFieldErrors("form", "name"));

        verifyNoInteractions(siteService);
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void createWithDuplicatedBaseUrlRerendersFormWithFieldError() throws Exception {
        when(siteService.baseUrlTaken("http://localhost:8090")).thenReturn(true);

        mvc.perform(post("/websites")
                        .param("name", "Fixture")
                        .param("baseUrl", "http://localhost:8090")
                        .param("maxPages", "200")
                        .param("maxDepth", "10")
                        .param("maxDurationMinutes", "30")
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(view().name("websites/formular"))
                .andExpect(model().attributeHasFieldErrors("form", "baseUrl"));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void postWebsitesWithValidInputCallsCreateAndRedirects() throws Exception {
        when(siteService.create(any())).thenReturn(42L);

        mvc.perform(post("/websites")
                        .with(csrf())
                        .param("name", "Neuer Kunde")
                        .param("baseUrl", "https://example.com/")
                        .param("maxPages", "250")
                        .param("maxDepth", "4")
                        .param("maxDurationMinutes", "15")
                        .param("includePatterns", "  /blog/* \n\n /news/* \n ")
                        .param("excludePatterns", "/intern/*\n/admin/*")
                        .param("pinnedKeyPages", " https://example.com/leistungen.html \n\n/kontakt.html\n ")
                        .param("respectRobots", "true")
                        .param("userAgent", "CustomBot/1.0")
                        .param("enabled", "true")
                        .param("formTestMode", "SUBMIT_AND_VERIFY_MAIL"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/websites/42/einrichtung"));

        ArgumentCaptor<SiteForm> captor = ArgumentCaptor.forClass(SiteForm.class);
        verify(siteService).create(captor.capture());

        SiteForm created = captor.getValue();
        assertThat(created.name()).isEqualTo("Neuer Kunde");
        assertThat(created.baseUrl()).isEqualTo("https://example.com/");
        assertThat(created.maxPages()).isEqualTo(250);
        assertThat(created.maxDepth()).isEqualTo(4);
        assertThat(created.maxDuration()).isEqualTo(Duration.ofMinutes(15));
        assertThat(created.includePatterns()).containsExactly("/blog/*", "/news/*");
        assertThat(created.excludePatterns()).containsExactly("/intern/*", "/admin/*");
        assertThat(created.pinnedKeyPages())
                .containsExactly("https://example.com/leistungen.html", "/kontakt.html");
        assertThat(created.respectRobots()).isTrue();
        assertThat(created.userAgent()).isEqualTo("CustomBot/1.0");
        assertThat(created.formTestMode()).isEqualTo(FormTestMode.SUBMIT_AND_VERIFY_MAIL);
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void creatingASiteSeedsItsThreeTiersImmediately() throws Exception {
        // Spec 9: "Defaults are applied when a site is created." D38's lazy backfill in the
        // dispatcher cannot stand in for that, because tick() short-circuits on the global pause
        // (spec 14) *before* it seeds — so a site added while scheduling is paused would show
        // "(noch keine Zeitpläne)" and offer no form, and the one thing an administrator cannot
        // then do is configure the schedules they paused the clock to sort out.
        when(siteService.create(any())).thenReturn(42L);

        mvc.perform(post("/websites")
                        .with(csrf())
                        .param("name", "Neuer Kunde")
                        .param("baseUrl", "https://example.com/")
                        .param("maxPages", "250")
                        .param("maxDepth", "4")
                        .param("maxDurationMinutes", "15")
                        .param("respectRobots", "true")
                        .param("enabled", "true"))
                .andExpect(status().is3xxRedirection());

        verify(scheduleService).seedDefaults(eq(42L), any(Instant.class));
    }

    @Test
    @WithMockUser(roles = "USER")
    void getWebsitesEditAsUserIsForbidden() throws Exception {
        mvc.perform(get("/websites/42/bearbeiten"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void getWebsitesEditAsAdminReturnsFormPopulatedFromContext() throws Exception {
        SiteContext context = new SiteContext(
                42L,
                "Bestehender Kunde",
                UrlNormalizer.normalize("https://example.com/").orElseThrow(),
                new CrawlBudget(150, 2, Duration.ofMinutes(20)),
                List.of("/shop/*"),
                List.of("/checkout/*"),
                List.of(),
                false,
                "MyBot",
                Map.of(),
                FormTestMode.SUBMIT
        );
        when(siteService.contextFor(42L)).thenReturn(context);
        when(siteService.summary(42L)).thenReturn(new SiteSummary(42L, "Bestehender Kunde", "https://example.com/", true, 2));

        var result = mvc.perform(get("/websites/42/bearbeiten"))
                .andExpect(status().isOk())
                .andExpect(view().name("websites/formular"))
                .andExpect(model().attributeExists("form"))
                .andExpect(model().attribute("siteId", 42L))
                .andReturn();

        SiteFormModel form = (SiteFormModel) result.getModelAndView().getModel().get("form");
        assertThat(form.formTestMode()).isEqualTo("SUBMIT");
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void postWebsitesEditWithValidInputUpdatesAndRedirects() throws Exception {
        mvc.perform(post("/websites/42")
                        .with(csrf())
                        .param("name", "Kunde Aktualisiert")
                        .param("baseUrl", "https://example.com/")
                        .param("maxPages", "100")
                        .param("maxDepth", "3")
                        .param("maxDurationMinutes", "10")
                        .param("includePatterns", "")
                        .param("excludePatterns", "")
                        .param("respectRobots", "true")
                        .param("formTestMode", "SUBMIT"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/websites/42"));

        ArgumentCaptor<SiteForm> captor = ArgumentCaptor.forClass(SiteForm.class);
        verify(siteService).update(eq(42L), captor.capture());

        SiteForm updated = captor.getValue();
        assertThat(updated.name()).isEqualTo("Kunde Aktualisiert");
        assertThat(updated.includePatterns()).isEmpty();
        assertThat(updated.excludePatterns()).isEmpty();
        assertThat(updated.formTestMode()).isEqualTo(FormTestMode.SUBMIT);
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void postWebsitesEditWithEnabledUncheckedDisablesTheSite() throws Exception {
        mvc.perform(post("/websites/42")
                        .with(csrf())
                        .param("name", "Kunde Pause")
                        .param("baseUrl", "https://example.com/")
                        .param("maxPages", "100")
                        .param("maxDepth", "3")
                        .param("maxDurationMinutes", "10"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/websites/42"));

        ArgumentCaptor<SiteForm> captor = ArgumentCaptor.forClass(SiteForm.class);
        verify(siteService).update(eq(42L), captor.capture());

        // An unchecked checkbox binds null; the site form must end up disabled, not left
        // whatever the previous state was.
        assertThat(captor.getValue().enabled()).isFalse();
    }

    @Test
    @WithMockUser(roles = "USER")
    void postWebsitesDeleteAsUserIsForbidden() throws Exception {
        mvc.perform(post("/websites/42/loeschen").with(csrf()))
                .andExpect(status().isForbidden());

        verify(siteService, never()).delete(anyLong());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void postWebsitesDeleteAsAdminDeletesAndRedirectsToListWithFlash() throws Exception {
        when(siteService.summary(42L))
                .thenReturn(new SiteSummary(42L, "Kunde A", "https://example.com/", true, 0));

        mvc.perform(post("/websites/42/loeschen").with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/websites"))
                .andExpect(flash().attribute("flashMessage", containsString("Kunde A")));

        verify(siteService).delete(42L);
    }
}
