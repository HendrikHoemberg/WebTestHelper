package dev.hendrikhoemberg.webtesthelper.findings;

/**
 * Result of an expiry sweep across findings and mute rules (spec 6.3, D49, D50).
 */
public record MuteSweepResult(int findingsUnmuted, int rulesExpired) {
}
