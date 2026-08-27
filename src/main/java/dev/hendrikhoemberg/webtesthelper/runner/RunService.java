package dev.hendrikhoemberg.webtesthelper.runner;

import dev.hendrikhoemberg.webtesthelper.catalog.SiteService;
import dev.hendrikhoemberg.webtesthelper.findings.FindingService;
import dev.hendrikhoemberg.webtesthelper.model.CheckType;
import dev.hendrikhoemberg.webtesthelper.model.RunScope;
import dev.hendrikhoemberg.webtesthelper.model.RunStatus;
import dev.hendrikhoemberg.webtesthelper.model.RunTrigger;
import dev.hendrikhoemberg.webtesthelper.runner.persistence.RunDashboardJdbcRepository;
import dev.hendrikhoemberg.webtesthelper.runner.persistence.RunEntity;
import dev.hendrikhoemberg.webtesthelper.runner.persistence.RunRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.time.Instant;
import java.util.stream.Collectors;

/**
 * Run creation, listing and summary. Lease mechanics live in the Jdbc repository; this
 * service owns the JPA-backed lifecycle and read side.
 *
 * <p>Deliberately not {@code @Transactional} at the type level (only {@link #acceptBaseline}
 * is, and it says why): each Spring Data repository
 * call is its own transaction anyway, and {@link #enqueue} must re-query with a fresh
 * persistence context after a failed insert (a transaction whose save hit the unique index
 * is rollback-only and poisoned for further queries).
 */
@Service
public class RunService {

    private final RunRepository runs;
    private final RunDashboardJdbcRepository dashboard;
    private final SiteService sites;
    private final FindingService findings;

    public RunService(RunRepository runs, RunDashboardJdbcRepository dashboard,
                      SiteService sites, FindingService findings) {
        this.runs = runs;
        this.dashboard = dashboard;
        this.sites = sites;
        this.findings = findings;
    }

    /**
     * Queues a run for a site, or returns the existing QUEUED run <em>of the same scope</em> if
     * one is already waiting (clicking "Jetzt prüfen" twice must not build a backlog).
     *
     * <p>Dedupe is per scope, not per site. The three tiers of spec 9 share the 03:00 window and
     * on the first Sunday of a month all three fire for the same site; collapsing them would
     * silently drop the deep run, which is the only tier that submits forms and verifies mail.
     * One run at a time per site (spec 5.3) is a constraint on RUNNING, and claimNext still
     * enforces it — the tiers queue together and execute one after another.
     */
    public long enqueue(long siteId, RunTrigger trigger, RunScope scope) {
        if (!sites.exists(siteId)) {
            throw new IllegalArgumentException("Site " + siteId + " existiert nicht");
        }
        Optional<RunEntity> alreadyQueued = runs.findFirstBySiteIdAndStatusAndScopeOrderByQueuedAtAsc(
                siteId, RunStatus.QUEUED, scope);
        if (alreadyQueued.isPresent()) {
            return alreadyQueued.get().getId();
        }
        RunEntity run = new RunEntity();
        run.setSiteId(siteId);
        run.setTriggerType(trigger);
        run.setScope(scope);
        try {
            return runs.save(run).getId();
        } catch (DataIntegrityViolationException raceLostToAnotherEnqueue) {
            // Mirrors claimNext's swallow-and-retry: the find-then-save check is
            // racy, so ux_run_single_queued_per_site_scope converts the loser's insert into
            // a duplicate key. Re-query the winner's QUEUED run and return it instead.
            // The re-query runs in a fresh transaction: the failed save rolled back and
            // poisoned its own persistence context, which must not be reused.
            return runs.findFirstBySiteIdAndStatusAndScopeOrderByQueuedAtAsc(
                            siteId, RunStatus.QUEUED, scope)
                    .orElseThrow(() -> raceLostToAnotherEnqueue)
                    .getId();
        }
    }

    public List<RunSummary> recentForSite(long siteId, int limit) {
        return runs.findBySiteIdOrderByQueuedAtDesc(siteId, Limit.of(limit))
                .stream().map(this::toSummary).toList();
    }

    /**
     * The newest terminal run per site ({@code COMPLETED}/{@code FAILED} only). A site whose only
     * run is {@code QUEUED} or {@code RUNNING} is absent: "nothing has finished here yet" and "it
     * is green" are different answers, and the grid needs the one that is true.
     */
    public Map<Long, LastRun> lastTerminalPerSite() {
        return dashboard.lastTerminalPerSite().stream()
                .collect(Collectors.toMap(LastRun::siteId, Function.identity()));
    }

    /** Runs waiting for a worker plus runs in flight, all scopes. */
    public int runsInFlight() {
        return dashboard.runsInFlight();
    }

    public RunSummary summary(long runId) {
        return runs.findById(runId).map(this::toSummary)
                .orElseThrow(() -> new IllegalArgumentException("Lauf " + runId + " existiert nicht"));
    }

    /**
     * Accepts the run as the baseline: acknowledges every {@code UNTRIAGED} finding the run
     * observed, then stamps {@code baseline_accepted_at}. Returns how many findings moved.
     *
     * <p>The one method on this service that <em>is</em> transactional. The acknowledgement and
     * the stamp are one decision: committing the first without the second would leave a run that
     * offers its baseline button again while every finding behind it is already acknowledged, so
     * the second press would report nothing moved.
     */
    @Transactional
    public int acceptBaseline(long runId) {
        RunEntity run = runs.findById(runId)
                .orElseThrow(() -> new IllegalArgumentException("Lauf " + runId + " existiert nicht"));
        int moved = findings.acceptBaseline(run.getSiteId(), runId);
        run.setBaselineAcceptedAt(Instant.now());
        runs.save(run);
        return moved;
    }

    public List<RunSummary> undigested(RunScope scope) {
        return runs.findByScopeAndDigestSentAtIsNullAndStatusInOrderByFinishedAtAsc(
                        scope, List.of(RunStatus.COMPLETED, RunStatus.FAILED))
                .stream().map(this::toSummary).toList();
    }

    public boolean hasRunsInFlight(RunScope scope) {
        return runs.existsByScopeAndStatusIn(scope, List.of(RunStatus.QUEUED, RunStatus.RUNNING));
    }

    @Transactional
    public int markDigested(List<Long> runIds, Instant at) {
        if (runIds == null || runIds.isEmpty()) {
            return 0;
        }
        return runs.markDigested(runIds, at);
    }

    private RunSummary toSummary(RunEntity run) {
        Set<CheckType> covered = run.getCoveredCheckTypes().isEmpty()
                ? EnumSet.noneOf(CheckType.class)
                : run.getCoveredCheckTypes().stream().map(CheckType::valueOf).collect(Collectors.toSet());
        Set<CheckType> coveredInteractions = run.getCoveredInteractionCheckTypes().isEmpty()
                ? EnumSet.noneOf(CheckType.class)
                : run.getCoveredInteractionCheckTypes().stream().map(CheckType::valueOf).collect(Collectors.toSet());
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
                run.getFindingsNew(),
                run.getFindingsResolved(),
                run.isPartialCoverage(),
                run.getBudgetStopReason(),
                run.isBaselineAccepted(),
                run.getErrorMessage(),
                covered,
                coveredInteractions,
                run.getCoveredInteractionUrls() != null ? run.getCoveredInteractionUrls() : List.of());
    }
}
