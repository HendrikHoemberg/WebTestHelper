package dev.hendrikhoemberg.webtesthelper.findings;

/**
 * The report's sections. {@code FIXED} outranks all other sections; silencing triage
 * ({@code MUTED}, {@code WONT_FIX}) outranks {@code NEW} and {@code REGRESSED} (D47); and
 * other triaged findings follow behind {@code NEW} and {@code REGRESSED} (spec 6.4).
 * The enum order defines the display order of the report's sections, with {@code KNOWN}
 * presented below the news.
 */
public enum ReportSection {
    FIXED, NEW, REGRESSED, KNOWN, STILL_OPEN
}
