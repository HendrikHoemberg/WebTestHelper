package dev.hendrikhoemberg.webtesthelper.reporting;

import dev.hendrikhoemberg.webtesthelper.model.RunScope;

import java.time.Instant;
import java.util.List;
import java.util.Set;

/**
 * Multi-site summary covering a closed digest window across a given scope tier.
 */
public record Digest(
        RunScope scope,
        Instant closedAt,
        List<SiteDigest> sites
) {
    public Digest {
        sites = sites == null ? List.of() : List.copyOf(sites);
    }

    public Digest restrictedTo(Set<Long> siteIds) {
        if (siteIds == null) {
            return new Digest(scope, closedAt, List.of());
        }
        List<SiteDigest> filtered = sites.stream()
                .filter(site -> siteIds.contains(site.siteId()))
                .toList();
        return new Digest(scope, closedAt, filtered);
    }

    public boolean notifiable() {
        return scope == RunScope.DEEP || sites.stream().anyMatch(SiteDigest::notable);
    }

    public boolean allClear() {
        return sites.stream().noneMatch(SiteDigest::notable);
    }

    public int errorTotal() {
        return sites.stream().mapToInt(SiteDigest::errorCount).sum();
    }

    public int failedRuns() {
        return (int) sites.stream().filter(SiteDigest::failed).count();
    }
}
