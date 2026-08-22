package dev.hendrikhoemberg.webtesthelper.model;

import java.util.List;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Inputs that must not throw and must not silently normalise to something wrong.
 */
class UrlNormalizerEdgeCasesTest {

    private static String norm(String raw) {
        return UrlNormalizer.key(raw).orElseThrow();
    }


    @Test
    void nullsAreEmptyEverywhere() {
        assertThat(UrlNormalizer.normalize(null)).isEmpty();
        assertThat(UrlNormalizer.key(null)).isEmpty();
        assertThat(UrlNormalizer.resolve(null, "/a")).isEmpty();
        assertThat(UrlNormalizer.resolve("https://x.com/", null)).isEmpty();
        assertThat(UrlNormalizer.locationKeyOf(null)).isNull();
        assertThat(UrlNormalizer.isSameSite(null, null)).isFalse();

        NormalizedUrl url = UrlNormalizer.normalize("https://example.com/").orElseThrow();
        assertThat(url.sameSiteAs(null)).isFalse();
    }

    @Test
    void stripsUserInfo() {
        assertThat(norm("https://user:pass@example.com/x")).isEqualTo("https://example.com/x");
    }

    @Test
    void doesNotDoubleDecodeALiteralPercentSequence() {
        assertThat(norm("https://example.com/a%252Fb")).isEqualTo("https://example.com/a%252Fb");
    }

    @Test
    void originOmitsTheDefaultPort() {
        assertThat(UrlNormalizer.normalize("https://example.com/a").orElseThrow().origin())
                .isEqualTo("https://example.com");
    }

    @Test
    void defaultPortComparisonsAreCaseInsensitiveOnTheScheme() {
        assertThat(UrlNormalizer.normalize("HTTPS://example.com:443/a").orElseThrow().hasDefaultPort())
                .isTrue();
    }

    @Test
    void handlesNumericAndIpv6Hosts() {
        assertThat(UrlNormalizer.normalize("https://192.168.1.1:8080/x").orElseThrow().host())
                .isEqualTo("192.168.1.1");
        assertThat(UrlNormalizer.normalize("http://[::1]:8080/x").orElseThrow().host())
                .isEqualTo("[::1]");
    }

    @Test
    void rawAndPercentEncodedUtf8QueryValuesMerge() {
        // The percent-encoded and raw spellings of the same UTF-8 value must fingerprint
        // identically instead of one becoming mojibake (Ã¼ber).
        assertThat(norm("https://example.com/s?q=%C3%BCber"))
                .isEqualTo(norm("https://example.com/s?q=über"));
    }

    @Test
    void queryPercentEncodedSlashKeepsItsEscape() {
        // %2F in a query is a literal slash inside a value; it must not be decoded into a
        // path separator or collapse the query.
        assertThat(norm("https://example.com/s?q=a%2Fb"))
                .isEqualTo("https://example.com/s?q=a%2Fb");
    }

    @Test
    void underscoreHostKeepsItsNonDefaultPort() {
        // An underscore host makes URI.getHost() return null, so the port is parsed by hand;
        // foo_bar.com:8080 and foo_bar.com are different services and must not collide.
        assertThat(norm("http://foo_bar.com:8080/x")).isEqualTo("http://foo_bar.com:8080/x");
        assertThat(norm("http://foo_bar.com/x")).isEqualTo("http://foo_bar.com/x");
    }

    @Test
    void percentEncodedDotSegmentsAreNotResolvedAndStayEscaped() {
        // RFC 3986 §5.2.4 runs on the raw path, so a percent-encoded %2e%2e is a literal dot
        // segment, not a path-climbing "..". Pinned deliberately so nobody silently "fixes" it
        // into removing a segment that the server actually serves as a distinct path.
        //
        // It stays escaped in the output: emitting a bare ".." would produce a canonical form
        // that normalises again to something else, so one resource could carry two subjectKeys.
        assertThat(norm("https://example.com/a/%2e%2e/b"))
                .isEqualTo("https://example.com/a/%2E%2E/b");
        assertThat(norm("https://example.com/a/%2e/b"))
                .isEqualTo("https://example.com/a/%2E/b");
    }

    @Test
    void normalisingAnAlreadyNormalisedUrlChangesNothing() {
        // The canonical form must be a fixed point, or a value that has been round-tripped
        // through storage fingerprints differently from the one just extracted from markup.
        List<String> raw = List.of(
                "https://example.com/a/%2e%2e/b",
                "https://example.com/a/%2e/b",
                "HTTPS://Example.COM:443/Leistungen/?utm_source=x&b=2&a=1#top",
                "https://example.com/über-uns",
                "https://example.com/mein dokument.pdf",
                "https://müller-bau.de/kontakt/",
                "https://example.com//a//b/",
                "https://example.com/a%2fb%7ec%41d",
                "https://example.com/s?q=a%2Fb",
                "https://example.com/s?a=",
                "https://example.com");

        for (String value : raw) {
            String once = norm(value);
            assertThat(norm(once)).as("normalising %s twice", value).isEqualTo(once);
        }
    }

    @Test
    void keepsAnEmptyValueDistinctFromABareParameter() {
        // ?a= and ?a are different requests to plenty of backends; merging them would
        // fingerprint two different pages as one.
        assertThat(norm("https://example.com/s?a=")).isEqualTo("https://example.com/s?a=");
        assertThat(norm("https://example.com/s?a")).isEqualTo("https://example.com/s?a");
    }

    @Test
    void encodesNonBmpCharactersAsWholeCodePoints() {
        // Encoding a surrogate pair char-by-char yields two unmappable halves, so every
        // non-BMP URL would collapse onto one %3F%3F key.
        assertThat(norm("https://example.com/a😀b"))
                .isEqualTo("https://example.com/a%F0%9F%98%80b");
        assertThat(norm("https://example.com/😀"))
                .isNotEqualTo(norm("https://example.com/🚀"));
    }
}
