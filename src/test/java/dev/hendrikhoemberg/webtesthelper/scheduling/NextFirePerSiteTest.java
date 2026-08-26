package dev.hendrikhoemberg.webtesthelper.scheduling;

import dev.hendrikhoemberg.webtesthelper.model.RunScope;
import dev.hendrikhoemberg.webtesthelper.scheduling.persistence.ScheduleEntity;
import dev.hendrikhoemberg.webtesthelper.scheduling.persistence.ScheduleRepository;
import dev.hendrikhoemberg.webtesthelper.support.AbstractPostgresTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class NextFirePerSiteTest extends AbstractPostgresTest {

    @Autowired
    ScheduleService schedules;

    @Autowired
    ScheduleRepository scheduleRepository;

    @Autowired
    JdbcTemplate jdbc;

    private long siteA;
    private long siteB;

    @BeforeEach
    void resetTables() {
        jdbc.update("DELETE FROM schedule");
        jdbc.update("DELETE FROM site");
        siteA = insertSite("https://a.example.com/");
        siteB = insertSite("https://b.example.com/");
    }

    private long insertSite(String baseUrl) {
        return jdbc.queryForObject(
                "INSERT INTO site (name, base_url) VALUES (?, ?) RETURNING id",
                Long.class, baseUrl, baseUrl);
    }

    private long insertSchedule(long siteId, RunScope scope, Instant nextFireAt, boolean enabled) {
        Instant now = Instant.now();
        ScheduleEntity row = new ScheduleEntity();
        row.setSiteId(siteId);
        row.setScope(scope);
        row.setCron(scope.defaultCron());
        row.setTimezone("Europe/Berlin");
        row.setEnabled(enabled);
        row.setNextFireAt(nextFireAt);
        row.setCreatedAt(now);
        row.setUpdatedAt(now);
        return scheduleRepository.save(row).getId();
    }

    @Test
    void picksTheEarliestFutureTierAcrossScopes() {
        insertSchedule(siteA, RunScope.PULSE, Instant.parse("2026-08-27T03:00:00Z"), true);
        insertSchedule(siteA, RunScope.FULL, Instant.parse("2026-08-30T03:00:00Z"), true);

        Map<Long, Schedule> bySite = schedules.nextFirePerSite();

        assertThat(bySite).containsOnlyKeys(siteA);
        assertThat(bySite.get(siteA).scope()).isEqualTo(RunScope.PULSE);
        assertThat(bySite.get(siteA).nextFireAt()).isEqualTo(Instant.parse("2026-08-27T03:00:00Z"));
    }

    @Test
    void disablingTheEarlierTierMovesTheSiteToTheNextRow() {
        long pulseId = insertSchedule(siteA, RunScope.PULSE, Instant.parse("2026-08-27T03:00:00Z"), true);
        insertSchedule(siteA, RunScope.FULL, Instant.parse("2026-08-30T03:00:00Z"), true);
        jdbc.update("UPDATE schedule SET enabled = FALSE WHERE id = ?", pulseId);

        assertThat(schedules.nextFirePerSite().get(siteA).scope()).isEqualTo(RunScope.FULL);
    }

    @Test
    void disablingTheSiteRemovesItFromTheMap() {
        insertSchedule(siteA, RunScope.PULSE, Instant.parse("2026-08-27T03:00:00Z"), true);
        jdbc.update("UPDATE site SET enabled = FALSE WHERE id = ?", siteA);

        assertThat(schedules.nextFirePerSite()).doesNotContainKey(siteA);
    }

    @Test
    void skipsARowWhoseNextFireAtIsNullRatherThanSortingItFirst() {
        insertSchedule(siteA, RunScope.PULSE, null, true);
        insertSchedule(siteA, RunScope.FULL, Instant.parse("2026-08-30T03:00:00Z"), true);
        insertSchedule(siteB, RunScope.PULSE, null, true);

        Map<Long, Schedule> bySite = schedules.nextFirePerSite();

        assertThat(bySite.get(siteA).scope()).isEqualTo(RunScope.FULL);
        assertThat(bySite).doesNotContainKey(siteB);
    }
}
