package dev.hendrikhoemberg.webtesthelper.findings;

import dev.hendrikhoemberg.webtesthelper.model.CheckType;
import dev.hendrikhoemberg.webtesthelper.model.Evidence;
import dev.hendrikhoemberg.webtesthelper.model.ObservedStatus;
import dev.hendrikhoemberg.webtesthelper.model.Severity;
import dev.hendrikhoemberg.webtesthelper.model.TriageStatus;

import java.time.Instant;
import java.util.List;

/**
 * One finding row, read side only. The persistent shape of spec 6.2: two orthogonal status
 * columns ({@link ObservedStatus} and {@link TriageStatus}) that must never collapse into one,
 * plus the run ids that make a regression visible (spec 6.3).
 *
 * @param observed        whether the finding is currently observable on the site.
 * @param triage          the human disposition of the finding.
 * @param resolvedAtRun   the run that resolved the finding; stays set after a regression so the
 *                       regression can be distinguished from a brand-new finding.
 */
public record Finding(long id, long siteId, String fingerprint, CheckType type, String subjectKey,
                      String locationKey, Severity severity, String messageKey, List<String> messageArgs,
                      Evidence evidence, ObservedStatus observed, TriageStatus triage,
                      String triageReason, long firstSeenRun, long lastSeenRun, Long resolvedAtRun,
                      int occurrenceCount, int pageCount, Instant firstSeenAt, Instant lastSeenAt) {

    public Finding {
        messageArgs = messageArgs == null ? List.of() : List.copyOf(messageArgs);
        evidence = evidence == null ? Evidence.NONE : evidence;
    }
}
