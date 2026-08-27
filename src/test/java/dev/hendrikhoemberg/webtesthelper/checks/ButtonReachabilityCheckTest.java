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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Tag("browser")
class ButtonReachabilityCheckTest {

    private static FixtureSite fixtureSite;
    private static Playwright playwright;
    private static Browser browser;
    private final ButtonReachabilityCheck check = new ButtonReachabilityCheck();

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

    private SiteContext siteContextWithPins(List<String> pins) {
        return new SiteContext(1L, "Test", Snapshots.url(fixtureSite.url("")),
                CrawlBudget.DEFAULT, List.of(), List.of(), pins, true, null, Map.of());
    }

    private CheckConfig checkConfig() {
        return new CheckConfig(Severity.WARN, Map.of(), Snapshots.facts());
    }

    @Test
    void knoepfeHtmlEmitsFindingsOnlyForDeadButtonsAndAvoidsDangerousControls() {
        try (BrowserContext context = browser.newContext()) {
            Page page = context.newPage();
            String initialUrl = fixtureSite.url("interaktiv/knoepfe.html");
            page.navigate(initialUrl);

            List<CheckFinding> findings = check.evaluate(page, siteContext(), checkConfig());

            NormalizedUrl expectedObservedOn = Snapshots.url(initialUrl);

            // Exactly two dead controls: #tut-nichts and #anker-tut-nichts (the fragment trap)
            assertThat(findings).hasSize(2);
            assertThat(findings).extracting(CheckFinding::subjectKey)
                    .containsExactlyInAnyOrder("Tut nichts", "Anker tut nichts");

            for (CheckFinding finding : findings) {
                assertThat(finding.type()).isEqualTo(CheckType.BUTTON_REACHABILITY);
                assertThat(finding.severity()).isEqualTo(Severity.WARN);
                assertThat(finding.observedOn()).isEqualTo(expectedObservedOn);
                assertThat(finding.messageKey()).isEqualTo("finding.BUTTON_REACHABILITY.dead");
                assertThat(finding.messageArgs()).containsExactly(finding.subjectKey(), expectedObservedOn.value());
            }

            // Dangerous and form controls were never clicked
            assertThat(fixtureSite.requestCount("/verboten-geklickt"))
                    .as("Dangerous delete action must never be clicked")
                    .isEqualTo(0);
            assertThat(fixtureSite.requestCount("/formular"))
                    .as("Form submit must never be clicked")
                    .isEqualTo(0);

            // D88: page.url() is back on knoepfe.html when evaluate returns
            assertThat(page.url()).isEqualTo(initialUrl);
        }
    }

    @Test
    void unruhigHtmlThrowsCheckAbstainedExceptionDueToRestlessDom() {
        try (BrowserContext context = browser.newContext()) {
            Page page = context.newPage();
            String initialUrl = fixtureSite.url("interaktiv/unruhig.html");
            page.navigate(initialUrl);

            // On a restless page whose DOM continually mutates, any button click would falsely appear
            // to produce a DOM change. Returning an empty list would claim the page was verified clean
            // and resolve open findings. CheckAbstainedException correctly signals that the check
            // could not judge the page (D86).
            assertThatThrownBy(() -> check.evaluate(page, siteContext(), checkConfig()))
                    .isInstanceOf(CheckAbstainedException.class)
                    .hasMessageContaining("BUTTON_REACHABILITY")
                    .hasMessageContaining(Snapshots.url(initialUrl).value());
        }
    }

    @Test
    void checkDescriptorProperties() {
        assertThat(check.type()).isEqualTo(CheckType.BUTTON_REACHABILITY);
        assertThat(check.defaultSeverity()).isEqualTo(Severity.WARN);
        assertThat(check.messageKeys()).containsExactly("finding.BUTTON_REACHABILITY.dead");
    }

    @Test
    void targetsUsesKeyPagesOrHomepage() {
        SiteContext siteWithoutPins = siteContext();
        PageSnapshot home = Snapshots.page(fixtureSite.url("")).build();
        PageSnapshot keyPage = Snapshots.page(fixtureSite.url("interaktiv/knoepfe.html")).build();
        RunSnapshots snapshots = new RunSnapshots(1L, siteWithoutPins, List.of(home, keyPage), SoftNotFoundProbe.NONE);

        // Fallback to homepage when no pins configured
        List<NormalizedUrl> fallbackTargets = check.targets(snapshots, siteWithoutPins, 5);
        assertThat(fallbackTargets).containsExactly(Snapshots.url(fixtureSite.url("")));

        // Pinned key pages are used when present
        SiteContext siteWithPins = siteContextWithPins(List.of(fixtureSite.url("interaktiv/knoepfe.html")));
        List<NormalizedUrl> pinnedTargets = check.targets(snapshots, siteWithPins, 5);
        assertThat(pinnedTargets).containsExactly(Snapshots.url(fixtureSite.url("interaktiv/knoepfe.html")));
    }
}
