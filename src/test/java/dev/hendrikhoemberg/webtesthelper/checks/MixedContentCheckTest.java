package dev.hendrikhoemberg.webtesthelper.checks;

import dev.hendrikhoemberg.webtesthelper.model.CheckFinding;
import dev.hendrikhoemberg.webtesthelper.model.MediaKind;
import dev.hendrikhoemberg.webtesthelper.support.Snapshots;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MixedContentCheckTest {

    private final MixedContentCheck check = new MixedContentCheck();

    @Test
    void anInsecureSubresourceOnASecurePageIsReported() {
        // Deviation D6: the fixture site is plain HTTP, so this check is proven here rather
        // than against it. That is the whole argument for pure functions over snapshots.
        assertThat(check.evaluate(
                Snapshots.page("https://example.com/")
                        .image("http://example.com/logo.png", 40)
                        .image("https://example.com/ok.png", 40)
                        .media(MediaKind.VIDEO, "http://example.com/film.mp4", 1, 9.0, null)
                        .frame("http://example.com/karte", false, 0)
                        .script("http://example.com/app.js")
                        .stylesheet("http://example.com/stil.css").build(),
                Snapshots.config(check, Snapshots.facts())))
                .extracting(CheckFinding::subjectKey)
                .containsExactly("http://example.com/logo.png", "http://example.com/film.mp4",
                        "http://example.com/karte", "http://example.com/app.js",
                        "http://example.com/stil.css");
    }

    @Test
    void anInsecureScriptIsReported() {
        // The severe case, and the one the check was blind to: a browser hard-blocks an http
        // script on an https page, so the page's behaviour is simply gone.
        assertThat(check.evaluate(
                Snapshots.page("https://example.com/")
                        .script("http://cdn.example/analytics.js")
                        .script("https://cdn.example/ok.js").build(),
                Snapshots.config(check, Snapshots.facts())))
                .extracting(CheckFinding::subjectKey)
                .containsExactly("http://cdn.example/analytics.js");
    }

    @Test
    void anInsecureStylesheetIsReported() {
        assertThat(check.evaluate(
                Snapshots.page("https://example.com/")
                        .stylesheet("http://cdn.example/theme.css")
                        .stylesheet("https://cdn.example/ok.css").build(),
                Snapshots.config(check, Snapshots.facts())))
                .extracting(CheckFinding::subjectKey)
                .containsExactly("http://cdn.example/theme.css");
    }

    @Test
    void anInsecurePageCannotHaveMixedContent() {
        assertThat(check.evaluate(
                Snapshots.page("http://example.com/").image("http://example.com/logo.png", 40).build(),
                Snapshots.config(check, Snapshots.facts()))).isEmpty();
    }

    @Test
    void anUnreachablePageCannotHaveMixedContent() {
        // Nothing was loaded, so there is nothing whose scheme could have downgraded the padlock.
        assertThat(check.evaluate(
                Snapshots.page("https://example.com/")
                        .image("http://example.com/logo.png", 40).unreachable("Timeout"),
                Snapshots.config(check, Snapshots.facts()))).isEmpty();
    }

    @Test
    void aPlainLinkToAnInsecurePageIsNotMixedContent() {
        // A link is a destination, not a subresource: nothing is loaded into this page, the
        // padlock survives, and reporting it would be a false positive on every partner link.
        assertThat(check.evaluate(
                Snapshots.page("https://example.com/").link("http://partner.example/", false).build(),
                Snapshots.config(check, Snapshots.facts()))).isEmpty();
    }

    @Test
    void theSameInsecureSubresourceTwiceIsOneFinding() {
        assertThat(check.evaluate(
                Snapshots.page("https://example.com/")
                        .image("http://example.com/logo.png", 40)
                        .image("http://example.com/logo.png", 40).build(),
                Snapshots.config(check, Snapshots.facts()))).hasSize(1);
    }
}