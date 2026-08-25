package dev.hendrikhoemberg.webtesthelper.findings;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MutePatternTest {

    @Test
    void starWildcardBecomesPercent() {
        assertThat(MutePattern.toLikePattern("*linkedin.com*"))
                .isEqualTo("%linkedin.com%");
        assertThat(MutePattern.toLikePattern("https://example.com/*"))
                .isEqualTo("https://example.com/%");
    }

    @Test
    void literalPercentAndUnderscoreAreEscaped() {
        // URLs are full of '_' and '%' (e.g. query params and URL encoding)
        assertThat(MutePattern.toLikePattern("https://example.com/page?utm_source=test_100%"))
                .isEqualTo("https://example.com/page?utm\\_source=test\\_100\\%");
    }

    @Test
    void literalBackslashIsEscaped() {
        assertThat(MutePattern.toLikePattern("path\\to\\file"))
                .isEqualTo("path\\\\to\\\\file");
    }

    @Test
    void globWithNoStarProducesExactMatchWithNoPercent() {
        assertThat(MutePattern.toLikePattern("https://example.com/archiv/page.html"))
                .isEqualTo("https://example.com/archiv/page.html");
    }

    @Test
    void combinedWildcardsAndSpecialCharacters() {
        assertThat(MutePattern.toLikePattern("*/api_v1/%*\\test"))
                .isEqualTo("%/api\\_v1/\\%%\\\\test");
    }

    @Test
    void isBlankChecksNullEmptyAndWhitespace() {
        assertThat(MutePattern.isBlank(null)).isTrue();
        assertThat(MutePattern.isBlank("")).isTrue();
        assertThat(MutePattern.isBlank("   ")).isTrue();
        assertThat(MutePattern.isBlank("\t\n  ")).isTrue();

        assertThat(MutePattern.isBlank("a")).isFalse();
        assertThat(MutePattern.isBlank("*")).isFalse();
        assertThat(MutePattern.isBlank("  *  ")).isFalse();
    }
}
