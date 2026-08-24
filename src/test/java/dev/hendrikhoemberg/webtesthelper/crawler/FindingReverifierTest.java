package dev.hendrikhoemberg.webtesthelper.crawler;

import dev.hendrikhoemberg.webtesthelper.model.CheckFinding;
import dev.hendrikhoemberg.webtesthelper.model.CheckType;
import dev.hendrikhoemberg.webtesthelper.model.CrawlBudget;
import dev.hendrikhoemberg.webtesthelper.model.DocumentTypes;
import dev.hendrikhoemberg.webtesthelper.model.Evidence;
import dev.hendrikhoemberg.webtesthelper.model.NormalizedUrl;
import dev.hendrikhoemberg.webtesthelper.model.RunSnapshots;
import dev.hendrikhoemberg.webtesthelper.model.Severity;
import dev.hendrikhoemberg.webtesthelper.model.SiteContext;
import dev.hendrikhoemberg.webtesthelper.model.SoftNotFoundProbe;
import dev.hendrikhoemberg.webtesthelper.model.UrlNormalizer;
import dev.hendrikhoemberg.webtesthelper.model.UrlStatus;
import dev.hendrikhoemberg.webtesthelper.model.UrlVerification;
import dev.hendrikhoemberg.webtesthelper.model.UrlVerifications;
import dev.hendrikhoemberg.webtesthelper.crawler.persistence.ExternalUrlCacheJdbcRepository;
import dev.hendrikhoemberg.webtesthelper.support.AbstractPostgresTest;
import dev.hendrikhoemberg.webtesthelper.support.FixtureSite;
import dev.hendrikhoemberg.webtesthelper.support.Snapshots;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class FindingReverifierTest extends AbstractPostgresTest {

    private static final String AGENT = "FindingReverifierTest/1.0";

    @Autowired
    FindingReverifier reverter;

    @Autowired
    UrlVerifier verifier;

    @Autowired
    ExternalUrlCacheJdbcRepository cache;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    VerifierProperties properties;

    private FixtureSite site;

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM external_url_check");
        site = FixtureSite.start();
    }

    @AfterEach
    void tearDown() {
        site.close();
    }

    private SiteContext ctx() {
        NormalizedUrl base = UrlNormalizer.normalize(site.baseUrl()).orElseThrow();
        return new SiteContext(1L, "Site", base, new CrawlBudget(100, 10, Duration.ofSeconds(30)),
                List.of("/"), List.of(), List.of(), false, AGENT, Map.of());
    }

    private RunSnapshots noSnapshots(SiteContext ctx) {
        return new RunSnapshots(1L, ctx, List.of(), SoftNotFoundProbe.NONE);
    }

    /** Fetch the candidates once and write the external ones back to the cache, mirroring 3b. */
    private UrlVerifications firstPass(SiteContext ctx, String... urls) {
        List<NormalizedUrl> norms = urls.length == 0 ? List.of()
                : java.util.Arrays.stream(urls)
                        .map(u -> UrlNormalizer.normalize(u).orElseThrow())
                        .toList();
        Map<String, UrlVerification> results = verifier.verifyAll(
                norms, ctx.effectiveUserAgent(), DocumentTypes::isDocument);
        storeExternal(ctx, results.values());
        return new UrlVerifications(results);
    }

    private void storeExternal(SiteContext ctx, Collection<UrlVerification> results) {
        List<UrlVerification> external = results.stream()
                .filter(r -> !ctx.baseUrl().sameSiteAs(
                        UrlNormalizer.normalize(r.url()).orElseThrow()))
                .toList();
        cache.store(external, ctx.siteId());
    }

    private String statusOf(NormalizedUrl url) {
        return jdbc.queryForObject("SELECT status FROM external_url_check WHERE url = ?",
                String.class, url.value());
    }

    private Instant checkedAtOf(NormalizedUrl url) {
        return jdbc.queryForObject("SELECT checked_at FROM external_url_check WHERE url = ?",
                Instant.class, url.value());
    }

    private CheckFinding deadFinding(String subject, String observedOn) {
        return new CheckFinding(CheckType.DEAD_LINK, Severity.ERROR, subject,
                UrlNormalizer.normalize(observedOn).orElseThrow(),
                "finding.DEAD_LINK.dead", List.of(subject, "503"), Evidence.NONE);
    }

    private CheckFinding wrongTypeFinding(String subject, String observedOn) {
        return new CheckFinding(CheckType.FILE_DOWNLOAD, Severity.ERROR, subject,
                UrlNormalizer.normalize(observedOn).orElseThrow(),
                "finding.FILE_DOWNLOAD.wrongType", List.of(subject, "text/html; charset=utf-8"),
                Evidence.NONE);
    }

    @Test
    void aDeadLinkThatHealsIsDroppedAndTheCacheRowReadsOk() {
        SiteContext ctx = ctx();
        String subject = site.externalBase() + "extern/flatterhaft";
        NormalizedUrl subjUrl = UrlNormalizer.normalize(subject).orElseThrow();

        UrlVerifications firstPass = firstPass(ctx, subject);
        assertThat(statusOf(subjUrl)).isEqualTo("DEAD");

        CheckFinding finding = deadFinding(subject, site.url("index.html"));
        ReverificationOutcome outcome = reverter.reverify(ctx, noSnapshots(ctx), firstPass,
                List.of(finding));

        assertThat(outcome.surviving()).isEmpty();
        assertThat(outcome.recoveredSubjects()).containsExactly(subject);
        assertThat(outcome.rechecked()).isEqualTo(1);
        assertThat(statusOf(subjUrl)).isEqualTo("OK");
    }

    @Test
    void aSubjectThatHealedIsNotProbedAgainByTheRemainingAttempts() {
        // Recovery is final: once a subject answers OK there is nothing left to learn about it,
        // and probing it again is a request the site did not need to serve (spec 8, politeness).
        // Three attempts, so a subject that stays dead keeps the loop running to the last one.
        SiteContext ctx = ctx();
        String healing = site.externalBase() + "extern/flatterhaft";
        String dead = "http://localhost:9/tot";
        UrlVerifications firstPass = firstPass(ctx, healing, dead);

        FindingReverifier threeAttempts = new FindingReverifier(verifier, cache,
                new VerifierProperties(properties.perHostPermits(), properties.requestTimeout(),
                        properties.successTtl(), properties.failureTtl(), 3,
                        Duration.ofMillis(10)));
        int afterFirstPass = site.requestCount("/extern/flatterhaft");

        ReverificationOutcome outcome = threeAttempts.reverify(ctx, noSnapshots(ctx), firstPass,
                List.of(deadFinding(healing, site.url("index.html")),
                        deadFinding(dead, site.url("index.html"))));

        // Exactly one further probe: attempt 1 healed it, attempts 2 and 3 must skip it.
        assertThat(site.requestCount("/extern/flatterhaft")).isEqualTo(afterFirstPass + 1);
        assertThat(outcome.recoveredSubjects()).containsExactly(healing);
        assertThat(outcome.surviving()).hasSize(1);
    }

    @Test
    void twoFindingsOnTheSameRecoveredSubjectAreDroppedTogether() {
        SiteContext ctx = ctx();
        String subject = site.externalBase() + "extern/flatterhaft";
        NormalizedUrl subjUrl = UrlNormalizer.normalize(subject).orElseThrow();

        UrlVerifications firstPass = firstPass(ctx, subject);

        CheckFinding onPageA = deadFinding(subject, site.url("leistungen.html"));
        CheckFinding onPageB = deadFinding(subject, site.url("kontakt.html"));
        ReverificationOutcome outcome = reverter.reverify(ctx, noSnapshots(ctx), firstPass,
                List.of(onPageA, onPageB));

        assertThat(outcome.surviving()).isEmpty();
        assertThat(outcome.recoveredSubjects()).containsExactly(subject);
        assertThat(outcome.rechecked()).isEqualTo(1);
        assertThat(statusOf(subjUrl)).isEqualTo("OK");
    }

    @Test
    void aDeadLinkThatStaysDeadSurvivesAndTheCacheRowIsRefreshed() {
        SiteContext ctx = ctx();
        String subject = "http://localhost:9/tot";
        NormalizedUrl subjUrl = UrlNormalizer.normalize(subject).orElseThrow();

        UrlVerifications firstPass = firstPass(ctx, subject);
        assertThat(statusOf(subjUrl)).isEqualTo("DEAD");
        Instant before = checkedAtOf(subjUrl);

        CheckFinding finding = deadFinding(subject, site.url("index.html"));
        ReverificationOutcome outcome = reverter.reverify(ctx, noSnapshots(ctx), firstPass,
                List.of(finding));

        assertThat(outcome.surviving()).containsExactly(finding);
        assertThat(outcome.recoveredSubjects()).isEmpty();
        assertThat(outcome.rechecked()).isEqualTo(1);
        assertThat(statusOf(subjUrl)).isEqualTo("DEAD");
        assertThat(checkedAtOf(subjUrl)).isAfter(before);
    }

    @Test
    void aFindingWhoseSubjectIsASnapshotUrlIsNeverReFetched() {
        SiteContext ctx = ctx();
        String subject = site.url("leistungen.html");
        NormalizedUrl subjUrl = UrlNormalizer.normalize(subject).orElseThrow();

        RunSnapshots run = new RunSnapshots(1L, ctx,
                List.of(Snapshots.page(subject).build()), SoftNotFoundProbe.NONE);
        UrlVerifications firstPass = UrlVerifications.of(List.of(
                new UrlVerification(subject, UrlStatus.DEAD, 0, null, 0, null, "down",
                        Instant.now())));

        CheckFinding finding = deadFinding(subject, site.url("index.html"));
        int before = site.requestCount("/leistungen.html");
        ReverificationOutcome outcome = reverter.reverify(ctx, run, firstPass, List.of(finding));

        assertThat(site.requestCount("/leistungen.html")).isEqualTo(before);
        assertThat(outcome.surviving()).containsExactly(finding);
    }

    @Test
    void aFindingWhoseFirstPassWasOkIsNotReFetchedAndSurvives() {
        SiteContext ctx = ctx();
        String subject = site.url("dateien/preisliste.pdf");
        NormalizedUrl subjUrl = UrlNormalizer.normalize(subject).orElseThrow();

        UrlVerifications firstPass = firstPass(ctx, subject);
        assertThat(firstPass.byUrl().get(subject).status()).isEqualTo(UrlStatus.OK);

        CheckFinding finding = wrongTypeFinding(subject, site.url("index.html"));
        int before = site.requestCount("/dateien/preisliste.pdf");
        ReverificationOutcome outcome = reverter.reverify(ctx, noSnapshots(ctx), firstPass,
                List.of(finding));

        assertThat(site.requestCount("/dateien/preisliste.pdf")).isEqualTo(before);
        assertThat(outcome.surviving()).containsExactly(finding);
    }

    @Test
    void aSubjectThatComesBackUnverifiableKeepsItsFinding() {
        SiteContext ctx = ctx();
        String subject = site.url("geblockt-403");
        NormalizedUrl subjUrl = UrlNormalizer.normalize(subject).orElseThrow();

        UrlVerifications firstPass = UrlVerifications.of(List.of(
                new UrlVerification(subject, UrlStatus.DEAD, 0, null, 0, null, "down",
                        Instant.now())));

        CheckFinding finding = deadFinding(subject, site.url("index.html"));
        ReverificationOutcome outcome = reverter.reverify(ctx, noSnapshots(ctx), firstPass,
                List.of(finding));

        assertThat(outcome.surviving()).containsExactly(finding);
        assertThat(outcome.recoveredSubjects()).isEmpty();
        assertThat(outcome.rechecked()).isEqualTo(1);
        assertThat(verifier.verifyAll(List.of(subjUrl), ctx.effectiveUserAgent(),
                DocumentTypes::isDocument).get(subject).status()).isEqualTo(UrlStatus.UNVERIFIABLE);
    }

    @Test
    void recheckedCountsSubjectsAndAnEmptyFindingListDoesNoIo() {
        SiteContext ctx = ctx();

        ReverificationOutcome outcome = reverter.reverify(ctx, noSnapshots(ctx),
                UrlVerifications.EMPTY, List.of());

        assertThat(outcome.surviving()).isEmpty();
        assertThat(outcome.recoveredSubjects()).isEmpty();
        assertThat(outcome.rechecked()).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM external_url_check", Integer.class))
                .isZero();
    }
}
