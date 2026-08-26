package dev.hendrikhoemberg.webtesthelper.runner.persistence;

import dev.hendrikhoemberg.webtesthelper.model.RunStatus;
import dev.hendrikhoemberg.webtesthelper.runner.LastRun;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * The dashboard's per-site run projections. Raw SQL deliberately: the "one row per site, the
 * interesting one" query is a Postgres {@code DISTINCT ON}, which JPA cannot express, and its
 * {@code ORDER BY site_id, queued_at DESC, id DESC} is exactly the shape of
 * {@code ix_run_site_recent (site_id, queued_at DESC)}, so it reads as an index scan and this
 * needs no migration.
 */
@Repository
public class RunDashboardJdbcRepository {

    private static final String LAST_TERMINAL_PER_SITE_SQL = """
            SELECT DISTINCT ON (site_id)
                   site_id, id, status, finished_at, partial_coverage
              FROM run
             WHERE status IN ('COMPLETED', 'FAILED')
             ORDER BY site_id, queued_at DESC, id DESC
            """;

    private static final RowMapper<LastRun> LAST_RUN_MAPPER = (rs, row) -> new LastRun(
            rs.getLong("site_id"),
            rs.getLong("id"),
            RunStatus.valueOf(rs.getString("status")),
            rs.getTimestamp("finished_at").toInstant(),
            rs.getBoolean("partial_coverage"));

    private final JdbcTemplate jdbc;

    public RunDashboardJdbcRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** The newest terminal run of every site that has one, ordered outermost by site id. */
    public List<LastRun> lastTerminalPerSite() {
        return jdbc.query(LAST_TERMINAL_PER_SITE_SQL, LAST_RUN_MAPPER);
    }

    /** Queue depth plus running work, all scopes — the grid's "running" signal. */
    public int runsInFlight() {
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM run WHERE status IN ('QUEUED', 'RUNNING')", Integer.class);
        return count == null ? 0 : count;
    }
}
