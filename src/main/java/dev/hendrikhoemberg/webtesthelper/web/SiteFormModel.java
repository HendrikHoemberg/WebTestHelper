package dev.hendrikhoemberg.webtesthelper.web;

import dev.hendrikhoemberg.webtesthelper.catalog.SiteForm;
import dev.hendrikhoemberg.webtesthelper.model.FormTestMode;
import dev.hendrikhoemberg.webtesthelper.model.SiteContext;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import java.time.Duration;
import java.util.List;

/**
 * Backing form model for creating and editing websites.
 * Multiline pattern fields are accepted as newline-separated strings from textareas.
 */
public record SiteFormModel(
        @NotBlank(message = "{ui.websites.formular.fehler.name.pflicht}")
        String name,

        @NotBlank(message = "{ui.websites.formular.fehler.baseUrl.pflicht}")
        @Pattern(regexp = "^https?://.*$", message = "{ui.websites.formular.fehler.baseUrl.format}")
        String baseUrl,

        @NotNull(message = "{ui.websites.formular.fehler.maxPages.pflicht}")
        @Min(value = 1, message = "{ui.websites.formular.fehler.maxPages.min}")
        Integer maxPages,

        @NotNull(message = "{ui.websites.formular.fehler.maxDepth.pflicht}")
        @Min(value = 0, message = "{ui.websites.formular.fehler.maxDepth.min}")
        Integer maxDepth,

        @NotNull(message = "{ui.websites.formular.fehler.maxDurationMinutes.pflicht}")
        @Min(value = 1, message = "{ui.websites.formular.fehler.maxDurationMinutes.min}")
        Integer maxDurationMinutes,

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

    public SiteFormModel(String name, String baseUrl, Integer maxPages, Integer maxDepth, Integer maxDurationMinutes,
                         String includePatterns, String excludePatterns, boolean respectRobots, String userAgent,
                         boolean enabled) {
        this(name, baseUrl, maxPages, maxDepth, maxDurationMinutes, includePatterns, excludePatterns,
                (Boolean) respectRobots, userAgent, (Boolean) enabled, "", FormTestMode.NO_SUBMIT.name());
    }

    public SiteFormModel(String name, String baseUrl, Integer maxPages, Integer maxDepth, Integer maxDurationMinutes,
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
                maxPages != null ? maxPages : 300,
                maxDepth != null ? maxDepth : 5,
                Duration.ofMinutes(maxDurationMinutes != null ? maxDurationMinutes : 30),
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
