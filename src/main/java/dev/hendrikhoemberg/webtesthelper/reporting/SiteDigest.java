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
}
