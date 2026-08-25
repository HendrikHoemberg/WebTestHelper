package dev.hendrikhoemberg.webtesthelper.scheduling;

import dev.hendrikhoemberg.webtesthelper.catalog.AppSettings;
import dev.hendrikhoemberg.webtesthelper.catalog.SiteForm;
import dev.hendrikhoemberg.webtesthelper.catalog.SiteService;
import dev.hendrikhoemberg.webtesthelper.model.RunScope;
import dev.hendrikhoemberg.webtesthelper.model.RunTrigger;
import dev.hendrikhoemberg.webtesthelper.runner.RunService;
import dev.hendrikhoemberg.webtesthelper.support.AbstractPostgresTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The full tier cycle, end to end: a site is created, its three schedules appear, a backdated
 * pulse fires once (D40's "fires once, then advances past now"), the kill switches stop the clock
 * without consuming the occurrences they skip, and a manual run still gets through while paused
 * (D41). Browser-free and database-backed: the tick is called by hand and every step is an
 * assertion.
 *
 * <p>Deliberately not {@code @Transactional}, for the same reason as
 * {@link ScheduleDispatcherTest}: the dispatcher commits each claim on its own, so the test clears
 * its tables in {@code @BeforeEach} rather than relying on a rollback.
 */
class ScheduleAcceptanceTest extends AbstractPostgresTest {

    private static final Instant NOW = Instant.parse("2026-08-25T12:00:00Z");

    @Autowired
    ScheduleDispatcher dispatcher;

    @Autowired
    ScheduleService schedules;

    @Autowired
    SiteService sites;

    @Autowired
    AppSettings appSettings;

    @Autowired
    RunService runs;

    @Autowired
    JdbcTemplate jdbc;

    private long siteId;

    @BeforeEach
    void resetState() {
        jdbc.update("DELETE FROM schedule");
        jdbc.update("DELETE FROM run");
        jdbc.update("DELETE FROM site_check_setting");
        jdbc.update("DELETE FROM site");
        jdbc.update("DELETE FROM app_setting WHERE setting_key = ?", AppSettings.KEY_SCHEDULING_PAUSED);
        siteId = sites.create(new SiteForm("Kunde", "https://accept.example.com/", 300, 5,
                Duration.ofMinutes(30), List.of(), List.of(), true, null, true));
    }

    @AfterEach
    void resetPause() {
        // scheduling.paused is persisted (this class is not @Transactional) and the database is
        // shared with the other scheduling tests via the shared Spring context, so a test that
        // flips the pause must not leave it on for the next one.
        appSettings.saveSchedulingPaused(false);
    }

    @Test
    void aFullTierCycleRunsToScheduleFirePauseAndDisable() {
        // Step 1: a fresh site gets all three tiers seeded on the first tick, and because every
        // default fire is in the future, the same tick does not queue anything.
        assertThat(dispatcher.tick(NOW)).isZero();
        List<Schedule> seeded = schedules.forSite(siteId);
        assertThat(seeded).hasSize(3);
        assertThat(seeded).allSatisfy(row -> {
            assertThat(row.enabled()).isTrue();
            assertThat(row.timezone()).isEqualTo("Europe/Berlin");
            assertThat(row.cron()).isEqualTo(row.scope().defaultCron());
        });
        assertThat(totalRuns(siteId)).isZero();

        // Step 2: a pulse backdated a minute fires exactly one run, and the row advances to
        // tomorrow's 03:00 in Berlin — asserted as an instant via the schedule's own computations.
        setNextFireAt(siteId, RunScope.PULSE, NOW.minusSeconds(60));
        assertThat(dispatcher.tick(NOW)).isEqualTo(1);
        Schedule pulseAfterFirstFire = row(siteId, RunScope.PULSE);
        assertThat(pulseAfterFirstFire.lastFiredAt()).isEqualTo(NOW);
        Instant expectedTomorrow = CronSchedule.parse("0 0 3 * * *", "Europe/Berlin")
                .orElseThrow().nextAfter(NOW);
        assertThat(pulseAfterFirstFire.nextFireAt()).isEqualTo(expectedTomorrow);
        assertThat(expectedTomorrow).isEqualTo(Instant.parse("2026-08-26T01:00:00Z"));
        assertThat(totalRuns(siteId)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT status FROM run WHERE site_id = ? AND status = 'QUEUED'", String.class, siteId))
                .isEqualTo("QUEUED");
        assertThat(jdbc.queryForObject(
                "SELECT trigger_type FROM run WHERE site_id = ? AND status = 'QUEUED'", String.class, siteId))
                .isEqualTo("SCHEDULED");
        assertThat(jdbc.queryForObject(
                "SELECT scope FROM run WHERE site_id = ? AND status = 'QUEUED'", String.class, siteId))
                .isEqualTo("PULSE");

        // Step 3: nothing is due yet, so a second tick changes nothing.
        assertThat(dispatcher.tick(NOW)).isZero();
        assertThat(totalRuns(siteId)).isEqualTo(1);

        // The first pulse run finishes its cycle; completing it releases the per-(site, scope)
        // QUEUED dedupe (ux_run_single_queued_per_site_scope), which is what lets the next
        // backdated pulse occurrence become a distinct second run rather than collapsing into it.
        complete(firstRun(siteId, RunScope.PULSE));

        // Step 4: D40. Backdating a pulse two days must still queue exactly one more run and land
        // the row on the next 03:00 after now — the two missed occurrences are not replayed.
        setNextFireAt(siteId, RunScope.PULSE, NOW.minus(Duration.ofDays(2)));
        assertThat(dispatcher.tick(NOW)).isEqualTo(1);
        assertThat(totalRuns(siteId)).isEqualTo(2);
        Instant expectedNext = CronSchedule.parse("0 0 3 * * *", "Europe/Berlin")
                .orElseThrow().nextAfter(NOW);
        assertThat(row(siteId, RunScope.PULSE).nextFireAt()).isEqualTo(expectedNext);
        assertThat(expectedNext).isEqualTo(Instant.parse("2026-08-26T01:00:00Z"));

        // Step 5: the global pause stops the clock — a backdated full must not advance the row.
        appSettings.saveSchedulingPaused(true);
        setNextFireAt(siteId, RunScope.FULL, NOW.minus(Duration.ofDays(1)));
        Schedule fullBeforePause = row(siteId, RunScope.FULL);
        assertThat(dispatcher.tick(NOW)).isZero();
        Schedule fullAfterPause = row(siteId, RunScope.FULL);
        assertThat(fullAfterPause.nextFireAt()).isEqualTo(fullBeforePause.nextFireAt());
        assertThat(fullAfterPause.lastFiredAt()).isEqualTo(fullBeforePause.lastFiredAt());
        assertThat(totalRuns(siteId)).isEqualTo(2);

        // Step 6: unpausing and disabling the site pauses it without forgetting its schedules.
        appSettings.saveSchedulingPaused(false);
        jdbc.update("UPDATE site SET enabled = FALSE WHERE id = ?", siteId);
        Schedule fullBeforeDisable = row(siteId, RunScope.FULL);
        assertThat(dispatcher.tick(NOW)).isZero();
        Schedule fullAfterDisable = row(siteId, RunScope.FULL);
        assertThat(fullAfterDisable.nextFireAt()).isEqualTo(fullBeforeDisable.nextFireAt());
        assertThat(fullAfterDisable.lastFiredAt()).isEqualTo(fullBeforeDisable.lastFiredAt());
        assertThat(totalRuns(siteId)).isEqualTo(2);
        assertThat(schedules.forSite(siteId)).hasSize(3);

        // Step 7: re-enabling the site lets the still-backdated full fire.
        jdbc.update("UPDATE site SET enabled = TRUE WHERE id = ?", siteId);
        assertThat(dispatcher.tick(NOW)).isEqualTo(1);
        assertThat(totalRuns(siteId)).isEqualTo(3);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM run WHERE site_id = ? AND scope = 'FULL' AND status = 'QUEUED' AND trigger_type = 'SCHEDULED'",
                Integer.class, siteId)).isEqualTo(1);

        // Step 8: §9's "any tier can be disabled" holds at the tier, not just the site — a disabled
        // deep row stays put however far it is backdated.
        jdbc.update("UPDATE schedule SET enabled = FALSE WHERE site_id = ? AND scope = 'DEEP'", siteId);
        setNextFireAt(siteId, RunScope.DEEP, NOW.minus(Duration.ofDays(30)));
        Schedule deepBefore = row(siteId, RunScope.DEEP);
        assertThat(dispatcher.tick(NOW)).isZero();
        Schedule deepAfter = row(siteId, RunScope.DEEP);
        assertThat(deepAfter.nextFireAt()).isEqualTo(deepBefore.nextFireAt());
        assertThat(deepAfter.lastFiredAt()).isNull();
        assertThat(totalRuns(siteId)).isEqualTo(3);

        // Step 9: D41 — the kill switches gate the clock, not the person. A manual full still
        // enqueues while paused, here as a genuinely new MANUAL row (the scheduled full finished).
        complete(firstRun(siteId, RunScope.FULL));
        appSettings.saveSchedulingPaused(true);
        long manualRunId = runs.enqueue(siteId, RunTrigger.MANUAL, RunScope.FULL);
        assertThat(manualRunId).isPositive();
        assertThat(jdbc.queryForObject("SELECT status FROM run WHERE id = ?", String.class, manualRunId))
                .isEqualTo("QUEUED");
        assertThat(jdbc.queryForObject("SELECT trigger_type FROM run WHERE id = ?", String.class, manualRunId))
                .isEqualTo("MANUAL");
        assertThat(totalRuns(siteId)).isEqualTo(4);
    }

    private Schedule row(long siteId, RunScope scope) {
        return schedules.forSite(siteId).stream()
                .filter(r -> r.scope() == scope)
                .findFirst().orElseThrow();
    }

    private long firstRun(long siteId, RunScope scope) {
        return jdbc.queryForObject(
                "SELECT id FROM run WHERE site_id = ? AND scope = ?", Long.class, siteId, scope.name());
    }

    private void complete(long runId) {
        jdbc.update("UPDATE run SET status = 'COMPLETED' WHERE id = ?", runId);
    }

    private void setNextFireAt(long siteId, RunScope scope, Instant value) {
        jdbc.update("UPDATE schedule SET next_fire_at = ? WHERE site_id = ? AND scope = ?",
                Timestamp.from(value), siteId, scope.name());
    }

    private int totalRuns(long siteId) {
        return jdbc.queryForObject("SELECT count(*) FROM run WHERE site_id = ?", Integer.class, siteId);
    }
}
