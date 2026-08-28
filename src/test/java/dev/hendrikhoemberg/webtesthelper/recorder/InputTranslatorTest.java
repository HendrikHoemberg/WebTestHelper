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
        // Given a 1280x720 canvas displaying a 1280x720 frame at 1:1 scale with no scroll or offset
        CanvasGeometry geometry = new CanvasGeometry(1280, 720, 1280, 720, 1.0, 0.0, 0.0, 0.0);

        // When clicking at the center of the canvas
        ViewportPoint point = InputTranslator.toViewport(640.0, 360.0, geometry);

        // Then it maps exactly to the viewport center (640, 360)
        assertThat(point.x()).isCloseTo(640.0, offset(EPSILON));
        assertThat(point.y()).isCloseTo(360.0, offset(EPSILON));
    }

    @Test
    void canvasDisplayedAtHalfFrameSizeDoublesCoordinates() {
        // Given a 640x360 canvas displaying a 1280x720 frame (scale 2.0)
        CanvasGeometry geometry = new CanvasGeometry(640, 360, 1280, 720, 1.0, 0.0, 0.0, 0.0);

        // When clicking at (100, 50) on canvas
        ViewportPoint point = InputTranslator.toViewport(100.0, 50.0, geometry);

        // Then frame coordinates are doubled to (200, 100)
        assertThat(point.x()).isCloseTo(200.0, offset(EPSILON));
        assertThat(point.y()).isCloseTo(100.0, offset(EPSILON));
    }

    @Test
    void nonZeroScrollOffsetYShiftsResultByExactlyThatMuch() {
        // Given 1:1 geometry with a vertical scroll offset of 180px
        CanvasGeometry geometry = new CanvasGeometry(1280, 720, 1280, 720, 1.0, 0.0, 0.0, 180.0);

        // When clicking at (200, 100)
        ViewportPoint point = InputTranslator.toViewport(200.0, 100.0, geometry);

        // Then Y is shifted by 180px -> 280px
        assertThat(point.x()).isCloseTo(200.0, offset(EPSILON));
        assertThat(point.y()).isCloseTo(280.0, offset(EPSILON));
    }

    @Test
    void nonZeroScrollOffsetXShiftsResultByExactlyThatMuch() {
        // Given 1:1 geometry with a horizontal scroll offset of 75px
        CanvasGeometry geometry = new CanvasGeometry(1280, 720, 1280, 720, 1.0, 0.0, 75.0, 0.0);

        // When clicking at (200, 100)
        ViewportPoint point = InputTranslator.toViewport(200.0, 100.0, geometry);

        // Then X is shifted by 75px -> 275px
        assertThat(point.x()).isCloseTo(275.0, offset(EPSILON));
        assertThat(point.y()).isCloseTo(100.0, offset(EPSILON));
    }

    @Test
    void offsetTopIsSubtractedBeforeScaleDivideNotAfter() {
        // Given frame=1280x720, canvas=1280x720, pageScaleFactor=2.0, offsetTop=40.0, scrollOffsetY=10.0
        // Formula: (frameY - offsetTop) / pageScaleFactor + scrollOffsetY
        // With canvasY = 240.0:
        // Correct order: (240.0 - 40.0) / 2.0 + 10.0 = 200.0 / 2.0 + 10.0 = 110.0
        // Wrong order:   240.0 / 2.0 - 40.0 + 10.0 = 120.0 - 40.0 + 10.0 = 90.0
        CanvasGeometry geometry = new CanvasGeometry(1280, 720, 1280, 720, 2.0, 40.0, 0.0, 10.0);

        ViewportPoint point = InputTranslator.toViewport(500.0, 240.0, geometry);

        assertThat(point.x()).isCloseTo(250.0, offset(EPSILON));
        assertThat(point.y()).isCloseTo(110.0, offset(EPSILON));
    }

    @Test
    void arbitraryAspectScalingAndHighDpiCombined() {
        // canvas: 800x450, frame: 1600x900 (2x CSS display scale)
        // pageScaleFactor: 1.5, offsetTop: 30.0, scrollOffsetX: 50.0, scrollOffsetY: 120.0
        CanvasGeometry geometry = new CanvasGeometry(800, 450, 1600, 900, 1.5, 30.0, 50.0, 120.0);

        // canvasX = 300, canvasY = 150
        // frameX = 300 * (1600 / 800) = 600
        // frameY = 150 * (900 / 450) = 300
        // viewportX = 600 / 1.5 + 50.0 = 400 + 50 = 450.0
        // viewportY = (300 - 30.0) / 1.5 + 120.0 = 270.0 / 1.5 + 120.0 = 180.0 + 120.0 = 300.0
        ViewportPoint point = InputTranslator.toViewport(300.0, 150.0, geometry);

        assertThat(point.x()).isCloseTo(450.0, offset(EPSILON));
        assertThat(point.y()).isCloseTo(300.0, offset(EPSILON));
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1, -500})
    void invalidCanvasDimensionsThrowIllegalArgumentException(int invalidDimension) {
        assertThatThrownBy(() -> new CanvasGeometry(invalidDimension, 720, 1280, 720, 1.0, 0.0, 0.0, 0.0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CanvasGeometry(1280, invalidDimension, 1280, 720, 1.0, 0.0, 0.0, 0.0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void nullGeometryThrowsNullPointerException() {
        assertThatThrownBy(() -> InputTranslator.toViewport(100.0, 100.0, null))
                .isInstanceOf(NullPointerException.class);
    }
}
