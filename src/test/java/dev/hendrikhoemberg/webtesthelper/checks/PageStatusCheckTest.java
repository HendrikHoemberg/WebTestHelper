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