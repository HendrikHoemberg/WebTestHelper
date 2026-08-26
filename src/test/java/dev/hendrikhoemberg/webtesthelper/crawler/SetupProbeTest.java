package dev.hendrikhoemberg.webtesthelper.crawler;

import dev.hendrikhoemberg.webtesthelper.model.CrawlBudget;
import dev.hendrikhoemberg.webtesthelper.model.NormalizedUrl;
import dev.hendrikhoemberg.webtesthelper.model.SiteContext;
import dev.hendrikhoemberg.webtesthelper.model.UrlNormalizer;
import dev.hendrikhoemberg.webtesthelper.support.FixtureSite;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("browser")
class SetupProbeTest {

    private static FixtureSite site;
    private static Path artifacts;
    private static ProbeEvidence evidence;
    private static ProbeEvidence unreachable;

    @BeforeAll
    static void start() throws Exception {
        site = FixtureSite.start();
        artifacts = Files.createTempDirectory("wth-probe");
        // Matches application-test.properties for the navigation timeout; the probe's own budget
        // is the decided 120 s, far above what eight fast fixture pages need.
        CrawlerProperties crawlerProperties = new CrawlerProperties(2, 10, Duration.ofSeconds(5),
                Duration.ZERO, artifacts, true);
        SetupProbeProperties properties = new SetupProbeProperties(8, Duration.ofSeconds(120));
        BrowserPool pool = new BrowserPool(crawlerProperties);
        PageNavigator navigator = new PageNavigator(crawlerProperties, new HostThrottle());
        SetupProbe probe = new SetupProbe(pool, navigator, new SiteResourceFetcher(),
                properties, crawlerProperties);

        // Crawl once per class — the single fixture probe every assertion below reads.
        evidence = probe.probe(siteContext(site.baseUrl()));
        unreachable = probe.probe(siteContext("http://localhost:9/"));
    }

    @AfterAll
    static void stop() {
        site.close();
    }

    private static SiteContext siteContext(String baseUrl) {
        NormalizedUrl normalized = UrlNormalizer.normalize(baseUrl).orElseThrow();
        return new SiteContext(1L, "Fixture", normalized, CrawlBudget.DEFAULT,
                List.of(), List.of(), List.of(), true, null, Map.of());
    }

    @Test
    void theProbeIsReachableAndStartsAtTheBaseUrl() {
        assertThat(evidence.reachable()).isTrue();
        assertThat(evidence.pagesVisited()).startsWith(site.baseUrl());
        assertThat(evidence.pagesVisited())
                .hasSizeLessThanOrEqualTo(8);   // probe-pages: the homepage plus seven
    }

    @Test
    void formPagesContainTheContactPage() {
        assertThat(evidence.formPages()).contains(site.url("kontakt.html"));
    }

    @Test
    void mediaPagesContainTheMediaPage() {
        assertThat(evidence.mediaPages()).contains(site.url("medien.html"));
    }

    @Test
    void mapPagesContainTheContactPageWithItsMapsEmbed() {
        assertThat(evidence.mapPages()).contains(site.url("kontakt.html"));
    }

    @Test
    void languagesCoverTheHreflangSet() {
        assertThat(evidence.languages()).contains("de", "en");
    }

    @Test
    void documentLinksContainThePdfThatTheSiteLinksTo() {
        assertThat(evidence.documentLinks()).contains(site.url("dateien/handbuch.pdf"));
    }

    @Test
    void theSitemapIsFound() {
        assertThat(evidence.sitemapFound()).isTrue();
    }

    @Test
    void robotsBlockedPathsAreNeverVisited() {
        assertThat(evidence.pagesVisited())
                .noneMatch(url -> url.contains("/geheim/intern.html"));
    }

    @Test
    void unNavigableAssetsAreNeverVisited() {
        // A .pdf is an asset (UrlAdmission's NOT_NAVIGABLE), not a page; the probe must not try
        // to render it in the browser.
        assertThat(evidence.pagesVisited())
                .noneMatch(url -> url.contains(".pdf"));
    }

    @Test
    void anUnreachableSiteYieldssEmptyEvidenceWithAReason() {
        assertThat(unreachable.reachable()).isFalse();
        assertThat(unreachable.unreachableReason()).isNotBlank();
        assertThat(unreachable.pagesVisited()).isEmpty();
        assertThat(unreachable.formPages()).isEmpty();
        assertThat(unreachable.mediaPages()).isEmpty();
        assertThat(unreachable.mapPages()).isEmpty();
        assertThat(unreachable.languages()).isEmpty();
        assertThat(unreachable.documentLinks()).isEmpty();
        assertThat(unreachable.sitemapFound()).isFalse();
    }
}
