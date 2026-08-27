package dev.hendrikhoemberg.webtesthelper.checks;

import dev.hendrikhoemberg.webtesthelper.model.NormalizedUrl;
import dev.hendrikhoemberg.webtesthelper.model.PageSnapshot;
import dev.hendrikhoemberg.webtesthelper.model.RunSnapshots;
import dev.hendrikhoemberg.webtesthelper.model.SiteContext;
import dev.hendrikhoemberg.webtesthelper.model.UrlNormalizer;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Pure target-selection functions for interaction checks (spec 5.2, 7.2; deviation D81).
 *
 * <p>Every primitive is deterministic and stable across runs (deviation D74) so that run-to-run
 * findings resolution does not flap when an unrelated change alters crawl order or snapshot
 * ordering. URLs are sorted by {@link NormalizedUrl#value()} before truncation.
 */
public final class InteractionTargets {

    private InteractionTargets() {
    }

    /**
     * The snapshot whose URL equals {@link SiteContext#baseUrl()}; empty if the crawl never reached
     * it or it was unreachable.
     */
    public static List<NormalizedUrl> homepage(RunSnapshots snapshots, SiteContext site) {
        PageSnapshot home = snapshots.byUrlIndex().get(site.baseUrl().value());
        if (home != null && home.reachable()) {
            return List.of(home.url());
        }
        return List.of();
    }

    /**
     * Reachable snapshots whose {@link PageSnapshot#forms()} is non-empty, sorted by URL value
     * and truncated to {@code maxTargets}.
     */
    public static List<NormalizedUrl> withForm(RunSnapshots snapshots, int maxTargets) {
        if (maxTargets <= 0) {
            return List.of();
        }
        return snapshots.snapshots().stream()
                .filter(PageSnapshot::reachable)
                .filter(snapshot -> !snapshot.forms().isEmpty())
                .map(PageSnapshot::url)
                .distinct()
                .sorted(Comparator.comparing(NormalizedUrl::value))
                .limit(maxTargets)
                .toList();
    }

    /**
     * The intersection of {@link SiteContext#pinnedKeyPages()} with the crawl's reachable snapshots,
     * sorted by URL value and truncated to {@code maxTargets}, falling back to {@link #homepage}
     * when the pin set is empty or disjoint.
     */
    public static List<NormalizedUrl> keyPagesOrHomepage(RunSnapshots snapshots, SiteContext site,
                                                         int maxTargets) {
        if (maxTargets <= 0) {
            return List.of();
        }
        if (site.pinnedKeyPages().isEmpty()) {
            return homepage(snapshots, site);
        }
        Map<String, PageSnapshot> index = snapshots.byUrlIndex();
        List<NormalizedUrl> matched = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (String pin : site.pinnedKeyPages()) {
            UrlNormalizer.resolve(site.baseUrl().value(), pin)
                    .map(NormalizedUrl::value)
                    .ifPresent(key -> {
                        PageSnapshot snapshot = index.get(key);
                        if (snapshot != null && snapshot.reachable() && seen.add(snapshot.url().value())) {
                            matched.add(snapshot.url());
                        }
                    });
        }
        if (matched.isEmpty()) {
            return homepage(snapshots, site);
        }
        return matched.stream()
                .sorted(Comparator.comparing(NormalizedUrl::value))
                .limit(maxTargets)
                .toList();
    }
}
