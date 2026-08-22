package dev.hendrikhoemberg.webtesthelper.crawler.persistence;

import dev.hendrikhoemberg.webtesthelper.crawler.CrawlItemStatus;
import dev.hendrikhoemberg.webtesthelper.crawler.CrawlOutcome;
import dev.hendrikhoemberg.webtesthelper.crawler.CrawlTarget;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.time.Duration;
import java.util.Collection;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * The crawl frontier. Raw SQL throughout: batched claim/complete is the point (spec 6.5), and
 * FOR UPDATE SKIP LOCKED has no JPA equivalent.
 */
@Repository
public class CrawlFrontierJdbcRepository {

    /**
     * Claims a batch under one statement. SKIP LOCKED lets several browser workers claim
     * simultaneously without blocking each other; ORDER BY depth makes the crawl
     * breadth-first, so a budget-capped run has covered the pages nearest the entry points.
     */
    private static final String CLAIM_SQL = """
            UPDATE crawl_queue_item
               SET status     = 'CLAIMED',
                   claimed_by = ?,
                   claimed_at = now(),
                   attempts   = attempts + 1
              FROM (SELECT id
                      FROM crawl_queue_item
                     WHERE run_id = ? AND status = 'PENDING'
                     ORDER BY depth, id
                     LIMIT ?
                     FOR UPDATE SKIP LOCKED) sub
             WHERE crawl_queue_item.id = sub.id
         RETURNING crawl_queue_item.id, crawl_queue_item.url, crawl_queue_item.depth
            """;

    private static final String ENQUEUE_SQL = """
            INSERT INTO crawl_queue_item (run_id, url, depth, discovered_from)
            VALUES (?, ?, ?, ?)
            ON CONFLICT (run_id, url) DO NOTHING
            """;

    private static final String COMPLETE_SQL = """
            UPDATE crawl_queue_item
               SET status = ?, http_status = ?, error_message = ?, completed_at = now()
             WHERE id = ?
            """;

    /**
     * Returns an abandoned claim to the queue — a worker whose browser died, or a container
     * that restarted mid-run (spec 14). A URL that has burned {@code maxAttempts} workers is
     * marked FAILED instead: it is far likelier to be a page that crashes the tab than a
     * coincidence, and one bad page must never keep a run alive forever.
     */
    private static final String RECLAIM_SQL = """
            UPDATE crawl_queue_item
               SET status     = CASE WHEN attempts >= ? THEN 'FAILED' ELSE 'PENDING' END,
                   claimed_by = NULL,
                   claimed_at = NULL,
                   error_message = CASE WHEN attempts >= ?
                                        THEN 'Nach ' || attempts || ' Versuchen aufgegeben'
                                        ELSE error_message END,
                   completed_at  = CASE WHEN attempts >= ? THEN now() ELSE NULL END
             WHERE run_id = ?
               AND status = 'CLAIMED'
               AND claimed_at < now() - make_interval(secs => ?)
            """;

    private static final RowMapper<CrawlTarget> TARGET_MAPPER = (rs, row) ->
            new CrawlTarget(rs.getLong("id"), rs.getString("url"), rs.getInt("depth"));

    private final JdbcTemplate jdbc;

    public CrawlFrontierJdbcRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public int seed(long runId, Collection<String> urls, int depth) {
        return enqueue(runId, urls, depth, null);
    }

    public int enqueue(long runId, Collection<String> urls, int depth, String discoveredFrom) {
        if (urls.isEmpty()) {
            return 0;
        }
        List<Object[]> batch = urls.stream()
                .distinct()
                .map(url -> new Object[]{runId, url, depth, discoveredFrom})
                .toList();
        int[] inserted = jdbc.batchUpdate(ENQUEUE_SQL, batch);
        return java.util.Arrays.stream(inserted).map(rows -> Math.max(rows, 0)).sum();
    }

    public List<CrawlTarget> claimBatch(long runId, String owner, int batchSize) {
        return jdbc.query(CLAIM_SQL, TARGET_MAPPER, owner, runId, batchSize);
    }

    public void complete(Collection<CrawlOutcome> outcomes) {
        if (outcomes.isEmpty()) {
            return;
        }
        jdbc.batchUpdate(COMPLETE_SQL, outcomes.stream()
                .map(outcome -> new Object[]{outcome.status().name(), outcome.httpStatus(),
                        outcome.errorMessage(), outcome.id()})
                .toList());
    }

    public int reclaimStale(long runId, Duration olderThan, int maxAttempts) {
        return jdbc.update(RECLAIM_SQL, maxAttempts, maxAttempts, maxAttempts,
                runId, olderThan.toSeconds());
    }

    public int countPending(long runId) {
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM crawl_queue_item WHERE run_id = ? AND status = 'PENDING'",
                Integer.class, runId);
        return count == null ? 0 : count;
    }

    public Map<CrawlItemStatus, Integer> countByStatus(long runId) {
        Map<CrawlItemStatus, Integer> counts = new EnumMap<>(CrawlItemStatus.class);
        jdbc.query("SELECT status, count(*) AS n FROM crawl_queue_item WHERE run_id = ? GROUP BY status",
                rs -> {
                    counts.put(CrawlItemStatus.valueOf(rs.getString("status")), rs.getInt("n"));
                }, runId);
        return counts;
    }

    /** The URLs this run actually visited — the URL half of its coverage (spec 6.4). */
    public List<String> visitedUrls(long runId) {
        return jdbc.queryForList(
                "SELECT url FROM crawl_queue_item WHERE run_id = ? AND status = 'DONE' ORDER BY url",
                String.class, runId);
    }
}