package dev.hendrikhoemberg.webtesthelper.model;

import java.time.Duration;

/** Budget guards. Exceeding any of them ends a run cleanly with partial coverage (spec 14). */
public record CrawlBudget(int maxPages, int maxDepth, Duration maxDuration) {

    public static final CrawlBudget DEFAULT = new CrawlBudget(300, 5, Duration.ofMinutes(30));

    public CrawlBudget {
        if (maxPages < 1) throw new IllegalArgumentException("maxPages must be >= 1");
        if (maxDepth < 0) throw new IllegalArgumentException("maxDepth must be >= 0");
        if (maxDuration.isNegative() || maxDuration.isZero()) {
            throw new IllegalArgumentException("maxDuration must be positive");
        }
    }
}
