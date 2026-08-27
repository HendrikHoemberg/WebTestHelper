package dev.hendrikhoemberg.webtesthelper.web;

import dev.hendrikhoemberg.webtesthelper.findings.FindingService;
import dev.hendrikhoemberg.webtesthelper.model.CheckFinding;
import dev.hendrikhoemberg.webtesthelper.model.CheckType;
import dev.hendrikhoemberg.webtesthelper.model.Evidence;
import dev.hendrikhoemberg.webtesthelper.model.NormalizedUrl;
import dev.hendrikhoemberg.webtesthelper.model.RunCoverage;
import dev.hendrikhoemberg.webtesthelper.model.RunScope;
import dev.hendrikhoemberg.webtesthelper.model.RunStatus;
import dev.hendrikhoemberg.webtesthelper.model.RunTrigger;
import dev.hendrikhoemberg.webtesthelper.model.Severity;
import dev.hendrikhoemberg.webtesthelper.model.UrlNormalizer;
import dev.hendrikhoemberg.webtesthelper.runner.persistence.RunEntity;
import dev.hendrikhoemberg.webtesthelper.runner.persistence.RunRepository;
import dev.hendrikhoemberg.webtesthelper.support.AbstractPostgresTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

/**
 * Acceptance test driving the product's central promise — what changed since last time —
 * through the screens without a browser.
 */
@AutoConfigureMockMvc
@WithMockUser(roles = "ADMIN")
class RunReportAcceptanceTest extends AbstractPostgresTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    FindingService findingService;

    @Autowired
    RunRepository runRepository;

    @Autowired
    JdbcTemplate jdbc;

    @BeforeEach
    void resetTables() {
        jdbc.update("DELETE FROM notification");
        jdbc.update("DELETE FROM crawl_queue_item");
        jdbc.update("DELETE FROM finding_occurrence");
        jdbc.update("DELETE FROM finding");
        jdbc.update("DELETE FROM run");
        jdbc.update("DELETE FROM site_check_setting");
        jdbc.update("DELETE FROM site");
    }

    @Test
    void runReportLifeCycleFromSiteCreationToBaselineAcceptanceAndPartialCoverage() throws Exception {
        // 1. Create a site through POST /websites; assert it appears on GET /websites
        MvcResult createResult = mvc.perform(post("/websites")
                        .with(csrf())
                        .param("name", "Test Kunde")
                        .param("baseUrl", "https://www.example.com/")
                        .param("maxPages", "100")
                        .param("maxDepth", "3")
                        .param("maxDurationMinutes", "10")
                        .param("respectRobots", "true"))
                .andExpect(status().is3xxRedirection())
                .andReturn();

        String redirectedUrl = createResult.getResponse().getRedirectedUrl();
        assertThat(redirectedUrl).isNotNull().startsWith("/websites/");
        long siteId = Long.parseLong(redirectedUrl.substring("/websites/".length()).split("/")[0]);

        mvc.perform(get("/websites"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Test Kunde")))
                .andExpect(content().string(containsString("https://www.example.com/")));

        // 2. Materialise run 1 through FindingService.record with three findings and full coverage;
        //    GET /laeufe/{1} shows three under Neu, nothing under Behoben, and the baseline button.
        NormalizedUrl page1 = UrlNormalizer.normalize("https://www.example.com/seite1").orElseThrow();
        NormalizedUrl page2 = UrlNormalizer.normalize("https://www.example.com/seite2").orElseThrow();
        NormalizedUrl page3 = UrlNormalizer.normalize("https://www.example.com/seite3").orElseThrow();

        CheckFinding f1 = new CheckFinding(CheckType.DEAD_LINK, Severity.ERROR, "https://www.example.com/dead1", page1,
                "finding.DEAD_LINK.dead", List.of("https://www.example.com/dead1", "404 Not Found"), Evidence.NONE);
        CheckFinding f2 = new CheckFinding(CheckType.DEAD_LINK, Severity.ERROR, "https://www.example.com/dead2", page2,
                "finding.DEAD_LINK.dead", List.of("https://www.example.com/dead2", "404 Not Found"), Evidence.NONE);
        CheckFinding f3 = new CheckFinding(CheckType.DEAD_LINK, Severity.ERROR, "https://www.example.com/dead3", page3,
                "finding.DEAD_LINK.dead", List.of("https://www.example.com/dead3", "404 Not Found"), Evidence.NONE);

        RunEntity run1 = new RunEntity();
        run1.setSiteId(siteId);
        run1.setStatus(RunStatus.COMPLETED);
        run1.setTriggerType(RunTrigger.MANUAL);
        run1.setScope(RunScope.FULL);
        run1.setPagesVisited(3);
        run1.setPagesFailed(0);
        run1.setPartialCoverage(false);
        run1.setCoveredCheckTypes(RunScope.FULL.checkTypes().stream().map(CheckType::name).toList());
        run1.setCoveredUrls(List.of("https://www.example.com/seite1", "https://www.example.com/seite2", "https://www.example.com/seite3"));
        run1.setStartedAt(Instant.now().minusSeconds(60));
        run1.setFinishedAt(Instant.now().minusSeconds(30));
        run1 = runRepository.save(run1);
        long runId1 = run1.getId();

        RunCoverage fullCoverageRun1 = RunCoverage.of(
                RunScope.FULL,
                RunScope.FULL.checkTypes().stream().map(CheckType::name).toList(),
                List.of("https://www.example.com/seite1", "https://www.example.com/seite2", "https://www.example.com/seite3"),
                List.of(),
                false
        );
        findingService.record(runId1, siteId, List.of(f1, f2, f3), fullCoverageRun1, Instant.now());

        mvc.perform(get("/laeufe/" + runId1))
                .andExpect(status().isOk())
                .andExpect(view().name("laeufe/detail"))
                .andExpect(content().string(containsString("Neu aufgetreten (3)")))
                .andExpect(content().string(not(containsString("Behoben"))))
                .andExpect(content().string(containsString("Als Ausgangsbestand übernehmen")))
                // Bulk triage lives on the findings list, not here (plan 7, "deliberately not in
                // this plan"). befundzeile is shared, so its checkbox must stay switched off — the
                // run report carries no findingsSelection() scope to make one work.
                .andExpect(content().string(not(containsString("befund-checkbox"))));

        // 3. POST /laeufe/{1}/ausgangsbestand redirects and reports three moved;
        //    re-rendering the page no longer offers the button.
        mvc.perform(post("/laeufe/" + runId1 + "/ausgangsbestand").with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/laeufe/" + runId1))
                .andExpect(flash().attribute("flashMessage", containsString("3")));

        mvc.perform(get("/laeufe/" + runId1))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString("Als Ausgangsbestand übernehmen"))))
                .andExpect(content().string(containsString("Ausgangsbestand wurde übernommen")));

        // 4. Materialise run 2 with two of the three findings and full coverage;
        //    GET /laeufe/{2} shows zero under Neu, one under Behoben and two under Bekannt.
        RunEntity run2 = new RunEntity();
        run2.setSiteId(siteId);
        run2.setStatus(RunStatus.COMPLETED);
        run2.setTriggerType(RunTrigger.MANUAL);
        run2.setScope(RunScope.FULL);
        run2.setPagesVisited(3);
        run2.setPagesFailed(0);
        run2.setPartialCoverage(false);
        run2.setCoveredCheckTypes(RunScope.FULL.checkTypes().stream().map(CheckType::name).toList());
        run2.setCoveredUrls(List.of("https://www.example.com/seite1", "https://www.example.com/seite2", "https://www.example.com/seite3"));
        run2.setStartedAt(Instant.now().minusSeconds(20));
        run2.setFinishedAt(Instant.now().minusSeconds(10));
        run2 = runRepository.save(run2);
        long runId2 = run2.getId();

        RunCoverage fullCoverageRun2 = RunCoverage.of(
                RunScope.FULL,
                RunScope.FULL.checkTypes().stream().map(CheckType::name).toList(),
                List.of("https://www.example.com/seite1", "https://www.example.com/seite2", "https://www.example.com/seite3"),
                List.of(),
                false
        );
        findingService.record(runId2, siteId, List.of(f1, f2), fullCoverageRun2, Instant.now());

        MvcResult run2Result = mvc.perform(get("/laeufe/" + runId2))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString("Neu aufgetreten"))))
                .andExpect(content().string(containsString("Behoben (1)")))
                .andExpect(content().string(containsString("Bekannt (2)")))
                .andReturn();

        // 5. A finding's Befund link resolves and its detail page carries all three §13.2 paragraphs.
        List<Long> findingIds = jdbc.queryForList(
                "SELECT id FROM finding WHERE site_id = ? ORDER BY id ASC", Long.class, siteId);
        assertThat(findingIds).isNotEmpty();
        long findingId = findingIds.get(0);

        String run2Html = run2Result.getResponse().getContentAsString();
        assertThat(run2Html).contains("/befunde/" + findingId);

        MvcResult findingResult = mvc.perform(get("/befunde/" + findingId))
                .andExpect(status().isOk())
                .andExpect(view().name("befunde/detail"))
                .andReturn();

        String findingHtml = findingResult.getResponse().getContentAsString();
        // 1. Was wir geprüft haben
        assertThat(findingHtml).contains("Was wir geprüft haben");
        assertThat(findingHtml).contains("Prüft, ob jeder Verweis noch zu einer Seite oder Datei führt");
        // 2. Was wir gefunden haben
        assertThat(findingHtml).contains("Was wir gefunden haben");
        assertThat(findingHtml).contains("führt ins Leere");
        // 3. Was zu tun ist
        assertThat(findingHtml).contains("Was zu tun ist");
        assertThat(findingHtml).contains("Verweis auf die richtige Adresse korrigieren");

        // 6. Materialise run 3 with partial coverage touching one page only;
        //    the run page renders the partial-coverage sentence and the finding on the untouched page is not under Behoben.
        RunEntity run3 = new RunEntity();
        run3.setSiteId(siteId);
        run3.setStatus(RunStatus.COMPLETED);
        run3.setTriggerType(RunTrigger.MANUAL);
        run3.setScope(RunScope.FULL);
        run3.setPagesVisited(1);
        run3.setPagesFailed(0);
        run3.setPartialCoverage(true);
        run3.setBudgetStopReason("Max pages reached");
        run3.setCoveredCheckTypes(RunScope.FULL.checkTypes().stream().map(CheckType::name).toList());
        run3.setCoveredUrls(List.of("https://www.example.com/seite1"));
        run3.setStartedAt(Instant.now().minusSeconds(5));
        run3.setFinishedAt(Instant.now());
        run3 = runRepository.save(run3);
        long runId3 = run3.getId();

        RunCoverage partialCoverageRun3 = RunCoverage.of(
                RunScope.FULL,
                RunScope.FULL.checkTypes().stream().map(CheckType::name).toList(),
                List.of("https://www.example.com/seite1"),
                List.of(),
                true
        );
        findingService.record(runId3, siteId, List.of(f1), partialCoverageRun3, Instant.now());

        mvc.perform(get("/laeufe/" + runId3))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Der Prüflauf hat das Seiten- oder Zeitlimit erreicht")))
                .andExpect(content().string(not(containsString("Behoben"))));

        // Assert that the finding on the untouched page (/seite2) is still ACTIVE, not RESOLVED
        String f2Status = jdbc.queryForObject(
                "SELECT observed_status FROM finding WHERE site_id = ? AND location_key = '/seite2'",
                String.class, siteId);
        assertThat(f2Status).isEqualTo("ACTIVE");
    }
}
