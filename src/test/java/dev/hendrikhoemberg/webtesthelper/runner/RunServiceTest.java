package dev.hendrikhoemberg.webtesthelper.runner;

import dev.hendrikhoemberg.webtesthelper.model.RunScope;
import dev.hendrikhoemberg.webtesthelper.model.RunTrigger;
import dev.hendrikhoemberg.webtesthelper.support.AbstractPostgresTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.HashSet;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

class RunServiceTest extends AbstractPostgresTest {

    @Autowired
    RunService runs;

    @Autowired
    JdbcTemplate jdbc;

    private long siteId;

    @BeforeEach
    void resetTables() {
        jdbc.update("DELETE FROM run");
        jdbc.update("DELETE FROM site_check_setting");
        jdbc.update("DELETE FROM site");
        siteId = jdbc.queryForObject(
                "INSERT INTO site (name, base_url) VALUES (?, ?) RETURNING id",
                Long.class, "https://a.example.com/", "https://a.example.com/");
    }

    @Test
    void parallelEnqueuesAllReturnTheSameRunAndLeaveOneQueued() throws Exception {
        int workers = 8;
        List<Long> ids = inParallel(workers,
                () -> runs.enqueue(siteId, RunTrigger.MANUAL, RunScope.FULL));

        assertThat(new HashSet<>(ids)).hasSize(1);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM run WHERE site_id = ? AND status = 'QUEUED'",
                Integer.class, siteId)).isEqualTo(1);
    }

    private <T> List<T> inParallel(int threads, Callable<T> work) throws Exception {
        try (ExecutorService pool = Executors.newFixedThreadPool(threads)) {
            List<Future<T>> futures = pool.invokeAll(java.util.Collections.nCopies(threads, work));
            List<T> results = new java.util.ArrayList<>();
            for (Future<T> future : futures) {
                results.add(future.get());
            }
            return results;
        }
    }
}