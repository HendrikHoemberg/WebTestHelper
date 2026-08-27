package dev.hendrikhoemberg.webtesthelper.checks;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import dev.hendrikhoemberg.webtesthelper.checks.CookieBanner.BannerOutcome;
import dev.hendrikhoemberg.webtesthelper.support.FixtureSite;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("browser")
class CookieBannerTest {

    private static FixtureSite site;
    private static Playwright playwright;
    private static Browser browser;

    @BeforeAll
    static void start() {
        site = FixtureSite.start();
        playwright = Playwright.create();
        browser = playwright.chromium().launch(new BrowserType.LaunchOptions().setHeadless(true));
    }

    @AfterAll
    static void stop() {
        if (browser != null) {
            browser.close();
        }
        if (playwright != null) {
            playwright.close();
        }
        if (site != null) {
            site.close();
        }
    }

    @Test
    void dismissableBannerIsDetectedAcceptedAndDismissed() {
        try (BrowserContext context = browser.newContext()) {
            Page page = context.newPage();
            page.navigate(site.url("interaktiv/banner.html"));

            BannerOutcome outcome = CookieBanner.accept(page, Duration.ofSeconds(2));

            assertThat(outcome.present()).isTrue();
            assertThat(outcome.dismissed()).isTrue();
            assertThat(outcome.containerId()).isEqualTo("cookie-hinweis");
            assertThat(outcome.acceptLabel()).isEqualTo("Alle akzeptieren");
        }
    }

    @Test
    void stubbornBannerIsDetectedAndClickedButNotDismissed() {
        try (BrowserContext context = browser.newContext()) {
            Page page = context.newPage();
            page.navigate(site.url("interaktiv/banner-hartnaeckig.html"));

            BannerOutcome outcome = CookieBanner.accept(page, Duration.ofMillis(500));

            assertThat(outcome.present()).isTrue();
            assertThat(outcome.dismissed()).isFalse();
            assertThat(outcome.containerId()).isEqualTo("cookie-hinweis");
            assertThat(outcome.acceptLabel()).isEqualTo("Alle akzeptieren");
        }
    }

    @Test
    void pageWithoutBannerReturnsNotPresent() {
        try (BrowserContext context = browser.newContext()) {
            Page page = context.newPage();
            page.navigate(site.url("interaktiv/ohne-banner.html"));

            BannerOutcome outcome = CookieBanner.accept(page, Duration.ofSeconds(2));

            assertThat(outcome.present()).isFalse();
            assertThat(outcome.dismissed()).isFalse();
            assertThat(outcome.containerId()).isNull();
            assertThat(outcome.acceptLabel()).isNull();
        }
    }

    @Test
    void standardFixturePageDoesNotTriggerFalsePositive() {
        try (BrowserContext context = browser.newContext()) {
            Page page = context.newPage();
            page.navigate(site.url("kontakt.html"));

            BannerOutcome outcome = CookieBanner.accept(page, Duration.ofSeconds(2));

            assertThat(outcome.present()).isFalse();
            assertThat(outcome.dismissed()).isFalse();
            assertThat(outcome.containerId()).isNull();
            assertThat(outcome.acceptLabel()).isNull();
        }
    }

    @Test
    void bannerInsideIframeIsDetectedAndAccepted() {
        try (BrowserContext context = browser.newContext()) {
            Page page = context.newPage();
            page.navigate(site.url("interaktiv/banner-iframe.html"));

            BannerOutcome outcome = CookieBanner.accept(page, Duration.ofSeconds(2));

            assertThat(outcome.present()).isTrue();
            assertThat(outcome.dismissed()).isTrue();
            assertThat(outcome.containerId()).isEqualTo("cookie-hinweis");
            assertThat(outcome.acceptLabel()).isEqualTo("Alle akzeptieren");
        }
    }

    @Test
    void nullPageReturnsNotPresentSafely() {
        BannerOutcome outcome = CookieBanner.accept(null, Duration.ofSeconds(1));

        assertThat(outcome.present()).isFalse();
        assertThat(outcome.dismissed()).isFalse();
        assertThat(outcome.containerId()).isNull();
        assertThat(outcome.acceptLabel()).isNull();
    }
}
