package dev.hendrikhoemberg.webtesthelper.model;

/**
 * Ranked selector strategies for finding elements (§10.2).
 * Declaration order is preference rank order: {@link #TEST_ID} is tried first and {@link #CSS} last.
 */
public enum LocatorStrategy {
    TEST_ID,
    ROLE,
    LABEL,
    ID,
    TEXT,
    CSS
}
