package dev.hendrikhoemberg.webtesthelper.recorder;

import java.util.Objects;

/**
 * A captured browser interaction event report from the injected capture script (§10.1).
 *
 * @param kind           the kind of event (CLICK, INPUT, CHANGE, SUBMIT)
 * @param tagName        lowercase tag name of the touched element
 * @param id             element ID attribute if present
 * @param testId         test ID (data-testid, data-test, data-cy) if present
 * @param role           ARIA role if present
 * @param accessibleName computed accessible name / label / alt if present
 * @param labelText      associated &lt;label&gt; text if present
 * @param textContent    visible text content if present
 * @param value          current value (for inputs/selects) if present
 * @param cssPath        scoped CSS selector path
 */
public record CapturedEvent(
        EventKind kind,
        String tagName,
        String id,
        String testId,
        String role,
        String accessibleName,
        String labelText,
        String textContent,
        String value,
        String cssPath
) {
    public CapturedEvent {
        Objects.requireNonNull(kind, "kind must not be null");
    }

    public enum EventKind {
        CLICK,
        INPUT,
        CHANGE,
        SUBMIT
    }
}
