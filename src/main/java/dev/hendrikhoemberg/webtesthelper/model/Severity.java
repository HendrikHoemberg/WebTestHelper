package dev.hendrikhoemberg.webtesthelper.model;

/** Only ERROR triggers notification by default (spec 8). */
public enum Severity {
    ERROR, WARN, INFO;

    /**
     * Returns the more severe of the two.
     *
     * <p>{@code Severity} is declared most-severe-first, so a lower ordinal means a higher
     * severity; this method relies on that ordering. Do not reorder the constants without
     * revisiting this contract (and every caller that depends on it).
     */
    public Severity max(Severity other) {
        return this.ordinal() <= other.ordinal() ? this : other;
    }
}
