package dev.hendrikhoemberg.webtesthelper.support;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Playwright;

/**
 * JVM-wide singleton Chromium browser process for standalone check and runner tests.
 *
 * <p>Spawning a headless Chromium OS process takes 3–8 seconds in virtualized CI environments.
 * Reusing a single warm {@link Browser} instance and issuing isolated {@link com.microsoft.playwright.BrowserContext}
 * instances per test saves minutes across the suite while providing 100% state isolation.
 */
public final class SharedBrowser {

    private static volatile Playwright playwright;
    private static volatile Browser browser;

    private SharedBrowser() {}

    public static synchronized Playwright playwright() {
        if (playwright == null) {
            playwright = Playwright.create();
            Runtime.getRuntime().addShutdownHook(new Thread(SharedBrowser::shutdown, "shared-playwright-shutdown"));
        }
        return playwright;
    }

    public static synchronized Browser browser() {
        if (browser == null || !browser.isConnected()) {
            browser = playwright().chromium().launch(new BrowserType.LaunchOptions().setHeadless(true));
        }
        return browser;
    }

    private static synchronized void shutdown() {
        if (browser != null) {
            try {
                browser.close();
            } catch (Exception ignored) {}
            browser = null;
        }
        if (playwright != null) {
            try {
                playwright.close();
            } catch (Exception ignored) {}
            playwright = null;
        }
    }
}
