package dev.hendrikhoemberg.webtesthelper.checks;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import dev.hendrikhoemberg.webtesthelper.model.CheckFinding;
import dev.hendrikhoemberg.webtesthelper.model.CheckType;
import dev.hendrikhoemberg.webtesthelper.model.CrawlBudget;
import dev.hendrikhoemberg.webtesthelper.model.NormalizedUrl;
import dev.hendrikhoemberg.webtesthelper.model.PageSnapshot;
import dev.hendrikhoemberg.webtesthelper.model.RunSnapshots;
import dev.hendrikhoemberg.webtesthelper.model.Severity;
import dev.hendrikhoemberg.webtesthelper.model.SiteContext;
import dev.hendrikhoemberg.webtesthelper.model.SoftNotFoundProbe;
import dev.hendrikhoemberg.webtesthelper.support.FixtureSite;
import dev.hendrikhoemberg.webtesthelper.support.Snapshots;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("browser")
class CookieBannerCheckTest {

    private static FixtureSite fixtureSite;
    private static Playwright playwright;
    private static Browser browser;
    private final CookieBannerCheck check = new CookieBannerCheck();

    @BeforeAll
    static void start() {
        fixtureSite = FixtureSite.start();
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
        if (fixtureSite != null) {
            fixtureSite.close();
        }
    }

    private SiteContext siteContext() {
        return new SiteContext(1L, "Test", Snapshots.url(fixtureSite.url("")),
                CrawlBudget.DEFAULT, List.of(), List.of(), List.of(), true, null, Map.of());
    }

    private CheckConfig checkConfig() {
        return new CheckConfig(Severity.ERROR, Map.of(), Snapshots.facts());
    }

    @Test
    void stubbornBannerEmitsExactlyOneUndismissableFinding() {
        try (BrowserContext context = browser.newContext()) {
            Page page = context.newPage();
            String url = fixtureSite.url("interaktiv/banner-hartnaeckig.html");
            page.navigate(url);

            List<CheckFinding> findings = check.evaluate(page, siteContext(), checkConfig());

            assertThat(findings).hasSize(1);
            CheckFinding finding = findings.get(0);
            assertThat(finding.type()).isEqualTo(CheckType.COOKIE_BANNER);
            assertThat(finding.severity()).isEqualTo(Severity.ERROR);
            assertThat(finding.messageKey()).isEqualTo("finding.COOKIE_BANNER.undismissable");
            assertThat(finding.observedOn()).isEqualTo(Snapshots.url(url));
            assertThat(finding.subjectKey()).isEqualTo("cookie-hinweis");
            assertThat(finding.messageArgs()).containsExactly("cookie-hinweis");
        }
    }

    @Test
    void dismissableBannerEmitsNoFindings() {
        try (BrowserContext context = browser.newContext()) {
            Page page = context.newPage();
            page.navigate(fixtureSite.url("interaktiv/banner.html"));

            List<CheckFinding> findings = check.evaluate(page, siteContext(), checkConfig());

            assertThat(findings).isEmpty();
        }
    }

    @Test
    void pageWithoutBannerEmitsNoFindings() {
        try (BrowserContext context = browser.newContext()) {
            Page page = context.newPage();
            page.navigate(fixtureSite.url("interaktiv/ohne-banner.html"));

            List<CheckFinding> findings = check.evaluate(page, siteContext(), checkConfig());

            assertThat(findings).isEmpty();
        }
    }

    @Test
    void checkDescriptorProperties() {
        assertThat(check.type()).isEqualTo(CheckType.COOKIE_BANNER);
        assertThat(check.defaultSeverity()).isEqualTo(Severity.ERROR);
        assertThat(check.messageKeys()).containsExactly("finding.COOKIE_BANNER.undismissable");
    }

    @Test
    void targetsReturnsHomepageSnapshotUrl() {
        SiteContext site = siteContext();
        PageSnapshot home = Snapshots.page(fixtureSite.url("")).build();
        RunSnapshots snapshots = new RunSnapshots(1L, site, List.of(home), SoftNotFoundProbe.NONE);

        List<NormalizedUrl> targets = check.targets(snapshots, site, 5);

        assertThat(targets).containsExactly(Snapshots.url(fixtureSite.url("")));
    }
}
