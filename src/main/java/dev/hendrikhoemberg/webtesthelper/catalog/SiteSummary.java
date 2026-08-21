package dev.hendrikhoemberg.webtesthelper.catalog;

/** Lightweight projection for the site list screen. */
public record SiteSummary(long id, String name, String baseUrl, boolean enabled, int settingCount) {
}
