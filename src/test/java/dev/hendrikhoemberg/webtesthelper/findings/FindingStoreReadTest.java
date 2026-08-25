package dev.hendrikhoemberg.webtesthelper.findings;

import dev.hendrikhoemberg.webtesthelper.catalog.SiteForm;
import dev.hendrikhoemberg.webtesthelper.catalog.SiteService;
import dev.hendrikhoemberg.webtesthelper.model.CheckType;
import dev.hendrikhoemberg.webtesthelper.model.Evidence;
import dev.hendrikhoemberg.webtesthelper.model.Severity;
import dev.hendrikhoemberg.webtesthelper.support.AbstractPostgresTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@Transactional
class FindingStoreReadTest extends AbstractPostgresTest {

    @Autowired
    FindingStore store;

    @Autowired
    FindingService findingService;

    @Autowired
    SiteService sites;

    private long siteId;
    private Instant observedAt;

    @BeforeEach
    void setup() {
        siteId = sites.create(new SiteForm(
                "Kunde Read", "https://www.example.com/", 100, 3,
                Duration.ofMinutes(10), List.of(), List.of(), true, null));
        observedAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
    }

    @Test
    void byIdReturnsMaterialisedFindingWithEvidenceAndMessageArgsAndEmptyForUnknown() {
        Evidence evidence = new Evidence(
                "abcdef0123456789abcdef0123456789.png",
                404,
                "GET /missing",
                "Not Found",
                List.of("error in main.js", "failed to load resource")
        );
        MaterialisedFinding finding = finding(CheckType.PAGE_STATUS, "missing-page", "https://www.example.com/missing",
                evidence, List.of("arg-1", "arg-2"));

        List<Long> ids = store.upsertAll(siteId, 1, List.of(finding), observedAt);
        long findingId = ids.get(0);

        Optional<Finding> found = store.byId(findingId);
        assertThat(found).isPresent();
        Finding f = found.get();
        assertThat(f.id()).isEqualTo(findingId);
        assertThat(f.siteId()).isEqualTo(siteId);
        assertThat(f.type()).isEqualTo(CheckType.PAGE_STATUS);
        assertThat(f.messageArgs()).containsExactly("arg-1", "arg-2");
        assertThat(f.evidence().screenshotPath()).isEqualTo("abcdef0123456789abcdef0123456789.png");
        assertThat(f.evidence().httpStatus()).isEqualTo(404);
        assertThat(f.evidence().requestDetail()).isEqualTo("GET /missing");
        assertThat(f.evidence().responseDetail()).isEqualTo("Not Found");
        assertThat(f.evidence().consoleExcerpt()).containsExactly("error in main.js", "failed to load resource");

        // FindingService also re-exposes byId
        assertThat(findingService.byId(findingId)).contains(f);

        // Unknown id returns empty
        assertThat(store.byId(999_999L)).isEmpty();
        assertThat(findingService.byId(999_999L)).isEmpty();
    }

    @Test
    void occurrencesOfLastRunReturnsOnlyLastRunsRows() {
        MaterialisedFinding run1Finding = finding(CheckType.DEAD_LINK, "dead-link-1", "*",
                Evidence.NONE, List.of(), List.of("https://www.example.com/page-run-1"));
        List<Long> ids1 = store.upsertAll(siteId, 1, List.of(run1Finding), observedAt);
        long findingId = ids1.get(0);
        store.insertOccurrences(ids1, 1, List.of(run1Finding), observedAt);

        // Seed same finding in run 2 with different occurrence page
        Instant secondRunAt = observedAt.plusSeconds(120);
        MaterialisedFinding run2Finding = finding(CheckType.DEAD_LINK, "dead-link-1", "*",
                Evidence.NONE, List.of(), List.of("https://www.example.com/page-run-2"));
        List<Long> ids2 = store.upsertAll(siteId, 2, List.of(run2Finding), secondRunAt);
        assertThat(ids2.get(0)).isEqualTo(findingId);
        store.insertOccurrences(ids2, 2, List.of(run2Finding), secondRunAt);

        List<FindingOccurrence> occurrences = store.occurrencesOfLastRun(findingId, 50);
        assertThat(occurrences).hasSize(1);
        assertThat(occurrences.get(0).pageUrl()).isEqualTo("https://www.example.com/page-run-2");

        // FindingService re-exposes occurrencesOfLastRun
        List<FindingOccurrence> fromService = findingService.occurrencesOfLastRun(findingId, 50);
        assertThat(fromService).hasSize(1);
        assertThat(fromService.get(0).pageUrl()).isEqualTo("https://www.example.com/page-run-2");
    }

    @Test
    void siteScopedOccurrenceComesBackWithNullPageUrl() {
        MaterialisedFinding siteWideFinding = finding(CheckType.TLS_CERT, "cert-warning", "*",
                Evidence.NONE, List.of("14")); // 5-arg finding() helper produces one occurrence with null page_url

        List<Long> ids = store.upsertAll(siteId, 1, List.of(siteWideFinding), observedAt);
        long findingId = ids.get(0);
        store.insertOccurrences(ids, 1, List.of(siteWideFinding), observedAt);

        List<FindingOccurrence> occurrences = store.occurrencesOfLastRun(findingId, 50);
        assertThat(occurrences).hasSize(1);
        assertThat(occurrences.get(0).pageUrl()).isNull();
    }

    @Test
    void occurrencesLimitTruncatesAndCallerLearnsTrueTotalFromPageCount() {
        List<String> tenPages = List.of(
                "https://www.example.com/p01",
                "https://www.example.com/p02",
                "https://www.example.com/p03",
                "https://www.example.com/p04",
                "https://www.example.com/p05",
                "https://www.example.com/p06",
                "https://www.example.com/p07",
                "https://www.example.com/p08",
                "https://www.example.com/p09",
                "https://www.example.com/p10"
        );
        MaterialisedFinding finding = finding(CheckType.DEAD_LINK, "multi-page", "*",
                Evidence.NONE, List.of(), tenPages);

        List<Long> ids = store.upsertAll(siteId, 1, List.of(finding), observedAt);
        long findingId = ids.get(0);
        store.insertOccurrences(ids, 1, List.of(finding), observedAt);

        List<FindingOccurrence> limited = store.occurrencesOfLastRun(findingId, 3);
        assertThat(limited).hasSize(3);
        assertThat(limited.stream().map(FindingOccurrence::pageUrl).toList())
                .containsExactly(
                        "https://www.example.com/p01",
                        "https://www.example.com/p02",
                        "https://www.example.com/p03"
                );

        Finding storedFinding = store.byId(findingId).orElseThrow();
        assertThat(storedFinding.pageCount()).isEqualTo(10);
    }

    private MaterialisedFinding finding(CheckType type, String subject, String location, Evidence evidence,
                                       List<String> messageArgs) {
        List<String> pages = new java.util.ArrayList<>();
        if (location.equals("*")) {
            pages.add(null);
        } else {
            pages.add(location);
        }
        return finding(type, subject, location, evidence, messageArgs, pages);
    }

    private MaterialisedFinding finding(CheckType type, String subject, String location, Evidence evidence,
                                       List<String> messageArgs, List<String> pageUrls) {
        List<FindingOccurrence> occurrences = pageUrls.stream()
                .map(p -> new FindingOccurrence(p, Severity.ERROR, "m", messageArgs, evidence))
                .toList();
        return new MaterialisedFinding(Fingerprint.of(siteId, type, subject, location), type, Severity.ERROR,
                subject, location, "m", messageArgs, evidence, occurrences);
    }
}
