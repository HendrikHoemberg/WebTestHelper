package dev.hendrikhoemberg.webtesthelper.reporting;

import dev.hendrikhoemberg.webtesthelper.model.Severity;
import dev.hendrikhoemberg.webtesthelper.model.TriageStatus;

import java.time.Instant;

/**
 * The view representation of a finding. Deliberately carries no CheckType (spec 13.1),
 * preventing internal identifiers from leaking into the UI.
 */
public record FindingView(
        long id,
        String title,
        String message,
        String remediation,
        String locationText,
        boolean siteWide,
        int pageCount,
        Severity severity,
        TriageStatus triage,
        Instant mutedUntil,
        Instant muteExpired,
        String triageReason
) {
    public FindingView(
            long id,
            String title,
            String message,
            String remediation,
            String locationText,
            boolean siteWide,
            int pageCount,
            Severity severity,
            TriageStatus triage
    ) {
        this(id, title, message, remediation, locationText, siteWide, pageCount, severity, triage, null, null, null);
    }
}
