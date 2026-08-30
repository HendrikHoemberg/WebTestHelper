package dev.hendrikhoemberg.webtesthelper.checks;

import dev.hendrikhoemberg.webtesthelper.model.CheckFinding;
import dev.hendrikhoemberg.webtesthelper.model.CheckType;
import dev.hendrikhoemberg.webtesthelper.model.SimHash;
import dev.hendrikhoemberg.webtesthelper.model.SoftNotFoundProbe;
import dev.hendrikhoemberg.webtesthelper.support.Snapshots;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class PageStatusCheckTest {

    private static final String NOT_FOUND_TEXT =
            "Seite nicht gefunden. Die gewünschte Seite existiert leider nicht. Zur Startseite";

    /**
     * Measured on http://www.theis-feinwerktechnik.de (2026-08-30, real Chromium): the site
     * answers the probe URL and the root URL with the IDENTICAL 930-char shared shell — the
     * d(probe, root) gap is 0. Real pages sit 12–16 bits from that shell (inside the 16-bit
     * cutoff); the old probe-only rule therefore reported 57 real pages as soft 404s. The shell
     * copy is the visible-text fingerprint of the site frame (menu, footer, cookie banner).
     */
    private static final String SHELL = """
            PERSÖNLICH. ZUVERLÄSSIG. MESSBAR GUT.
            VERMESSUNG
            FORMENBAU
            SONDERFERTIGUNG
            Impressum
            Datenschutz
            AGB
            Lieferantenkodex
            Lieferanteninformation
            © 2025 THEIS Feinwerktechnik · Alle Rechte vorbehalten.
            """;

    private static final String BROCHURE = SHELL + """
            Home
            News
            Unternehmen
            Karriere
            Kontakt
            VERMESSUNG
            PRODUKTE
            SERVICE
            DOWNLOAD
            HÄNDLER
            ANFRAGELISTE
            Theis VISION Agriculture Produktbroschüre
            Größe: 1982.25 KB
            Letzte Aktualisierung: 07.01.2020
            Download
            Broschüren
            Anleitungen
            Sonstiges
            """;

    private static final String ROOT_TEXT =
            "Startseite Leistungen Kontakt Medien Gemischte Inhalte Karte (grau) Karte (gesund) "
            + "Karte (spät) Karte (Ebenen) English Seite die es nicht mehr gibt Harte 404 "
            + "Weiterleitungskette Weiterleitungsschleife Handbuch (PDF) Preisliste (angeblich PDF) "
            + "Externer Partner Zeitweise gestörter Partner Externer toter Link Interner Bereich "
            + "Gesperrter Bereich Faule Bilder Langsames Bild Kontakt (Mantel) HEAD-Lügner Mehr erfahren";

    private final PageStatusCheck check = new PageStatusCheck();

    private static SoftNotFoundProbe probe() {
        return new SoftNotFoundProbe(200, SimHash.of(NOT_FOUND_TEXT), NOT_FOUND_TEXT.length());
    }

    @Test
    void aServerErrorIsReportedWithItsStatusCode() {
        List<CheckFinding> findings = check.evaluate(
                Snapshots.page("https://example.com/weg").status(404).build(),
                Snapshots.config(check, Snapshots.facts(probe())));

        assertThat(findings).singleElement().satisfies(finding -> {
            assertThat(finding.type()).isEqualTo(CheckType.PAGE_STATUS);
            assertThat(finding.messageArgs()).containsExactly("404");
            assertThat(finding.evidence().httpStatus()).isEqualTo(404);
            assertThat(check.messageKeys()).contains(finding.messageKey());
        });
    }

    @Test
    void aPageThatIsTheNotFoundPageInDisguiseIsReportedAsASoftNotFound() {
        // The whole point of the {baseUrl}/{uuid} probe (spec 7.1): status 200 means nothing on
        // a site that answers 200 for everything.
        List<CheckFinding> findings = check.evaluate(
                Snapshots.page("https://example.com/verirrt").text(NOT_FOUND_TEXT).build(),
                Snapshots.config(check, Snapshots.facts(probe())));

        assertThat(findings).singleElement().satisfies(finding ->
                assertThat(finding.messageKey()).isEqualTo("finding.PAGE_STATUS.soft404"));
    }

    @Test
    void aRealPageIsNotMistakenForTheNotFoundPage() {
        // Measured against the fixture: the closest unrelated real page sits at 27, the cutoff
        // at 16. A check that eats real pages is worse than no check at all (spec 8).
        assertThat(check.evaluate(
                Snapshots.page("https://example.com/kontakt")
                        .text("Kontakt. Zurück zur Startseite. Name E-Mail Nachricht Absenden").build(),
                Snapshots.config(check, Snapshots.facts(probe())))).isEmpty();
    }

    @Test
    void theCutoffIsOverridablePerSite() {
        assertThat(check.evaluate(
                Snapshots.page("https://example.com/kontakt")
                        .text("Kontakt. Zurück zur Startseite. Name E-Mail Nachricht Absenden").build(),
                Snapshots.config(check, Snapshots.facts(probe()), Map.of("maxDistance", 64))))
                .hasSize(1);
    }

    @Test
    void aRealPageOnASiteWhoseShellAnswersEveryPathIsNotBlamedAsASoftNotFound() {
        // Shell-heavy site: the probe and the root have the same fingerprint. The old rule
        // flagged everything within 16 bits of the shell, so 57 real pages were reported.
        SoftNotFoundProbe probe = new SoftNotFoundProbe(200,
                SimHash.of(SHELL), SHELL.length(),
                200, SimHash.of(SHELL), SHELL.length());
        List<CheckFinding> findings = check.evaluate(
                Snapshots.page("https://example.com/broschuere").text(BROCHURE).build(),
                Snapshots.config(check, Snapshots.facts(probe)));

        assertThat(findings).isEmpty();
    }

    @Test
    void aGenuineNotFoundCloneIsStillReportedAgainstARootAnchor() {
        // Normal site (fixture geometry): root is far from the probe (36 bits), a clone of the
        // not-found body is at 0 — clearly closer to the probe than to the root.
        SoftNotFoundProbe probe = new SoftNotFoundProbe(200,
                SimHash.of(NOT_FOUND_TEXT), NOT_FOUND_TEXT.length(),
                200, SimHash.of(ROOT_TEXT), ROOT_TEXT.length());
        List<CheckFinding> findings = check.evaluate(
                Snapshots.page("https://example.com/verirrt").text(NOT_FOUND_TEXT).build(),
                Snapshots.config(check, Snapshots.facts(probe)));

        assertThat(findings).singleElement().satisfies(finding ->
                assertThat(finding.messageKey()).isEqualTo("finding.PAGE_STATUS.soft404"));
    }

    @Test
    void theRootAnchorDoesNotReviveAFindingForAPageOffTheAbsoluteCutoff() {
        SoftNotFoundProbe probe = new SoftNotFoundProbe(200,
                SimHash.of(NOT_FOUND_TEXT), NOT_FOUND_TEXT.length(),
                200, SimHash.of(ROOT_TEXT), ROOT_TEXT.length());
        List<CheckFinding> findings = check.evaluate(
                Snapshots.page("https://example.com/weit-weg")
                        .text("Völlig andere Inhalte, keine Ähnlichkeit zur Fehlerseite.").build(),
                Snapshots.config(check, Snapshots.facts(probe)));

        assertThat(findings).isEmpty();
    }

    @Test
    void anUnusableRootAnchorFallsBackToTheProbeOnlyRule() {
        SoftNotFoundProbe probe = new SoftNotFoundProbe(200, SimHash.of(NOT_FOUND_TEXT),
                NOT_FOUND_TEXT.length(), 0, 0L, 0);
        List<CheckFinding> findings = check.evaluate(
                Snapshots.page("https://example.com/verirrt").text(NOT_FOUND_TEXT).build(),
                Snapshots.config(check, Snapshots.facts(probe)));

        assertThat(findings).singleElement().satisfies(finding ->
                assertThat(finding.messageKey()).isEqualTo("finding.PAGE_STATUS.soft404"));
    }

    @Test
    void withoutAUsableProbeNothingIsCalledASoftNotFound() {
        // A site whose {uuid} page is a genuine 404 gives us nothing to compare against, and
        // guessing there would turn every short page into a finding.
        assertThat(check.evaluate(
                Snapshots.page("https://example.com/verirrt").text(NOT_FOUND_TEXT).build(),
                Snapshots.config(check, Snapshots.facts(SoftNotFoundProbe.NONE)))).isEmpty();
    }

    @Test
    void anUnreachablePageIsLeftToThePageUnreachableCheck() {
        assertThat(check.evaluate(
                Snapshots.page("https://example.com/x").unreachable("Timeout"),
                Snapshots.config(check, Snapshots.facts(probe())))).isEmpty();
    }

    @Test
    void aHardNotFoundIsReportedOnceAsAStatusErrorAndNotAlsoAsASoftNotFound() {
        assertThat(check.evaluate(
                Snapshots.page("https://example.com/hart-404").status(404).text(NOT_FOUND_TEXT).build(),
                Snapshots.config(check, Snapshots.facts(probe()))))
                .singleElement()
                .satisfies(finding ->
                        assertThat(finding.messageKey()).isEqualTo("finding.PAGE_STATUS.httpError"));
    }

    @Test
    void aThreeHundredFinalStatusIsNotReported() {
        // Main-frame navigation follows redirects, so a 3xx is almost never the status a page
        // check is handed — the browser lands on the redirect target and that page is what gets
        // visited. A 3xx as the *final* status is therefore a corner case that is currently out
        // of scope; if it ever shows up in the data, decide then how it should read.
        assertThat(check.evaluate(
                Snapshots.page("https://example.com/umgeleitet").status(301).build(),
                Snapshots.config(check, Snapshots.facts(probe())))).isEmpty();
    }
}