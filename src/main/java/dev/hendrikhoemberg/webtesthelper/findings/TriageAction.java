package dev.hendrikhoemberg.webtesthelper.findings;

import dev.hendrikhoemberg.webtesthelper.model.TriageStatus;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;

/**
 * Validates and represents a human triage action on one or more findings.
 *
 * @param target     the target triage status.
 * @param reason     the mandatory reason for MUTED and WONT_FIX, optional for ACKNOWLEDGED, null for UNTRIAGED.
 * @param mutedUntil the mandatory expiry date for MUTED, strictly future and bounded by maxMuteDays; null otherwise.
 */
public record TriageAction(TriageStatus target, String reason, Instant mutedUntil) {

    public static TriageAction of(TriageStatus target, String reason, Instant mutedUntil, Instant now, int maxMuteDays) {
        Objects.requireNonNull(target, "target must not be null");
        Objects.requireNonNull(now, "now must not be null");

        return switch (target) {
            case MUTED -> {
                if (reason == null || reason.isBlank()) {
                    throw new TriageValidationException("triage.reason.required");
                }
                if (mutedUntil == null) {
                    throw new TriageValidationException("triage.mutedUntil.required");
                }
                if (!mutedUntil.isAfter(now)) {
                    throw new TriageValidationException("triage.mutedUntil.past");
                }
                if (mutedUntil.isAfter(now.plus(maxMuteDays, ChronoUnit.DAYS))) {
                    throw new TriageValidationException("triage.mutedUntil.too_far");
                }
                yield new TriageAction(TriageStatus.MUTED, reason, mutedUntil);
            }
            case WONT_FIX -> {
                if (reason == null || reason.isBlank()) {
                    throw new TriageValidationException("triage.reason.required");
                }
                if (mutedUntil != null) {
                    throw new TriageValidationException("triage.mutedUntil.forbidden");
                }
                yield new TriageAction(TriageStatus.WONT_FIX, reason, null);
            }
            case ACKNOWLEDGED -> {
                if (mutedUntil != null) {
                    throw new TriageValidationException("triage.mutedUntil.forbidden");
                }
                yield new TriageAction(TriageStatus.ACKNOWLEDGED, reason, null);
            }
            case UNTRIAGED -> new TriageAction(TriageStatus.UNTRIAGED, null, null);
        };
    }
}
