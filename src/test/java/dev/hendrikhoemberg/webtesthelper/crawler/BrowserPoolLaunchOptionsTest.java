package dev.hendrikhoemberg.webtesthelper.crawler;

import com.microsoft.playwright.BrowserType;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class BrowserPoolLaunchOptionsTest {

    @Test
    void setsTheHeadlessFlag() {
        assertThat(BrowserPool.launchOptions(true, false).headless).isTrue();
        assertThat(BrowserPool.launchOptions(false, false).headless).isFalse();
    }

    @Test
    void addsTheNoSandboxArgOnlyWhenRequested() {
        assertThat(BrowserPool.launchOptions(true, true).args).contains("--no-sandbox");
        assertThat(BrowserPool.launchOptions(true, false).args).isNullOrEmpty();
        assertThat(BrowserPool.launchOptions(false, true).args).contains("--no-sandbox");
    }
}
