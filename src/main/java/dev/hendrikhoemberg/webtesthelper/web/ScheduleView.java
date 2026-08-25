package dev.hendrikhoemberg.webtesthelper.web;

import dev.hendrikhoemberg.webtesthelper.model.RunScope;
import dev.hendrikhoemberg.webtesthelper.scheduling.Schedule;
import dev.hendrikhoemberg.webtesthelper.scheduling.TierCron;

import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * One tier of a site's schedule, shaped for a non-technical reader (spec 13.1). The template draws
 * the plain-German sentence from {@code timeOfDay}; the raw cron survives only behind the
 * Erweitert disclosure.
 *
 * @param scope       the tier
 * @param cron        the stored cron expression
 * @param timezone    the timezone the cron is evaluated in
 * @param enabled     whether the tier runs at all
 * @param timeOfDay   the time of day the cron encodes, or null when the cron does not fit the
 *                    tier's shape (a hand-written expression)
 * @param lastFiredAt when the tier last fired, or null if never
 * @param nextFireAt  the next occurrence, or null if the cron never fires again
 */
public record ScheduleView(RunScope scope, String cron, String timezone, boolean enabled,
                           LocalTime timeOfDay, Instant lastFiredAt, Instant nextFireAt) {

    /** The next fire in the schedule's own zone, for correct {@code #temporals} formatting. */
    public ZonedDateTime nextFireZoned() {
        return nextFireAt == null ? null : nextFireAt.atZone(ZoneId.of(timezone));
    }

    /** The last fire in the schedule's own zone, for correct {@code #temporals} formatting. */
    public ZonedDateTime lastFireZoned() {
        return lastFiredAt == null ? null : lastFiredAt.atZone(ZoneId.of(timezone));
    }

    /** The display map per tier, keyed by scope, so a template can look up a tier's fire times. */
    public static Map<RunScope, ScheduleView> detailByScope(List<Schedule> schedules) {
        Map<RunScope, ScheduleView> map = new EnumMap<>(RunScope.class);
        for (Schedule schedule : schedules) {
            map.put(schedule.scope(), new ScheduleView(schedule.scope(), schedule.cron(),
                    schedule.timezone(), schedule.enabled(),
                    TierCron.timeOfDay(schedule.scope(), schedule.cron()).orElse(null),
                    schedule.lastFiredAt(), schedule.nextFireAt()));
        }
        return map;
    }
}
