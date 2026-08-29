package dev.hendrikhoemberg.webtesthelper.model;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class FrameRefTest {

    private static FrameRef frame(String src) {
        return new FrameRef(UrlNormalizer.normalize(src).orElseThrow(), "Eingebettet", true, 0, false);
    }

    @Test
    void aMapsEmbedPathIsRecognisedEvenOnANonGoogleHost() {
        // The fixture serves its Maps frame from its own host, so a host-only copy of the rule
        // would silently fail on the very site that proves the check.
        assertThat(frame("http://127.0.0.1:8080/maps/embed/v1/place").isMapsEmbed()).isTrue();
    }

    @Test
    void aGoogleHostWithAMapsPathIsRecognised() {
        assertThat(frame("https://www.google.com/maps/embed/v1/directions").isMapsEmbed()).isTrue();
    }

    @Test
    void aGoogleHostWithoutAMapsPathIsNotRecognised() {
        assertThat(frame("https://google.com/search").isMapsEmbed()).isFalse();
    }

    @Test
    void anUnrelatedHostAndPathIsNotRecognised() {
        assertThat(frame("https://example.com/kontakt").isMapsEmbed()).isFalse();
    }

    @Test
    void aFrameWithoutAPaintSignalReportsUnknownPaintState() {
        // A frame built without a canvas signal (cross-origin, or no canvas at all) is the
        // absence of a signal — UNKNOWN — not a finding.
        assertThat(frame("https://www.google.com/maps/embed/v1/place")
                .mapPaintState()).isEqualTo(MapPaintState.UNKNOWN);
    }
}
