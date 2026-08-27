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
import dev.hendrikhoemberg.webtesthelper.model.Mailbox;
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

import dev.hendrikhoemberg.webtesthelper.model.RunFacts;
import dev.hendrikhoemberg.webtesthelper.model.RunScope;
import dev.hendrikhoemberg.webtesthelper.model.TlsCertificateFact;
import dev.hendrikhoemberg.webtesthelper.model.UrlVerifications;

import java.time.Duration;
import java.time.Instant;
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
        return siteContext(FormTestMode.NO_SUBMIT);
    }

    private SiteContext siteContext(FormTestMode mode) {
        return new SiteContext(1L, "Test", Snapshots.url(fixtureSite.url("")),
                CrawlBudget.DEFAULT, List.of(), List.of(), List.of(), true, null, Map.of(), mode);
    }

    private CheckConfig checkConfig() {
        return checkConfig(RunScope.FULL);
    }

    private CheckConfig checkConfig(RunScope scope) {
        RunFacts facts = new RunFacts(1L, scope, Instant.EPOCH, SoftNotFoundProbe.NONE,
                UrlVerifications.EMPTY, TlsCertificateFact.NONE, List.of());
        return new CheckConfig(Severity.ERROR, Map.of(), facts);
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

    @Test
    void submitModeAgainstHealthyFormSucceedsLeavesNoFindingsAndRestoresUrl() {
        try (BrowserContext context = browser.newContext()) {
            Page page = context.newPage();
            String initialUrl = fixtureSite.url("interaktiv/formular.html");
            page.navigate(initialUrl);

            int requestsBefore = fixtureSite.requestCount("/kontakt/gesendet");
            List<CheckFinding> findings = check.evaluate(page, siteContext(FormTestMode.SUBMIT), checkConfig(RunScope.DEEP));
            int requestsAfter = fixtureSite.requestCount("/kontakt/gesendet");

            assertThat(findings).isEmpty();
            assertThat(requestsAfter - requestsBefore).isEqualTo(1);
            assertThat(page.url()).isEqualTo(initialUrl);

            // Honeypot assertion: posted body recorded by FixtureSite
            String postedBody = fixtureSite.lastPostedBody("/kontakt/gesendet");
            assertThat(postedBody).isNotNull();
            assertThat(postedBody).contains("csrf=abc123");
            assertThat(postedBody).contains("website=");
            assertThat(postedBody).contains("fax=");
            assertThat(postedBody).contains("url2=");
            assertThat(postedBody).contains("company2=");
            assertThat(postedBody).contains("website=&");
            assertThat(postedBody).contains("fax=&");
            assertThat(postedBody).contains("url2=&");
            assertThat(postedBody).contains("company2=&");
        }
    }

    @Test
    void submitModeAgainstFormWithoutSuccessEmitsNoSuccessFinding() {
        try (BrowserContext context = browser.newContext()) {
            Page page = context.newPage();
            String initialUrl = fixtureSite.url("interaktiv/formular-ohne-erfolg.html");
            page.navigate(initialUrl);

            List<CheckFinding> findings = check.evaluate(page, siteContext(FormTestMode.SUBMIT), checkConfig(RunScope.DEEP));

            assertThat(findings).hasSize(1);
            CheckFinding finding = findings.get(0);
            assertThat(finding.type()).isEqualTo(CheckType.CONTACT_FORM);
            assertThat(finding.severity()).isEqualTo(Severity.ERROR);
            assertThat(finding.messageKey()).isEqualTo("finding.CONTACT_FORM.noSuccess");
            assertThat(finding.observedOn()).isEqualTo(Snapshots.url(initialUrl));
            assertThat(finding.messageArgs()).containsExactly(Snapshots.url(initialUrl).value());
            assertThat(page.url()).isEqualTo(initialUrl);
        }
    }

    @Test
    void submitModeWhenNoSubmitButtonPresentAbstains() {
        try (BrowserContext context = browser.newContext()) {
            Page page = context.newPage();
            page.setContent("""
                    <!doctype html><html><body>
                    <form id="nosubmit" action="/kontakt/gesendet" method="post">
                      <input type="text" name="name" id="name" required>
                      <input type="email" name="email" id="email" required>
                      <textarea name="message" id="message" required></textarea>
                    </form>
                    </body></html>
                    """);

            assertThatThrownBy(() -> check.evaluate(page, siteContext(FormTestMode.SUBMIT), checkConfig(RunScope.DEEP)))
                    .isInstanceOf(CheckAbstainedException.class)
                    .hasMessageContaining("kein Absende-Knopf gefunden");
        }
    }

    private static class FakeMailbox implements Mailbox {
        private final String address;
        private final Result result;
        private String receivedToken;
        private Duration receivedBudget;

        FakeMailbox(String address, Result result) {
            this.address = address;
            this.result = result;
        }

        @Override
        public String address() {
            return address;
        }

        @Override
        public Result awaitToken(String token, Duration budget) {
            this.receivedToken = token;
            this.receivedBudget = budget;
            return result;
        }
    }

    @Test
    void timeoutReturns90Seconds() {
        assertThat(check.timeout()).isEqualTo(Duration.ofSeconds(90));
    }

    @Test
    void submitAndVerifyMailAgainstSilentFormEmitsNotDeliveredFindingWithGermanSuccessText() {
        FakeMailbox mailbox = new FakeMailbox("pruefpostfach@example.com", Mailbox.Result.NOT_FOUND);
        ContactFormCheck checkWithMailbox = new ContactFormCheck(mailbox);

        try (BrowserContext context = browser.newContext()) {
            Page page = context.newPage();
            String initialUrl = fixtureSite.url("interaktiv/formular-still.html");
            page.navigate(initialUrl);

            int requestsBefore = fixtureSite.requestCount("/kontakt/still");
            List<CheckFinding> findings = checkWithMailbox.evaluate(page, siteContext(FormTestMode.SUBMIT_AND_VERIFY_MAIL), checkConfig(RunScope.DEEP));
            int requestsAfter = fixtureSite.requestCount("/kontakt/still");

            assertThat(requestsAfter - requestsBefore).isEqualTo(1);
            assertThat(findings).hasSize(1);
            CheckFinding finding = findings.get(0);
            assertThat(finding.type()).isEqualTo(CheckType.CONTACT_FORM);
            assertThat(finding.severity()).isEqualTo(Severity.ERROR);
            assertThat(finding.messageKey()).isEqualTo("finding.CONTACT_FORM.notDelivered");
            assertThat(finding.observedOn()).isEqualTo(Snapshots.url(initialUrl));
            assertThat(finding.messageArgs()).hasSize(3);
            assertThat(finding.messageArgs().get(0)).isEqualTo(Snapshots.url(initialUrl).value());
            assertThat(finding.messageArgs().get(1)).isEqualTo("Vielen Dank, Ihre Nachricht wurde versendet.");
            assertThat(finding.messageArgs().get(2)).isEqualTo("60");
            assertThat(page.url()).isEqualTo(initialUrl);

            assertThat(mailbox.receivedToken).isNotNull();
            assertThat(mailbox.receivedToken).startsWith("WTH-");
            assertThat(mailbox.receivedBudget).isEqualTo(Duration.ofSeconds(60));

            // Verify email field contained the mailbox address
            String postedBody = fixtureSite.lastPostedBody("/kontakt/still");
            assertThat(postedBody).contains("email=pruefpostfach%40example.com");
        }
    }

    @Test
    void submitAndVerifyMailAgainstSilentFormWithFoundMailboxEmitsNoFindings() {
        FakeMailbox mailbox = new FakeMailbox("pruefpostfach@example.com", Mailbox.Result.FOUND);
        ContactFormCheck checkWithMailbox = new ContactFormCheck(mailbox);

        try (BrowserContext context = browser.newContext()) {
            Page page = context.newPage();
            String initialUrl = fixtureSite.url("interaktiv/formular-still.html");
            page.navigate(initialUrl);

            int requestsBefore = fixtureSite.requestCount("/kontakt/still");
            List<CheckFinding> findings = checkWithMailbox.evaluate(page, siteContext(FormTestMode.SUBMIT_AND_VERIFY_MAIL), checkConfig(RunScope.DEEP));
            int requestsAfter = fixtureSite.requestCount("/kontakt/still");

            assertThat(requestsAfter - requestsBefore).isEqualTo(1);
            assertThat(findings).isEmpty();
            assertThat(page.url()).isEqualTo(initialUrl);

            assertThat(mailbox.receivedToken).isNotNull();
            assertThat(mailbox.receivedToken).startsWith("WTH-");
            assertThat(mailbox.receivedBudget).isEqualTo(Duration.ofSeconds(60));
        }
    }

    @Test
    void submitAndVerifyMailAgainstFormWithoutSuccessEmitsNoSuccessAndNeverAwaitsToken() {
        FakeMailbox mailbox = new FakeMailbox("pruefpostfach@example.com", Mailbox.Result.NOT_FOUND);
        ContactFormCheck checkWithMailbox = new ContactFormCheck(mailbox);

        try (BrowserContext context = browser.newContext()) {
            Page page = context.newPage();
            String initialUrl = fixtureSite.url("interaktiv/formular-ohne-erfolg.html");
            page.navigate(initialUrl);

            List<CheckFinding> findings = checkWithMailbox.evaluate(page, siteContext(FormTestMode.SUBMIT_AND_VERIFY_MAIL), checkConfig(RunScope.DEEP));

            // Step 3's rule: verdict is not SUCCESS -> emit noSuccess and stop (no notDelivered)
            assertThat(findings).hasSize(1);
            CheckFinding finding = findings.get(0);
            assertThat(finding.type()).isEqualTo(CheckType.CONTACT_FORM);
            assertThat(finding.severity()).isEqualTo(Severity.ERROR);
            assertThat(finding.messageKey()).isEqualTo("finding.CONTACT_FORM.noSuccess");
            assertThat(finding.observedOn()).isEqualTo(Snapshots.url(initialUrl));
            assertThat(page.url()).isEqualTo(initialUrl);

            assertThat(mailbox.receivedToken).isNull();
        }
    }

    /**
     * D89: Mailbox failure (wrong password, unreachable IMAP server, etc.) must abstain,
     * not emit false-positive notDelivered findings.
     */
    @Test
    void submitAndVerifyMailWhenMailboxUnavailableAbstains() {
        FakeMailbox mailbox = new FakeMailbox("pruefpostfach@example.com", Mailbox.Result.UNAVAILABLE);
        ContactFormCheck checkWithMailbox = new ContactFormCheck(mailbox);

        try (BrowserContext context = browser.newContext()) {
            Page page = context.newPage();
            String initialUrl = fixtureSite.url("interaktiv/formular-still.html");
            page.navigate(initialUrl);

            assertThatThrownBy(() -> checkWithMailbox.evaluate(page, siteContext(FormTestMode.SUBMIT_AND_VERIFY_MAIL), checkConfig(RunScope.DEEP)))
                    .isInstanceOf(CheckAbstainedException.class)
                    .hasMessageContaining("Prüfpostfach nicht erreichbar");

            // Page must still be restored (D88)
            assertThat(page.url()).isEqualTo(initialUrl);
        }
    }

    @Test
    void submitAndVerifyMailWhenMailboxUnconfiguredAbstains() {
        ContactFormCheck checkWithoutMailbox = new ContactFormCheck(Mailbox.UNCONFIGURED);

        try (BrowserContext context = browser.newContext()) {
            Page page = context.newPage();
            String initialUrl = fixtureSite.url("interaktiv/formular-still.html");
            page.navigate(initialUrl);

            assertThatThrownBy(() -> checkWithoutMailbox.evaluate(page, siteContext(FormTestMode.SUBMIT_AND_VERIFY_MAIL), checkConfig(RunScope.DEEP)))
                    .isInstanceOf(CheckAbstainedException.class)
                    .hasMessageContaining("kein Prüfpostfach konfiguriert");
        }
    }

    /**
     * Spec 7.2's second half of NO_SUBMIT: "invalid input rejected". The form's e-mail field is a
     * plain required text input, so the browser accepts "kein-at-zeichen" and the check must say so
     * at a fixed WARN — the TlsCertCheck precedent — while the site's severity stays ERROR.
     */
    @Test
    void laxEmailFieldEmitsAcceptsInvalidAtWarnAndSubmitsNothing() {
        FakeMailbox mailbox = new FakeMailbox("pruefpostfach@example.com", Mailbox.Result.NOT_FOUND);
        ContactFormCheck checkWithMailbox = new ContactFormCheck(mailbox);

        try (BrowserContext context = browser.newContext()) {
            Page page = context.newPage();
            String initialUrl = fixtureSite.url("interaktiv/formular-lax-email.html");
            page.navigate(initialUrl);

            int requestsBefore = fixtureSite.requestCount("/kontakt/still");
            List<CheckFinding> findings = checkWithMailbox.evaluate(page, siteContext(), checkConfig());
            int requestsAfter = fixtureSite.requestCount("/kontakt/still");

            assertThat(requestsAfter - requestsBefore).isEqualTo(0);
            assertThat(findings).hasSize(1);
            CheckFinding finding = findings.get(0);
            assertThat(finding.type()).isEqualTo(CheckType.CONTACT_FORM);
            assertThat(finding.severity()).isEqualTo(Severity.WARN);
            assertThat(finding.messageKey()).isEqualTo("finding.CONTACT_FORM.acceptsInvalid");
            assertThat(finding.observedOn()).isEqualTo(Snapshots.url(initialUrl));
            assertThat(finding.messageArgs()).containsExactly(Snapshots.url(initialUrl).value(), "kein-at-zeichen");

            // The plausible address is restored, so the field is not left holding the probe value.
            Object emailValue = page.evaluate("() => document.getElementById('email').value");
            assertThat(emailValue).isEqualTo("pruefpostfach@example.com");
        }
    }

    /**
     * The regression this fixture exists for: acceptsInvalid must not suppress the submit branch.
     * A form with a lax e-mail field that says "Vielen Dank" and delivers nothing is exactly the
     * failure spec 7.2 calls the most common real one, and it has to be reported alongside the WARN.
     */
    @Test
    void laxEmailFieldStillSubmitsAndVerifiesDelivery() {
        FakeMailbox mailbox = new FakeMailbox("pruefpostfach@example.com", Mailbox.Result.NOT_FOUND);
        ContactFormCheck checkWithMailbox = new ContactFormCheck(mailbox);

        try (BrowserContext context = browser.newContext()) {
            Page page = context.newPage();
            String initialUrl = fixtureSite.url("interaktiv/formular-lax-email.html");
            page.navigate(initialUrl);

            int requestsBefore = fixtureSite.requestCount("/kontakt/still");
            List<CheckFinding> findings = checkWithMailbox.evaluate(
                    page, siteContext(FormTestMode.SUBMIT_AND_VERIFY_MAIL), checkConfig(RunScope.DEEP));
            int requestsAfter = fixtureSite.requestCount("/kontakt/still");

            assertThat(requestsAfter - requestsBefore).isEqualTo(1);
            assertThat(mailbox.receivedToken).isNotNull();
            assertThat(findings).hasSize(2);
            assertThat(findings).extracting(CheckFinding::messageKey)
                    .containsExactly("finding.CONTACT_FORM.acceptsInvalid", "finding.CONTACT_FORM.notDelivered");
            assertThat(findings).extracting(CheckFinding::severity)
                    .containsExactly(Severity.WARN, Severity.ERROR);
            assertThat(page.url()).isEqualTo(initialUrl);
        }
    }

    /**
     * The e-mail typed into the form is the verification mailbox's address in every mode that has
     * one, not a fictional recipient: a reply to the test message must reach us.
     */
    @Test
    void submitModeTypesTheMailboxAddressIntoTheForm() {
        FakeMailbox mailbox = new FakeMailbox("pruefpostfach@example.com", Mailbox.Result.NOT_FOUND);
        ContactFormCheck checkWithMailbox = new ContactFormCheck(mailbox);

        try (BrowserContext context = browser.newContext()) {
            Page page = context.newPage();
            page.navigate(fixtureSite.url("interaktiv/formular.html"));

            checkWithMailbox.evaluate(page, siteContext(FormTestMode.SUBMIT), checkConfig(RunScope.DEEP));

            assertThat(fixtureSite.lastPostedBody("/kontakt/gesendet"))
                    .contains("email=pruefpostfach%40example.com");
            assertThat(mailbox.receivedToken).isNull();
        }
    }
}
