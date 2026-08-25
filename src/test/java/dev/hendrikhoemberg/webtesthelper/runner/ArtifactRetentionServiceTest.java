package dev.hendrikhoemberg.webtesthelper.runner;

import dev.hendrikhoemberg.webtesthelper.crawler.CrawlerProperties;
import dev.hendrikhoemberg.webtesthelper.model.RunStatus;
import dev.hendrikhoemberg.webtesthelper.support.AbstractPostgresTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class ArtifactRetentionServiceTest extends AbstractPostgresTest {

    private static final Instant BASE = Instant.parse("2026-08-21T00:00:00Z");

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private CrawlerProperties properties;

    @Autowired
    private ArtifactRetentionService retention;

    private long siteA;
    private long siteB;
    private final Set<Long> createdDirs = new HashSet<>();

    @BeforeEach
    void resetTables() {
        jdbc.update("DELETE FROM run");
        jdbc.update("DELETE FROM site_check_setting");
        jdbc.update("DELETE FROM site");
        siteA = insertSite("https://a.example.com/");
        siteB = insertSite("https://b.example.com/");
        createdDirs.forEach(id -> deleteDir(properties.artifactDir().resolve(String.valueOf(id))));
        createdDirs.clear();
    }

    private long insertSite(String baseUrl) {
        return jdbc.queryForObject(
                "INSERT INTO site (name, base_url) VALUES (?, ?) RETURNING id",
                Long.class, baseUrl, baseUrl);
    }

    private long insertRun(long siteId, RunStatus status, Instant queuedAt) {
        return jdbc.queryForObject("""
                INSERT INTO run (site_id, trigger_type, scope, status, queued_at)
                VALUES (?, 'MANUAL', 'FULL', ?, ?) RETURNING id
                """, Long.class, siteId, status.name(), Timestamp.from(queuedAt));
    }

    private void createDir(long runId) {
        try {
            Files.createDirectories(properties.artifactDir().resolve(String.valueOf(runId)));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        createdDirs.add(runId);
    }

    private void deleteDir(Path dir) {
        if (Files.exists(dir)) {
            try {
                Files.delete(dir);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }

    private List<Long> insertTerminalRuns(long siteId, int count) {
        List<Long> ids = new ArrayList<>();
        for (int i = 1; i <= count; i++) {
            long id = insertRun(siteId, RunStatus.COMPLETED, BASE.plusSeconds(i));
            createDir(id);
            ids.add(id);
        }
        return ids;
    }

    @Test
    void keepsTheTwelveNewestAndDeletesTheThreeOldestPerSite() {
        List<Long> ids = insertTerminalRuns(siteA, 15);

        assertThat(retention.prune()).isEqualTo(3);

        assertThat(properties.artifactDir().resolve(String.valueOf(ids.get(0)))).doesNotExist();
        assertThat(properties.artifactDir().resolve(String.valueOf(ids.get(1)))).doesNotExist();
        assertThat(properties.artifactDir().resolve(String.valueOf(ids.get(2)))).doesNotExist();
        assertThat(properties.artifactDir().resolve(String.valueOf(ids.get(3)))).exists();
        assertThat(properties.artifactDir().resolve(String.valueOf(ids.get(14)))).exists();
    }

    @Test
    void aRunningRunsDirectoryIsNeverDeletedEvenWhenItIsTheOldest() {
        long running = insertRun(siteA, RunStatus.RUNNING, BASE.plusSeconds(0));
        createDir(running);
        List<Long> terminal = insertTerminalRuns(siteA, 15);

        assertThat(retention.prune()).isEqualTo(3);

        assertThat(properties.artifactDir().resolve(String.valueOf(running))).exists();
        assertThat(properties.artifactDir().resolve(String.valueOf(terminal.get(0)))).doesNotExist();
        assertThat(properties.artifactDir().resolve(String.valueOf(terminal.get(1)))).doesNotExist();
        assertThat(properties.artifactDir().resolve(String.valueOf(terminal.get(2)))).doesNotExist();
        assertThat(properties.artifactDir().resolve(String.valueOf(terminal.get(3)))).exists();
    }

    @Test
    void twoSitesAreRankedIndependently() {
        List<Long> siteAIds = insertTerminalRuns(siteA, 15);
        List<Long> siteBIds = insertTerminalRuns(siteB, 3);

        assertThat(retention.prune()).isEqualTo(3);

        assertThat(properties.artifactDir().resolve(String.valueOf(siteAIds.get(0)))).doesNotExist();
        assertThat(properties.artifactDir().resolve(String.valueOf(siteAIds.get(2)))).doesNotExist();
        assertThat(properties.artifactDir().resolve(String.valueOf(siteAIds.get(3)))).exists();
        // Site B has three terminal runs — few enough that none are pruned.
        siteBIds.forEach(id -> assertThat(properties.artifactDir().resolve(String.valueOf(id))).exists());
    }

    @Test
    void aDirectoryNamedForARunIdWithNoRowIsLeftAlone() {
        long ghost = 900_000_000L;
        createDir(ghost);
        insertTerminalRuns(siteA, 15);

        retention.prune();

        assertThat(properties.artifactDir().resolve(String.valueOf(ghost))).exists();
    }

    @Test
    void runningPruneTwiceDeletesNothingTheSecondTime() {
        insertTerminalRuns(siteA, 15);

        assertThat(retention.prune()).isEqualTo(3);
        assertThat(retention.prune()).isZero();
    }
}
