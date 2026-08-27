package dev.hendrikhoemberg.webtesthelper.runner;

import dev.hendrikhoemberg.webtesthelper.model.CheckFinding;
import dev.hendrikhoemberg.webtesthelper.model.CheckType;

import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Outcome of the interaction pass (spec 5.2, 7.2).
 *
 * <p>Carries the transient findings and the two things run coverage needs (D74), which are
 * deliberately not the same thing:
 * <ul>
 *   <li>{@link #candidateTypes} — every interaction check that was in scope and enabled, whether
 *       or not it managed to drive anything. Coverage needs this to keep those types out of the
 *       crawl-scoped resolve; dropping a type here because it failed would let the crawl resolve
 *       its findings on every page it visited.</li>
 *   <li>{@link #drivenUrlsByType} — per type, the target URLs it actually completed on. A check
 *       that threw or timed out on its only target contributes an empty set, so the run cannot
 *       resolve findings it failed to look for (D79).</li>
 * </ul>
 */
public record InteractionOutcome(List<CheckFinding> findings,
                                 Set<CheckType> candidateTypes,
                                 Map<CheckType, Set<String>> drivenUrlsByType) {

    public static final InteractionOutcome NONE =
            new InteractionOutcome(List.of(), Set.of(), Map.of());

    public InteractionOutcome {
        findings = findings == null ? List.of() : List.copyOf(findings);
        candidateTypes = candidateTypes == null ? Set.of() : Set.copyOf(candidateTypes);
        Map<CheckType, Set<String>> copied = new EnumMap<>(CheckType.class);
        if (drivenUrlsByType != null) {
            drivenUrlsByType.forEach((type, urls) -> copied.put(type, Set.copyOf(urls)));
        }
        drivenUrlsByType = Map.copyOf(copied);
    }

    /** The types that actually completed on at least one target — what the run screen reports. */
    public Set<CheckType> drivenTypes() {
        Set<CheckType> types = new LinkedHashSet<>();
        drivenUrlsByType.forEach((type, urls) -> {
            if (!urls.isEmpty()) {
                types.add(type);
            }
        });
        return types;
    }

    /** The union of every driven target, for display and for the run row. */
    public Set<String> drivenUrls() {
        Set<String> urls = new LinkedHashSet<>();
        drivenUrlsByType.values().forEach(urls::addAll);
        return urls;
    }
}
