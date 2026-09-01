package dev.hendrikhoemberg.webtesthelper.findings;

import dev.hendrikhoemberg.webtesthelper.model.CheckFinding;
import dev.hendrikhoemberg.webtesthelper.model.CheckType;
import dev.hendrikhoemberg.webtesthelper.model.RunCoverage;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The findings write path. {@link #record} performs the lifecycle writes and report snapshot in one
 * transaction, then reads the diff back out of the database rather than computing it in memory, so
 * the report and the table can never disagree (plan 4, task 3).
 */
@Service
@Transactional
public class FindingService {

    /**
     * The triage reason stamped when a baseline is accepted: a plain-German sentence a colleague
     * reads (spec 13.1), carrying no internal identifier.
     */
    private static final String BASELINE_TRIAGE_REASON = "Als Ausgangsbestand übernommen.";

    private final FindingStore store;
    private final FindingProperties properties;
    private final MuteRuleApplier muteRuleApplier;

    public FindingService(FindingStore store, FindingProperties properties, MuteRuleApplier muteRuleApplier) {
        this.store = store;
        this.properties = properties;
        this.muteRuleApplier = muteRuleApplier;
    }

    /**
     * Records one run as a single transaction. {@code findings} must be the COMPLETE set of
     * findings the run produced — the crawl pipeline guarantees this. A partial replay under the
     * same {@code runId} would leave the unmentioned findings' {@code last_seen_run} stale, so
     * {@link FindingStore#resolveOutsideRun} could not tell they were absent and would never
     * resolve them (spec 6.4); the caller contract for tasks 4–6 depends on this.
     *
     * <p>This overload carries no exclusion set, so it applies only to callers that pass the full
     * finding set — this form and the 6-arg form below both delegate with {@link Set#of()}. The
     * production journey path uses the overload that takes {@code journeysNeedingRerecording}.
     */
    public RunDiff record(long runId, long siteId, List<CheckFinding> findings, RunCoverage coverage,
            Instant observedAt) {
        return record(runId, siteId, findings, List.of(), coverage, observedAt);
    }

    public RunDiff record(long runId, long siteId, List<CheckFinding> findings,
            List<MaterialisedFinding> extraMaterialised, RunCoverage coverage, Instant observedAt) {
        return record(runId, siteId, findings, extraMaterialised, coverage, Set.of(), observedAt);
    }

    /**
     * Records one run as a single transaction, excluding journeys flagged needs-re-recording (§10.4)
     * from resolution.
     *
     * <p>{@code findings} may intentionally omit {@link CheckType#JOURNEY_STEP_FAILED} findings for
     * journeys in {@code journeysNeedingRerecording} — {@code JourneyPass} stops emitting them once a
     * journey is flagged. Those omitted findings are deliberately NOT resolved to FIXED: the
     * exclusion set is subtracted from the journey resolve scope in
     * {@link FindingStore#resolveOutsideRun(long, long, RunCoverage, java.util.Set)}, so a run that
     * completed such a journey without re-observing it still leaves the finding ACTIVE (under
     * "Still open") rather than reporting a still-failing journey as fixed. Healthy journeys not in
     * the set keep the normal COMPLETE-set contract described above.
     *
     * @param journeysNeedingRerecording journey IDs (subset of {@code coverage.journeyIds()}) whose
     *                                    findings must stay ACTIVE; null is treated as empty
     */
    public RunDiff record(long runId, long siteId, List<CheckFinding> findings,
            List<MaterialisedFinding> extraMaterialised, RunCoverage coverage,
            Set<Long> journeysNeedingRerecording, Instant observedAt) {
        Set<Long> excluded = journeysNeedingRerecording == null ? Set.of() : journeysNeedingRerecording;
        List<MaterialisedFinding> materialised = new ArrayList<>(
                FindingMaterializer.materialise(siteId, findings, properties.siteWideThreshold(),
                        coverage.interactionCheckTypes()));
        if (extraMaterialised != null && !extraMaterialised.isEmpty()) {
            materialised.addAll(extraMaterialised);
        }
        List<Long> ids = store.upsertAll(siteId, runId, materialised, observedAt);
        store.insertOccurrences(ids, runId, materialised, observedAt);
        store.recountOccurrences(ids);
        store.resolveOutsideRun(siteId, runId, coverage, excluded);
        // D46: Apply mute rules after resolveOutsideRun and before diffOf inside the same transaction
        muteRuleApplier.applyToRun(siteId, runId, observedAt);
        store.snapshotDiff(siteId, runId);
        return store.diffOf(siteId, runId);
    }

    public RunDiff diffOf(long siteId, long runId) {
        return store.diffOf(siteId, runId);
    }

    public RunDiff diffForReport(long siteId, long runId) {
        return store.snapshotOf(siteId, runId).orElseGet(() -> store.diffOf(siteId, runId));
    }

    /**
     * Marks every {@code UNTRIAGED} finding the run observed as {@code ACKNOWLEDGED}, recording
     * the baseline reason and now. Only what that run actually saw is acknowledged — a finding
     * with no occurrence in the run is left alone. Idempotent: the {@code UNTRIAGED} guard makes
     * a repeat call move nothing.
     */
    @Transactional
    public int acceptBaseline(long siteId, long runId) {
        return store.acceptBaseline(siteId, runId, BASELINE_TRIAGE_REASON, Instant.now());
    }

    /**
     * Triages a list of findings for a site using the given validated action and actor.
     * Returns the number of findings modified.
     */
    @Transactional
    public int triage(long siteId, List<Long> ids, TriageAction action, String actor, Instant now) {
        return store.triage(siteId, ids, action, actor, now);
    }

    public java.util.Optional<Finding> byId(long id) {
        return store.byId(id);
    }

    public FindingPage search(FindingQuery query) {
        return store.search(query);
    }

    public List<FindingOccurrence> occurrencesOfLastRun(long findingId, int limit) {
        return store.occurrencesOfLastRun(findingId, limit);
    }

    @Transactional(readOnly = true)
    public Map<Long, OpenFindingCounts> openCountsBySite() {
        return store.openCountsBySite();
    }
}
