package dev.hendrikhoemberg.webtesthelper.recorder;

import dev.hendrikhoemberg.webtesthelper.model.JourneyStep;
import dev.hendrikhoemberg.webtesthelper.model.LocatorCandidate;
import dev.hendrikhoemberg.webtesthelper.model.StepAction;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

/**
 * Builds an executable sequence of journey steps from captured browser interaction events (§10.4).
 *
 * <p>Applies editorial rules:
 * <ul>
 *   <li>Prepend a {@link StepAction#GOTO} step targeting the start URL at ordinal 0.</li>
 *   <li>Consecutive {@link CapturedEvent.EventKind#INPUT} events on the same element collapse
 *       into a single {@link StepAction#FILL} step carrying the final value.</li>
 *   <li>A {@link CapturedEvent.EventKind#SUBMIT} directly following a {@link CapturedEvent.EventKind#CLICK}
 *       is suppressed to avoid duplicate steps for a single user action.</li>
 *   <li>Password inputs never carry plaintext values into the built step list.</li>
 *   <li>All steps receive a fresh {@link UUID} and dense sequential ordinals.</li>
 * </ul>
 */
public final class StepBuilder {

    private StepBuilder() {
    }

    /**
     * Builds a list of journey steps from the given captured events and start URL.
     *
     * @param events   the recorded browser events
     * @param startUrl the initial URL for the journey
     * @return a dense, sequential list of journey steps starting with a GOTO step
     */
    public static List<JourneyStep> build(List<CapturedEvent> events, String startUrl) {
        Objects.requireNonNull(events, "events must not be null");
        Objects.requireNonNull(startUrl, "startUrl must not be null");

        List<JourneyStep> steps = new ArrayList<>();
        int ordinal = 0;

        // Step 0 is always GOTO startUrl
        steps.add(new JourneyStep(
                UUID.randomUUID(),
                ordinal++,
                StepAction.GOTO,
                List.of(),
                startUrl,
                null,
                false,
                JourneyStep.DEFAULT_TIMEOUT_MS
        ));

        List<CapturedEvent> editorialEvents = collapseEvents(events);

        for (CapturedEvent event : editorialEvents) {
            JourneyStep step = toStep(event, ordinal++);
            steps.add(step);
        }

        return Collections.unmodifiableList(steps);
    }

    private static List<CapturedEvent> collapseEvents(List<CapturedEvent> events) {
        List<CapturedEvent> collapsed = new ArrayList<>();
        for (CapturedEvent event : events) {
            if (event == null) {
                continue;
            }
            if (event.kind() == CapturedEvent.EventKind.SUBMIT) {
                if (!collapsed.isEmpty()) {
                    CapturedEvent last = collapsed.get(collapsed.size() - 1);
                    if (last.kind() == CapturedEvent.EventKind.CLICK) {
                        continue;
                    }
                }
            }
            if (event.kind() == CapturedEvent.EventKind.INPUT || event.kind() == CapturedEvent.EventKind.CHANGE) {
                if (!collapsed.isEmpty()) {
                    CapturedEvent last = collapsed.get(collapsed.size() - 1);
                    if (isSameElement(last, event) && (last.kind() == CapturedEvent.EventKind.INPUT
                            || last.kind() == CapturedEvent.EventKind.CHANGE
                            || last.kind() == CapturedEvent.EventKind.CLICK)) {
                        collapsed.set(collapsed.size() - 1, event);
                        continue;
                    }
                }
            }
            collapsed.add(event);
        }
        return collapsed;
    }

    private static JourneyStep toStep(CapturedEvent event, int ordinal) {
        StepAction action = mapAction(event);
        List<LocatorCandidate> candidates = CandidateBuilder.build(event);
        String value = resolveValue(event, action);

        return new JourneyStep(
                UUID.randomUUID(),
                ordinal,
                action,
                candidates,
                value,
                null,
                false,
                JourneyStep.DEFAULT_TIMEOUT_MS
        );
    }

    private static StepAction mapAction(CapturedEvent event) {
        if ("select".equalsIgnoreCase(event.tagName()) || "combobox".equalsIgnoreCase(event.role())) {
            return StepAction.SELECT;
        }
        return switch (event.kind()) {
            case CLICK, SUBMIT -> StepAction.CLICK;
            case INPUT, CHANGE -> StepAction.FILL;
        };
    }

    private static String resolveValue(CapturedEvent event, StepAction action) {
        if (action == StepAction.FILL) {
            if (isPasswordField(event)) {
                return "";
            }
            return event.value() != null ? event.value() : "";
        }
        if (action == StepAction.SELECT) {
            return event.value() != null ? event.value() : "";
        }
        return null;
    }

    private static boolean isSameElement(CapturedEvent a, CapturedEvent b) {
        if (a == b) {
            return true;
        }
        if (a == null || b == null) {
            return false;
        }
        return Objects.equals(a.tagName(), b.tagName())
                && Objects.equals(a.id(), b.id())
                && Objects.equals(a.testId(), b.testId())
                && Objects.equals(a.role(), b.role())
                && Objects.equals(a.accessibleName(), b.accessibleName())
                && Objects.equals(a.labelText(), b.labelText())
                && Objects.equals(a.cssPath(), b.cssPath());
    }

    private static boolean isPasswordField(CapturedEvent event) {
        return matchesPassword(event.cssPath())
                || matchesPassword(event.id())
                || matchesPassword(event.testId())
                || matchesPassword(event.labelText())
                || matchesPassword(event.accessibleName());
    }

    private static boolean matchesPassword(String s) {
        if (s == null || s.isBlank()) {
            return false;
        }
        String lower = s.toLowerCase(Locale.ROOT);
        return lower.contains("password")
                || lower.contains("passwort")
                || lower.contains("kennwort")
                || lower.contains("pwd");
    }
}
