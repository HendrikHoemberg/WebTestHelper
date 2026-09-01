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

import dev.hendrikhoemberg.webtesthelper.support.SharedBrowser;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("browser")
@org.junit.jupiter.api.parallel.ResourceLock("browser")
class LanguageSwitcherCheckTest {

    private static FixtureSite fixtureSite;
    private static Browser browser;
    private final LanguageSwitcherCheck check = new LanguageSwitcherCheck();

    @BeforeAll
    static void start() {
        fixtureSite = FixtureSite.start();
        browser = SharedBrowser.browser();
    }

    @AfterAll
    static void stop() {
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
    void healthyLanguageSwitcherEmitsNoFindingsAndRestoresPageUrl() {
        try (BrowserContext context = browser.newContext()) {
            Page page = context.newPage();
            String initialUrl = fixtureSite.url("interaktiv/sprachen.html");
            page.navigate(initialUrl);

            List<CheckFinding> findings = check.evaluate(page, siteContext(), checkConfig());

            assertThat(findings).isEmpty();
            assertThat(page.url()).isEqualTo(initialUrl);
        }
    }

    @Test
    void brokenLanguageSwitcherEmitsThreeFindingsAndRestoresPageUrl() {
        try (BrowserContext context = browser.newContext()) {
            Page page = context.newPage();
            String initialUrl = fixtureSite.url("interaktiv/sprachen-kaputt.html");
            page.navigate(initialUrl);

            List<CheckFinding> findings = check.evaluate(page, siteContext(), checkConfig());

            assertThat(findings).hasSize(3);
            assertThat(page.url()).isEqualTo(initialUrl);

            NormalizedUrl expectedObservedOn = Snapshots.url(initialUrl);

            for (CheckFinding finding : findings) {
                assertThat(finding.type()).isEqualTo(CheckType.LANGUAGE_SWITCHER);
                assertThat(finding.severity()).isEqualTo(Severity.ERROR);
                assertThat(finding.observedOn()).isEqualTo(expectedObservedOn);
                assertThat(finding.subjectKey()).isNotEqualTo("Deutsch");
                assertThat(finding.messageArgs()).noneMatch(arg -> arg.contains("Deutsch"));
            }

            CheckFinding noNavFinding = findings.stream()
                    .filter(f -> f.messageKey().equals("finding.LANGUAGE_SWITCHER.noNavigation"))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("noNavigation finding missing"));
            assertThat(noNavFinding.subjectKey()).isEqualTo(expectedObservedOn.value());
            assertThat(noNavFinding.messageArgs()).containsExactly("Italiano");

            CheckFinding langUnchangedFinding = findings.stream()
                    .filter(f -> f.messageKey().equals("finding.LANGUAGE_SWITCHER.langUnchanged"))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("langUnchanged finding missing"));
            String frUrl = fixtureSite.url("interaktiv/fr/sprachen.html");
            assertThat(langUnchangedFinding.subjectKey()).isEqualTo(Snapshots.url(frUrl).value());
            assertThat(langUnchangedFinding.messageArgs()).containsExactly(Snapshots.url(frUrl).value(), "Français", "de");

            CheckFinding sameContentFinding = findings.stream()
                    .filter(f -> f.messageKey().equals("finding.LANGUAGE_SWITCHER.sameContent"))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("sameContent finding missing"));
            String enUrl = fixtureSite.url("interaktiv/en/sprachen-kaputt.html");
            assertThat(sameContentFinding.subjectKey()).isEqualTo(Snapshots.url(enUrl).value());
            assertThat(sameContentFinding.messageArgs()).containsExactly("English", Snapshots.url(enUrl).value());
        }
    }

    @Test
    void checkDescriptorProperties() {
        assertThat(check.type()).isEqualTo(CheckType.LANGUAGE_SWITCHER);
        assertThat(check.defaultSeverity()).isEqualTo(Severity.ERROR);
        assertThat(check.messageKeys()).containsExactlyInAnyOrder(
                "finding.LANGUAGE_SWITCHER.noNavigation",
                "finding.LANGUAGE_SWITCHER.langUnchanged",
                "finding.LANGUAGE_SWITCHER.sameContent"
        );
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
