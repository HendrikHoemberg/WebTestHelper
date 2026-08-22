package dev.hendrikhoemberg.webtesthelper.model;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Resolving hrefs against a base URL — the step that turns a page's raw links into
 * frontier candidates.
 */
class UrlNormalizerResolutionTest {

    private static final String BASE = "https://example.com/leistungen/beratung";

    @Test
    void resolvesRelativeReferences() {
        assertThat(UrlNormalizer.resolve(BASE, "../kontakt").orElseThrow().value())
                .isEqualTo("https://example.com/kontakt");
        assertThat(UrlNormalizer.resolve(BASE, "preise").orElseThrow().value())
                .isEqualTo("https://example.com/leistungen/preise");
        assertThat(UrlNormalizer.resolve(BASE, "/impressum").orElseThrow().value())
                .isEqualTo("https://example.com/impressum");
    }

    @Test
    void resolvesProtocolRelativeReferencesAgainstTheBaseScheme() {
        assertThat(UrlNormalizer.resolve(BASE, "//cdn.example.net/logo.png").orElseThrow().value())
                .isEqualTo("https://cdn.example.net/logo.png");
    }

    @Test
    void passesAbsoluteReferencesThrough() {
        assertThat(UrlNormalizer.resolve(BASE, "https://andere.de/x").orElseThrow().value())
                .isEqualTo("https://andere.de/x");
    }

    @Test
    void rejectsSamePageAnchorsAndNonWebSchemes() {
        assertThat(UrlNormalizer.resolve(BASE, "#weiter")).isEmpty();
        assertThat(UrlNormalizer.resolve(BASE, "mailto:info@example.com")).isEmpty();
    }

    @Test
    void toleratesWhitespaceAndNewlinesInsideMarkupHrefs() {
        assertThat(UrlNormalizer.resolve(BASE, "  /kon\ntakt  ").orElseThrow().value())
                .isEqualTo("https://example.com/kontakt");
    }

    @Test
    void resolvesHrefsContainingCharactersRfc3986Forbids() {
        // <a href="mein dokument.pdf"> is ordinary on the sites this tool checks. Dropping
        // such an href would keep the link out of the frontier and out of every report —
        // a silent miss, which is the worst failure mode for a link checker.
        assertThat(UrlNormalizer.resolve(BASE, "mein dokument.pdf").orElseThrow().value())
                .isEqualTo("https://example.com/leistungen/mein%20dokument.pdf");
        assertThat(UrlNormalizer.resolve(BASE, "/downloads/preisliste 2026.pdf").orElseThrow().value())
                .isEqualTo("https://example.com/downloads/preisliste%202026.pdf");
        assertThat(UrlNormalizer.resolve(BASE, "über-uns.html").orElseThrow().value())
                .isEqualTo("https://example.com/leistungen/%C3%BCber-uns.html");
        assertThat(UrlNormalizer.resolve(BASE, "/a|b").orElseThrow().value())
                .isEqualTo("https://example.com/a%7Cb");
    }

    @Test
    void resolvesAgainstABaseThatItselfNeedsEncoding() {
        assertThat(UrlNormalizer.resolve("https://example.com/mein ordner/seite.html", "a.html")
                .orElseThrow().value())
                .isEqualTo("https://example.com/mein%20ordner/a.html");
    }
}
