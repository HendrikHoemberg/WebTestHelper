package dev.hendrikhoemberg.webtesthelper.model;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * One step in an executable journey (§10.3).
 *
 * @param id                stable UUID across edits and re-recordings
 * @param ordinal           zero-based step sequence number
 * @param action            the action to perform
 * @param locatorCandidates ranked locator candidates, sorted by rank ascending
 * @param value             input value, navigation URL, or credential template
 * @param assertion         optional assertion to evaluate
 * @param optional          true if missing element should skip rather than fail
 * @param timeoutMs         per-step timeout in milliseconds, defaulting to {@value #DEFAULT_TIMEOUT_MS}
 */
public record JourneyStep(
        UUID id,
        int ordinal,
        StepAction action,
        List<LocatorCandidate> locatorCandidates,
        String value,
        StepAssertion assertion,
        boolean optional,
        int timeoutMs
) {
    public static final int DEFAULT_TIMEOUT_MS = 5000;

    public JourneyStep {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(action, "action");
        locatorCandidates = locatorCandidates == null
                ? List.of()
                : List.copyOf(locatorCandidates.stream()
                        .sorted(Comparator.comparingInt(LocatorCandidate::rank))
                        .toList());

        if (action == StepAction.GOTO) {
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException("GOTO step requires a non-blank value");
            }
        }
        if (action == StepAction.CLICK && locatorCandidates.isEmpty()) {
            throw new IllegalArgumentException("CLICK step requires at least one locator candidate");
        }
        if (timeoutMs <= 0) {
            timeoutMs = DEFAULT_TIMEOUT_MS;
        }
    }
}
