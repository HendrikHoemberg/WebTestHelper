package dev.hendrikhoemberg.webtesthelper.checks;

import dev.hendrikhoemberg.webtesthelper.model.CheckFinding;
import dev.hendrikhoemberg.webtesthelper.model.ImageOrigin;
import dev.hendrikhoemberg.webtesthelper.model.ImageRef;
import dev.hendrikhoemberg.webtesthelper.model.ImageState;
import dev.hendrikhoemberg.webtesthelper.support.Snapshots;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ImageBrokenCheckTest {

    private final ImageBrokenCheck check = new ImageBrokenCheck();

    @Test
    void anImageThatNeverRenderedIsReported() {
        CheckFinding finding = check.evaluate(
                Snapshots.page("https://example.com/")
                        .image("https://example.com/logo.png", 200)
                        .image("https://example.com/fehlt.png", 0).build(),
                Snapshots.config(check, Snapshots.facts())).getFirst();

        assertThat(finding.subjectKey()).isEqualTo("https://example.com/fehlt.png");
        assertThat(finding.observedOn().value()).isEqualTo("https://example.com/");
        assertThat(finding.messageArgs()).containsExactly("https://example.com/fehlt.png");
        assertThat(check.messageKeys()).contains(finding.messageKey());
    }

    @Test
    void srcsetCandidatesAndCssBackgroundsCountAsImagesToo() {
        // Spec 7.1 names all three origins, because "the img tag loaded" is not the test — a
        // retina candidate or a hero background nobody ever decoded fails silently otherwise.
        assertThat(check.evaluate(
                Snapshots.page("https://example.com/leistungen")
                        .image("https://example.com/a.png", 0, ImageOrigin.SRCSET)
                        .image("https://example.com/b.png", 0, ImageOrigin.CSS_BACKGROUND).build(),
                Snapshots.config(check, Snapshots.facts())))
                .extracting(CheckFinding::subjectKey)
                .containsExactly("https://example.com/a.png", "https://example.com/b.png");
    }

    @Test
    void oneBrokenImageUsedTwiceOnAPageIsOneFinding() {
        // The same file in the header and the footer is one broken thing, not two. Occurrences
        // across pages are counted at materialisation (spec 6.2), not here.
        assertThat(check.evaluate(
                Snapshots.page("https://example.com/")
                        .image("https://example.com/fehlt.png", 0)
                        .image("https://example.com/fehlt.png", 0, ImageOrigin.CSS_BACKGROUND).build(),
                Snapshots.config(check, Snapshots.facts()))).hasSize(1);
    }

    @Test
    void anUnreachablePageReportsNoImages() {
        assertThat(check.evaluate(Snapshots.page("https://example.com/x").unreachable("Timeout"),
                Snapshots.config(check, Snapshots.facts()))).isEmpty();
    }

    @Test
    void naturalWidthWithoutNaturalHeightIsBroken() {
        // Spec 7.1: both dimensions must render. A decoder can report a width yet stand
        // there with no height, and that still leaves a broken image in the layout.
        assertThat(check.evaluate(
                Snapshots.page("https://example.com/")
                        .image("https://example.com/kaputt.png", 400, 0, ImageOrigin.IMG).build(),
                Snapshots.config(check, Snapshots.facts())))
                .singleElement()
                .satisfies(finding -> assertThat(finding.subjectKey())
                        .isEqualTo("https://example.com/kaputt.png"));
    }

    @Test
    void anUnknownImageIsNotReportedBecauseItMayBeHealthy() {
        // A lazy image or a slow CDN that timed out before the probe completed is not
        // confirmed broken — reporting it would be a false positive (same philosophy as
        // UNVERIFIABLE ≠ DEAD for links).
        assertThat(check.evaluate(
                Snapshots.page("https://example.com/")
                        .image("https://example.com/langsam.png", 0, 0, ImageOrigin.IMG,
                                ImageState.UNKNOWN)
                        .build(),
                Snapshots.config(check, Snapshots.facts()))).isEmpty();
    }

    @Test
    void aBrokenImageWithExplicitStateIsStillReported() {
        assertThat(check.evaluate(
                Snapshots.page("https://example.com/")
                        .image("https://example.com/kaputt.png", 0, 0, ImageOrigin.IMG,
                                ImageState.BROKEN)
                        .build(),
                Snapshots.config(check, Snapshots.facts())))
                .singleElement()
                .satisfies(f -> assertThat(f.subjectKey())
                        .isEqualTo("https://example.com/kaputt.png"));
    }

    @Test
    void anImageWhoseTargetCouldNotBeNormalisedDoesNotCrash() {
        // PageNavigator only emits images with a normalised target, but a hand-built snapshot
        // (or a future extraction origin) can carry none; naming it is impossible, so it is
        // skipped rather than crashing the whole page's findings.
        assertThat(check.evaluate(
                Snapshots.page("https://example.com/")
                        .image(new ImageRef("data:image/png;base64,bG9nbw==", null, "Alt-Text",
                                0, 0, ImageOrigin.IMG)).build(),
                Snapshots.config(check, Snapshots.facts()))).isEmpty();
    }
}