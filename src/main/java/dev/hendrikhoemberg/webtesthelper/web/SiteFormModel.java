package dev.hendrikhoemberg.webtesthelper.web;

import dev.hendrikhoemberg.webtesthelper.catalog.SiteForm;
import dev.hendrikhoemberg.webtesthelper.model.FormTestMode;
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

        String userAgent,

        Boolean enabled,

        String pinnedKeyPages,

        String formTestMode) {

    public SiteFormModel {
        if (formTestMode == null || formTestMode.isBlank()) {
            formTestMode = FormTestMode.NO_SUBMIT.name();
        }
    }

    public SiteFormModel(String name, String baseUrl, int maxPages, int maxDepth, int maxDurationMinutes,
                         String includePatterns, String excludePatterns, boolean respectRobots, String userAgent,
                         boolean enabled) {
        this(name, baseUrl, maxPages, maxDepth, maxDurationMinutes, includePatterns, excludePatterns,
                (Boolean) respectRobots, userAgent, (Boolean) enabled, "", FormTestMode.NO_SUBMIT.name());
    }

    public SiteFormModel(String name, String baseUrl, int maxPages, int maxDepth, int maxDurationMinutes,
                         String includePatterns, String excludePatterns, boolean respectRobots, String userAgent,
                         boolean enabled, String pinnedKeyPages) {
        this(name, baseUrl, maxPages, maxDepth, maxDurationMinutes, includePatterns, excludePatterns,
                (Boolean) respectRobots, userAgent, (Boolean) enabled, pinnedKeyPages, FormTestMode.NO_SUBMIT.name());
    }

    public static SiteFormModel empty() {
        return new SiteFormModel("", "", 300, 5, 30, "", "", true, null, true, "", FormTestMode.NO_SUBMIT.name());
    }

    public static SiteFormModel of(SiteContext context, boolean enabled) {
        return new SiteFormModel(
                context.name(),
                context.baseUrl().value(),
                context.budget().maxPages(),
                context.budget().maxDepth(),
                (int) context.budget().maxDuration().toMinutes(),
                context.includePatterns() != null ? String.join("\n", context.includePatterns()) : "",
                context.excludePatterns() != null ? String.join("\n", context.excludePatterns()) : "",
                context.respectRobots(),
                context.userAgent(),
                enabled,
                context.pinnedKeyPages() != null ? String.join("\n", context.pinnedKeyPages()) : "",
                context.formTestMode() != null ? context.formTestMode().name() : FormTestMode.NO_SUBMIT.name());
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
                userAgent != null && !userAgent.isBlank() ? userAgent.trim() : null,
                Boolean.TRUE.equals(enabled),
                splitPatterns(pinnedKeyPages),
                parseFormTestMode(formTestMode));
    }

    private static FormTestMode parseFormTestMode(String mode) {
        if (mode == null || mode.isBlank()) {
            return FormTestMode.NO_SUBMIT;
        }
        try {
            return FormTestMode.valueOf(mode.trim());
        } catch (IllegalArgumentException e) {
            return FormTestMode.NO_SUBMIT;
        }
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
