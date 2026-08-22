package dev.hendrikhoemberg.webtesthelper.checks;

import dev.hendrikhoemberg.webtesthelper.catalog.SiteForm;
import dev.hendrikhoemberg.webtesthelper.catalog.SiteService;
import dev.hendrikhoemberg.webtesthelper.crawler.CrawlRequest;
import dev.hendrikhoemberg.webtesthelper.crawler.CrawlResult;
import dev.hendrikhoemberg.webtesthelper.crawler.CrawlService;
import dev.hendrikhoemberg.webtesthelper.model.CheckFinding;
import dev.hendrikhoemberg.webtesthelper.model.CheckSetting;
import dev.hendrikhoemberg.webtesthelper.model.CheckType;
import dev.hendrikhoemberg.webtesthelper.model.RunFacts;
import dev.hendrikhoemberg.webtesthelper.model.RunScope;
import dev.hendrikhoemberg.webtesthelper.model.SiteContext;
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
import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Spec 15: every check is developed and regression-tested against the fixture site, with a real
 * Chromium. The crawl runs <em>once</em> and the snapshots are then evaluated twice under
 * different configuration — which is the "navigate once, check many" claim of spec 5.2 in
 * literal form.
 */
@Tag("browser")
class PageCheckAcceptanceTest extends AbstractPostgresTest {

    @Autowired CrawlService crawler;
    @Autowired CheckEngine engine;
    @Autowired SiteService sites;
    @Autowired JdbcTemplate jdbc;

    private static FixtureSite site;

    private SiteContext context;
    private CrawlResult result;
    private List<CheckFinding> findings;

    @BeforeAll static void startSite() { site = FixtureSite.start(); }
    @AfterAll static void stopSite() { site.close(); }

    @BeforeEach
    void crawlOnce() {
        jdbc.update("DELETE FROM crawl_queue_item");
        jdbc.update("DELETE FROM run");
        jdbc.update("DELETE FROM site_check_setting");
        jdbc.update("DELETE FROM site");
        long siteId = sites.create(new SiteForm("Fixture", site.baseUrl(), 30, 3,
                Duration.ofMinutes(3), List.of(), List.of(), true, null));
        long runId = jdbc.queryForObject("INSERT INTO run (site_id, trigger_type, scope, status) "
                + "VALUES (?, 'MANUAL', 'FULL', 'RUNNING') RETURNING id", Long.class, siteId);

        context = sites.contextFor(siteId);
        result = crawler.crawl(new CrawlRequest(runId, context, RunScope.FULL, "test-worker"),
                (visited, failed) -> { });
        findings = engine.evaluateRun(result.snapshots(), context,
                RunFacts.of(result.snapshots(), RunScope.FULL, Instant.now()));
    }

    private List<CheckFinding> of(CheckType type) {
        return findings.stream().filter(finding -> finding.type() == type).toList();
    }

    @Test
    void thePageThatPretendsToExistIsReportedAsASoftNotFound() {
        assertThat(of(CheckType.PAGE_STATUS))
                .filteredOn(finding -> finding.messageKey().endsWith(".soft404"))
                .extracting(finding -> finding.observedOn().path())
                .contains("/verirrt.html");
    }

    @Test
    void theHardNotFoundPageIsReportedWithItsStatusCode() {
        assertThat(of(CheckType.PAGE_STATUS))
                .filteredOn(finding -> finding.observedOn().path().equals("/hart-404"))
                .singleElement()
                .satisfies(finding -> assertThat(finding.messageArgs()).containsExactly("404"));
    }

    @Test
    void noRealPageOfTheFixtureIsMistakenForTheNotFoundPage() {
        // The measured margin: the closest real page sits at 27, the cutoff at 16. If this ever
        // fails, re-measure before touching the cutoff.
        //
        // Exactly two pages are soft 404s. /verirrt.html is linked from the start page, and
        // /nicht-vorhanden.html is listed in the fixture's sitemap.xml — both paths the fixture
        // answers 200 for with its not-found body, which is what makes them soft 404s.
        assertThat(of(CheckType.PAGE_STATUS))
                .filteredOn(finding -> finding.messageKey().endsWith(".soft404"))
                .extracting(finding -> finding.observedOn().path())
                .containsExactlyInAnyOrder("/verirrt.html", "/nicht-vorhanden.html");
    }

    @Test
    void theRedirectLoopIsReportedAsALoopAndNotAlsoAsAnUnreachablePage() {
        assertThat(of(CheckType.REDIRECT_CHAIN))
                .filteredOn(finding -> finding.messageKey().endsWith(".loop"))
                .isNotEmpty();
        assertThat(of(CheckType.PAGE_UNREACHABLE))
                .extracting(finding -> finding.observedOn().path())
                .doesNotContain("/schleife/a");
    }

    @Test
    void theMissingFooterImageIsReportedOnEveryPageThatShowsIt() {
        // The same subject on many pages is what Plan 4 promotes to a site-wide finding
        // (spec 6.2). Here it must simply be reported once per page, with the same subject.
        assertThat(of(CheckType.IMAGE_BROKEN))
                .filteredOn(finding -> finding.subjectKey().endsWith("/assets/fehlt.png"))
                .hasSizeGreaterThanOrEqualTo(4);
    }

    @Test
    void theVideoWithABrokenSourceIsReportedAndTheWorkingAudioIsNot() {
        assertThat(of(CheckType.MEDIA_PLAYABLE)).singleElement().satisfies(finding -> {
            assertThat(finding.observedOn().path()).isEqualTo("/medien.html");
            assertThat(finding.subjectKey()).endsWith("/medien/fehlt.mp4");
            assertThat(finding.messageKey()).isEqualTo("finding.MEDIA_PLAYABLE.video");
        });
    }

    @Test
    void bothBrokenEmbedsOnTheContactPageAreReported() {
        assertThat(of(CheckType.IFRAME_EMBED))
                .extracting(CheckFinding::messageKey)
                .containsExactlyInAnyOrder("finding.IFRAME_EMBED.blocked",
                        "finding.IFRAME_EMBED.maps");
        assertThat(of(CheckType.IFRAME_EMBED))
                .allSatisfy(finding ->
                        assertThat(finding.observedOn().path()).isEqualTo("/kontakt.html"));
    }

    @Test
    void theCheckThatShipsDisabledReportsNothingEvenThoughItHadSomethingToSay() {
        // Spec 7.1: enabled by default this would make the first report mostly noise. The
        // fixture does log console errors, so this asserts the switch, not an empty console.
        // SITEMAP_CONSISTENCY, the other check that ships disabled, arrives in Plan 3b.
        assertThat(result.snapshots().snapshots())
                .anySatisfy(snapshot -> assertThat(snapshot.errors()).isNotEmpty());
        assertThat(of(CheckType.CONSOLE_ERRORS)).isEmpty();
    }

    @Test
    void aPlainHttpSiteHasNoMixedContent() {
        // Deviation D6: the fixture is served over http, so this check has nothing to say here.
        // Its positive case lives in MixedContentCheckTest, on a hand-built snapshot.
        assertThat(of(CheckType.MIXED_CONTENT)).isEmpty();
    }

    @Test
    void theSameSnapshotsUnderAStricterHopLimitReportTheRedirectChain() {
        // Navigate once, check many (spec 5.2): no second crawl, only a second evaluation.
        Map<CheckType, CheckSetting> stricter = new EnumMap<>(context.checkSettings());
        stricter.put(CheckType.REDIRECT_CHAIN,
                new CheckSetting(true, null, Map.of("maxHops", 2)));
        SiteContext strictSite = new SiteContext(context.siteId(), context.name(),
                context.baseUrl(), context.budget(), context.includePatterns(),
                context.excludePatterns(), context.pinnedKeyPages(), context.respectRobots(),
                context.userAgent(), stricter);

        List<CheckFinding> stricterFindings = engine.evaluateRun(result.snapshots(), strictSite,
                RunFacts.of(result.snapshots(), RunScope.FULL, Instant.now()));

        assertThat(stricterFindings)
                .filteredOn(finding -> finding.messageKey().endsWith(".tooManyHops"))
                .singleElement()
                .satisfies(finding -> {
                    assertThat(finding.subjectKey()).endsWith("/weiter/1");
                    assertThat(finding.messageArgs().getFirst()).isEqualTo("3");
                });
    }
}