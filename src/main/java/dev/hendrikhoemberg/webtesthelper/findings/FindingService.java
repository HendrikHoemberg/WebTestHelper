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

    private final FindingStore store;
    private final FindingProperties properties;

    public FindingService(FindingStore store, FindingProperties properties) {
        this.store = store;
        this.properties = properties;
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
        return store.diffOf(siteId, runId);
    }

    public RunDiff diffOf(long siteId, long runId) {
        return store.diffOf(siteId, runId);
    }
}
