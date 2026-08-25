package dev.hendrikhoemberg.webtesthelper.scheduling;

import dev.hendrikhoemberg.webtesthelper.catalog.SiteForm;
import dev.hendrikhoemberg.webtesthelper.catalog.SiteService;
import dev.hendrikhoemberg.webtesthelper.model.RunScope;
import dev.hendrikhoemberg.webtesthelper.support.AbstractPostgresTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves the {@link ScheduleService#seedDefaults} lost-race swallow: the whole reason it is not
 * {@code @Transactional} is that a concurrent seed of the same (site, tier) must collapse to the
 * winner's row rather than fail. A {@code @Transactional} test class could never exercise that —
 * the competing insert would run in one shared, rollback-only transaction.
 */
class ScheduleSeedRaceTest extends AbstractPostgresTest {

    @Autowired
    ScheduleService schedules;

    @Autowired
    SiteService sites;

    @Autowired
    JdbcTemplate jdbc;

    private long siteId;
    private Instant now;

    @BeforeEach
    void resetTables() {
        jdbc.update("DELETE FROM schedule");
        jdbc.update("DELETE FROM run");
        jdbc.update("DELETE FROM site_check_setting");
        jdbc.update("DELETE FROM site");
        siteId = sites.create(new SiteForm("Kunde", "https://seed-race.example.com/", 300, 5,
                Duration.ofMinutes(30), List.of(), List.of(), true, null));
        now = Instant.now();
    }

    @Test
    void concurrentSeedsLeaveExactlyOneRowPerScope() throws Exception {
        inParallel(8, () -> {
            schedules.seedDefaults(siteId, now);
            return null;
        });

        List<Schedule> rows = schedules.forSite(siteId);
        assertThat(rows).hasSize(3);
        assertThat(rows).extracting(Schedule::scope)
                .containsExactlyInAnyOrder(RunScope.PULSE, RunScope.FULL, RunScope.DEEP);
    }

    @Test
    void reseedingALoadedSiteStaysAtOneRowPerScope() {
        schedules.seedDefaults(siteId, now);

        schedules.seedDefaults(siteId, now);

        assertThat(schedules.forSite(siteId)).hasSize(3);
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
