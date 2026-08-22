package dev.hendrikhoemberg.webtesthelper.model;

/** A hreflang alternate language link declared in the page's <head>. */
public record AlternateRef(String hreflang, NormalizedUrl target) {
}
