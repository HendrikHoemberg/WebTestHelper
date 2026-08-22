package dev.hendrikhoemberg.webtesthelper.model;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The key forms the frontier, the finding fingerprint and the external URL cache share.
 */
class UrlNormalizerKeysTest {

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
