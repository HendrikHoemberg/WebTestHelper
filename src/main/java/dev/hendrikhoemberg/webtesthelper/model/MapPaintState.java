package dev.hendrikhoemberg.webtesthelper.model;

/**
 * Whether an embedded frame's map canvas actually painted (spec 7.1's Maps case).
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
