package dev.hendrikhoemberg.webtesthelper.reporting;

import dev.hendrikhoemberg.webtesthelper.model.Severity;

import java.util.List;

/**
 * Group of findings under the same check title within a report section.
 * Provides aggregated severity counts and category-level remediation for UI badges and banners.
 */
public record FindingGroup(
        String title,
        String remediation,
        List<FindingView> findings,
        long errorCount,
        long warnCount,
        long infoCount
) {
    public FindingGroup(String title, List<FindingView> findings, long errorCount, long warnCount, long infoCount) {
        this(title, (findings != null && !findings.isEmpty()) ? findings.getFirst().remediation() : null, findings, errorCount, warnCount, infoCount);
    }

    public static FindingGroup of(String title, List<FindingView> findings) {
        long errors = findings != null ? findings.stream().filter(f -> f.severity() == Severity.ERROR).count() : 0;
        long warns = findings != null ? findings.stream().filter(f -> f.severity() == Severity.WARN).count() : 0;
        long infos = findings != null ? findings.stream().filter(f -> f.severity() == Severity.INFO).count() : 0;
        String rem = (findings != null && !findings.isEmpty()) ? findings.getFirst().remediation() : null;
        return new FindingGroup(title, rem, findings, errors, warns, infos);
    }

    public int totalCount() {
        return findings != null ? findings.size() : 0;
    }
}
