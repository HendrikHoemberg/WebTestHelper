package dev.hendrikhoemberg.webtesthelper.crawler.persistence;

import dev.hendrikhoemberg.webtesthelper.crawler.VerifierProperties;
import dev.hendrikhoemberg.webtesthelper.model.UrlStatus;
import dev.hendrikhoemberg.webtesthelper.model.UrlVerification;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Repository
public class ExternalUrlCacheJdbcRepository {

    private static final RowMapper<UrlVerification> ROW_MAPPER = (rs, row) ->
            new UrlVerification(
                    rs.getString("url"),
                    UrlStatus.valueOf(rs.getString("status")),
                    rs.getInt("http_status"),
                    rs.getString("content_type"),
                    rs.getLong("content_length"),
                    rs.getString("body_prefix"),
                    rs.getString("failure_text"),
                    rs.getTimestamp("checked_at").toInstant());

    private static final String FRESH_SQL = """
            SELECT url, status, http_status, content_type, content_length,
                   body_prefix, failure_text, checked_at
              FROM external_url_check
             WHERE url = ANY(?)
               AND ((status =  'OK' AND checked_at > ?)
                 OR (status <> 'OK' AND checked_at > ?))
            """;

    private static final String UPSERT_SQL = """
            INSERT INTO external_url_check (url, status, http_status, content_type, content_length,
                                            body_prefix, failure_text, checked_at, dependent_site_ids)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb)
            ON CONFLICT (url) DO UPDATE SET
                status = excluded.status, http_status = excluded.http_status,
                content_type = excluded.content_type, content_length = excluded.content_length,
                body_prefix = coalesce(excluded.body_prefix, external_url_check.body_prefix),
                failure_text = excluded.failure_text, checked_at = excluded.checked_at,
                dependent_site_ids = (SELECT coalesce(jsonb_agg(DISTINCT value), '[]'::jsonb)
                                        FROM jsonb_array_elements(
                                             external_url_check.dependent_site_ids
                                             || excluded.dependent_site_ids) AS value)
            """;

    private final JdbcTemplate jdbc;
    private final VerifierProperties properties;

    public ExternalUrlCacheJdbcRepository(JdbcTemplate jdbc, VerifierProperties properties) {
        this.jdbc = jdbc;
        this.properties = properties;
    }

    public Map<String, UrlVerification> fresh(Collection<String> urls, Instant now) {
        if (urls.isEmpty()) {
            return Map.of();
        }
        String[] urlArray = urls.toArray(String[]::new);
        Timestamp successCutoff = Timestamp.from(now.minus(properties.successTtl()));
        Timestamp failureCutoff = Timestamp.from(now.minus(properties.failureTtl()));
        List<UrlVerification> rows = jdbc.query(FRESH_SQL, ROW_MAPPER,
                urlArray, successCutoff, failureCutoff);
        return rows.stream().collect(Collectors.toMap(UrlVerification::url, v -> v));
    }

    public void store(Collection<UrlVerification> results, long siteId) {
        if (results.isEmpty()) {
            return;
        }
        String siteIdJson = "[%d]".formatted(siteId);
        results.stream().map(r -> new Object[]{
                r.url(), r.status().name(), r.httpStatus(), r.contentType(),
                r.contentLength(), r.bodyPrefix(), r.failureText(),
                Timestamp.from(r.checkedAt()), siteIdJson
        }).toList().forEach(args ->
                jdbc.update(UPSERT_SQL, args));
    }
}
