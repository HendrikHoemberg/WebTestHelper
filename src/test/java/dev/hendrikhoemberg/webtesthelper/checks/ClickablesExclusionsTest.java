package dev.hendrikhoemberg.webtesthelper.checks;

import dev.hendrikhoemberg.webtesthelper.checks.Clickables.Clickable;
import dev.hendrikhoemberg.webtesthelper.model.NormalizedUrl;
import dev.hendrikhoemberg.webtesthelper.support.Snapshots;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The controls this system refuses to click (plan 11, Task 3, D85). A bug here is
 * "the checker bought something on a customer's website", so every rule is asserted by
 * absence from the candidate list.
 *
 * <p>A sibling top-level class rather than a {@code @Nested} one: CLAUDE.md forbids
 * {@code @Nested}, because surefire's directory scanner is configured by filename and an
 * inner class it declines to walk into is reported as a passing {@code Tests run: 0}.
 */
class ClickablesExclusionsTest {

    private static final NormalizedUrl BASE = Snapshots.url("https://example.com/start");

    @Test
    void invisibleButtonIsDropped() {
        List<Clickable> harvested = List.of(
                new Clickable(0, "button", "button", "Mehr erfahren", null, false, false, false, null)
        );

        List<Clickable> selected = Clickables.select(harvested, BASE, 10);

        assertThat(selected).isEmpty();
    }

    @Test
    void disabledButtonIsDropped() {
        List<Clickable> harvested = List.of(
                new Clickable(0, "button", "button", "Mehr erfahren", null, false, true, true, null)
        );

        List<Clickable> selected = Clickables.select(harvested, BASE, 10);

        assertThat(selected).isEmpty();
    }

    @Test
    void buttonInsideFormIsDropped() {
        List<Clickable> harvested = List.of(
                new Clickable(0, "button", "button", "Absenden", null, true, false, true, null)
        );

        List<Clickable> selected = Clickables.select(harvested, BASE, 10);

        assertThat(selected).isEmpty();
    }

    @Test
    void inputTypeSubmitIsDropped() {
        List<Clickable> harvested = List.of(
                new Clickable(0, "input", "submit", "Suchen", null, false, false, true, null)
        );

        List<Clickable> selected = Clickables.select(harvested, BASE, 10);

        assertThat(selected).isEmpty();
    }

    @Test
    void inputTypeResetIsDropped() {
        List<Clickable> harvested = List.of(
                new Clickable(0, "input", "reset", "Zurücksetzen", null, false, false, true, null)
        );

        List<Clickable> selected = Clickables.select(harvested, BASE, 10);

        assertThat(selected).isEmpty();
    }

    @Test
    void targetBlankAnchorIsDropped() {
        List<Clickable> harvested = List.of(
                new Clickable(0, "a", null, "Mehr erfahren", "https://example.com/start", false, false, true, "_blank")
        );

        List<Clickable> selected = Clickables.select(harvested, BASE, 10);

        assertThat(selected).isEmpty();
    }

    @Test
    void anchorWhoseHrefGoesToAnotherPageIsDropped() {
        List<Clickable> harvested = List.of(
                new Clickable(0, "a", null, "Weiterlesen", "/other-page", false, false, true, null),
                new Clickable(1, "a", null, "Partnerseite", "https://other.com/start", false, false, true, null)
        );

        List<Clickable> selected = Clickables.select(harvested, BASE, 10);

        assertThat(selected).isEmpty();
    }

    @Test
    void bestellungLoeschenIsDropped() {
        List<Clickable> harvested = List.of(
                new Clickable(0, "button", "button", "Bestellung löschen", null, false, false, true, null)
        );

        List<Clickable> selected = Clickables.select(harvested, BASE, 10);

        assertThat(selected).isEmpty();
    }

    @Test
    void jetztKaufenIsDropped() {
        List<Clickable> harvested = List.of(
                new Clickable(0, "button", "button", "Jetzt kaufen", null, false, false, true, null)
        );

        List<Clickable> selected = Clickables.select(harvested, BASE, 10);

        assertThat(selected).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "Bestellung löschen", "Artikel entfernen", "Delete account", "Remove item",
            "Warenkorb leeren", "Passwort zurücksetzen", "Jetzt abmelden", "Logout now",
            "Hier anmelden", "Login", "Jetzt registrieren", "Newsletter abbestellen",
            "Vertrag kündigen", "Kostenpflichtig bestellen", "Kaufen", "Zahlungspflichtig",
            "In den Warenkorb", "Rechnung bezahlen", "Formular absenden", "Nachricht abschicken",
            "Daten senden", "Submit form", "PDF Download", "Handbuch herunterladen", "Seite drucken"
    })
    void allNeverClickVocabularyTokensAreDropped(String label) {
        List<Clickable> harvested = List.of(
                new Clickable(0, "button", "button", label, null, false, false, true, null)
        );

        List<Clickable> selected = Clickables.select(harvested, BASE, 10);

        assertThat(selected).isEmpty();
    }

    @Test
    void alleAkzeptierenIsDropped() {
        List<Clickable> harvested = List.of(
                new Clickable(0, "button", "button", "Alle akzeptieren", null, false, false, true, null)
        );

        List<Clickable> selected = Clickables.select(harvested, BASE, 10);

        assertThat(selected).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "Alle akzeptieren", "Alle Cookies akzeptieren", "Alle zulassen", "Alle auswählen",
            "Accept all", "Allow all", "Akzeptieren", "Zustimmen", "Einverstanden",
            "Verstanden", "Ich stimme zu", "Accept", "Agree", "OK",
            "Nur notwendige", "Ablehnen"
    })
    void allCookieConsentLabelsAreDropped(String label) {
        List<Clickable> harvested = List.of(
                new Clickable(0, "button", "button", label, null, false, false, true, null)
        );

        List<Clickable> selected = Clickables.select(harvested, BASE, 10);

        assertThat(selected).isEmpty();
    }
}
