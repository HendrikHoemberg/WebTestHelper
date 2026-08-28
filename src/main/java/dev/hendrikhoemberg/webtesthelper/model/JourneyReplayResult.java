package dev.hendrikhoemberg.webtesthelper.model;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The outcome of replaying a journey (§10.4, D106).
 *
 * @param journeyId      ID of the replayed journey (0 if unsaved)
 * @param journeyName    name of the journey
 * @param status         overall status of the replay (PASSED, DRIFTED, FAILED)
 * @param outcomes       recorded step outcomes in execution order
 * @param driftCount     number of steps that fell back to a secondary locator candidate
 * @param screenshotName screenshot filename in the artifact directory if failed, empty otherwise
 * @param traceName      Playwright trace zip filename in the artifact directory if failed, empty otherwise
 */
public record JourneyReplayResult(
        long journeyId,
        String journeyName,
        ReplayStatus status,
        List<StepOutcome> outcomes,
        int driftCount,
        Optional<String> screenshotName,
        Optional<String> traceName
) {
    public JourneyReplayResult {
        Objects.requireNonNull(journeyName, "journeyName");
        Objects.requireNonNull(status, "status");
        outcomes = outcomes == null ? List.of() : List.copyOf(outcomes);
        screenshotName = screenshotName == null ? Optional.empty() : screenshotName;
        traceName = traceName == null ? Optional.empty() : traceName;
    }
}
