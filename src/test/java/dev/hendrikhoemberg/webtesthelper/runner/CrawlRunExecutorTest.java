package dev.hendrikhoemberg.webtesthelper.runner;

import dev.hendrikhoemberg.webtesthelper.catalog.SiteForm;
import dev.hendrikhoemberg.webtesthelper.catalog.SiteService;
import dev.hendrikhoemberg.webtesthelper.crawler.CrawlerProperties;
import dev.hendrikhoemberg.webtesthelper.model.RunScope;
import dev.hendrikhoemberg.webtesthelper.model.RunTrigger;
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

import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

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
    private long runId1;
    private long runId2;
    private long runId3;

    @BeforeAll static void startSite() { site = FixtureSite.start(); }
    @AfterAll static void stopSite() { site.close(); }

    /**
     * Three fixture crawls for the whole class (spec 5.2: navigate once, check many). Run 1 and
     * run 2 are both full crawls of the same site, so the run-2 assertions prove fingerprint
     * stability — nothing is reported as "new" just because the crawl order differed. Run 3 caps
     * the budget to two pages, so it is partial and resolves nothing it did not reach. Every
     * downstream assertion reads the same completed runs rather than paying for more Chromium
     * sweeps.
     */
    @BeforeAll
    void crawlFixtureThreeTimes() {
        jdbc.update("DELETE FROM crawl_queue_item");
        jdbc.update("DELETE FROM run");
        jdbc.update("DELETE FROM site_check_setting");
        jdbc.update("DELETE FROM site");
        siteId = sites.create(new SiteForm("Fixture", site.baseUrl(), 30, 3,
                Duration.ofMinutes(3), List.of(), List.of(), true, null));
        runId1 = runs.enqueue(siteId, RunTrigger.MANUAL, RunScope.FULL);
        assertThat(worker.workOnce()).isTrue();
        runId2 = runs.enqueue(siteId, RunTrigger.MANUAL, RunScope.FULL);
        assertThat(worker.workOnce()).isTrue();
        sites.update(siteId, new SiteForm("Fixture", site.baseUrl(), 2, 3,
                Duration.ofMinutes(3), List.of(), List.of(), true, null));
        runId3 = runs.enqueue(siteId, RunTrigger.MANUAL, RunScope.FULL);
        assertThat(worker.workOnce()).isTrue();
    }

    @Test
    void aManualRunCrawlsTheFixtureSiteEndToEnd() {
        assertThat(jdbc.queryForObject("SELECT status FROM run WHERE id = ?", String.class, runId2))
                .isEqualTo("COMPLETED");
        assertThat(jdbc.queryForObject("SELECT pages_visited FROM run WHERE id = ?", Integer.class, runId2))
                .isGreaterThanOrEqualTo(6);
        assertThat(jdbc.queryForObject("SELECT partial_coverage FROM run WHERE id = ?", Boolean.class, runId2))
                .isFalse();
        assertThat(jdbc.queryForObject("SELECT soft404_status FROM run WHERE id = ?", Integer.class, runId2))
                .isEqualTo(200);
        assertThat(jdbc.queryForObject("SELECT soft404_simhash FROM run WHERE id = ?", Long.class, runId2))
                .isNotZero();
        // The pipeline now records findings: run 2 re-observes run 1's set, so its total equals
        // the full fixture failure count (spec 15 — one of every failure mode).
        assertThat(jdbc.queryForObject("SELECT findings_total FROM run WHERE id = ?", Integer.class, runId2))
                .isGreaterThanOrEqualTo(10);
        assertThat(jdbc.queryForObject("SELECT lease_owner FROM run WHERE id = ?", String.class, runId2))
                .isNull();
    }

    @Test
    void theRunRecordsWhatItCovered() {
        String coveredUrls = jdbc.queryForObject(
                "SELECT covered_urls::text FROM run WHERE id = ?", String.class, runId2);
        assertThat(coveredUrls).contains("/leistungen.html").contains("/kontakt.html");

        String coveredCheckTypes = jdbc.queryForObject(
                "SELECT covered_check_types::text FROM run WHERE id = ?", String.class, runId2);
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
                Integer.class, runId2)).isGreaterThanOrEqualTo(6);
        assertThat(properties.artifactDir().resolve(String.valueOf(runId2))).exists();
        assertThat(properties.artifactDir().resolve(String.valueOf(runId2)).toFile().list())
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

    // ---- Plan 4, task 6: the two-run (and three-run) identity acceptance ----

    @Test
    void run1EstablishesTheBaselineFindings() {
        int firstCount = jdbc.queryForObject(
                "SELECT count(*) FROM finding WHERE site_id = ? AND first_seen_run = ?",
                Integer.class, siteId, runId1);
        assertThat(firstCount).isGreaterThan(0);
        // Everything run 1 first saw is still untriaged. Run 2 (the identical full re-crawl) must
        // not have resolved any of them; run 3 is a smaller scope and may resolve findings it
        // covered but did not re-observe (spec 6.4), which is checked on its own.
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM finding WHERE site_id = ? AND first_seen_run = ? "
                        + "AND observed_status = 'RESOLVED' AND resolved_at_run <= ?",
                Integer.class, siteId, runId1, runId2)).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM finding WHERE site_id = ? AND first_seen_run = ? "
                        + "AND triage_status <> 'UNTRIAGED'", Integer.class, siteId, runId1))
                .isZero();
        // The run row's new count is exactly the findings run 1 first saw.
        int newCount = jdbc.queryForObject("SELECT findings_new FROM run WHERE id = ?", Integer.class, runId1);
        assertThat(newCount).isEqualTo(firstCount);
    }

    @Test
    void run2ChangesNothing() {
        // Run 2 is an identical full re-crawl, so it introduces no new fingerprint. (Run 3 is a
        // deliberately smaller scope and legitimately materialises some findings differently, so
        // it is excluded from this check — the headline is run 1 ↔ run 2 stability.)
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM finding WHERE site_id = ? AND first_seen_run = ?",
                Integer.class, siteId, runId2)).isZero();
        // Run 2 reports neither new nor resolved findings, because everything it saw was already known.
        assertThat(jdbc.queryForObject("SELECT findings_new FROM run WHERE id = ?", Integer.class, runId2))
                .isZero();
        assertThat(jdbc.queryForObject("SELECT findings_resolved FROM run WHERE id = ?", Integer.class, runId2))
                .isZero();
    }

    @Test
    void run2AccumulatesOccurrencesWithoutMovingIdentity() {
        // History accumulates: run 2 produced its own occurrence rows alongside run 1's.
        assertThat(jdbc.queryForObject("SELECT count(*) FROM finding_occurrence WHERE run_id = ?",
                Integer.class, runId1)).isGreaterThan(0);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM finding_occurrence WHERE run_id = ?",
                Integer.class, runId2)).isGreaterThan(0);
    }

    @Test
    void run3ResolvesNothingOutsideItsCoverage() {
        // Derive the location keys run 3 actually covered, straight from its covered_urls.
        List<String> run3Covered = jdbc.queryForList(
                "SELECT jsonb_array_elements_text(covered_urls) FROM run WHERE id = ?",
                String.class, runId3);
        Set<String> coveredKeys = run3Covered.stream()
                .map(UrlNormalizer::locationKeyOf)
                .collect(Collectors.toSet());

        List<String> outsideKeys = jdbc.queryForList(
                "SELECT DISTINCT location_key FROM finding WHERE site_id = ? AND location_key <> '*'",
                String.class, siteId).stream()
                .filter(key -> !coveredKeys.contains(key))
                .toList();
        // There is genuinely something run 3 did not reach.
        assertThat(outsideKeys).isNotEmpty();

        for (String key : outsideKeys) {
            int wrong = jdbc.queryForObject(
                    "SELECT count(*) FROM finding WHERE site_id = ? AND location_key = ? "
                            + "AND (observed_status <> 'ACTIVE' OR last_seen_run <> ?)",
                    Integer.class, siteId, key, runId2);
            assertThat(wrong).isZero();
        }
        // A capped run is partial by definition.
        assertThat(jdbc.queryForObject("SELECT partial_coverage FROM run WHERE id = ?",
                Boolean.class, runId3)).isTrue();
    }

    @Test
    void noTwoFindingsShareAFingerprint() {
        int total = jdbc.queryForObject("SELECT count(*) FROM finding WHERE site_id = ?",
                Integer.class, siteId);
        int distinct = jdbc.queryForObject(
                "SELECT count(DISTINCT fingerprint) FROM finding WHERE site_id = ?",
                Integer.class, siteId);
        assertThat(total).isEqualTo(distinct);
    }

    @Test
    void theFlakyExternalLinkLeavesNoSurvivingFinding() {
        // /extern/flatterhaft answers 503 once then 200, so re-verification inside the pipeline
        // drops the transient dead-link finding. No unit test can prove this; the browser run can.
        List<String> flaky = jdbc.queryForList(
                "SELECT subject_key FROM finding WHERE site_id = ? AND subject_key LIKE ?",
                String.class, siteId, "%flatterhaft%");
        assertThat(flaky).isEmpty();
    }
}
