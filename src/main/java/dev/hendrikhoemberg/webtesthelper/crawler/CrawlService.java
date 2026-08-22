package dev.hendrikhoemberg.webtesthelper.crawler;

import dev.hendrikhoemberg.webtesthelper.crawler.persistence.CrawlFrontierJdbcRepository;
import dev.hendrikhoemberg.webtesthelper.model.LinkRef;
import dev.hendrikhoemberg.webtesthelper.model.NormalizedUrl;
import dev.hendrikhoemberg.webtesthelper.model.PageSnapshot;
import dev.hendrikhoemberg.webtesthelper.model.RunSnapshots;
import dev.hendrikhoemberg.webtesthelper.model.SiteContext;
import dev.hendrikhoemberg.webtesthelper.model.SoftNotFoundProbe;
import dev.hendrikhoemberg.webtesthelper.model.UrlNormalizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The crawl pipeline: seed the frontier from the base URL and the sitemap, probe
 * {base}/{uuid} for the site's not-found fingerprint, then drain the frontier in batches
 * through the browser pool until it runs dry or a budget is hit (spec 6.4, spec 14).
 *
 * <p>The fan-out uses virtual threads <em>submitting into</em> {@link BrowserPool}: a pile of
 * waiting TASK threads is cheap, but real concurrency is bounded by the pool's workers, each
 * of which owns one Playwright+Chromium on a platform thread (spec 5.4).
 *
 * <p>Coverage is the set of URLs actually visited — the frontier's {@code DONE} rows — and
 * {@code partialCoverage} is true whenever the frontier still holds {@code PENDING} rows when
 * the loop ends, whatever the reason. A run that stops on budget resolves nothing it did not
 * reach, and a week without a full crawl must not report last week's unreached pages as
 * regressed.
 */
@Service
public class CrawlService {

    private static final Logger log = LoggerFactory.getLogger(CrawlService.class);

    /** A malformed index pointing at itself must not become an infinite loop (spec 5.3). */
    private static final int SITEMAP_INDEX_LIMIT = 10;

    /**
     * How long a claim may sit untouched before its worker counts as dead. Comfortably longer
     * than the slowest single navigation, so a healthy worker is never robbed of its batch.
     */
    private static final Duration STALE_CLAIM_TIMEOUT = Duration.ofMinutes(10);

    /** A URL that has burned this many workers is given up on rather than reclaimed forever. */
    private static final int MAX_CLAIM_ATTEMPTS = 3;

    private final CrawlFrontierJdbcRepository frontier;
    private final BrowserPool pool;
    private final PageNavigator navigator;
    private final SiteResourceFetcher fetcher;
    private final CrawlerProperties properties;

    public CrawlService(CrawlFrontierJdbcRepository frontier, BrowserPool pool,
                        PageNavigator navigator, SiteResourceFetcher fetcher,
                        CrawlerProperties properties) {
        this.frontier = frontier;
        this.pool = pool;
        this.navigator = navigator;
        this.fetcher = fetcher;
        this.properties = properties;
    }

    public CrawlResult crawl(CrawlRequest request, CrawlProgressListener listener) {
        MDC.put("runId", String.valueOf(request.runId()));
        MDC.put("siteId", String.valueOf(request.site().siteId()));
        try {
            return doCrawl(request, listener);
        } finally {
            MDC.remove("runId");
            MDC.remove("siteId");
        }
    }

    private CrawlResult doCrawl(CrawlRequest request, CrawlProgressListener listener) {
        Path runArtifacts = properties.artifactDir().resolve(String.valueOf(request.runId()));
        try {
            Files.createDirectories(runArtifacts);
        } catch (IOException e) {
            throw new UncheckedIOException("Run-Artefakte nicht anlegbar: " + runArtifacts, e);
        }

        RobotsRules robots = request.site().respectRobots()
                ? UrlNormalizer.resolve(request.site().baseUrl().value(), "/robots.txt")
                        .flatMap(url -> fetcher.fetchText(url,
                                request.site().effectiveUserAgent()))
                        .map(RobotsRules::parse)
                        .orElse(RobotsRules.ALLOW_ALL)
                : RobotsRules.ALLOW_ALL;
        UrlAdmission admission = new UrlAdmission(request.site(), robots);

        // A run whose lease expired is re-queued and re-executed (spec 14), and rows its dead
        // worker had claimed are still CLAIMED. Unreclaimed they are never visited and never
        // pending, so the resumed run would report full coverage for pages it never reached —
        // which is precisely what spec 6.4 must not let happen.
        int reclaimed = frontier.reclaimStale(request.runId(), STALE_CLAIM_TIMEOUT, MAX_CLAIM_ATTEMPTS);
        if (reclaimed > 0) {
            log.info("Lauf {} setzt {} verwaiste Ansprüche zurück", request.runId(), reclaimed);
        }

        SoftNotFoundProbe probe = probe(request, runArtifacts);
        seedFrontier(request, admission, robots);
        log.info("Lauf {} startet: Budget {} Seiten, Tiefe {}, {} Lang", request.runId(),
                request.site().budget().maxPages(), request.site().budget().maxDepth(),
                request.site().budget().maxDuration());

        List<PageSnapshot> snapshots = Collections.synchronizedList(new ArrayList<>());
        AtomicInteger visited = new AtomicInteger();
        AtomicInteger failed = new AtomicInteger();
        Instant deadline = Instant.now().plus(request.site().budget().maxDuration());
        int maxPages = request.site().budget().maxPages();
        String stopReason = null;

        try (ExecutorService fanOut = Executors.newVirtualThreadPerTaskExecutor()) {
            while (true) {
                if (visited.get() >= maxPages) { stopReason = "maxPages"; break; }
                if (Instant.now().isAfter(deadline)) { stopReason = "maxDuration"; break; }

                int room = Math.min(properties.batchSize(), maxPages - visited.get());
                List<CrawlTarget> batch = frontier.claimBatch(request.runId(), request.owner(), room);
                if (batch.isEmpty()) {
                    break;                       // the frontier ran dry — a complete crawl
                }
                List<Future<CrawlOutcome>> pending = batch.stream()
                        .map(target -> fanOut.submit(() -> visit(
                                target, request, admission, runArtifacts, snapshots, visited, failed)))
                        .toList();
                List<CrawlOutcome> outcomes = new ArrayList<>(pending.size());
                for (Future<CrawlOutcome> future : pending) {
                    try {
                        outcomes.add(future.get());  // visit() never throws; FAILED outcome instead
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException("Crawl unterbrochen", e);
                    } catch (ExecutionException e) {
                        throw new IllegalStateException("Crawl-Aufgabe fehlgeschlagen", e.getCause());
                    }
                }
                frontier.complete(outcomes);
                listener.onProgress(visited.get(), failed.get());
            }
        }

        if (stopReason != null) {
            log.info("Lauf {} endet vorzeitig: {} ({} von {} besucht)", request.runId(),
                    stopReason, visited.get(), maxPages);
        }

        List<String> coveredUrls = frontier.visitedUrls(request.runId());
        boolean partial = frontier.countPending(request.runId()) > 0;
        log.info("Lauf {} beendet: {} besucht, {} fehlgeschlagen, partielle Abdeckung {}",
                request.runId(), visited.get(), failed.get(), partial);
        return new CrawlResult(
                new RunSnapshots(request.runId(), request.site(), List.copyOf(snapshots), probe),
                visited.get(), failed.get(), coveredUrls, partial, stopReason);
    }

    /**
     * One page visit. Never throws: a browser worker that dies mid-page must cost one URL, not
     * the run (spec 14), so every exceptional failure returns a FAILED outcome. Discovery only
     * reaches the frontier for whole-site scopes and only through {@link NormalizedUrl#value()},
     * which is the frontier's dedupe key.
     */
    private CrawlOutcome visit(CrawlTarget target, CrawlRequest request, UrlAdmission admission,
            Path runArtifacts, List<PageSnapshot> snapshots, AtomicInteger visited,
            AtomicInteger failed) {
        try {
            PageSnapshot snapshot = pool.submit(browser ->
                    navigator.capture(browser, target, request.site(), runArtifacts));
            snapshots.add(snapshot);
            if (!snapshot.reachable()) {
                failed.incrementAndGet();
                return new CrawlOutcome(target.id(), CrawlItemStatus.FAILED, null,
                        snapshot.unreachableReason());
            }
            visited.incrementAndGet();
            if (request.scope().crawlsWholeSite()) {
                List<String> discovered = snapshot.internalLinks().stream()
                        .map(LinkRef::target)
                        .filter(url -> admission.admit(url, target.depth() + 1).admitted())
                        .map(NormalizedUrl::value)
                        .distinct()
                        .toList();
                frontier.enqueue(request.runId(), discovered, target.depth() + 1, target.url());
            }
            return new CrawlOutcome(target.id(), CrawlItemStatus.DONE, snapshot.httpStatus(), null);
        } catch (RuntimeException e) {
            failed.incrementAndGet();
            return new CrawlOutcome(target.id(), CrawlItemStatus.FAILED, null,
                    truncate(e.getMessage(), 500));
        }
    }

    private void seedFrontier(CrawlRequest request, UrlAdmission admission, RobotsRules robots) {
        if (!request.scope().crawlsWholeSite()) {                       // PULSE (spec 9)
            // Pinning a page is not a way around robots or the site's own exclude patterns
            // (spec 8) — respectRobots on the site is. So the pinned set is admitted like any
            // other URL, at depth 0 because these are entry points.
            List<String> pinned = request.site().pinnedKeyPages().stream()
                    .map(page -> UrlNormalizer.resolve(request.site().baseUrl().value(), page))
                    .flatMap(Optional::stream)
                    .filter(page -> admitted(page, admission, request.runId()))
                    .map(NormalizedUrl::value).toList();
            frontier.seed(request.runId(), pinned, 0);
            return;
        }
        // The base URL is the entry point and is seeded unfiltered: a site whose include
        // patterns do not cover its own start page would otherwise crawl nothing at all.
        frontier.seed(request.runId(), List.of(request.site().baseUrl().value()), 0);

        // sitemap.xml, one level of sitemap-index following (spec 5.3).
        // Sitemap entry points sit one level below the base URL, so a maxDepth=0 run still
        // covers only the base page.
        String agent = request.site().effectiveUserAgent();
        for (NormalizedUrl sitemapUrl : sitemapUrls(request.site(), robots)) {
            fetcher.fetchText(sitemapUrl, agent).ifPresent(xml -> {
                List<String> admitted = sitemapLocations(sitemapUrl, xml, agent).stream()
                        .map(UrlNormalizer::normalize).flatMap(Optional::stream)
                        .filter(candidate -> admission.admit(candidate, 1).admitted())
                        .map(NormalizedUrl::value).toList();
                frontier.enqueue(request.runId(), admitted, 1, "sitemap.xml");
            });
        }
    }

    /** Logs why a pinned key page was dropped — otherwise a shrinking pulse set is a mystery. */
    private boolean admitted(NormalizedUrl page, UrlAdmission admission, long runId) {
        UrlAdmission.Decision decision = admission.admit(page, 0);
        if (!decision.admitted()) {
            log.info("Lauf {} überspringt die vorgemerkte Seite {}: {}",
                    runId, page.value(), decision.reason());
        }
        return decision.admitted();
    }

    /** {@code robots.sitemaps()} resolved against the base URL, or {base}/sitemap.xml as fallback. */
    private List<NormalizedUrl> sitemapUrls(SiteContext site, RobotsRules robots) {
        List<String> candidates = robots.sitemaps().isEmpty()
                ? List.of("/sitemap.xml")
                : robots.sitemaps();
        String base = site.baseUrl().value();
        return candidates.stream()
                .map(candidate -> UrlNormalizer.resolve(base, candidate))
                .flatMap(Optional::stream)
                .toList();
    }

    /**
     * The page URLs a sitemap or (one level of) a sitemap index declares. Each child index is
     * fetched at most once, capped at {@value #SITEMAP_INDEX_LIMIT} children — a malformed index
     * that points at itself (or a sprawling one) must neither loop nor flood the frontier.
     */
    private List<String> sitemapLocations(NormalizedUrl sitemap, String xml, String userAgent) {
        if (!SitemapReader.isIndex(xml)) {
            return SitemapReader.locations(xml);
        }
        List<String> pageUrls = new ArrayList<>();
        int fetched = 0;
        for (String child : SitemapReader.locations(xml).stream().distinct().toList()) {
            if (fetched >= SITEMAP_INDEX_LIMIT) {
                break;
            }
            fetched++;
            UrlNormalizer.resolve(sitemap.value(), child)
                    .flatMap(resolved -> fetcher.fetchText(resolved, userAgent))
                    .ifPresent(childXml -> pageUrls.addAll(SitemapReader.locations(childXml)));
        }
        return pageUrls;
    }

    /**
     * Deviation D11: the probe navigates. {base}/{uuid} is deliberate — a random path cannot be
     * a real page, so whatever answers for it is the site's not-found fingerprint (spec 7.1). Its
     * frontier id is -1 and it is never added to the snapshots: it is a measurement of the site,
     * not a page of it.
     */
    private SoftNotFoundProbe probe(CrawlRequest request, Path runArtifacts) {
        NormalizedUrl probeUrl = UrlNormalizer
                .resolve(request.site().baseUrl().value(), "/" + UUID.randomUUID()).orElseThrow();
        PageSnapshot snapshot = pool.submit(browser -> navigator.capture(browser,
                new CrawlTarget(-1L, probeUrl.value(), 0), request.site(), runArtifacts));
        return snapshot.reachable()
                ? new SoftNotFoundProbe(snapshot.httpStatus(), snapshot.textSimhash(),
                                        snapshot.textContent().length())
                : SoftNotFoundProbe.NONE;
    }

    private static String truncate(String message, int max) {
        if (message == null || message.length() <= max) {
            return message;
        }
        return message.substring(0, max);
    }
}