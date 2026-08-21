package dev.hendrikhoemberg.webtesthelper.model;

/** Only ERROR triggers notification by default (spec 8). */
public enum Severity {
    ERROR, WARN, INFO;

    /** Highest severity of two, used when occurrences of one subject disagree. */
    public Severity max(Severity other) {
        return this.ordinal() <= other.ordinal() ? this : other;
    }
}
