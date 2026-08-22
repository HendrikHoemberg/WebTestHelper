package dev.hendrikhoemberg.webtesthelper.checks;

import dev.hendrikhoemberg.webtesthelper.model.CheckFinding;
import dev.hendrikhoemberg.webtesthelper.support.Snapshots;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class IframeEmbedCheckTest {

    private static final String MAPS_ERROR =
            "Google Maps JavaScript API error: ApiNotActivatedMapError";

    private final IframeEmbedCheck check = new IframeEmbedCheck();

    @Test
    void aFrameTheBrowserRefusedToDisplayIsReportedAsBlocked() {
        // Measured against the fixture: an X-Frame-Options refusal shows up as a failed
        // document request, and nowhere else that can be tied back to the frame.
        CheckFinding finding = check.evaluate(
                Snapshots.page("https://example.com/kontakt")
                        .frame("https://bewertungen.example/widget", false, 0)
                        .blockedDocument("https://bewertungen.example/widget").build(),
                Snapshots.config(check, Snapshots.facts())).getFirst();

        assertThat(finding.messageKey()).isEqualTo("finding.IFRAME_EMBED.blocked");
        assertThat(finding.subjectKey()).isEqualTo("https://bewertungen.example/widget");
        assertThat(check.messageKeys()).contains(finding.messageKey());
    }

    @Test
    void aMapsEmbedWithAProviderErrorIsReportedEvenThoughItLoaded() {
        // Spec 7.1: the real failure is billing or an API key, and "the iframe loaded" passes
        // a grey tile with a watermark. The console is where the truth is.
        CheckFinding finding = check.evaluate(
                Snapshots.page("https://example.com/kontakt")
                        .frame("https://www.google.com/maps/embed/v1/place", false, 0)
                        .consoleError(MAPS_ERROR).build(),
                Snapshots.config(check, Snapshots.facts())).getFirst();

        assertThat(finding.messageKey()).isEqualTo("finding.IFRAME_EMBED.maps");
        assertThat(finding.messageArgs()).containsExactly("ApiNotActivatedMapError");
        assertThat(finding.evidence().consoleExcerpt()).contains(MAPS_ERROR);
    }

    @Test
    void aHealthyMapsEmbedIsNotReported() {
        assertThat(check.evaluate(
                Snapshots.page("https://example.com/kontakt")
                        .frame("https://www.google.com/maps/embed/v1/place", false, 0).build(),
                Snapshots.config(check, Snapshots.facts()))).isEmpty();
    }

    @Test
    void aCrossOriginFrameIsNeverReportedMerelyForBeingUnreadable() {
        // Deviation D17. Measured: a healthy cross-origin embed and a blocked one both report
        // textLength 0, because the parent cannot read the document either way. Reporting on
        // that would fire on every YouTube embed on every page — the false positive spec 8
        // exists to prevent.
        assertThat(check.evaluate(
                Snapshots.page("https://example.com/kontakt")
                        .frame("https://www.youtube.com/embed/abc", false, 0).build(),
                Snapshots.config(check, Snapshots.facts()))).isEmpty();
    }

    @Test
    void aSameOriginFrameWithNoContentIsReportedAsEmpty() {
        assertThat(check.evaluate(
                Snapshots.page("https://example.com/kontakt")
                        .frame("https://example.com/teil/anfahrt", true, 0).build(),
                Snapshots.config(check, Snapshots.facts())))
                .singleElement()
                .satisfies(finding ->
                        assertThat(finding.messageKey()).isEqualTo("finding.IFRAME_EMBED.empty"));
    }

    @Test
    void aSameOriginFrameWithContentIsNotReported() {
        assertThat(check.evaluate(
                Snapshots.page("https://example.com/kontakt")
                        .frame("https://example.com/teil/anfahrt", true, 240).build(),
                Snapshots.config(check, Snapshots.facts()))).isEmpty();
    }

    @Test
    void aFrameThatIsBothBlockedAndEmptyIsReportedOnce() {
        assertThat(check.evaluate(
                Snapshots.page("https://example.com/kontakt")
                        .frame("https://example.com/teil/anfahrt", true, 0)
                        .blockedDocument("https://example.com/teil/anfahrt").build(),
                Snapshots.config(check, Snapshots.facts())))
                .singleElement()
                .satisfies(finding ->
                        assertThat(finding.messageKey()).isEqualTo("finding.IFRAME_EMBED.blocked"));
    }
}