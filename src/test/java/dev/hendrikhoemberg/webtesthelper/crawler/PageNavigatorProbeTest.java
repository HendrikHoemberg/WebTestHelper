package dev.hendrikhoemberg.webtesthelper.crawler;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PageNavigatorProbeTest {

    private static String extractSource() throws Exception {
        return new ClassPathResource("crawler/extract.js")
                .getContentAsString(StandardCharsets.UTF_8);
    }

    @Test
    void theLoadedExtractJsEmbedsTheMapPaintProbeDefinition() throws Exception {
        // The probe's definition must be inlined at the marker. "mapPaintOf" alone is too weak:
        // extract.js already calls mapPaintOf in its frame loop, so a silent no-op replace would
        // still contain that word. Only this definition string comes from crawler/mapPaint.js.
        assertThat(PageNavigator.EXTRACT_JS).contains("const mapPaintOf");
    }

    @Test
    void inliningWithoutTheMarkerThrowsInsteadOfSilentlyNoOp() throws Exception {
        // If the marker is ever removed or renamed, the replacement must fail fast at startup
        // rather than no-oping and leaving every crawled page to throw a ReferenceError.
        String probeFree = extractSource().replace("// [[MAP_PAINT_PROBE]]", "");

        assertThatThrownBy(() -> PageNavigator.inlineProbe(probeFree))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("// [[MAP_PAINT_PROBE]]");
    }
}
