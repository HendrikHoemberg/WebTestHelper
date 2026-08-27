package dev.hendrikhoemberg.webtesthelper.runner;

import dev.hendrikhoemberg.webtesthelper.catalog.SiteForm;
import dev.hendrikhoemberg.webtesthelper.catalog.SiteService;
import dev.hendrikhoemberg.webtesthelper.findings.Finding;
import dev.hendrikhoemberg.webtesthelper.findings.FindingService;
import dev.hendrikhoemberg.webtesthelper.findings.ReportSection;
import dev.hendrikhoemberg.webtesthelper.findings.RunDiff;
import dev.hendrikhoemberg.webtesthelper.model.CheckType;
import dev.hendrikhoemberg.webtesthelper.model.ObservedStatus;
import dev.hendrikhoemberg.webtesthelper.model.RunScope;
import dev.hendrikhoemberg.webtesthelper.model.RunStatus;
import dev.hendrikhoemberg.webtesthelper.model.RunTrigger;
import dev.hendrikhoemberg.webtesthelper.model.Severity;
import dev.hendrikhoemberg.webtesthelper.reporting.FindingView;
import dev.hendrikhoemberg.webtesthelper.reporting.FindingViewFactory;
import dev.hendrikhoemberg.webtesthelper.support.AbstractPostgresTest;
import dev.hendrikhoemberg.webtesthelper.support.FixtureSite;
import dev.hendrikhoemberg.webtesthelper.web.ArtifactController;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end acceptance test for the language switcher and button reachability interaction checks (spec 7.2, 15).
 * Proves the 3-run story and the per-type coverage map preventing cross-page false resolution (plan 10 Finding 6).
 * Real FixtureSite, real PostgreSQL container, real BrowserPool and real CheckRegistry.
 */
@Tag("browser")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SwitcherButtonAcceptanceTest extends AbstractPostgresTest {

    @Autowired
    SiteService sites;

    @Autowired
    RunService runs;

    @Autowired
    RunWorker worker;

    @Autowired
    FindingService findingService;

    @Autowired
    FindingViewFactory findingViewFactory;

    @Autowired
    ArtifactController artifactController;

    @Autowired
    JdbcTemplate jdbc;

    private FixtureSite site;

    @BeforeAll
    void startSite() {
        site = FixtureSite.start();
    }

    @AfterAll
    void stopSite() {
        if (site != null) {
            site.close();
        }
    }

    @Test
    void threeRunStoryForSwitcherButtonsAndPerTypeCoverage() throws IOException {
        jdbc.update("DELETE FROM notification");
        jdbc.update("DELETE FROM finding_occurrence");
        jdbc.update("DELETE FROM finding");
        jdbc.update("DELETE FROM crawl_queue_item");
        jdbc.update("DELETE FROM run");
        jdbc.update("DELETE FROM site_check_setting");
        jdbc.update("DELETE FROM site");

        site.setLanguageSwitcherHealed(false);

        // 1. Create a site whose base URL is the fixture's /interaktiv/sprachen-kaputt.html,
        // pinned explicitly to the homepage, with BUTTON_REACHABILITY enabled.
        long siteId = sites.create(new SiteForm(
                "Sprachen und Knoepfe Akzeptanz",
                site.url("interaktiv/sprachen-kaputt.html"),
                30,
                3,
                Duration.ofMinutes(3),
                List.of(),
                List.of(),
                true,
                null,
                true,
                List.of(site.url("interaktiv/sprachen-kaputt.html"))));
        sites.setCheckEnabled(siteId, CheckType.BUTTON_REACHABILITY, true);

        // ==========================================
        // Run 1: Both checks driven on the homepage
        // ==========================================
        long runId1 = runs.enqueue(siteId, RunTrigger.MANUAL, RunScope.FULL);
        assertThat(worker.workOnce()).isTrue();

        RunSummary summary1 = runs.summary(runId1);
        assertThat(summary1.status()).isEqualTo(RunStatus.COMPLETED);
        assertThat(summary1.coveredInteractionCheckTypes()).contains(
                CheckType.LANGUAGE_SWITCHER, CheckType.BUTTON_REACHABILITY);

        RunDiff diff1 = findingService.diffOf(siteId, runId1);
        List<Finding> newFindings1 = diff1.of(ReportSection.NEW);

        // Three LANGUAGE_SWITCHER findings at ERROR on the homepage
        List<Finding> switcherFindings1 = newFindings1.stream()
                .filter(f -> f.type() == CheckType.LANGUAGE_SWITCHER)
                .toList();
        assertThat(switcherFindings1).hasSize(3);
        for (Finding f : switcherFindings1) {
            assertThat(f.severity()).isEqualTo(Severity.ERROR);
            assertThat(f.locationKey()).isEqualTo("/interaktiv/sprachen-kaputt.html");
        }

        // One BUTTON_REACHABILITY finding at WARN on the homepage (#tut-nichts)
        List<Finding> buttonFindings1 = newFindings1.stream()
                .filter(f -> f.type() == CheckType.BUTTON_REACHABILITY)
                .toList();
        assertThat(buttonFindings1).hasSize(1);
        Finding buttonFinding1 = buttonFindings1.get(0);
        assertThat(buttonFinding1.severity()).isEqualTo(Severity.WARN);
        assertThat(buttonFinding1.locationKey()).isEqualTo("/interaktiv/sprachen-kaputt.html");
        assertThat(buttonFinding1.subjectKey()).isEqualTo("Mehr erfahren");

        // Assert rendered German text via real MessageSource, containing no internal identifier (§13.1)
        Finding switcherFinding1 = switcherFindings1.get(0);
        FindingView switcherFindingView = findingViewFactory.of(switcherFinding1, Locale.GERMAN);
        assertThat(switcherFindingView.title()).isEqualTo("Sprachumschalter");
        assertThat(switcherFindingView.remediation()).isEqualTo(
                "Die Sprachwahl selbst anklicken und die Zielseite ansehen. Steht dort derselbe Text, fehlt die Übersetzung im Redaktionssystem; bleibt die Adresse gleich, ist gar kein Ziel hinterlegt.");
        assertThat(switcherFindingView.title()).doesNotContain("LANGUAGE_SWITCHER");
        assertThat(switcherFindingView.message()).doesNotContain("LANGUAGE_SWITCHER");
        assertThat(switcherFindingView.remediation()).doesNotContain("LANGUAGE_SWITCHER");

        // Screenshot for LANGUAGE_SWITCHER served by ArtifactController
        String switcherScreenshot = switcherFinding1.evidence().screenshotPath();
        assertThat(switcherScreenshot).isNotNull().matches("^[0-9a-f]{32}\\.png$");
        ResponseEntity<Resource> switcherResponse = artifactController.screenshot(runId1, switcherScreenshot);
        assertThat(switcherResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(switcherResponse.getHeaders().getContentType()).isEqualTo(MediaType.IMAGE_PNG);
        assertThat(switcherResponse.getBody()).isNotNull();
        assertThat(switcherResponse.getBody().exists()).isTrue();
        assertThat(switcherResponse.getBody().contentLength()).isGreaterThan(0);

        // Rendered German text for BUTTON_REACHABILITY via real MessageSource
        FindingView buttonFindingView = findingViewFactory.of(buttonFinding1, Locale.GERMAN);
        assertThat(buttonFindingView.title()).isEqualTo("Schaltflächen");
        assertThat(buttonFindingView.message()).isEqualTo(
                "Die Schaltfläche „Mehr erfahren“ auf " + site.url("interaktiv/sprachen-kaputt.html") + " bewirkt nichts.");
        assertThat(buttonFindingView.remediation()).isEqualTo(
                "Die Schaltfläche selbst anklicken. Passiert nichts, fehlt die Verknüpfung, oder das Skript dahinter wird nicht mehr geladen.");
        assertThat(buttonFindingView.title()).doesNotContain("BUTTON_REACHABILITY");
        assertThat(buttonFindingView.message()).doesNotContain("BUTTON_REACHABILITY");
        assertThat(buttonFindingView.remediation()).doesNotContain("BUTTON_REACHABILITY");

        // Screenshot for BUTTON_REACHABILITY served by ArtifactController
        String buttonScreenshot = buttonFinding1.evidence().screenshotPath();
        assertThat(buttonScreenshot).isNotNull().matches("^[0-9a-f]{32}\\.png$");
        ResponseEntity<Resource> buttonResponse = artifactController.screenshot(runId1, buttonScreenshot);
        assertThat(buttonResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(buttonResponse.getHeaders().getContentType()).isEqualTo(MediaType.IMAGE_PNG);
        assertThat(buttonResponse.getBody()).isNotNull();
        assertThat(buttonResponse.getBody().exists()).isTrue();
        assertThat(buttonResponse.getBody().contentLength()).isGreaterThan(0);

        // =========================================================================
        // Run 2: Move buttons to knoepfe.html, prove switcher on homepage did not move
        // =========================================================================
        sites.pinKeyPages(siteId, List.of(site.url("interaktiv/knoepfe.html")));

        long runId2 = runs.enqueue(siteId, RunTrigger.MANUAL, RunScope.FULL);
        assertThat(worker.workOnce()).isTrue();

        RunSummary summary2 = runs.summary(runId2);
        assertThat(summary2.status()).isEqualTo(RunStatus.COMPLETED);

        RunDiff diff2 = findingService.diffOf(siteId, runId2);

        // Two New BUTTON_REACHABILITY findings on knoepfe.html (#tut-nichts and #anker-tut-nichts)
        List<Finding> newButtonFindings2 = diff2.of(ReportSection.NEW).stream()
                .filter(f -> f.type() == CheckType.BUTTON_REACHABILITY)
                .toList();
        assertThat(newButtonFindings2).hasSize(2);
        assertThat(newButtonFindings2).extracting(Finding::subjectKey)
                .containsExactlyInAnyOrder("Tut nichts", "Anker tut nichts");
        for (Finding f : newButtonFindings2) {
            assertThat(f.locationKey()).isEqualTo("/interaktiv/knoepfe.html");
        }

        // The run-1 BUTTON_REACHABILITY finding on the homepage is still ACTIVE and NOT in Fixed
        assertThat(diff2.of(ReportSection.FIXED).stream().noneMatch(f -> f.id() == buttonFinding1.id())).isTrue();
        String buttonFinding1Status = jdbc.queryForObject(
                "SELECT observed_status FROM finding WHERE id = ?", String.class, buttonFinding1.id());
        assertThat(buttonFinding1Status).isEqualTo(ObservedStatus.ACTIVE.name());

        // The three LANGUAGE_SWITCHER findings are still open
        for (Finding f : switcherFindings1) {
            String status = jdbc.queryForObject(
                    "SELECT observed_status FROM finding WHERE id = ?", String.class, f.id());
            assertThat(status).isEqualTo(ObservedStatus.ACTIVE.name());
        }

        // =========================================================================
        // Run 3: Heal the translation and watch exactly one finding resolve
        // =========================================================================
        site.setLanguageSwitcherHealed(true);

        long runId3 = runs.enqueue(siteId, RunTrigger.MANUAL, RunScope.FULL);
        assertThat(worker.workOnce()).isTrue();

        RunSummary summary3 = runs.summary(runId3);
        assertThat(summary3.status()).isEqualTo(RunStatus.COMPLETED);

        RunDiff diff3 = findingService.diffOf(siteId, runId3);

        Finding sameContentFinding = switcherFindings1.stream()
                .filter(f -> f.messageKey().equals("finding.LANGUAGE_SWITCHER.sameContent"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("sameContent finding missing"));
        Finding noNavFinding = switcherFindings1.stream()
                .filter(f -> f.messageKey().equals("finding.LANGUAGE_SWITCHER.noNavigation"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("noNavigation finding missing"));
        Finding langUnchangedFinding = switcherFindings1.stream()
                .filter(f -> f.messageKey().equals("finding.LANGUAGE_SWITCHER.langUnchanged"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("langUnchanged finding missing"));

        // sameContent moves to Fixed
        List<Finding> fixedFindings3 = diff3.of(ReportSection.FIXED);
        assertThat(fixedFindings3).extracting(Finding::fingerprint)
                .containsExactly(sameContentFinding.fingerprint());

        String sameContentStatus = jdbc.queryForObject(
                "SELECT observed_status FROM finding WHERE id = ?", String.class, sameContentFinding.id());
        assertThat(sameContentStatus).isEqualTo(ObservedStatus.RESOLVED.name());

        // noNavigation and langUnchanged stay open
        String noNavStatus = jdbc.queryForObject(
                "SELECT observed_status FROM finding WHERE id = ?", String.class, noNavFinding.id());
        assertThat(noNavStatus).isEqualTo(ObservedStatus.ACTIVE.name());

        String langUnchangedStatus = jdbc.queryForObject(
                "SELECT observed_status FROM finding WHERE id = ?", String.class, langUnchangedFinding.id());
        assertThat(langUnchangedStatus).isEqualTo(ObservedStatus.ACTIVE.name());

        // Button findings remain active
        String button1StatusRun3 = jdbc.queryForObject(
                "SELECT observed_status FROM finding WHERE id = ?", String.class, buttonFinding1.id());
        assertThat(button1StatusRun3).isEqualTo(ObservedStatus.ACTIVE.name());
        for (Finding f : newButtonFindings2) {
            String status = jdbc.queryForObject(
                    "SELECT observed_status FROM finding WHERE id = ?", String.class, f.id());
            assertThat(status).isEqualTo(ObservedStatus.ACTIVE.name());
        }
    }
}
