package dev.hendrikhoemberg.webtesthelper.runner;

import dev.hendrikhoemberg.webtesthelper.catalog.SiteForm;
import dev.hendrikhoemberg.webtesthelper.catalog.SiteService;
import dev.hendrikhoemberg.webtesthelper.crawler.CrawlerProperties;
import dev.hendrikhoemberg.webtesthelper.model.RunScope;
import dev.hendrikhoemberg.webtesthelper.model.RunTrigger;
import dev.hendrikhoemberg.webtesthelper.support.AbstractPostgresTest;
import dev.hendrikhoemberg.webtesthelper.support.FixtureSite;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("browser")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CrawlRunExecutorTest extends AbstractPostgresTest {

    @Autowired RunWorker worker;
    @Autowired RunService runs;
    @Autowired SiteService sites;
    @Autowired JdbcTemplate jdbc;
    @Autowired CrawlerProperties properties;

    private static FixtureSite site;
    private long siteId;
    private long runId;

    @BeforeAll static void startSite() { site = FixtureSite.start(); }
    @AfterAll static void stopSite() { site.close(); }

    /**
     * One fixture crawl for the whole class (spec 5.2: navigate once, check many). The pipeline
     * now also verifies URLs and runs the site checks, so every downstream assertion reads the
     * same completed run rather than paying for four Chromium sweeps.
     */
    @BeforeAll
    void crawlFixtureOnce() {
        jdbc.update("DELETE FROM crawl_queue_item");
        jdbc.update("DELETE FROM run");
        jdbc.update("DELETE FROM site_check_setting");
        jdbc.update("DELETE FROM site");
        siteId = sites.create(new SiteForm("Fixture", site.baseUrl(), 30, 3,
                Duration.ofMinutes(3), List.of(), List.of(), true, null));
        runId = runs.enqueue(siteId, RunTrigger.MANUAL, RunScope.FULL);
        assertThat(worker.workOnce()).isTrue();
    }

    @Test
    void aManualRunCrawlsTheFixtureSiteEndToEnd() {
        assertThat(jdbc.queryForObject("SELECT status FROM run WHERE id = ?", String.class, runId))
                .isEqualTo("COMPLETED");
        assertThat(jdbc.queryForObject("SELECT pages_visited FROM run WHERE id = ?", Integer.class, runId))
                .isGreaterThanOrEqualTo(6);
        assertThat(jdbc.queryForObject("SELECT partial_coverage FROM run WHERE id = ?", Boolean.class, runId))
                .isFalse();
        assertThat(jdbc.queryForObject("SELECT soft404_status FROM run WHERE id = ?", Integer.class, runId))
                .isEqualTo(200);
        assertThat(jdbc.queryForObject("SELECT soft404_simhash FROM run WHERE id = ?", Long.class, runId))
                .isNotZero();
        // The check pass ran and the verification too: the fixture contains one of every failure
        // mode (spec 15), so a run that found nothing means the checks were never invoked.
        assertThat(jdbc.queryForObject("SELECT findings_total FROM run WHERE id = ?", Integer.class, runId))
                .isGreaterThanOrEqualTo(10);
        assertThat(jdbc.queryForObject("SELECT lease_owner FROM run WHERE id = ?", String.class, runId))
                .isNull();
    }

    @Test
    void theRunRecordsWhatItCovered() {
        String coveredUrls = jdbc.queryForObject(
                "SELECT covered_urls::text FROM run WHERE id = ?", String.class, runId);
        assertThat(coveredUrls).contains("/leistungen.html").contains("/kontakt.html");

        String coveredCheckTypes = jdbc.queryForObject(
                "SELECT covered_check_types::text FROM run WHERE id = ?", String.class, runId);
        // A run's coverage must claim exactly the checks it actually evaluated.
        assertThat(coveredCheckTypes).contains("PAGE_STATUS", "DEAD_LINK", "FILE_DOWNLOAD",
                "TLS_CERT", "HREFLANG");
        // Spec 7.1: these two ship disabled, so a run does not claim to have covered them.
        assertThat(coveredCheckTypes).doesNotContain("CONSOLE_ERRORS")
                .doesNotContain("SITEMAP_CONSISTENCY");
    }

    @Test
    void theExternalUrlCheckCacheHoldsTheExternalPartnerButNoInternalUrl() {
        String externOk = site.externalBase() + "extern/ok";
        List<String> cached = jdbc.queryForList("SELECT url FROM external_url_check", String.class);
        assertThat(cached).contains(externOk);
        assertThat(cached).noneSatisfy(url -> assertThat(url).contains("127.0.0.1"));
    }

    @Test
    void theFrontierAndTheArtifactsSurviveTheRun() {
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM crawl_queue_item WHERE run_id = ? AND status = 'DONE'",
                Integer.class, runId)).isGreaterThanOrEqualTo(6);
        assertThat(properties.artifactDir().resolve(String.valueOf(runId))).exists();
        assertThat(properties.artifactDir().resolve(String.valueOf(runId)).toFile().list())
                .isNotEmpty();
    }

    @Test
    void aSiteThatCannotBeReachedFailsTheRunRatherThanHangingIt() {
        long deadSiteId = sites.create(new SiteForm("Tot", "http://localhost:9/", 10, 2,
                Duration.ofSeconds(30), List.of(), List.of(), true, null));
        long deadRunId = runs.enqueue(deadSiteId, RunTrigger.MANUAL, RunScope.FULL);

        assertThat(worker.workOnce()).isTrue();

        // The start page is unreachable, so the crawl completes having visited nothing.
        assertThat(jdbc.queryForObject("SELECT status FROM run WHERE id = ?", String.class, deadRunId))
                .isEqualTo("COMPLETED");
        assertThat(jdbc.queryForObject("SELECT pages_visited FROM run WHERE id = ?", Integer.class, deadRunId))
                .isZero();
        assertThat(jdbc.queryForObject("SELECT pages_failed FROM run WHERE id = ?", Integer.class, deadRunId))
                .isEqualTo(1);
    }
}
