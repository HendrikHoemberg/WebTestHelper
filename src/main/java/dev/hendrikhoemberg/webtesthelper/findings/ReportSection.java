package dev.hendrikhoemberg.webtesthelper.findings;

/**
 * The report's sections, declared in precedence order. The precedence is what the diff SQL's
 * {@code CASE} evaluates: the first branch that matches wins, so {@code FIXED} is checked before
 * {@code NEW} before {@code REGRESSED}, and so on (spec 6.4).
 */
public enum ReportSection {
    FIXED, NEW, REGRESSED, KNOWN, STILL_OPEN
}
