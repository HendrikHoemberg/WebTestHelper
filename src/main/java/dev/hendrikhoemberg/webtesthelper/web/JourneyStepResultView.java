package dev.hendrikhoemberg.webtesthelper.web;

import dev.hendrikhoemberg.webtesthelper.model.StepAction;
import dev.hendrikhoemberg.webtesthelper.model.StepStatus;

import java.util.Objects;

/**
 * View representation of a single executed journey step outcome for display in test results.
 *
 * @param ordinal          1-based step order
 * @param action           the step action
 * @param actionLabel      human-readable action label (e.g. "Seite aufrufen", "Klicken")
 * @param targetSummary    readable target description or URL
 * @param status           execution status (PASSED, DRIFTED, FAILED, SKIPPED)
 * @param statusBadgeClass CSS class for the status badge
 * @param statusLabel      localized status label (e.g. "Erfolgreich", "Angepasst")
 * @param drifted          true if a fallback locator was used
 * @param winnerDetails    human-friendly detail about which fallback locator won, or null
 * @param failureMessage   localized failure reason if failed, or null
 */
public record JourneyStepResultView(
        int ordinal,
        StepAction action,
        String actionLabel,
        String targetSummary,
        StepStatus status,
        String statusBadgeClass,
        String statusLabel,
        boolean drifted,
        String winnerDetails,
        String failureMessage
) {
    public JourneyStepResultView {
        Objects.requireNonNull(action, "action");
        Objects.requireNonNull(actionLabel, "actionLabel");
        Objects.requireNonNull(targetSummary, "targetSummary");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(statusBadgeClass, "statusBadgeClass");
        Objects.requireNonNull(statusLabel, "statusLabel");
    }
}
