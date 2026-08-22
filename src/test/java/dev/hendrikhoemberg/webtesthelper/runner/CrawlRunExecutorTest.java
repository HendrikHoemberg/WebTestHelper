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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("browser")
class CrawlRunExecutorTest extends AbstractPostgresTest {

    @Autowired RunWorker worker;
    @Autowired RunService runs;
    @Autowired SiteService sites;
    @Autowired JdbcTemplate jdbc;
    @Autowired CrawlerProperties properties;

    private static FixtureSite site;
    private long siteId;

    @BeforeAll static void startSite() { site = FixtureSite.start(); }
    @AfterAll static void stopSite() { site.close(); }

    @BeforeEach
    void freshSite() {
        jdbc.update("DELETE FROM crawl_queue_item");
        jdbc.update("DELETE FROM run");
        jdbc.update("DELETE FROM site_check_setting");
        jdbc.update("DELETE FROM site");
        siteId = sites.create(new SiteForm("Fixture", site.baseUrl(), 30, 3,
                Duration.ofMinutes(3), List.of(), List.of(), true, null));
    }

    @Test
    void aManualRunCrawlsTheFixtureSiteEndToEnd() {
        long runId = runs.enqueue(siteId, RunTrigger.MANUAL, RunScope.FULL);

        assertThat(worker.workOnce()).isTrue();

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
        // The check pass ran: the fixture contains one of every failure mode (spec 15), so a
        // run that found nothing means the checks were never invoked.
        assertThat(jdbc.queryForObject("SELECT findings_total FROM run WHERE id = ?", Integer.class, runId))
                .isGreaterThan(0);
        assertThat(jdbc.queryForObject("SELECT lease_owner FROM run WHERE id = ?", String.class, runId))
                .isNull();
    }

    @Test
    void theRunRecordsWhatItCovered() {
        long runId = runs.enqueue(siteId, RunTrigger.MANUAL, RunScope.FULL);
        worker.workOnce();

        String coveredUrls = jdbc.queryForObject(
                "SELECT covered_urls::text FROM run WHERE id = ?", String.class, runId);
        assertThat(coveredUrls).contains("/leistungen.html").contains("/kontakt.html");

        String coveredCheckTypes = jdbc.queryForObject(
                "SELECT covered_check_types::text FROM run WHERE id = ?", String.class, runId);
        assertThat(coveredCheckTypes).contains("PAGE_STATUS");
        // DEAD_LINK has no implementation until Plan 3b, so a run must not claim it covered.
        assertThat(coveredCheckTypes).doesNotContain("DEAD_LINK");
        // Spec 7.1: these two ship disabled, so a run does not claim to have covered them.
        assertThat(coveredCheckTypes).doesNotContain("CONSOLE_ERRORS")
                .doesNotContain("SITEMAP_CONSISTENCY");
    }

    @Test
    void theFrontierAndTheArtifactsSurviveTheRun() {
        long runId = runs.enqueue(siteId, RunTrigger.MANUAL, RunScope.FULL);
        worker.workOnce();

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
        long runId = runs.enqueue(deadSiteId, RunTrigger.MANUAL, RunScope.FULL);

        assertThat(worker.workOnce()).isTrue();

        // The start page is unreachable, so the crawl completes having visited nothing.
        assertThat(jdbc.queryForObject("SELECT status FROM run WHERE id = ?", String.class, runId))
                .isEqualTo("COMPLETED");
        assertThat(jdbc.queryForObject("SELECT pages_visited FROM run WHERE id = ?", Integer.class, runId))
                .isZero();
        assertThat(jdbc.queryForObject("SELECT pages_failed FROM run WHERE id = ?", Integer.class, runId))
                .isEqualTo(1);
    }
}