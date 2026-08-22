package dev.hendrikhoemberg.webtesthelper.crawler;

import dev.hendrikhoemberg.webtesthelper.crawler.persistence.ExternalUrlCacheJdbcRepository;
import dev.hendrikhoemberg.webtesthelper.model.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

@Component
public class UrlVerificationService {

    private static final Logger log = LoggerFactory.getLogger(UrlVerificationService.class);

    private final UrlVerifier verifier;
    private final ExternalUrlCacheJdbcRepository cache;

    public UrlVerificationService(UrlVerifier verifier, ExternalUrlCacheJdbcRepository cache) {
        this.verifier = verifier;
        this.cache = cache;
    }

    public UrlVerifications verify(SiteContext site, RunSnapshots snapshots,
                                   List<String> candidates) {
        Instant now = Instant.now();

        // Seed from snapshots: no HTTP request needed for already-visited pages.
        Map<String, UrlVerification> results = snapshots.snapshots().stream()
                .collect(Collectors.toMap(
                        s -> s.url().value(),
                        UrlVerification::ofSnapshot));

        // Drop candidates already seeded from snapshots.
        List<String> unvisited = candidates.stream()
                .filter(c -> {
                    Optional<NormalizedUrl> norm = UrlNormalizer.normalize(c);
                    return norm.isPresent() && !results.containsKey(norm.get().value());
                })
                .toList();
        int snapshotSeeded = candidates.size() - unvisited.size();

        // Split on internal vs external.
        NormalizedUrl siteBaseUrl = site.baseUrl();
        List<String> internal = new ArrayList<>();
        List<String> external = new ArrayList<>();
        for (String c : unvisited) {
            Optional<NormalizedUrl> norm = UrlNormalizer.normalize(c);
            if (norm.isEmpty()) {
                continue;
            }
            if (siteBaseUrl.sameSiteAs(norm.get())) {
                internal.add(c);
            } else {
                external.add(c);
            }
        }

        // External: cache-first, then fetch misses and store.
        int cacheHits = 0;
        int fetched = 0;
        List<UrlVerification> toStore = new ArrayList<>();
        if (!external.isEmpty()) {
            List<String> externalKeys = external.stream()
                    .map(c -> UrlNormalizer.normalize(c).map(NormalizedUrl::value).orElse(null))
                    .filter(java.util.Objects::nonNull)
                    .toList();
            Map<String, UrlVerification> fresh = cache.fresh(externalKeys, now);

            // Separate cache hits into those we can use as-is and those that need re-fetch.
            List<UrlVerification> hitsToStore = new ArrayList<>();
            for (String key : externalKeys) {
                UrlVerification v = fresh.get(key);
                if (v == null) {
                    continue;
                }
                // Document candidate with no body_prefix: must re-fetch to get the body.
                Optional<NormalizedUrl> norm = UrlNormalizer.normalize(key);
                if (norm.isPresent() && DocumentTypes.isDocument(norm.get()) && v.bodyPrefix() == null) {
                    // Treat as miss — will be re-fetched below.
                    continue;
                }
                cacheHits++;
                results.put(key, v);
                hitsToStore.add(v);
            }

            // Store cache hits to merge the current site id into dependent_site_ids.
            if (!hitsToStore.isEmpty()) {
                cache.store(hitsToStore, site.siteId());
            }

            List<String> misses = external.stream()
                    .filter(c -> UrlNormalizer.normalize(c)
                            .map(n -> !results.containsKey(n.value()))
                            .orElse(true))
                    .toList();
            if (!misses.isEmpty()) {
                List<NormalizedUrl> missNorms = misses.stream()
                        .map(c -> UrlNormalizer.normalize(c).orElse(null))
                        .filter(java.util.Objects::nonNull)
                        .toList();
                Map<String, UrlVerification> fetchedResults = verifier.verifyAll(missNorms,
                        site.effectiveUserAgent(), DocumentTypes::isDocument);
                results.putAll(fetchedResults);
                toStore.addAll(fetchedResults.values());
                fetched = fetchedResults.size();
            }
        }

        // Internal: always fetch.
        if (!internal.isEmpty()) {
            List<NormalizedUrl> internalNorms = internal.stream()
                    .map(c -> UrlNormalizer.normalize(c).orElse(null))
                    .filter(java.util.Objects::nonNull)
                    .toList();
            Map<String, UrlVerification> internalResults = verifier.verifyAll(internalNorms,
                    site.effectiveUserAgent(), DocumentTypes::isDocument);
            results.putAll(internalResults);
        }

        // Store newly fetched external results in cache.
        if (!toStore.isEmpty()) {
            cache.store(toStore, site.siteId());
        }

        // Log summary.
        long okCount = results.values().stream().filter(UrlVerification::ok).count();
        long deadCount = results.values().stream()
                .filter(v -> v.status() == UrlStatus.DEAD).count();
        long unverifiableCount = results.values().stream()
                .filter(v -> v.status() == UrlStatus.UNVERIFIABLE).count();
        log.info("URL verification run: candidates={}, snapshotSeeded={}, cacheHits={}, fetched={}, ok={}, dead={}, unverifiable={}",
                candidates.size(), snapshotSeeded, cacheHits, fetched, okCount, deadCount, unverifiableCount);

        return new UrlVerifications(results);
    }
}
