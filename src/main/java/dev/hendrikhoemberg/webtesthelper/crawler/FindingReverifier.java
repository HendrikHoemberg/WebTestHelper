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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
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
 *
 * <p>Interaction findings are excluded outright (D105). Their subject is not always a URL, and
 * where it is — {@code LANGUAGE_SWITCHER} names the locale it clicked through to — re-fetching it
 * answers a different question than the one the finding asked, so a recovery there would drop a
 * finding nothing had re-examined.
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
        Set<String> suspects = findings.stream()
                // An interaction check's verdict cannot be re-probed over HTTP (D78): fetching
                // LANGUAGE_SWITCHER's subject URL says nothing about whether the page was
                // translated. Its subject *is* a URL, so without this the whole finding would be
                // dropped by a recovery that never examined the thing it is about.
                .filter(finding -> !finding.type().interaction())
                .map(CheckFinding::subjectKey).distinct()
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
        Map<String, UrlVerification> latest = new HashMap<>();
        Duration delay = properties.reverifyDelay();
        // Only the subjects still believed dead go into the next attempt. Recovery is final —
        // there is nothing more to learn about a subject that answered OK — and re-probing it
        // would be a request the site never needed to serve (spec 8's politeness).
        List<NormalizedUrl> pending = new ArrayList<>(urls);
        for (int attempt = 0;
             attempt < properties.reverifyAttempts() && !pending.isEmpty();
             attempt++) {
            if (attempt > 0) {
                // With the default reverify-attempts=2 only one sleep happens, so the doubling
                // only engages once attempts reach 3 or more.
                sleep(delay);
                delay = delay.multipliedBy(2);
            }
            Map<String, UrlVerification> results = verifier.verifyAll(
                    pending, site.effectiveUserAgent(), DocumentTypes::isDocument);
            for (UrlVerification result : results.values()) {
                latest.put(result.url(), result);
                if (result.status() == UrlStatus.OK) {
                    recovered.add(result.url());
                }
            }
            pending.removeIf(url -> recovered.contains(url.value()));
        }

        List<UrlVerification> toStore = latest.values().stream()
                .filter(result -> !site.baseUrl().sameSiteAs(
                        UrlNormalizer.normalize(result.url()).orElseThrow()))
                .toList();
        if (!toStore.isEmpty()) {
            cache.store(toStore);
        }

        List<CheckFinding> surviving = new ArrayList<>(findings);
        surviving.removeIf(finding ->
                !finding.type().interaction() && recovered.contains(finding.subjectKey()));

        return new ReverificationOutcome(List.copyOf(surviving), Set.copyOf(recovered),
                urls.size());
    }

    private void sleep(Duration delay) {
        try {
            Thread.sleep(delay.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
