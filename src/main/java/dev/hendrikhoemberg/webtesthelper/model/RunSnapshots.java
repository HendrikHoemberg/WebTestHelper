package dev.hendrikhoemberg.webtesthelper.model;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/** Everything one run saw. The input to every SiteCheck (spec 7.3) and to materialisation. */
public record RunSnapshots(long runId, SiteContext site, List<PageSnapshot> snapshots,
                           SoftNotFoundProbe softNotFound) {

    public RunSnapshots {
        snapshots = List.copyOf(snapshots);
    }

    public Optional<PageSnapshot> byUrl(String normalizedUrl) {
        return snapshots.stream()
                .filter(snapshot -> snapshot.url().value().equals(normalizedUrl))
                .findFirst();
    }

    /**
     * The same lookup as {@link #byUrl}, built once — for a site check that resolves many URLs
     * against the crawl. Scanning per lookup is fine for one call and quadratic for a sitemap
     * with an entry per page, which a real site has.
     *
     * <p>Two snapshots can share a key: the frontier dedupes on the URL that was
     * <em>requested</em> while a snapshot carries the one that finally answered, so a page
     * reachable under two addresses is crawled twice. First wins, matching {@link #byUrl}.
     */
    public Map<String, PageSnapshot> byUrlIndex() {
        Map<String, PageSnapshot> index = new LinkedHashMap<>();
        for (PageSnapshot snapshot : snapshots) {
            index.putIfAbsent(snapshot.url().value(), snapshot);
        }
        return Collections.unmodifiableMap(index);
    }

    public Set<String> visitedUrls() {
        return snapshots.stream()
                .map(snapshot -> snapshot.url().value())
                .collect(Collectors.toUnmodifiableSet());
    }

    public int pageCount() {
        return snapshots.size();
    }
}