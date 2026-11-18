package dev.hendrikhoemberg.webtesthelper.web;

import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.assertions.PlaywrightAssertions;
import com.microsoft.playwright.options.LoadState;
import dev.hendrikhoemberg.webtesthelper.auth.AppRole;
import dev.hendrikhoemberg.webtesthelper.auth.AppUserService;
import dev.hendrikhoemberg.webtesthelper.support.AbstractPostgresTest;
import dev.hendrikhoemberg.webtesthelper.support.SharedBrowser;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The onboarding tour spans two pages (dashboard, websites). A real browser is the only way to see
 * what Driver.js renders: the step counter must count the whole tour, the dashboard's last step
 * must not say "done", and walking away mid-tour must not start it over on the next page.
 */
@Tag("browser")
@org.junit.jupiter.api.parallel.ResourceLock("browser")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TutorialTourBrowserAcceptanceTest extends AbstractPostgresTest {

    @LocalServerPort
    int port;

    @Autowired
    AppUserService appUserService;

    private String base() {
        return "http://localhost:" + port;
    }

    @Test
    void theTourCountsAllSixStepsAndDoesNotRestartAfterBeingLeft() {
        String user = "tour" + System.nanoTime();
        appUserService.create(user, "test-pass-42", AppRole.ADMIN);
        BrowserContext ctx = SharedBrowser.browser().newContext();
        try {
            Page page = ctx.newPage();
            page.navigate(base() + "/");
            page.fill("input[name='username']", user);
            page.fill("input[name='password']", "test-pass-42");
            page.click("button[type='submit']");

            Locator progress = page.locator(".driver-popover-progress-text");
            Locator next = page.locator(".driver-popover-next-btn");
            PlaywrightAssertions.assertThat(progress).hasText("1 / 6");
            next.click();
            PlaywrightAssertions.assertThat(progress).hasText("2 / 6");
            next.click();
            PlaywrightAssertions.assertThat(progress).hasText("3 / 6");
            PlaywrightAssertions.assertThat(next).containsText("Weiter");

            next.click();
            page.waitForURL(url -> url.endsWith("/websites"));
            PlaywrightAssertions.assertThat(progress).hasText("4 / 6");

            // Walk away mid-tour: a fresh tab on the dashboard must not start it again.
            Page fresh = ctx.newPage();
            fresh.navigate(base() + "/");
            fresh.waitForLoadState(LoadState.NETWORKIDLE);
            assertThat(fresh.locator(".driver-popover").count()).isZero();
        } finally {
            ctx.close();
        }
    }
}
