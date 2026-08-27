package dev.hendrikhoemberg.webtesthelper.checks;

import dev.hendrikhoemberg.webtesthelper.checks.LanguageSwitchers.LocaleLink;
import dev.hendrikhoemberg.webtesthelper.checks.LanguageSwitchers.LocaleTarget;
import dev.hendrikhoemberg.webtesthelper.model.NormalizedUrl;
import dev.hendrikhoemberg.webtesthelper.support.Snapshots;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Which harvested anchors are a language switch (plan 11, Task 1).
 *
 * <p>A sibling top-level class rather than a {@code @Nested} one: CLAUDE.md forbids
 * {@code @Nested}, because surefire's directory scanner is configured by filename and an
 * inner class it declines to walk into is reported as a passing {@code Tests run: 0}.
 */
class LanguageSwitchersSelectionTest {

    private static final NormalizedUrl BASE_DE = Snapshots.url("https://example.com/de/start");
    private static final NormalizedUrl BASE_ROOT = Snapshots.url("https://example.com/");

    @Test
    void hreflangLinkIsCandidate() {
        List<LocaleLink> links = List.of(
                new LocaleLink(0, "/en/start", "en", "Click here", true)
        );

        List<LocaleTarget> targets = LanguageSwitchers.select(links, BASE_DE, "de", 5);

        assertThat(targets).containsExactly(
                new LocaleTarget(0, Snapshots.url("https://example.com/en/start"), "Click here")
        );
    }

    @Test
    void plainStartseiteLinkIsNotCandidate() {
        List<LocaleLink> links = List.of(
                new LocaleLink(0, "/startseite", null, "Startseite", true)
        );

        List<LocaleTarget> targets = LanguageSwitchers.select(links, BASE_DE, "de", 5);

        assertThat(targets).isEmpty();
    }

    @Test
    void deutschlandIsNotLanguageMatchWholeLabelRequired() {
        List<LocaleLink> links = List.of(
                new LocaleLink(0, "/germany", null, "Deutschland", true)
        );

        List<LocaleTarget> targets = LanguageSwitchers.select(links, BASE_DE, "de", 5);

        assertThat(targets).isEmpty();
    }

    @Test
    void francaisAndFRANCAISBothMatchVocabulary() {
        List<LocaleLink> links = List.of(
                new LocaleLink(0, "/page-fr1", null, "Français", true),
                new LocaleLink(1, "/page-fr2", null, "FRANCAIS", true)
        );

        List<LocaleTarget> targets = LanguageSwitchers.select(links, BASE_DE, "de", 5);

        assertThat(targets).extracting(LocaleTarget::label)
                .containsExactly("Français", "FRANCAIS");
    }

    @Test
    void allVocabularyWordsMatchCaseAndDiacriticInsensitively() {
        List<LocaleLink> links = List.of(
                new LocaleLink(0, "/p1", null, "Deutsch", true),
                new LocaleLink(1, "/p2", null, "GERMAN", true),
                new LocaleLink(2, "/p3", null, "de", true),
                new LocaleLink(3, "/p4", null, "English", true),
                new LocaleLink(4, "/p5", null, "Englisch", true),
                new LocaleLink(5, "/p6", null, "EN", true),
                new LocaleLink(6, "/p7", null, "Französisch", true),
                new LocaleLink(7, "/p8", null, "Italiano", true),
                new LocaleLink(8, "/p9", null, "Italienisch", true),
                new LocaleLink(9, "/p10", null, "IT", true),
                new LocaleLink(10, "/p11", null, "Español", true),
                new LocaleLink(11, "/p12", null, "Spanisch", true),
                new LocaleLink(12, "/p13", null, "ES", true),
                new LocaleLink(13, "/p14", null, "Nederlands", true),
                new LocaleLink(14, "/p15", null, "Niederländisch", true),
                new LocaleLink(15, "/p16", null, "NL", true),
                new LocaleLink(16, "/p17", null, "Polski", true),
                new LocaleLink(17, "/p18", null, "Polnisch", true),
                new LocaleLink(18, "/p19", null, "PL", true)
        );

        List<LocaleTarget> targets = LanguageSwitchers.select(links, BASE_ROOT, "fr", 20);

        assertThat(targets).hasSize(19);
    }

    @Test
    void hrefWithFirstPathSegmentTwoLetterCodeIsCandidate() {
        List<LocaleLink> links = List.of(
                new LocaleLink(0, "/en/about", null, "Unknown Label", true),
                new LocaleLink(1, "/fr", null, "Another Label", true),
                new LocaleLink(2, "https://example.com/es/home", null, "Custom", true)
        );

        List<LocaleTarget> targets = LanguageSwitchers.select(links, BASE_ROOT, "de", 5);

        assertThat(targets).hasSize(3);
    }

    @Test
    void hrefWithQueryParamLangHlLocaleIsCandidate() {
        List<LocaleLink> links = List.of(
                new LocaleLink(0, "/page?lang=en", null, "About", true),
                new LocaleLink(1, "/page?hl=fr", null, "Services", true),
                new LocaleLink(2, "/page?locale=es_ES", null, "Contact", true)
        );

        List<LocaleTarget> targets = LanguageSwitchers.select(links, BASE_ROOT, "de", 5);

        assertThat(targets).hasSize(3);
    }

    @Test
    void invisibleLinksAreDropped() {
        List<LocaleLink> links = List.of(
                new LocaleLink(0, "/en/start", "en", "English", false),
                new LocaleLink(1, "/fr/start", "fr", "Français", true)
        );

        List<LocaleTarget> targets = LanguageSwitchers.select(links, BASE_DE, "de", 5);

        assertThat(targets).containsExactly(
                new LocaleTarget(1, Snapshots.url("https://example.com/fr/start"), "Français")
        );
    }

    @Test
    void crossSiteLinkIsDropped() {
        List<LocaleLink> links = List.of(
                new LocaleLink(0, "https://partner-domain.com/en", "en", "English", true),
                new LocaleLink(1, "https://example.com/en", "en", "English", true)
        );

        List<LocaleTarget> targets = LanguageSwitchers.select(links, BASE_ROOT, "de", 5);

        assertThat(targets).containsExactly(
                new LocaleTarget(1, Snapshots.url("https://example.com/en"), "English")
        );
    }

    @Test
    void selfLinkWhoseHreflangIsCurrentLangIsDropped() {
        List<LocaleLink> links = List.of(
                new LocaleLink(0, "/de/start", "de", "Deutsch", true),
                new LocaleLink(1, "/en/start", "en", "English", true)
        );

        List<LocaleTarget> targets = LanguageSwitchers.select(links, BASE_DE, "de", 5);

        assertThat(targets).containsExactly(
                new LocaleTarget(1, Snapshots.url("https://example.com/en/start"), "English")
        );
    }

    @Test
    void selfLinkWhoseLabelLanguageIsCurrentLangIsDroppedEvenWithoutHreflang() {
        List<LocaleLink> links = List.of(
                new LocaleLink(0, "/de/start", null, "Deutsch", true),
                new LocaleLink(1, "/en/start", null, "English", true)
        );

        List<LocaleTarget> targets = LanguageSwitchers.select(links, BASE_DE, "de", 5);

        assertThat(targets).containsExactly(
                new LocaleTarget(1, Snapshots.url("https://example.com/en/start"), "English")
        );
    }

    @Test
    void selfLinkLabelledEnglishOnGermanPageIsKeptAsDeadSwitcherCandidate() {
        List<LocaleLink> links = List.of(
                new LocaleLink(0, "/de/start", null, "English", true)
        );

        List<LocaleTarget> targets = LanguageSwitchers.select(links, BASE_DE, "de", 5);

        assertThat(targets).containsExactly(
                new LocaleTarget(0, BASE_DE, "English")
        );
    }

    @Test
    void selfLinkWithHreflangEnOnGermanPageIsKeptAsDeadSwitcherCandidate() {
        List<LocaleLink> links = List.of(
                new LocaleLink(0, "/de/start", "en", "Switch", true)
        );

        List<LocaleTarget> targets = LanguageSwitchers.select(links, BASE_DE, "de", 5);

        assertThat(targets).containsExactly(
                new LocaleTarget(0, BASE_DE, "Switch")
        );
    }

    @Test
    void twoLinksToSameTargetYieldOneTargetKeepingLowestIndex() {
        List<LocaleLink> links = List.of(
                new LocaleLink(5, "/en", "en", "English (footer)", true),
                new LocaleLink(1, "/en", "en", "EN (header)", true),
                new LocaleLink(3, "/en", "en", "English (nav)", true)
        );

        List<LocaleTarget> targets = LanguageSwitchers.select(links, BASE_ROOT, "de", 5);

        assertThat(targets).containsExactly(
                new LocaleTarget(1, Snapshots.url("https://example.com/en"), "EN (header)")
        );
    }

    @Test
    void shuffledInputYieldsSameListInSameOrder() {
        List<LocaleLink> links = List.of(
                new LocaleLink(0, "/pl", "pl", "Polski", true),
                new LocaleLink(1, "/en", "en", "English", true),
                new LocaleLink(2, "/fr", "fr", "Français", true),
                new LocaleLink(3, "/es", "es", "Español", true),
                new LocaleLink(4, "/it", "it", "Italiano", true)
        );

        List<LocaleLink> shuffled = new ArrayList<>(links);
        Collections.shuffle(shuffled);

        List<LocaleTarget> originalTargets = LanguageSwitchers.select(links, BASE_ROOT, "de", 10);
        List<LocaleTarget> shuffledTargets = LanguageSwitchers.select(shuffled, BASE_ROOT, "de", 10);

        assertThat(originalTargets).containsExactlyElementsOf(shuffledTargets);
        assertThat(originalTargets).extracting(target -> target.url().value())
                .isSorted();
    }

    @Test
    void maxTruncatesAfterSortingNotBefore() {
        List<LocaleLink> links = List.of(
                new LocaleLink(0, "/zz-lang", "zz", "ZZ", true),
                new LocaleLink(1, "/aa-lang", "aa", "AA", true),
                new LocaleLink(2, "/mm-lang", "mm", "MM", true)
        );

        // /aa-lang, /mm-lang, /zz-lang in value order. Truncating to 2 must yield aa and mm.
        List<LocaleTarget> targets = LanguageSwitchers.select(links, BASE_ROOT, "de", 2);

        assertThat(targets).containsExactly(
                new LocaleTarget(1, Snapshots.url("https://example.com/aa-lang"), "AA"),
                new LocaleTarget(2, Snapshots.url("https://example.com/mm-lang"), "MM")
        );
    }

    @Test
    void maxZeroOrNegativeReturnsEmpty() {
        List<LocaleLink> links = List.of(
                new LocaleLink(0, "/en", "en", "English", true)
        );

        assertThat(LanguageSwitchers.select(links, BASE_ROOT, "de", 0)).isEmpty();
        assertThat(LanguageSwitchers.select(links, BASE_ROOT, "de", -1)).isEmpty();
    }

    @Test
    void nullOrEmptyInputReturnsEmpty() {
        assertThat(LanguageSwitchers.select(null, BASE_ROOT, "de", 5)).isEmpty();
        assertThat(LanguageSwitchers.select(List.of(), BASE_ROOT, "de", 5)).isEmpty();
        assertThat(LanguageSwitchers.select(List.of(), null, "de", 5)).isEmpty();
    }
}
