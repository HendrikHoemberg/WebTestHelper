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
               SET pages_visited       = ?,
                   pages_failed        = ?,
                   findings_total      = ?,
                   findings_new        = ?,
                   findings_resolved   = ?,
                   covered_check_types = ?::jsonb,
                   covered_urls        = ?::jsonb,
                   partial_coverage    = ?,
                   budget_stop_reason  = ?,
                   soft404_status      = ?,
                   soft404_simhash     = ?,
                   soft404_text_length = ?
             WHERE id = ?
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
        try {
            jdbc.update(OUTCOME_SQL,
                    result.pagesVisited(),
                    result.pagesFailed(),
                    findingsTotal,
                    findingsNew,
                    findingsResolved,
                    objectMapper.writeValueAsString(coveredCheckTypes),
                    objectMapper.writeValueAsString(result.coveredUrls()),
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
}