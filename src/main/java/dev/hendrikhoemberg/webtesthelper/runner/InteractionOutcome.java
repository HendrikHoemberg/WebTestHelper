package dev.hendrikhoemberg.webtesthelper.runner;

import dev.hendrikhoemberg.webtesthelper.model.CheckFinding;
import dev.hendrikhoemberg.webtesthelper.model.CheckType;

import java.util.List;
import java.util.Set;

/**
 * Outcome of the interaction pass (spec 5.2, 7.2).
 *
 * <p>Carries the transient findings and the two coverage sets (D74): what was actually driven,
 * not what was planned. A check that failed or timed out on all its targets is absent from
 * {@link #drivenTypes}, so silent failure cannot falsely resolve an existing finding.
 */
public record InteractionOutcome(List<CheckFinding> findings,
                                  Set<CheckType> drivenTypes,
                                  Set<String> drivenLocationKeys) {

    public InteractionOutcome {
        findings = findings == null ? List.of() : List.copyOf(findings);
        drivenTypes = drivenTypes == null ? Set.of() : Set.copyOf(drivenTypes);
        drivenLocationKeys = drivenLocationKeys == null ? Set.of() : Set.copyOf(drivenLocationKeys);
    }
}
