package dev.hendrikhoemberg.webtesthelper.runner;

import dev.hendrikhoemberg.webtesthelper.model.CheckType;
import dev.hendrikhoemberg.webtesthelper.model.RunScope;
import dev.hendrikhoemberg.webtesthelper.model.RunStatus;
import dev.hendrikhoemberg.webtesthelper.model.RunTrigger;

import java.time.Instant;
import java.util.List;
import java.util.Set;

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
        Set<CheckType> coveredCheckTypes,
        Set<CheckType> coveredInteractionCheckTypes,
        List<String> coveredInteractionUrls) {

    public RunSummary(
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
            Set<CheckType> coveredCheckTypes) {
        this(id, siteId, status, trigger, scope, queuedAt, startedAt, finishedAt,
                pagesVisited, pagesFailed, findingsTotal, findingsNew, findingsResolved,
                partialCoverage, budgetStopReason, baselineAccepted, errorMessage,
                coveredCheckTypes, Set.of(), List.of());
    }
}
