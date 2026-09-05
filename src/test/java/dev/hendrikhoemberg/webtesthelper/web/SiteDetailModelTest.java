package dev.hendrikhoemberg.webtesthelper.web;

import dev.hendrikhoemberg.webtesthelper.catalog.AppSettings;
import dev.hendrikhoemberg.webtesthelper.catalog.CredentialService;
import dev.hendrikhoemberg.webtesthelper.catalog.RecipientService;
import dev.hendrikhoemberg.webtesthelper.catalog.SiteService;
import dev.hendrikhoemberg.webtesthelper.catalog.SiteSummary;
import dev.hendrikhoemberg.webtesthelper.checks.CheckRegistry;
import dev.hendrikhoemberg.webtesthelper.findings.FindingService;
import dev.hendrikhoemberg.webtesthelper.model.CrawlBudget;
import dev.hendrikhoemberg.webtesthelper.model.SiteContext;
import dev.hendrikhoemberg.webtesthelper.model.UrlNormalizer;
import dev.hendrikhoemberg.webtesthelper.reporting.FindingViewFactory;
import dev.hendrikhoemberg.webtesthelper.runner.RunService;
import dev.hendrikhoemberg.webtesthelper.scheduling.ScheduleService;
import org.junit.jupiter.api.Test;
import org.springframework.ui.ConcurrentModel;
import org.springframework.ui.Model;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SiteDetailModelTest {

    @Test
    void populateConfigLoadsTheSiteContextOnlyOnce() {
        SiteService siteService = mock(SiteService.class);
        RunService runService = mock(RunService.class);
        CheckRegistry checkRegistry = mock(CheckRegistry.class);
        ScheduleService scheduleService = mock(ScheduleService.class);
        RecipientService recipientService = mock(RecipientService.class);
        CredentialService credentialService = mock(CredentialService.class);
        AppSettings appSettings = mock(AppSettings.class);
        FindingService findingService = mock(FindingService.class);
        FindingViewFactory findingViewFactory = mock(FindingViewFactory.class);

        SiteContext site = new SiteContext(1L, "Test",
                UrlNormalizer.normalize("https://example.com/").orElseThrow(),
                CrawlBudget.DEFAULT, List.of(), List.of(), List.of(), true, "WebTestHelper/1.0", Map.of());

        when(siteService.contextFor(1L)).thenReturn(site);
        when(siteService.summary(1L)).thenReturn(new SiteSummary(1L, "Test", "https://example.com/", true, 3));
        when(runService.recentForSite(anyLong(), anyInt())).thenReturn(List.of());
        when(scheduleService.forSite(1L)).thenReturn(List.of());
        when(recipientService.list(1L)).thenReturn(List.of());
        when(credentialService.list(1L)).thenReturn(List.of());
        when(appSettings.fallbackRecipients()).thenReturn(List.of());
        when(checkRegistry.all()).thenReturn(List.of());
        when(checkRegistry.categories()).thenReturn(List.of());
        when(findingService.openCountsBySite()).thenReturn(Map.of());

        SiteDetailModel builder = new SiteDetailModel(siteService, runService, checkRegistry,
                scheduleService, recipientService, credentialService, appSettings,
                findingService, findingViewFactory);

        Model model = new ConcurrentModel();
        builder.populateConfig(1L, model);

        verify(siteService, times(1)).contextFor(1L);
        assertThat(model.getAttribute("site")).isSameAs(site);
        assertThat(model.getAttribute("checkCategories")).isNotNull();
        assertThat(model.getAttribute("trafficLight")).isNotNull();
    }

    @Test
    void populateLoadsTheSiteContextOnlyOnce() {
        SiteService siteService = mock(SiteService.class);
        RunService runService = mock(RunService.class);
        CheckRegistry checkRegistry = mock(CheckRegistry.class);
        ScheduleService scheduleService = mock(ScheduleService.class);
        RecipientService recipientService = mock(RecipientService.class);
        CredentialService credentialService = mock(CredentialService.class);
        AppSettings appSettings = mock(AppSettings.class);
        FindingService findingService = mock(FindingService.class);
        FindingViewFactory findingViewFactory = mock(FindingViewFactory.class);

        SiteContext site = new SiteContext(2L, "Test",
                UrlNormalizer.normalize("https://example.com/").orElseThrow(),
                CrawlBudget.DEFAULT, List.of(), List.of(), List.of(), true, "WebTestHelper/1.0", Map.of());

        when(siteService.contextFor(2L)).thenReturn(site);
        when(runService.recentForSite(anyLong(), anyInt())).thenReturn(List.of());
        when(scheduleService.forSite(2L)).thenReturn(List.of());
        when(recipientService.list(2L)).thenReturn(List.of());
        when(credentialService.list(2L)).thenReturn(List.of());
        when(appSettings.fallbackRecipients()).thenReturn(List.of());
        when(checkRegistry.all()).thenReturn(List.of());

        SiteDetailModel builder = new SiteDetailModel(siteService, runService, checkRegistry,
                scheduleService, recipientService, credentialService, appSettings,
                findingService, findingViewFactory);

        Model model = new ConcurrentModel();
        builder.populate(2L, model);

        verify(siteService, times(1)).contextFor(2L);
        assertThat(model.getAttribute("site")).isSameAs(site);
    }
}
