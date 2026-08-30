package dev.hendrikhoemberg.webtesthelper.crawler;

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
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.File;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The properties of one unconstrained crawl of the fixture site. Every test here reads the
 * same {@link CrawlResult}; cases that vary the scope, the budget or the frontier's starting
 * state need their own crawl and live in {@link CrawlServiceScopeAndBudgetTest}.
 *
 * <p>Clearing the tables in {@code @BeforeAll} rather than {@code @BeforeEach} departs from
 * {@link AbstractPostgresTest}'s default contract. It is safe because surefire runs test
 * classes sequentially in one JVM, so no other class interleaves with this one.
 */
@Tag("browser")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CrawlServiceFullCrawlTest extends AbstractPostgresTest {

    @Autowired CrawlService crawler;
    @Autowired JdbcTemplate jdbc;
    @Autowired CrawlerProperties properties;

    private FixtureSite site;
    private long runId;
    private CrawlResult result;
    private final List<Integer> progressReports = Collections.synchronizedList(new ArrayList<>());

    @AfterAll void stopSite() { site.close(); }

    /**
     * One crawl for the whole class. Five tests once drove this same crawl themselves, at
     * 6.2s each, to assert five different properties of an identical result.
     */
    @BeforeAll
    void crawlOnce() {
        site = FixtureSite.start();
        jdbc.update("DELETE FROM crawl_queue_item");
        jdbc.update("DELETE FROM run");
        jdbc.update("DELETE FROM site");
        long siteId = jdbc.queryForObject("INSERT INTO site (name, base_url) VALUES (?, ?) RETURNING id",
                Long.class, "Fixture", site.baseUrl());
        runId = jdbc.queryForObject("INSERT INTO run (site_id, trigger_type, scope, status) "
                + "VALUES (?, 'MANUAL', 'FULL', 'RUNNING') RETURNING id", Long.class, siteId);

        SiteContext context = new SiteContext(siteId, "Fixture",
                UrlNormalizer.normalize(site.baseUrl()).orElseThrow(),
                new CrawlBudget(50, 3, Duration.ofMinutes(3)),
                List.of(), List.of(), List.of(), true, null, Map.of());
        deleteRunArtifacts(runId);
        result = crawler.crawl(new CrawlRequest(runId, context, RunScope.FULL, "test-worker"),
                (visited, failed) -> progressReports.add(visited));
    }

    /** The shared /tmp artifact dir survives across runs (run ids restart per fresh container), so
     *  each class starts from a clean slate before asserting the per-snapshot artifact count. */
    private void deleteRunArtifacts(long id) {
        Path dir = properties.artifactDir().resolve(String.valueOf(id));
        try (var paths = java.nio.file.Files.walk(dir)) {
            paths.sorted(Comparator.reverseOrder()).map(java.nio.file.Path::toFile)
                    .forEach(java.io.File::delete);
        } catch (java.io.IOException ignored) {
        }
    }

    @Test
    void aFullCrawlReachesEveryCrawlablePageExactlyOnce() {
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
        assertThat(result.coveredUrls()).noneSatisfy(url -> assertThat(url).contains("/geheim/"));
    }

    @Test
    void assetsAndOffSiteLinksNeverEnterTheFrontier() {
        List<String> queued = jdbc.queryForList(
                "SELECT url FROM crawl_queue_item WHERE run_id = ?", String.class, runId);
        assertThat(queued).noneSatisfy(url -> assertThat(url).endsWith(".pdf"));
        assertThat(queued).allSatisfy(url -> assertThat(url).contains("127.0.0.1"));
    }

    @Test
    void theSoftNotFoundProbeLearnsTheSitesNotFoundPage() {
        SoftNotFoundProbe probe = result.snapshots().softNotFound();
        assertThat(probe.usable()).isTrue();
        assertThat(probe.httpStatus()).isEqualTo(200);        // the fixture is a soft-404 site
        assertThat(probe.simhash()).isNotZero();
        // A page that IS the not-found page fingerprints close to it.
        PageSnapshot verirrt = result.snapshots().snapshots().stream()
                .filter(s -> s.url().path().equals("/verirrt.html")).findFirst().orElseThrow();
        assertThat(SimHash.hammingDistance(verirrt.textSimhash(), probe.simhash()))
                .isLessThanOrEqualTo(6);
        // The root page is the known-real anchor for the two-anchor soft-404 rule.
        assertThat(probe.referenceUsable()).isTrue();
        PageSnapshot home = result.snapshots().snapshots().stream()
                .filter(s -> s.url().value().equals(site.baseUrl())).findFirst().orElseThrow();
        assertThat(probe.referenceSimhash()).isEqualTo(home.textSimhash());
    }

    @Test
    void progressIsReportedDuringTheCrawlNotOnlyAtTheEnd() {
        assertThat(progressReports).isNotEmpty().isSorted();
    }

    @Test
    void theVerificationCandidatesAreTheUnvisitedVerifiableLinks() {
        List<String> candidates = result.verificationCandidates();
        assertThat(candidates).doesNotHaveDuplicates();
        assertThat(candidates).anySatisfy(url -> assertThat(url).contains("/dateien/handbuch.pdf"));
        assertThat(candidates).contains("http://localhost:9/tot");
        assertThat(candidates).noneSatisfy(url -> assertThat(url).contains("/geheim/"));
        assertThat(candidates).noneSatisfy(url -> assertThat(url).isIn(result.snapshots().visitedUrls()));
    }

    @Test
    void theSitemapUrlsAreTheDeclaredLocEntries() {
        assertThat(result.sitemapUrls()).doesNotHaveDuplicates();
        assertThat(result.sitemapUrls()).hasSize(4);
        assertThat(result.sitemapUrls())
                .anySatisfy(url -> assertThat(url).contains("/nicht-vorhanden.html"));
    }

    @Test
    void everyVisitedPageLeavesExactlyOneArtifactAndTheProbeScreenshotIsGone() {
        File[] artifacts = properties.artifactDir().resolve(String.valueOf(runId)).toFile().listFiles();
        assertThat(artifacts).isNotNull();
        long screenshots = result.snapshots().snapshots().stream()
                .filter(PageSnapshot::reachable).count();
        assertThat(artifacts).hasSize((int) screenshots);
    }
}
