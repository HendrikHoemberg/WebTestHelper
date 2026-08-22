package dev.hendrikhoemberg.webtesthelper.model;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Spec 6.2: the normalisation rules that make a subject key stable, so the same
 * broken link fingerprints identically wherever it is found.
 */
class UrlNormalizerNormalisationTest {

    private static String norm(String raw) {
        return UrlNormalizer.key(raw).orElseThrow();
    }


    @Test
    void dropsTheFragment() {
        assertThat(norm("https://example.com/a#kontakt")).isEqualTo("https://example.com/a");
    }

    @Test
    void sortsQueryParametersByNameThenValue() {
        assertThat(norm("https://example.com/s?b=2&a=1&a=0"))
                .isEqualTo("https://example.com/s?a=0&a=1&b=2");
    }

    @Test
    void stripsTrackingParametersButKeepsRealOnes() {
        assertThat(norm("https://example.com/s?utm_source=news&page=2&fbclid=xyz&utm_medium=mail"))
                .isEqualTo("https://example.com/s?page=2");
    }

    @Test
    void stripsTrackingParametersCaseInsensitively() {
        assertThat(norm("https://example.com/s?UTM_SOURCE=x&page=1"))
                .isEqualTo("https://example.com/s?page=1");
    }

    @Test
    void dropsTheQueryEntirelyWhenOnlyTrackingParametersWerePresent() {
        assertThat(norm("https://example.com/s?utm_source=news")).isEqualTo("https://example.com/s");
    }

    @Test
    void dropsTheDefaultPortAndKeepsAnyOther() {
        assertThat(norm("https://example.com:443/a")).isEqualTo("https://example.com/a");
        assertThat(norm("http://example.com:80/a")).isEqualTo("http://example.com/a");
        assertThat(norm("https://example.com:8443/a")).isEqualTo("https://example.com:8443/a");
    }

    @Test
    void lowercasesSchemeAndHostButNeverThePath() {
        // Deviation D4: lowercasing the path would merge distinct resources on a
        // case-sensitive server and corrupt both the frontier and the URL cache.
        assertThat(norm("HTTPS://Example.COM/Leistungen/PDF")).isEqualTo("https://example.com/Leistungen/PDF");
    }

    @Test
    void stripsTheTrailingSlashExceptAtTheRoot() {
        assertThat(norm("https://example.com/leistungen/")).isEqualTo("https://example.com/leistungen");
        assertThat(norm("https://example.com/")).isEqualTo("https://example.com/");
        assertThat(norm("https://example.com")).isEqualTo("https://example.com/");
    }

    @Test
    void removesDotSegmentsAndCollapsesDuplicateSlashes() {
        assertThat(norm("https://example.com/a/b/../c")).isEqualTo("https://example.com/a/c");
        assertThat(norm("https://example.com/./a")).isEqualTo("https://example.com/a");
        assertThat(norm("https://example.com//a//b")).isEqualTo("https://example.com/a/b");
        assertThat(norm("https://example.com/../..")).isEqualTo("https://example.com/");
    }

    @Test
    void convertsInternationalisedHostsToPunycodeAndDropsTheRootLabel() {
        assertThat(norm("https://müller-bau.de/kontakt")).isEqualTo("https://xn--mller-bau-q9a.de/kontakt");
        assertThat(norm("https://example.com./a")).isEqualTo("https://example.com/a");
    }

    @Test
    void uppercasesPercentEscapesAndDecodesUnreservedOnes() {
        assertThat(norm("https://example.com/a%2fb%7ec%41d")).isEqualTo("https://example.com/a%2Fb~cAd");
    }

    @Test
    void encodesCharactersRealMarkupContainsAndRfc3986Forbids() {
        assertThat(norm("https://example.com/mein dokument.pdf"))
                .isEqualTo("https://example.com/mein%20dokument.pdf");
        assertThat(norm("https://example.com/über-uns"))
                .isEqualTo("https://example.com/%C3%BCber-uns");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "mailto:info@example.com", "tel:+4930123456", "javascript:void(0)",
            "data:image/png;base64,iVBOR", "ftp://example.com/x", "#top", "", "   "})
    void rejectsEverythingThatIsNotAnHttpUrl(String raw) {
        assertThat(UrlNormalizer.key(raw)).isEmpty();
    }

    @Test
    void rejectsNull() {
        assertThat(UrlNormalizer.key(null)).isEmpty();
    }

    @Test
    void theSameDeadLinkWrittenTwoWaysNormalisesIdentically() {
        // The motivating case from the spec: the same dead link must fingerprint identically.
        String fromFooter = norm("HTTP://Partner.example.com:80/angebot/?utm_source=footer&id=7#top");
        String fromBody   = norm("http://partner.example.com/angebot?id=7&utm_campaign=body");
        assertThat(fromFooter).isEqualTo(fromBody);
    }
}
