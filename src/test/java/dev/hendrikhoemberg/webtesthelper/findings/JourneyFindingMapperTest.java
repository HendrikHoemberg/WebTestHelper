package dev.hendrikhoemberg.webtesthelper.findings;

import dev.hendrikhoemberg.webtesthelper.model.CheckType;
import dev.hendrikhoemberg.webtesthelper.model.Evidence;
import dev.hendrikhoemberg.webtesthelper.model.JourneyDefinition;
import dev.hendrikhoemberg.webtesthelper.model.JourneyStep;
import dev.hendrikhoemberg.webtesthelper.model.LocatorCandidate;
import dev.hendrikhoemberg.webtesthelper.model.LocatorStrategy;
import dev.hendrikhoemberg.webtesthelper.model.Severity;
import dev.hendrikhoemberg.webtesthelper.model.StepAction;
import dev.hendrikhoemberg.webtesthelper.model.JourneyReplayResult;
import dev.hendrikhoemberg.webtesthelper.model.ReplayStatus;
import dev.hendrikhoemberg.webtesthelper.model.StepOutcome;
import dev.hendrikhoemberg.webtesthelper.model.StepStatus;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JourneyFindingMapperTest {

    private static final long SITE_ID = 42L;
    private static final long JOURNEY_ID = 101L;

    private JourneyDefinition createJourney(long id, long siteId, String name, List<JourneyStep> steps) {
        return new JourneyDefinition(id, siteId, name, true, steps);
    }

    private JourneyStep createStep(UUID id, int ordinal, StepAction action, String value) {
        List<LocatorCandidate> locators = action == StepAction.CLICK
                ? List.of(new LocatorCandidate(LocatorStrategy.CSS, "button", 1))
                : List.of();
        return new JourneyStep(id, ordinal, action, locators, value, null, false, 5000);
    }

    @Test
    void failedStepProducesJourneyStepFailedFinding() {
        UUID stepId = UUID.randomUUID();
        JourneyStep step = createStep(stepId, 0, StepAction.GOTO, "https://example.com/checkout");
        JourneyDefinition journey = createJourney(JOURNEY_ID, SITE_ID, "Checkout Flow", List.of(step));

        StepOutcome failedOutcome = StepOutcome.failed(
                stepId, "journey.step.failed.timeout", List.of("5000"));
        JourneyReplayResult result = new JourneyReplayResult(
                JOURNEY_ID,
                "Checkout Flow",
                ReplayStatus.FAILED,
                List.of(failedOutcome),
                0,
                Optional.of("failure-screenshot.png"),
                Optional.of("trace.zip")
        );

        List<MaterialisedFinding> findings = JourneyFindingMapper.map(journey, result);

        assertThat(findings).hasSize(1);
        MaterialisedFinding finding = findings.get(0);

        String expectedSubjectKey = stepId.toString();
        String expectedLocationKey = String.valueOf(JOURNEY_ID);
        String expectedFingerprint = Fingerprint.of(
                SITE_ID, CheckType.JOURNEY_STEP_FAILED, expectedSubjectKey, expectedLocationKey);

        assertThat(finding.fingerprint()).isEqualTo(expectedFingerprint);
        assertThat(finding.type()).isEqualTo(CheckType.JOURNEY_STEP_FAILED);
        assertThat(finding.severity()).isEqualTo(Severity.ERROR);
        assertThat(finding.subjectKey()).isEqualTo(expectedSubjectKey);
        assertThat(finding.locationKey()).isEqualTo(expectedLocationKey);
        assertThat(finding.messageKey()).isEqualTo("journey.step.failed.timeout");
        assertThat(finding.messageArgs()).containsExactly("5000");
        assertThat(finding.evidence().screenshotPath()).isEqualTo("failure-screenshot.png");
        assertThat(finding.pageCount()).isEqualTo(1);

        assertThat(finding.occurrences()).hasSize(1);
        FindingOccurrence occurrence = finding.occurrences().get(0);
        assertThat(occurrence.pageUrl()).isNull();
        assertThat(occurrence.severity()).isEqualTo(Severity.ERROR);
        assertThat(occurrence.messageKey()).isEqualTo("journey.step.failed.timeout");
        assertThat(occurrence.messageArgs()).containsExactly("5000");
        assertThat(occurrence.evidence().screenshotPath()).isEqualTo("failure-screenshot.png");
    }

    @Test
    void failedStepWithoutFailureMessageKeyUsesFallback() {
        UUID stepId = UUID.randomUUID();
        JourneyStep step = createStep(stepId, 0, StepAction.GOTO, "https://example.com/");
        JourneyDefinition journey = createJourney(JOURNEY_ID, SITE_ID, "Home", List.of(step));

        StepOutcome failedOutcome = StepOutcome.failed(stepId, null, List.of());
        JourneyReplayResult result = new JourneyReplayResult(
                JOURNEY_ID,
                "Home",
                ReplayStatus.FAILED,
                List.of(failedOutcome),
                0,
                Optional.empty(),
                Optional.empty()
        );

        List<MaterialisedFinding> findings = JourneyFindingMapper.map(journey, result);

        assertThat(findings).hasSize(1);
        MaterialisedFinding finding = findings.get(0);
        assertThat(finding.messageKey()).isEqualTo("finding.journey_step_failed.title");
        assertThat(finding.evidence()).isEqualTo(Evidence.NONE);
    }

    @Test
    void driftedStepProducesSelectorDriftFinding() {
        UUID stepId = UUID.randomUUID();
        JourneyStep step = createStep(stepId, 0, StepAction.CLICK, null);
        JourneyDefinition journey = createJourney(JOURNEY_ID, SITE_ID, "Login Flow", List.of(step));

        LocatorCandidate winner = new LocatorCandidate(LocatorStrategy.CSS, "#submit", 2);
        StepOutcome driftedOutcome = StepOutcome.drifted(stepId, winner);
        JourneyReplayResult result = new JourneyReplayResult(
                JOURNEY_ID,
                "Login Flow",
                ReplayStatus.DRIFTED,
                List.of(driftedOutcome),
                1,
                Optional.empty(),
                Optional.empty()
        );

        List<MaterialisedFinding> findings = JourneyFindingMapper.map(journey, result);

        assertThat(findings).hasSize(1);
        MaterialisedFinding finding = findings.get(0);

        String expectedSubjectKey = stepId.toString();
        String expectedLocationKey = String.valueOf(JOURNEY_ID);
        String expectedFingerprint = Fingerprint.of(
                SITE_ID, CheckType.SELECTOR_DRIFT, expectedSubjectKey, expectedLocationKey);

        assertThat(finding.fingerprint()).isEqualTo(expectedFingerprint);
        assertThat(finding.type()).isEqualTo(CheckType.SELECTOR_DRIFT);
        assertThat(finding.severity()).isEqualTo(Severity.WARN);
        assertThat(finding.subjectKey()).isEqualTo(expectedSubjectKey);
        assertThat(finding.locationKey()).isEqualTo(expectedLocationKey);
        assertThat(finding.messageKey()).isEqualTo("finding.selector_drift.title");
        assertThat(finding.messageArgs()).isEmpty();
        assertThat(finding.evidence()).isEqualTo(Evidence.NONE);
        assertThat(finding.pageCount()).isEqualTo(1);

        assertThat(finding.occurrences()).hasSize(1);
        FindingOccurrence occurrence = finding.occurrences().get(0);
        assertThat(occurrence.pageUrl()).isNull();
        assertThat(occurrence.severity()).isEqualTo(Severity.WARN);
        assertThat(occurrence.messageKey()).isEqualTo("finding.selector_drift.title");
        assertThat(occurrence.messageArgs()).isEmpty();
        assertThat(occurrence.evidence()).isEqualTo(Evidence.NONE);
    }

    @Test
    void passedAndSkippedStepsProduceNoFindings() {
        UUID step1Id = UUID.randomUUID();
        UUID step2Id = UUID.randomUUID();
        JourneyStep step1 = createStep(step1Id, 0, StepAction.GOTO, "https://example.com/");
        JourneyStep step2 = createStep(step2Id, 1, StepAction.CLICK, null);
        JourneyDefinition journey = createJourney(JOURNEY_ID, SITE_ID, "Passing Flow", List.of(step1, step2));

        StepOutcome outcome1 = StepOutcome.passed(step1Id, null);
        StepOutcome outcome2 = StepOutcome.skipped(step2Id);
        JourneyReplayResult result = new JourneyReplayResult(
                JOURNEY_ID,
                "Passing Flow",
                ReplayStatus.PASSED,
                List.of(outcome1, outcome2),
                0,
                Optional.empty(),
                Optional.empty()
        );

        List<MaterialisedFinding> findings = JourneyFindingMapper.map(journey, result);

        assertThat(findings).isEmpty();
    }

    @Test
    void mixedOutcomesProduceRespectiveFindings() {
        UUID step1Id = UUID.randomUUID();
        UUID step2Id = UUID.randomUUID();
        UUID step3Id = UUID.randomUUID();
        UUID step4Id = UUID.randomUUID();

        JourneyStep step1 = createStep(step1Id, 0, StepAction.GOTO, "https://example.com/");
        JourneyStep step2 = createStep(step2Id, 1, StepAction.CLICK, null);
        JourneyStep step3 = createStep(step3Id, 2, StepAction.CLICK, null);
        JourneyStep step4 = createStep(step4Id, 3, StepAction.CLICK, null);

        JourneyDefinition journey = createJourney(
                JOURNEY_ID, SITE_ID, "Mixed Flow", List.of(step1, step2, step3, step4));

        LocatorCandidate fallbackWinner = new LocatorCandidate(LocatorStrategy.CSS, ".btn-primary", 2);
        StepOutcome outcome1 = StepOutcome.passed(step1Id, null);
        StepOutcome outcome2 = StepOutcome.drifted(step2Id, fallbackWinner);
        StepOutcome outcome3 = StepOutcome.failed(step3Id, "journey.step.failed.not_found", List.of());
        StepOutcome outcome4 = StepOutcome.skipped(step4Id);

        JourneyReplayResult result = new JourneyReplayResult(
                JOURNEY_ID,
                "Mixed Flow",
                ReplayStatus.FAILED,
                List.of(outcome1, outcome2, outcome3, outcome4),
                1,
                Optional.of("fail-snap.png"),
                Optional.empty()
        );

        List<MaterialisedFinding> findings = JourneyFindingMapper.map(journey, result);

        assertThat(findings).hasSize(2);

        MaterialisedFinding driftFinding = findings.get(0);
        assertThat(driftFinding.type()).isEqualTo(CheckType.SELECTOR_DRIFT);
        assertThat(driftFinding.subjectKey()).isEqualTo(step2Id.toString());
        assertThat(driftFinding.severity()).isEqualTo(Severity.WARN);
        assertThat(driftFinding.evidence()).isEqualTo(Evidence.NONE);

        MaterialisedFinding failedFinding = findings.get(1);
        assertThat(failedFinding.type()).isEqualTo(CheckType.JOURNEY_STEP_FAILED);
        assertThat(failedFinding.subjectKey()).isEqualTo(step3Id.toString());
        assertThat(failedFinding.severity()).isEqualTo(Severity.ERROR);
        assertThat(failedFinding.evidence().screenshotPath()).isEqualTo("fail-snap.png");
    }

    /**
     * Core invariant from §6.2: Re-recording a journey and reordering its steps does NOT change
     * the fingerprint of a finding about a step that kept its UUID.
     */
    @Test
    void reorderingStepMaintainsIdenticalFingerprint() {
        UUID stableStepId = UUID.randomUUID();

        // Journey version 1: step is at ordinal 2
        JourneyStep v1Step0 = createStep(UUID.randomUUID(), 0, StepAction.GOTO, "https://example.com/");
        JourneyStep v1Step1 = createStep(UUID.randomUUID(), 1, StepAction.CLICK, null);
        JourneyStep v1Step2 = createStep(stableStepId, 2, StepAction.CLICK, null);
        JourneyDefinition journeyV1 = createJourney(
                JOURNEY_ID, SITE_ID, "Checkout Flow", List.of(v1Step0, v1Step1, v1Step2));

        StepOutcome failedOutcomeV1 = StepOutcome.failed(
                stableStepId, "journey.step.failed.timeout", List.of("5000"));
        JourneyReplayResult resultV1 = new JourneyReplayResult(
                JOURNEY_ID,
                "Checkout Flow",
                ReplayStatus.FAILED,
                List.of(failedOutcomeV1),
                0,
                Optional.empty(),
                Optional.empty()
        );

        List<MaterialisedFinding> findingsV1 = JourneyFindingMapper.map(journeyV1, resultV1);
        assertThat(findingsV1).hasSize(1);
        String fingerprintV1 = findingsV1.get(0).fingerprint();

        // Journey version 2: re-recorded with new steps, stableStepId is now at ordinal 7
        JourneyStep v2Step0 = createStep(UUID.randomUUID(), 0, StepAction.GOTO, "https://example.com/new");
        JourneyStep v2Step1 = createStep(UUID.randomUUID(), 1, StepAction.CLICK, null);
        JourneyStep v2Step2 = createStep(UUID.randomUUID(), 2, StepAction.CLICK, null);
        JourneyStep v2Step3 = createStep(UUID.randomUUID(), 3, StepAction.CLICK, null);
        JourneyStep v2Step4 = createStep(UUID.randomUUID(), 4, StepAction.CLICK, null);
        JourneyStep v2Step5 = createStep(UUID.randomUUID(), 5, StepAction.CLICK, null);
        JourneyStep v2Step6 = createStep(UUID.randomUUID(), 6, StepAction.CLICK, null);
        JourneyStep v2Step7 = createStep(stableStepId, 7, StepAction.CLICK, null);
        JourneyDefinition journeyV2 = createJourney(
                JOURNEY_ID, SITE_ID, "Checkout Flow",
                List.of(v2Step0, v2Step1, v2Step2, v2Step3, v2Step4, v2Step5, v2Step6, v2Step7));

        StepOutcome failedOutcomeV2 = StepOutcome.failed(
                stableStepId, "journey.step.failed.timeout", List.of("5000"));
        JourneyReplayResult resultV2 = new JourneyReplayResult(
                JOURNEY_ID,
                "Checkout Flow",
                ReplayStatus.FAILED,
                List.of(failedOutcomeV2),
                0,
                Optional.empty(),
                Optional.empty()
        );

        List<MaterialisedFinding> findingsV2 = JourneyFindingMapper.map(journeyV2, resultV2);
        assertThat(findingsV2).hasSize(1);
        String fingerprintV2 = findingsV2.get(0).fingerprint();

        // Fingerprint MUST be identical so triage history is preserved across re-recording
        assertThat(fingerprintV1).isEqualTo(fingerprintV2);
        assertThat(findingsV1.get(0).subjectKey()).isEqualTo(findingsV2.get(0).subjectKey());
        assertThat(findingsV1.get(0).locationKey()).isEqualTo(findingsV2.get(0).locationKey());
    }

    /**
     * Location key is derived from journey.id(), NOT journey.name(), so renaming a journey
     * does not orphan triage history.
     */
    @Test
    void renamingJourneyKeepsIdenticalLocationKeyAndFingerprint() {
        UUID stepId = UUID.randomUUID();
        JourneyStep step = createStep(stepId, 0, StepAction.GOTO, "https://example.com/");

        JourneyDefinition journeyOriginal = createJourney(JOURNEY_ID, SITE_ID, "Original Name", List.of(step));
        JourneyDefinition journeyRenamed = createJourney(JOURNEY_ID, SITE_ID, "Completely Renamed Flow", List.of(step));

        StepOutcome outcome = StepOutcome.failed(stepId, "journey.step.failed.not_found", List.of());
        JourneyReplayResult result1 = new JourneyReplayResult(
                JOURNEY_ID, "Original Name", ReplayStatus.FAILED, List.of(outcome), 0, Optional.empty(), Optional.empty());
        JourneyReplayResult result2 = new JourneyReplayResult(
                JOURNEY_ID, "Completely Renamed Flow", ReplayStatus.FAILED, List.of(outcome), 0, Optional.empty(), Optional.empty());

        List<MaterialisedFinding> findings1 = JourneyFindingMapper.map(journeyOriginal, result1);
        List<MaterialisedFinding> findings2 = JourneyFindingMapper.map(journeyRenamed, result2);

        assertThat(findings1.get(0).locationKey()).isEqualTo(String.valueOf(JOURNEY_ID));
        assertThat(findings2.get(0).locationKey()).isEqualTo(String.valueOf(JOURNEY_ID));
        assertThat(findings1.get(0).fingerprint()).isEqualTo(findings2.get(0).fingerprint());
    }

    @Test
    void siteIdIsTakenFromJourneyDefinition() {
        UUID stepId = UUID.randomUUID();
        JourneyStep step = createStep(stepId, 0, StepAction.GOTO, "https://example.com/");
        long differentSiteId = 999L;
        JourneyDefinition journey = createJourney(JOURNEY_ID, differentSiteId, "Journey", List.of(step));

        StepOutcome outcome = StepOutcome.failed(stepId, "journey.step.failed.not_found", List.of());
        JourneyReplayResult result = new JourneyReplayResult(
                JOURNEY_ID, "Journey", ReplayStatus.FAILED, List.of(outcome), 0, Optional.empty(), Optional.empty());

        List<MaterialisedFinding> findings = JourneyFindingMapper.map(journey, result);

        String expectedFp = Fingerprint.of(
                differentSiteId, CheckType.JOURNEY_STEP_FAILED, stepId.toString(), String.valueOf(JOURNEY_ID));
        assertThat(findings.get(0).fingerprint()).isEqualTo(expectedFp);
    }

    @Test
    void nullValidations() {
        UUID stepId = UUID.randomUUID();
        JourneyStep step = createStep(stepId, 0, StepAction.GOTO, "https://example.com/");
        JourneyDefinition journey = createJourney(JOURNEY_ID, SITE_ID, "Journey", List.of(step));
        JourneyReplayResult result = new JourneyReplayResult(
                JOURNEY_ID, "Journey", ReplayStatus.PASSED, List.of(), 0, Optional.empty(), Optional.empty());

        assertThatThrownBy(() -> JourneyFindingMapper.map(null, result))
                .isInstanceOf(NullPointerException.class);

        assertThatThrownBy(() -> JourneyFindingMapper.map(journey, null))
                .isInstanceOf(NullPointerException.class);

        JourneyDefinition journeyWithoutId = new JourneyDefinition(null, SITE_ID, "Unsaved", true, List.of(step));
        assertThatThrownBy(() -> JourneyFindingMapper.map(journeyWithoutId, result))
                .isInstanceOf(NullPointerException.class);

        JourneyDefinition journeyWithoutSiteId = new JourneyDefinition(JOURNEY_ID, null, "No Site", true, List.of(step));
        assertThatThrownBy(() -> JourneyFindingMapper.map(journeyWithoutSiteId, result))
                .isInstanceOf(NullPointerException.class);
    }
}
