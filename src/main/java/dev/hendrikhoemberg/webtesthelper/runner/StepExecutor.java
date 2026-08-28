package dev.hendrikhoemberg.webtesthelper.runner;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.TimeoutError;
import dev.hendrikhoemberg.webtesthelper.model.AssertionType;
import dev.hendrikhoemberg.webtesthelper.model.JourneyStep;
import dev.hendrikhoemberg.webtesthelper.model.LocatorCandidate;
import dev.hendrikhoemberg.webtesthelper.model.StepAction;
import dev.hendrikhoemberg.webtesthelper.model.StepAssertion;
import dev.hendrikhoemberg.webtesthelper.model.StepOutcome;
import dev.hendrikhoemberg.webtesthelper.model.StepStatus;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Executes a single journey step against a live Playwright page (§10.3).
 *
 * <p>The step's element is resolved <strong>once</strong>, up front, through
 * {@link LocatorResolver#resolveWithin} — so the ladder is walked once, the resolved match is
 * available to every later failure, and §10.4's auto-waiting applies to finding the element.
 *
 * <p>{@code optional} means <em>the element may not be there</em>, and nothing more. A step whose
 * element is present but whose action or assertion then fails is a failure whether or not it is
 * optional: swallowing that would let a redesigned site replay green (§10.3).
 */
public final class StepExecutor {

    public static final String MSG_NOT_FOUND = "journey.step.failed.not_found";
    public static final String MSG_TIMEOUT = "journey.step.failed.timeout";
    public static final String MSG_ASSERTION = "journey.step.failed.assertion";
    public static final String MSG_ACTION = "journey.step.failed.action";
    public static final String MSG_NAVIGATION = "journey.step.failed.navigation";
    public static final String MSG_NOT_VISIBLE = "journey.step.failed.not_visible";
    public static final String MSG_PATTERN = "journey.step.failed.pattern";
    public static final String MSG_COUNT_EXPECTATION = "journey.step.failed.count_expectation";
    public static final String MSG_CREDENTIAL = "journey.step.failed.credential";

    private StepExecutor() {
    }

    /**
     * Executes the given step against the page with the resolved value.
     *
     * @param page          the live Playwright page
     * @param step          the step to execute
     * @param resolvedValue the step's value resolved from credentials or templates
     * @return the outcome of the step execution
     */
    public static StepOutcome execute(Page page, JourneyStep step, String resolvedValue) {
        Objects.requireNonNull(page, "page");
        Objects.requireNonNull(step, "step");

        LocatorMatch match = null;
        try {
            if (needsResolvedElement(step)) {
                Optional<LocatorMatch> resolved =
                        LocatorResolver.resolveWithin(page, step, step.timeoutMs());
                if (resolved.isEmpty()) {
                    return missingElementOutcome(step);
                }
                match = resolved.get();
            }
            return performAction(page, step, resolvedValue, match);
        } catch (TimeoutError e) {
            return failure(step, match, MSG_TIMEOUT, List.of(String.valueOf(step.timeoutMs())));
        } catch (RuntimeException e) {
            return failure(step, match, MSG_ACTION,
                    List.of(e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()));
        }
    }

    /**
     * A step needs an element resolved up front when it names candidates, except for a
     * {@code COUNT} assertion: that one is about how many elements match, so "resolved" cannot
     * mean "matched exactly once" and {@link #evaluateCount} walks the ladder on its own terms.
     */
    private static boolean needsResolvedElement(JourneyStep step) {
        if (step.locatorCandidates().isEmpty()) {
            return false;
        }
        return !(step.action() == StepAction.ASSERT && assertionTypeIs(step, AssertionType.COUNT));
    }

    private static boolean assertionTypeIs(JourneyStep step, AssertionType type) {
        return step.assertion() != null && step.assertion().type() == type;
    }

    private static StepOutcome performAction(
            Page page, JourneyStep step, String resolvedValue, LocatorMatch match) {
        return switch (step.action()) {
            case GOTO -> executeGoto(page, step, resolvedValue, match);
            case CLICK -> withAssertion(page, step, match, resolvedValue, () ->
                    match.locator().click(new Locator.ClickOptions().setTimeout(step.timeoutMs())));
            case FILL -> withAssertion(page, step, match, resolvedValue, () ->
                    match.locator().fill(orEmpty(resolvedValue),
                            new Locator.FillOptions().setTimeout(step.timeoutMs())));
            case SELECT -> withAssertion(page, step, match, resolvedValue, () ->
                    match.locator().selectOption(orEmpty(resolvedValue),
                            new Locator.SelectOptionOptions().setTimeout(step.timeoutMs())));
            case PRESS -> withAssertion(page, step, match, resolvedValue, () -> {
                if (match != null) {
                    match.locator().press(orEmpty(resolvedValue),
                            new Locator.PressOptions().setTimeout(step.timeoutMs()));
                } else {
                    page.keyboard().press(orEmpty(resolvedValue));
                }
            });
            case HOVER -> withAssertion(page, step, match, resolvedValue, () ->
                    match.locator().hover(new Locator.HoverOptions().setTimeout(step.timeoutMs())));
            // The element was already awaited by resolveWithin: reaching here is the success case.
            case WAIT_FOR -> withAssertion(page, step, match, resolvedValue, () -> {
            });
            case ASSERT -> evaluateAssertion(page, step, match, resolvedValue);
        };
    }

    /** Runs the action, then evaluates the step's assertion if it carries one. */
    private static StepOutcome withAssertion(
            Page page, JourneyStep step, LocatorMatch match, String resolvedValue, Runnable action) {
        action.run();
        if (step.assertion() != null) {
            return evaluateAssertion(page, step, match, resolvedValue);
        }
        return outcomeFor(step, match);
    }

    private static StepOutcome executeGoto(
            Page page, JourneyStep step, String resolvedValue, LocatorMatch match) {
        String url = resolvedValue != null ? resolvedValue : step.value();
        try {
            page.navigate(url, new Page.NavigateOptions().setTimeout(step.timeoutMs()));
        } catch (RuntimeException e) {
            return failure(step, match, MSG_NAVIGATION,
                    List.of(String.valueOf(url),
                            e.getMessage() != null ? firstLine(e.getMessage()) : e.getClass().getSimpleName()));
        }
        if (step.assertion() != null) {
            return evaluateAssertion(page, step, match, resolvedValue);
        }
        return outcomeFor(step, match);
    }

    private static StepOutcome evaluateAssertion(
            Page page, JourneyStep step, LocatorMatch match, String resolvedValue) {
        StepAssertion assertion = step.assertion();
        if (assertion == null) {
            return outcomeFor(step, match);
        }

        return switch (assertion.type()) {
            case TEXT_CONTAINS -> evaluateTextContains(page, step, match, assertion, resolvedValue);
            case VISIBLE -> evaluateVisible(step, match);
            case URL_MATCHES -> evaluateUrlMatches(page, step, match, assertion, resolvedValue);
            case COUNT -> evaluateCount(page, step, assertion);
        };
    }

    private static StepOutcome evaluateTextContains(
            Page page, JourneyStep step, LocatorMatch match, StepAssertion assertion, String resolvedValue) {
        String expected = assertion.expected() != null ? assertion.expected() : orEmpty(resolvedValue);
        String actualText = match != null
                ? match.locator().innerText(new Locator.InnerTextOptions().setTimeout(step.timeoutMs()))
                : page.innerText("body", new Page.InnerTextOptions().setTimeout(step.timeoutMs()));

        if (actualText != null && actualText.contains(expected)) {
            return outcomeFor(step, match);
        }
        return failure(step, match, MSG_ASSERTION, List.of(expected, orEmpty(actualText)));
    }

    private static StepOutcome evaluateVisible(JourneyStep step, LocatorMatch match) {
        if (match == null) {
            return missingElementOutcome(step);
        }
        if (match.locator().isVisible()) {
            return outcomeFor(step, match);
        }
        return failure(step, match, MSG_NOT_VISIBLE, List.of());
    }

    private static StepOutcome evaluateUrlMatches(
            Page page, JourneyStep step, LocatorMatch match, StepAssertion assertion, String resolvedValue) {
        String expected = assertion.expected() != null ? assertion.expected() : orEmpty(resolvedValue);
        Pattern pattern;
        try {
            pattern = Pattern.compile(expected);
        } catch (PatternSyntaxException e) {
            // An unusable pattern is an authoring fault, not a failed assertion: say so distinctly
            // rather than reporting "the URL did not match" about a pattern that can never match.
            return failure(step, match, MSG_PATTERN, List.of(expected));
        }

        String currentUrl = orEmpty(page.url());
        if (pattern.matcher(currentUrl).find()) {
            return outcomeFor(step, match);
        }
        return failure(step, match, MSG_ASSERTION, List.of(expected, currentUrl));
    }

    /**
     * Evaluates a {@code COUNT} assertion, re-checking until the step's timeout is spent.
     *
     * <p>The <strong>first candidate that matches anything</strong> decides the observed count.
     * Walking on to a lower-ranked candidate merely because it happens to match the expected
     * number would turn the assertion into a search for a number that agrees with it, and no
     * {@code COUNT} assertion could then fail.
     */
    private static StepOutcome evaluateCount(Page page, JourneyStep step, StepAssertion assertion) {
        int expected;
        try {
            expected = Integer.parseInt(String.valueOf(assertion.expected()).trim());
        } catch (NumberFormatException e) {
            return failure(step, null, MSG_COUNT_EXPECTATION, List.of(String.valueOf(assertion.expected())));
        }

        List<LocatorCandidate> ladder = LocatorResolver.inLadderOrder(step.locatorCandidates());
        long deadline = System.nanoTime() + step.timeoutMs() * 1_000_000L;
        CountObservation observed;
        while (true) {
            observed = observeCount(page, ladder);
            if (observed.count() == expected) {
                return observed.drifted()
                        ? StepOutcome.drifted(step.id(), observed.candidate())
                        : StepOutcome.passed(step.id(), observed.candidate());
            }
            long remainingMs = (deadline - System.nanoTime()) / 1_000_000L;
            if (remainingMs <= 0) {
                break;
            }
            page.waitForTimeout(Math.min(LocatorResolver.POLL_INTERVAL_MS, remainingMs));
        }

        return StepOutcome.failed(step.id(), observed.candidate(), observed.drifted(), MSG_ASSERTION,
                List.of(String.valueOf(expected), String.valueOf(observed.count())));
    }

    /** What the ladder currently observes for a {@code COUNT} assertion. */
    private record CountObservation(LocatorCandidate candidate, boolean drifted, int count) {
    }

    private static CountObservation observeCount(Page page, List<LocatorCandidate> ladder) {
        if (ladder.isEmpty()) {
            return new CountObservation(null, false, 0);
        }
        LocatorCandidate primary = ladder.get(0);
        for (LocatorCandidate candidate : ladder) {
            int count = LocatorResolver.countOrMinusOne(page, candidate);
            if (count > 0) {
                return new CountObservation(candidate, !candidate.equals(primary), count);
            }
        }
        return new CountObservation(primary, false, 0);
    }

    /**
     * The step's element was not on the page. This is the one place {@code optional} applies:
     * §10.3's motivating case is the cookie banner that may simply not be there.
     */
    private static StepOutcome missingElementOutcome(JourneyStep step) {
        if (step.optional()) {
            return StepOutcome.skipped(step.id());
        }
        return StepOutcome.failed(step.id(), MSG_NOT_FOUND, List.of());
    }

    /**
     * Reports a failure while keeping whatever the ladder had already established. A step that
     * resolved through a fallback and then failed is exactly the "site was redesigned and is now
     * breaking" case §10.2 exists to surface, so its winner and drift must survive the failure.
     */
    private static StepOutcome failure(
            JourneyStep step, LocatorMatch match, String messageKey, List<String> args) {
        if (match == null) {
            return StepOutcome.failed(step.id(), messageKey, args);
        }
        return StepOutcome.failed(step.id(), match.winner(), match.drifted(), messageKey, args);
    }

    private static StepOutcome outcomeFor(JourneyStep step, LocatorMatch match) {
        if (match == null) {
            return StepOutcome.passed(step.id(), null);
        }
        return match.drifted()
                ? StepOutcome.drifted(step.id(), match.winner())
                : StepOutcome.passed(step.id(), match.winner());
    }

    private static String orEmpty(String value) {
        return value != null ? value : "";
    }

    private static String firstLine(String message) {
        int newline = message.indexOf('\n');
        return newline == -1 ? message : message.substring(0, newline);
    }
}
