package dev.hendrikhoemberg.webtesthelper.findings;

import dev.hendrikhoemberg.webtesthelper.model.CheckType;
import dev.hendrikhoemberg.webtesthelper.model.Evidence;
import dev.hendrikhoemberg.webtesthelper.model.Severity;

import java.util.List;

/**
 * A finding after materialisation: grouped, possibly promoted to site-wide, and given a stable
 * {@link #fingerprint()} (plan 4, task 1). Occurrences are deduped by page, so {@link #pageCount()}
 * is simply the number of occurrences.
 */
public record MaterialisedFinding(String fingerprint, CheckType type, Severity severity,
        String subjectKey, String locationKey, String messageKey, List<String> messageArgs,
        Evidence evidence, List<FindingOccurrence> occurrences) {

    public MaterialisedFinding {
        occurrences = List.copyOf(occurrences);
        messageArgs = messageArgs == null ? List.of() : List.copyOf(messageArgs);
        evidence = evidence == null ? Evidence.NONE : evidence;
    }

    /** Number of distinct pages the finding was observed on. */
    public int pageCount() {
        return occurrences.size();
    }
}
