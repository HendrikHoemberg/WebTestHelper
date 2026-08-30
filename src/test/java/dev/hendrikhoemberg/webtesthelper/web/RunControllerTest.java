package dev.hendrikhoemberg.webtesthelper.web;

import dev.hendrikhoemberg.webtesthelper.catalog.SiteService;
import dev.hendrikhoemberg.webtesthelper.findings.FindingService;
import dev.hendrikhoemberg.webtesthelper.findings.ReportSection;
import dev.hendrikhoemberg.webtesthelper.findings.RunDiff;
import dev.hendrikhoemberg.webtesthelper.model.CheckType;
import dev.hendrikhoemberg.webtesthelper.model.CrawlBudget;
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
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.containsStringIgnoringCase;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

@WebMvcTest(RunController.class)
class RunControllerTest {

    @Autowired
    MockMvc mvc;

    @MockitoBean
    RunService runService;

    @MockitoBean
    FindingService findingService;

    @MockitoBean
    FindingViewFactory findingViewFactory;

    @MockitoBean
    SiteService siteService;

    @MockitoBean
    AppUserService appUserService;

    private SiteContext sampleSite(long siteId) {
        return new SiteContext(
                siteId,
                "Acme Shop",
                UrlNormalizer.normalize("https://acme.example.com/").orElseThrow(),
                new CrawlBudget(120, 3, Duration.ofMinutes(15)),
                List.of(),
                List.of(),
                List.of(),
                true,
                null,
                Map.of()
        );
    }

    private RunSummary sampleSummary(long id, long siteId, RunStatus status, boolean partialCoverage,
                                    boolean baselineAccepted, String errorMessage) {
        return new RunSummary(
                id,
                siteId,
                status,
                RunTrigger.MANUAL,
                RunScope.FULL,
                Instant.parse("2026-08-25T10:00:00Z"),
                Instant.parse("2026-08-25T10:00:05Z"),
                Instant.parse("2026-08-25T10:02:30Z"),
                85,
                2,
                4,
                2,
                1,
                partialCoverage,
                partialCoverage ? "Max pages reached" : null,
                baselineAccepted,
                errorMessage,
                Set.of(CheckType.PAGE_STATUS, CheckType.DEAD_LINK)
        );
    }

    @Test
    @WithMockUser(roles = "USER")
    void runDetailRendersHumanizedDateTimes() throws Exception {
        long runId = 101L;
        long siteId = 42L;
        when(runService.summary(runId)).thenReturn(sampleSummary(runId, siteId, RunStatus.COMPLETED, false, false, null));
        when(siteService.contextFor(siteId)).thenReturn(sampleSite(siteId));
        when(findingService.diffOf(siteId, runId)).thenReturn(new RunDiff(runId, Map.of()));
        when(findingViewFactory.of(eq(new RunDiff(runId, Map.of())), any(Locale.class))).thenReturn(Map.of());

        mvc.perform(get("/laeufe/" + runId))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("25.08.2026")))
                .andExpect(content().string(not(containsString("2026-08-25T10:00:00Z"))));
    }

    @Test
    @WithMockUser(roles = "USER")
    void runDetailRendersOnlyNonEmptySectionHeadingsWithCounts() throws Exception {
        long runId = 101L;
        long siteId = 42L;
        RunSummary summary = sampleSummary(runId, siteId, RunStatus.COMPLETED, false, false, null);
        SiteContext site = sampleSite(siteId);

        RunDiff diff = new RunDiff(runId, Map.of(
                ReportSection.NEW, List.of(),
                ReportSection.FIXED, List.of(),
                ReportSection.KNOWN, List.of(),
                ReportSection.REGRESSED, List.of(),
                ReportSection.STILL_OPEN, List.of()
        ));

        FindingView viewNew1 = new FindingView(1L, "Tote Links", "Link tot", "Korrigieren", "https://acme.example.com/a", false, 1, Severity.ERROR, TriageStatus.UNTRIAGED);
        FindingView viewNew2 = new FindingView(2L, "Fehlende Bilder", "Bild fehlt", "Hochladen", "https://acme.example.com/b", false, 1, Severity.WARN, TriageStatus.UNTRIAGED);
        FindingView viewFixed = new FindingView(3L, "Seitenstatus", "Fehler behoben", "Nichts tun", "https://acme.example.com/c", false, 1, Severity.INFO, TriageStatus.UNTRIAGED);
        FindingView viewKnown = new FindingView(4L, "Verschlüsselung der Website", "Zertifikat läuft ab", "Erneuern", "*", true, 312, Severity.WARN, TriageStatus.ACKNOWLEDGED);

        Map<ReportSection, List<FindingView>> sections = new LinkedHashMap<>();
        sections.put(ReportSection.FIXED, List.of(viewFixed));
        sections.put(ReportSection.NEW, List.of(viewNew1, viewNew2));
        sections.put(ReportSection.REGRESSED, List.of());
        sections.put(ReportSection.KNOWN, List.of(viewKnown));
        sections.put(ReportSection.STILL_OPEN, List.of());

        when(runService.summary(runId)).thenReturn(summary);
        when(siteService.contextFor(siteId)).thenReturn(site);
        when(findingService.diffOf(siteId, runId)).thenReturn(diff);
        when(findingViewFactory.of(eq(diff), any(Locale.class))).thenReturn(sections);

        mvc.perform(get("/laeufe/" + runId))
                .andExpect(status().isOk())
                .andExpect(view().name("laeufe/detail"))
                .andExpect(model().attributeExists("run", "site", "sections"))
                // Present sections have headings and counts
                .andExpect(content().string(containsString("Behoben (1)")))
                .andExpect(content().string(containsString("Neu aufgetreten (2)")))
                .andExpect(content().string(containsString("Bekannt (1)")))
                // Empty sections render NO headings at all
                .andExpect(content().string(not(containsString("Wieder aufgetreten"))))
                .andExpect(content().string(not(containsString("Unverändert offen"))))
                // Section headings carry the group status as accent class
                .andExpect(content().string(containsString("abschnitt-FIXED")))
                .andExpect(content().string(containsString("abschnitt-NEW")))
                .andExpect(content().string(containsString("abschnitt-KNOWN")));
    }

    @Test
    @WithMockUser(roles = "USER")
    void coverageLineRendersPagesVisitedFailedAndPartialCoverageSentenceWhenTrue() throws Exception {
        long runId = 102L;
        long siteId = 42L;
        RunSummary summary = sampleSummary(runId, siteId, RunStatus.COMPLETED, true, false, null);
        SiteContext site = sampleSite(siteId);
        RunDiff diff = new RunDiff(runId, Map.of());

        when(runService.summary(runId)).thenReturn(summary);
        when(siteService.contextFor(siteId)).thenReturn(site);
        when(findingService.diffOf(siteId, runId)).thenReturn(diff);
        when(findingViewFactory.of(eq(diff), any(Locale.class))).thenReturn(Map.of());

        mvc.perform(get("/laeufe/" + runId))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("85 Seiten besucht")))
                .andExpect(content().string(containsString("2 fehlgeschlagen")))
                // Sentence from §6.4 explaining partial coverage
                .andExpect(content().string(containsString("Der Prüflauf hat das Seiten- oder Zeitlimit erreicht")));
    }

    @Test
    @WithMockUser(roles = "USER")
    void coverageLineDoesNotRenderPartialCoverageSentenceWhenComplete() throws Exception {
        long runId = 103L;
        long siteId = 42L;
        RunSummary summary = sampleSummary(runId, siteId, RunStatus.COMPLETED, false, false, null);
        SiteContext site = sampleSite(siteId);
        RunDiff diff = new RunDiff(runId, Map.of());

        when(runService.summary(runId)).thenReturn(summary);
        when(siteService.contextFor(siteId)).thenReturn(site);
        when(findingService.diffOf(siteId, runId)).thenReturn(diff);
        when(findingViewFactory.of(eq(diff), any(Locale.class))).thenReturn(Map.of());

        mvc.perform(get("/laeufe/" + runId))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("85 Seiten besucht")))
                .andExpect(content().string(containsString("Interaktive Prüfungen: keine")))
                .andExpect(content().string(not(containsString("Der Prüflauf hat das Seiten- oder Zeitlimit erreicht"))));
    }

    @Test
    @WithMockUser(roles = "USER")
    void coverageLineRendersInteractiveChecksCountAndPageCount() throws Exception {
        long runId = 1031L;
        long siteId = 42L;
        RunSummary summary = new RunSummary(
                runId,
                siteId,
                RunStatus.COMPLETED,
                RunTrigger.MANUAL,
                RunScope.FULL,
                Instant.parse("2026-08-25T10:00:00Z"),
                Instant.parse("2026-08-25T10:00:05Z"),
                Instant.parse("2026-08-25T10:02:30Z"),
                85,
                0,
                4,
                2,
                1,
                false,
                null,
                false,
                null,
                Set.of(CheckType.PAGE_STATUS, CheckType.DEAD_LINK),
                Set.of(CheckType.COOKIE_BANNER, CheckType.IFRAME_EMBED),
                List.of("https://acme.example.com/", "https://acme.example.com/a", "https://acme.example.com/b")
        );
        SiteContext site = sampleSite(siteId);
        RunDiff diff = new RunDiff(runId, Map.of());

        when(runService.summary(runId)).thenReturn(summary);
        when(siteService.contextFor(siteId)).thenReturn(site);
        when(findingService.diffOf(siteId, runId)).thenReturn(diff);
        when(findingViewFactory.of(eq(diff), any(Locale.class))).thenReturn(Map.of());

        mvc.perform(get("/laeufe/" + runId))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Interaktive Prüfungen: 2 Prüfungen auf 3 Seiten")));
    }

    @Test
    @WithMockUser(roles = "USER")
    void coverageLineRendersInteractiveChecksNoneWhenCollectionsAreNull() throws Exception {
        long runId = 1032L;
        long siteId = 42L;
        RunSummary summary = new RunSummary(
                runId,
                siteId,
                RunStatus.COMPLETED,
                RunTrigger.MANUAL,
                RunScope.FULL,
                Instant.parse("2026-08-25T10:00:00Z"),
                Instant.parse("2026-08-25T10:00:05Z"),
                Instant.parse("2026-08-25T10:02:30Z"),
                85,
                0,
                4,
                2,
                1,
                false,
                null,
                false,
                null,
                null,
                null,
                null
        );
        SiteContext site = sampleSite(siteId);
        RunDiff diff = new RunDiff(runId, Map.of());

        when(runService.summary(runId)).thenReturn(summary);
        when(siteService.contextFor(siteId)).thenReturn(site);
        when(findingService.diffOf(siteId, runId)).thenReturn(diff);
        when(findingViewFactory.of(eq(diff), any(Locale.class))).thenReturn(Map.of());

        mvc.perform(get("/laeufe/" + runId))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Interaktive Prüfungen: keine")));
    }

    @Test
    @WithMockUser(roles = "USER")
    void failedRunRendersErrorMessageInTechnicalBlockNotHeadline() throws Exception {
        long runId = 104L;
        long siteId = 42L;
        String errorMsg = "Chromium crashed unexpectedly with SIGSEGV in worker 2";
        RunSummary summary = sampleSummary(runId, siteId, RunStatus.FAILED, false, false, errorMsg);
        SiteContext site = sampleSite(siteId);

        when(runService.summary(runId)).thenReturn(summary);
        when(siteService.contextFor(siteId)).thenReturn(site);

        mvc.perform(get("/laeufe/" + runId))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(errorMsg)))
                .andExpect(content().string(containsString("Fehlgeschlagen")))
                // Headline is the run ID / overview, not the error message
                .andExpect(content().string(containsString("Prüflauf #104")));
    }

    @Test
    @WithMockUser(roles = "USER")
    void fortschrittForRunningRunReturnsFragmentWith3sTrigger() throws Exception {
        long runId = 105L;
        RunSummary summary = sampleSummary(runId, 42L, RunStatus.RUNNING, false, false, null);

        when(runService.summary(runId)).thenReturn(summary);

        mvc.perform(get("/laeufe/" + runId + "/fortschritt"))
                .andExpect(status().isOk())
                .andExpect(view().name("fragments/fortschritt :: fortschritt"))
                .andExpect(content().string(containsString("hx-trigger=\"every 3s\"")))
                // The body is the fragment, not the template file that hosts it: a document
                // wrapper here is markup HTMX has to strip before it can swap the div.
                .andExpect(content().string(not(containsStringIgnoringCase("<!DOCTYPE"))))
                .andExpect(content().string(not(containsStringIgnoringCase("<body"))))
                .andExpect(header().doesNotExist("HX-Refresh"));
    }

    @Test
    @WithMockUser(roles = "USER")
    void fortschrittForTerminalRunReturnsHxRefreshHeader() throws Exception {
        long runId = 106L;
        RunSummary summary = sampleSummary(runId, 42L, RunStatus.COMPLETED, false, false, null);

        when(runService.summary(runId)).thenReturn(summary);

        mvc.perform(get("/laeufe/" + runId + "/fortschritt"))
                .andExpect(status().isOk())
                .andExpect(header().string("HX-Refresh", "true"));
    }

    @Test
    @WithMockUser(roles = "USER")
    void postAusgangsbestandWithoutCsrfIsForbidden() throws Exception {
        mvc.perform(post("/laeufe/101/ausgangsbestand"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "USER")
    void postAusgangsbestandWithCsrfCallsAcceptBaselineAndRedirectsWithFlashMessage() throws Exception {
        long runId = 101L;
        when(runService.acceptBaseline(runId)).thenReturn(5);

        mvc.perform(post("/laeufe/" + runId + "/ausgangsbestand").with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/laeufe/" + runId))
                .andExpect(flash().attributeExists("flashMessage"));

        verify(runService).acceptBaseline(runId);
    }

    @Test
    @WithMockUser(roles = "USER")
    void baselineButtonAbsentWhenBaselineAlreadyAccepted() throws Exception {
        long runId = 107L;
        long siteId = 42L;
        RunSummary summary = sampleSummary(runId, siteId, RunStatus.COMPLETED, false, true, null);
        SiteContext site = sampleSite(siteId);
        RunDiff diff = new RunDiff(runId, Map.of());

        when(runService.summary(runId)).thenReturn(summary);
        when(siteService.contextFor(siteId)).thenReturn(site);
        when(findingService.diffOf(siteId, runId)).thenReturn(diff);
        when(findingViewFactory.of(eq(diff), any(Locale.class))).thenReturn(Map.of());

        mvc.perform(get("/laeufe/" + runId))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString("Als Ausgangsbestand übernehmen"))))
                .andExpect(content().string(containsString("Ausgangsbestand wurde übernommen")));
    }

    @Test
    @WithMockUser(roles = "USER")
    void baselineButtonPresentWithAlpineWarningWhenBaselineNotAccepted() throws Exception {
        long runId = 108L;
        long siteId = 42L;
        RunSummary summary = sampleSummary(runId, siteId, RunStatus.COMPLETED, false, false, null);
        SiteContext site = sampleSite(siteId);
        RunDiff diff = new RunDiff(runId, Map.of());

        when(runService.summary(runId)).thenReturn(summary);
        when(siteService.contextFor(siteId)).thenReturn(site);
        when(findingService.diffOf(siteId, runId)).thenReturn(diff);
        when(findingViewFactory.of(eq(diff), any(Locale.class))).thenReturn(Map.of());

        mvc.perform(get("/laeufe/" + runId))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Als Ausgangsbestand übernehmen")))
                .andExpect(content().string(containsString("x-data=\"{ offen: false }\"")))
                .andExpect(content().string(containsString("Hiermit werden alle")));
    }

    @Test
    @WithMockUser(roles = "USER")
    void getRunDetailWithUnknownIdReturns404() throws Exception {
        when(runService.summary(999L)).thenThrow(new IllegalArgumentException("Lauf 999 existiert nicht"));

        mvc.perform(get("/laeufe/999"))
                .andExpect(status().isNotFound());
    }
}
