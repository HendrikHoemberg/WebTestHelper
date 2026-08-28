package dev.hendrikhoemberg.webtesthelper.recorder;

/**
 * Geometric properties of the canvas displaying a screencast frame (§10.1, §10.5).
 *
 * @param canvasWidth     width of the HTML {@code <canvas>} element in CSS pixels
 * @param canvasHeight    height of the HTML {@code <canvas>} element in CSS pixels
 * @param frameWidth      width of the delivered screencast frame in pixels
 * @param frameHeight     height of the delivered screencast frame in pixels
 * @param pageScaleFactor browser page scale factor (DPI / zoom)
 * @param offsetTop       screencast top offset in CSS pixels
 * @param scrollOffsetX   horizontal scroll offset in CSS pixels
 * @param scrollOffsetY   vertical scroll offset in CSS pixels
 */
public record CanvasGeometry(
        int canvasWidth,
        int canvasHeight,
        int frameWidth,
        int frameHeight,
        double pageScaleFactor,
        double offsetTop,
        double scrollOffsetX,
        double scrollOffsetY
) {
    public CanvasGeometry {
        if (canvasWidth <= 0 || canvasHeight <= 0) {
            throw new IllegalArgumentException("Canvas dimensions must be positive (canvasWidth=" + canvasWidth + ", canvasHeight=" + canvasHeight + ")");
        }
    }

    public CanvasGeometry(int canvasWidth, int canvasHeight, ScreencastMetadata metadata) {
        this(
                canvasWidth,
                canvasHeight,
                metadata != null ? metadata.deviceWidth() : canvasWidth,
                metadata != null ? metadata.deviceHeight() : canvasHeight,
                metadata != null ? metadata.pageScaleFactor() : 1.0,
                metadata != null ? metadata.offsetTop() : 0.0,
                metadata != null ? metadata.scrollOffsetX() : 0.0,
                metadata != null ? metadata.scrollOffsetY() : 0.0
        );
    }
}
