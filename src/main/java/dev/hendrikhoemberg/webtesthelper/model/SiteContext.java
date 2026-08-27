package dev.hendrikhoemberg.webtesthelper.model;

import java.util.List;
import java.util.Map;

/**
 * Everything a run needs to know about the site it is checking. Immutable, and the only
 * shape in which catalog data crosses a module boundary — the JPA entities never do.
 */
public record SiteContext(
        long siteId,
        String name,
        NormalizedUrl baseUrl,
        CrawlBudget budget,
        List<String> includePatterns,
        List<String> excludePatterns,
        List<String> pinnedKeyPages,
        boolean respectRobots,
        String userAgent,
        Map<CheckType, CheckSetting> checkSettings,
        FormTestMode formTestMode) {

    public SiteContext {
        includePatterns = List.copyOf(includePatterns);
        excludePatterns = List.copyOf(excludePatterns);
        pinnedKeyPages = List.copyOf(pinnedKeyPages);
        checkSettings = Map.copyOf(checkSettings);
        if (formTestMode == null) {
            formTestMode = FormTestMode.NO_SUBMIT;
        }
    }

    public SiteContext(
            long siteId,
            String name,
            NormalizedUrl baseUrl,
            CrawlBudget budget,
            List<String> includePatterns,
            List<String> excludePatterns,
            List<String> pinnedKeyPages,
            boolean respectRobots,
            String userAgent,
            Map<CheckType, CheckSetting> checkSettings) {
        this(siteId, name, baseUrl, budget, includePatterns, excludePatterns, pinnedKeyPages,
                respectRobots, userAgent, checkSettings, FormTestMode.NO_SUBMIT);
    }

    public boolean enabled(CheckType type) {
        CheckSetting setting = checkSettings.get(type);
        return setting != null && setting.enabled();
    }

    public Map<String, Object> settingsFor(CheckType type) {
        CheckSetting setting = checkSettings.get(type);
        return setting == null ? Map.of() : setting.config();
    }

    public Severity severityFor(CheckType type, Severity declaredDefault) {
        CheckSetting setting = checkSettings.get(type);
        return (setting == null || setting.severityOverride() == null)
                ? declaredDefault
                : setting.severityOverride();
    }

    /** The User-Agent to identify ourselves with, so the company's access logs stay greppable (spec 8). */
    public String effectiveUserAgent() {
        return (userAgent == null || userAgent.isBlank())
                ? "WebTestHelper/1.0 (+internes Website-Monitoring)"
                : userAgent;
    }
}
