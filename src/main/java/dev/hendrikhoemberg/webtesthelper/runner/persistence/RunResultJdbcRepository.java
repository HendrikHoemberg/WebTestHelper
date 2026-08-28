package dev.hendrikhoemberg.webtesthelper.runner.persistence;

import dev.hendrikhoemberg.webtesthelper.crawler.CrawlResult;
import dev.hendrikhoemberg.webtesthelper.model.SoftNotFoundProbe;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

/**
 * Writes a run's progress and its crawl outcome — including the two jsonb coverage columns —
 * straight onto the run row. Coverage is load-bearing for resolution (spec 6.4), so the two
 * lists are serialised with Jackson rather than by string splicing.
 */
@Repository
public class RunResultJdbcRepository {

    private static final String PROGRESS_SQL =
            "UPDATE run SET pages_visited = ?, pages_failed = ? WHERE id = ?";

    private static final String OUTCOME_SQL = """
            UPDATE run
               SET pages_visited                   = ?,
                   pages_failed                    = ?,
                   findings_total                  = ?,
                   findings_new                    = ?,
                   findings_resolved               = ?,
                   covered_check_types             = ?::jsonb,
                   covered_urls                    = ?::jsonb,
                   covered_interaction_urls        = ?::jsonb,
                   covered_interaction_check_types = ?::jsonb,
                   covered_journey_ids             = ?::jsonb,
                   partial_coverage                = ?,
                   budget_stop_reason              = ?,
                   soft404_status                  = ?,
                   soft404_simhash                 = ?,
                   soft404_text_length             = ?
             WHERE id = ?
            """;

    private static final String RETENTION_DELETE_SQL = """
            SELECT id FROM (
                SELECT id, row_number() OVER (PARTITION BY site_id ORDER BY queued_at DESC) AS rn
                  FROM run
                 WHERE status IN ('COMPLETED', 'FAILED', 'CANCELLED')
            ) ranked
             WHERE rn > ?
            """;

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public RunResultJdbcRepository(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    public void updateProgress(long runId, int visited, int failed) {
        jdbc.update(PROGRESS_SQL, visited, failed, runId);
    }

    public void saveCrawlOutcome(long runId, CrawlResult result, List<String> coveredCheckTypes,
            SoftNotFoundProbe probe, int findingsTotal, int findingsNew, int findingsResolved) {
        saveCrawlOutcome(runId, result, coveredCheckTypes, List.of(), List.of(), List.of(), probe,
                findingsTotal, findingsNew, findingsResolved);
    }

    public void saveCrawlOutcome(long runId, CrawlResult result, List<String> coveredCheckTypes,
            List<String> coveredInteractionCheckTypes, List<String> coveredInteractionUrls,
            SoftNotFoundProbe probe, int findingsTotal, int findingsNew, int findingsResolved) {
        saveCrawlOutcome(runId, result, coveredCheckTypes, coveredInteractionCheckTypes, coveredInteractionUrls,
                List.of(), probe, findingsTotal, findingsNew, findingsResolved);
    }

    public void saveCrawlOutcome(long runId, CrawlResult result, List<String> coveredCheckTypes,
            List<String> coveredInteractionCheckTypes, List<String> coveredInteractionUrls,
            List<Long> coveredJourneyIds,
            SoftNotFoundProbe probe, int findingsTotal, int findingsNew, int findingsResolved) {
        try {
            jdbc.update(OUTCOME_SQL,
                    result.pagesVisited(),
                    result.pagesFailed(),
                    findingsTotal,
                    findingsNew,
                    findingsResolved,
                    objectMapper.writeValueAsString(coveredCheckTypes),
                    objectMapper.writeValueAsString(result.coveredUrls()),
                    objectMapper.writeValueAsString(coveredInteractionUrls != null ? coveredInteractionUrls : List.of()),
                    objectMapper.writeValueAsString(coveredInteractionCheckTypes != null ? coveredInteractionCheckTypes : List.of()),
                    objectMapper.writeValueAsString(coveredJourneyIds != null ? coveredJourneyIds : List.of()),
                    result.partialCoverage(),
                    result.budgetStopReason(),
                    probe.httpStatus(),
                    probe.simhash(),
                    probe.textLength(),
                    runId);
        } catch (JacksonException e) {
            // A list of strings that will not serialise is a bug, not a condition.
            throw new IllegalStateException("Lauf-" + runId + "-Ergebnis nicht als JSON serialisierbar",
                    e);
        }
    }

    /**
     * The runs whose artifact directories are due for removal: every terminal run per site past
     * the newest {@code keepPerSite}. Only terminal runs are ranked, so a still-QUEUED or
     * RUNNING run is never a candidate — deleting a run currently writing its screenshots would
     * corrupt the report it is producing.
     */
    public List<Long> findExpiredArtifactRunIds(int keepPerSite) {
        return jdbc.queryForList(RETENTION_DELETE_SQL, Long.class, keepPerSite);
    }
}