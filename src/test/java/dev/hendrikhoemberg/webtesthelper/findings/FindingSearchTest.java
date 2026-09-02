package dev.hendrikhoemberg.webtesthelper.findings;

import dev.hendrikhoemberg.webtesthelper.catalog.SiteForm;
import dev.hendrikhoemberg.webtesthelper.catalog.SiteService;
import dev.hendrikhoemberg.webtesthelper.model.CheckType;
import dev.hendrikhoemberg.webtesthelper.model.Evidence;
import dev.hendrikhoemberg.webtesthelper.model.ObservedStatus;
import dev.hendrikhoemberg.webtesthelper.model.Severity;
import dev.hendrikhoemberg.webtesthelper.model.TriageStatus;
import dev.hendrikhoemberg.webtesthelper.support.AbstractPostgresTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

@Transactional
class FindingSearchTest extends AbstractPostgresTest {

    @Autowired
    FindingStore store;

    @Autowired
    FindingService findingService;

    @Autowired
    SiteService sites;

    private long site1Id;
    private long site2Id;
    private Instant baseTime;

    @BeforeEach
    void setup() {
        site1Id = sites.create(new SiteForm(
                "Site 1", "https://site1.example.com/", 100, 3,
                Duration.ofMinutes(10), List.of(), List.of(), true, null, true));
        site2Id = sites.create(new SiteForm(
                "Site 2", "https://site2.example.com/", 100, 3,
                Duration.ofMinutes(10), List.of(), List.of(), true, null, true));
        baseTime = Instant.now().truncatedTo(ChronoUnit.MICROS);
    }

    @Test
    void noFiltersReturnsEveryFindingOfSiteAndNoneOfOtherSite() {
        MaterialisedFinding f1 = finding(site1Id, CheckType.DEAD_LINK, Severity.ERROR, "s1", "/p1");
        MaterialisedFinding f2 = finding(site1Id, CheckType.PAGE_STATUS, Severity.WARN, "s2", "/p2");
        MaterialisedFinding fOther = finding(site2Id, CheckType.DEAD_LINK, Severity.ERROR, "s-other", "/p3");

        store.upsertAll(site1Id, 1, List.of(f1, f2), baseTime);
        store.upsertAll(site2Id, 1, List.of(fOther), baseTime);

        FindingQuery query = FindingQuery.forSite(site1Id);
        FindingPage page = findingService.search(query);

        assertThat(page.total()).isEqualTo(2);
        assertThat(page.findings()).hasSize(2);
        assertThat(page.findings().stream().map(Finding::siteId).distinct()).containsExactly(site1Id);
    }

    @Test
    void eachAxisFiltersIndependentlyAndMultipleAxesAreCombinedWithAnd() {
        // Seed 4 findings on site1:
        // f1: DEAD_LINK, ERROR, UNTRIAGED, ACTIVE
        // f2: DEAD_LINK, WARN, ACKNOWLEDGED, ACTIVE
        // f3: PAGE_STATUS, ERROR, ACKNOWLEDGED, ACTIVE
        // f4: PAGE_STATUS, INFO, UNTRIAGED, ACTIVE
        MaterialisedFinding f1 = finding(site1Id, CheckType.DEAD_LINK, Severity.ERROR, "s1", "/p1");
        MaterialisedFinding f2 = finding(site1Id, CheckType.DEAD_LINK, Severity.WARN, "s2", "/p2");
        MaterialisedFinding f3 = finding(site1Id, CheckType.PAGE_STATUS, Severity.ERROR, "s3", "/p3");
        MaterialisedFinding f4 = finding(site1Id, CheckType.PAGE_STATUS, Severity.INFO, "s4", "/p4");

        List<Long> ids = store.upsertAll(site1Id, 1, List.of(f1, f2, f3, f4), baseTime);
        // Triage f2 and f3 as ACKNOWLEDGED
        store.triage(site1Id, List.of(ids.get(1), ids.get(2)),
                new TriageAction(TriageStatus.ACKNOWLEDGED, "ok", null), "tester", baseTime);

        // Filter by severity alone: ERROR (f1, f3)
        FindingPage errorPage = findingService.search(new FindingQuery(
                site1Id, Set.of(Severity.ERROR), Set.of(), null, Set.of(), 1, 50));
        assertThat(errorPage.total()).isEqualTo(2);
        assertThat(errorPage.findings()).extracting(Finding::subjectKey).containsExactlyInAnyOrder("s1", "s3");

        // Filter by triage status alone: ACKNOWLEDGED (f2, f3)
        FindingPage ackPage = findingService.search(new FindingQuery(
                site1Id, Set.of(), Set.of(TriageStatus.ACKNOWLEDGED), null, Set.of(), 1, 50));
        assertThat(ackPage.total()).isEqualTo(2);
        assertThat(ackPage.findings()).extracting(Finding::subjectKey).containsExactlyInAnyOrder("s2", "s3");

        // Filter by check type alone: DEAD_LINK (f1, f2)
        FindingPage deadLinkPage = findingService.search(new FindingQuery(
                site1Id, Set.of(), Set.of(), null, Set.of(CheckType.DEAD_LINK), 1, 50));
        assertThat(deadLinkPage.total()).isEqualTo(2);
        assertThat(deadLinkPage.findings()).extracting(Finding::subjectKey).containsExactlyInAnyOrder("s1", "s2");

        // Combined AND: DEAD_LINK and ACKNOWLEDGED -> only f2
        FindingPage combinedPage = findingService.search(new FindingQuery(
                site1Id, Set.of(), Set.of(TriageStatus.ACKNOWLEDGED), null, Set.of(CheckType.DEAD_LINK), 1, 50));
        assertThat(combinedPage.total()).isEqualTo(1);
        assertThat(combinedPage.findings().get(0).subjectKey()).isEqualTo("s2");

        // Combined AND: ERROR and ACKNOWLEDGED -> only f3
        FindingPage combinedPage2 = findingService.search(new FindingQuery(
                site1Id, Set.of(Severity.ERROR), Set.of(TriageStatus.ACKNOWLEDGED), null, Set.of(), 1, 50));
        assertThat(combinedPage2.total()).isEqualTo(1);
        assertThat(combinedPage2.findings().get(0).subjectKey()).isEqualTo("s3");
    }

    @Test
    void emptySetOnAxisMeansNoFilterNotMatchNothing() {
        MaterialisedFinding f1 = finding(site1Id, CheckType.DEAD_LINK, Severity.ERROR, "s1", "/p1");
        MaterialisedFinding f2 = finding(site1Id, CheckType.MEDIA_PLAYABLE, Severity.INFO, "s2", "/p2");
        store.upsertAll(site1Id, 1, List.of(f1, f2), baseTime);

        // Empty sets for all filter collections
        FindingQuery query = new FindingQuery(site1Id, Set.of(), Set.of(), null, Set.of(), 1, 50);
        FindingPage page = findingService.search(query);

        assertThat(page.total()).isEqualTo(2);
        assertThat(page.findings()).hasSize(2);
    }

    @Test
    void orderingIsErrorBeforeWarnBeforeInfoThenMostRecentlySeenFirst() {
        // Seed findings with different severities and lastSeenAt timestamps
        // fInfoOld: INFO at baseTime
        // fInfoNew: INFO at baseTime + 10s
        // fWarnOld: WARN at baseTime
        // fWarnNew: WARN at baseTime + 10s
        // fErrorOld: ERROR at baseTime
        // fErrorNew: ERROR at baseTime + 10s
        MaterialisedFinding fInfoOld = finding(site1Id, CheckType.PAGE_STATUS, Severity.INFO, "info-old", "/p1");
        MaterialisedFinding fInfoNew = finding(site1Id, CheckType.PAGE_STATUS, Severity.INFO, "info-new", "/p2");
        MaterialisedFinding fWarnOld = finding(site1Id, CheckType.PAGE_STATUS, Severity.WARN, "warn-old", "/p3");
        MaterialisedFinding fWarnNew = finding(site1Id, CheckType.PAGE_STATUS, Severity.WARN, "warn-new", "/p4");
        MaterialisedFinding fErrorOld = finding(site1Id, CheckType.PAGE_STATUS, Severity.ERROR, "error-old", "/p5");
        MaterialisedFinding fErrorNew = finding(site1Id, CheckType.PAGE_STATUS, Severity.ERROR, "error-new", "/p6");

        store.upsertAll(site1Id, 1, List.of(fInfoOld, fWarnOld, fErrorOld), baseTime);
        store.upsertAll(site1Id, 2, List.of(fInfoNew, fWarnNew, fErrorNew), baseTime.plusSeconds(10));

        FindingPage page = findingService.search(FindingQuery.forSite(site1Id));

        assertThat(page.findings()).extracting(Finding::subjectKey).containsExactly(
                "error-new",
                "error-old",
                "warn-new",
                "warn-old",
                "info-new",
                "info-old"
        );
    }

    @Test
    void totalIsCountBeforePagingAndPage3Of120Returns20AndPage4ReturnsEmpty() {
        // Seed 120 findings
        List<MaterialisedFinding> list = new ArrayList<>(120);
        for (int i = 0; i < 120; i++) {
            list.add(finding(site1Id, CheckType.DEAD_LINK, Severity.ERROR, "item-" + String.format("%03d", i), "/p/" + i));
        }
        store.upsertAll(site1Id, 1, list, baseTime);

        // Page 1 with size 50
        FindingPage page1 = findingService.search(new FindingQuery(
                site1Id, Set.of(), Set.of(), null, Set.of(), 1, 50));
        assertThat(page1.total()).isEqualTo(120);
        assertThat(page1.pageCount()).isEqualTo(3);
        assertThat(page1.findings()).hasSize(50);

        // Page 2 with size 50
        FindingPage page2 = findingService.search(new FindingQuery(
                site1Id, Set.of(), Set.of(), null, Set.of(), 2, 50));
        assertThat(page2.total()).isEqualTo(120);
        assertThat(page2.findings()).hasSize(50);

        // Page 3 with size 50 -> 20 rows
        FindingPage page3 = findingService.search(new FindingQuery(
                site1Id, Set.of(), Set.of(), null, Set.of(), 3, 50));
        assertThat(page3.total()).isEqualTo(120);
        assertThat(page3.findings()).hasSize(20);

        // Page 4 with size 50 -> 0 rows, no error thrown
        FindingPage page4 = findingService.search(new FindingQuery(
                site1Id, Set.of(), Set.of(), null, Set.of(), 4, 50));
        assertThat(page4.total()).isEqualTo(120);
        assertThat(page4.findings()).isEmpty();
    }

    @Test
    void freeTextSearchMatchesSubjectAndLocationCaseInsensitively() {
        MaterialisedFinding f1 = finding(site1Id, CheckType.DEAD_LINK, Severity.ERROR,
                "https://example.com/karriere/stelle", "/karriere/stelle");
        MaterialisedFinding f2 = finding(site1Id, CheckType.PAGE_STATUS, Severity.WARN,
                "s2", "/impressum");
        MaterialisedFinding f3 = finding(site1Id, CheckType.DEAD_LINK, Severity.ERROR,
                "s3", "/blog/1");
        store.upsertAll(site1Id, 1, List.of(f1, f2, f3), baseTime);

        // Match on the subject (URL)
        FindingPage bySubject = findingService.search(new FindingQuery(
                site1Id, Set.of(), Set.of(), null, Set.of(), 1, 50, "karriere"));
        assertThat(bySubject.total()).isEqualTo(1);
        assertThat(bySubject.findings()).extracting(Finding::subjectKey)
                .containsExactly("https://example.com/karriere/stelle");

        // Match on the location, case-insensitively
        FindingPage byLocation = findingService.search(new FindingQuery(
                site1Id, Set.of(), Set.of(), null, Set.of(), 1, 50, "IMPRESSUM"));
        assertThat(byLocation.total()).isEqualTo(1);
        assertThat(byLocation.findings()).extracting(Finding::locationKey)
                .containsExactly("/impressum");

        // No match
        FindingPage none = findingService.search(new FindingQuery(
                site1Id, Set.of(), Set.of(), null, Set.of(), 1, 50, "nichtda"));
        assertThat(none.total()).isZero();
    }

    @Test
    void freeTextSearchTreatsLikeWildcardsAsLiteralText() {
        MaterialisedFinding fPercent = finding(site1Id, CheckType.DEAD_LINK, Severity.ERROR,
                "rabatt100%", "/p1");
        MaterialisedFinding fOther = finding(site1Id, CheckType.DEAD_LINK, Severity.ERROR,
                "rabatt100x", "/p2");
        store.upsertAll(site1Id, 1, List.of(fPercent, fOther), baseTime);

        FindingPage page = findingService.search(new FindingQuery(
                site1Id, Set.of(), Set.of(), null, Set.of(), 1, 50, "100%"));

        assertThat(page.total()).isEqualTo(1);
        assertThat(page.findings()).extracting(Finding::subjectKey).containsExactly("rabatt100%");
    }

    private MaterialisedFinding finding(long siteId, CheckType type, Severity severity, String subject, String location) {
        String fp = Fingerprint.of(siteId, type, subject, location);
        FindingOccurrence occ = new FindingOccurrence(location, severity, "finding." + type.name() + ".test", List.of(), Evidence.NONE);
        return new MaterialisedFinding(fp, type, severity, subject, location, "finding." + type.name() + ".test", List.of(), Evidence.NONE, List.of(occ));
    }
}
