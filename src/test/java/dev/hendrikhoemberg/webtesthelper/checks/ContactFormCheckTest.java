package dev.hendrikhoemberg.webtesthelper.checks;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import dev.hendrikhoemberg.webtesthelper.model.CheckFinding;
import dev.hendrikhoemberg.webtesthelper.model.CheckType;
import dev.hendrikhoemberg.webtesthelper.model.CrawlBudget;
import dev.hendrikhoemberg.webtesthelper.model.FormTestMode;
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
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Tag("browser")
class ContactFormCheckTest {

    private static FixtureSite fixtureSite;
    private static Playwright playwright;
    private static Browser browser;
    private final ContactFormCheck check = new ContactFormCheck();

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
                CrawlBudget.DEFAULT, List.of(), List.of(), List.of(), true, null, Map.of(), FormTestMode.NO_SUBMIT);
    }

    private CheckConfig checkConfig() {
        return new CheckConfig(Severity.ERROR, Map.of(), Snapshots.facts());
    }

    @Test
    void healthyContactFormEmitsNoFindingsAndSendsNothing() {
        try (BrowserContext context = browser.newContext()) {
            Page page = context.newPage();
            String initialUrl = fixtureSite.url("interaktiv/formular.html");
            page.navigate(initialUrl);

            int requestsBefore = fixtureSite.requestCount("/kontakt/gesendet");
            List<CheckFinding> findings = check.evaluate(page, siteContext(), checkConfig());
            int requestsAfter = fixtureSite.requestCount("/kontakt/gesendet");

            assertThat(findings).isEmpty();
            assertThat(requestsAfter - requestsBefore).isEqualTo(0);
        }
    }

    @Test
    void brokenFormWithUnselectableOptionEmitsRejectsValidFinding() {
        try (BrowserContext context = browser.newContext()) {
            Page page = context.newPage();
            String initialUrl = fixtureSite.url("interaktiv/formular-kaputt.html");
            page.navigate(initialUrl);

            List<CheckFinding> findings = check.evaluate(page, siteContext(), checkConfig());

            assertThat(findings).hasSize(1);
            CheckFinding finding = findings.get(0);
            assertThat(finding.type()).isEqualTo(CheckType.CONTACT_FORM);
            assertThat(finding.severity()).isEqualTo(Severity.ERROR);
            assertThat(finding.messageKey()).isEqualTo("finding.CONTACT_FORM.rejectsValid");
            assertThat(finding.observedOn()).isEqualTo(Snapshots.url(initialUrl));
            assertThat(finding.messageArgs()).hasSize(2);
            assertThat(finding.messageArgs().get(0)).isEqualTo(Snapshots.url(initialUrl).value());
            assertThat(finding.messageArgs().get(1).toLowerCase()).contains("anrede");
        }
    }

    @Test
    void captchaProtectedFormAbstainsRatherThanReportingEmptyFindings() {
        try (BrowserContext context = browser.newContext()) {
            Page page = context.newPage();
            String initialUrl = fixtureSite.url("interaktiv/formular-captcha.html");
            page.navigate(initialUrl);

            // An empty list would be a lie: §6.4 would let the run resolve last month's finding
            // on a page it could not read.
            assertThatThrownBy(() -> check.evaluate(page, siteContext(), checkConfig()))
                    .isInstanceOf(CheckAbstainedException.class)
                    .hasMessageContaining("Captcha");
        }
    }

    @Test
    void multipleFormsPageSelectsContactFormAndNeverTypesIntoPasswordBox() {
        try (BrowserContext context = browser.newContext()) {
            Page page = context.newPage();
            String initialUrl = fixtureSite.url("interaktiv/formular-viele.html");
            page.navigate(initialUrl);

            List<CheckFinding> findings = check.evaluate(page, siteContext(), checkConfig());

            assertThat(findings).isEmpty();

            // Single assertion in the plan that proves the check does not type into a password box
            Object passwordValue = page.evaluate("() => document.getElementById('password') ? document.getElementById('password').value : ''");
            assertThat(passwordValue).isEqualTo("");
        }
    }

    @Test
    void checkDescriptorProperties() {
        assertThat(check.type()).isEqualTo(CheckType.CONTACT_FORM);
        assertThat(check.defaultSeverity()).isEqualTo(Severity.ERROR);
        assertThat(check.messageKeys()).containsExactlyInAnyOrder(
                "finding.CONTACT_FORM.rejectsValid",
                "finding.CONTACT_FORM.acceptsInvalid",
                "finding.CONTACT_FORM.noSuccess",
                "finding.CONTACT_FORM.errorShown",
                "finding.CONTACT_FORM.notDelivered"
        );
    }

    @Test
    void targetsReturnsSnapshotsWithForms() {
        SiteContext site = siteContext();
        PageSnapshot home = Snapshots.page(fixtureSite.url("")).build();
        PageSnapshot contact = Snapshots.page(fixtureSite.url("kontakt.html"))
                .form("kontaktformular", "/kontakt/gesendet", "post")
                .build();
        RunSnapshots snapshots = new RunSnapshots(1L, site, List.of(home, contact), SoftNotFoundProbe.NONE);

        List<NormalizedUrl> targets = check.targets(snapshots, site, 5);

        assertThat(targets).containsExactly(Snapshots.url(fixtureSite.url("kontakt.html")));
    }
}
