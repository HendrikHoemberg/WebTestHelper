package dev.hendrikhoemberg.webtesthelper.runner;

import com.microsoft.playwright.Locator;
import dev.hendrikhoemberg.webtesthelper.model.LocatorCandidate;

import java.util.Objects;

/**
 * The outcome of resolving locator candidates against a page (§10.2).
 *
 * @param locator the Playwright locator that resolved to exactly one element
 * @param winner  the winning candidate from the step's ladder
 * @param drifted true when the winner was not the highest-ranked candidate on the step
 */
public record LocatorMatch(Locator locator, LocatorCandidate winner, boolean drifted) {

    public LocatorMatch {
        Objects.requireNonNull(locator, "locator");
        Objects.requireNonNull(winner, "winner");
    }
}
