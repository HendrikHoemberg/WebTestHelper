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
 * Spec 14's two kill switches: a global pause and a per-site disable. Each one must stop the
 * clock — a paused tick must not even seed, and neither switch may advance a backdated row —
 * without building an invisible backlog. D41: neither may block a manual run, because the
 * switches gate the clock, not the person.
 *
 * <p>Deliberately not {@code @Transactional}, for the same reason as
 * {@link ScheduleDispatcherTest}: the dispatcher commits each claim on its own, so the test
 * clears its tables in {@code @BeforeEach} rather than relying on a rollback.
 */
class ScheduleKillSwitchTest extends AbstractPostgresTest {

    private static final Instant NOW = Instant.parse("2026-08-25T12:00:00Z");

    @Autowired
    ScheduleDispatcher dispatcher;

    @Autowired
    ScheduleService schedules;

    @Autowired
    SiteService sites;

    @Autowired
    RunService runs;

    @Autowired
    AppSettings appSettings;

    @Autowired
    JdbcTemplate jdbc;

    private long siteId;

    @BeforeEach
    void resetTables() {
        jdbc.update("DELETE FROM schedule");
        jdbc.update("DELETE FROM run");
        jdbc.update("DELETE FROM site_check_setting");
        jdbc.update("DELETE FROM site");
        siteId = sites.create(new SiteForm("Kunde", "https://pause.example.com/", 300, 5,
                Duration.ofMinutes(30), List.of(), List.of(), true, null, true));
    }

    @AfterEach
    void resetPauseSwitch() {
        // scheduling.paused is persisted (this class is not @Transactional) and the database is
        // shared with the other scheduling tests via the shared Spring context, so a test that
        // flips the pause must not leave it on for the next one.
        appSettings.saveSchedulingPaused(false);
    }

    @Test
    void aPausedTickSeedsNothingAndLeavesABackdatedRowAlone() {
        appSettings.saveSchedulingPaused(true);

        // The pause must short-circuit before the seed step: a paused instance must not grow
        // the schedule table either — it only sits still.
        assertThat(dispatcher.tick(NOW)).isZero();
        assertThat(schedules.forSite(siteId)).isEmpty();
        assertThat(queuedRuns(siteId)).isZero();

        schedules.seedDefaults(siteId, NOW);
        setNextFireAt(siteId, RunScope.PULSE, NOW.minus(Duration.ofDays(1)));
        Schedule before = row(siteId, RunScope.PULSE);

        assertThat(dispatcher.tick(NOW)).isZero();
        Schedule after = row(siteId, RunScope.PULSE);

        // A pause must not consume the occurrences it skipped. If it advanced the row, the
        // two missed days are silently gone — indistinguishable from the outage it was flipped
        // for. The exact stored values must be identical before and after.
        assertThat(after.nextFireAt()).isEqualTo(before.nextFireAt());
        assertThat(after.lastFiredAt()).isEqualTo(before.lastFiredAt());
        assertThat(queuedRuns(siteId)).isZero();
    }

    @Test
    void unpausingLetsAPausedInstanceFireTheBackdatedRun() {
        schedules.seedDefaults(siteId, NOW);
        setNextFireAt(siteId, RunScope.PULSE, NOW.minus(Duration.ofDays(1)));
        appSettings.saveSchedulingPaused(true);
        assertThat(dispatcher.tick(NOW)).isZero();

        appSettings.saveSchedulingPaused(false);

        assertThat(dispatcher.tick(NOW)).isEqualTo(1);
        assertThat(queuedRuns(siteId)).isEqualTo(1);
    }

    @Test
    void aDisabledSitePausesWithoutForgettingItsSchedules() {
        schedules.seedDefaults(siteId, NOW);
        jdbc.update("UPDATE site SET enabled = FALSE WHERE id = ?", siteId);
        setNextFireAt(siteId, RunScope.PULSE, NOW.minus(Duration.ofDays(1)));
        Schedule before = row(siteId, RunScope.PULSE);

        assertThat(dispatcher.tick(NOW)).isZero();
        Schedule after = row(siteId, RunScope.PULSE);
        assertThat(after.nextFireAt()).isEqualTo(before.nextFireAt());
        assertThat(after.lastFiredAt()).isEqualTo(before.lastFiredAt());
        assertThat(queuedRuns(siteId)).isZero();

        // A disabled site is paused, not forgotten: its schedules are still seeded and listed.
        assertThat(schedules.forSite(siteId)).hasSize(3);

        jdbc.update("UPDATE site SET enabled = TRUE WHERE id = ?", siteId);
        assertThat(dispatcher.tick(NOW)).isEqualTo(1);
        assertThat(queuedRuns(siteId)).isEqualTo(1);
    }

    @Test
    void aManualRunStillQueuesWhileTheSiteIsDisabledAndTheSchedulerIsPaused() {
        schedules.seedDefaults(siteId, NOW);
        jdbc.update("UPDATE site SET enabled = FALSE WHERE id = ?", siteId);
        appSettings.saveSchedulingPaused(true);

        // D41: the kill switches gate the clock, not the person. A manual "Jetzt prüfen" must
        // still enqueue, here exercised through the same RunService the controller calls.
        long runId = runs.enqueue(siteId, RunTrigger.MANUAL, RunScope.FULL);

        assertThat(runId).isPositive();
        assertThat(jdbc.queryForObject("SELECT status FROM run WHERE id = ?", String.class, runId))
                .isEqualTo("QUEUED");
    }

    private Schedule row(long siteId, RunScope scope) {
        return schedules.forSite(siteId).stream()
                .filter(r -> r.scope() == scope)
                .findFirst().orElseThrow();
    }

    private int queuedRuns(long siteId) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM run WHERE site_id = ? AND status = 'QUEUED'", Integer.class, siteId);
    }

    private void setNextFireAt(long siteId, RunScope scope, Instant value) {
        jdbc.update("UPDATE schedule SET next_fire_at = ? WHERE site_id = ? AND scope = ?",
                Timestamp.from(value), siteId, scope.name());
    }
}
