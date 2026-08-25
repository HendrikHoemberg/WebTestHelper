package dev.hendrikhoemberg.webtesthelper.web;

import dev.hendrikhoemberg.webtesthelper.model.Severity;
import dev.hendrikhoemberg.webtesthelper.model.TriageStatus;

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
        TriageStatus triage
) {
}
