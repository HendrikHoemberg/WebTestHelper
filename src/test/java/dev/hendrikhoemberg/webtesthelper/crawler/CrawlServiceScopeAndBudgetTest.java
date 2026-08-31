package dev.hendrikhoemberg.webtesthelper.crawler;

import dev.hendrikhoemberg.webtesthelper.crawler.persistence.CrawlFrontierJdbcRepository;
import dev.hendrikhoemberg.webtesthelper.model.CrawlBudget;
import dev.hendrikhoemberg.webtesthelper.model.RunScope;
import dev.hendrikhoemberg.webtesthelper.model.SiteContext;
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
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Cases that each need their own crawl because they vary the scope, the budget or the
 * frontier's starting state. The properties of one plain full crawl live in
 * {@link CrawlServiceFullCrawlTest}, which pays for that crawl once.
 */
@Tag("browser")
class CrawlServiceScopeAndBudgetTest extends AbstractPostgresTest {

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
    void aClaimAbandonedByADeadWorkerIsReclaimedAndCrawled() {
        // Spec 14: the frontier is a table so a run survives a worker dying mid-batch. Left
        // unreclaimed the row stays CLAIMED forever — never visited, and countPending() then
        // reports full coverage for a run that silently missed a page (spec 6.4).
        // /ziel.html is reachable only through the redirect chain, so discovery never enqueues
        // it: if it ends up covered, it was the reclaim that put it back.
        jdbc.update("INSERT INTO crawl_queue_item (run_id, url, depth, status, claimed_by, claimed_at) "
                        + "VALUES (?, ?, 1, 'CLAIMED', 'worker-tot', now() - interval '30 minutes')",
                runId, site.url("ziel.html"));

        CrawlResult result = crawl(RunScope.FULL, budget(50, 3, Duration.ofMinutes(3)), List.of());

        assertThat(result.coveredUrls()).anySatisfy(url -> assertThat(url).endsWith("/ziel.html"));
        assertThat(result.partialCoverage()).isFalse();
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
    void aPinnedPageThatRobotsDisallowsIsNotCrawled() {
        // Spec 8: robots is honoured by default, and pinning a page is not the supported way to
        // override it — respectRobots on the site is (deviation D9).
        CrawlResult result = crawl(RunScope.PULSE, budget(50, 3, Duration.ofMinutes(3)),
                List.of("/geheim/intern.html", "/kontakt.html"));

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
    void aTransientNavigationFailureIsRetriedAndRecovered() {
        // /wackeliges-netz.html answers request 1 past the 5s navigation timeout (transient
        // transport failure) and request 2 normally. One retry must turn the page reachable —
        // an unreachable snapshot would otherwise seed dead-link findings across the site.
        CrawlResult result = crawl(RunScope.PULSE, budget(50, 3, Duration.ofSeconds(60)),
                List.of("/wackeliges-netz.html"));

        assertThat(result.pagesFailed()).isZero();
        assertThat(result.pagesVisited()).isEqualTo(1);
        assertThat(result.coveredUrls()).singleElement()
                .satisfies(url -> assertThat(url).endsWith("/wackeliges-netz.html"));
        assertThat(result.snapshots().snapshots()).singleElement()
                .satisfies(s -> assertThat(s.reachable()).isTrue());
        assertThat(site.requestCount("/wackeliges-netz.html")).isEqualTo(2);
    }

    @Test
    void theSnapshotListIsBoundedEvenWhenEveryPageFails() {
        // visited counts only reachable pages, so a run whose pages all fail would otherwise
        // accumulate snapshots past maxPages (p2b's last open carry-over). The loop guard and the
        // room calculation both bound the snapshot list, so three unreachable pages under
        // maxPages=2 yield at most two snapshots.
        CrawlResult result = crawl(RunScope.PULSE, budget(2, 3, Duration.ofSeconds(60)),
                List.of("/langsam", "/langsam?q=1", "/langsam?q=2"));

        assertThat(result.pagesVisited()).isZero();
        assertThat(result.snapshots().pageCount()).isLessThanOrEqualTo(2);
    }

}