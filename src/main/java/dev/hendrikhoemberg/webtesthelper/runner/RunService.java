package dev.hendrikhoemberg.webtesthelper.runner;

import dev.hendrikhoemberg.webtesthelper.catalog.SiteService;
import dev.hendrikhoemberg.webtesthelper.model.CheckType;
import dev.hendrikhoemberg.webtesthelper.model.RunScope;
import dev.hendrikhoemberg.webtesthelper.model.RunStatus;
import dev.hendrikhoemberg.webtesthelper.model.RunTrigger;
import dev.hendrikhoemberg.webtesthelper.runner.persistence.RunEntity;
import dev.hendrikhoemberg.webtesthelper.runner.persistence.RunRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Run creation, listing and summary. Lease mechanics live in the Jdbc repository; this
 * service owns the JPA-backed lifecycle and read side.
 */
@Service
@Transactional
public class RunService {

    private final RunRepository runs;
    private final SiteService sites;

    public RunService(RunRepository runs, SiteService sites) {
        this.runs = runs;
        this.sites = sites;
    }

    /**
     * Queues a run for a site, or returns the existing QUEUED run if one is already waiting
     * (clicking "Jetzt prüfen" twice must not build a backlog).
     */
    public long enqueue(long siteId, RunTrigger trigger, RunScope scope) {
        if (!sites.exists(siteId)) {
            throw new IllegalArgumentException("Site " + siteId + " existiert nicht");
        }
        return runs.findFirstBySiteIdAndStatusOrderByQueuedAtAsc(siteId, RunStatus.QUEUED)
                .map(RunEntity::getId)
                .orElseGet(() -> {
                    RunEntity run = new RunEntity();
                    run.setSiteId(siteId);
                    run.setTriggerType(trigger);
                    run.setScope(scope);
                    try {
                        return runs.save(run).getId();
                    } catch (DataIntegrityViolationException raceLostToAnotherEnqueue) {
                        // Mirrors claimNext's swallow-and-retry: the find-then-save check is
                        // racy, so ux_run_single_queued_per_site converts the loser's insert into
                        // a duplicate key. Re-query the winner's QUEUED run and return it instead.
                        return runs.findFirstBySiteIdAndStatusOrderByQueuedAtAsc(siteId, RunStatus.QUEUED)
                                .orElseThrow(() -> raceLostToAnotherEnqueue)
                                .getId();
                    }
                });
    }

    public List<RunSummary> recentForSite(long siteId, int limit) {
        return runs.findBySiteIdOrderByQueuedAtDesc(siteId, Limit.of(limit))
                .stream().map(this::toSummary).toList();
    }

    public RunSummary summary(long runId) {
        return runs.findById(runId).map(this::toSummary)
                .orElseThrow(() -> new IllegalArgumentException("Lauf " + runId + " existiert nicht"));
    }

    private RunSummary toSummary(RunEntity run) {
        Set<CheckType> covered = run.getCoveredCheckTypes().isEmpty()
                ? EnumSet.noneOf(CheckType.class)
                : run.getCoveredCheckTypes().stream().map(CheckType::valueOf).collect(Collectors.toSet());
        return new RunSummary(
                run.getId(),
                run.getSiteId(),
                run.getStatus(),
                run.getTriggerType(),
                run.getScope(),
                run.getQueuedAt(),
                run.getStartedAt(),
                run.getFinishedAt(),
                run.getPagesVisited(),
                run.getPagesFailed(),
                run.getFindingsTotal(),
                run.isPartialCoverage(),
                run.getBudgetStopReason(),
                run.isBaselineAccepted(),
                run.getErrorMessage(),
                covered);
    }
}
