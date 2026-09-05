package dev.hendrikhoemberg.webtesthelper.reporting;

import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class PdfRendererTest {

    @Test
    void launchOptions_setsHeadlessAndNoSandbox() {
        assertThat(PdfRenderer.launchOptions(true, false).headless).isTrue();
        assertThat(PdfRenderer.launchOptions(false, false).headless).isFalse();
        assertThat(PdfRenderer.launchOptions(true, true).args).contains("--no-sandbox");
        assertThat(PdfRenderer.launchOptions(true, false).args).isNullOrEmpty();
    }

    @Test
    void closeMethodHasPreDestroyAnnotation() throws NoSuchMethodException {
        assertThat(PdfRenderer.class.getMethod("close").isAnnotationPresent(jakarta.annotation.PreDestroy.class))
                .isTrue();
    }

    @Test
    void close_terminatesCleanlyWhenUnused() {
        PdfRenderer renderer = new PdfRenderer(false);
        renderer.close(1, TimeUnit.SECONDS);
    }
}
