package dev.hendrikhoemberg.webtesthelper.recorder;

import dev.hendrikhoemberg.webtesthelper.model.LocatorCandidate;
import dev.hendrikhoemberg.webtesthelper.model.LocatorStrategy;
import dev.hendrikhoemberg.webtesthelper.recorder.CapturedEvent.EventKind;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CandidateBuilderTest {

    @Test
    void rejectsNullEvent() {
        assertThatThrownBy(() -> CandidateBuilder.build(null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void buildsAllSixCandidatesInRankOrderWhenAllArePresentAndValid() {
        CapturedEvent event = new CapturedEvent(
                EventKind.CLICK,
                "button",
                "checkout-btn",
                "btn-checkout-test",
                "button",
                "Abschließen",
                "Kaufabschluss",
                "Jetzt kaufen",
                null,
                "form > div > button.primary"
        );

        List<LocatorCandidate> candidates = CandidateBuilder.build(event);

        assertThat(candidates).containsExactly(
                new LocatorCandidate(LocatorStrategy.TEST_ID, "btn-checkout-test", 0),
                new LocatorCandidate(LocatorStrategy.ROLE, "button[name=\"Abschließen\"]", 0),
                new LocatorCandidate(LocatorStrategy.LABEL, "Kaufabschluss", 0),
                new LocatorCandidate(LocatorStrategy.ID, "checkout-btn", 0),
                new LocatorCandidate(LocatorStrategy.TEXT, "Jetzt kaufen", 0),
                new LocatorCandidate(LocatorStrategy.CSS, "form > div > button.primary", 0)
        );
    }

    @Test
    void roleWithoutAccessibleNameEmitsBareRoleName() {
        CapturedEvent event = new CapturedEvent(
                EventKind.CLICK,
                "button",
                null,
                null,
                "button",
                null,
                null,
                null,
                null,
                "button"
        );

        List<LocatorCandidate> candidates = CandidateBuilder.build(event);

        assertThat(candidates).containsExactly(
                new LocatorCandidate(LocatorStrategy.ROLE, "button", 0),
                new LocatorCandidate(LocatorStrategy.CSS, "button", 0)
        );
    }

    @Test
    void roleWithBlankAccessibleNameEmitsBareRoleName() {
        CapturedEvent event = new CapturedEvent(
                EventKind.CLICK,
                "button",
                null,
                null,
                "button",
                "   ",
                null,
                null,
                null,
                "button"
        );

        List<LocatorCandidate> candidates = CandidateBuilder.build(event);

        assertThat(candidates).containsExactly(
                new LocatorCandidate(LocatorStrategy.ROLE, "button", 0),
                new LocatorCandidate(LocatorStrategy.CSS, "button", 0)
        );
    }

    @Test
    void unauthoredIdIsExcludedFromCandidates() {
        CapturedEvent eventWithFrameworkId = new CapturedEvent(
                EventKind.INPUT,
                "input",
                "react-aria-12345",
                null,
                null,
                null,
                "Vorname",
                null,
                "Max",
                "input.first-name"
        );

        List<LocatorCandidate> candidates = CandidateBuilder.build(eventWithFrameworkId);

        assertThat(candidates).extracting(LocatorCandidate::strategy)
                .containsExactly(LocatorStrategy.LABEL, LocatorStrategy.CSS);
    }

    @Test
    void longHexGeneratedIdIsExcludedFromCandidates() {
        CapturedEvent eventWithHexId = new CapturedEvent(
                EventKind.CLICK,
                "button",
                "a1b2c3d4e5f6",
                null,
                null,
                null,
                null,
                "Klick",
                null,
                "button.btn"
        );

        List<LocatorCandidate> candidates = CandidateBuilder.build(eventWithHexId);

        assertThat(candidates).extracting(LocatorCandidate::strategy)
                .containsExactly(LocatorStrategy.TEXT, LocatorStrategy.CSS);
    }

    @Test
    void authoredIdIsIncludedInCandidates() {
        CapturedEvent eventWithAuthoredId = new CapturedEvent(
                EventKind.CLICK,
                "button",
                "login-submit-btn",
                null,
                null,
                null,
                null,
                null,
                null,
                "#login-submit-btn"
        );

        List<LocatorCandidate> candidates = CandidateBuilder.build(eventWithAuthoredId);

        assertThat(candidates).containsExactly(
                new LocatorCandidate(LocatorStrategy.ID, "login-submit-btn", 0),
                new LocatorCandidate(LocatorStrategy.CSS, "#login-submit-btn", 0)
        );
    }

    @Test
    void textContentExceedingBoundIsNotEmittedAsCandidate() {
        String longParagraph = "Dies ist ein sehr langer Text, der definitiv nicht als Selektor dienen sollte, "
                + "weil er viel mehr als 80 Zeichen umfasst und eher Fließtext ist.";
        assertThat(longParagraph.length()).isGreaterThan(80);

        CapturedEvent eventWithLongText = new CapturedEvent(
                EventKind.CLICK,
                "p",
                null,
                null,
                null,
                null,
                null,
                longParagraph,
                null,
                "p.lead"
        );

        List<LocatorCandidate> candidates = CandidateBuilder.build(eventWithLongText);

        assertThat(candidates).containsExactly(
                new LocatorCandidate(LocatorStrategy.CSS, "p.lead", 0)
        );
    }

    @Test
    void textContentWithinBoundIsEmittedAsCandidate() {
        String shortText = "Konto erstellen";
        assertThat(shortText.length()).isLessThanOrEqualTo(80);

        CapturedEvent eventWithShortText = new CapturedEvent(
                EventKind.CLICK,
                "a",
                null,
                null,
                null,
                null,
                null,
                shortText,
                null,
                "a.signup"
        );

        List<LocatorCandidate> candidates = CandidateBuilder.build(eventWithShortText);

        assertThat(candidates).containsExactly(
                new LocatorCandidate(LocatorStrategy.TEXT, shortText, 0),
                new LocatorCandidate(LocatorStrategy.CSS, "a.signup", 0)
        );
    }

    @Test
    void textContentExactlyAtBoundaryIsEmittedAsCandidate() {
        String boundary80 = "A".repeat(80);

        CapturedEvent eventWith80Chars = new CapturedEvent(
                EventKind.CLICK,
                "div",
                null,
                null,
                null,
                null,
                null,
                boundary80,
                null,
                "div.box"
        );

        List<LocatorCandidate> candidates = CandidateBuilder.build(eventWith80Chars);

        assertThat(candidates).containsExactly(
                new LocatorCandidate(LocatorStrategy.TEXT, boundary80, 0),
                new LocatorCandidate(LocatorStrategy.CSS, "div.box", 0)
        );
    }

    @Test
    void textContentOneAboveBoundaryIsNotEmittedAsCandidate() {
        String boundary81 = "A".repeat(81);

        CapturedEvent eventWith81Chars = new CapturedEvent(
                EventKind.CLICK,
                "div",
                null,
                null,
                null,
                null,
                null,
                boundary81,
                null,
                "div.box"
        );

        List<LocatorCandidate> candidates = CandidateBuilder.build(eventWith81Chars);

        assertThat(candidates).containsExactly(
                new LocatorCandidate(LocatorStrategy.CSS, "div.box", 0)
        );
    }

    @Test
    void eventWithOnlyCssPathYieldsExactlyOneCssCandidate() {
        CapturedEvent event = new CapturedEvent(
                EventKind.CLICK,
                "div",
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                "div.wrapper > span"
        );

        List<LocatorCandidate> candidates = CandidateBuilder.build(event);

        assertThat(candidates).containsExactly(
                new LocatorCandidate(LocatorStrategy.CSS, "div.wrapper > span", 0)
        );
    }

    @Test
    void missingCssPathFallsBackToTagName() {
        CapturedEvent event = new CapturedEvent(
                EventKind.CLICK,
                "button",
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null
        );

        List<LocatorCandidate> candidates = CandidateBuilder.build(event);

        assertThat(candidates).containsExactly(
                new LocatorCandidate(LocatorStrategy.CSS, "button", 0)
        );
    }

    @Test
    void blankCssPathAndBlankTagNameFallsBackToWildcard() {
        CapturedEvent event = new CapturedEvent(
                EventKind.CLICK,
                "   ",
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                "   "
        );

        List<LocatorCandidate> candidates = CandidateBuilder.build(event);

        assertThat(candidates).containsExactly(
                new LocatorCandidate(LocatorStrategy.CSS, "*", 0)
        );
    }

    @Test
    void blankFieldsAreIgnoredAndNotEmitted() {
        CapturedEvent eventWithBlanks = new CapturedEvent(
                EventKind.CLICK,
                "button",
                "   ",
                "   ",
                "   ",
                "   ",
                "   ",
                "   ",
                null,
                "button.btn"
        );

        List<LocatorCandidate> candidates = CandidateBuilder.build(eventWithBlanks);

        assertThat(candidates).containsExactly(
                new LocatorCandidate(LocatorStrategy.CSS, "button.btn", 0)
        );
    }
}
