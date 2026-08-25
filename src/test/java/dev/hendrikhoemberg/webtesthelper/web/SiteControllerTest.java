package dev.hendrikhoemberg.webtesthelper.web;

import dev.hendrikhoemberg.webtesthelper.catalog.SiteForm;
import dev.hendrikhoemberg.webtesthelper.catalog.SiteService;
import dev.hendrikhoemberg.webtesthelper.catalog.SiteSummary;
import dev.hendrikhoemberg.webtesthelper.model.CheckType;
import dev.hendrikhoemberg.webtesthelper.model.CrawlBudget;
import dev.hendrikhoemberg.webtesthelper.model.NormalizedUrl;
import dev.hendrikhoemberg.webtesthelper.model.SiteContext;
import dev.hendrikhoemberg.webtesthelper.model.UrlNormalizer;
import dev.hendrikhoemberg.webtesthelper.checks.CheckRegistry;
import dev.hendrikhoemberg.webtesthelper.runner.RunService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

@WebMvcTest(SiteController.class)
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
    AppUserService appUserService;

    @Test
    @WithMockUser(roles = "USER")
    void getRootAsUserReturnsSiteList() throws Exception {
        when(siteService.summaries()).thenReturn(List.of(
                new SiteSummary(1L, "Test Site", "https://example.com/", true, 10)
        ));

        mvc.perform(get("/"))
                .andExpect(status().isOk())
                .andExpect(view().name("websites/liste"))
                .andExpect(model().attributeExists("sites"));
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
    void postWebsitesWithBlankNameReRendersFormWithErrorsAndDoesNotCallService() throws Exception {
        mvc.perform(post("/websites")
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
                        .param("enabled", "true"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/websites/42"));

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
                Map.of()
        );
        when(siteService.contextFor(42L)).thenReturn(context);
        when(siteService.summary(42L)).thenReturn(new SiteSummary(42L, "Bestehender Kunde", "https://example.com/", true, 2));

        mvc.perform(get("/websites/42/bearbeiten"))
                .andExpect(status().isOk())
                .andExpect(view().name("websites/formular"))
                .andExpect(model().attributeExists("form"))
                .andExpect(model().attribute("siteId", 42L));
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
                        .param("respectRobots", "true"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/websites/42"));

        ArgumentCaptor<SiteForm> captor = ArgumentCaptor.forClass(SiteForm.class);
        verify(siteService).update(eq(42L), captor.capture());

        SiteForm updated = captor.getValue();
        assertThat(updated.name()).isEqualTo("Kunde Aktualisiert");
        assertThat(updated.includePatterns()).isEmpty();
        assertThat(updated.excludePatterns()).isEmpty();
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
}
