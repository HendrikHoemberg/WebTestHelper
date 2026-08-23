package dev.hendrikhoemberg.webtesthelper.findings;

import dev.hendrikhoemberg.webtesthelper.model.CheckFinding;
import dev.hendrikhoemberg.webtesthelper.model.CheckType;
import dev.hendrikhoemberg.webtesthelper.model.Evidence;
import dev.hendrikhoemberg.webtesthelper.model.NormalizedUrl;
import dev.hendrikhoemberg.webtesthelper.model.Severity;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class FindingMaterializerTest {

    private static final long SITE_ID = 42L;
    private static final int THRESHOLD = 5;

    private static NormalizedUrl page(String path) {
        return new NormalizedUrl("https", "example.com", 443, path, null);
    }

    private static CheckFinding finding(CheckType type, String subject, String pagePath,
            Severity severity, String messageKey) {
        return new CheckFinding(type, severity, subject, page(pagePath), messageKey, List.of(), Evidence.NONE);
    }

    private static CheckFinding siteFinding(CheckType type, String subject, Severity severity,
            String messageKey) {
        return new CheckFinding(type, severity, subject, null, messageKey, List.of(), Evidence.NONE);
    }

    private static List<MaterialisedFinding> forSubject(CheckType type, String subject,
            List<MaterialisedFinding> findings) {
        return findings.stream()
                .filter(f -> f.type() == type && f.subjectKey().equals(subject))
                .toList();
    }

    @Test
    void subjectOnFourPagesStaysPerPageBelowThreshold() {
        List<CheckFinding> input = List.of(
                finding(CheckType.DEAD_LINK, "https://e.com/a", "/a", Severity.ERROR, "m"),
                finding(CheckType.DEAD_LINK, "https://e.com/a", "/b", Severity.ERROR, "m"),
                finding(CheckType.DEAD_LINK, "https://e.com/a", "/c", Severity.ERROR, "m"),
                finding(CheckType.DEAD_LINK, "https://e.com/a", "/d", Severity.ERROR, "m"));

        List<MaterialisedFinding> result = FindingMaterializer.materialise(SITE_ID, input, THRESHOLD);

        assertThat(result).hasSize(4);
        for (MaterialisedFinding f : result) {
            assertThat(f.locationKey()).isIn("/a", "/b", "/c", "/d");
            assertThat(f.occurrences()).hasSize(1);
            assertThat(f.pageCount()).isEqualTo(1);
        }
    }

    @Test
    void boundaryFivePagesStillStaysPerPage() {
        List<CheckFinding> input = List.of(
                finding(CheckType.DEAD_LINK, "https://e.com/a", "/a", Severity.ERROR, "m"),
                finding(CheckType.DEAD_LINK, "https://e.com/a", "/b", Severity.ERROR, "m"),
                finding(CheckType.DEAD_LINK, "https://e.com/a", "/c", Severity.ERROR, "m"),
                finding(CheckType.DEAD_LINK, "https://e.com/a", "/d", Severity.ERROR, "m"),
                finding(CheckType.DEAD_LINK, "https://e.com/a", "/e", Severity.ERROR, "m"));

        List<MaterialisedFinding> result = FindingMaterializer.materialise(SITE_ID, input, THRESHOLD);

        assertThat(result).hasSize(5);
        assertThat(result).allMatch(f -> !"*".equals(f.locationKey()));
    }

    @Test
    void subjectOnSixPagesIsPromotedToSiteWide() {
        List<CheckFinding> input = List.of(
                finding(CheckType.DEAD_LINK, "https://e.com/a", "/a", Severity.ERROR, "m"),
                finding(CheckType.DEAD_LINK, "https://e.com/a", "/b", Severity.ERROR, "m"),
                finding(CheckType.DEAD_LINK, "https://e.com/a", "/c", Severity.ERROR, "m"),
                finding(CheckType.DEAD_LINK, "https://e.com/a", "/d", Severity.ERROR, "m"),
                finding(CheckType.DEAD_LINK, "https://e.com/a", "/e", Severity.ERROR, "m"),
                finding(CheckType.DEAD_LINK, "https://e.com/a", "/f", Severity.ERROR, "m"));

        List<MaterialisedFinding> result = FindingMaterializer.materialise(SITE_ID, input, THRESHOLD);

        assertThat(result).hasSize(1);
        MaterialisedFinding f = result.get(0);
        assertThat(f.locationKey()).isEqualTo("*");
        assertThat(f.occurrences()).hasSize(6);
        assertThat(f.pageCount()).isEqualTo(6);
        assertThat(f.fingerprint()).isEqualTo(
                Fingerprint.of(SITE_ID, CheckType.DEAD_LINK, "https://e.com/a", "*"));
    }

    @Test
    void promotionIsDecidedPerSubject() {
        List<CheckFinding> input = List.of(
                finding(CheckType.DEAD_LINK, "https://e.com/a", "/a", Severity.ERROR, "m"),
                finding(CheckType.DEAD_LINK, "https://e.com/a", "/b", Severity.ERROR, "m"),
                finding(CheckType.HREFLANG, "https://e.com/b", "/x", Severity.WARN, "m"),
                finding(CheckType.HREFLANG, "https://e.com/b", "/y", Severity.WARN, "m"));

        List<MaterialisedFinding> result = FindingMaterializer.materialise(SITE_ID, input, THRESHOLD);

        assertThat(result).hasSize(4);
        List<MaterialisedFinding> subjectB = forSubject(CheckType.HREFLANG, "https://e.com/b", result);
        assertThat(subjectB).hasSize(2);
        assertThat(subjectB).allMatch(f -> !"*".equals(f.locationKey()));
        assertThat(subjectB.stream().map(MaterialisedFinding::locationKey)).containsExactlyInAnyOrder("/x", "/y");
    }

    @Test
    void siteScopedFindingHasWildcardLocationAndNullPageUrl() {
        List<CheckFinding> input = List.of(
                siteFinding(CheckType.CONSOLE_ERRORS, "https://e.com", Severity.ERROR, "m"));

        List<MaterialisedFinding> result = FindingMaterializer.materialise(SITE_ID, input, THRESHOLD);

        assertThat(result).hasSize(1);
        MaterialisedFinding f = result.get(0);
        assertThat(f.locationKey()).isEqualTo("*");
        assertThat(f.occurrences()).hasSize(1);
        assertThat(f.occurrences().get(0).pageUrl()).isNull();
    }

    @Test
    void identicalFindingsOnOnePageCollapseToSingleOccurrence() {
        List<CheckFinding> input = List.of(
                finding(CheckType.MEDIA_PLAYABLE, "https://e.com/v", "/p", Severity.WARN, "no-source"),
                finding(CheckType.MEDIA_PLAYABLE, "https://e.com/v", "/p", Severity.WARN, "no-source"));

        List<MaterialisedFinding> result = FindingMaterializer.materialise(SITE_ID, input, THRESHOLD);

        assertThat(result).hasSize(1);
        MaterialisedFinding f = result.get(0);
        assertThat(f.locationKey()).isEqualTo("/p");
        assertThat(f.occurrences()).hasSize(1);
    }

    @Test
    void findingCarriesSeverityMaxWhileEachOccurrenceKeepsItsOwn() {
        List<CheckFinding> input = List.of(
                finding(CheckType.DEAD_LINK, "https://e.com/a", "/a", Severity.INFO, "m"),
                finding(CheckType.DEAD_LINK, "https://e.com/a", "/b", Severity.WARN, "m"),
                finding(CheckType.DEAD_LINK, "https://e.com/a", "/c", Severity.ERROR, "m"),
                finding(CheckType.DEAD_LINK, "https://e.com/a", "/d", Severity.WARN, "m"),
                finding(CheckType.DEAD_LINK, "https://e.com/a", "/e", Severity.INFO, "m"),
                finding(CheckType.DEAD_LINK, "https://e.com/a", "/f", Severity.WARN, "m"));

        List<MaterialisedFinding> result = FindingMaterializer.materialise(SITE_ID, input, THRESHOLD);

        assertThat(result).hasSize(1);
        MaterialisedFinding f = result.get(0);
        assertThat(f.severity()).isEqualTo(Severity.ERROR);
        assertThat(f.occurrences()).extracting(FindingOccurrence::severity)
                .contains(Severity.ERROR, Severity.WARN, Severity.INFO);
    }

    @Test
    void representativeMessageIsHighestSeverityTieBrokenByLowestPageUrl() {
        List<CheckFinding> input = List.of(
                finding(CheckType.DEAD_LINK, "https://e.com/a", "/a", Severity.ERROR, "headline_a"),
                finding(CheckType.DEAD_LINK, "https://e.com/a", "/b", Severity.ERROR, "headline_b"),
                finding(CheckType.DEAD_LINK, "https://e.com/a", "/c", Severity.WARN, "w"),
                finding(CheckType.DEAD_LINK, "https://e.com/a", "/d", Severity.INFO, "i"),
                finding(CheckType.DEAD_LINK, "https://e.com/a", "/e", Severity.WARN, "w"),
                finding(CheckType.DEAD_LINK, "https://e.com/a", "/f", Severity.INFO, "i"));

        List<MaterialisedFinding> result = FindingMaterializer.materialise(SITE_ID, input, THRESHOLD);

        assertThat(result).hasSize(1);
        MaterialisedFinding f = result.get(0);
        assertThat(f.severity()).isEqualTo(Severity.ERROR);
        assertThat(f.messageKey()).isEqualTo("headline_a");
    }

    @Test
    void materialisingTheSameInputTwiceIsEqualAndOrdered() {
        List<CheckFinding> input = List.of(
                finding(CheckType.DEAD_LINK, "https://e.com/a", "/b", Severity.ERROR, "m"),
                finding(CheckType.DEAD_LINK, "https://e.com/a", "/a", Severity.ERROR, "m"),
                finding(CheckType.HREFLANG, "https://e.com/b", "/x", Severity.WARN, "m"),
                finding(CheckType.DEAD_LINK, "https://e.com/a", "/c", Severity.ERROR, "m"));

        List<MaterialisedFinding> first = FindingMaterializer.materialise(SITE_ID, input, THRESHOLD);
        List<MaterialisedFinding> second = FindingMaterializer.materialise(SITE_ID, input, THRESHOLD);

        assertThat(second).isEqualTo(first);
        assertThat(second).containsExactlyElementsOf(first);
    }

    @Test
    void mixedSiteAndPageScopedErrorsPromoteAndKeepNullPageUrlOccurrence() {
        List<CheckFinding> input = List.of(
                siteFinding(CheckType.CONSOLE_ERRORS, "https://e.com", Severity.ERROR, "site-headline"),
                finding(CheckType.CONSOLE_ERRORS, "https://e.com", "/a", Severity.ERROR, "page-headline"),
                finding(CheckType.CONSOLE_ERRORS, "https://e.com", "/b", Severity.ERROR, "page-headline"),
                finding(CheckType.CONSOLE_ERRORS, "https://e.com", "/c", Severity.ERROR, "page-headline"),
                finding(CheckType.CONSOLE_ERRORS, "https://e.com", "/d", Severity.ERROR, "page-headline"),
                finding(CheckType.CONSOLE_ERRORS, "https://e.com", "/e", Severity.ERROR, "page-headline"),
                finding(CheckType.CONSOLE_ERRORS, "https://e.com", "/f", Severity.ERROR, "page-headline"));

        List<MaterialisedFinding> result = FindingMaterializer.materialise(SITE_ID, input, THRESHOLD);

        assertThat(result).hasSize(1);
        MaterialisedFinding f = result.get(0);
        assertThat(f.locationKey()).isEqualTo("*");
        assertThat(f.pageCount()).isEqualTo(7);
        assertThat(f.occurrences()).extracting(FindingOccurrence::pageUrl)
                .containsExactly(null, "https://example.com/a", "https://example.com/b",
                        "https://example.com/c", "https://example.com/d", "https://example.com/e",
                        "https://example.com/f");
        assertThat(f.occurrences()).filteredOn(o -> o.pageUrl() == null)
                .singleElement()
                .extracting(FindingOccurrence::severity)
                .isEqualTo(Severity.ERROR);
        assertThat(f.severity()).isEqualTo(Severity.ERROR);
        assertThat(f.messageKey()).isEqualTo("site-headline");
        assertThat(f.fingerprint()).isEqualTo(
                Fingerprint.of(SITE_ID, CheckType.CONSOLE_ERRORS, "https://e.com", "*"));
    }

    @Test
    void emptyInputYieldsEmptyList() {
        List<MaterialisedFinding> result =
                FindingMaterializer.materialise(SITE_ID, List.of(), THRESHOLD);
        assertThat(result).isEmpty();
    }
}
