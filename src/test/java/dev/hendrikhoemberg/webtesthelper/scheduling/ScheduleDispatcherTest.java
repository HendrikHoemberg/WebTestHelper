package dev.hendrikhoemberg.webtesthelper.scheduling;

import dev.hendrikhoemberg.webtesthelper.catalog.SiteForm;
import dev.hendrikhoemberg.webtesthelper.catalog.SiteService;
import dev.hendrikhoemberg.webtesthelper.model.RunScope;
import dev.hendrikhoemberg.webtesthelper.model.RunTrigger;
import dev.hendrikhoemberg.webtesthelper.runner.RunService;
import dev.hendrikhoemberg.webtesthelper.scheduling.persistence.ScheduleClaimJdbcRepository;
import dev.hendrikhoemberg.webtesthelper.support.AbstractPostgresTest;
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
 * The scheduling tick: due detection, durable claim, enqueue. Deliberately not
 * {@code @Transactional} — the dispatcher commits each claim on its own, so the test clears
 * {@code run} and {@code schedule} in {@code @BeforeEach} rather than relying on a rollback.
 */
class ScheduleDispatcherTest extends AbstractPostgresTest {

    private static final Instant NOW = Instant.parse("2026-08-25T12:00:00Z");

    @Autowired
    ScheduleDispatcher dispatcher;

    @Autowired
    ScheduleService schedules;

    @Autowired
    ScheduleClaimJdbcRepository claims;

    @Autowired
    SiteService sites;

    @Autowired
    RunService runs;

    @Autowired
    JdbcTemplate jdbc;

    private long siteId;

    @BeforeEach
    void resetTables() {
        jdbc.update("DELETE FROM schedule");
        jdbc.update("DELETE FROM run");
        jdbc.update("DELETE FROM site_check_setting");
        jdbc.update("DELETE FROM site");
        siteId = sites.create(new SiteForm("Kunde", "https://tick.example.com/", 300, 5,
                Duration.ofMinutes(30), List.of(), List.of(), true, null));
    }

    @Test
    void aDueScheduleQueuesOneRunAndAdvancesTheRow() {
        schedules.seedDefaults(siteId, NOW);
        setNextFireAt(siteId, RunScope.PULSE, NOW.minusSeconds(60));

        int queued = dispatcher.tick(NOW);

        assertThat(queued).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT status FROM run WHERE site_id = ? AND status = 'QUEUED'", String.class, siteId))
                .isEqualTo("QUEUED");
        assertThat(jdbc.queryForObject(
                "SELECT trigger_type FROM run WHERE site_id = ? AND status = 'QUEUED'", String.class, siteId))
                .isEqualTo("SCHEDULED");
        assertThat(jdbc.queryForObject(
                "SELECT scope FROM run WHERE site_id = ? AND status = 'QUEUED'", String.class, siteId))
                .isEqualTo("PULSE");
        Schedule after = row(siteId, RunScope.PULSE);
        assertThat(after.lastFiredAt()).isEqualTo(NOW);
        assertThat(after.nextFireAt()).isAfter(NOW);
    }

    @Test
    void tickingAgainImmediatelyFiresNothing() {
        schedules.seedDefaults(siteId, NOW);
        setNextFireAt(siteId, RunScope.PULSE, NOW.minusSeconds(60));
        dispatcher.tick(NOW);

        assertThat(dispatcher.tick(NOW)).isZero();
        // The first tick's run is still queued; the second tick must not add a second one.
        assertThat(queuedRuns(siteId)).isEqualTo(1);
    }

    @Test
    void aPulseBackdatedTwoDaysJumpsStraightToTheNextOccurrenceAfterNow() {
        schedules.seedDefaults(siteId, NOW);
        setNextFireAt(siteId, RunScope.PULSE, NOW.minus(Duration.ofDays(2)));

        dispatcher.tick(NOW);

        // D40: one occurrence per tick, never a replay. The row must land on the next 03:00
        // after now, not on yesterday's (which repeated ticks would only reach by luck).
        Instant expected = CronSchedule.parse("0 0 3 * * *", "Europe/Berlin")
                .orElseThrow().nextAfter(NOW);
        assertThat(row(siteId, RunScope.PULSE).nextFireAt()).isEqualTo(expected);
        assertThat(expected).isEqualTo(Instant.parse("2026-08-26T01:00:00Z"));
    }

    @Test
    void aDisabledScheduleNeverFiresHoweverFarBackdated() {
        schedules.seedDefaults(siteId, NOW);
        jdbc.update("UPDATE schedule SET enabled = FALSE WHERE site_id = ? AND scope = 'PULSE'", siteId);
        setNextFireAt(siteId, RunScope.PULSE, NOW.minus(Duration.ofDays(400)));

        assertThat(dispatcher.tick(NOW)).isZero();
        assertThat(queuedRuns(siteId)).isZero();
        assertThat(row(siteId, RunScope.PULSE).nextFireAt()).isEqualTo(NOW.minus(Duration.ofDays(400)));
    }

    @Test
    void aBrokenCronDoesNotPoisonTheTick() {
        schedules.seedDefaults(siteId, NOW);
        // The broken row must be processed first: its next_fire_at is older than the healthy
        // FULL row, and due() orders ascending, so the disabled parse is exercised before the
        // healthy row ever gets a chance to fire.
        jdbc.update("UPDATE schedule SET cron = 'nicht-ein-cron' WHERE site_id = ? AND scope = 'PULSE'", siteId);
        setNextFireAt(siteId, RunScope.PULSE, NOW.minus(Duration.ofDays(2)));
        setNextFireAt(siteId, RunScope.FULL, NOW.minus(Duration.ofDays(1)));

        int queued = dispatcher.tick(NOW);

        assertThat(queued).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT scope FROM run WHERE site_id = ? AND status = 'QUEUED'", String.class, siteId))
                .isEqualTo("FULL");
        assertThat(row(siteId, RunScope.PULSE).nextFireAt())
                .isEqualTo(NOW.minus(Duration.ofDays(2)));
    }

    @Test
    void theClaimIsACompareAndSetAndIsExclusive() {
        Schedule pulse = seededAndBackdated(siteId, RunScope.PULSE, NOW.minus(Duration.ofDays(1)));
        Instant newNext = NOW.plusSeconds(3600);
        Instant otherNext = NOW.plusSeconds(7200);

        assertThat(claims.claim(pulse.id(), pulse.nextFireAt(), newNext, NOW)).isTrue();
        assertThat(claims.claim(pulse.id(), pulse.nextFireAt(), otherNext, NOW)).isFalse();
        assertThat(row(siteId, RunScope.PULSE).nextFireAt()).isEqualTo(newNext);
    }

    @Test
    void anAlreadyQueuedSameScopeRunIsNotDuplicated() {
        schedules.seedDefaults(siteId, NOW);
        setNextFireAt(siteId, RunScope.PULSE, NOW.minusSeconds(60));
        runs.enqueue(siteId, RunTrigger.MANUAL, RunScope.PULSE);

        int queued = dispatcher.tick(NOW);

        // The tick still fired (return value is the number of occurrences it handled); the
        // existing QUEUED run simply swallows the new one instead of building a backlog.
        assertThat(queued).isEqualTo(1);
        assertThat(queuedRuns(siteId)).isEqualTo(1);
    }

    @Test
    void aNeverFiringCronIsSkippedAndLeavesTheRowAlone() {
        // "31 February" parses in Spring's CronExpression but has no future occurrence, so
        // nextAfter returns null. The guard must leave the row exactly where it is.
        schedules.seedDefaults(siteId, NOW);
        jdbc.update("UPDATE schedule SET cron = '0 0 3 31 2 *' WHERE site_id = ? AND scope = 'PULSE'", siteId);
        setNextFireAt(siteId, RunScope.PULSE, NOW.minus(Duration.ofDays(2)));

        assertThat(dispatcher.tick(NOW)).isZero();
        assertThat(queuedRuns(siteId)).isZero();
        assertThat(row(siteId, RunScope.PULSE).nextFireAt()).isEqualTo(NOW.minus(Duration.ofDays(2)));
        assertThat(row(siteId, RunScope.PULSE).lastFiredAt()).isNull();
    }

    @Test
    void seedMissingDefaultsRunsFirstAndFiresNoneOfTheFreshRows() {
        // siteId has no schedule rows: the tick's lazy backfill seeds all three tiers, and
        // because every default is strictly after now, nothing is due within the same tick.
        assertThat(dispatcher.tick(NOW)).isZero();

        List<Schedule> rows = schedules.forSite(siteId);
        assertThat(rows).hasSize(3);
        assertThat(rows).allSatisfy(row -> assertThat(row.lastFiredAt()).isNull());
        assertThat(queuedRuns(siteId)).isZero();
    }

    private Schedule seededAndBackdated(long siteId, RunScope scope, Instant nextFireAt) {
        schedules.seedDefaults(siteId, NOW);
        Schedule row = row(siteId, scope);
        setNextFireAt(siteId, scope, nextFireAt);
        return row(siteId, scope);
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
