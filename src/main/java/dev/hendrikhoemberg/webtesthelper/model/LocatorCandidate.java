package dev.hendrikhoemberg.webtesthelper.model;

import java.util.Objects;

/**
 * A ranked locator candidate for finding an element (§10.2, §10.3).
 *
 * @param strategy the selector strategy to use
 * @param value    the selector value / query string
 * @param rank     relative rank within the strategy
 */
public record LocatorCandidate(LocatorStrategy strategy, String value, int rank) {

    public LocatorCandidate {
        Objects.requireNonNull(strategy, "strategy");
        Objects.requireNonNull(value, "value");
        if (value.isBlank()) {
            throw new IllegalArgumentException("value must not be blank");
        }
        if (rank < 0) {
            throw new IllegalArgumentException("rank must not be negative: " + rank);
        }
    }
}
