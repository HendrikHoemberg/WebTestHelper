package dev.hendrikhoemberg.webtesthelper.recorder;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.data.Offset.offset;

class InputTranslatorTest {

    private static final double EPSILON = 0.0001;

    @Test
    void clickAtCanvasCenterMapsToViewportCenterAtScaleOne() {
        // Given a 1280x720 canvas displaying a 1280x720 frame at 1:1 scale with no offset
        CanvasGeometry geometry = new CanvasGeometry(1280, 720, 1280, 720, 1.0, 0.0);

        // When clicking at the center of the canvas
        ViewportPoint point = InputTranslator.toViewport(640.0, 360.0, geometry);

        // Then it maps exactly to the viewport center (640, 360)
        assertThat(point.x()).isCloseTo(640.0, offset(EPSILON));
        assertThat(point.y()).isCloseTo(360.0, offset(EPSILON));
    }

    @Test
    void canvasDisplayedAtHalfFrameSizeDoublesCoordinates() {
        // Given a 640x360 canvas displaying a 1280x720 frame (scale 2.0)
        CanvasGeometry geometry = new CanvasGeometry(640, 360, 1280, 720, 1.0, 0.0);

        // When clicking at (100, 50) on canvas
        ViewportPoint point = InputTranslator.toViewport(100.0, 50.0, geometry);

        // Then frame coordinates are doubled to (200, 100)
        assertThat(point.x()).isCloseTo(200.0, offset(EPSILON));
        assertThat(point.y()).isCloseTo(100.0, offset(EPSILON));
    }

    @Test
    void offsetTopIsSubtractedBeforeScaleDivideNotAfter() {
        // Given frame=1280x720, canvas=1280x720, pageScaleFactor=2.0, offsetTop=40.0
        // With canvasY = 240.0:
        // Correct order: (240.0 - 40.0) / 2.0 = 100.0
        // Wrong order:   240.0 / 2.0 - 40.0   =  80.0
        CanvasGeometry geometry = new CanvasGeometry(1280, 720, 1280, 720, 2.0, 40.0);

        ViewportPoint point = InputTranslator.toViewport(500.0, 240.0, geometry);

        assertThat(point.x()).isCloseTo(250.0, offset(EPSILON));
        assertThat(point.y()).isCloseTo(100.0, offset(EPSILON));
    }

    @Test
    void arbitraryAspectScalingAndPageScaleCombined() {
        // canvas: 800x450, frame: 1600x900 (2x CSS display scale), pageScaleFactor 1.5, offsetTop 30
        // frameX    = 300 * (1600 / 800) = 600      -> viewportX = 600 / 1.5       = 400.0
        // frameY    = 150 * (900 / 450)  = 300      -> viewportY = (300 - 30) / 1.5 = 180.0
        CanvasGeometry geometry = new CanvasGeometry(800, 450, 1600, 900, 1.5, 30.0);

        ViewportPoint point = InputTranslator.toViewport(300.0, 150.0, geometry);

        assertThat(point.x()).isCloseTo(400.0, offset(EPSILON));
        assertThat(point.y()).isCloseTo(180.0, offset(EPSILON));
    }

    @Test
    void aScrolledPageDoesNotShiftTheResult() {
        // A screencast frame shows the visual viewport, and Input.dispatchMouseEvent consumes
        // viewport coordinates, so how far the page has scrolled cannot enter the translation.
        // CanvasGeometry therefore has nowhere to put a scroll offset, and a click at a given
        // canvas point maps to the same viewport point whatever the page's scroll position is.
        CanvasGeometry geometry = new CanvasGeometry(1280, 720, 1280, 720, 1.0, 0.0);

        ViewportPoint point = InputTranslator.toViewport(200.0, 100.0, geometry);

        assertThat(point.x()).isCloseTo(200.0, offset(EPSILON));
        assertThat(point.y()).isCloseTo(100.0, offset(EPSILON));
    }

    @Test
    void aZeroPageScaleFactorIsTreatedAsOneRatherThanDividingByZero() {
        CanvasGeometry geometry = new CanvasGeometry(1280, 720, 1280, 720, 0.0, 0.0);

        ViewportPoint point = InputTranslator.toViewport(200.0, 100.0, geometry);

        assertThat(point.x()).isCloseTo(200.0, offset(EPSILON));
        assertThat(point.y()).isCloseTo(100.0, offset(EPSILON));
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1, -500})
    void invalidCanvasDimensionsThrowIllegalArgumentException(int invalidDimension) {
        assertThatThrownBy(() -> new CanvasGeometry(invalidDimension, 720, 1280, 720, 1.0, 0.0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CanvasGeometry(1280, invalidDimension, 1280, 720, 1.0, 0.0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void nullGeometryThrowsNullPointerException() {
        assertThatThrownBy(() -> InputTranslator.toViewport(100.0, 100.0, null))
                .isInstanceOf(NullPointerException.class);
    }
}
