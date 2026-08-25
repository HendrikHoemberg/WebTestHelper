package dev.hendrikhoemberg.webtesthelper.web;

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
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
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
    AppUserService appUserService;

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
