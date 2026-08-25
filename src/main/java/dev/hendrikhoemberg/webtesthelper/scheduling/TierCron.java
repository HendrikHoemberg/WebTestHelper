package dev.hendrikhoemberg.webtesthelper.scheduling;

import dev.hendrikhoemberg.webtesthelper.model.RunScope;

import java.time.LocalTime;
import java.util.Optional;

/**
 * The tier's shape fixed, the time of day supplied by the form — the two together determine the
 * cron expression a schedule stores. §13.1 keeps the ugly six-field string out of the colleague's
 * reading; this is the machinery behind the plain-German "Täglich um {0} Uhr".
 *
 * <p>The tier pins the calendar part, the form supplies only the time. PULSE is daily, FULL is
 * weekly on Sunday, DEEP is monthly on the first (spec 9). {@link #compose} is the forward
 * direction; {@link #timeOfDay} recognises the same shape so a hand-written cron that does not
 * fit is reported as such — the screen can surface the advanced field rather than silently
 * rewriting somebody's expression on the next save.
 */
public final class TierCron {

    private TierCron() {
    }

    /** Seconds has no place in a tier's shape: the three defaults all fire on the second zero. */
    private static final String SECONDS = "0";

    /** Composes the cron for a tier at {@code time}: the tier fixes the calendar, the form the time. */
    public static String compose(RunScope scope, LocalTime time) {
        return SECONDS + " " + time.getMinute() + " " + time.getHour()
                + " " + calendarPart(scope);
    }

    /**
     * Recognises a cron that is exactly {@link #compose} for this tier and returns the time of day
     * it encodes. Returns {@link Optional#empty()} for a cron that does not fit the shape — an
     * unparseable expression, a wrong day part, or a non-zero second field — so the caller can
     * treat it as an advanced custom schedule rather than a simple time.
     */
    public static Optional<LocalTime> timeOfDay(RunScope scope, String cron) {
        if (cron == null) {
            return Optional.empty();
        }
        String[] parts = cron.strip().split("\\s+");
        if (parts.length != 6 || !parts[0].equals(SECONDS) || !matches(scope, parts)) {
            return Optional.empty();
        }
        try {
            return Optional.of(LocalTime.of(Integer.parseInt(parts[2]), Integer.parseInt(parts[1])));
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    /** The day-of-month/month/day-of-week triple the tier fixes. */
    private static String calendarPart(RunScope scope) {
        return switch (scope) {
            case PULSE -> "* * *";
            case FULL -> "* * SUN";
            case DEEP -> "1 * *";
        };
    }

    private static boolean matches(RunScope scope, String[] parts) {
        return switch (scope) {
            case PULSE -> parts[3].equals("*") && parts[4].equals("*") && parts[5].equals("*");
            case FULL -> parts[3].equals("*") && parts[4].equals("*") && parts[5].equals("SUN");
            case DEEP -> parts[3].equals("1") && parts[4].equals("*") && parts[5].equals("*");
        };
    }
}
