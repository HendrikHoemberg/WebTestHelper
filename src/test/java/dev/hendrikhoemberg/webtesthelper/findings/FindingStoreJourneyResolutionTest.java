package dev.hendrikhoemberg.webtesthelper.findings;

import dev.hendrikhoemberg.webtesthelper.catalog.SiteForm;
import dev.hendrikhoemberg.webtesthelper.catalog.SiteService;
import dev.hendrikhoemberg.webtesthelper.model.CheckType;
import dev.hendrikhoemberg.webtesthelper.model.Evidence;
import dev.hendrikhoemberg.webtesthelper.model.ObservedStatus;
import dev.hendrikhoemberg.webtesthelper.model.RunCoverage;
import dev.hendrikhoemberg.webtesthelper.model.RunScope;
import dev.hendrikhoemberg.webtesthelper.model.Severity;
import dev.hendrikhoemberg.webtesthelper.support.AbstractPostgresTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

@Transactional
class FindingStoreJourneyResolutionTest extends AbstractPostgresTest {

    @Autowired
    FindingStore store;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    SiteService sites;
    @Autowired
    dev.hendrikhoemberg.webtesthelper.runner.persistence.RunResultJdbcRepository runResults;
    @Autowired
    dev.hendrikhoemberg.webtesthelper.runner.persistence.RunRepository runs;
    @Autowired
    jakarta.persistence.EntityManager entityManager;

    private long siteId;
    private Instant observedAt;

    @BeforeEach
    void setup() {
        siteId = sites.create(new SiteForm(
                "Kunde", "https://www.example.com/", 100, 3,
                Duration.ofMinutes(10), List.of(), List.of(), true, null, true));
        observedAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
    }

    /**
     * D107: A run that replayed journey 1 but not journey 2 resolves only journey 1's findings.
     * Journey 2's finding must remain ACTIVE.
     */
    @Test
    void resolvesOnlyWithinCoveredJourneys() {
        long journey1Id = 101L;
        long journey2Id = 102L;

        MaterialisedFinding findingJ1 = journeyFinding(CheckType.JOURNEY_STEP_FAILED, "step:submit", journey1Id);
        MaterialisedFinding findingJ2 = journeyFinding(CheckType.JOURNEY_STEP_FAILED, "step:checkout", journey2Id);

        store.upsertAll(siteId, 1, List.of(findingJ1, findingJ2), observedAt);

        // Run 2 replayed only journey 1 (journeyIds = {101L})
        RunCoverage run2Coverage = RunCoverage.of(
                RunScope.FULL,
                List.of(CheckType.JOURNEY_STEP_FAILED.name()),
                List.of("https://www.example.com/"),
                List.of(),
                false,
                Set.of(journey1Id));

        int resolved = store.resolveOutsideRun(siteId, 2, run2Coverage);

        // Journey 1's finding is RESOLVED
        assertThat(observedStatus(findingJ1.fingerprint())).isEqualTo(ObservedStatus.RESOLVED);
        assertThat(resolvedAtRun(findingJ1.fingerprint())).isEqualTo(2L);

        // Journey 2's finding is STILL ACTIVE
        assertThat(observedStatus(findingJ2.fingerprint())).isEqualTo(ObservedStatus.ACTIVE);
        assertThat(resolvedAtRun(findingJ2.fingerprint())).isNull();

        assertThat(resolved).isEqualTo(1);
    }

    /**
     * D107 / spec 6.4 leak: A crawl-scoped statement must NOT resolve journey findings, even though
     * the run crawled 300 URLs and covered the whole site. Standard crawl findings ARE resolved.
     */
    @Test
    void crawlScopedStatementDoesNotResolveJourneyFindings() {
        long journeyId = 201L;
        MaterialisedFinding journeyFinding = journeyFinding(CheckType.JOURNEY_STEP_FAILED, "step:login", journeyId);
        MaterialisedFinding crawlFinding = pageFinding(CheckType.DEAD_LINK, "dead:link1", "/page1");

        store.upsertAll(siteId, 1, List.of(journeyFinding, crawlFinding), observedAt);

        // Run 2 crawled 300 URLs including /page1, wholeSite = true, checkTypes includes all types,
        // but no journeys were replayed (journeyIds = empty).
        List<String> threeHundredUrls = new ArrayList<>(List.of("https://www.example.com/page1"));
        for (int i = 2; i <= 300; i++) {
            threeHundredUrls.add("https://www.example.com/page" + i);
        }

        RunCoverage run2Coverage = RunCoverage.of(
                RunScope.FULL,
                RunScope.FULL.checkTypes().stream().map(CheckType::name).toList(),
                threeHundredUrls,
                List.of(),
                false,
                Set.of());

        int resolved = store.resolveOutsideRun(siteId, 2, run2Coverage);

        // Journey finding must NOT be resolved by the crawl
        assertThat(observedStatus(journeyFinding.fingerprint())).isEqualTo(ObservedStatus.ACTIVE);
        assertThat(resolvedAtRun(journeyFinding.fingerprint())).isNull();

        // Standard page check finding IS resolved
        assertThat(observedStatus(crawlFinding.fingerprint())).isEqualTo(ObservedStatus.RESOLVED);
        assertThat(resolvedAtRun(crawlFinding.fingerprint())).isEqualTo(2L);

        assertThat(resolved).isEqualTo(1);
    }

    /**
     * D107 backstop: a journey type must leave the crawl-scoped statement because of its *type*,
     * not because its location key happens to look nothing like a URL.
     *
     * <p>{@link FindingStore#resolveOutsideRun} filters journey types out of the crawl-scoped
     * statement, and {@code crawlScopedStatementDoesNotResolveJourneyFindings} cannot see that
     * filter work: a journey's location key is a numeric id, so the location scoping alone already
     * spares it and the test stays green with the filter deleted. This test removes that second
     * line of defence by giving the journey finding a location key the run actually crawled, so
     * the type filter is the only thing left that can save it. Delete
     * {@code .filter(t -> !t.journey())} and this fails.
     */
    @Test
    void journeyFindingIsNotResolvedByTheCrawlEvenWhenItsLocationKeyWasCrawled() {
        // The location key a crawl of https://www.example.com/page1 covers (UrlNormalizer strips
        // the origin), so this journey finding sits squarely inside the crawl's scope.
        MaterialisedFinding journeyFinding = findingAt(CheckType.JOURNEY_STEP_FAILED, "step:login", "/page1");

        store.upsertAll(siteId, 1, List.of(journeyFinding), observedAt);

        RunCoverage run2Coverage = RunCoverage.of(
                RunScope.FULL,
                RunScope.FULL.checkTypes().stream().map(CheckType::name).toList(),
                List.of("https://www.example.com/page1"),
                List.of(),
                false,
                Set.of());

        int resolved = store.resolveOutsideRun(siteId, 2, run2Coverage);

        assertThat(observedStatus(journeyFinding.fingerprint())).isEqualTo(ObservedStatus.ACTIVE);
        assertThat(resolvedAtRun(journeyFinding.fingerprint())).isNull();
        assertThat(resolved).isZero();
    }

    /**
     * The same backstop against the site-wide clause (spec 6.2): a whole-site run resolves
     * {@code location_key = '*'} findings, and only the type filter keeps a journey type out of
     * that clause. A journey is a single location and can never be "on 312 pages".
     */
    @Test
    void journeyFindingIsNotResolvedBySiteWidePromotion() {
        MaterialisedFinding journeyFinding = findingAt(CheckType.SELECTOR_DRIFT, "step:login", "*");

        store.upsertAll(siteId, 1, List.of(journeyFinding), observedAt);

        RunCoverage run2Coverage = RunCoverage.of(
                RunScope.FULL,
                RunScope.FULL.checkTypes().stream().map(CheckType::name).toList(),
                List.of("https://www.example.com/page1"),
                List.of(),
                false,
                Set.of());

        int resolved = store.resolveOutsideRun(siteId, 2, run2Coverage);

        assertThat(observedStatus(journeyFinding.fingerprint())).isEqualTo(ObservedStatus.ACTIVE);
        assertThat(resolvedAtRun(journeyFinding.fingerprint())).isNull();
        assertThat(resolved).isZero();
    }

    /**
     * Selector drift is also a journey check type and resolves when the journey is replayed.
     */
    @Test
    void selectorDriftResolvesWhenJourneyIsCovered() {
        long journeyId = 301L;
        MaterialisedFinding driftFinding = journeyFinding(CheckType.SELECTOR_DRIFT, "step:input#search", journeyId);
        store.upsertAll(siteId, 1, List.of(driftFinding), observedAt);

        RunCoverage run2Coverage = RunCoverage.of(
                RunScope.FULL,
                List.of(CheckType.SELECTOR_DRIFT.name()),
                List.of("https://www.example.com/"),
                List.of(),
                false,
                Set.of(journeyId));

        int resolved = store.resolveOutsideRun(siteId, 2, run2Coverage);

        assertThat(resolved).isEqualTo(1);
        assertThat(observedStatus(driftFinding.fingerprint())).isEqualTo(ObservedStatus.RESOLVED);
        assertThat(resolvedAtRun(driftFinding.fingerprint())).isEqualTo(2L);
    }

    /**
     * An empty journeyIds set is a no-op on journey resolution.
     */
    @Test
    void emptyJourneyIdsIsNoOp() {
        long journeyId = 401L;
        MaterialisedFinding finding = journeyFinding(CheckType.JOURNEY_STEP_FAILED, "step:form", journeyId);
        store.upsertAll(siteId, 1, List.of(finding), observedAt);

        RunCoverage coverage = RunCoverage.of(
                RunScope.FULL,
                List.of(CheckType.JOURNEY_STEP_FAILED.name()),
                List.of("https://www.example.com/"),
                List.of(),
                false,
                Set.of());

        int resolved = store.resolveOutsideRun(siteId, 2, coverage);

        assertThat(resolved).isZero();
        assertThat(observedStatus(finding.fingerprint())).isEqualTo(ObservedStatus.ACTIVE);
    }

    @Test
    void nonRerecordingJourneyFindingStillResolvesWhenJourneyCompletes() {
        long journeyId = 501L;
        MaterialisedFinding finding = journeyFinding(CheckType.JOURNEY_STEP_FAILED, "step:checkout", journeyId);
        store.upsertAll(siteId, 1, List.of(finding), observedAt);

        RunCoverage run2Coverage = RunCoverage.of(
                RunScope.FULL,
                List.of(CheckType.JOURNEY_STEP_FAILED.name()),
                List.of("https://www.example.com/"),
                List.of(),
                false,
                Set.of(journeyId));

        // The journey completed and is NOT flagged needs-re-recording, so the exclusion set is empty.
        int resolved = store.resolveOutsideRun(siteId, 2, run2Coverage, Set.of());

        assertThat(resolved).isEqualTo(1);
        assertThat(observedStatus(finding.fingerprint())).isEqualTo(ObservedStatus.RESOLVED);
        assertThat(resolvedAtRun(finding.fingerprint())).isEqualTo(2L);
    }

    @Test
    void needsRerecordingJourneyFindingStaysActiveWhenJourneyCompletes() {
        long journeyId = 601L;
        MaterialisedFinding finding = journeyFinding(CheckType.JOURNEY_STEP_FAILED, "step:submit", journeyId);
        store.upsertAll(siteId, 1, List.of(finding), observedAt);

        RunCoverage run2Coverage = RunCoverage.of(
                RunScope.FULL,
                List.of(CheckType.JOURNEY_STEP_FAILED.name()),
                List.of("https://www.example.com/"),
                List.of(),
                false,
                Set.of(journeyId));

        // The journey completed but is flagged needs-re-recording: its finding must not be resolved,
        // even though the run did not re-observe it.
        int resolved = store.resolveOutsideRun(siteId, 2, run2Coverage, Set.of(journeyId));

        assertThat(resolved).isZero();
        assertThat(observedStatus(finding.fingerprint())).isEqualTo(ObservedStatus.ACTIVE);
        assertThat(resolvedAtRun(finding.fingerprint())).isNull();
    }

    @Test
    void runResultRepositoryPersistsAndReadsBackJourneyCoverageColumns() {
        dev.hendrikhoemberg.webtesthelper.runner.persistence.RunEntity run =
                new dev.hendrikhoemberg.webtesthelper.runner.persistence.RunEntity();
        run.setSiteId(siteId);
        run.setTriggerType(dev.hendrikhoemberg.webtesthelper.model.RunTrigger.MANUAL);
        run.setScope(RunScope.FULL);
        long runId = runs.save(run).getId();

        dev.hendrikhoemberg.webtesthelper.crawler.CrawlResult crawlResult =
                new dev.hendrikhoemberg.webtesthelper.crawler.CrawlResult(
                        new dev.hendrikhoemberg.webtesthelper.model.RunSnapshots(
                                runId, sites.contextFor(siteId), List.of(), dev.hendrikhoemberg.webtesthelper.model.SoftNotFoundProbe.NONE),
                        5, 0, List.of("https://www.example.com/"), false, null, List.of(), List.of());

        runResults.saveCrawlOutcome(runId, crawlResult,
                List.of("DEAD_LINK"),
                List.of(),
                List.of(),
                List.of(101L, 102L),
                dev.hendrikhoemberg.webtesthelper.model.SoftNotFoundProbe.NONE,
                1, 1, 0);

        entityManager.flush();
        entityManager.clear();

        dev.hendrikhoemberg.webtesthelper.runner.persistence.RunEntity reloaded = runs.findById(runId).orElseThrow();
        assertThat(reloaded.getCoveredJourneyIds()).containsExactly(101L, 102L);
    }

    private MaterialisedFinding journeyFinding(CheckType type, String subject, long journeyId) {
        String locationKey = String.valueOf(journeyId);
        FindingOccurrence occurrence = new FindingOccurrence(null, Severity.ERROR, "m", List.of(), Evidence.NONE);
        return new MaterialisedFinding(
                Fingerprint.of(siteId, type, subject, locationKey),
                type,
                Severity.ERROR,
                subject,
                locationKey,
                "m",
                List.of(),
                Evidence.NONE,
                List.of(occurrence));
    }

    /** A finding of any type at any location key — for the adversarial keys the mappers never emit. */
    private MaterialisedFinding findingAt(CheckType type, String subject, String locationKey) {
        FindingOccurrence occurrence = new FindingOccurrence(null, Severity.ERROR, "m", List.of(), Evidence.NONE);
        return new MaterialisedFinding(
                Fingerprint.of(siteId, type, subject, locationKey),
                type,
                Severity.ERROR,
                subject,
                locationKey,
                "m",
                List.of(),
                Evidence.NONE,
                List.of(occurrence));
    }

    private MaterialisedFinding pageFinding(CheckType type, String subject, String location) {
        FindingOccurrence occurrence = new FindingOccurrence(location, Severity.ERROR, "m", List.of(), Evidence.NONE);
        return new MaterialisedFinding(
                Fingerprint.of(siteId, type, subject, location),
                type,
                Severity.ERROR,
                subject,
                location,
                "m",
                List.of(),
                Evidence.NONE,
                List.of(occurrence));
    }

    private ObservedStatus observedStatus(String fp) {
        return ObservedStatus.valueOf(jdbc.queryForObject(
                "SELECT observed_status FROM finding WHERE fingerprint = ?", String.class, fp));
    }

    private Long resolvedAtRun(String fp) {
        return jdbc.queryForObject("SELECT resolved_at_run FROM finding WHERE fingerprint = ?", Long.class, fp);
    }
}
