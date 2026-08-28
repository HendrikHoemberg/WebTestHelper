package dev.hendrikhoemberg.webtesthelper.model;

import java.util.List;
import java.util.Objects;

/**
 * A journey definition as replayed or edited (§10.3, plan 14 Task 1).
 * Pure value type: no JPA, no Spring annotations.
 *
 * @param id      database ID if persisted, or null for unsaved definitions
 * @param siteId  associated site ID if persisted, or null
 * @param name    human-readable name of the journey
 * @param enabled whether the journey is active for scheduled runs
 * @param steps   ordered list of steps
 */
public record JourneyDefinition(
        Long id,
        Long siteId,
        String name,
        boolean enabled,
        List<JourneyStep> steps
) {
    public JourneyDefinition {
        Objects.requireNonNull(name, "name");
        if (name.isBlank()) {
            throw new IllegalArgumentException("name must not be blank");
        }
        steps = steps == null ? List.of() : List.copyOf(steps);
    }
}
