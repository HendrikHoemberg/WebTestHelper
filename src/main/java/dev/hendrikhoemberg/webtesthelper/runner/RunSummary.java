package dev.hendrikhoemberg.webtesthelper.runner;

import dev.hendrikhoemberg.webtesthelper.model.RunScope;
import dev.hendrikhoemberg.webtesthelper.model.RunStatus;
import dev.hendrikhoemberg.webtesthelper.model.RunTrigger;

import java.time.Instant;

/** A read-only snapshot of a run's public, externally-visible state. */
public record RunSummary(
        long id,
        long siteId,
        RunStatus status,
        RunTrigger trigger,
        RunScope scope,
        Instant queuedAt,
        Instant startedAt,
        Instant finishedAt,
        int pagesVisited,
        int pagesFailed,
        int findingsTotal,
        int findingsNew,
        int findingsResolved,
        boolean partialCoverage,
        String budgetStopReason,
        boolean baselineAccepted,
        String errorMessage,
        java.util.Set<dev.hendrikhoemberg.webtesthelper.model.CheckType> coveredCheckTypes) {
}
