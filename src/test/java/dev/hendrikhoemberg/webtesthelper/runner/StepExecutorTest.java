package dev.hendrikhoemberg.webtesthelper.runner;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import dev.hendrikhoemberg.webtesthelper.model.AssertionType;
import dev.hendrikhoemberg.webtesthelper.model.JourneyStep;
import dev.hendrikhoemberg.webtesthelper.model.LocatorCandidate;
import dev.hendrikhoemberg.webtesthelper.model.LocatorStrategy;
import dev.hendrikhoemberg.webtesthelper.model.StepAction;
import dev.hendrikhoemberg.webtesthelper.model.StepAssertion;
import dev.hendrikhoemberg.webtesthelper.support.FixtureSite;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("browser")
class StepExecutorTest {

    private static FixtureSite fixtureSite;
    private static Playwright playwright;
    private static Browser browser;
    private static Page page;

    @BeforeAll
    static void start() {
        fixtureSite = FixtureSite.start();
        playwright = Playwright.create();
        browser = playwright.chromium().launch(new BrowserType.LaunchOptions().setHeadless(true));
        page = browser.newPage();
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

    @BeforeEach
    void resetPage() {
        page.navigate(fixtureSite.url("reise/start.html"));
    }

    private static JourneyStep createStep(
            StepAction action,
            List<LocatorCandidate> candidates,
            String value,
            StepAssertion assertion,
            boolean optional,
            int timeoutMs
    ) {
        return new JourneyStep(
                UUID.randomUUID(),
                0,
                action,
                candidates,
                value,
                assertion,
                optional,
                timeoutMs
        );
    }

    @Test
    void gotoNavigatesToGivenUrl() {
        String targetUrl = fixtureSite.url("reise/schritt2.html");
        JourneyStep step = createStep(StepAction.GOTO, List.of(), targetUrl, null, false, 5000);

        StepOutcome outcome = StepExecutor.execute(page, step, targetUrl);

        assertThat(outcome.stepId()).isEqualTo(step.id());
        assertThat(outcome.status()).isEqualTo(StepStatus.PASSED);
        assertThat(outcome.winner()).isNull();
        assertThat(outcome.drifted()).isFalse();
        assertThat(outcome.failureMessageKey()).isNull();
        assertThat(page.url()).isEqualTo(targetUrl);
    }

    @Test
    void clickFollowsLink() {
        LocatorCandidate linkCandidate = new LocatorCandidate(LocatorStrategy.TEST_ID, "reise-start", 0);
        JourneyStep step = createStep(StepAction.CLICK, List.of(linkCandidate), null, null, false, 5000);

        StepOutcome outcome = StepExecutor.execute(page, step, null);

        assertThat(outcome.status()).isEqualTo(StepStatus.PASSED);
        assertThat(outcome.winner()).isEqualTo(linkCandidate);
        assertThat(outcome.drifted()).isFalse();
        assertThat(page.url()).contains("schritt2.html");
    }

    @Test
    void fillTypesIntoInput() {
        page.navigate(fixtureSite.url("reise/schritt2.html"));
        LocatorCandidate nameInput = new LocatorCandidate(LocatorStrategy.TEST_ID, "reise-name", 0);
        JourneyStep step = createStep(StepAction.FILL, List.of(nameInput), "{{name}}", null, false, 5000);

        StepOutcome outcome = StepExecutor.execute(page, step, "Erika Mustermann");

        assertThat(outcome.status()).isEqualTo(StepStatus.PASSED);
        assertThat(outcome.winner()).isEqualTo(nameInput);
        assertThat(outcome.drifted()).isFalse();
        assertThat(page.getByTestId("reise-name").inputValue()).isEqualTo("Erika Mustermann");
    }

    @Test
    void selectPicksAnOption() {
        page.navigate(fixtureSite.url("reise/schritt2.html"));
        LocatorCandidate selectCandidate = new LocatorCandidate(LocatorStrategy.TEST_ID, "reise-ziel", 0);
        JourneyStep step = createStep(StepAction.SELECT, List.of(selectCandidate), "paris", null, false, 5000);

        StepOutcome outcome = StepExecutor.execute(page, step, "paris");

        assertThat(outcome.status()).isEqualTo(StepStatus.PASSED);
        assertThat(outcome.winner()).isEqualTo(selectCandidate);
        assertThat(outcome.drifted()).isFalse();
        assertThat(page.getByTestId("reise-ziel").inputValue()).isEqualTo("paris");
    }

    @Test
    void pressWithLocatorSendsKey() {
        page.navigate(fixtureSite.url("reise/schritt2.html"));
        LocatorCandidate nameInput = new LocatorCandidate(LocatorStrategy.TEST_ID, "reise-name", 0);
        JourneyStep step = createStep(StepAction.PRESS, List.of(nameInput), "a", null, false, 5000);

        StepOutcome outcome = StepExecutor.execute(page, step, "a");

        assertThat(outcome.status()).isEqualTo(StepStatus.PASSED);
        assertThat(outcome.winner()).isEqualTo(nameInput);
        assertThat(outcome.drifted()).isFalse();
        assertThat(page.getByTestId("reise-name").inputValue()).isEqualTo("a");
    }

    @Test
    void pressWithoutLocatorSendsKeyToPage() {
        page.navigate(fixtureSite.url("reise/schritt2.html"));
        JourneyStep step = createStep(StepAction.PRESS, List.of(), "Tab", null, false, 5000);

        StepOutcome outcome = StepExecutor.execute(page, step, "Tab");

        assertThat(outcome.status()).isEqualTo(StepStatus.PASSED);
        assertThat(outcome.winner()).isNull();
        assertThat(outcome.drifted()).isFalse();
    }

    @Test
    void hoverResolvesWithoutNavigating() {
        LocatorCandidate linkCandidate = new LocatorCandidate(LocatorStrategy.TEST_ID, "reise-start", 0);
        JourneyStep step = createStep(StepAction.HOVER, List.of(linkCandidate), null, null, false, 5000);

        StepOutcome outcome = StepExecutor.execute(page, step, null);

        assertThat(outcome.status()).isEqualTo(StepStatus.PASSED);
        assertThat(outcome.winner()).isEqualTo(linkCandidate);
        assertThat(outcome.drifted()).isFalse();
        assertThat(page.url()).contains("start.html");
    }

    @Test
    void waitForSucceedsOnPresentElement() {
        page.navigate(fixtureSite.url("reise/schritt2.html"));
        LocatorCandidate buttonCandidate = new LocatorCandidate(LocatorStrategy.TEST_ID, "reise-submit", 0);
        JourneyStep step = createStep(StepAction.WAIT_FOR, List.of(buttonCandidate), null, null, false, 1000);

        StepOutcome outcome = StepExecutor.execute(page, step, null);

        assertThat(outcome.status()).isEqualTo(StepStatus.PASSED);
        assertThat(outcome.winner()).isEqualTo(buttonCandidate);
        assertThat(outcome.drifted()).isFalse();
    }

    @Test
    void waitForFailsOnAbsentElementWithinTimeout() {
        page.navigate(fixtureSite.url("reise/schritt2.html"));
        LocatorCandidate missing = new LocatorCandidate(LocatorStrategy.TEST_ID, "does-not-exist", 0);
        JourneyStep step = createStep(StepAction.WAIT_FOR, List.of(missing), null, null, false, 100);

        StepOutcome outcome = StepExecutor.execute(page, step, null);

        assertThat(outcome.status()).isEqualTo(StepStatus.FAILED);
        assertThat(outcome.failureMessageKey()).isNotNull();
    }

    @Test
    void assertTextContainsPassingAndFailing() {
        page.navigate(fixtureSite.url("reise/schritt2.html"));
        LocatorCandidate heading = new LocatorCandidate(LocatorStrategy.CSS, "h1", 0);

        // Passing
        StepAssertion passAssertion = new StepAssertion(AssertionType.TEXT_CONTAINS, "Reiseangaben");
        JourneyStep passStep = createStep(StepAction.ASSERT, List.of(heading), null, passAssertion, false, 5000);
        StepOutcome passOutcome = StepExecutor.execute(page, passStep, null);

        assertThat(passOutcome.status()).isEqualTo(StepStatus.PASSED);
        assertThat(passOutcome.winner()).isEqualTo(heading);

        // Failing
        StepAssertion failAssertion = new StepAssertion(AssertionType.TEXT_CONTAINS, "Falscher Text");
        JourneyStep failStep = createStep(StepAction.ASSERT, List.of(heading), null, failAssertion, false, 5000);
        StepOutcome failOutcome = StepExecutor.execute(page, failStep, null);

        assertThat(failOutcome.status()).isEqualTo(StepStatus.FAILED);
        assertThat(failOutcome.failureMessageKey()).isEqualTo("journey.step.failed.assertion");
        assertThat(failOutcome.failureArgs()).contains("Falscher Text");
    }

    @Test
    void assertTextContainsPageLevelPassingAndFailing() {
        page.navigate(fixtureSite.url("reise/schritt2.html"));

        // Passing on whole page body
        StepAssertion passAssertion = new StepAssertion(AssertionType.TEXT_CONTAINS, "Reiseziel");
        JourneyStep passStep = createStep(StepAction.ASSERT, List.of(), null, passAssertion, false, 5000);
        StepOutcome passOutcome = StepExecutor.execute(page, passStep, null);

        assertThat(passOutcome.status()).isEqualTo(StepStatus.PASSED);

        // Failing on whole page body
        StepAssertion failAssertion = new StepAssertion(AssertionType.TEXT_CONTAINS, "Nicht auf der Seite");
        JourneyStep failStep = createStep(StepAction.ASSERT, List.of(), null, failAssertion, false, 5000);
        StepOutcome failOutcome = StepExecutor.execute(page, failStep, null);

        assertThat(failOutcome.status()).isEqualTo(StepStatus.FAILED);
        assertThat(failOutcome.failureMessageKey()).isEqualTo("journey.step.failed.assertion");
    }

    @Test
    void assertVisiblePassingAndFailing() {
        page.navigate(fixtureSite.url("reise/schritt2.html"));

        // Passing
        LocatorCandidate submitBtn = new LocatorCandidate(LocatorStrategy.TEST_ID, "reise-submit", 0);
        StepAssertion passAssertion = new StepAssertion(AssertionType.VISIBLE, null);
        JourneyStep passStep = createStep(StepAction.ASSERT, List.of(submitBtn), null, passAssertion, false, 5000);
        StepOutcome passOutcome = StepExecutor.execute(page, passStep, null);

        assertThat(passOutcome.status()).isEqualTo(StepStatus.PASSED);
        assertThat(passOutcome.winner()).isEqualTo(submitBtn);

        // Failing (missing element)
        LocatorCandidate missing = new LocatorCandidate(LocatorStrategy.TEST_ID, "missing-element", 0);
        StepAssertion failAssertion = new StepAssertion(AssertionType.VISIBLE, null);
        JourneyStep failStep = createStep(StepAction.ASSERT, List.of(missing), null, failAssertion, false, 5000);
        StepOutcome failOutcome = StepExecutor.execute(page, failStep, null);

        assertThat(failOutcome.status()).isEqualTo(StepStatus.FAILED);
        assertThat(failOutcome.failureMessageKey()).isNotNull();
    }

    @Test
    void assertUrlMatchesPassingAndFailing() {
        page.navigate(fixtureSite.url("reise/schritt2.html"));

        // Passing
        StepAssertion passAssertion = new StepAssertion(AssertionType.URL_MATCHES, "schritt2.html");
        JourneyStep passStep = createStep(StepAction.ASSERT, List.of(), null, passAssertion, false, 5000);
        StepOutcome passOutcome = StepExecutor.execute(page, passStep, null);

        assertThat(passOutcome.status()).isEqualTo(StepStatus.PASSED);

        // Failing
        StepAssertion failAssertion = new StepAssertion(AssertionType.URL_MATCHES, "ziel.html");
        JourneyStep failStep = createStep(StepAction.ASSERT, List.of(), null, failAssertion, false, 5000);
        StepOutcome failOutcome = StepExecutor.execute(page, failStep, null);

        assertThat(failOutcome.status()).isEqualTo(StepStatus.FAILED);
        assertThat(failOutcome.failureMessageKey()).isEqualTo("journey.step.failed.assertion");
    }

    @Test
    void assertCountPassingAndFailing() {
        page.navigate(fixtureSite.url("reise/schritt2.html"));
        // 2 input elements: #reise-name and #:r7:
        LocatorCandidate inputCandidate = new LocatorCandidate(LocatorStrategy.CSS, "input", 0);

        // Passing
        StepAssertion passAssertion = new StepAssertion(AssertionType.COUNT, "2");
        JourneyStep passStep = createStep(StepAction.ASSERT, List.of(inputCandidate), null, passAssertion, false, 5000);
        StepOutcome passOutcome = StepExecutor.execute(page, passStep, null);

        assertThat(passOutcome.status()).isEqualTo(StepStatus.PASSED);
        assertThat(passOutcome.winner()).isEqualTo(inputCandidate);

        // Failing
        StepAssertion failAssertion = new StepAssertion(AssertionType.COUNT, "5");
        JourneyStep failStep = createStep(StepAction.ASSERT, List.of(inputCandidate), null, failAssertion, false, 5000);
        StepOutcome failOutcome = StepExecutor.execute(page, failStep, null);

        assertThat(failOutcome.status()).isEqualTo(StepStatus.FAILED);
        assertThat(failOutcome.failureMessageKey()).isEqualTo("journey.step.failed.assertion");
    }

    @Test
    void optionalStepReturnsSkippedWhenElementMissing() {
        page.navigate(fixtureSite.url("reise/schritt2.html"));
        LocatorCandidate missing = new LocatorCandidate(LocatorStrategy.TEST_ID, "cookie-accept", 0);
        JourneyStep step = createStep(StepAction.CLICK, List.of(missing), null, null, true, 5000);

        StepOutcome outcome = StepExecutor.execute(page, step, null);

        assertThat(outcome.status()).isEqualTo(StepStatus.SKIPPED);
        assertThat(outcome.winner()).isNull();
        assertThat(outcome.drifted()).isFalse();
        assertThat(outcome.failureMessageKey()).isNull();
        assertThat(outcome.failureArgs()).isEmpty();
    }

    @Test
    void nonOptionalStepReturnsFailedWhenElementMissing() {
        page.navigate(fixtureSite.url("reise/schritt2.html"));
        LocatorCandidate missing = new LocatorCandidate(LocatorStrategy.TEST_ID, "cookie-accept", 0);
        JourneyStep step = createStep(StepAction.CLICK, List.of(missing), null, null, false, 5000);

        StepOutcome outcome = StepExecutor.execute(page, step, null);

        assertThat(outcome.status()).isEqualTo(StepStatus.FAILED);
        assertThat(outcome.winner()).isNull();
        assertThat(outcome.drifted()).isFalse();
        assertThat(outcome.failureMessageKey()).isEqualTo("journey.step.failed.not_found");
    }

    @Test
    void driftedStepReportsDriftedAsPassingStatusWithWinningCandidate() {
        page.navigate(fixtureSite.url("reise/start.html"));
        LocatorCandidate missingTestId = new LocatorCandidate(LocatorStrategy.TEST_ID, "non-existent-link", 0);
        LocatorCandidate matchingRole = new LocatorCandidate(
                LocatorStrategy.ROLE,
                "link[name='Reise buchen']",
                0
        );
        JourneyStep step = createStep(StepAction.CLICK, List.of(missingTestId, matchingRole), null, null, false, 5000);

        StepOutcome outcome = StepExecutor.execute(page, step, null);

        assertThat(outcome.status()).isEqualTo(StepStatus.DRIFTED);
        assertThat(outcome.winner()).isEqualTo(matchingRole);
        assertThat(outcome.drifted()).isTrue();
        assertThat(outcome.failureMessageKey()).isNull();
        assertThat(page.url()).contains("schritt2.html");
    }
}
