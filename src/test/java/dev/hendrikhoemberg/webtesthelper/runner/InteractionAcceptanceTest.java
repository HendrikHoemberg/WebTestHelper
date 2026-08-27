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
 * End-to-end acceptance test for the interaction pass and cookie banner check (spec 15).
 * Real FixtureSite, real PostgreSQL container, real BrowserPool and real CheckRegistry.
 */
@Tag("browser")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class InteractionAcceptanceTest extends AbstractPostgresTest {

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
    void bannerIsDismissedAndBrokenOneIsReportedAndResolved() throws IOException {
        jdbc.update("DELETE FROM notification");
        jdbc.update("DELETE FROM finding_occurrence");
        jdbc.update("DELETE FROM finding");
        jdbc.update("DELETE FROM crawl_queue_item");
        jdbc.update("DELETE FROM run");
        jdbc.update("DELETE FROM site_check_setting");
        jdbc.update("DELETE FROM site");

        site.setCookieBannerDismissable(false);

        // 1. Create a site whose base URL is the fixture's /interaktiv/banner-hartnaeckig.html
        long siteId = sites.create(new SiteForm(
                "Interaktiv Akzeptanz",
                site.url("interaktiv/banner-hartnaeckig.html"),
                30,
                3,
                Duration.ofMinutes(3),
                List.of(),
                List.of(),
                true,
                null,
                true));

        // 2. Queue a FULL run, execute it
        long runId1 = runs.enqueue(siteId, RunTrigger.MANUAL, RunScope.FULL);
        assertThat(worker.workOnce()).isTrue();

        RunSummary summary1 = runs.summary(runId1);
        assertThat(summary1.status()).isEqualTo(RunStatus.COMPLETED);

        // Assert: one COOKIE_BANNER finding in the report's New section with severity ERROR
        RunDiff diff1 = findingService.diffOf(siteId, runId1);
        List<Finding> cookieFindings1 = diff1.of(ReportSection.NEW).stream()
                .filter(f -> f.type() == CheckType.COOKIE_BANNER)
                .toList();
        assertThat(cookieFindings1).hasSize(1);

        Finding finding1 = cookieFindings1.get(0);
        assertThat(finding1.type()).isEqualTo(CheckType.COOKIE_BANNER);
        assertThat(finding1.severity()).isEqualTo(Severity.ERROR);

        // Assert: rendered German text is the one from Task 5's table, resolved through real MessageSource
        FindingView view1 = findingViewFactory.of(finding1, Locale.GERMAN);
        assertThat(view1.title()).isEqualTo("Cookie-Hinweis");
        assertThat(view1.message()).isEqualTo("Der Cookie-Hinweis „cookie-hinweis\" lässt sich nicht wegklicken. Besucher kommen nicht an den Inhalt der Seite.");
        assertThat(view1.remediation()).isEqualTo("Die Seite selbst aufrufen und den Zustimmen-Knopf drücken. Passiert nichts, lädt das Skript des Cookie-Werkzeugs nicht mehr — Einbindung und Konto beim Anbieter prüfen.");

        // Assert: containing no identifier (§13.1)
        assertThat(view1.title()).doesNotContain("COOKIE_BANNER");
        assertThat(view1.message()).doesNotContain("COOKIE_BANNER");
        assertThat(view1.remediation()).doesNotContain("COOKIE_BANNER");

        // Assert: screenshot resolves to a file the ArtifactController would serve
        String screenshotPath = finding1.evidence().screenshotPath();
        assertThat(screenshotPath).isNotNull().matches("^[0-9a-f]{32}\\.png$");

        ResponseEntity<Resource> response = artifactController.screenshot(runId1, screenshotPath);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.IMAGE_PNG);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().exists()).isTrue();
        assertThat(response.getBody().contentLength()).isGreaterThan(0);

        // 3. Run the same site again against working banner, assert finding moves to Fixed
        // (because this run drove COOKIE_BANNER on that location and D74's second statement lets it)
        site.setCookieBannerDismissable(true);

        long runId2 = runs.enqueue(siteId, RunTrigger.MANUAL, RunScope.FULL);
        assertThat(worker.workOnce()).isTrue();

        RunSummary summary2 = runs.summary(runId2);
        assertThat(summary2.status()).isEqualTo(RunStatus.COMPLETED);

        RunDiff diff2 = findingService.diffOf(siteId, runId2);
        assertThat(diff2.count(ReportSection.NEW)).isZero();
        assertThat(diff2.count(ReportSection.FIXED)).isEqualTo(1);
        assertThat(diff2.of(ReportSection.FIXED)).hasSize(1);
        assertThat(diff2.of(ReportSection.FIXED).get(0).fingerprint()).isEqualTo(finding1.fingerprint());

        String statusInDb = jdbc.queryForObject(
                "SELECT observed_status FROM finding WHERE id = ?", String.class, finding1.id());
        assertThat(statusInDb).isEqualTo(ObservedStatus.RESOLVED.name());

        // 4. Finally queue a PULSE run and assert it resolves nothing (§6.4)
        long pulseRunId = runs.enqueue(siteId, RunTrigger.MANUAL, RunScope.PULSE);
        assertThat(worker.workOnce()).isTrue();

        RunSummary pulseSummary = runs.summary(pulseRunId);
        assertThat(pulseSummary.status()).isEqualTo(RunStatus.COMPLETED);
        assertThat(pulseSummary.coveredInteractionCheckTypes()).isEmpty();
        assertThat(pulseSummary.coveredInteractionUrls()).isEmpty();

        RunDiff pulseDiff = findingService.diffOf(siteId, pulseRunId);
        List<Finding> pulseFixedCookieFindings = pulseDiff.of(ReportSection.FIXED).stream()
                .filter(f -> f.type() == CheckType.COOKIE_BANNER)
                .toList();
        assertThat(pulseFixedCookieFindings).isEmpty();

        List<Finding> pulseNewCookieFindings = pulseDiff.of(ReportSection.NEW).stream()
                .filter(f -> f.type() == CheckType.COOKIE_BANNER)
                .toList();
        assertThat(pulseNewCookieFindings).isEmpty();
    }
}
