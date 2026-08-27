package dev.hendrikhoemberg.webtesthelper.catalog;

import dev.hendrikhoemberg.webtesthelper.model.FormTestMode;

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
        String userAgent,
        boolean enabled,
        List<String> pinnedKeyPages,
        FormTestMode formTestMode) {

    public SiteForm {
        pinnedKeyPages = pinnedKeyPages == null ? List.of() : List.copyOf(pinnedKeyPages);
        formTestMode = formTestMode == null ? FormTestMode.NO_SUBMIT : formTestMode;
    }

    public SiteForm(
            String name,
            String baseUrl,
            int maxPages,
            int maxDepth,
            Duration maxDuration,
            List<String> includePatterns,
            List<String> excludePatterns,
            boolean respectRobots,
            String userAgent,
            boolean enabled,
            List<String> pinnedKeyPages) {
        this(name, baseUrl, maxPages, maxDepth, maxDuration, includePatterns, excludePatterns,
                respectRobots, userAgent, enabled, pinnedKeyPages, FormTestMode.NO_SUBMIT);
    }

    public SiteForm(
            String name,
            String baseUrl,
            int maxPages,
            int maxDepth,
            Duration maxDuration,
            List<String> includePatterns,
            List<String> excludePatterns,
            boolean respectRobots,
            String userAgent,
            boolean enabled) {
        this(name, baseUrl, maxPages, maxDepth, maxDuration, includePatterns, excludePatterns,
                respectRobots, userAgent, enabled, List.of(), FormTestMode.NO_SUBMIT);
    }
}
