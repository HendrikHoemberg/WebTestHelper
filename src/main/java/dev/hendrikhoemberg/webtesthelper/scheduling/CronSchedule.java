package dev.hendrikhoemberg.webtesthelper.scheduling;

import org.springframework.scheduling.support.CronExpression;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Optional;

/**
 * A site's recurring run schedule: a cron expression plus the timezone it is evaluated in.
 *
 * <p>The timezone is part of the schedule rather than a process-wide default because "03:00
 * local" is a different UTC instant on either side of a daylight-savings change (spec 9, D40).
 * Storing a schedule as a UTC hour would drift by an hour twice a year.
 *
 * <p>Pure domain type: no database, no Spring container, no clock. The "fires once, then
 * advances past now" rule (D40) belongs to the caller, which always passes {@code now} — see
 * {@link #nextAfter(Instant)}.
 *
 * @param expression the cron expression defining the local firing times
 * @param zone       the timezone the expression is evaluated in
 */
public record CronSchedule(CronExpression expression, ZoneId zone) {

    /**
     * Parses a cron expression and a timezone. Returns {@link Optional#empty()} instead of
     * throwing for an invalid cron or unknown zone, so that one bad row does not stop the tick
     * for every other site.
     */
    public static Optional<CronSchedule> parse(String cron, String timezone) {
        try {
            return Optional.of(new CronSchedule(CronExpression.parse(cron), ZoneId.of(timezone)));
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    /** The next occurrence strictly after {@code instant}, in this schedule's own zone. */
    Instant nextAfter(Instant instant) {
        ZonedDateTime next = expression.next(instant.atZone(zone));
        // next() returns null when the expression has no further occurrence (a fixed date in the
        // past). Treat that as "never again" rather than as an error: the row stays put and the
        // partial index stops matching it.
        return next == null ? null : next.toInstant();
    }
}
