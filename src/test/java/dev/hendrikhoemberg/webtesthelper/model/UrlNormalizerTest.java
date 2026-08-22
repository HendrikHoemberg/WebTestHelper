package dev.hendrikhoemberg.webtesthelper.model;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class UrlNormalizerTest {

    private static String norm(String raw) {
        return UrlNormalizer.key(raw).orElseThrow();
    }

    @Nested
    class Normalisation {

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

    @Nested
    class Resolution {

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

    @Nested
    class Keys {

        @Test
        void locationKeyIsThePathAndSurvivingQuery() {
            assertThat(UrlNormalizer.locationKeyOf("https://example.com/aktuelles?page=2&utm_source=x"))
                    .isEqualTo("/aktuelles?page=2");
            assertThat(UrlNormalizer.locationKeyOf("https://example.com/")).isEqualTo("/");
        }

        @Test
        void locationKeyOfSomethingUnparseableIsTheInputItself() {
            assertThat(UrlNormalizer.locationKeyOf("nonsense")).isEqualTo("nonsense");
        }

        @Test
        void sameSiteComparisonIgnoresALeadingWww() {
            NormalizedUrl apex = UrlNormalizer.normalize("https://example.com/a").orElseThrow();
            NormalizedUrl www = UrlNormalizer.normalize("https://www.example.com/b").orElseThrow();
            NormalizedUrl other = UrlNormalizer.normalize("https://andere.de/c").orElseThrow();

            assertThat(UrlNormalizer.isSameSite(apex, www)).isTrue();
            assertThat(UrlNormalizer.isSameSite(apex, other)).isFalse();
        }

        @Test
        void normalizedUrlExposesItsParts() {
            NormalizedUrl url = UrlNormalizer.normalize("https://example.com:8443/a?b=1").orElseThrow();
            assertThat(url.scheme()).isEqualTo("https");
            assertThat(url.host()).isEqualTo("example.com");
            assertThat(url.port()).isEqualTo(8443);
            assertThat(url.path()).isEqualTo("/a");
            assertThat(url.query()).isEqualTo("b=1");
            assertThat(url.hasDefaultPort()).isFalse();
            assertThat(url.origin()).isEqualTo("https://example.com:8443");
        }
    }

    @Nested
    class EdgeCases {

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
}
