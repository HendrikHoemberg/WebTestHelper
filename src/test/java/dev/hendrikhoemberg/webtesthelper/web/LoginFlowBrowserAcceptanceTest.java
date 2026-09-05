package dev.hendrikhoemberg.webtesthelper.web;

import dev.hendrikhoemberg.webtesthelper.auth.AppRole;
import dev.hendrikhoemberg.webtesthelper.auth.AppUserService;
import dev.hendrikhoemberg.webtesthelper.auth.UserValidationException;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.options.RequestOptions;
import dev.hendrikhoemberg.webtesthelper.support.AbstractPostgresTest;
import dev.hendrikhoemberg.webtesthelper.support.SharedBrowser;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression test for a real-browser failure MockMvc cannot show: Chromium requests
 * /favicon.ico on page load, the app had none, the 404's error dispatch was saved by
 * Spring Security's request cache, and the login success handler redirected into
 * /error?continue (HTTP 500). Real browser, real Postgres, real server.
 */
@Tag("browser")
@org.junit.jupiter.api.parallel.ResourceLock("browser")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LoginFlowBrowserAcceptanceTest extends AbstractPostgresTest {

    @LocalServerPort
    int port;

    @Autowired
    AppUserService appUserService;

    Browser browser;

    @BeforeAll
    void setUserAndLaunch() {
        // The Postgres container is shared across the whole suite; the user may already exist
        // from an earlier run inside the same JVM, so creating is best-effort.
        try {
            appUserService.create("browser-login", "test-pass-42", AppRole.ADMIN);
        } catch (UserValidationException ex) {
            if (!"user.username.duplicate".equals(ex.messageKey())) {
                throw ex;
            }
        }
        browser = SharedBrowser.browser();
    }

    @AfterAll
    void closeBrowser() {
    }

    private String base() {
        return "http://localhost:" + port;
    }

    @Test
    void faviconIsServed() {
        Page page = browser.newPage();
        // No auto-redirect: with the bug the 404 logged the /error request and the response was a
        // 302 to /anmelden — following it would fake a 200 and the regression would pass silently.
        int status = page.request().get(base() + "/favicon.ico",
                RequestOptions.create().setMaxRedirects(0)).status();
        assertThat(status).isEqualTo(200);
        page.close();
    }

    @Test
    void loginWithRealBrowserLandsOnDashboard() {
        Page page = browser.newPage();
        // Protected URL first: the anonymous redirect creates the session Spring Security will
        // pollute with the favicon's saved /error request below.
        page.navigate(base() + "/");
        assertThat(page.locator(".anmelde-karte").count()).isEqualTo(1);
        // A real browser asks for the favicon in this same session; the headless shell sometimes
        // skips it, so request it explicitly to make the loop deterministic.
        page.request().get(base() + "/favicon.ico");
        page.fill("input[name='username']", "browser-login");
        page.fill("input[name='password']", "test-pass-42");
        page.click("button[type='submit']");
        // Spring Security 7 appends ?continue=<request-URI> to the saved-request redirect, so
        // wait for the path "/" rather than a glob that would require an exact URL.
        page.waitForURL(url -> {
            try {
                return new java.net.URI(url).getPath().equals("/");
            } catch (Exception ex) {
                return false;
            }
        }, new Page.WaitForURLOptions().setTimeout(15000));
        assertThat(page.url()).startsWith(base() + "/");
        assertThat(page.url()).doesNotContain("/error");
        assertThat(page.locator(".hinweis:empty").first()
                .evaluate("el => getComputedStyle(el).display")).isEqualTo("none");
        page.close();
    }
}
