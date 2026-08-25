package dev.hendrikhoemberg.webtesthelper.runner;

import dev.hendrikhoemberg.webtesthelper.model.NormalizedUrl;
import dev.hendrikhoemberg.webtesthelper.model.PageSnapshot;
import dev.hendrikhoemberg.webtesthelper.model.RunSnapshots;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Selects the pulse set: the key pages a site's periodic check should cover, pinned from the
 * first full crawl so the set cannot drift between runs (spec 9, §6.4).
 *
 * <p>The ranking counts distinct sourcing pages, not raw links: five pages linking a target
 * once beats one page linking it five times, and a page linking a target twice is one source
 * (deviation D45). A target that was not successfully crawled is dropped, so the set can never
 * come to include a page the run failed on.
 */
public final class KeyPageSelector {

    private KeyPageSelector() {
    }

    /**
     * @param snapshots everything one run saw
     * @param baseUrl   the site's normalised base URL — always first in the result
     * @param limit     how many key pages beyond the base URL to keep
     * @return absolute normalised URLs, base URL first, at most {@code limit + 1} of them
     */
    public static List<String> select(RunSnapshots snapshots, NormalizedUrl baseUrl, int limit) {
        Map<String, Long> inboundSources = snapshots.snapshots().stream()
                .flatMap(page -> page.internalLinks().stream()
                        .map(link -> link.target().value())
                        .distinct()                        // D45: one page linking a target twice is one
                        .map(target -> Map.entry(target, page.url().value())))
                .filter(entry -> !entry.getKey().equals(entry.getValue()))   // a self-link is not a source
                .collect(Collectors.groupingBy(Map.Entry::getKey, Collectors.counting()));

        Map<String, PageSnapshot> crawled = snapshots.byUrlIndex();
        List<String> ranked = inboundSources.entrySet().stream()
                .filter(entry -> healthy(crawled.get(entry.getKey())))      // reachable and 2xx
                .sorted(Comparator.<Map.Entry<String, Long>>comparingLong(Map.Entry::getValue).reversed()
                        .thenComparing(Map.Entry::getKey))                  // deterministic, or the set drifts
                .map(Map.Entry::getKey)
                .filter(url -> !url.equals(baseUrl.value()))
                .limit(limit)
                .toList();

        List<String> result = new ArrayList<>(ranked.size() + 1);
        result.add(baseUrl.value());
        result.addAll(ranked);
        return result;
    }

    private static boolean healthy(PageSnapshot snapshot) {
        return snapshot != null && snapshot.reachable()
                && snapshot.httpStatus() >= 200 && snapshot.httpStatus() < 300;
    }
}
