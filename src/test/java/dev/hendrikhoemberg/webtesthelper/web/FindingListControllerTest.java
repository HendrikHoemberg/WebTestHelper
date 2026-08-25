package dev.hendrikhoemberg.webtesthelper.web;

import dev.hendrikhoemberg.webtesthelper.catalog.SiteService;
import dev.hendrikhoemberg.webtesthelper.checks.CheckRegistry;
import dev.hendrikhoemberg.webtesthelper.findings.Finding;
import dev.hendrikhoemberg.webtesthelper.findings.FindingPage;
import dev.hendrikhoemberg.webtesthelper.findings.FindingQuery;
import dev.hendrikhoemberg.webtesthelper.findings.FindingService;
import dev.hendrikhoemberg.webtesthelper.model.CheckType;
import dev.hendrikhoemberg.webtesthelper.model.CrawlBudget;
import dev.hendrikhoemberg.webtesthelper.model.Evidence;
import dev.hendrikhoemberg.webtesthelper.model.NormalizedUrl;
import dev.hendrikhoemberg.webtesthelper.model.ObservedStatus;
import dev.hendrikhoemberg.webtesthelper.model.Severity;
import dev.hendrikhoemberg.webtesthelper.model.SiteContext;
import dev.hendrikhoemberg.webtesthelper.model.TriageStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

@WebMvcTest(FindingListController.class)
@Import({FindingViewFactory.class, FindingListControllerTest.TestConfig.class})
class FindingListControllerTest {

    @Autowired
    MockMvc mvc;

    @MockitoBean
    SiteService siteService;

    @MockitoBean
    FindingService findingService;

    @MockitoBean
    AppUserService appUserService;

    @TestConfiguration
    static class TestConfig {
        @Bean
        public CheckRegistry checkRegistry() {
            return CheckRegistry.standard();
        }
    }

    private SiteContext sampleSite(long siteId) {
        return new SiteContext(
                siteId,
                "Acme Corp",
                dev.hendrikhoemberg.webtesthelper.model.UrlNormalizer.normalize("https://acme.example.com/").orElseThrow(),
                new CrawlBudget(100, 3, Duration.ofMinutes(10)),
                List.of(),
                List.of(),
                List.of(),
                true,
                "WebTestHelper/1.0",
                Map.of()
        );
    }

    @Test
    @WithMockUser(roles = "USER")
    void pageRendersWithFilterChipsReflectingQueryString() throws Exception {
        long siteId = 42L;
        when(siteService.contextFor(siteId)).thenReturn(sampleSite(siteId));

        Finding finding = new Finding(
                1L, siteId, "fp-1",
                CheckType.DEAD_LINK,
                "https://example.com/dead",
                "https://example.com/source",
                Severity.ERROR,
                "finding.DEAD_LINK.dead",
                List.of("https://example.com/dead", "404 Not Found"),
                Evidence.NONE,
                ObservedStatus.ACTIVE,
                TriageStatus.UNTRIAGED,
                null,
                10L, 10L, null, null,
                1, 1,
                Instant.parse("2026-08-25T10:00:00Z"),
                Instant.parse("2026-08-25T10:00:00Z")
        );

        when(findingService.search(any(FindingQuery.class)))
                .thenReturn(new FindingPage(List.of(finding), 1, 50, 1));

        mvc.perform(get("/websites/{id}/befunde", siteId)
                        .param("severities", "ERROR")
                        .param("triageStatuses", "UNTRIAGED")
                        .param("observed", "ACTIVE")
                        .param("checkTypes", "DEAD_LINK")
                        .param("page", "1")
                        .param("size", "50"))
                .andExpect(status().isOk())
                .andExpect(view().name("websites/befunde"))
                .andExpect(model().attributeExists("site"))
                .andExpect(model().attributeExists("filter"))
                .andExpect(model().attributeExists("page"))
                .andExpect(model().attributeExists("findings"))
                .andExpect(model().attribute("filter", new FindingFilterForm(
                        Set.of(Severity.ERROR),
                        Set.of(TriageStatus.UNTRIAGED),
                        ObservedStatus.ACTIVE,
                        Set.of(CheckType.DEAD_LINK),
                        1,
                        50
                )));
    }

    @Test
    @WithMockUser(roles = "USER")
    void unknownSiteIdReturns404() throws Exception {
        when(siteService.contextFor(999L)).thenThrow(new IllegalArgumentException("Unbekannte Website-ID: 999"));

        mvc.perform(get("/websites/999/befunde"))
                .andExpect(status().isNotFound());
    }

    @Test
    @WithMockUser(roles = "USER")
    void pageIsReachableByUser() throws Exception {
        long siteId = 42L;
        when(siteService.contextFor(siteId)).thenReturn(sampleSite(siteId));
        when(findingService.search(any(FindingQuery.class)))
                .thenReturn(new FindingPage(List.of(), 1, 50, 0));

        mvc.perform(get("/websites/{id}/befunde", siteId))
                .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(roles = "USER")
    void invalidCheckTypeValueReturns400Not500() throws Exception {
        long siteId = 42L;
        when(siteService.contextFor(siteId)).thenReturn(sampleSite(siteId));

        mvc.perform(get("/websites/{id}/befunde", siteId)
                        .param("checkTypes", "COMPLETELY_INVALID_CHECK_TYPE"))
                .andExpect(status().isBadRequest());
    }
}
