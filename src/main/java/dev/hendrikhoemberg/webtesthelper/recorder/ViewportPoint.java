package dev.hendrikhoemberg.webtesthelper.recorder;

/**
 * A coordinate point in the target browser viewport in CSS pixels (§10.1).
 *
 * @param x horizontal coordinate in viewport CSS pixels
 * @param y vertical coordinate in viewport CSS pixels
 */
public record ViewportPoint(double x, double y) {
}
