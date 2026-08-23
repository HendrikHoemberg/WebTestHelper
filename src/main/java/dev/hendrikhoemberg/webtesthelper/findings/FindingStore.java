package dev.hendrikhoemberg.webtesthelper.findings;

import dev.hendrikhoemberg.webtesthelper.model.CheckType;
import dev.hendrikhoemberg.webtesthelper.model.Evidence;
import dev.hendrikhoemberg.webtesthelper.model.ObservedStatus;
import dev.hendrikhoemberg.webtesthelper.model.RunCoverage;
import dev.hendrikhoemberg.webtesthelper.model.Severity;
import dev.hendrikhoemberg.webtesthelper.model.TriageStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.sql.Array;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The findings tables. Every write is the spec's lifecycle or resolution rule written down
 * (plan 4, tasks 3.1–3.4); the diff is the same CASE the report shows, read back out of the
 * database rather than recomputed, so the two can never disagree.
 */
@Repository
public class FindingStore {

    private static final String UPSERT_SQL = """
            INSERT INTO finding (site_id, fingerprint, check_type, subject_key, location_key, severity,
                                 message_key, message_args, evidence, observed_status, triage_status,
                                 first_seen_run, last_seen_run, page_count, first_seen_at, last_seen_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, 'ACTIVE', 'UNTRIAGED', ?, ?, ?, ?, ?)
            ON CONFLICT (fingerprint) DO UPDATE SET
                observed_status = 'ACTIVE',
                severity        = excluded.severity,
                message_key     = excluded.message_key,
                message_args    = excluded.message_args,
                evidence        = excluded.evidence,
                last_seen_run   = excluded.last_seen_run,
                last_seen_at    = excluded.last_seen_at,
                page_count      = excluded.page_count,
                version         = finding.version + 1
            RETURNING id
            """;

    private static final String OCCURRENCE_SQL = """
            INSERT INTO finding_occurrence (finding_id, run_id, page_url, severity, message_key,
                                            message_args, evidence, observed_at)
            VALUES (?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?)
            ON CONFLICT (finding_id, run_id, page_url) DO UPDATE SET
                severity      = excluded.severity,
                message_key   = excluded.message_key,
                message_args  = excluded.message_args,
                evidence      = excluded.evidence,
                observed_at   = excluded.observed_at
            """;

    private static final String RECOUNT_SQL = """
            UPDATE finding f SET occurrence_count = o.n
              FROM (SELECT finding_id, count(*) AS n FROM finding_occurrence
                     WHERE finding_id = ANY(?) GROUP BY finding_id) o
             WHERE f.id = o.finding_id
            """;

    private static final String RESOLVE_SQL = """
            UPDATE finding
               SET observed_status = 'RESOLVED', resolved_at_run = ?, version = version + 1
             WHERE site_id = ?
               AND observed_status = 'ACTIVE'
               AND last_seen_run <> ?
               AND check_type = ANY(?)
               AND (location_key = ANY(?)
                    OR (location_key = '*' AND ?))
            """;

    private static final String ACCEPT_BASELINE_SQL = """
            UPDATE finding
               SET triage_status = 'ACKNOWLEDGED',
                   triage_reason = ?,
                   triaged_at = ?
             WHERE site_id = ?
               AND triage_status = 'UNTRIAGED'
               AND id IN (SELECT finding_id FROM finding_occurrence WHERE run_id = ?)
            """;

    private static final String DIFF_SQL = """
            SELECT f.*, CASE
                WHEN f.observed_status = 'RESOLVED' AND f.resolved_at_run = ? THEN 'FIXED'
                WHEN f.first_seen_run = ?                                     THEN 'NEW'
                WHEN f.resolved_at_run IS NOT NULL                            THEN 'REGRESSED'
                WHEN f.triage_status <> 'UNTRIAGED'                           THEN 'KNOWN'
                ELSE 'STILL_OPEN' END AS section
              FROM finding f
             WHERE f.site_id = ?
               AND (f.last_seen_run = ? OR (f.observed_status = 'RESOLVED' AND f.resolved_at_run = ?))
             ORDER BY CASE f.severity WHEN 'ERROR' THEN 0 WHEN 'WARN' THEN 1 ELSE 2 END,
                      f.check_type, f.location_key, f.subject_key
            """;

    private static final tools.jackson.core.type.TypeReference<List<String>> LIST_STRING =
            new tools.jackson.core.type.TypeReference<>() {
            };

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final RowMapper<Finding> findingRow;

    public FindingStore(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.findingRow = (rs, row) -> {
            long resolved = rs.getLong("resolved_at_run");
            Long resolvedAtRun = rs.wasNull() ? null : resolved;
            String evidenceJson = rs.getString("evidence");
            Evidence evidence = evidenceJson == null ? Evidence.NONE
                    : readJson(evidenceJson, Evidence.class);
            return new Finding(
                    rs.getLong("id"),
                    rs.getLong("site_id"),
                    rs.getString("fingerprint"),
                    CheckType.valueOf(rs.getString("check_type")),
                    rs.getString("subject_key"),
                    rs.getString("location_key"),
                    Severity.valueOf(rs.getString("severity")),
                    rs.getString("message_key"),
                    readJson(rs.getString("message_args"), LIST_STRING),
                    evidence,
                    ObservedStatus.valueOf(rs.getString("observed_status")),
                    TriageStatus.valueOf(rs.getString("triage_status")),
                    rs.getString("triage_reason"),
                    rs.getLong("first_seen_run"),
                    rs.getLong("last_seen_run"),
                    resolvedAtRun,
                    rs.getInt("occurrence_count"),
                    rs.getInt("page_count"),
                    rs.getTimestamp("first_seen_at").toInstant(),
                    rs.getTimestamp("last_seen_at").toInstant());
        };
    }

    /** Insert-or-revive each finding, returning its id in input order. */
    public List<Long> upsertAll(long siteId, long runId, List<MaterialisedFinding> findings,
            Instant observedAt) {
        Timestamp observed = ts(observedAt);
        List<Long> ids = new ArrayList<>(findings.size());
        for (MaterialisedFinding f : findings) {
            Long id = jdbc.queryForObject(UPSERT_SQL, Long.class,
                    siteId, f.fingerprint(), f.type().name(), f.subjectKey(), f.locationKey(),
                    f.severity().name(), f.messageKey(), toJson(f.messageArgs()), toJson(f.evidence()),
                    runId, runId, f.pageCount(), observed, observed);
            ids.add(id);
        }
        return ids;
    }

    /** Insert-or-replace every occurrence for the given findings (the row count that grows). */
    public void insertOccurrences(List<Long> findingIds, long runId,
            List<MaterialisedFinding> findings, Instant observedAt) {
        List<OccurrenceRow> rows = new ArrayList<>();
        for (int i = 0; i < findings.size(); i++) {
            MaterialisedFinding f = findings.get(i);
            for (FindingOccurrence o : f.occurrences()) {
                rows.add(new OccurrenceRow(findingIds.get(i), o));
            }
        }
        if (rows.isEmpty()) {
            return;
        }
        Timestamp observed = ts(observedAt);
        jdbc.batchUpdate(OCCURRENCE_SQL, new BatchPreparedStatementSetter() {
            @Override
            public void setValues(PreparedStatement ps, int i) throws SQLException {
                OccurrenceRow r = rows.get(i);
                ps.setLong(1, r.findingId());
                ps.setLong(2, runId);
                ps.setString(3, r.occurrence().pageUrl());
                ps.setString(4, r.occurrence().severity().name());
                ps.setString(5, r.occurrence().messageKey());
                ps.setString(6, toJson(r.occurrence().messageArgs()));
                ps.setString(7, toJson(r.occurrence().evidence()));
                ps.setTimestamp(8, observed);
            }

            @Override
            public int getBatchSize() {
                return rows.size();
            }
        });
    }

    /** Recount occurrences from the occurrence table (D26 — nothing accumulates). */
    public void recountOccurrences(List<Long> findingIds) {
        if (findingIds.isEmpty()) {
            return;
        }
        jdbc.execute((java.sql.Connection c) -> {
            Array ids = c.createArrayOf("bigint", findingIds.toArray());
            try (PreparedStatement ps = c.prepareStatement(RECOUNT_SQL)) {
                ps.setArray(1, ids);
                return ps.executeUpdate();
            } finally {
                ids.free();
            }
        });
    }

    /** Resolve the findings this run did not re-observe, scoped to what the run actually covered. */
    public int resolveOutsideRun(long siteId, long runId, RunCoverage coverage) {
        String[] checkTypes = coverage.checkTypes().stream().map(CheckType::name).toArray(String[]::new);
        String[] locationKeys = coverage.locationKeys().toArray(String[]::new);
        return jdbc.update(RESOLVE_SQL, runId, siteId, runId, checkTypes, locationKeys,
                coverage.complete());
    }

    /** Move every UNTRIAGED finding observed in the run to ACKNOWLEDGED. */
    public int acceptBaseline(long siteId, long runId, String reason, Instant now) {
        return jdbc.update(ACCEPT_BASELINE_SQL, reason, ts(now), siteId, runId);
    }

    /** Read the run's diff straight out of the database. */
    public RunDiff diffOf(long siteId, long runId) {
        List<SectionedFinding> rows = jdbc.query(DIFF_SQL, (rs, rn) -> {
            Finding f = findingRow.mapRow(rs, rn);
            return new SectionedFinding(f, ReportSection.valueOf(rs.getString("section")));
        }, runId, runId, siteId, runId, runId);

        Map<ReportSection, List<Finding>> bySection = new LinkedHashMap<>();
        for (ReportSection section : ReportSection.values()) {
            bySection.put(section, new ArrayList<>());
        }
        for (SectionedFinding r : rows) {
            bySection.get(r.section()).add(r.finding());
        }
        return new RunDiff(runId, bySection);
    }

    private Timestamp ts(Instant instant) {
        return Timestamp.from(instant.truncatedTo(ChronoUnit.MICROS));
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JacksonException e) {
            throw new IllegalStateException("Finding ist nicht als JSON serialisierbar", e);
        }
    }

    private <T> T readJson(String json, Class<T> type) {
        try {
            return objectMapper.readValue(json, type);
        } catch (JacksonException e) {
            throw new IllegalStateException("Finding jsonb ist nicht lesbar: " + json, e);
        }
    }

    private <T> T readJson(String json, tools.jackson.core.type.TypeReference<T> type) {
        try {
            return objectMapper.readValue(json, type);
        } catch (JacksonException e) {
            throw new IllegalStateException("Finding jsonb ist nicht lesbar: " + json, e);
        }
    }

    private record OccurrenceRow(long findingId, FindingOccurrence occurrence) {
    }

    private record SectionedFinding(Finding finding, ReportSection section) {
    }
}
