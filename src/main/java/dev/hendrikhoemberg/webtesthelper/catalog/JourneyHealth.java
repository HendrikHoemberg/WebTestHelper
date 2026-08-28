package dev.hendrikhoemberg.webtesthelper.catalog;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Health statistics of a journey (§10.4, D106).
 *
 * @param lastSuccessAt        timestamp of the last successful (or drifted) replay, null if never passed
 * @param consecutiveFailures  number of consecutive failing replays since the last success
 * @param driftCount           cumulative number of selector drifts recorded across replays
 * @param lastDriftedStepIds   the steps that drifted on the most recent completed replay, in execution order
 * @param needsRerecording     derived flag indicating the recording is likely stale rather than the site being broken
 */
public record JourneyHealth(
        Instant lastSuccessAt,
        int consecutiveFailures,
        int driftCount,
        List<UUID> lastDriftedStepIds,
        boolean needsRerecording
) {
    /**
     * Consecutive failure threshold for flagging a journey as needing re-recording (§10.4).
     *
     * <p>Threshold rationale:
     * Repeated failure alone (>= 3 failures, 0 drift) indicates the site itself is broken (reported as a finding).
     * Drift alone (drift > 0, 0 failures) indicates a selector moved but the step still succeeded.
     * The combination (consecutiveFailures >= 3 && driftCount > 0) indicates the journey's recorded locators
     * are failing after drifting, meaning the recording is stale and needs to be re-recorded.
     */
    public static final int FAILURE_THRESHOLD_FOR_RERECORDING = 3;

    public JourneyHealth(Instant lastSuccessAt, int consecutiveFailures, int driftCount) {
        this(lastSuccessAt, consecutiveFailures, driftCount, List.of());
    }

    public JourneyHealth(Instant lastSuccessAt, int consecutiveFailures, int driftCount,
                         List<UUID> lastDriftedStepIds) {
        this(lastSuccessAt, consecutiveFailures, driftCount, lastDriftedStepIds,
                isNeedsRerecording(consecutiveFailures, driftCount));
    }

    public JourneyHealth {
        lastDriftedStepIds = lastDriftedStepIds == null ? List.of() : List.copyOf(lastDriftedStepIds);
        needsRerecording = isNeedsRerecording(consecutiveFailures, driftCount);
    }

    private static boolean isNeedsRerecording(int consecutiveFailures, int driftCount) {
        return consecutiveFailures >= FAILURE_THRESHOLD_FOR_RERECORDING && driftCount > 0;
    }
}
