package dev.hendrikhoemberg.webtesthelper.checks;

import dev.hendrikhoemberg.webtesthelper.checks.Clickables.Clickable;
import dev.hendrikhoemberg.webtesthelper.model.NormalizedUrl;
import dev.hendrikhoemberg.webtesthelper.support.Snapshots;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ClickablesTest {

    private static final NormalizedUrl BASE = Snapshots.url("https://example.com/start");

    @Nested
    class Exclusions {

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

    @Nested
    class Keepers {

        @Test
        void plainButtonIsKept() {
            List<Clickable> harvested = List.of(
                    new Clickable(0, "button", "button", "Mehr erfahren", null, false, false, true, null)
            );

            List<Clickable> selected = Clickables.select(harvested, BASE, 10);

            assertThat(selected).containsExactly(
                    new Clickable(0, "button", "button", "Mehr erfahren", null, false, false, true, null)
            );
        }

        @Test
        void anchorWithHashHrefIsKept() {
            List<Clickable> harvested = List.of(
                    new Clickable(0, "a", null, "Menü öffnen", "#", false, false, true, null)
            );

            List<Clickable> selected = Clickables.select(harvested, BASE, 10);

            assertThat(selected).containsExactly(
                    new Clickable(0, "a", null, "Menü öffnen", "#", false, false, true, null)
            );
        }

        @Test
        void anchorWithJavascriptHrefIsKept() {
            List<Clickable> harvested = List.of(
                    new Clickable(0, "a", null, "Details anzeigen", "javascript:void(0)", false, false, true, null)
            );

            List<Clickable> selected = Clickables.select(harvested, BASE, 10);

            assertThat(selected).containsExactly(
                    new Clickable(0, "a", null, "Details anzeigen", "javascript:void(0)", false, false, true, null)
            );
        }

        @Test
        void anchorResolvingToCurrentPageIsKept() {
            List<Clickable> harvested = List.of(
                    new Clickable(0, "a", null, "Aktualisieren", "/start", false, false, true, null),
                    new Clickable(1, "a", null, "Abschnitt", "https://example.com/start#details", false, false, true, null)
            );

            List<Clickable> selected = Clickables.select(harvested, BASE, 10);

            assertThat(selected).hasSize(2);
        }

        @Test
        void anchorWithNoOrEmptyHrefIsKept() {
            List<Clickable> harvested = List.of(
                    new Clickable(0, "a", null, "Akkordeon", null, false, false, true, null),
                    new Clickable(1, "a", null, "Filter", "", false, false, true, null)
            );

            List<Clickable> selected = Clickables.select(harvested, BASE, 10);

            assertThat(selected).hasSize(2);
        }
    }

    @Nested
    class OrderingAndDeduplication {

        @Test
        void maxTruncatesInDocumentOrder() {
            List<Clickable> harvested = List.of(
                    new Clickable(0, "button", "button", "Btn 0", null, false, false, true, null),
                    new Clickable(1, "button", "button", "Btn 1", null, false, false, true, null),
                    new Clickable(2, "button", "button", "Btn 2", null, false, false, true, null),
                    new Clickable(3, "button", "button", "Btn 3", null, false, false, true, null)
            );

            List<Clickable> selected = Clickables.select(harvested, BASE, 2);

            assertThat(selected).containsExactly(
                    new Clickable(0, "button", "button", "Btn 0", null, false, false, true, null),
                    new Clickable(1, "button", "button", "Btn 1", null, false, false, true, null)
            );
        }

        @Test
        void shuffledInputProducesSameListInDocumentOrder() {
            List<Clickable> harvested = List.of(
                    new Clickable(0, "button", "button", "Erster", null, false, false, true, null),
                    new Clickable(1, "button", "button", "Zweiter", null, false, false, true, null),
                    new Clickable(2, "button", "button", "Dritter", null, false, false, true, null),
                    new Clickable(3, "button", "button", "Vierter", null, false, false, true, null)
            );

            List<Clickable> shuffled = new ArrayList<>(harvested);
            Collections.shuffle(shuffled);

            List<Clickable> selectedOriginal = Clickables.select(harvested, BASE, 10);
            List<Clickable> selectedShuffled = Clickables.select(shuffled, BASE, 10);

            assertThat(selectedShuffled).containsExactlyElementsOf(selectedOriginal);
            assertThat(selectedShuffled).extracting(Clickable::index).isSorted();
        }

        @Test
        void deduplicationByLabelAndIndex() {
            List<Clickable> harvested = List.of(
                    new Clickable(0, "button", "button", "Mehr", null, false, false, true, null),
                    new Clickable(0, "button", "button", "Mehr", null, false, false, true, null),
                    new Clickable(1, "button", "button", "Mehr", null, false, false, true, null)
            );

            List<Clickable> selected = Clickables.select(harvested, BASE, 10);

            assertThat(selected).containsExactly(
                    new Clickable(0, "button", "button", "Mehr", null, false, false, true, null),
                    new Clickable(1, "button", "button", "Mehr", null, false, false, true, null)
            );
        }

        @Test
        void nullOrEmptyOrNegativeReturnsEmpty() {
            assertThat(Clickables.select(null, BASE, 5)).isEmpty();
            assertThat(Clickables.select(List.of(), BASE, 5)).isEmpty();
            assertThat(Clickables.select(List.of(new Clickable(0, "button", "button", "Mehr", null, false, false, true, null)), null, 5)).isEmpty();
            assertThat(Clickables.select(List.of(new Clickable(0, "button", "button", "Mehr", null, false, false, true, null)), BASE, 0)).isEmpty();
            assertThat(Clickables.select(List.of(new Clickable(0, "button", "button", "Mehr", null, false, false, true, null)), BASE, -1)).isEmpty();
        }
    }
}
