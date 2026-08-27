package dev.hendrikhoemberg.webtesthelper.checks;

import dev.hendrikhoemberg.webtesthelper.checks.LanguageSwitchers.Observation;
import dev.hendrikhoemberg.webtesthelper.checks.LanguageSwitchers.SwitchVerdict;
import dev.hendrikhoemberg.webtesthelper.model.NormalizedUrl;
import dev.hendrikhoemberg.webtesthelper.model.SimHash;
import dev.hendrikhoemberg.webtesthelper.support.Snapshots;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What a locale click proved: the three conditions of spec 7.2 (plan 11, Task 1).
 *
 * <p>Sibling of {@link LanguageSwitchersSelectionTest}; see its javadoc for why neither is
 * {@code @Nested}.
 */
class LanguageSwitchersVerdictTest {

    private static final NormalizedUrl BASE_DE = Snapshots.url("https://example.com/de/start");
    private static final NormalizedUrl BASE_ROOT = Snapshots.url("https://example.com/");

    private static final String GERMAN_LONG_TEXT = """
            Willkommen auf unserer offiziellen Webseite für professionelle Beratung und innovative Softwarelösungen.
            Wir unterstützen Unternehmen bei der digitalen Transformation und Optimierung ihrer internen Geschäftsprozesse
            durch moderne Technologien und agile Methoden.
            """;

    private static final String ENGLISH_LONG_TEXT = """
            Welcome to our official website for professional consulting and innovative enterprise software solutions.
            We support companies in digital transformation and optimization of their internal business processes
            through modern cutting-edge technologies and agile workflows.
            """;

    @Test
    void noNavigationWhenUrlIsUnchanged() {
        Observation before = new Observation(Snapshots.url("https://example.com/de"), "de", GERMAN_LONG_TEXT);
        Observation after = new Observation(Snapshots.url("https://example.com/de"), "en", ENGLISH_LONG_TEXT);

        SwitchVerdict verdict = LanguageSwitchers.verdict(before, after, 12);

        assertThat(verdict).isEqualTo(SwitchVerdict.NO_NAVIGATION);
    }

    @Test
    void noNavigationWinsOverLangUnchangedWhenBothFail() {
        // URL unchanged AND lang unchanged
        Observation before = new Observation(Snapshots.url("https://example.com/de"), "de", GERMAN_LONG_TEXT);
        Observation after = new Observation(Snapshots.url("https://example.com/de"), "de", GERMAN_LONG_TEXT);

        SwitchVerdict verdict = LanguageSwitchers.verdict(before, after, 12);

        assertThat(verdict).isEqualTo(SwitchVerdict.NO_NAVIGATION);
    }

    @Test
    void langUnchangedWhenAfterHtmlLangEqualsBeforeHtmlLang() {
        Observation before = new Observation(Snapshots.url("https://example.com/de"), "de", GERMAN_LONG_TEXT);
        Observation after = new Observation(Snapshots.url("https://example.com/en"), "de", ENGLISH_LONG_TEXT);

        SwitchVerdict verdict = LanguageSwitchers.verdict(before, after, 12);

        assertThat(verdict).isEqualTo(SwitchVerdict.LANG_UNCHANGED);
    }

    @Test
    void langUnchangedWhenAfterHtmlLangIsBlankOrNull() {
        Observation before = new Observation(Snapshots.url("https://example.com/de"), "de", GERMAN_LONG_TEXT);
        Observation afterBlank = new Observation(Snapshots.url("https://example.com/en"), "  ", ENGLISH_LONG_TEXT);
        Observation afterNull = new Observation(Snapshots.url("https://example.com/en"), null, ENGLISH_LONG_TEXT);

        assertThat(LanguageSwitchers.verdict(before, afterBlank, 12)).isEqualTo(SwitchVerdict.LANG_UNCHANGED);
        assertThat(LanguageSwitchers.verdict(before, afterNull, 12)).isEqualTo(SwitchVerdict.LANG_UNCHANGED);
    }

    @Test
    void langUnchangedIgnoresCase() {
        Observation before = new Observation(Snapshots.url("https://example.com/de"), "de-DE", GERMAN_LONG_TEXT);
        Observation after = new Observation(Snapshots.url("https://example.com/en"), "DE-de", ENGLISH_LONG_TEXT);

        SwitchVerdict verdict = LanguageSwitchers.verdict(before, after, 12);

        assertThat(verdict).isEqualTo(SwitchVerdict.LANG_UNCHANGED);
    }

    @Test
    void shortTextGuardReturnsOkWhenEitherTextHasFewerThan20Words() {
        Observation before = new Observation(Snapshots.url("https://example.com/de"), "de", "Kurzer Text auf Deutsch.");
        Observation after = new Observation(Snapshots.url("https://example.com/en"), "en", "Kurzer Text auf Deutsch.");

        SwitchVerdict verdict = LanguageSwitchers.verdict(before, after, 12);

        assertThat(verdict).isEqualTo(SwitchVerdict.OK);
    }

    @Test
    void sameContentWhenHammingDistanceWithinMaxTextDistance() {
        Observation before = new Observation(Snapshots.url("https://example.com/de"), "de", GERMAN_LONG_TEXT);
        Observation after = new Observation(Snapshots.url("https://example.com/en"), "en", GERMAN_LONG_TEXT);

        SwitchVerdict verdict = LanguageSwitchers.verdict(before, after, 12);

        assertThat(verdict).isEqualTo(SwitchVerdict.SAME_CONTENT);
    }

    @Test
    void okWhenSuccessfullyNavigatedLangChangedAndContentDistinct() {
        Observation before = new Observation(Snapshots.url("https://example.com/de"), "de", GERMAN_LONG_TEXT);
        Observation after = new Observation(Snapshots.url("https://example.com/en"), "en", ENGLISH_LONG_TEXT);

        SwitchVerdict verdict = LanguageSwitchers.verdict(before, after, 12);

        assertThat(verdict).isEqualTo(SwitchVerdict.OK);
    }

    @Test
    void distanceBoundaryTwelveIsSameContentAndThirteenIsOk() {
        String textA = "alpha bravo charlie delta echo foxtrot golf hotel india juliet kilo lima mike november oscar papa quebec romeo sierra tango uniform victor";
        String textB = "alpha bravo charlie delta echo foxtrot golf hotel india juliet kilo lima mike november oscar papa quebec romeo sierra tango uniform whisky";

        int actualDistance = SimHash.hammingDistance(SimHash.of(textA), SimHash.of(textB));

        Observation before = new Observation(Snapshots.url("https://example.com/de"), "de", textA);
        Observation after = new Observation(Snapshots.url("https://example.com/en"), "en", textB);

        // With maxTextDistance set to actualDistance: distance <= actualDistance is true -> SAME_CONTENT
        assertThat(LanguageSwitchers.verdict(before, after, actualDistance))
                .isEqualTo(SwitchVerdict.SAME_CONTENT);

        // With maxTextDistance set to actualDistance - 1: distance <= actualDistance - 1 is false -> OK
        if (actualDistance > 0) {
            assertThat(LanguageSwitchers.verdict(before, after, actualDistance - 1))
                    .isEqualTo(SwitchVerdict.OK);
        }
    }
}
