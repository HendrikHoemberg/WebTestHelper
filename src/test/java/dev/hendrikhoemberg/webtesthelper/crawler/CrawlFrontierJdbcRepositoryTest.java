package dev.hendrikhoemberg.webtesthelper.crawler;

import dev.hendrikhoemberg.webtesthelper.crawler.persistence.CrawlFrontierJdbcRepository;
import dev.hendrikhoemberg.webtesthelper.support.AbstractPostgresTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

class CrawlFrontierJdbcRepositoryTest extends AbstractPostgresTest {

    @Autowired
    CrawlFrontierJdbcRepository frontier;

    @Autowired
    JdbcTemplate jdbc;

    private long runId;

    @BeforeEach
    void freshRun() {
        jdbc.update("DELETE FROM crawl_queue_item");
        jdbc.update("DELETE FROM run");
        jdbc.update("DELETE FROM site");
        Long siteId = jdbc.queryForObject(
                "INSERT INTO site (name, base_url) VALUES ('Fixture', 'http://127.0.0.1:1/') RETURNING id",
                Long.class);
        runId = jdbc.queryForObject(
                "INSERT INTO run (site_id, trigger_type, scope, status) "
                        + "VALUES (?, 'MANUAL', 'FULL', 'RUNNING') RETURNING id",
                Long.class, siteId);
    }

    @Test
    void seedingTheSameUrlTwiceInsertsItOnce() {
        assertThat(frontier.seed(runId, List.of("http://h/", "http://h/a"), 0)).isEqualTo(2);
        assertThat(frontier.seed(runId, List.of("http://h/", "http://h/b"), 0)).isEqualTo(1);
        assertThat(frontier.countPending(runId)).isEqualTo(3);
    }

    @Test
    void discoveryReEnqueuingAKnownUrlIsANoOp() {
        frontier.seed(runId, List.of("http://h/"), 0);
        assertThat(frontier.enqueue(runId, List.of("http://h/"), 1, "http://h/a")).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT depth FROM crawl_queue_item WHERE run_id = ? AND url = 'http://h/'",
                Integer.class, runId)).isZero();   // ON CONFLICT DO NOTHING: the first insert
        // keeps its depth, and breadth-first claiming means the first insert is the shallow one
    }

    @Test
    void claimingTakesABatchShallowestFirstAndMarksItClaimed() {
        frontier.seed(runId, List.of("http://h/"), 0);
        frontier.enqueue(runId, List.of("http://h/tief"), 3, "http://h/");
        frontier.enqueue(runId, List.of("http://h/flach"), 1, "http://h/");

        List<CrawlTarget> batch = frontier.claimBatch(runId, "worker-1", 2);

        assertThat(batch).extracting(CrawlTarget::url)
                .containsExactly("http://h/", "http://h/flach");
        assertThat(batch).extracting(CrawlTarget::depth).containsExactly(0, 1);
        assertThat(frontier.countPending(runId)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM crawl_queue_item WHERE status = 'CLAIMED' AND claimed_by = 'worker-1'",
                Integer.class)).isEqualTo(2);
    }

    @Test
    void twoWorkersClaimingConcurrentlyNeverGetTheSameUrl() throws Exception {
        List<String> urls = new java.util.ArrayList<>();
        for (int i = 0; i < 200; i++) {
            urls.add("http://h/seite-" + i);
        }
        frontier.seed(runId, urls, 0);

        try (ExecutorService pool = Executors.newFixedThreadPool(4)) {
            List<Callable<List<String>>> claimers = new java.util.ArrayList<>();
            for (int worker = 0; worker < 4; worker++) {
                String owner = "worker-" + worker;
                claimers.add(() -> {
                    List<String> mine = new java.util.ArrayList<>();
                    List<CrawlTarget> batch;
                    while (!(batch = frontier.claimBatch(runId, owner, 20)).isEmpty()) {
                        batch.forEach(target -> mine.add(target.url()));
                    }
                    return mine;
                });
            }
            List<String> all = new java.util.ArrayList<>();
            for (Future<List<String>> claimed : pool.invokeAll(claimers)) {
                all.addAll(claimed.get());
            }
            assertThat(all).hasSize(200).doesNotHaveDuplicates();
        }
    }

    @Test
    void completingABatchWritesStatusAndHttpCodeInOneRoundTrip() {
        frontier.seed(runId, List.of("http://h/", "http://h/kaputt"), 0);
        List<CrawlTarget> batch = frontier.claimBatch(runId, "worker-1", 10);

        frontier.complete(List.of(
                new CrawlOutcome(batch.get(0).id(), CrawlItemStatus.DONE, 200, null),
                new CrawlOutcome(batch.get(1).id(), CrawlItemStatus.FAILED, null, "Timeout 30000ms")));

        assertThat(frontier.countByStatus(runId))
                .containsEntry(CrawlItemStatus.DONE, 1)
                .containsEntry(CrawlItemStatus.FAILED, 1);
        assertThat(frontier.visitedUrls(runId)).containsExactly("http://h/");
        assertThat(jdbc.queryForObject(
                "SELECT error_message FROM crawl_queue_item WHERE url = 'http://h/kaputt'",
                String.class)).isEqualTo("Timeout 30000ms");
    }

    @Test
    void aClaimAbandonedByADeadWorkerIsReclaimed() {
        frontier.seed(runId, List.of("http://h/"), 0);
        frontier.claimBatch(runId, "worker-die", 10);
        jdbc.update("UPDATE crawl_queue_item SET claimed_at = now() - interval '10 minutes'");

        assertThat(frontier.reclaimStale(runId, Duration.ofMinutes(5), 3)).isEqualTo(1);
        assertThat(frontier.countPending(runId)).isEqualTo(1);
    }

    @Test
    void aUrlThatKeepsKillingItsWorkerIsGivenUpOnRatherThanReclaimedForever() {
        frontier.seed(runId, List.of("http://h/gift"), 0);
        for (int attempt = 0; attempt < 3; attempt++) {
            frontier.claimBatch(runId, "worker-1", 10);
            jdbc.update("UPDATE crawl_queue_item SET claimed_at = now() - interval '10 minutes'");
            frontier.reclaimStale(runId, Duration.ofMinutes(5), 3);
        }
        assertThat(frontier.countPending(runId)).isZero();
        assertThat(frontier.countByStatus(runId)).containsEntry(CrawlItemStatus.FAILED, 1);
    }

    @Test
    void frontierRowsBelongToTheirRunAndVanishWithIt() {
        frontier.seed(runId, List.of("http://h/"), 0);
        jdbc.update("DELETE FROM run WHERE id = ?", runId);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM crawl_queue_item", Integer.class))
                .isZero();
    }
}