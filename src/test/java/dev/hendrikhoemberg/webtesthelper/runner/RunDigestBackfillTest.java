package dev.hendrikhoemberg.webtesthelper.runner;

import dev.hendrikhoemberg.webtesthelper.support.AbstractPostgresTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V17 added {@code digest_sent_at} as a plain nullable column, which leaves every run that already
 * existed looking undigested. On the first cycle after the upgrade the whole run history closes as
 * one window and is mailed as current news. V18 is the one-shot repair; this test is what keeps it
 * in the tree.
 */
class RunDigestBackfillTest extends AbstractPostgresTest {

    private static final String MIGRATION = "db/migration/V18__backfill_digest_sent_at.sql";

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void theBackfillStampsRunsThatFinishedBeforeTheDigestExisted() throws Exception {
        jdbc.update("DELETE FROM run");
        jdbc.update("DELETE FROM site");
        Long siteId = jdbc.queryForObject("""
                INSERT INTO site (name, base_url, max_pages, max_depth, max_duration_seconds,
                                  include_patterns, exclude_patterns, respect_robots, enabled)
                VALUES ('Altbestand', 'https://alt.example.com/', 100, 3, 600,
                        '[]'::jsonb, '[]'::jsonb, TRUE, TRUE)
                RETURNING id""", Long.class);

        insertRun(siteId, "COMPLETED", "now() - interval '40 days'");
        insertRun(siteId, "FAILED", "now() - interval '9 days'");
        insertRun(siteId, "CANCELLED", "now() - interval '3 days'");

        jdbc.execute(readMigration());

        assertThat(undigested()).describedAs("terminal runs predating the digest must be stamped")
                .isEmpty();
    }

    @Test
    void theBackfillLeavesARunThatAlreadyCarriesAStamp() throws Exception {
        jdbc.update("DELETE FROM run");
        jdbc.update("DELETE FROM site");
        Long siteId = jdbc.queryForObject("""
                INSERT INTO site (name, base_url, max_pages, max_depth, max_duration_seconds,
                                  include_patterns, exclude_patterns, respect_robots, enabled)
                VALUES ('Altbestand', 'https://alt.example.com/', 100, 3, 600,
                        '[]'::jsonb, '[]'::jsonb, TRUE, TRUE)
                RETURNING id""", Long.class);

        insertRun(siteId, "COMPLETED", "now() - interval '2 days'");
        jdbc.update("UPDATE run SET digest_sent_at = TIMESTAMPTZ '2026-01-01 00:00:00+00'");

        jdbc.execute(readMigration());

        List<Map<String, Object>> rows = jdbc.queryForList("SELECT digest_sent_at FROM run");
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).get("digest_sent_at").toString()).startsWith("2026-01-01");
    }

    private void insertRun(Long siteId, String status, String finishedExpr) {
        jdbc.update("""
                INSERT INTO run (site_id, trigger_type, scope, status, queued_at, started_at, finished_at)
                VALUES (?, 'SCHEDULED', 'FULL', ?, %s, %s, %s)""".formatted(
                        finishedExpr, finishedExpr, finishedExpr),
                siteId, status);
    }

    private List<Map<String, Object>> undigested() {
        return jdbc.queryForList(
                "SELECT id FROM run WHERE digest_sent_at IS NULL AND status IN ('COMPLETED', 'FAILED')");
    }

    private String readMigration() throws Exception {
        try (var in = getClass().getClassLoader().getResourceAsStream(MIGRATION)) {
            assertThat(in).describedAs("migration %s must be on the classpath", MIGRATION).isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
