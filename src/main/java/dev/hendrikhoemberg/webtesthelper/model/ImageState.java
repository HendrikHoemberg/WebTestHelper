package dev.hendrikhoemberg.webtesthelper.model;

/**
 * The decode outcome of an image as determined by the extraction probe.
 *
 * <p>{@code DECODED} means the browser loaded and decoded the image to non-zero dimensions.
 * {@code BROKEN} means the load failed ({@code onerror}). {@code UNKNOWN} means the probe
 * timed out before a definitive answer — the image may be healthy but slow, or lazy-loaded
 * and never triggered. Reporting UNKNOWN as broken would be a false positive.
 */
public enum ImageState {
    DECODED,
    BROKEN,
    UNKNOWN;

    /**
     * Parse the state string from extract.js. Falls back to {@code UNKNOWN} for any
     * unrecognised value, so a future script change cannot crash the Java side.
     */
    public static ImageState parse(String raw) {
        if (raw == null) return UNKNOWN;
        return switch (raw) {
            case "decoded" -> DECODED;
            case "broken" -> BROKEN;
            default -> UNKNOWN;
        };
    }
}
