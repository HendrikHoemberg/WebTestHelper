package dev.hendrikhoemberg.webtesthelper.model;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Result of executing one journey step against a live page (§10.3).
 *
 * @param stepId            the step's identifier
 * @param status            execution status (PASSED, DRIFTED, FAILED, SKIPPED)
 * @param winner            the winning locator candidate, or null if no locator was used / found
 * @param drifted           true if a fallback candidate won rather than the primary
 * @param failureMessageKey German message key in messages.properties if failed, null otherwise
 * @param failureArgs       arguments for message formatting if failed
 */
public record StepOutcome(
        UUID stepId,
        StepStatus status,
        LocatorCandidate winner,
        boolean drifted,
        String failureMessageKey,
        List<String> failureArgs
) {
    public StepOutcome {
        Objects.requireNonNull(stepId, "stepId");
        Objects.requireNonNull(status, "status");
        failureArgs = failureArgs == null ? List.of() : List.copyOf(failureArgs);
    }

    public static StepOutcome passed(UUID stepId, LocatorCandidate winner) {
        return new StepOutcome(stepId, StepStatus.PASSED, winner, false, null, List.of());
    }

    public static StepOutcome drifted(UUID stepId, LocatorCandidate winner) {
        return new StepOutcome(stepId, StepStatus.DRIFTED, winner, true, null, List.of());
    }

    public static StepOutcome failed(UUID stepId, String failureMessageKey, List<String> failureArgs) {
        return new StepOutcome(stepId, StepStatus.FAILED, null, false, failureMessageKey, failureArgs);
    }

    public static StepOutcome failed(UUID stepId, LocatorCandidate winner, boolean drifted, String failureMessageKey, List<String> failureArgs) {
        return new StepOutcome(stepId, StepStatus.FAILED, winner, drifted, failureMessageKey, failureArgs);
    }

    public static StepOutcome skipped(UUID stepId) {
        return new StepOutcome(stepId, StepStatus.SKIPPED, null, false, null, List.of());
    }
}
