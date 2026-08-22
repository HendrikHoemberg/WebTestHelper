package dev.hendrikhoemberg.webtesthelper.checks;

import dev.hendrikhoemberg.webtesthelper.model.RunFacts;
import dev.hendrikhoemberg.webtesthelper.model.Severity;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * What a check is handed besides its subject: the severity this site resolved for it, the
 * site's per-check options, and the run-scoped facts (deviation D3).
 *
 * @param options the site's {@code site_check_setting.config} jsonb, so a number arrives as
 *                whatever Jackson produced — {@link #option(String, int)} exists because
 *                {@code (Integer) options.get("maxHops")} throws on a perfectly valid Long.
 */
public record CheckConfig(Severity severity, Map<String, Object> options, RunFacts facts) {

    public CheckConfig {
        Objects.requireNonNull(severity, "severity");
        Objects.requireNonNull(facts, "facts");
        if (options == null) {
            options = Map.of();
        } else {
            Map<String, Object> copy = new HashMap<>(options);
            copy.values().removeIf(Objects::isNull);    // jsonb: {"maxDistance": null}
            options = Map.copyOf(copy);
        }
    }

    public int option(String key, int fallback) {
        return options.get(key) instanceof Number number ? number.intValue() : fallback;
    }

    /** A list-valued option, e.g. the ignore patterns of {@code CONSOLE_ERRORS}. */
    public List<String> optionList(String key) {
        if (!(options.get(key) instanceof List<?> values)) {
            return List.of();
        }
        return values.stream().filter(Objects::nonNull).map(Object::toString).toList();
    }
}