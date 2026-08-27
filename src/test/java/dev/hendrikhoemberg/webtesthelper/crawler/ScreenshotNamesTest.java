package dev.hendrikhoemberg.webtesthelper.crawler;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ScreenshotNamesTest {

    @Test
    void oneArgumentFormDelegatesWithEmptyDiscriminator() {
        String url = "https://example.com/test";
        String singleArg = ScreenshotNames.screenshotName(url);
        String emptyDiscriminator = ScreenshotNames.screenshotName(url, "");
        String nullDiscriminator = ScreenshotNames.screenshotName(url, null);

        assertThat(singleArg).isEqualTo(emptyDiscriminator);
        assertThat(singleArg).isEqualTo(nullDiscriminator);
        assertThat(singleArg).hasSize(36).endsWith(".png");
    }

    @Test
    void discriminatorChangesHashDeterministically() {
        String url = "https://example.com/test";
        String base = ScreenshotNames.screenshotName(url);
        String cookieBanner = ScreenshotNames.screenshotName(url, "COOKIE_BANNER");
        String contactForm = ScreenshotNames.screenshotName(url, "CONTACT_FORM");

        assertThat(cookieBanner).hasSize(36).endsWith(".png");
        assertThat(contactForm).hasSize(36).endsWith(".png");

        assertThat(cookieBanner).isNotEqualTo(base);
        assertThat(contactForm).isNotEqualTo(base);
        assertThat(cookieBanner).isNotEqualTo(contactForm);

        // Deterministic
        assertThat(ScreenshotNames.screenshotName(url, "COOKIE_BANNER")).isEqualTo(cookieBanner);
    }
}
