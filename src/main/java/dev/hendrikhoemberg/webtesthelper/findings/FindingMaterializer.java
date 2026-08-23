package dev.hendrikhoemberg.webtesthelper.findings;

import dev.hendrikhoemberg.webtesthelper.model.CheckFinding;
import dev.hendrikhoemberg.webtesthelper.model.CheckType;
import dev.hendrikhoemberg.webtesthelper.model.NormalizedUrl;
import dev.hendrikhoemberg.webtesthelper.model.Severity;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * Turns transient {@link CheckFinding}s into persisted-shaped {@link MaterialisedFinding}s.
 *
 * <p>Findings are grouped by {@code (type, subjectKey)}. If the group spans more distinct page
 * locations than {@code siteWideThreshold}, it is promoted to a single site-wide finding at
 * location {@code "*"}; otherwise it splits into one finding per location. Within each resulting
 * finding, occurrences are deduped by page, the finding's own severity is the {@link Severity#max}
 * of its occurrences, and its message, args and evidence come from the highest-severity occurrence
 * (ties broken by the lowest page URL) so the headline never contradicts the severity beside it.
 *
 * <p>{@link LinkedHashMap}/{@link LinkedHashSet} are used throughout so output order follows input
 * order and materialisation is deterministic — the diff (plan 4, task 4) depends on it.
 */
public final class FindingMaterializer {

    private FindingMaterializer() {
    }

    public static List<MaterialisedFinding> materialise(long siteId, List<CheckFinding> findings,
            int siteWideThreshold) {
        Map<GroupKey, List<CheckFinding>> groups = new LinkedHashMap<>();
        for (CheckFinding f : findings) {
            GroupKey key = new GroupKey(f.type(), f.subjectKey());
            groups.computeIfAbsent(key, k -> new ArrayList<>()).add(f);
        }
        List<MaterialisedFinding> result = new ArrayList<>();
        for (Map.Entry<GroupKey, List<CheckFinding>> entry : groups.entrySet()) {
            result.addAll(materialiseGroup(siteId, entry.getKey(), entry.getValue(), siteWideThreshold));
        }
        return List.copyOf(result);
    }

    private static List<MaterialisedFinding> materialiseGroup(long siteId, GroupKey key,
            List<CheckFinding> group, int threshold) {
        LinkedHashSet<String> distinctLocations = new LinkedHashSet<>();
        for (CheckFinding f : group) {
            distinctLocations.add(f.locationKey());
        }
        if (distinctLocations.size() > threshold) {
            return List.of(buildFinding(siteId, key, "*", group));
        }
        List<MaterialisedFinding> out = new ArrayList<>();
        for (String location : distinctLocations) {
            List<CheckFinding> atLocation = new ArrayList<>();
            for (CheckFinding f : group) {
                if (f.locationKey().equals(location)) {
                    atLocation.add(f);
                }
            }
            out.add(buildFinding(siteId, key, location, atLocation));
        }
        return out;
    }

    private static MaterialisedFinding buildFinding(long siteId, GroupKey key, String locationKey,
            List<CheckFinding> scope) {
        Map<String, CheckFinding> byPage = new LinkedHashMap<>();
        for (CheckFinding f : scope) {
            byPage.merge(pageUrlOf(f), f, FindingMaterializer::pickRepresentative);
        }
        List<FindingOccurrence> occurrences = new ArrayList<>();
        for (Map.Entry<String, CheckFinding> e : byPage.entrySet()) {
            CheckFinding rep = e.getValue();
            occurrences.add(new FindingOccurrence(e.getKey(), rep.severity(), rep.messageKey(),
                    rep.messageArgs(), rep.evidence()));
        }
        CheckFinding findingRep = scope.stream().min(REPRESENTATIVE_ORDER).orElseThrow();
        String fingerprint = Fingerprint.of(siteId, key.type(), key.subjectKey(), locationKey);
        return new MaterialisedFinding(fingerprint, key.type(), findingRep.severity(),
                key.subjectKey(), locationKey, findingRep.messageKey(), findingRep.messageArgs(),
                findingRep.evidence(), occurrences);
    }

    private static String pageUrlOf(CheckFinding f) {
        NormalizedUrl observedOn = f.observedOn();
        return observedOn == null ? null : observedOn.value();
    }

    private static CheckFinding pickRepresentative(CheckFinding a, CheckFinding b) {
        return REPRESENTATIVE_ORDER.compare(a, b) <= 0 ? a : b;
    }

    /** Highest severity first, then lowest page URL (null first), then message key. */
    private static final Comparator<CheckFinding> REPRESENTATIVE_ORDER = Comparator
            .comparingInt((CheckFinding f) -> f.severity().ordinal())
            .thenComparing(FindingMaterializer::pageUrlOf, Comparator.nullsFirst(Comparator.naturalOrder()))
            .thenComparing(CheckFinding::messageKey, Comparator.nullsFirst(Comparator.naturalOrder()));

    private record GroupKey(CheckType type, String subjectKey) {
    }
}
