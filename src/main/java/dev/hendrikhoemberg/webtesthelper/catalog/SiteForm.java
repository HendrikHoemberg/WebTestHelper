package dev.hendrikhoemberg.webtesthelper.catalog;

import java.time.Duration;
import java.util.List;

/** Editable site fields, mirrored 1:1 with the site form (spec 6.1). */
public record SiteForm(
        String name,
        String baseUrl,
        int maxPages,
        int maxDepth,
        Duration maxDuration,
        List<String> includePatterns,
        List<String> excludePatterns,
        boolean respectRobots,
        String userAgent) {
}
