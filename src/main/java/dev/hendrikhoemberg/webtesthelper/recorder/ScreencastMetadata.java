package dev.hendrikhoemberg.webtesthelper.recorder;

/**
 * Metadata delivered alongside a screencast frame (§10.1, D110).
 *
 * @param offsetTop       top offset of the frame in CSS pixels
 * @param pageScaleFactor page scale factor applied by the browser
 * @param deviceWidth     width of the visual viewport in CSS pixels — the space
 *                        {@code Input.dispatchMouseEvent} coordinates live in, which is what
 *                        makes it the right divisor in {@link InputTranslator}
 * @param deviceHeight    height of the visual viewport in CSS pixels
 * @param scrollOffsetX   horizontal scroll offset in CSS pixels
 * @param scrollOffsetY   vertical scroll offset in CSS pixels
 * @param timestamp       screencast frame timestamp (seconds)
 */
public record ScreencastMetadata(
        double offsetTop,
        double pageScaleFactor,
        int deviceWidth,
        int deviceHeight,
        double scrollOffsetX,
        double scrollOffsetY,
        double timestamp
) {
}
