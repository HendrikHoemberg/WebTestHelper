package dev.hendrikhoemberg.webtesthelper.findings;

import dev.hendrikhoemberg.webtesthelper.catalog.SiteForm;
import dev.hendrikhoemberg.webtesthelper.catalog.SiteService;
import dev.hendrikhoemberg.webtesthelper.model.CheckFinding;
import dev.hendrikhoemberg.webtesthelper.model.CheckType;
import dev.hendrikhoemberg.webtesthelper.model.Evidence;
import dev.hendrikhoemberg.webtesthelper.model.NormalizedUrl;
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
class InteractionCoverageTest extends AbstractPostgresTest {

    private static final CheckType INTERACTION_TYPE = CheckType.PAGE_STATUS;
    private static final CheckType PAGE_CHECK_TYPE = CheckType.DEAD_LINK;

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
     * D74 / spec 6.4: A run driving an interaction check on the homepage must not resolve
     * an interaction finding on /kontakt, even if the crawl visited 300 pages including /kontakt.
     * Meanwhile, standard page checks (like DEAD_LINK) on /kontakt ARE resolved.
     */
    @Test
    void interactionCheckOnlyResolvesOnPagesItWasDrivenOn() {
        // Seed run 1:
        // - interaction finding on /
        // - interaction finding on /kontakt
        // - page check finding on /kontakt
        MaterialisedFinding interactionRoot = finding(INTERACTION_TYPE, "overlay:root", "/", Evidence.NONE);
        MaterialisedFinding interactionKontakt = finding(INTERACTION_TYPE, "overlay:kontakt", "/kontakt", Evidence.NONE);
        MaterialisedFinding pageCheckKontakt = finding(PAGE_CHECK_TYPE, "dead:link1", "/kontakt", Evidence.NONE);

        store.upsertAll(siteId, 1, List.of(interactionRoot, interactionKontakt, pageCheckKontakt), observedAt);

        // Run 2:
        // Covers check types {INTERACTION_TYPE, PAGE_CHECK_TYPE}
        // locationKeys = 300 URLs including / and /kontakt
        // interactionLocationKeys = {/} only
        // interactionCheckTypes = {INTERACTION_TYPE}
        // Reports neither finding.
        List<String> threeHundredUrls = new ArrayList<>(List.of("https://www.example.com/", "https://www.example.com/kontakt"));
        for (int i = 1; i <= 298; i++) {
            threeHundredUrls.add("https://www.example.com/page" + i);
        }

        RunCoverage run2Coverage = RunCoverage.of(
                RunScope.FULL,
                List.of(INTERACTION_TYPE.name(), PAGE_CHECK_TYPE.name()),
                threeHundredUrls,
                List.of(),
                false,
                List.of(INTERACTION_TYPE.name()),
                List.of("https://www.example.com/"));

        int resolvedCount = store.resolveOutsideRun(siteId, 2, run2Coverage);

        // Assert:
        // 1) The / finding for INTERACTION_TYPE is RESOLVED
        assertThat(observedStatus(interactionRoot.fingerprint())).isEqualTo(ObservedStatus.RESOLVED);
        assertThat(resolvedAtRun(interactionRoot.fingerprint())).isEqualTo(2L);

        // 2) The /kontakt finding for INTERACTION_TYPE is STILL ACTIVE (this is what D74 exists for)
        assertThat(observedStatus(interactionKontakt.fingerprint())).isEqualTo(ObservedStatus.ACTIVE);
        assertThat(resolvedAtRun(interactionKontakt.fingerprint())).isNull();

        // 3) The DEAD_LINK finding on /kontakt IS resolved by the same run (split did not narrow page checks)
        assertThat(observedStatus(pageCheckKontakt.fingerprint())).isEqualTo(ObservedStatus.RESOLVED);
        assertThat(resolvedAtRun(pageCheckKontakt.fingerprint())).isEqualTo(2L);

        assertThat(resolvedCount).isEqualTo(2);
    }

    /**
     * D75: Materialising six findings of an interaction check type with the same subject
     * across six distinct pages against a siteWideThreshold of 5 yields six separate findings
     * and NO '*' site-wide finding.
     */
    @Test
    void materialiseSkipsSiteWidePromotionForInteractionCheckTypes() {
        List<CheckFinding> sixInteractionFindings = new ArrayList<>();
        for (int i = 1; i <= 6; i++) {
            sixInteractionFindings.add(new CheckFinding(
                    INTERACTION_TYPE,
                    Severity.ERROR,
                    "overlay:common",
                    new NormalizedUrl("https", "www.example.com", 443, "/page" + i, null),
                    "m",
                    List.of(),
                    Evidence.NONE));
        }

        List<MaterialisedFinding> materialised = FindingMaterializer.materialise(
                siteId, sixInteractionFindings, 5, Set.of(INTERACTION_TYPE));

        assertThat(materialised).hasSize(6);
        assertThat(materialised).noneMatch(f -> "*".equals(f.locationKey()));
        assertThat(materialised).extracting(MaterialisedFinding::locationKey)
                .containsExactlyInAnyOrder("/page1", "/page2", "/page3", "/page4", "/page5", "/page6");

        // By contrast, for standard checks (not in interaction types), siteWideThreshold promotes to '*'
        List<MaterialisedFinding> standardMaterialised = FindingMaterializer.materialise(
                siteId, sixInteractionFindings, 5, Set.of());
        assertThat(standardMaterialised).hasSize(1);
        assertThat(standardMaterialised.get(0).locationKey()).isEqualTo("*");
    }

    /**
     * Empty interaction types or empty interaction location keys must be a no-op on the interaction
     * table update, not resolving anything.
     */
    @Test
    void emptyInteractionArraysAreNoOp() {
        MaterialisedFinding interactionRoot = finding(INTERACTION_TYPE, "overlay:root", "/", Evidence.NONE);
        store.upsertAll(siteId, 1, List.of(interactionRoot), observedAt);

        // Run 2 drove nothing interactively:
        RunCoverage noInteractionCoverage = RunCoverage.of(
                RunScope.FULL,
                List.of(INTERACTION_TYPE.name()),
                List.of("https://www.example.com/"),
                List.of(),
                false,
                List.of(),
                List.of());

        int resolved = store.resolveOutsideRun(siteId, 2, noInteractionCoverage);

        // Since INTERACTION_TYPE was in checkTypes but not in interactionCheckTypes,
        // it was treated as a standard check type on the crawled location keys.
        // But if interactionCheckTypes is specified with empty driven URLs:
        MaterialisedFinding interaction2 = finding(INTERACTION_TYPE, "overlay:other", "/other", Evidence.NONE);
        store.upsertAll(siteId, 2, List.of(interaction2), observedAt);

        RunCoverage emptyUrlsCoverage = new RunCoverage(
                Set.of(),
                Set.of(),
                false,
                Set.of(INTERACTION_TYPE),
                Set.of());

        int resolvedEmpty = store.resolveOutsideRun(siteId, 3, emptyUrlsCoverage);
        assertThat(resolvedEmpty).isZero();
        assertThat(observedStatus(interaction2.fingerprint())).isEqualTo(ObservedStatus.ACTIVE);
    }

    @Autowired
    jakarta.persistence.EntityManager entityManager;

    @Test
    void runResultRepositoryPersistsAndReadsBackInteractionCoverageColumns() {
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
                List.of("COOKIE_BANNER"),
                List.of("https://www.example.com/"),
                dev.hendrikhoemberg.webtesthelper.model.SoftNotFoundProbe.NONE,
                1, 1, 0);

        entityManager.flush();
        entityManager.clear();

        dev.hendrikhoemberg.webtesthelper.runner.persistence.RunEntity reloaded = runs.findById(runId).orElseThrow();
        assertThat(reloaded.getCoveredInteractionCheckTypes()).containsExactly("COOKIE_BANNER");
        assertThat(reloaded.getCoveredInteractionUrls()).containsExactly("https://www.example.com/");
    }

    private MaterialisedFinding finding(CheckType type, String subject, String location, Evidence evidence) {
        List<String> pages = location.equals("*") ? List.of() : List.of(location);
        List<FindingOccurrence> occurrences = pages.stream()
                .map(p -> new FindingOccurrence(p, Severity.ERROR, "m", List.of(), evidence))
                .toList();
        return new MaterialisedFinding(Fingerprint.of(siteId, type, subject, location), type, Severity.ERROR,
                subject, location, "m", List.of(), evidence, occurrences);
    }

    private ObservedStatus observedStatus(String fp) {
        return ObservedStatus.valueOf(jdbc.queryForObject(
                "SELECT observed_status FROM finding WHERE fingerprint = ?", String.class, fp));
    }

    private Long resolvedAtRun(String fp) {
        return jdbc.queryForObject("SELECT resolved_at_run FROM finding WHERE fingerprint = ?", Long.class, fp);
    }
}
