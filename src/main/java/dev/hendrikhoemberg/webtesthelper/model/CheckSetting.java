package dev.hendrikhoemberg.webtesthelper.model;

import java.util.Map;

/**
 * A site's configuration for one check. {@code severityOverride} is null when the check's
 * declared default severity applies (spec 8).
 */
public record CheckSetting(boolean enabled, Severity severityOverride, Map<String, Object> config) {

    public CheckSetting {
        config = config == null ? Map.of() : Map.copyOf(config);
    }

    public static CheckSetting defaultEnabled() {
        return new CheckSetting(true, null, Map.of());
    }

    public static CheckSetting defaultDisabled() {
        return new CheckSetting(false, null, Map.of());
    }
}
