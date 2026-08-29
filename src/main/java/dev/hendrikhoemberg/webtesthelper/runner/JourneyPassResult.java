package dev.hendrikhoemberg.webtesthelper.runner;

import dev.hendrikhoemberg.webtesthelper.findings.MaterialisedFinding;

import java.util.List;
import java.util.Set;

/**
 * Outcome of the journey pass (§10.4, D107).
 *
 * @param findings                    materialised findings from all replayed journeys
 * @param completedJourneyIds         the set of journey IDs whose replay completed successfully
 * @param journeysNeedingRerecording  subset of {@code completedJourneyIds} flagged needs-re-recording (§10.4);
 *                                    their findings must not be resolved to FIXED by a run that no longer
 *                                    re-observes them
 */
public record JourneyPassResult(List<MaterialisedFinding> findings,
                                Set<Long> completedJourneyIds,
                                Set<Long> journeysNeedingRerecording) {

    public static final JourneyPassResult NONE = new JourneyPassResult(List.of(), Set.of(), Set.of());

    public JourneyPassResult {
        findings = findings == null ? List.of() : List.copyOf(findings);
        completedJourneyIds = completedJourneyIds == null ? Set.of() : Set.copyOf(completedJourneyIds);
        journeysNeedingRerecording = journeysNeedingRerecording == null
                ? Set.of() : Set.copyOf(journeysNeedingRerecording);
    }
}
