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
 * @param triageReason    the human or rule explanation for the triage decision.
 * @param triagedBy       the actor who triaged the finding.
 * @param mutedUntil      the expiry timestamp for a muted finding.
 * @param muteExpiredAt   the timestamp when a mute expired (set by sweep).
 * @param mutedByRuleId   the ID of the rule that muted this finding, if any.
 * @param resolvedAtRun   the run that last resolved the finding; kept as history after a
 *                        regression, so a row still tells the whole fix/regression cycle.
 * @param regressedAtRun  the run that revived the finding from {@code RESOLVED}. A regression is
 *                        news in that run alone — reporting it in every later run would mail on
 *                        every run forever (spec 11.1) and would keep an acknowledged finding out
 *                        of the quiet Known section for good.
 */
public record Finding(long id, long siteId, String fingerprint, CheckType type, String subjectKey,
                      String locationKey, Severity severity, String messageKey, List<String> messageArgs,
                      Evidence evidence, ObservedStatus observed, TriageStatus triage,
                      String triageReason, String triagedBy, Instant mutedUntil, Instant muteExpiredAt,
                      Long mutedByRuleId,
                      long firstSeenRun, long lastSeenRun, Long resolvedAtRun,
                      Long regressedAtRun, int occurrenceCount, int pageCount, Instant firstSeenAt,
                      Instant lastSeenAt) {

    public Finding {
        messageArgs = messageArgs == null ? List.of() : List.copyOf(messageArgs);
        evidence = evidence == null ? Evidence.NONE : evidence;
    }

    public Finding(long id, long siteId, String fingerprint, CheckType type, String subjectKey,
                   String locationKey, Severity severity, String messageKey, List<String> messageArgs,
                   Evidence evidence, ObservedStatus observed, TriageStatus triage,
                   String triageReason, long firstSeenRun, long lastSeenRun, Long resolvedAtRun,
                   Long regressedAtRun, int occurrenceCount, int pageCount, Instant firstSeenAt,
                   Instant lastSeenAt) {
        this(id, siteId, fingerprint, type, subjectKey, locationKey, severity, messageKey, messageArgs,
                evidence, observed, triage, triageReason, null, null, null, null,
                firstSeenRun, lastSeenRun, resolvedAtRun, regressedAtRun, occurrenceCount, pageCount,
                firstSeenAt, lastSeenAt);
    }
}
