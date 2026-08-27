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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
                -- The revival IS the regression, and it is news in this run only. Every SET
                -- expression sees the pre-update row, so this reads the status the finding had
                -- when the run found it again. resolved_at_run stays untouched as history.
                regressed_at_run = CASE WHEN finding.observed_status = 'RESOLVED'
                                        THEN excluded.last_seen_run
                                        ELSE finding.regressed_at_run END,
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
                    -- Site-wide (spec 6.2): only a run that crawled the whole site and finished
                    -- can disprove "on 312 pages". A pulse over a dozen pinned pages cannot.
                    OR (location_key = '*' AND ?))
            """;

    private static final String RESOLVE_INTERACTION_SQL = """
            UPDATE finding
               SET observed_status = 'RESOLVED', resolved_at_run = ?, version = version + 1
             WHERE site_id = ?
               AND observed_status = 'ACTIVE'
               AND last_seen_run <> ?
               AND check_type = ANY(?)
               AND location_key = ANY(?)
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

    private static final String TRIAGE_SQL = """
            UPDATE finding
               SET triage_status = ?,
                   triage_reason = ?,
                   muted_until = ?,
                   triaged_by = ?,
                   triaged_at = ?,
                   -- A human's decision is now the reason this finding is quiet, so it can no longer
                   -- belong to a rule: leaving muted_by_rule_id set would let a later rule deletion
                   -- reset that decision to UNTRIAGED, which is exactly what D51 forbids.
                   muted_by_rule_id = NULL,
                   -- Cleared only when a fresh mute starts (?=TRUE). An un-triage leaves the stamp
                   -- alone — that column is the sweep's record, and a human is not an expiry.
                   mute_expired_at = CASE WHEN ? THEN NULL ELSE mute_expired_at END,
                   version = version + 1
             WHERE site_id = ?
               AND id = ANY(?)
            """;

    private static final String APPLY_RULE_SQL = """
            UPDATE finding SET triage_status = 'MUTED', triage_reason = ?, triaged_by = ?, triaged_at = ?,
                               muted_until = ?, muted_by_rule_id = ?, mute_expired_at = NULL,
                               version = version + 1
             WHERE (? IS NULL OR site_id = ?)
               AND triage_status = 'UNTRIAGED'
               AND (? IS NULL OR check_type = ?)
               AND (? IS NULL OR lower(subject_key)  LIKE ? ESCAPE '\\')
               AND (? IS NULL OR lower(location_key) LIKE ? ESCAPE '\\')
               AND (? IS NULL OR last_seen_run = ?)
            """;

    private static final String UNMUTE_RULE_SQL = """
            UPDATE finding
               SET triage_status = 'UNTRIAGED',
                   muted_until = NULL,
                   muted_by_rule_id = NULL,
                   -- Same SET list as EXPIRE_MUTES_SQL, and for the same reason (D50): triage_reason,
                   -- triaged_by and triaged_at survive so the screen can print "Stummschaltung
                   -- abgelaufen am … · Damalige Begründung: …" instead of an empty line.
                   mute_expired_at = ?,
                   version = version + 1
             WHERE muted_by_rule_id = ?
               -- D51: only rows the rule itself is still silencing. A human who re-triaged a
               -- rule-muted finding owns it now, and deleting the rule must not touch them.
               AND triage_status = 'MUTED'
            """;

    private static final String COUNT_MATCHING_SQL = """
            SELECT COUNT(*) FROM finding
             WHERE (? IS NULL OR site_id = ?)
               AND (? IS NULL OR check_type = ?)
               AND (? IS NULL OR lower(subject_key) LIKE ? ESCAPE '\\')
               AND (? IS NULL OR lower(location_key) LIKE ? ESCAPE '\\')
            """;

    private static final String EXPIRE_MUTES_SQL = """
            UPDATE finding
               SET triage_status = 'UNTRIAGED',
                   muted_until = NULL,
                   muted_by_rule_id = NULL,
                   mute_expired_at = ?,
                   -- triage_reason and triaged_at are conspicuously absent on purpose (D50):
                   -- an expired mute keeps its reason and triaged_at so un-muting is visible
                   -- next to a live occurrence count.
                   version = version + 1
             WHERE triage_status = 'MUTED'
               AND muted_until <= ?
            """;

    private static final String SILENCING_IN_CLAUSE = TriageStatus.SILENCING.stream()
            .map(s -> "'" + s.name() + "'")
            .sorted()
            .collect(java.util.stream.Collectors.joining(", "));

    private static final String OPEN_COUNTS_SQL = String.format("""
            SELECT site_id, severity,
                   count(*)                                               AS open_count,
                   count(*) FILTER (WHERE triage_status = 'UNTRIAGED')     AS untriaged_count
              FROM finding
             WHERE observed_status = 'ACTIVE'
               AND triage_status NOT IN (%s)
             GROUP BY site_id, severity
            """, SILENCING_IN_CLAUSE);

    private static final String DIFF_SQL = String.format("""
            SELECT f.*, CASE
                WHEN f.observed_status = 'RESOLVED' AND f.resolved_at_run = ? THEN 'FIXED'
                -- D47: a mute that stops applying the moment the thing flaps is not a mute. Placed above
                -- NEW and REGRESSED, below FIXED. The IN list is built from TriageStatus.SILENCING so the
                -- enum and this string cannot drift apart.
                WHEN f.triage_status IN (%s)                                  THEN 'KNOWN'
                WHEN f.first_seen_run = ?                                     THEN 'NEW'
                WHEN f.regressed_at_run = ?                                   THEN 'REGRESSED'
                WHEN f.triage_status <> 'UNTRIAGED'                           THEN 'KNOWN'
                ELSE 'STILL_OPEN' END AS section
              FROM finding f
             WHERE f.site_id = ?
               AND (f.last_seen_run = ? OR (f.observed_status = 'RESOLVED' AND f.resolved_at_run = ?))
             ORDER BY CASE f.severity WHEN 'ERROR' THEN 0 WHEN 'WARN' THEN 1 ELSE 2 END,
                      f.check_type, f.location_key, f.subject_key
            """, SILENCING_IN_CLAUSE);

    private static final String COUNT_SQL = """
            SELECT count(*) FROM finding
             WHERE site_id = ?
               AND (?::varchar[] IS NULL OR severity = ANY(?))
               AND (?::varchar[] IS NULL OR triage_status = ANY(?))
               AND (?::varchar IS NULL OR observed_status = ?)
               AND (?::varchar[] IS NULL OR check_type = ANY(?))
            """;

    private static final String SEARCH_SQL = """
            SELECT * FROM finding
             WHERE site_id = ?
               AND (?::varchar[] IS NULL OR severity = ANY(?))
               AND (?::varchar[] IS NULL OR triage_status = ANY(?))
               AND (?::varchar IS NULL OR observed_status = ?)
               AND (?::varchar[] IS NULL OR check_type = ANY(?))
             ORDER BY CASE severity WHEN 'ERROR' THEN 0 WHEN 'WARN' THEN 1 ELSE 2 END,
                      last_seen_at DESC,
                      id ASC
             LIMIT ? OFFSET ?
            """;

    private static final String BY_ID_SQL = """
            SELECT * FROM finding WHERE id = ?
            """;

    private static final String OCCURRENCES_OF_LAST_RUN_SQL = """
            SELECT o.page_url, o.severity, o.message_key, o.message_args, o.evidence
              FROM finding_occurrence o
              JOIN finding f ON f.id = o.finding_id AND f.last_seen_run = o.run_id
             WHERE o.finding_id = ?
             ORDER BY o.page_url ASC NULLS FIRST
             LIMIT ?
            """;

    private static final tools.jackson.core.type.TypeReference<List<String>> LIST_STRING =
            new tools.jackson.core.type.TypeReference<>() {
            };

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final RowMapper<Finding> findingRow;
    private final RowMapper<FindingOccurrence> occurrenceRow;

    public FindingStore(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.occurrenceRow = (rs, row) -> {
            String evidenceJson = rs.getString("evidence");
            Evidence evidence = evidenceJson == null ? Evidence.NONE
                    : readJson(evidenceJson, Evidence.class);
            return new FindingOccurrence(
                    rs.getString("page_url"),
                    Severity.valueOf(rs.getString("severity")),
                    rs.getString("message_key"),
                    readJson(rs.getString("message_args"), LIST_STRING),
                    evidence);
        };
        this.findingRow = (rs, row) -> {
            long resolved = rs.getLong("resolved_at_run");
            Long resolvedAtRun = rs.wasNull() ? null : resolved;
            long regressed = rs.getLong("regressed_at_run");
            Long regressedAtRun = rs.wasNull() ? null : regressed;
            Timestamp mutedUntilTs = rs.getTimestamp("muted_until");
            Instant mutedUntil = mutedUntilTs == null ? null : mutedUntilTs.toInstant();
            Timestamp muteExpiredAtTs = rs.getTimestamp("mute_expired_at");
            Instant muteExpiredAt = muteExpiredAtTs == null ? null : muteExpiredAtTs.toInstant();
            Long mutedByRuleId = getLongOrNull(rs, "muted_by_rule_id");
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
                    rs.getString("triaged_by"),
                    mutedUntil,
                    muteExpiredAt,
                    mutedByRuleId,
                    rs.getLong("first_seen_run"),
                    rs.getLong("last_seen_run"),
                    resolvedAtRun,
                    regressedAtRun,
                    rs.getInt("occurrence_count"),
                    rs.getInt("page_count"),
                    rs.getTimestamp("first_seen_at").toInstant(),
                    rs.getTimestamp("last_seen_at").toInstant());
        };
    }

    public java.util.Optional<Finding> byId(long id) {
        List<Finding> list = jdbc.query(BY_ID_SQL, findingRow, id);
        return list.isEmpty() ? java.util.Optional.empty() : java.util.Optional.of(list.get(0));
    }

    public long count(FindingQuery query) {
        return jdbc.execute((java.sql.Connection conn) -> {
            List<Array> arrays = new ArrayList<>();
            try (PreparedStatement ps = conn.prepareStatement(COUNT_SQL)) {
                bindFilterParams(ps, conn, query, arrays);
                try (var rs = ps.executeQuery()) {
                    if (rs.next()) {
                        return rs.getLong(1);
                    }
                    return 0L;
                }
            } finally {
                for (Array arr : arrays) {
                    try {
                        arr.free();
                    } catch (SQLException ignored) {
                    }
                }
            }
        });
    }

    public FindingPage search(FindingQuery query) {
        long total = count(query);
        if (total == 0) {
            return new FindingPage(List.of(), query.page(), query.size(), 0);
        }
        List<Finding> findings = jdbc.execute((java.sql.Connection conn) -> {
            List<Array> arrays = new ArrayList<>();
            try (PreparedStatement ps = conn.prepareStatement(SEARCH_SQL)) {
                bindFilterParams(ps, conn, query, arrays);
                ps.setInt(10, query.size());
                ps.setInt(11, (query.page() - 1) * query.size());
                try (var rs = ps.executeQuery()) {
                    List<Finding> list = new ArrayList<>();
                    int rowNum = 0;
                    while (rs.next()) {
                        list.add(findingRow.mapRow(rs, rowNum++));
                    }
                    return list;
                }
            } finally {
                for (Array arr : arrays) {
                    try {
                        arr.free();
                    } catch (SQLException ignored) {
                    }
                }
            }
        });
        return new FindingPage(findings, query.page(), query.size(), total);
    }

    private void bindFilterParams(PreparedStatement ps, java.sql.Connection conn, FindingQuery query, List<Array> arraysToFree) throws SQLException {
        ps.setLong(1, query.siteId());

        String[] severities = query.severities().isEmpty() ? null
                : query.severities().stream().map(Severity::name).toArray(String[]::new);
        if (severities == null) {
            ps.setNull(2, java.sql.Types.ARRAY);
            ps.setNull(3, java.sql.Types.ARRAY);
        } else {
            Array arr = conn.createArrayOf("varchar", severities);
            arraysToFree.add(arr);
            ps.setArray(2, arr);
            ps.setArray(3, arr);
        }

        String[] triageStatuses = query.triageStatuses().isEmpty() ? null
                : query.triageStatuses().stream().map(TriageStatus::name).toArray(String[]::new);
        if (triageStatuses == null) {
            ps.setNull(4, java.sql.Types.ARRAY);
            ps.setNull(5, java.sql.Types.ARRAY);
        } else {
            Array arr = conn.createArrayOf("varchar", triageStatuses);
            arraysToFree.add(arr);
            ps.setArray(4, arr);
            ps.setArray(5, arr);
        }

        if (query.observed() == null) {
            ps.setNull(6, java.sql.Types.VARCHAR);
            ps.setNull(7, java.sql.Types.VARCHAR);
        } else {
            ps.setString(6, query.observed().name());
            ps.setString(7, query.observed().name());
        }

        String[] checkTypes = query.checkTypes().isEmpty() ? null
                : query.checkTypes().stream().map(CheckType::name).toArray(String[]::new);
        if (checkTypes == null) {
            ps.setNull(8, java.sql.Types.ARRAY);
            ps.setNull(9, java.sql.Types.ARRAY);
        } else {
            Array arr = conn.createArrayOf("varchar", checkTypes);
            arraysToFree.add(arr);
            ps.setArray(8, arr);
            ps.setArray(9, arr);
        }
    }

    public List<FindingOccurrence> occurrencesOfLastRun(long findingId, int limit) {
        return jdbc.query(OCCURRENCES_OF_LAST_RUN_SQL, occurrenceRow, findingId, limit);
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
        int resolved = 0;

        // Every interaction type the run was allowed to drive leaves the crawl-scoped statement,
        // driven or not (D74/D79). A check that threw, timed out, or never found a reachable
        // target must resolve nothing — but its type is still in the run's covered check types,
        // so filtering on what was *driven* here would silently resolve it against every crawled
        // page, which is precisely the false resolution spec 6.4 exists to prevent.
        String[] standardCheckTypes = coverage.checkTypes().stream()
                .filter(t -> !coverage.interactionCheckTypes().contains(t))
                .map(CheckType::name)
                .toArray(String[]::new);
        if (standardCheckTypes.length > 0) {
            String[] locationKeys = coverage.locationKeys().toArray(String[]::new);
            resolved += jdbc.update(RESOLVE_SQL, runId, siteId, runId, standardCheckTypes, locationKeys,
                    coverage.wholeSite());
        }

        // One statement per type rather than one over both arrays: two types driven on two
        // different pages would otherwise form a cartesian product and let each resolve the
        // other's page (D74).
        for (Map.Entry<CheckType, Set<String>> entry : coverage.interactionLocationKeys().entrySet()) {
            if (entry.getValue().isEmpty()) {
                continue;
            }
            resolved += jdbc.update(RESOLVE_INTERACTION_SQL, runId, siteId, runId,
                    new String[] {entry.getKey().name()},
                    entry.getValue().toArray(String[]::new));
        }
        return resolved;
    }

    /** Move every UNTRIAGED finding observed in the run to ACKNOWLEDGED. */
    public int acceptBaseline(long siteId, long runId, String reason, Instant now) {
        return jdbc.update(ACCEPT_BASELINE_SQL, reason, ts(now), siteId, runId);
    }

    /** Apply a triage action to the specified findings within the site scope. */
    public int triage(long siteId, List<Long> ids, TriageAction action, String actor, Instant now) {
        if (ids == null || ids.isEmpty()) {
            return 0;
        }
        boolean isUntriaged = action.target() == TriageStatus.UNTRIAGED;
        String triageStatus = action.target().name();
        String triageReason = isUntriaged ? null : action.reason();
        Timestamp mutedUntil = (isUntriaged || action.mutedUntil() == null) ? null : ts(action.mutedUntil());
        String triagedBy = isUntriaged ? null : actor;
        Timestamp triagedAt = isUntriaged ? null : ts(now);

        boolean startsFreshMute = action.target() == TriageStatus.MUTED;

        return jdbc.execute((java.sql.Connection c) -> {
            Array idArray = c.createArrayOf("bigint", ids.toArray());
            try (PreparedStatement ps = c.prepareStatement(TRIAGE_SQL)) {
                ps.setString(1, triageStatus);
                ps.setString(2, triageReason);
                ps.setTimestamp(3, mutedUntil);
                ps.setString(4, triagedBy);
                ps.setTimestamp(5, triagedAt);
                ps.setBoolean(6, startsFreshMute);
                ps.setLong(7, siteId);
                ps.setArray(8, idArray);
                return ps.executeUpdate();
            } finally {
                idArray.free();
            }
        });
    }

    /**
     * Applies a mute rule to UNTRIAGED findings matching the rule's criteria.
     * If siteId is non-null, matches only findings for that site.
     * If runId is non-null, matches only findings seen in that run (applyToRun); otherwise applies retroactively.
     */
    public int applyMuteRule(Long siteId, Long runId, MuteRule rule, Instant now) {
        String subjectLike = MutePattern.isBlank(rule.subjectPattern()) ? null
                : MutePattern.toLikePattern(rule.subjectPattern()).toLowerCase(java.util.Locale.ROOT);
        String locationLike = MutePattern.isBlank(rule.locationPattern()) ? null
                : MutePattern.toLikePattern(rule.locationPattern()).toLowerCase(java.util.Locale.ROOT);
        String checkType = rule.checkType() == null ? null : rule.checkType().name();

        return jdbc.update(APPLY_RULE_SQL, ps -> {
            ps.setString(1, rule.reason());
            ps.setString(2, rule.createdBy());
            ps.setTimestamp(3, ts(now));
            ps.setTimestamp(4, ts(rule.expiresAt()));
            ps.setLong(5, rule.id());

            if (siteId == null) {
                ps.setNull(6, java.sql.Types.BIGINT);
                ps.setNull(7, java.sql.Types.BIGINT);
            } else {
                ps.setLong(6, siteId);
                ps.setLong(7, siteId);
            }

            if (checkType == null) {
                ps.setNull(8, java.sql.Types.VARCHAR);
                ps.setNull(9, java.sql.Types.VARCHAR);
            } else {
                ps.setString(8, checkType);
                ps.setString(9, checkType);
            }

            if (subjectLike == null) {
                ps.setNull(10, java.sql.Types.VARCHAR);
                ps.setNull(11, java.sql.Types.VARCHAR);
            } else {
                ps.setString(10, subjectLike);
                ps.setString(11, subjectLike);
            }

            if (locationLike == null) {
                ps.setNull(12, java.sql.Types.VARCHAR);
                ps.setNull(13, java.sql.Types.VARCHAR);
            } else {
                ps.setString(12, locationLike);
                ps.setString(13, locationLike);
            }

            if (runId == null) {
                ps.setNull(14, java.sql.Types.BIGINT);
                ps.setNull(15, java.sql.Types.BIGINT);
            } else {
                ps.setLong(14, runId);
                ps.setLong(15, runId);
            }
        });
    }

    /**
     * Unmutes findings previously muted by the given ruleId, returning them to UNTRIAGED.
     */
    public int unmuteRule(long ruleId, Instant now) {
        return jdbc.update(UNMUTE_RULE_SQL, ps -> {
            ps.setTimestamp(1, ts(now));
            ps.setLong(2, ruleId);
        });
    }

    /**
     * Expire mutes whose muted_until has passed.
     * D50: Keeps triage_reason and triaged_at intact on purpose so the old reason remains visible.
     */
    public int expireMutes(Instant now) {
        return jdbc.update(EXPIRE_MUTES_SQL, ps -> {
            ps.setTimestamp(1, ts(now));
            ps.setTimestamp(2, ts(now));
        });
    }

    /**
     * Counts currently existing findings matching the given criteria (for rule preview).
     * If all criteria (checkType, subjectPattern, locationPattern) are empty/blank, returns 0.
     */
    public int countMatching(Long siteId, CheckType checkType, String subjectPattern, String locationPattern) {
        if (checkType == null && MutePattern.isBlank(subjectPattern) && MutePattern.isBlank(locationPattern)) {
            return 0;
        }
        String subjectLike = MutePattern.isBlank(subjectPattern) ? null
                : MutePattern.toLikePattern(subjectPattern).toLowerCase(java.util.Locale.ROOT);
        String locationLike = MutePattern.isBlank(locationPattern) ? null
                : MutePattern.toLikePattern(locationPattern).toLowerCase(java.util.Locale.ROOT);
        String checkTypeName = checkType == null ? null : checkType.name();

        return jdbc.query(COUNT_MATCHING_SQL, ps -> {
            if (siteId == null) {
                ps.setNull(1, java.sql.Types.BIGINT);
                ps.setNull(2, java.sql.Types.BIGINT);
            } else {
                ps.setLong(1, siteId);
                ps.setLong(2, siteId);
            }

            if (checkTypeName == null) {
                ps.setNull(3, java.sql.Types.VARCHAR);
                ps.setNull(4, java.sql.Types.VARCHAR);
            } else {
                ps.setString(3, checkTypeName);
                ps.setString(4, checkTypeName);
            }

            if (subjectLike == null) {
                ps.setNull(5, java.sql.Types.VARCHAR);
                ps.setNull(6, java.sql.Types.VARCHAR);
            } else {
                ps.setString(5, subjectLike);
                ps.setString(6, subjectLike);
            }

            if (locationLike == null) {
                ps.setNull(7, java.sql.Types.VARCHAR);
                ps.setNull(8, java.sql.Types.VARCHAR);
            } else {
                ps.setString(7, locationLike);
                ps.setString(8, locationLike);
            }
        }, rs -> {
            rs.next();
            return rs.getInt(1);
        });
    }


    /** Open findings per site, per severity, in one grouped statement. Sites with no open
     * finding are absent from the map rather than present with zeros. */
    public Map<Long, OpenFindingCounts> openCountsBySite() {
        return jdbc.query(OPEN_COUNTS_SQL, rs -> {
            Map<Long, OpenFindingCounts> bySite = new LinkedHashMap<>();
            while (rs.next()) {
                long siteId = rs.getLong("site_id");
                int openCount = rs.getInt("open_count");
                int untriaged = rs.getInt("untriaged_count");
                OpenFindingCounts current = bySite.getOrDefault(siteId, OpenFindingCounts.none());
                bySite.put(siteId, switch (Severity.valueOf(rs.getString("severity"))) {
                    case ERROR -> new OpenFindingCounts(
                            openCount, current.warnings(), current.infos(), current.untriaged() + untriaged);
                    case WARN -> new OpenFindingCounts(
                            current.errors(), openCount, current.infos(), current.untriaged() + untriaged);
                    case INFO -> new OpenFindingCounts(
                            current.errors(), current.warnings(), openCount, current.untriaged() + untriaged);
                });
            }
            return bySite;
        });
    }

    private static Long getLongOrNull(java.sql.ResultSet rs, String column) {
        try {
            long val = rs.getLong(column);
            return rs.wasNull() ? null : val;
        } catch (SQLException e) {
            return null;
        }
    }

    /** Read the run's diff straight out of the database. */
    public RunDiff diffOf(long siteId, long runId) {
        List<SectionedFinding> rows = jdbc.query(DIFF_SQL, (rs, rn) -> {
            Finding f = findingRow.mapRow(rs, rn);
            return new SectionedFinding(f, ReportSection.valueOf(rs.getString("section")));
        }, runId, runId, runId, siteId, runId, runId);

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
