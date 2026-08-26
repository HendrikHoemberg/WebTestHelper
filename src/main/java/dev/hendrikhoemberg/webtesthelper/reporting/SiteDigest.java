package dev.hendrikhoemberg.webtesthelper.reporting;

import dev.hendrikhoemberg.webtesthelper.model.RunStatus;

import java.time.Instant;
import java.util.List;

/**
 * Summary of a single site's run within a digest window.
 *
 * @param errorCount count of NEW + REGRESSED findings at ERROR severity
 */
public record SiteDigest(
        long siteId,
        String siteName,
        long runId,
        RunStatus status,
        Instant finishedAt,
        String errorMessage,
        boolean partialCoverage,
        DigestSection news,
        DigestSection regressions,
        int errorCount,
        int fixedCount,
        int stillOpenCount,
        int knownCount
) {
    public SiteDigest {
        if (news == null) {
            news = new DigestSection(List.of(), 0);
        }
        if (regressions == null) {
            regressions = new DigestSection(List.of(), 0);
        }
    }

    public boolean failed() {
        return status == RunStatus.FAILED;
    }

    public boolean notable() {
        return errorCount > 0 || failed();
    }

    /**
     * Nothing whatsoever to report: no findings in either section, no counts, full coverage, no
     * failure. Spec 11.2 aggregates to keep volume down, so these sites become one summary line
     * rather than a card each. A site with so much as a "3 behoben" keeps its card.
     */
    public boolean quiet() {
        return !failed()
                && !partialCoverage
                && news.total() == 0
                && regressions.total() == 0
                && fixedCount == 0
                && stillOpenCount == 0
                && knownCount == 0;
    }
}
