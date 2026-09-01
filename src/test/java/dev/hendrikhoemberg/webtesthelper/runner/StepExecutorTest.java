package dev.hendrikhoemberg.webtesthelper.runner;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import dev.hendrikhoemberg.webtesthelper.model.AssertionType;
import dev.hendrikhoemberg.webtesthelper.model.JourneyStep;
import dev.hendrikhoemberg.webtesthelper.model.LocatorCandidate;
import dev.hendrikhoemberg.webtesthelper.model.LocatorStrategy;
import dev.hendrikhoemberg.webtesthelper.model.StepAction;
import dev.hendrikhoemberg.webtesthelper.model.StepAssertion;
import dev.hendrikhoemberg.webtesthelper.model.StepOutcome;
import dev.hendrikhoemberg.webtesthelper.model.StepStatus;
import dev.hendrikhoemberg.webtesthelper.support.FixtureSite;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import dev.hendrikhoemberg.webtesthelper.support.SharedBrowser;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("browser")
@org.junit.jupiter.api.parallel.ResourceLock("browser")
class StepExecutorTest {

    private static FixtureSite fixtureSite;
    private static Browser browser;
    private static BrowserContext context;
    private static Page page;

    @BeforeAll
    static void start() {
        fixtureSite = FixtureSite.start();
        browser = SharedBrowser.browser();
        context = browser.newContext();
        page = context.newPage();
    }

    @AfterAll
    static void stop() {
        if (context != null) {
            context.close();
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
        JourneyStep failStep = createStep(StepAction.ASSERT, List.of(missing), null, failAssertion, false, 300);
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
        // Short budget: a COUNT assertion now re-checks until its timeout before giving up.
        JourneyStep failStep = createStep(StepAction.ASSERT, List.of(inputCandidate), null, failAssertion, false, 300);
        StepOutcome failOutcome = StepExecutor.execute(page, failStep, null);

        assertThat(failOutcome.status()).isEqualTo(StepStatus.FAILED);
        assertThat(failOutcome.failureMessageKey()).isEqualTo("journey.step.failed.assertion");
    }

    @Test
    void optionalStepReturnsSkippedWhenElementMissing() {
        page.navigate(fixtureSite.url("reise/schritt2.html"));
        LocatorCandidate missing = new LocatorCandidate(LocatorStrategy.TEST_ID, "cookie-accept", 0);
        JourneyStep step = createStep(StepAction.CLICK, List.of(missing), null, null, true, 300);

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
        JourneyStep step = createStep(StepAction.CLICK, List.of(missing), null, null, false, 300);

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

    @Test
    void waitForWaitsForAnElementThatIsRenderedAfterLoad() {
        page.navigate(fixtureSite.url("reise/spaet.html"));
        LocatorCandidate late = new LocatorCandidate(LocatorStrategy.TEST_ID, "spaet-link", 0);
        JourneyStep step = createStep(StepAction.WAIT_FOR, List.of(late), null, null, false, 3000);

        StepOutcome outcome = StepExecutor.execute(page, step, null);

        assertThat(outcome.status()).isEqualTo(StepStatus.PASSED);
        assertThat(outcome.winner()).isEqualTo(late);
    }

    @Test
    void clickWaitsForAnElementThatIsRenderedAfterLoad() {
        page.navigate(fixtureSite.url("reise/spaet.html"));
        LocatorCandidate late = new LocatorCandidate(LocatorStrategy.TEST_ID, "spaet-link", 0);
        JourneyStep step = createStep(StepAction.CLICK, List.of(late), null, null, false, 3000);

        StepOutcome outcome = StepExecutor.execute(page, step, null);

        assertThat(outcome.status()).isEqualTo(StepStatus.PASSED);
        assertThat(page.url()).contains("ziel.html");
    }

    @Test
    void aStepThatDriftedAndThenFailedStillReportsTheWinnerAndTheDrift() {
        page.navigate(fixtureSite.url("reise/schritt2.html"));
        LocatorCandidate goneAfterRedesign = new LocatorCandidate(LocatorStrategy.TEST_ID, "ueberschrift", 0);
        LocatorCandidate fallback = new LocatorCandidate(LocatorStrategy.CSS, "h1", 0);
        StepAssertion wrongText = new StepAssertion(AssertionType.TEXT_CONTAINS, "Falscher Text");
        JourneyStep step = createStep(
                StepAction.ASSERT, List.of(goneAfterRedesign, fallback), null, wrongText, false, 300);

        StepOutcome outcome = StepExecutor.execute(page, step, null);

        assertThat(outcome.status()).isEqualTo(StepStatus.FAILED);
        assertThat(outcome.winner()).isEqualTo(fallback);
        assertThat(outcome.drifted()).isTrue();
        assertThat(outcome.failureMessageKey()).isEqualTo("journey.step.failed.assertion");
    }

    @Test
    void countAssertionJudgesTheFirstMatchingCandidateInsteadOfHuntingForTheExpectedNumber() {
        page.navigate(fixtureSite.url("reise/schritt2.html"));
        // schritt2.html has 2 <input> and 4 <div>. The step is about the inputs; a later candidate
        // happening to count 4 must not be allowed to satisfy the assertion.
        LocatorCandidate inputs = new LocatorCandidate(LocatorStrategy.CSS, "input", 0);
        LocatorCandidate divs = new LocatorCandidate(LocatorStrategy.CSS, "div", 1);
        StepAssertion expectFour = new StepAssertion(AssertionType.COUNT, "4");
        JourneyStep step = createStep(StepAction.ASSERT, List.of(inputs, divs), null, expectFour, false, 300);

        StepOutcome outcome = StepExecutor.execute(page, step, null);

        assertThat(outcome.status()).isEqualTo(StepStatus.FAILED);
        assertThat(outcome.failureMessageKey()).isEqualTo("journey.step.failed.assertion");
        assertThat(outcome.failureArgs()).containsExactly("4", "2");
    }

    @Test
    void countAssertionStillFallsBackWhenThePrimaryCandidateMatchesNothing() {
        page.navigate(fixtureSite.url("reise/schritt2.html"));
        LocatorCandidate gone = new LocatorCandidate(LocatorStrategy.TEST_ID, "eingabefelder", 0);
        LocatorCandidate inputs = new LocatorCandidate(LocatorStrategy.CSS, "input", 0);
        StepAssertion expectTwo = new StepAssertion(AssertionType.COUNT, "2");
        JourneyStep step = createStep(StepAction.ASSERT, List.of(gone, inputs), null, expectTwo, false, 300);

        StepOutcome outcome = StepExecutor.execute(page, step, null);

        assertThat(outcome.status()).isEqualTo(StepStatus.DRIFTED);
        assertThat(outcome.winner()).isEqualTo(inputs);
    }

    @Test
    void countAssertionWithANonNumericExpectationFailsWithItsOwnKey() {
        page.navigate(fixtureSite.url("reise/schritt2.html"));
        LocatorCandidate inputs = new LocatorCandidate(LocatorStrategy.CSS, "input", 0);
        StepAssertion nonsense = new StepAssertion(AssertionType.COUNT, "zwei");
        JourneyStep step = createStep(StepAction.ASSERT, List.of(inputs), null, nonsense, false, 300);

        StepOutcome outcome = StepExecutor.execute(page, step, null);

        assertThat(outcome.status()).isEqualTo(StepStatus.FAILED);
        assertThat(outcome.failureMessageKey()).isEqualTo("journey.step.failed.count_expectation");
        assertThat(outcome.failureArgs()).containsExactly("zwei");
    }

    @Test
    void urlAssertionTreatsTheExpectedValueAsARegularExpression() {
        page.navigate(fixtureSite.url("reise/schritt2.html"));
        StepAssertion pattern = new StepAssertion(AssertionType.URL_MATCHES, "schritt\\d+\\.html$");
        JourneyStep step = createStep(StepAction.ASSERT, List.of(), null, pattern, false, 300);

        StepOutcome outcome = StepExecutor.execute(page, step, null);

        assertThat(outcome.status()).isEqualTo(StepStatus.PASSED);
    }

    @Test
    void urlAssertionWithAnUnusablePatternFailsWithItsOwnKey() {
        page.navigate(fixtureSite.url("reise/schritt2.html"));
        StepAssertion broken = new StepAssertion(AssertionType.URL_MATCHES, "[unvollstaendig");
        JourneyStep step = createStep(StepAction.ASSERT, List.of(), null, broken, false, 300);

        StepOutcome outcome = StepExecutor.execute(page, step, null);

        assertThat(outcome.status()).isEqualTo(StepStatus.FAILED);
        assertThat(outcome.failureMessageKey()).isEqualTo("journey.step.failed.pattern");
        assertThat(outcome.failureArgs()).contains("[unvollstaendig");
    }

    @Test
    void visibleAssertionOnAHiddenElementFailsWithItsOwnKeyAndNoGermanArguments() {
        page.navigate(fixtureSite.url("reise/spaet.html"));
        LocatorCandidate hidden = new LocatorCandidate(LocatorStrategy.TEST_ID, "unsichtbar", 0);
        StepAssertion visible = new StepAssertion(AssertionType.VISIBLE, null);
        JourneyStep step = createStep(StepAction.ASSERT, List.of(hidden), null, visible, false, 300);

        StepOutcome outcome = StepExecutor.execute(page, step, null);

        assertThat(outcome.status()).isEqualTo(StepStatus.FAILED);
        assertThat(outcome.failureMessageKey()).isEqualTo("journey.step.failed.not_visible");
        assertThat(outcome.failureArgs()).isEmpty();
        assertThat(outcome.winner()).isEqualTo(hidden);
    }

    @Test
    void gotoFailureIsReportedWithTheNavigationMessageKey() {
        // Port 1 is privileged and closed: the navigation is refused rather than timing out.
        // Run on a throwaway page — a refused navigation leaves the tab on chrome-error://, which
        // would race the next test's navigation away from it.
        String unreachable = "http://127.0.0.1:1/";
        JourneyStep step = createStep(StepAction.GOTO, List.of(), unreachable, null, false, 2000);

        Page scratchPage = browser.newPage();
        try {
            StepOutcome outcome = StepExecutor.execute(scratchPage, step, unreachable);

            assertThat(outcome.status()).isEqualTo(StepStatus.FAILED);
            assertThat(outcome.failureMessageKey()).isEqualTo("journey.step.failed.navigation");
            assertThat(outcome.failureArgs()).first().isEqualTo(unreachable);
        } finally {
            scratchPage.close();
        }
    }

    @Test
    void anOptionalStepFailsWhenItsElementIsThereButTheAssertionIsFalse() {
        page.navigate(fixtureSite.url("reise/schritt2.html"));
        LocatorCandidate heading = new LocatorCandidate(LocatorStrategy.CSS, "h1", 0);
        StepAssertion wrongText = new StepAssertion(AssertionType.TEXT_CONTAINS, "Falscher Text");
        JourneyStep step = createStep(StepAction.ASSERT, List.of(heading), null, wrongText, true, 300);

        StepOutcome outcome = StepExecutor.execute(page, step, null);

        assertThat(outcome.status()).isEqualTo(StepStatus.FAILED);
    }

    @Test
    void anOptionalStepFailsWhenItsElementIsThereButTheActionCannotBeCarriedOut() {
        page.navigate(fixtureSite.url("reise/schritt2.html"));
        LocatorCandidate select = new LocatorCandidate(LocatorStrategy.TEST_ID, "reise-ziel", 0);
        JourneyStep step = createStep(StepAction.SELECT, List.of(select), null, null, true, 500);

        StepOutcome outcome = StepExecutor.execute(page, step, "gibt-es-nicht");

        assertThat(outcome.status()).isEqualTo(StepStatus.FAILED);
    }
}
