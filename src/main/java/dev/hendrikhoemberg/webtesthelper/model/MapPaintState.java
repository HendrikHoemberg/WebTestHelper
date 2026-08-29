package dev.hendrikhoemberg.webtesthelper.model;

/**
 * Whether an embedded frame's map canvas painted (spec 7.1's Maps case).
 *
 * <p>The signal is only "did anything paint": an opaque placeholder canvas painted by a failed
 * provider reads {@linkplain #PAINTED} even though the map is unusable. That case is covered by the
 * console-code path. {@link #NOT_PAINTED} is reserved for a canvas that was read and stayed blank
 * on both the first and a settle-confirmed second read, so a slow-but-healthy map is not misreport.
 *
 * <p>Only {@link #NOT_PAINTED} is a ground for a finding: the grey map the console scan misses.
 * {@link #UNKNOWN} is the absence of a signal — a cross-origin frame whose pixels cannot be read,
 * or a same-origin frame with no canvas at all — and never produces one.
 */
public enum MapPaintState {
    PAINTED,
    NOT_PAINTED,
    UNKNOWN
}
