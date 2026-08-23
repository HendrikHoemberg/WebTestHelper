package dev.hendrikhoemberg.webtesthelper.crawler;

import dev.hendrikhoemberg.webtesthelper.model.CheckFinding;
import dev.hendrikhoemberg.webtesthelper.model.DocumentTypes;
import dev.hendrikhoemberg.webtesthelper.model.NormalizedUrl;
import dev.hendrikhoemberg.webtesthelper.model.RunSnapshots;
import dev.hendrikhoemberg.webtesthelper.model.SiteContext;
import dev.hendrikhoemberg.webtesthelper.model.UrlNormalizer;
import dev.hendrikhoemberg.webtesthelper.model.UrlStatus;
import dev.hendrikhoemberg.webtesthelper.model.UrlVerification;
import dev.hendrikhoemberg.webtesthelper.model.UrlVerifications;
import dev.hendrikhoemberg.webtesthelper.crawler.persistence.ExternalUrlCacheJdbcRepository;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * A last chance for a dead-link finding to not be a false positive (spec 8). A URL the crawl saw
 * as dead is re-checked a few times; if it answers {@code OK} on any attempt the failure was
 * transient and the finding is dropped.
 *
 * <p>Selection is deliberately narrow (deviation D23): only findings whose subject is not a page
 * the crawl itself visited — so a browser verdict is never overturned — and whose <em>first-pass</em>
 * verification was {@code DEAD}. Anything the first pass called {@code OK} or {@code UNVERIFIABLE}
 * is left alone, and a subject that comes back {@code UNVERIFIABLE} on re-check is still treated as
 * broken and keeps its finding. Recovery is decided per subject, so two findings on the same dead
 * link (the link on two pages) are dropped together.
 *
 * <p>Re-checked results for external subjects are written back to the shared cache, so a transient
 * failure does not sit there for its full TTL — exactly the handoff from plan 3b.
 */
@Component
public class FindingReverifier {

    private final UrlVerifier verifier;
    private final ExternalUrlCacheJdbcRepository cache;
    private final VerifierProperties properties;

    public FindingReverifier(UrlVerifier verifier, ExternalUrlCacheJdbcRepository cache,
                             VerifierProperties properties) {
        this.verifier = verifier;
        this.cache = cache;
        this.properties = properties;
    }

    public ReverificationOutcome reverify(SiteContext site, RunSnapshots snapshots,
                                          UrlVerifications firstPass, List<CheckFinding> findings) {
        Set<String> visited = snapshots.visitedUrls();                    // browser verdicts stay (D23)
        Set<String> suspects = findings.stream().map(CheckFinding::subjectKey).distinct()
                .filter(subject -> !visited.contains(subject))
                .filter(subject -> {
                    UrlVerification first = firstPass.byUrl().get(subject);
                    return first != null && first.status() == UrlStatus.DEAD;
                })
                .collect(Collectors.toSet());

        if (suspects.isEmpty()) {
            return new ReverificationOutcome(List.copyOf(findings), Set.of(), 0);
        }

        List<NormalizedUrl> urls = suspects.stream()
                .map(UrlNormalizer::normalize)
                .filter(Optional::isPresent)
                .map(Optional::orElseThrow)
                .toList();

        Set<String> recovered = new HashSet<>();
        Map<String, UrlVerification> latest = new ConcurrentHashMap<>();
        Duration delay = properties.reverifyDelay();
        for (int attempt = 0;
             attempt < properties.reverifyAttempts() && recovered.size() < suspects.size();
             attempt++) {
            if (attempt > 0) {
                sleep(delay);
                delay = delay.multipliedBy(2);
            }
            Map<String, UrlVerification> results = verifier.verifyAll(
                    urls, site.effectiveUserAgent(), DocumentTypes::isDocument);
            for (UrlVerification result : results.values()) {
                latest.put(result.url(), result);
                if (result.status() == UrlStatus.OK) {
                    recovered.add(result.url());
                }
            }
        }

        List<UrlVerification> toStore = latest.values().stream()
                .filter(result -> !site.baseUrl().sameSiteAs(
                        UrlNormalizer.normalize(result.url()).orElseThrow()))
                .toList();
        if (!toStore.isEmpty()) {
            cache.store(toStore, site.siteId());
        }

        List<CheckFinding> surviving = new ArrayList<>(findings);
        surviving.removeIf(finding -> recovered.contains(finding.subjectKey()));

        return new ReverificationOutcome(List.copyOf(surviving), Set.copyOf(recovered),
                suspects.size());
    }

    private void sleep(Duration delay) {
        try {
            Thread.sleep(delay.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
