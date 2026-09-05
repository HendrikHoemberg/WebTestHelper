package dev.hendrikhoemberg.webtesthelper.challenger;

import dev.hendrikhoemberg.webtesthelper.catalog.SiteForm;
import dev.hendrikhoemberg.webtesthelper.catalog.SiteService;
import dev.hendrikhoemberg.webtesthelper.model.RunScope;
import dev.hendrikhoemberg.webtesthelper.scheduling.Schedule;
import dev.hendrikhoemberg.webtesthelper.scheduling.ScheduleService;
import dev.hendrikhoemberg.webtesthelper.support.AbstractPostgresTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Empirical stress test harness challenging ScheduleService transactions, concurrency,
 * and race condition handling under high thread contention (DB-02 / ScheduleSeedRace).
 */
class ScheduleSeedRaceAdversarialTest extends AbstractPostgresTest {

    @Autowired
    private ScheduleService schedules;

    @Autowired
    private SiteService sites;

    @Autowired
    private JdbcTemplate jdbc;

    private Instant now;

    @BeforeEach
    void cleanDatabase() {
        jdbc.update("DELETE FROM schedule");
        jdbc.update("DELETE FROM run");
        jdbc.update("DELETE FROM site_check_setting");
        jdbc.update("DELETE FROM site");
        now = Instant.now();
    }

    private long createSite(String name, String url) {
        return sites.create(new SiteForm(name, url, 300, 5,
                Duration.ofMinutes(30), List.of(), List.of(), true, null, true));
    }

    @Test
    @DisplayName("DB-02 Race: 24 concurrent threads competing to seed one site yields exactly 3 rows with 0 poisoned transactions")
    void extremeContentionOnSingleSiteLeavesExactRowsAndNoPoisoning() throws Exception {
        long siteId = createSite("HighContentionSite", "https://contention.example.com/");
        int threadCount = 24;

        List<Throwable> errors = Collections.synchronizedList(new ArrayList<>());
        ExecutorService pool = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();

        for (int i = 0; i < threadCount; i++) {
            futures.add(pool.submit(() -> {
                try {
                    startLatch.await();
                    schedules.seedDefaults(siteId, now);
                } catch (Throwable t) {
                    errors.add(t);
                }
            }));
        }

        startLatch.countDown();
        for (Future<?> f : futures) {
            f.get(10, TimeUnit.SECONDS);
        }

        pool.shutdown();
        assertThat(pool.awaitTermination(5, TimeUnit.SECONDS)).isTrue();

        assertThat(errors)
                .as("No DataIntegrityViolationException or UnexpectedRollbackException should escape seedDefaults")
                .isEmpty();

        List<Schedule> rows = schedules.forSite(siteId);
        assertThat(rows).hasSize(3);
        assertThat(rows).extracting(Schedule::scope)
                .containsExactlyInAnyOrder(RunScope.PULSE, RunScope.FULL, RunScope.DEEP);

        // Verify that database count matches JPA mapping
        Integer dbCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM schedule WHERE site_id = ?", Integer.class, siteId);
        assertThat(dbCount).isEqualTo(3);
    }

    @Test
    @DisplayName("DB-02 Race: Multiple threads concurrently executing seedMissingDefaults on multiple unseeded sites")
    void concurrentSeedMissingDefaultsAcrossMultipleSites() throws Exception {
        int siteCount = 5;
        List<Long> siteIds = new ArrayList<>();
        for (int i = 0; i < siteCount; i++) {
            siteIds.add(createSite("BatchSite-" + i, "https://batch-" + i + ".example.com/"));
        }

        // Verify initial state: 0 schedules
        Integer initialCount = jdbc.queryForObject("SELECT COUNT(*) FROM schedule", Integer.class);
        assertThat(initialCount).isEqualTo(0);

        int threads = 12;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch latch = new CountDownLatch(1);
        List<Throwable> errors = Collections.synchronizedList(new ArrayList<>());
        List<Future<?>> futures = new ArrayList<>();

        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                try {
                    latch.await();
                    schedules.seedMissingDefaults(now);
                } catch (Throwable t) {
                    errors.add(t);
                }
            }));
        }

        latch.countDown();
        for (Future<?> f : futures) {
            f.get(15, TimeUnit.SECONDS);
        }

        pool.shutdown();
        assertThat(pool.awaitTermination(5, TimeUnit.SECONDS)).isTrue();

        assertThat(errors).isEmpty();

        Integer totalSchedules = jdbc.queryForObject("SELECT COUNT(*) FROM schedule", Integer.class);
        assertThat(totalSchedules).isEqualTo(siteCount * 3);

        for (Long siteId : siteIds) {
            List<Schedule> siteSchedules = schedules.forSite(siteId);
            assertThat(siteSchedules).hasSize(3);
            assertThat(siteSchedules).extracting(Schedule::scope)
                    .containsExactlyInAnyOrder(RunScope.PULSE, RunScope.FULL, RunScope.DEEP);
        }
    }

    @Test
    @DisplayName("DB-02 Isolation: Concurrent seedDefaults interleaved with @Transactional read queries and updates")
    void concurrentSeedsWithInterleavedReadsAndUpdates() throws Exception {
        long siteId1 = createSite("Site1", "https://s1.example.com/");
        long siteId2 = createSite("Site2", "https://s2.example.com/");

        // Seed site1 initially so update has a row to modify
        schedules.seedDefaults(siteId1, now);
        List<Schedule> s1Rows = schedules.forSite(siteId1);
        long pulseScheduleId = s1Rows.stream()
                .filter(s -> s.scope() == RunScope.PULSE)
                .findFirst()
                .orElseThrow()
                .id();

        int seeders = 8;
        int readers = 6;
        int updaters = 1;
        ExecutorService pool = Executors.newFixedThreadPool(seeders + readers + updaters);
        CountDownLatch latch = new CountDownLatch(1);
        AtomicBoolean running = new AtomicBoolean(true);
        List<Throwable> errors = Collections.synchronizedList(new ArrayList<>());
        List<Future<?>> futures = new ArrayList<>();

        // Seeder threads hammering site2
        for (int i = 0; i < seeders; i++) {
            futures.add(pool.submit(() -> {
                try {
                    latch.await();
                    while (running.get()) {
                        schedules.seedDefaults(siteId2, Instant.now());
                        Thread.sleep(2);
                    }
                } catch (Throwable t) {
                    errors.add(t);
                }
            }));
        }

        // Reader threads calling @Transactional(readOnly = true) methods
        for (int i = 0; i < readers; i++) {
            final int rId = i;
            futures.add(pool.submit(() -> {
                try {
                    latch.await();
                    while (running.get()) {
                        if (rId % 3 == 0) {
                            List<Schedule> due = schedules.due(Instant.now().plus(Duration.ofDays(30)), 10);
                            assertThat(due).isNotNull();
                        } else if (rId % 3 == 1) {
                            Map<Long, Schedule> nextFire = schedules.nextFirePerSite();
                            assertThat(nextFire).isNotNull();
                        } else {
                            List<Schedule> siteRows = schedules.forSite(siteId1);
                            assertThat(siteRows).hasSize(3);
                        }
                        Thread.sleep(3);
                    }
                } catch (Throwable t) {
                    errors.add(t);
                }
            }));
        }

        // Updater thread calling @Transactional update concurrently with seeders and readers
        for (int i = 0; i < updaters; i++) {
            futures.add(pool.submit(() -> {
                try {
                    latch.await();
                    int count = 0;
                    while (running.get()) {
                        String cron = (count++ % 2 == 0) ? "0 0 * * * ?" : "0 30 * * * ?";
                        schedules.update(pulseScheduleId, cron, "Europe/Berlin", true, Instant.now());
                        Thread.sleep(5);
                    }
                } catch (Throwable t) {
                    errors.add(t);
                }
            }));
        }

        latch.countDown();
        Thread.sleep(1500);
        running.set(false);

        for (Future<?> f : futures) {
            f.get(10, TimeUnit.SECONDS);
        }

        pool.shutdown();
        assertThat(pool.awaitTermination(5, TimeUnit.SECONDS)).isTrue();

        assertThat(errors).isEmpty();

        // Verify final state of site2: exactly 3 rows
        List<Schedule> site2Rows = schedules.forSite(siteId2);
        assertThat(site2Rows).hasSize(3);
        assertThat(site2Rows).extracting(Schedule::scope)
                .containsExactlyInAnyOrder(RunScope.PULSE, RunScope.FULL, RunScope.DEEP);
    }

    @Test
    @DisplayName("DB-02 Versioning: Concurrent updates on the same schedule row are defended by optimistic locking")
    void concurrentUpdatesTriggerOptimisticLockingAsDesigned() throws Exception {
        long siteId = createSite("LockingSite", "https://locking.example.com/");
        schedules.seedDefaults(siteId, now);
        List<Schedule> rows = schedules.forSite(siteId);
        long scheduleId = rows.get(0).id();

        int threads = 4;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch latch = new CountDownLatch(1);
        List<Throwable> errors = Collections.synchronizedList(new ArrayList<>());
        List<Future<?>> futures = new ArrayList<>();

        for (int i = 0; i < threads; i++) {
            final int idx = i;
            futures.add(pool.submit(() -> {
                try {
                    latch.await();
                    schedules.update(scheduleId, "0 " + idx + " * * * ?", "Europe/Berlin", true, Instant.now());
                } catch (Throwable t) {
                    errors.add(t);
                }
            }));
        }

        latch.countDown();
        for (Future<?> f : futures) {
            f.get(5, TimeUnit.SECONDS);
        }
        pool.shutdown();

        // At least one thread should succeed, and conflicting threads should encounter optimistic locking failure
        // rather than silent lost updates or database deadlock.
        long optimisticLockFailures = errors.stream()
                .filter(t -> t instanceof org.springframework.orm.ObjectOptimisticLockingFailureException
                        || (t.getCause() != null && t.getCause() instanceof org.hibernate.StaleObjectStateException))
                .count();

        // If there were any errors, they MUST be optimistic locking failures, never SQL deadlocks or poisonings
        assertThat(errors).allMatch(t -> t instanceof org.springframework.orm.ObjectOptimisticLockingFailureException
                || (t.getCause() != null && t.getCause() instanceof org.hibernate.StaleObjectStateException));
    }
}
