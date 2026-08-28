package dev.hendrikhoemberg.webtesthelper.recorder;

import java.util.Objects;

/**
 * Translates click and input coordinates from the HTML canvas to viewport coordinates (§10.1).
 *
 * <p>Translation algorithm:
 * <pre>
 * frameX    = canvasX * (frameWidth / canvasWidth)
 * frameY    = canvasY * (frameHeight / canvasHeight)
 * viewportX = frameX / pageScaleFactor
 * viewportY = (frameY - offsetTop) / pageScaleFactor
 * </pre>
 *
 * <p><strong>No scroll offset is added, and that is measured, not assumed.</strong> A screencast
 * frame shows the visual viewport, and {@code Input.dispatchMouseEvent} consumes viewport-relative
 * CSS pixels — the two spaces are the same one. Against {@code reise/lang.html} scrolled to
 * y=1500, dispatching at the link's viewport position hit it and dispatching at its document
 * position (viewport + scrollY) hit nothing at all, silently. The plan's original formula added
 * {@code scrollOffsetX/Y}; it survived review because every test until now ran at scroll 0.
 *
 * <p>{@code offsetTop} is subtracted <em>before</em> the {@code pageScaleFactor} divide. The two
 * orders differ whenever both are non-zero. Both are inert on a desktop viewport — offsetTop is
 * browser chrome under mobile emulation and pageScaleFactor is pinch zoom, neither of which §10.5's
 * single-tab desktop recorder can produce — so they are carried faithfully rather than verified.
 */
public final class InputTranslator {

    private InputTranslator() {
    }

    /**
     * Translates a canvas coordinate point (canvasX, canvasY) into viewport coordinates.
     *
     * @param canvasX  horizontal coordinate on the canvas in CSS pixels
     * @param canvasY  vertical coordinate on the canvas in CSS pixels
     * @param geometry geometry of the canvas and the frame it is displaying
     * @return translated {@link ViewportPoint}
     */
    public static ViewportPoint toViewport(double canvasX, double canvasY, CanvasGeometry geometry) {
        Objects.requireNonNull(geometry, "geometry must not be null");

        double frameX = canvasX * ((double) geometry.frameWidth() / geometry.canvasWidth());
        double frameY = canvasY * ((double) geometry.frameHeight() / geometry.canvasHeight());

        double pageScale = geometry.pageScaleFactor() != 0.0 ? geometry.pageScaleFactor() : 1.0;

        return new ViewportPoint(frameX / pageScale, (frameY - geometry.offsetTop()) / pageScale);
    }
}
