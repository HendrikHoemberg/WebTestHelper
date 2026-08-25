package dev.hendrikhoemberg.webtesthelper.findings;

import dev.hendrikhoemberg.webtesthelper.model.CheckFinding;
import dev.hendrikhoemberg.webtesthelper.model.RunCoverage;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/**
 * The findings write path. {@link #record} is four statements in one transaction — upsert,
 * occurrences, recount, resolve — and then it reads the diff back out of the database rather than
 * computing it in memory, so the report and the table can never disagree (plan 4, task 3).
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
     */
    public RunDiff record(long runId, long siteId, List<CheckFinding> findings, RunCoverage coverage,
            Instant observedAt) {
        List<MaterialisedFinding> materialised =
                FindingMaterializer.materialise(siteId, findings, properties.siteWideThreshold());
        List<Long> ids = store.upsertAll(siteId, runId, materialised, observedAt);
        store.insertOccurrences(ids, runId, materialised, observedAt);
        store.recountOccurrences(ids);
        store.resolveOutsideRun(siteId, runId, coverage);
        // D46: Apply mute rules after resolveOutsideRun and before diffOf inside the same transaction
        muteRuleApplier.applyToRun(siteId, runId, observedAt);
        return store.diffOf(siteId, runId);
    }

    public RunDiff diffOf(long siteId, long runId) {
        return store.diffOf(siteId, runId);
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
}

