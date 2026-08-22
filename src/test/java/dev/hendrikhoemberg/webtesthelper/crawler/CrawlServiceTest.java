package dev.hendrikhoemberg.webtesthelper.crawler;

import dev.hendrikhoemberg.webtesthelper.crawler.persistence.CrawlFrontierJdbcRepository;
import dev.hendrikhoemberg.webtesthelper.model.CrawlBudget;
import dev.hendrikhoemberg.webtesthelper.model.PageSnapshot;
import dev.hendrikhoemberg.webtesthelper.model.RunScope;
import dev.hendrikhoemberg.webtesthelper.model.SimHash;
import dev.hendrikhoemberg.webtesthelper.model.SiteContext;
import dev.hendrikhoemberg.webtesthelper.model.SoftNotFoundProbe;
import dev.hendrikhoemberg.webtesthelper.model.UrlNormalizer;
import dev.hendrikhoemberg.webtesthelper.support.AbstractPostgresTest;
import dev.hendrikhoemberg.webtesthelper.support.FixtureSite;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("browser")
class CrawlServiceTest extends AbstractPostgresTest {

    @Autowired CrawlService crawler;
    @Autowired JdbcTemplate jdbc;
    @Autowired CrawlFrontierJdbcRepository frontier;

    private static FixtureSite site;
    private long siteId;
    private long runId;

    @BeforeAll static void startSite() { site = FixtureSite.start(); }
    @AfterAll static void stopSite() { site.close(); }

    @BeforeEach
    void freshRun() {
        jdbc.update("DELETE FROM crawl_queue_item");
        jdbc.update("DELETE FROM run");
        jdbc.update("DELETE FROM site");
        siteId = jdbc.queryForObject("INSERT INTO site (name, base_url) VALUES (?, ?) RETURNING id",
                Long.class, "Fixture", site.baseUrl());
        runId = jdbc.queryForObject("INSERT INTO run (site_id, trigger_type, scope, status) "
                + "VALUES (?, 'MANUAL', 'FULL', 'RUNNING') RETURNING id", Long.class, siteId);
    }

    private static CrawlBudget budget(int maxPages, int maxDepth, Duration maxDuration) {
        return new CrawlBudget(maxPages, maxDepth, maxDuration);
    }

    /**
     * The SiteContext is built here rather than read back through SiteService: these cases vary
     * the budget and the pinned pages, and the site row never needs to.
     */
    private CrawlRequest request(RunScope scope, CrawlBudget budget, List<String> pinned) {
        SiteContext context = new SiteContext(siteId, "Fixture",
                UrlNormalizer.normalize(site.baseUrl()).orElseThrow(), budget,
                List.of(), List.of(), pinned, true, null, Map.of());
        return new CrawlRequest(runId, context, scope, "test-worker");
    }

    private CrawlResult crawl(RunScope scope, CrawlBudget budget, List<String> pinned) {
        return crawler.crawl(request(scope, budget, pinned), (visited, failed) -> { });
    }

    @Test
    void aFullCrawlReachesEveryCrawlablePageExactlyOnce() {
        CrawlResult result = crawl(RunScope.FULL, budget(50, 3, Duration.ofMinutes(3)), List.of());

        assertThat(result.coveredUrls()).doesNotHaveDuplicates();
        assertThat(result.coveredUrls()).anySatisfy(url -> assertThat(url).endsWith("/leistungen.html"));
        assertThat(result.coveredUrls()).anySatisfy(url -> assertThat(url).endsWith("/kontakt.html"));
        assertThat(result.coveredUrls()).anySatisfy(url -> assertThat(url).endsWith("/medien.html"));
        assertThat(result.snapshots().pageCount()).isEqualTo(result.pagesVisited() + result.pagesFailed());
        assertThat(result.partialCoverage()).isFalse();
        assertThat(result.budgetStopReason()).isNull();
    }

    @Test
    void theRobotsDisallowedPageIsNeverVisited() {
        CrawlResult result = crawl(RunScope.FULL, budget(50, 3, Duration.ofMinutes(3)), List.of());
        assertThat(result.coveredUrls()).noneSatisfy(url -> assertThat(url).contains("/geheim/"));
    }

    @Test
    void assetsAndOffSiteLinksNeverEnterTheFrontier() {
        crawl(RunScope.FULL, budget(50, 3, Duration.ofMinutes(3)), List.of());
        List<String> queued = jdbc.queryForList(
                "SELECT url FROM crawl_queue_item WHERE run_id = ?", String.class, runId);
        assertThat(queued).noneSatisfy(url -> assertThat(url).endsWith(".pdf"));
        assertThat(queued).allSatisfy(url -> assertThat(url).contains("127.0.0.1"));
    }

    @Test
    void theSoftNotFoundProbeLearnsTheSitesNotFoundPage() {
        CrawlResult result = crawl(RunScope.FULL, budget(50, 3, Duration.ofMinutes(3)), List.of());

        SoftNotFoundProbe probe = result.snapshots().softNotFound();
        assertThat(probe.usable()).isTrue();
        assertThat(probe.httpStatus()).isEqualTo(200);        // the fixture is a soft-404 site
        assertThat(probe.simhash()).isNotZero();
        // …and a page that IS the not-found page fingerprints close to it.
        PageSnapshot verirrt = result.snapshots().snapshots().stream()
                .filter(s -> s.url().path().equals("/verirrt.html")).findFirst().orElseThrow();
        assertThat(SimHash.hammingDistance(verirrt.textSimhash(), probe.simhash()))
                .isLessThanOrEqualTo(6);
    }

    @Test
    void aPageBudgetStopsTheRunWithPartialCoverageAndResolvesNothingItDidNotReach() {
        CrawlResult result = crawl(RunScope.FULL, budget(2, 3, Duration.ofMinutes(3)), List.of());

        assertThat(result.pagesVisited()).isLessThanOrEqualTo(2);
        assertThat(result.coveredUrls()).hasSizeLessThanOrEqualTo(2);
        assertThat(result.partialCoverage()).isTrue();
        assertThat(result.budgetStopReason()).isEqualTo("maxPages");
        assertThat(frontier.countPending(runId)).isPositive();
    }

    @Test
    void maxDepthTruncatesDiscoveryWithoutBeingABudgetStop() {
        CrawlResult result = crawl(RunScope.FULL, budget(50, 0, Duration.ofMinutes(3)), List.of());

        assertThat(result.coveredUrls()).hasSize(1);          // the start page only
        assertThat(result.budgetStopReason()).isNull();       // the frontier simply ran dry
        assertThat(result.partialCoverage()).isFalse();
    }

    @Test
    void aPulseScopeCrawlsOnlyThePinnedKeyPages() {
        CrawlResult result = crawl(RunScope.PULSE, budget(50, 3, Duration.ofMinutes(3)),
                List.of("/kontakt.html"));

        assertThat(result.coveredUrls()).singleElement()
                .satisfies(url -> assertThat(url).endsWith("/kontakt.html"));
    }

    @Test
    void anUnreachablePageIsCountedAsFailedAndDoesNotKillTheRun() {
        // /langsam is linked from nowhere, so seed it directly through a pinned PULSE run.
        CrawlResult result = crawl(RunScope.PULSE, budget(50, 3, Duration.ofSeconds(60)),
                List.of("/langsam"));

        assertThat(result.pagesFailed()).isEqualTo(1);
        assertThat(result.pagesVisited()).isZero();
        assertThat(result.coveredUrls()).isEmpty();           // never reached, never covered
        assertThat(result.snapshots().pageCount()).isEqualTo(1);
    }

    @Test
    void progressIsReportedDuringTheCrawlNotOnlyAtTheEnd() {
        List<Integer> reports = Collections.synchronizedList(new ArrayList<>());
        crawler.crawl(request(RunScope.FULL, budget(50, 3, Duration.ofMinutes(3)), List.of()),
                (visited, failed) -> reports.add(visited));
        assertThat(reports).isNotEmpty().isSorted();
    }
}