package dev.hendrikhoemberg.webtesthelper.findings;

import dev.hendrikhoemberg.webtesthelper.model.CheckType;
import dev.hendrikhoemberg.webtesthelper.model.Evidence;
import dev.hendrikhoemberg.webtesthelper.model.JourneyDefinition;
import dev.hendrikhoemberg.webtesthelper.model.Severity;
import dev.hendrikhoemberg.webtesthelper.model.JourneyReplayResult;
import dev.hendrikhoemberg.webtesthelper.model.StepOutcome;
import dev.hendrikhoemberg.webtesthelper.model.StepStatus;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Maps a journey execution result into persisted-shaped {@link MaterialisedFinding}s (§6.2, §10.4, D108).
 *
 * <p>Identity rules:
 * <ul>
 *   <li>{@code locationKey} is the journey ID (as string), never the journey name, so renaming does not orphan history.</li>
 *   <li>{@code subjectKey} is the step's stable UUID (as string), so reordering steps across re-recordings preserves triage history.</li>
 *   <li>The site ID comes strictly from {@link JourneyDefinition#siteId()}.</li>
 *   <li>Site-wide promotion does not apply — a journey is already a single location.</li>
 * </ul>
 */
public final class JourneyFindingMapper {

    public static final String MSG_JOURNEY_STEP_FAILED = "check.JOURNEY_STEP_FAILED.title";
    public static final String MSG_SELECTOR_DRIFT = "check.SELECTOR_DRIFT.title";

    private JourneyFindingMapper() {
    }

    public static List<MaterialisedFinding> map(JourneyDefinition journey, JourneyReplayResult result) {
        Objects.requireNonNull(journey, "journey");
        Objects.requireNonNull(result, "result");
        Objects.requireNonNull(journey.id(), "journey id");
        Objects.requireNonNull(journey.siteId(), "journey siteId");

        long siteId = journey.siteId();
        String locationKey = String.valueOf(journey.id());

        Evidence failureEvidence = result.screenshotName()
                .filter(s -> !s.isBlank())
                .map(s -> new Evidence(s, null, null, null, List.of()))
                .orElse(Evidence.NONE);

        List<MaterialisedFinding> findings = new ArrayList<>();

        for (StepOutcome outcome : result.outcomes()) {
            if (outcome.status() == StepStatus.FAILED) {
                String subjectKey = outcome.stepId().toString();
                String fingerprint = Fingerprint.of(siteId, CheckType.JOURNEY_STEP_FAILED, subjectKey, locationKey);
                String messageKey = outcome.failureMessageKey() != null && !outcome.failureMessageKey().isBlank()
                        ? outcome.failureMessageKey()
                        : MSG_JOURNEY_STEP_FAILED;
                List<String> messageArgs = outcome.failureArgs();

                FindingOccurrence occurrence = new FindingOccurrence(
                        null, Severity.ERROR, messageKey, messageArgs, failureEvidence);

                findings.add(new MaterialisedFinding(
                        fingerprint,
                        CheckType.JOURNEY_STEP_FAILED,
                        Severity.ERROR,
                        subjectKey,
                        locationKey,
                        messageKey,
                        messageArgs,
                        failureEvidence,
                        List.of(occurrence)
                ));
            } else if (outcome.status() == StepStatus.DRIFTED) {
                String subjectKey = outcome.stepId().toString();
                String fingerprint = Fingerprint.of(siteId, CheckType.SELECTOR_DRIFT, subjectKey, locationKey);
                String messageKey = MSG_SELECTOR_DRIFT;
                List<String> messageArgs = List.of();

                FindingOccurrence occurrence = new FindingOccurrence(
                        null, Severity.WARN, messageKey, messageArgs, Evidence.NONE);

                findings.add(new MaterialisedFinding(
                        fingerprint,
                        CheckType.SELECTOR_DRIFT,
                        Severity.WARN,
                        subjectKey,
                        locationKey,
                        messageKey,
                        messageArgs,
                        Evidence.NONE,
                        List.of(occurrence)
                ));
            }
        }

        return List.copyOf(findings);
    }
}
