package dev.hendrikhoemberg.webtesthelper.runner;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.TimeoutError;
import dev.hendrikhoemberg.webtesthelper.model.AssertionType;
import dev.hendrikhoemberg.webtesthelper.model.JourneyStep;
import dev.hendrikhoemberg.webtesthelper.model.LocatorCandidate;
import dev.hendrikhoemberg.webtesthelper.model.StepAction;
import dev.hendrikhoemberg.webtesthelper.model.StepAssertion;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Executes a single journey step against a live Playwright page (§10.3).
 */
public final class StepExecutor {

    public static final String MSG_NOT_FOUND = "journey.step.failed.not_found";
    public static final String MSG_TIMEOUT = "journey.step.failed.timeout";
    public static final String MSG_ASSERTION = "journey.step.failed.assertion";
    public static final String MSG_ACTION = "journey.step.failed.action";

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

        try {
            return performAction(page, step, resolvedValue);
        } catch (TimeoutError e) {
            if (step.optional()) {
                return StepOutcome.skipped(step.id());
            }
            return StepOutcome.failed(step.id(), MSG_TIMEOUT, List.of(String.valueOf(step.timeoutMs())));
        } catch (Exception e) {
            if (step.optional()) {
                return StepOutcome.skipped(step.id());
            }
            return StepOutcome.failed(step.id(), MSG_ACTION, List.of(e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()));
        }
    }

    private static StepOutcome performAction(Page page, JourneyStep step, String resolvedValue) {
        return switch (step.action()) {
            case GOTO -> executeGoto(page, step, resolvedValue);
            case CLICK -> executeClick(page, step, resolvedValue);
            case FILL -> executeFill(page, step, resolvedValue);
            case SELECT -> executeSelect(page, step, resolvedValue);
            case PRESS -> executePress(page, step, resolvedValue);
            case HOVER -> executeHover(page, step, resolvedValue);
            case WAIT_FOR -> executeWaitFor(page, step, resolvedValue);
            case ASSERT -> evaluateAssertion(page, step, null, resolvedValue);
        };
    }

    private static StepOutcome executeGoto(Page page, JourneyStep step, String resolvedValue) {
        String url = resolvedValue != null ? resolvedValue : step.value();
        page.navigate(url, new Page.NavigateOptions().setTimeout(step.timeoutMs()));
        if (step.assertion() != null) {
            return evaluateAssertion(page, step, null, resolvedValue);
        }
        return StepOutcome.passed(step.id(), null);
    }

    private static StepOutcome executeClick(Page page, JourneyStep step, String resolvedValue) {
        Optional<LocatorMatch> matchOpt = LocatorResolver.resolve(page, step);
        if (matchOpt.isEmpty()) {
            return missingElementOutcome(step);
        }
        LocatorMatch match = matchOpt.get();
        match.locator().click(new Locator.ClickOptions().setTimeout(step.timeoutMs()));
        if (step.assertion() != null) {
            return evaluateAssertion(page, step, match, resolvedValue);
        }
        return outcomeFor(step, match);
    }

    private static StepOutcome executeFill(Page page, JourneyStep step, String resolvedValue) {
        Optional<LocatorMatch> matchOpt = LocatorResolver.resolve(page, step);
        if (matchOpt.isEmpty()) {
            return missingElementOutcome(step);
        }
        LocatorMatch match = matchOpt.get();
        String textToFill = resolvedValue != null ? resolvedValue : "";
        match.locator().fill(textToFill, new Locator.FillOptions().setTimeout(step.timeoutMs()));
        if (step.assertion() != null) {
            return evaluateAssertion(page, step, match, resolvedValue);
        }
        return outcomeFor(step, match);
    }

    private static StepOutcome executeSelect(Page page, JourneyStep step, String resolvedValue) {
        Optional<LocatorMatch> matchOpt = LocatorResolver.resolve(page, step);
        if (matchOpt.isEmpty()) {
            return missingElementOutcome(step);
        }
        LocatorMatch match = matchOpt.get();
        String optionToSelect = resolvedValue != null ? resolvedValue : "";
        match.locator().selectOption(optionToSelect, new Locator.SelectOptionOptions().setTimeout(step.timeoutMs()));
        if (step.assertion() != null) {
            return evaluateAssertion(page, step, match, resolvedValue);
        }
        return outcomeFor(step, match);
    }

    private static StepOutcome executePress(Page page, JourneyStep step, String resolvedValue) {
        String key = resolvedValue != null ? resolvedValue : "";
        if (!step.locatorCandidates().isEmpty()) {
            Optional<LocatorMatch> matchOpt = LocatorResolver.resolve(page, step);
            if (matchOpt.isEmpty()) {
                return missingElementOutcome(step);
            }
            LocatorMatch match = matchOpt.get();
            match.locator().press(key, new Locator.PressOptions().setTimeout(step.timeoutMs()));
            if (step.assertion() != null) {
                return evaluateAssertion(page, step, match, resolvedValue);
            }
            return outcomeFor(step, match);
        } else {
            page.keyboard().press(key);
            if (step.assertion() != null) {
                return evaluateAssertion(page, step, null, resolvedValue);
            }
            return StepOutcome.passed(step.id(), null);
        }
    }

    private static StepOutcome executeHover(Page page, JourneyStep step, String resolvedValue) {
        Optional<LocatorMatch> matchOpt = LocatorResolver.resolve(page, step);
        if (matchOpt.isEmpty()) {
            return missingElementOutcome(step);
        }
        LocatorMatch match = matchOpt.get();
        match.locator().hover(new Locator.HoverOptions().setTimeout(step.timeoutMs()));
        if (step.assertion() != null) {
            return evaluateAssertion(page, step, match, resolvedValue);
        }
        return outcomeFor(step, match);
    }

    private static StepOutcome executeWaitFor(Page page, JourneyStep step, String resolvedValue) {
        if (!step.locatorCandidates().isEmpty()) {
            Optional<LocatorMatch> matchOpt = LocatorResolver.resolve(page, step);
            if (matchOpt.isEmpty()) {
                return missingElementOutcome(step);
            }
            LocatorMatch match = matchOpt.get();
            match.locator().waitFor(new Locator.WaitForOptions().setTimeout(step.timeoutMs()));
            if (step.assertion() != null) {
                return evaluateAssertion(page, step, match, resolvedValue);
            }
            return outcomeFor(step, match);
        } else {
            if (step.assertion() != null) {
                return evaluateAssertion(page, step, null, resolvedValue);
            }
            return StepOutcome.passed(step.id(), null);
        }
    }

    private static StepOutcome evaluateAssertion(Page page, JourneyStep step, LocatorMatch match, String resolvedValue) {
        StepAssertion assertion = step.assertion();
        if (assertion == null) {
            return outcomeFor(step, match);
        }

        return switch (assertion.type()) {
            case TEXT_CONTAINS -> evaluateTextContains(page, step, match, assertion, resolvedValue);
            case VISIBLE -> evaluateVisible(page, step, match, assertion);
            case URL_MATCHES -> evaluateUrlMatches(page, step, match, assertion, resolvedValue);
            case COUNT -> evaluateCount(page, step, match, assertion);
        };
    }

    private static StepOutcome evaluateTextContains(
            Page page,
            JourneyStep step,
            LocatorMatch match,
            StepAssertion assertion,
            String resolvedValue
    ) {
        String expected = assertion.expected() != null ? assertion.expected() : (resolvedValue != null ? resolvedValue : "");
        String actualText;
        if (match != null) {
            actualText = match.locator().innerText(new Locator.InnerTextOptions().setTimeout(step.timeoutMs()));
        } else if (!step.locatorCandidates().isEmpty()) {
            Optional<LocatorMatch> resolved = LocatorResolver.resolve(page, step);
            if (resolved.isEmpty()) {
                return missingElementOutcome(step);
            }
            match = resolved.get();
            actualText = match.locator().innerText(new Locator.InnerTextOptions().setTimeout(step.timeoutMs()));
        } else {
            actualText = page.innerText("body", new Page.InnerTextOptions().setTimeout(step.timeoutMs()));
        }

        if (actualText != null && actualText.contains(expected)) {
            return outcomeFor(step, match);
        }

        if (step.optional()) {
            return StepOutcome.skipped(step.id());
        }
        return StepOutcome.failed(step.id(), MSG_ASSERTION, List.of(expected, actualText != null ? actualText : ""));
    }

    private static StepOutcome evaluateVisible(
            Page page,
            JourneyStep step,
            LocatorMatch match,
            StepAssertion assertion
    ) {
        boolean visible;
        if (match != null) {
            visible = match.locator().isVisible();
        } else if (!step.locatorCandidates().isEmpty()) {
            Optional<LocatorMatch> resolved = LocatorResolver.resolve(page, step);
            if (resolved.isEmpty()) {
                return missingElementOutcome(step);
            }
            match = resolved.get();
            visible = match.locator().isVisible();
        } else {
            return missingElementOutcome(step);
        }

        if (visible) {
            return outcomeFor(step, match);
        }

        if (step.optional()) {
            return StepOutcome.skipped(step.id());
        }
        return StepOutcome.failed(step.id(), MSG_ASSERTION, List.of("sichtbar", "nicht sichtbar"));
    }

    private static StepOutcome evaluateUrlMatches(
            Page page,
            JourneyStep step,
            LocatorMatch match,
            StepAssertion assertion,
            String resolvedValue
    ) {
        String expected = assertion.expected() != null ? assertion.expected() : (resolvedValue != null ? resolvedValue : "");
        String currentUrl = page.url();
        boolean matches = currentUrl != null && (currentUrl.contains(expected) || matchesPattern(currentUrl, expected));

        if (matches) {
            return outcomeFor(step, match);
        }

        if (step.optional()) {
            return StepOutcome.skipped(step.id());
        }
        return StepOutcome.failed(step.id(), MSG_ASSERTION, List.of(expected, currentUrl != null ? currentUrl : ""));
    }

    private static StepOutcome evaluateCount(
            Page page,
            JourneyStep step,
            LocatorMatch match,
            StepAssertion assertion
    ) {
        int expectedCount;
        try {
            expectedCount = Integer.parseInt(assertion.expected().trim());
        } catch (Exception e) {
            if (step.optional()) {
                return StepOutcome.skipped(step.id());
            }
            return StepOutcome.failed(step.id(), MSG_ASSERTION, List.of(String.valueOf(assertion.expected()), "ungültige Zahl"));
        }

        if (!step.locatorCandidates().isEmpty()) {
            List<LocatorCandidate> sorted = step.locatorCandidates().stream()
                    .sorted(Comparator.comparingInt((LocatorCandidate c) -> c.strategy().ordinal())
                            .thenComparingInt(LocatorCandidate::rank))
                    .toList();
            LocatorCandidate primary = sorted.get(0);
            LocatorCandidate winner = null;
            int actualCount = 0;
            for (LocatorCandidate candidate : sorted) {
                Locator locator = LocatorResolver.toLocator(page, candidate);
                int cnt = locator.count();
                if (cnt == expectedCount) {
                    winner = candidate;
                    actualCount = cnt;
                    break;
                }
            }

            if (winner != null) {
                boolean drifted = !winner.equals(primary);
                return drifted ? StepOutcome.drifted(step.id(), winner) : StepOutcome.passed(step.id(), winner);
            }

            actualCount = LocatorResolver.toLocator(page, primary).count();
            if (step.optional()) {
                return StepOutcome.skipped(step.id());
            }
            return StepOutcome.failed(step.id(), MSG_ASSERTION, List.of(String.valueOf(expectedCount), String.valueOf(actualCount)));
        }

        if (step.optional()) {
            return StepOutcome.skipped(step.id());
        }
        return StepOutcome.failed(step.id(), MSG_ASSERTION, List.of(String.valueOf(expectedCount), "0"));
    }

    private static boolean matchesPattern(String text, String pattern) {
        try {
            return text.matches(pattern);
        } catch (Exception e) {
            return false;
        }
    }

    private static StepOutcome missingElementOutcome(JourneyStep step) {
        if (step.optional()) {
            return StepOutcome.skipped(step.id());
        }
        return StepOutcome.failed(step.id(), MSG_NOT_FOUND, List.of());
    }

    private static StepOutcome outcomeFor(JourneyStep step, LocatorMatch match) {
        if (match != null) {
            if (match.drifted()) {
                return StepOutcome.drifted(step.id(), match.winner());
            } else {
                return StepOutcome.passed(step.id(), match.winner());
            }
        }
        return StepOutcome.passed(step.id(), null);
    }
}
