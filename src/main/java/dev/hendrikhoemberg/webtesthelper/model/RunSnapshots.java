package dev.hendrikhoemberg.webtesthelper.model;

import java.util.List;
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

    public Set<String> visitedUrls() {
        return snapshots.stream()
                .map(snapshot -> snapshot.url().value())
                .collect(Collectors.toUnmodifiableSet());
    }

    public int pageCount() {
        return snapshots.size();
    }
}