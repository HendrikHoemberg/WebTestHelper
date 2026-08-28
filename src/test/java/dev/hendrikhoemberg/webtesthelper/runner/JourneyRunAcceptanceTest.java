package dev.hendrikhoemberg.webtesthelper.runner;

import dev.hendrikhoemberg.webtesthelper.catalog.JourneyHealth;
import dev.hendrikhoemberg.webtesthelper.catalog.JourneyHealthService;
import dev.hendrikhoemberg.webtesthelper.catalog.JourneyService;
import dev.hendrikhoemberg.webtesthelper.catalog.SiteForm;
import dev.hendrikhoemberg.webtesthelper.catalog.SiteService;
import dev.hendrikhoemberg.webtesthelper.findings.Finding;
import dev.hendrikhoemberg.webtesthelper.findings.FindingService;
import dev.hendrikhoemberg.webtesthelper.findings.ReportSection;
import dev.hendrikhoemberg.webtesthelper.findings.RunDiff;
import dev.hendrikhoemberg.webtesthelper.model.AssertionType;
import dev.hendrikhoemberg.webtesthelper.model.CheckType;
import dev.hendrikhoemberg.webtesthelper.model.LocatorCandidate;
import dev.hendrikhoemberg.webtesthelper.model.LocatorStrategy;
import dev.hendrikhoemberg.webtesthelper.model.JourneyStep;
import dev.hendrikhoemberg.webtesthelper.model.ObservedStatus;
import dev.hendrikhoemberg.webtesthelper.model.RunScope;
import dev.hendrikhoemberg.webtesthelper.model.RunStatus;
import dev.hendrikhoemberg.webtesthelper.model.RunTrigger;
import dev.hendrikhoemberg.webtesthelper.model.Severity;
import dev.hendrikhoemberg.webtesthelper.model.StepAction;
import dev.hendrikhoemberg.webtesthelper.model.StepAssertion;
import dev.hendrikhoemberg.webtesthelper.reporting.FindingView;
import dev.hendrikhoemberg.webtesthelper.reporting.FindingViewFactory;
import dev.hendrikhoemberg.webtesthelper.runner.persistence.RunRepository;
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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end acceptance test for journeys running inside runs (§10.4, D107, Task 7).
 *
 * <p>Proves the 4 acceptance scenarios in one crawl/setup:
 * <ol>
 *   <li>(a) A FULL run with a healthy journey produces NO journey findings and sets {@code lastSuccessAt}.</li>
 *   <li>(b) A run with a journey whose step targets a missing element produces exactly one {@code JOURNEY_STEP_FAILED} finding, named for the journey and step.</li>
 *   <li>(c) Repairing the journey and running again flips that finding to {@code RESOLVED} — the diff model working end-to-end for a non-page subject.</li>
 *   <li>(d) A second site's journey findings are untouched by the first site's run.</li>
 * </ol>
 */
@Tag("browser")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class JourneyRunAcceptanceTest extends AbstractPostgresTest {

    @Autowired
    SiteService sites;

    @Autowired
    JourneyService journeyService;

    @Autowired
    JourneyHealthService journeyHealthService;

    @Autowired
    RunService runs;

    @Autowired
    RunWorker worker;

    @Autowired
    RunRepository runRepository;

    @Autowired
    FindingService findingService;

    @Autowired
    FindingViewFactory findingViewFactory;

    @Autowired
    ArtifactController artifactController;

    @Autowired
    JdbcTemplate jdbc;

    private FixtureSite fixtureSite;

    @BeforeAll
    void startFixture() {
        fixtureSite = FixtureSite.start();
    }

    @AfterAll
    void stopFixture() {
        if (fixtureSite != null) {
            fixtureSite.close();
        }
    }

    @Test
    void endToEndJourneyLifecycleAcrossRunsAndSites() throws IOException {
        jdbc.update("DELETE FROM notification");
        jdbc.update("DELETE FROM finding_occurrence");
        jdbc.update("DELETE FROM finding");
        jdbc.update("DELETE FROM crawl_queue_item");
        jdbc.update("DELETE FROM run");
        jdbc.update("DELETE FROM journey");
        jdbc.update("DELETE FROM site_check_setting");
        jdbc.update("DELETE FROM site");

        // -------------------------------------------------------------------------
        // Setup Site 1 and healthy journey definition (reise/ flow)
        // -------------------------------------------------------------------------
        long site1Id = sites.create(new SiteForm(
                "Reise Portal",
                fixtureSite.url("reise/start.html"),
                30,
                3,
                Duration.ofMinutes(3),
                List.of(),
                List.of(),
                true,
                null,
                true));

        UUID step0Id = UUID.randomUUID();
        UUID step1Id = UUID.randomUUID();
        UUID step2Id = UUID.randomUUID();
        UUID step3Id = UUID.randomUUID();
        UUID step4Id = UUID.randomUUID();
        UUID step5Id = UUID.randomUUID();

        JourneyStep s0 = new JourneyStep(step0Id, 0, StepAction.GOTO, List.of(),
                fixtureSite.url("reise/start.html"), null, false, 5000);
        JourneyStep s1 = new JourneyStep(step1Id, 1, StepAction.CLICK, List.of(
                new LocatorCandidate(LocatorStrategy.TEST_ID, "reise-start", 0),
                new LocatorCandidate(LocatorStrategy.ROLE, "link[name='Reise buchen']", 0)
        ), null, null, false, 5000);
        JourneyStep s2 = new JourneyStep(step2Id, 2, StepAction.FILL, List.of(
                new LocatorCandidate(LocatorStrategy.TEST_ID, "reise-name", 0),
                new LocatorCandidate(LocatorStrategy.LABEL, "Name", 0)
        ), "Erika Mustermann", null, false, 5000);
        JourneyStep s3Healthy = new JourneyStep(step3Id, 3, StepAction.FILL, List.of(
                new LocatorCandidate(LocatorStrategy.LABEL, "E-Mail", 0),
                new LocatorCandidate(LocatorStrategy.ID, ":r7:", 0)
        ), "erika@example.com", null, false, 5000);
        JourneyStep s4 = new JourneyStep(step4Id, 4, StepAction.CLICK, List.of(
                new LocatorCandidate(LocatorStrategy.TEST_ID, "reise-submit", 0),
                new LocatorCandidate(LocatorStrategy.ROLE, "button[name='Buchung abschließen']", 0)
        ), null, null, false, 5000);
        JourneyStep s5 = new JourneyStep(step5Id, 5, StepAction.ASSERT, List.of(
                new LocatorCandidate(LocatorStrategy.TEST_ID, "bestaetigung", 0),
                new LocatorCandidate(LocatorStrategy.ROLE, "heading[name='Buchung bestätigt']", 0)
        ), null, new StepAssertion(AssertionType.TEXT_CONTAINS, "Buchung bestätigt"), false, 5000);

        long journey1Id = journeyService.create(site1Id, "Buchungsreise",
                List.of(s0, s1, s2, s3Healthy, s4, s5));

        // =========================================================================
        // Scenario (a): A FULL run with a healthy journey produces NO journey findings
        // and sets lastSuccessAt on the journey.
        // =========================================================================
        long runId1 = runs.enqueue(site1Id, RunTrigger.MANUAL, RunScope.FULL);
        assertThat(worker.workOnce()).isTrue();

        RunSummary summary1 = runs.summary(runId1);
        assertThat(summary1.status()).isEqualTo(RunStatus.COMPLETED);
        assertThat(runRepository.findById(runId1).orElseThrow().getCoveredJourneyIds()).containsExactly(journey1Id);

        RunDiff diff1 = findingService.diffOf(site1Id, runId1);
        List<Finding> journeyFindingsRun1 = diff1.of(ReportSection.NEW).stream()
                .filter(f -> f.type().journey())
                .toList();
        assertThat(journeyFindingsRun1).isEmpty();

        JourneyHealth health1 = journeyHealthService.health(journey1Id).orElseThrow();
        assertThat(health1.lastSuccessAt()).isNotNull();
        assertThat(health1.consecutiveFailures()).isZero();
        assertThat(health1.driftCount()).isZero();
        assertThat(health1.needsRerecording()).isFalse();

        // =========================================================================
        // Scenario (b): A run with a journey whose step 3 targets a missing element
        // produces exactly one JOURNEY_STEP_FAILED finding, named for the journey and the step.
        // =========================================================================
        JourneyStep s3Broken = new JourneyStep(step3Id, 3, StepAction.FILL, List.of(
                new LocatorCandidate(LocatorStrategy.TEST_ID, "nicht-vorhandenes-element", 0)
        ), "erika@example.com", null, false, 300);

        journeyService.update(journey1Id, "Buchungsreise", true,
                List.of(s0, s1, s2, s3Broken, s4, s5));

        long runId2 = runs.enqueue(site1Id, RunTrigger.MANUAL, RunScope.FULL);
        assertThat(worker.workOnce()).isTrue();

        RunSummary summary2 = runs.summary(runId2);
        assertThat(summary2.status()).isEqualTo(RunStatus.COMPLETED);
        assertThat(runRepository.findById(runId2).orElseThrow().getCoveredJourneyIds()).containsExactly(journey1Id);

        RunDiff diff2 = findingService.diffOf(site1Id, runId2);
        List<Finding> newJourneyFindingsRun2 = diff2.of(ReportSection.NEW).stream()
                .filter(f -> f.type().journey())
                .toList();
        assertThat(newJourneyFindingsRun2).hasSize(1);

        Finding finding2 = newJourneyFindingsRun2.get(0);
        assertThat(finding2.type()).isEqualTo(CheckType.JOURNEY_STEP_FAILED);
        assertThat(finding2.locationKey()).isEqualTo(String.valueOf(journey1Id));
        assertThat(finding2.subjectKey()).isEqualTo(step3Id.toString());
        assertThat(finding2.severity()).isEqualTo(Severity.ERROR);
        assertThat(finding2.observed()).isEqualTo(ObservedStatus.ACTIVE);

        // Finding view formatting without internal identifiers (§13.1)
        FindingView view2 = findingViewFactory.of(finding2, Locale.GERMAN);
        assertThat(view2.title()).isEqualTo("Benutzerabläufe");
        assertThat(view2.title()).doesNotContain("JOURNEY_STEP_FAILED");
        assertThat(view2.message()).doesNotContain("JOURNEY_STEP_FAILED");
        assertThat(view2.remediation()).doesNotContain("JOURNEY_STEP_FAILED");

        // Screenshot capture served by ArtifactController
        String screenshotPath = finding2.evidence().screenshotPath();
        assertThat(screenshotPath).isNotNull().matches("^[0-9a-f]{32}\\.png$");
        ResponseEntity<Resource> response = artifactController.screenshot(runId2, screenshotPath);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.IMAGE_PNG);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().exists()).isTrue();
        assertThat(response.getBody().contentLength()).isGreaterThan(0);

        JourneyHealth health2 = journeyHealthService.health(journey1Id).orElseThrow();
        assertThat(health2.consecutiveFailures()).isEqualTo(1);

        // =========================================================================
        // Scenario (c): Repairing the journey and running again flips that finding to
        // RESOLVED — the diff model working end-to-end for a non-page subject.
        // =========================================================================
        journeyService.update(journey1Id, "Buchungsreise", true,
                List.of(s0, s1, s2, s3Healthy, s4, s5));

        long runId3 = runs.enqueue(site1Id, RunTrigger.MANUAL, RunScope.FULL);
        assertThat(worker.workOnce()).isTrue();

        RunSummary summary3 = runs.summary(runId3);
        assertThat(summary3.status()).isEqualTo(RunStatus.COMPLETED);
        assertThat(runRepository.findById(runId3).orElseThrow().getCoveredJourneyIds()).containsExactly(journey1Id);

        RunDiff diff3 = findingService.diffOf(site1Id, runId3);
        List<Finding> fixedJourneyFindingsRun3 = diff3.of(ReportSection.FIXED).stream()
                .filter(f -> f.type().journey())
                .toList();
        assertThat(fixedJourneyFindingsRun3).hasSize(1);
        assertThat(fixedJourneyFindingsRun3.get(0).fingerprint()).isEqualTo(finding2.fingerprint());
        assertThat(diff3.of(ReportSection.NEW).stream().filter(f -> f.type().journey()).toList()).isEmpty();

        String finding2StatusInDb = jdbc.queryForObject(
                "SELECT observed_status FROM finding WHERE id = ?", String.class, finding2.id());
        assertThat(finding2StatusInDb).isEqualTo(ObservedStatus.RESOLVED.name());

        Long finding2ResolvedRun = jdbc.queryForObject(
                "SELECT resolved_at_run FROM finding WHERE id = ?", Long.class, finding2.id());
        assertThat(finding2ResolvedRun).isEqualTo(runId3);

        JourneyHealth health3 = journeyHealthService.health(journey1Id).orElseThrow();
        assertThat(health3.lastSuccessAt()).isNotNull();
        assertThat(health3.consecutiveFailures()).isZero();

        // =========================================================================
        // Scenario (d): A second site's journey findings are untouched by the first site's run.
        // =========================================================================
        long site2Id = sites.create(new SiteForm(
                "Zweites Reise Portal",
                fixtureSite.url("index.html"),
                30,
                3,
                Duration.ofMinutes(3),
                List.of(),
                List.of(),
                true,
                null,
                true));

        UUID site2Step0Id = UUID.randomUUID();
        UUID site2Step1Id = UUID.randomUUID();
        JourneyStep site2S0 = new JourneyStep(site2Step0Id, 0, StepAction.GOTO, List.of(),
                fixtureSite.url("reise/start.html"), null, false, 5000);
        JourneyStep site2S1Broken = new JourneyStep(site2Step1Id, 1, StepAction.CLICK, List.of(
                new LocatorCandidate(LocatorStrategy.TEST_ID, "nicht-vorhanden-site2", 0)
        ), null, null, false, 300);

        long journey2Id = journeyService.create(site2Id, "Defekte Reise Site 2",
                List.of(site2S0, site2S1Broken));

        long runIdSite2 = runs.enqueue(site2Id, RunTrigger.MANUAL, RunScope.FULL);
        assertThat(worker.workOnce()).isTrue();

        RunDiff diffSite2 = findingService.diffOf(site2Id, runIdSite2);
        List<Finding> site2Findings = diffSite2.of(ReportSection.NEW).stream()
                .filter(f -> f.type().journey())
                .toList();
        assertThat(site2Findings).hasSize(1);
        Finding site2Finding = site2Findings.get(0);
        assertThat(site2Finding.observed()).isEqualTo(ObservedStatus.ACTIVE);

        // Run Site 1 again with its healthy journey
        long runIdSite1Again = runs.enqueue(site1Id, RunTrigger.MANUAL, RunScope.FULL);
        assertThat(worker.workOnce()).isTrue();

        // Verify Site 2's finding is still ACTIVE and not touched/resolved
        String site2FindingStatusAfterSite1Run = jdbc.queryForObject(
                "SELECT observed_status FROM finding WHERE id = ?", String.class, site2Finding.id());
        assertThat(site2FindingStatusAfterSite1Run).isEqualTo(ObservedStatus.ACTIVE.name());

        Long site2ResolvedRun = jdbc.queryForObject(
                "SELECT resolved_at_run FROM finding WHERE id = ?", Long.class, site2Finding.id());
        assertThat(site2ResolvedRun).isNull();

        RunDiff diffSite1Again = findingService.diffOf(site1Id, runIdSite1Again);
        assertThat(diffSite1Again.of(ReportSection.FIXED).stream().noneMatch(f -> f.id() == site2Finding.id())).isTrue();
        assertThat(diffSite1Again.of(ReportSection.NEW).stream().noneMatch(f -> f.id() == site2Finding.id())).isTrue();
    }
}
