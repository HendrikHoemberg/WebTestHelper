package dev.hendrikhoemberg.webtesthelper.scheduling;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class CronScheduleTest {

    private CronSchedule berlinDaily() {
        return CronSchedule.parse("0 0 3 * * *", "Europe/Berlin").orElseThrow();
    }

    @Test
    void dailyAtThreeBerlinInAugustIsOneUtcWhenCest() {
        assertThat(berlinDaily().nextAfter(Instant.parse("2026-08-25T12:00:00Z")))
                .isEqualTo(Instant.parse("2026-08-26T01:00:00Z"));
    }

    @Test
    void dailyAtThreeOnTheDstTransitionDayResolvesToCet() {
        assertThat(berlinDaily().nextAfter(Instant.parse("2026-10-24T12:00:00Z")))
                .isEqualTo(Instant.parse("2026-10-25T02:00:00Z"));
    }

    @Test
    void dailyAtThreeAfterBerlinLeavesCestIsTwoUtc() {
        assertThat(berlinDaily().nextAfter(Instant.parse("2026-10-26T12:00:00Z")))
                .isEqualTo(Instant.parse("2026-10-27T02:00:00Z"));
    }

    @Test
    void weeklySundayAtThreeRunsOnTheComingSunday() {
        CronSchedule weekly = CronSchedule.parse("0 0 3 * * SUN", "Europe/Berlin").orElseThrow();
        assertThat(weekly.nextAfter(Instant.parse("2026-08-26T12:00:00Z")))
                .isEqualTo(Instant.parse("2026-08-30T01:00:00Z"));
    }

    @Test
    void monthlyFirstAtThreeRunsOnTheNextMonthsFirst() {
        CronSchedule monthly = CronSchedule.parse("0 0 3 1 * *", "Europe/Berlin").orElseThrow();
        assertThat(monthly.nextAfter(Instant.parse("2026-08-25T12:00:00Z")))
                .isEqualTo(Instant.parse("2026-09-01T01:00:00Z"));
    }

    @Test
    void parseRejectsAnUnknownCronExpressionWithoutThrowing() {
        assertThat(CronSchedule.parse("keine ahnung", "Europe/Berlin")).isEmpty();
    }

    @Test
    void parseRejectsAnUnknownTimezoneWithoutThrowing() {
        assertThat(CronSchedule.parse("0 0 3 * * *", "Mars/Olympus")).isEmpty();
    }

    @Test
    void nextAfterIsPureAndHasNoMemoryOfItsOwn() {
        CronSchedule schedule = berlinDaily();
        Instant stale = Instant.parse("2026-08-23T12:00:00Z");
        Instant later = Instant.parse("2026-08-28T12:00:00Z");
        assertThat(schedule.nextAfter(stale)).isEqualTo(Instant.parse("2026-08-24T01:00:00Z"));
        assertThat(schedule.nextAfter(stale)).isEqualTo(schedule.nextAfter(stale));
        assertThat(schedule.nextAfter(later)).isEqualTo(Instant.parse("2026-08-29T01:00:00Z"));
    }
}
