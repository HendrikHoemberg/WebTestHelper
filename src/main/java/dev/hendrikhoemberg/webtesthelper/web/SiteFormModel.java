package dev.hendrikhoemberg.webtesthelper.web;

import dev.hendrikhoemberg.webtesthelper.catalog.SiteForm;
import dev.hendrikhoemberg.webtesthelper.model.SiteContext;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

import java.time.Duration;
import java.util.List;

/**
 * Backing form model for creating and editing websites.
 * Multiline pattern fields are accepted as newline-separated strings from textareas.
 */
public record SiteFormModel(
        @NotBlank
        String name,

        @NotBlank
        @Pattern(regexp = "^https?://.*$")
        String baseUrl,

        @Min(1)
        int maxPages,

        @Min(0)
        int maxDepth,

        @Min(1)
        int maxDurationMinutes,

        String includePatterns,

        String excludePatterns,

        Boolean respectRobots,

        String userAgent) {

    public SiteFormModel(String name, String baseUrl, int maxPages, int maxDepth, int maxDurationMinutes,
                         String includePatterns, String excludePatterns, boolean respectRobots, String userAgent) {
        this(name, baseUrl, maxPages, maxDepth, maxDurationMinutes, includePatterns, excludePatterns, (Boolean) respectRobots, userAgent);
    }

    public static SiteFormModel empty() {
        return new SiteFormModel("", "", 300, 5, 30, "", "", true, null);
    }

    public static SiteFormModel of(SiteContext context) {
        return new SiteFormModel(
                context.name(),
                context.baseUrl().value(),
                context.budget().maxPages(),
                context.budget().maxDepth(),
                (int) context.budget().maxDuration().toMinutes(),
                context.includePatterns() != null ? String.join("\n", context.includePatterns()) : "",
                context.excludePatterns() != null ? String.join("\n", context.excludePatterns()) : "",
                context.respectRobots(),
                context.userAgent()
        );
    }

    public SiteForm toForm() {
        return new SiteForm(
                name,
                baseUrl,
                maxPages,
                maxDepth,
                Duration.ofMinutes(maxDurationMinutes),
                splitPatterns(includePatterns),
                splitPatterns(excludePatterns),
                Boolean.TRUE.equals(respectRobots),
                userAgent != null && !userAgent.isBlank() ? userAgent.trim() : null
        );
    }

    private static List<String> splitPatterns(String patterns) {
        if (patterns == null || patterns.isBlank()) {
            return List.of();
        }
        return patterns.lines()
                .map(String::trim)
                .filter(line -> !line.isEmpty())
                .toList();
    }
}
