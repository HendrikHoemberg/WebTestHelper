package dev.hendrikhoemberg.webtesthelper.checks;

import dev.hendrikhoemberg.webtesthelper.model.CheckFinding;
import dev.hendrikhoemberg.webtesthelper.model.ImageOrigin;
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
}