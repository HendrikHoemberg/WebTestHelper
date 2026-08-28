package dev.hendrikhoemberg.webtesthelper.recorder;

import dev.hendrikhoemberg.webtesthelper.model.AuthoredId;
import dev.hendrikhoemberg.webtesthelper.model.LocatorCandidate;
import dev.hendrikhoemberg.webtesthelper.model.LocatorStrategy;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Builds ranked locator candidate lists from captured browser interaction events (§10.2).
 *
 * <p>Emits candidates in {@link LocatorStrategy} declaration preference order (from {@code TEST_ID}
 * down to {@code CSS}). Excludes framework-generated IDs via {@link AuthoredId#looksAuthored}
 * and suppresses overlong text content selectors. The candidate list is guaranteed to never be empty.
 */
public final class CandidateBuilder {

    /** Maximum allowed character length for a text content selector candidate. */
    public static final int MAX_TEXT_LENGTH = 80;

    private CandidateBuilder() {
    }

    /**
     * Builds a ranked, unmodifiable list of locator candidates for the given captured event.
     *
     * @param event the captured interaction event
     * @return a sorted, non-empty list of locator candidates
     */
    public static List<LocatorCandidate> build(CapturedEvent event) {
        Objects.requireNonNull(event, "event");

        List<LocatorCandidate> candidates = new ArrayList<>();

        // 1. TEST_ID
        if (isNonBlank(event.testId())) {
            candidates.add(new LocatorCandidate(LocatorStrategy.TEST_ID, event.testId().trim(), 0));
        }

        // 2. ROLE
        if (isNonBlank(event.role())) {
            String roleValue = event.role().trim();
            if (isNonBlank(event.accessibleName())) {
                roleValue = roleValue + "[name=\"" + event.accessibleName().trim() + "\"]";
            }
            candidates.add(new LocatorCandidate(LocatorStrategy.ROLE, roleValue, 0));
        }

        // 3. LABEL
        if (isNonBlank(event.labelText())) {
            candidates.add(new LocatorCandidate(LocatorStrategy.LABEL, event.labelText().trim(), 0));
        }

        // 4. ID (only authored ids)
        if (isNonBlank(event.id()) && AuthoredId.looksAuthored(event.id().trim())) {
            candidates.add(new LocatorCandidate(LocatorStrategy.ID, event.id().trim(), 0));
        }

        // 5. TEXT (bounded length)
        if (isNonBlank(event.textContent())) {
            String text = event.textContent().trim();
            if (text.length() <= MAX_TEXT_LENGTH) {
                candidates.add(new LocatorCandidate(LocatorStrategy.TEXT, text, 0));
            }
        }

        // 6. CSS (fallback guarantee: cssPath -> tagName -> "*")
        String cssValue;
        if (isNonBlank(event.cssPath())) {
            cssValue = event.cssPath().trim();
        } else if (isNonBlank(event.tagName())) {
            cssValue = event.tagName().trim();
        } else {
            cssValue = "*";
        }
        candidates.add(new LocatorCandidate(LocatorStrategy.CSS, cssValue, 0));

        return Collections.unmodifiableList(candidates);
    }

    private static boolean isNonBlank(String s) {
        return s != null && !s.isBlank();
    }
}
