package dev.hendrikhoemberg.webtesthelper.crawler;

import dev.hendrikhoemberg.webtesthelper.crawler.persistence.ExternalUrlCacheJdbcRepository;
import dev.hendrikhoemberg.webtesthelper.model.DocumentTypes;
import dev.hendrikhoemberg.webtesthelper.model.NormalizedUrl;
import dev.hendrikhoemberg.webtesthelper.model.PageSnapshot;
import dev.hendrikhoemberg.webtesthelper.model.RunSnapshots;
import dev.hendrikhoemberg.webtesthelper.model.SiteContext;
import dev.hendrikhoemberg.webtesthelper.model.UrlNormalizer;
import dev.hendrikhoemberg.webtesthelper.model.UrlStatus;
import dev.hendrikhoemberg.webtesthelper.model.UrlVerification;
import dev.hendrikhoemberg.webtesthelper.model.UrlVerifications;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Answers "does this URL resolve" for everything the crawl saw but did not navigate (deviation
 * D19), reusing what the crawl already knows and the shared cache before touching the network.
 *
 * <p>Three sources, cheapest first: a page the crawl visited answers from its own snapshot at no
 * cost; an external URL another site checked within the TTL answers from {@code
 * external_url_check} (spec 8.1); everything else is fetched. Only external results are cached —
 * a site's own pages are where a day-old answer would be wrong, and they sit on the host we are
 * already crawling (deviation D20).
 */
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
        Map<String, UrlVerification> results = seedFromSnapshots(snapshots);
        int snapshotSeeded = 0;

        // Every candidate is normalised exactly once, here: a candidate that does not normalise is
        // not a defect, it is a mailto: or a javascript: href, and it simply drops out.
        Map<String, NormalizedUrl> unvisited = new LinkedHashMap<>();
        for (String candidate : candidates) {
            NormalizedUrl url = UrlNormalizer.normalize(candidate).orElse(null);
            if (url == null) {
                continue;
            }
            if (results.containsKey(url.value())) {
                snapshotSeeded++;
            } else {
                unvisited.putIfAbsent(url.value(), url);
            }
        }

        List<NormalizedUrl> internal = new ArrayList<>();
        List<NormalizedUrl> external = new ArrayList<>();
        for (NormalizedUrl url : unvisited.values()) {
            (site.baseUrl().sameSiteAs(url) ? internal : external).add(url);
        }

        int cacheHits = takeFromCache(external, results, site.siteId(), now);
        List<NormalizedUrl> misses = external.stream()
                .filter(url -> !results.containsKey(url.value()))
                .toList();

        Map<String, UrlVerification> fetched = fetch(misses, site);
        results.putAll(fetched);
        if (!fetched.isEmpty()) {
            cache.store(fetched.values(), site.siteId());
        }
        results.putAll(fetch(internal, site));

        logSummary(candidates.size(), snapshotSeeded, cacheHits, fetched.size(), results.values());
        return new UrlVerifications(results);
    }

    /**
     * A page the crawl navigated needs no request at all. Two snapshots can share one key: the
     * frontier dedupes on the URL that was <em>requested</em> while a snapshot carries the one
     * that finally answered, so {@code /kontakt} and {@code /kontakt.html} are two crawl items and
     * one page. First snapshot wins — they describe the same response.
     */
    private static Map<String, UrlVerification> seedFromSnapshots(RunSnapshots snapshots) {
        Map<String, UrlVerification> results = new LinkedHashMap<>();
        for (PageSnapshot snapshot : snapshots.snapshots()) {
            results.putIfAbsent(snapshot.url().value(), UrlVerification.ofSnapshot(snapshot));
        }
        return results;
    }

    /**
     * Fills {@code results} from the shared cache and returns how many entries came from it.
     */
    private int takeFromCache(List<NormalizedUrl> external, Map<String, UrlVerification> results,
            long siteId, Instant now) {
        if (external.isEmpty()) {
            return 0;
        }
        Map<String, UrlVerification> fresh = cache.fresh(
                external.stream().map(NormalizedUrl::value).toList(), now);
        List<UrlVerification> hits = new ArrayList<>();
        for (NormalizedUrl url : external) {
            UrlVerification cached = fresh.get(url.value());
            // A document cached from a HEAD carries no body, and FILE_DOWNLOAD cannot judge a PDF
            // without one — so that row is a miss and the URL is fetched again.
            if (cached == null
                    || (DocumentTypes.isDocument(url) && cached.bodyPrefix() == null)) {
                continue;
            }
            results.put(url.value(), cached);
            hits.add(cached);
        }
        if (!hits.isEmpty()) {
            cache.store(hits, siteId);
        }
        return hits.size();
    }

    private Map<String, UrlVerification> fetch(List<NormalizedUrl> urls, SiteContext site) {
        if (urls.isEmpty()) {
            return Map.of();
        }
        return verifier.verifyAll(urls, site.effectiveUserAgent(), DocumentTypes::isDocument);
    }

    /** A pass that silently fetched nothing is otherwise invisible. */
    private static void logSummary(int candidates, int snapshotSeeded, int cacheHits, int fetched,
            Collection<UrlVerification> results) {
        long ok = results.stream().filter(UrlVerification::ok).count();
        long dead = results.stream().filter(v -> v.status() == UrlStatus.DEAD).count();
        long unverifiable = results.stream()
                .filter(v -> v.status() == UrlStatus.UNVERIFIABLE).count();
        log.info("URL verification run: candidates={}, snapshotSeeded={}, cacheHits={}, "
                        + "fetched={}, ok={}, dead={}, unverifiable={}",
                candidates, snapshotSeeded, cacheHits, fetched, ok, dead, unverifiable);
    }
}
