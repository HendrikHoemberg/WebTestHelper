package dev.hendrikhoemberg.webtesthelper.model;

import java.util.Objects;

/**
 * Assertion specification for a journey step (§10.3).
 *
 * @param type     the type of assertion to evaluate
 * @param expected the expected value or pattern
 */
public record StepAssertion(AssertionType type, String expected) {

    public StepAssertion {
        Objects.requireNonNull(type, "type");
    }
}
