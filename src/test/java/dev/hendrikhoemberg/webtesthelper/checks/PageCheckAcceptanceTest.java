package dev.hendrikhoemberg.webtesthelper.checks;

import dev.hendrikhoemberg.webtesthelper.catalog.SiteForm;
import dev.hendrikhoemberg.webtesthelper.catalog.SiteService;
import dev.hendrikhoemberg.webtesthelper.crawler.CrawlRequest;
import dev.hendrikhoemberg.webtesthelper.crawler.CrawlResult;
import dev.hendrikhoemberg.webtesthelper.crawler.CrawlService;
import dev.hendrikhoemberg.webtesthelper.crawler.TlsProbe;
import dev.hendrikhoemberg.webtesthelper.crawler.UrlVerificationService;
import dev.hendrikhoemberg.webtesthelper.model.CheckFinding;
import dev.hendrikhoemberg.webtesthelper.model.CheckSetting;
import dev.hendrikhoemberg.webtesthelper.model.CheckType;
import dev.hendrikhoemberg.webtesthelper.model.RunFacts;
import dev.hendrikhoemberg.webtesthelper.model.RunScope;
import dev.hendrikhoemberg.webtesthelper.model.Severity;
import dev.hendrikhoemberg.webtesthelper.model.SimHash;
import dev.hendrikhoemberg.webtesthelper.model.SiteContext;
import dev.hendrikhoemberg.webtesthelper.model.SoftNotFoundProbe;
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
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Spec 15: every check is developed and regression-tested against the fixture site, with a real
 * Chromium. The crawl runs <em>once</em> and the snapshots are then evaluated twice under
 * different configuration — which is the "navigate once, check many" claim of spec 5.2 in
 * literal form.
 */
@Tag("browser")
@org.junit.jupiter.api.parallel.ResourceLock("browser")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PageCheckAcceptanceTest extends AbstractPostgresTest {

    @Autowired CrawlService crawler;
    @Autowired CheckEngine engine;
    @Autowired SiteService sites;
    @Autowired UrlVerificationService verifier;
    @Autowired TlsProbe tlsProbe;
    @Autowired JdbcTemplate jdbc;

    private FixtureSite site;

    private SiteContext context;
    private CrawlResult result;
    private List<CheckFinding> findings;

    @AfterAll void stopSite() { site.close(); }

    /**
     * One crawl for the whole class. Every test below only reads {@code result} and
     * {@code findings}, so re-crawling per test would cost eleven Chromium sweeps to prove
     * the same snapshots — the exact opposite of the "navigate once, check many" claim in
     * the class javadoc.
     */
    @BeforeAll
    void crawlOnce() {
        site = FixtureSite.start();
        jdbc.update("DELETE FROM crawl_queue_item");
        jdbc.update("DELETE FROM run");
        jdbc.update("DELETE FROM site_check_setting");
        jdbc.update("DELETE FROM site");
        long siteId = sites.create(new SiteForm("Fixture", site.baseUrl(), 30, 3,
                Duration.ofMinutes(3), List.of(), List.of(), true, null, true));
        long runId = jdbc.queryForObject("INSERT INTO run (site_id, trigger_type, scope, status) "
                + "VALUES (?, 'MANUAL', 'FULL', 'RUNNING') RETURNING id", Long.class, siteId);

        context = sites.contextFor(siteId);
        result = crawler.crawl(new CrawlRequest(runId, context, RunScope.FULL, "test-worker"),
                (visited, failed) -> { });
        RunFacts facts = RunFacts.of(result.snapshots(), RunScope.FULL, Instant.now(),
                verifier.verify(context, result.snapshots(), result.verificationCandidates()),
                tlsProbe.probe(context.baseUrl()), result.sitemapUrls());
        findings = new ArrayList<>();
        findings.addAll(engine.evaluateRun(result.snapshots(), context, facts));
        findings.addAll(engine.evaluateSite(result.snapshots(), context, facts));
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
    void everyReachableStatusTwoHundredPageStaysWellClearOfTheSoftNotFoundProbe() {
        // A canary for the soft-404 margin: elsewhere the measured gap is "closest real page at
        // 27, cutoff at 16". Guarding half of it means drift that eats real pages trips this long
        // before it reaches the cutoff, instead of silently shrinking the two-page fixture oracle.
        SoftNotFoundProbe probe = result.snapshots().softNotFound();
        assertThat(probe.usable()).isTrue();
        assertThat(result.snapshots().snapshots())
                .filteredOn(snapshot -> snapshot.reachable() && snapshot.httpStatus() == 200
                        && !snapshot.url().path().equals("/verirrt.html")
                        && !snapshot.url().path().equals("/nicht-vorhanden.html"))
                .allSatisfy(snapshot -> assertThat(
                        SimHash.hammingDistance(snapshot.textSimhash(), probe.simhash()))
                        .isGreaterThan(20));
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
                .filteredOn(finding -> finding.observedOn().path().equals("/kontakt.html"))
                .extracting(CheckFinding::messageKey)
                .containsExactlyInAnyOrder("finding.IFRAME_EMBED.blocked",
                        "finding.IFRAME_EMBED.maps");
    }

    @Test
    void theGreyMapThatNeverPaintsIsReportedAndTheHealthyOneIsNot() {
        // Spec 7.1's grey-map case, with no provider console error — the one the console scan
        // misses. The paint signal is what carries it.
        assertThat(of(CheckType.IFRAME_EMBED))
                .filteredOn(finding -> finding.observedOn().path().equals("/karten-grau.html"))
                .isNotEmpty()
                .allMatch(finding -> finding.messageKey().equals("finding.IFRAME_EMBED.mapsNotPainted"));
        assertThat(of(CheckType.IFRAME_EMBED))
                .filteredOn(finding -> finding.observedOn().path().equals("/karten-gesund.html"))
                .isEmpty();
    }

    @Test
    void aHealthyMapThatPaintsLateIsNotReportedAsNotPainted() {
        // The single-shot probe would read the still-blank canvas and raise a false finding here.
        assertThat(of(CheckType.IFRAME_EMBED))
                .filteredOn(finding -> finding.observedOn().path().equals("/karten-spaet.html"))
                .isEmpty();
    }

    @Test
    void aPaintedMapWithATransparentOverlayAboveItIsNotReportedAsNotPainted() {
        assertThat(of(CheckType.IFRAME_EMBED))
                .filteredOn(finding -> finding.observedOn().path().equals("/karten-zwei.html"))
                .isEmpty();
    }

    @Test
    void aLazyImageBelowTheFoldIsNotReportedAsBroken() {
        // /faul.html has a loading="lazy" image below a 2000px spacer and the file exists —
        // IMAGE_BROKEN must stay silent on this page, without hiding a genuinely broken file.
        assertThat(result.snapshots().snapshots())
                .filteredOn(snapshot -> snapshot.url().path().equals("/faul.html"))
                .isNotEmpty();
        assertThat(of(CheckType.IMAGE_BROKEN))
                .filteredOn(f -> f.observedOn().path().equals("/faul.html"))
                .isEmpty();
    }

    @Test
    void aHeadLyingExternalLinkIsNotReportedAsDead() {
        // /extern/head-taeuscht: HEAD 404, GET 200. The crawl must verify it with the GET
        // fallback (exactly two fixture requests) and report nothing as DEAD_LINK.
        assertThat(site.requestCount("/extern/head-taeuscht")).isEqualTo(2);
        assertThat(of(CheckType.DEAD_LINK))
                .extracting(CheckFinding::subjectKey)
                .noneMatch(s -> s.contains("extern/head-taeuscht"));
    }

    @Test
    void aCloakWrapperDoesNotProduceADeadLinkOrPageStatus() {
        // /mantel.html has a cloak wrapper. The outer anchor's resolved URL must not appear
        // as a DEAD_LINK or PAGE_STATUS finding.
        assertThat(result.snapshots().snapshots())
                .filteredOn(snapshot -> snapshot.url().path().equals("/mantel.html"))
                .isNotEmpty();
        assertThat(of(CheckType.DEAD_LINK))
                .extracting(CheckFinding::subjectKey)
                .noneMatch(s -> s.contains("kontakt@example.com"));
        assertThat(of(CheckType.PAGE_STATUS))
                .filteredOn(f -> f.observedOn().path().contains("kontakt@example.com"))
                .isEmpty();
    }

    @Test
    void theCheckThatShipsDisabledReportsNothingEvenThoughItHadSomethingToSay() {
        // Spec 7.1: enabled by default this would make the first report mostly noise. The
        // fixture does log console errors, so this asserts the switch, not an empty console.
        // SITEMAP_CONSISTENCY, the other check that ships disabled, is exercised separately.
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

    private String base() {
        return context.baseUrl().value();
    }

    @Test
    void deadLinkReportsTheExternalTombstoneAsTechnicalFailureButNotTheSoft404() {
        // http://localhost:9/tot refuses every connection. That is a transport failure, not
        // proof of death: the finding must be the INFO "technical failure", never "führt ins
        // Leere" — and the soft-404 page must stay silent either way.
        assertThat(of(CheckType.DEAD_LINK))
                .filteredOn(finding -> finding.messageKey().endsWith(".technicalFailure"))
                .filteredOn(finding -> finding.subjectKey().equals("http://localhost:9/tot"))
                .singleElement()
                .satisfies(finding -> {
                    assertThat(finding.observedOn().path()).isEqualTo("/");
                    assertThat(finding.severity()).isEqualTo(Severity.INFO);
                });

        assertThat(of(CheckType.DEAD_LINK))
                .filteredOn(finding -> finding.messageKey().endsWith(".dead"))
                .extracting(CheckFinding::subjectKey)
                .contains(base() + "hart-404")
                .doesNotContain(base() + "verirrt.html");
    }

    @Test
    void deadLinkReportsTheRobots403AsUnverifiableNotDead() {
        assertThat(of(CheckType.DEAD_LINK))
                .filteredOn(finding -> finding.subjectKey().endsWith("/geblockt-403"))
                .singleElement()
                .satisfies(finding -> {
                    assertThat(finding.messageKey()).isEqualTo("finding.DEAD_LINK.unverifiable");
                    assertThat(finding.severity()).isEqualTo(Severity.INFO);
                });
    }

    @Test
    void deadLinkStaysSilentOnThingsTheVerifierFoundFine() {
        assertThat(of(CheckType.DEAD_LINK))
                .extracting(CheckFinding::subjectKey)
                .doesNotContain(base() + "dateien/handbuch.pdf")
                .doesNotContain(site.externalBase() + "extern/ok");
    }

    @Test
    void fileDownloadReportsTheHtmlPretendingToBeAPdf() {
        assertThat(of(CheckType.FILE_DOWNLOAD))
                .filteredOn(finding -> finding.subjectKey().endsWith("/dateien/preisliste.pdf"))
                .singleElement()
                .satisfies(finding -> {
                    assertThat(finding.messageKey()).isEqualTo("finding.FILE_DOWNLOAD.wrongType");
                    assertThat(finding.messageArgs().get(1)).contains("text/html");
                });
        assertThat(of(CheckType.FILE_DOWNLOAD))
                .extracting(CheckFinding::subjectKey)
                .doesNotContain(base() + "dateien/handbuch.pdf");
    }

    @Test
    void hreflangReportsTheDeadAlternateAndTheMissingReciprocation() {
        assertThat(of(CheckType.HREFLANG))
                .filteredOn(finding -> finding.messageKey().endsWith(".deadAlternate"))
                .singleElement()
                .satisfies(finding -> assertThat(finding.subjectKey()).endsWith("/hart-404"));
        assertThat(of(CheckType.HREFLANG))
                .filteredOn(finding -> finding.messageKey().endsWith(".notReciprocated"))
                .singleElement()
                .satisfies(finding -> {
                    assertThat(finding.messageArgs()).contains(base() + "leistungen.html");
                    assertThat(finding.messageArgs()).contains(base() + "en/index.html");
                });
    }

    @Test
    void tlsCertIsSilentOnAPlainHttpSite() {
        // Deviation D6: the fixture is plain http, so the certificate check has nothing to say
        // rather than reporting the absence of encryption as a defect.
        assertThat(of(CheckType.TLS_CERT)).isEmpty();
    }

    @Test
    void sitemapConsistencyIsSilentWhileDisabledAndSpeaksWhenEnabled() {
        assertThat(of(CheckType.SITEMAP_CONSISTENCY)).isEmpty();

        Map<CheckType, CheckSetting> sitemapOn = new EnumMap<>(context.checkSettings());
        sitemapOn.put(CheckType.SITEMAP_CONSISTENCY, new CheckSetting(true, null, Map.of()));
        SiteContext sitemapSite = new SiteContext(context.siteId(), context.name(),
                context.baseUrl(), context.budget(), context.includePatterns(),
                context.excludePatterns(), context.pinnedKeyPages(), context.respectRobots(),
                context.userAgent(), sitemapOn);

        List<CheckFinding> sitemapFindings = engine.evaluateSite(result.snapshots(), sitemapSite,
                RunFacts.of(result.snapshots(), RunScope.FULL, Instant.now(),
                        verifier.verify(sitemapSite, result.snapshots(),
                                result.verificationCandidates()),
                        tlsProbe.probe(sitemapSite.baseUrl()), result.sitemapUrls()));

        assertThat(sitemapFindings)
                .filteredOn(finding -> finding.messageKey().endsWith(".missingPage"))
                .extracting(CheckFinding::subjectKey)
                .contains(base() + "medien.html");
        assertThat(sitemapFindings)
                .filteredOn(finding -> finding.messageKey().endsWith(".deadEntry"))
                .extracting(CheckFinding::subjectKey)
                .doesNotContain(base() + "nicht-vorhanden.html");
    }

    @Test
    void theWholeFindingListHoldsNoDuplicateTuples() {
        Set<String> tuples = findings.stream()
                .map(f -> f.type() + "|" + f.subjectKey() + "|" + f.locationKey() + "|" + f.messageKey())
                .collect(Collectors.toSet());
        assertThat(tuples).hasSize(findings.size());
    }
}