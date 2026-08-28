package dev.hendrikhoemberg.webtesthelper.recorder;

import java.util.Objects;

/**
 * Translates click and input coordinates from the HTML canvas to viewport coordinates (§10.1).
 *
 * <p>Translation algorithm:
 * <pre>
 * scaleX = (double) frameWidth / canvasWidth
 * scaleY = (double) frameHeight / canvasHeight
 * frameX = canvasX * scaleX
 * frameY = canvasY * scaleY
 * viewportX = frameX / pageScaleFactor + scrollOffsetX
 * viewportY = (frameY - offsetTop) / pageScaleFactor + scrollOffsetY
 * </pre>
 */
public final class InputTranslator {

    private InputTranslator() {
    }

    /**
     * Translates a canvas coordinate point (canvasX, canvasY) into viewport coordinates.
     *
     * @param canvasX  horizontal coordinate on the canvas in CSS pixels
     * @param canvasY  vertical coordinate on the canvas in CSS pixels
     * @param geometry geometry of the canvas, frame, and scroll state
     * @return translated {@link ViewportPoint}
     */
    public static ViewportPoint toViewport(double canvasX, double canvasY, CanvasGeometry geometry) {
        Objects.requireNonNull(geometry, "geometry must not be null");

        double scaleX = (double) geometry.frameWidth() / geometry.canvasWidth();
        double scaleY = (double) geometry.frameHeight() / geometry.canvasHeight();

        double frameX = canvasX * scaleX;
        double frameY = canvasY * scaleY;

        double pageScale = geometry.pageScaleFactor() != 0.0 ? geometry.pageScaleFactor() : 1.0;

        double viewportX = frameX / pageScale + geometry.scrollOffsetX();
        double viewportY = (frameY - geometry.offsetTop()) / pageScale + geometry.scrollOffsetY();

        return new ViewportPoint(viewportX, viewportY);
    }
}
