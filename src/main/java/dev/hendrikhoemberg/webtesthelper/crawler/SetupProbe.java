package dev.hendrikhoemberg.webtesthelper.crawler;

import dev.hendrikhoemberg.webtesthelper.model.AlternateRef;
import dev.hendrikhoemberg.webtesthelper.model.DocumentTypes;
import dev.hendrikhoemberg.webtesthelper.model.FrameRef;
import dev.hendrikhoemberg.webtesthelper.model.LinkRef;
import dev.hendrikhoemberg.webtesthelper.model.NormalizedUrl;
import dev.hendrikhoemberg.webtesthelper.model.PageSnapshot;
import dev.hendrikhoemberg.webtesthelper.model.SiteContext;
import dev.hendrikhoemberg.webtesthelper.model.UrlNormalizer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * The browser half of guided setup: read what a site contains without a full crawl, so the
 * setup wizard can propose the default checks. Blocking (in production it runs on a background
 * thread) with one {@link BrowserPool#submit} per page, so no worker is held for the whole
 * probe and no navigation is ever interrupted mid-flight — the deadline is checked between pages.
 */
@Component
public class SetupProbe {

    private final BrowserPool pool;
    private final PageNavigator navigator;
    private final SiteResourceFetcher fetcher;
    private final SetupProbeProperties properties;
    private final CrawlerProperties crawlerProperties;
    private final HostThrottle throttle;

    @Autowired
    public SetupProbe(BrowserPool pool, PageNavigator navigator, SiteResourceFetcher fetcher,
            SetupProbeProperties properties, CrawlerProperties crawlerProperties,
            HostThrottle throttle) {
        this.pool = pool;
        this.navigator = navigator;
        this.fetcher = fetcher;
        this.properties = properties;
        this.crawlerProperties = crawlerProperties;
        this.throttle = throttle;
    }

    public SetupProbe(BrowserPool pool, PageNavigator navigator, SiteResourceFetcher fetcher,
            SetupProbeProperties properties, CrawlerProperties crawlerProperties) {
        this(pool, navigator, fetcher, properties, crawlerProperties, new HostThrottle());
    }

    public ProbeEvidence probe(SiteContext site) {
        Instant deadline = Instant.now().plus(properties.probeTimeout());
        Path artifacts = crawlerProperties.artifactDir().resolve("probe");
        NormalizedUrl base = site.baseUrl();

        RobotsRules robots = site.respectRobots()
                ? UrlNormalizer.resolve(base.value(), "/robots.txt")
                        .flatMap(url -> fetcher.fetchText(url, site.effectiveUserAgent()))
                        .map(RobotsRules::parse)
                        .orElse(RobotsRules.ALLOW_ALL)
                : RobotsRules.ALLOW_ALL;
        UrlAdmission admission = new UrlAdmission(site, robots);

        PageSnapshot home = capture(base, site, artifacts);
        if (!home.reachable()) {
            return ProbeEvidence.unreachable(home.unreachableReason());
        }

        List<String> candidates = home.internalLinks().stream()
                .map(link -> link.target().value())
                .distinct()                                   // document order survives; the nav comes first
                .filter(url -> !url.equals(base.value()))
                .map(UrlNormalizer::normalize).flatMap(Optional::stream)
                .filter(url -> admission.admit(url, 0).admitted())   // depth 0: probe pages are entry points
                .map(NormalizedUrl::value)
                .limit(properties.probePages() - 1)
                .toList();

        List<String> pagesVisited = new ArrayList<>();
        List<String> formPages = new ArrayList<>();
        List<String> mediaPages = new ArrayList<>();
        List<String> mapPages = new ArrayList<>();
        Set<String> languages = new LinkedHashSet<>();
        List<String> documentLinks = new ArrayList<>();

        collect(home, pagesVisited, formPages, mediaPages, mapPages, languages, documentLinks);
        for (String candidate : candidates) {
            if (Instant.now().isAfter(deadline)) {   // checked between pages, never inside a navigation
                break;
            }
            UrlNormalizer.normalize(candidate).ifPresent(url ->
                    collect(capture(url, site, artifacts), pagesVisited, formPages, mediaPages,
                            mapPages, languages, documentLinks));
        }

        return new ProbeEvidence(true, null, pagesVisited, formPages, mediaPages, mapPages,
                languages, documentLinks, sitemapFound(site, robots), base.isSecure());
    }

    private PageSnapshot capture(NormalizedUrl url, SiteContext site, Path artifacts) {
        throttle.await(url.host(), crawlerProperties.perHostDelay());
        return pool.submit(browser -> navigator.capture(browser,
                new CrawlTarget(-1L, url.value(), 0), site, artifacts));
    }

    private void collect(PageSnapshot snapshot, List<String> pagesVisited, List<String> formPages,
            List<String> mediaPages, List<String> mapPages, Set<String> languages,
            List<String> documentLinks) {
        if (!snapshot.reachable()) {
            return;
        }
        String page = snapshot.url().value();
        pagesVisited.add(page);
        if (!snapshot.forms().isEmpty() && !formPages.contains(page)) {
            formPages.add(page);
        }
        if (!snapshot.media().isEmpty() && !mediaPages.contains(page)) {
            mediaPages.add(page);
        }
        if (snapshot.frames().stream().anyMatch(FrameRef::isMapsEmbed) && !mapPages.contains(page)) {
            mapPages.add(page);
        }
        for (AlternateRef alternate : snapshot.alternates()) {
            languages.add(alternate.hreflang());
        }
        for (LinkRef link : snapshot.links()) {
            String target = link.target().value();
            if (DocumentTypes.isDocument(link.target()) && !documentLinks.contains(target)) {
                documentLinks.add(target);
            }
        }
    }

    /** {@code sitemapFound} flips {@code SITEMAP_CONSISTENCY}, which {@code SiteService.NOISY_BY_DEFAULT} ships off. */
    private boolean sitemapFound(SiteContext site, RobotsRules robots) {
        List<String> candidates = robots.sitemaps().isEmpty()
                ? List.of("/sitemap.xml")
                : robots.sitemaps();
        String base = site.baseUrl().value();
        String agent = site.effectiveUserAgent();
        return candidates.stream()
                .map(candidate -> UrlNormalizer.resolve(base, candidate))
                .flatMap(Optional::stream)
                .anyMatch(url -> fetcher.fetchText(url, agent).isPresent());
    }
}
