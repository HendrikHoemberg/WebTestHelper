package dev.hendrikhoemberg.webtesthelper.scheduling;

import dev.hendrikhoemberg.webtesthelper.catalog.SiteForm;
import dev.hendrikhoemberg.webtesthelper.catalog.SiteService;
import dev.hendrikhoemberg.webtesthelper.model.RunScope;
import dev.hendrikhoemberg.webtesthelper.support.AbstractPostgresTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

@Transactional
class ScheduleServiceTest extends AbstractPostgresTest {

    @Autowired
    ScheduleService schedules;

    @Autowired
    SiteService sites;

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void seedDefaultsCreatesOneRowPerTierWithTheSpecNineCrons() {
        long siteId = newSite("a.example.com");
        Instant now = Instant.now();

        schedules.seedDefaults(siteId, now);

        List<Schedule> rows = schedules.forSite(siteId);
        assertThat(rows).hasSize(3);
        assertThat(rows).extracting(Schedule::scope, Schedule::cron)
                .contains(tuple(RunScope.PULSE, "0 0 3 * * *"),
                        tuple(RunScope.FULL, "0 0 3 * * SUN"),
                        tuple(RunScope.DEEP, "0 0 3 1 * *"));
        assertThat(rows).allSatisfy(row -> {
            assertThat(row.timezone()).isEqualTo("Europe/Berlin");
            assertThat(row.enabled()).isTrue();
            assertThat(row.lastFiredAt()).isNull();
        });
    }

    @Test
    void everySeededNextFireAtIsStrictlyInTheFuture() {
        long siteId = newSite("b.example.com");
        Instant now = Instant.now();

        schedules.seedDefaults(siteId, now);

        // A site taking shape at 02:59 must not queue a crawl for a site nobody finished
        // configuring: every default fires strictly after now.
        assertThat(schedules.forSite(siteId))
                .allSatisfy(row -> assertThat(row.nextFireAt()).isAfter(now));
    }

    @Test
    void seedDefaultsIsIdempotentPerScopeAndRefillsADeletedRow() {
        long siteId = newSite("c.example.com");
        Instant now = Instant.now();

        schedules.seedDefaults(siteId, now);
        assertThat(schedules.forSite(siteId)).hasSize(3);

        schedules.seedDefaults(siteId, now);
        assertThat(schedules.forSite(siteId)).hasSize(3);

        // Idempotency is per scope, not per site: a missing DEEP row comes back alone.
        jdbc.update("DELETE FROM schedule WHERE site_id = ? AND scope = 'DEEP'", siteId);

        schedules.seedDefaults(siteId, now);

        List<Schedule> refilled = schedules.forSite(siteId);
        assertThat(refilled).hasSize(3);
        assertThat(refilled).extracting(Schedule::scope)
                .containsExactlyInAnyOrder(RunScope.PULSE, RunScope.FULL, RunScope.DEEP);
    }

    @Test
    void seedMissingDefaultsSeedsEverySiteWithNoRowsAndCountsThem() {
        // Other test classes commit sites that have no schedule rows; this is a global count, so
        // start from an empty site table (cascade clears every child) to keep it deterministic.
        jdbc.update("DELETE FROM site");
        long unseeded = newSite("d.example.com");
        long seeded = newSite("e.example.com");
        Instant now = Instant.now();
        schedules.seedDefaults(seeded, now);

        assertThat(schedules.seedMissingDefaults(now)).isEqualTo(1);
        assertThat(schedules.seedMissingDefaults(now)).isEqualTo(0);

        assertThat(schedules.forSite(unseeded)).hasSize(3);
        assertThat(schedules.forSite(seeded)).hasSize(3);
    }

    @Test
    void updateRejectsAnInvalidCronAndLeavesTheRowUnchanged() {
        long siteId = newSite("f.example.com");
        Instant now = Instant.now();
        schedules.seedDefaults(siteId, now);
        Schedule pulse = single(siteId, RunScope.PULSE);

        assertThatThrownBy(() -> schedules.update(pulse.id(), "nicht-ein-cron", "Europe/Berlin", true, now))
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(single(siteId, RunScope.PULSE)).isEqualTo(pulse);
    }

    @Test
    void updateRecomputesNextFireAtFromNowNotTheStoredValue() {
        long siteId = newSite("g.example.com");
        // A Wednesday, so the full tier's next Sunday differs from a daily 03:00.
        Instant now = Instant.parse("2026-08-26T10:00:00Z");
        schedules.seedDefaults(siteId, now);
        Schedule full = single(siteId, RunScope.FULL);
        Instant oldNext = full.nextFireAt();

        schedules.update(full.id(), "0 0 3 * * *", "Europe/Berlin", true, now);

        Schedule after = single(siteId, RunScope.FULL);
        assertThat(after.nextFireAt()).isNotEqualTo(oldNext);
        Instant expected = CronSchedule.parse("0 0 3 * * *", "Europe/Berlin")
                .orElseThrow().nextAfter(now);
        assertThat(after.nextFireAt()).isEqualTo(expected);
    }

    @Test
    void updateWithEnabledFalseKeepsTheRow() {
        long siteId = newSite("h.example.com");
        Instant now = Instant.now();
        schedules.seedDefaults(siteId, now);
        Schedule deep = single(siteId, RunScope.DEEP);

        schedules.update(deep.id(), deep.cron(), deep.timezone(), false, now);

        Schedule after = single(siteId, RunScope.DEEP);
        assertThat(after.id()).isEqualTo(deep.id());
        assertThat(after.enabled()).isFalse();
    }

    private Schedule single(long siteId, RunScope scope) {
        return schedules.forSite(siteId).stream()
                .filter(row -> row.scope() == scope)
                .findFirst().orElseThrow();
    }

    private long newSite(String host) {
        return sites.create(new SiteForm("Kunde", "https://" + host + "/", 300, 5,
                Duration.ofMinutes(30), List.of(), List.of(), true, null));
    }
}
