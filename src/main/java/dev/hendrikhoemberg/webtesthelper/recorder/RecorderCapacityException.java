package dev.hendrikhoemberg.webtesthelper.recorder;

/**
 * Thrown when every recorder worker is already allocated (§10.1: two concurrent sessions maximum).
 *
 * <p>Distinct from the {@link IllegalStateException} a failed browser start produces, because the
 * two need opposite advice: waiting for a colleague to finish fixes the first and never fixes the
 * second (§13.4).
 *
 * @param limit the configured concurrent-session limit, for the message shown to the user
 */
public class RecorderCapacityException extends RuntimeException {

    private final int limit;

    public RecorderCapacityException(int limit) {
        super("Alle " + limit + " Aufnahme-Browser sind belegt");
        this.limit = limit;
    }

    public int limit() {
        return limit;
    }
}
