package dev.hendrikhoemberg.webtesthelper.web;

import dev.hendrikhoemberg.webtesthelper.model.RunScope;
import dev.hendrikhoemberg.webtesthelper.scheduling.Schedule;
import dev.hendrikhoemberg.webtesthelper.scheduling.TierCron;

import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Backing form model for the schedules screen: one {@link Row} per tier. Editing a site and saving
 * unchanged keeps each tier's default cron, because the form only carries a time of day and the
 * tier supplies the calendar part (see {@link TierCron}).
 *
 * @param zeitplaene the rows, one per tier, in scope order
 */
public record ScheduleFormModel(List<Row> zeitplaene) {

    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm");

    /**
     * @param scope    the tier
     * @param zeit     the time of day as HH:mm; empty when the stored cron does not fit the tier
     * @param cron     the cron as supplied verbatim by the Erweitert field; empty for the common case
     * @param timezone the timezone the cron is evaluated in
     * @param enabled  whether the tier runs at all; an unchecked checkbox binds null
     */
    public record Row(
            RunScope scope,
            String zeit,
            String cron,
            String timezone,
            Boolean enabled) {
    }

    public static ScheduleFormModel of(List<Schedule> schedules) {
        List<Row> rows = schedules.stream()
                .map(schedule -> {
                    String zeit = TierCron.timeOfDay(schedule.scope(), schedule.cron())
                            .map(TIME_FORMAT::format)
                            .orElse("");
                    // Fitting cron: the time field is the whole story, and the raw cron stays out
                    // of the page (§13.1). Non-fitting: surface it so it survives a save verbatim.
                    String cronForForm = zeit.isEmpty() ? schedule.cron() : "";
                    return new Row(schedule.scope(), zeit, cronForForm,
                            schedule.timezone(), schedule.enabled());
                })
                .toList();
        return new ScheduleFormModel(rows);
    }
}
