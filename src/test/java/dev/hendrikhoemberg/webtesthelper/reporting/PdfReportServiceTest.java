package dev.hendrikhoemberg.webtesthelper.reporting;

import org.junit.jupiter.api.Test;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PdfReportServiceTest {

    @Test
    void generatePdf_delegatesToPdfRenderer() {
        TemplateEngine engine = mock(TemplateEngine.class);
        PdfRenderer renderer = mock(PdfRenderer.class);
        byte[] fakePdf = "%PDF-1.4 mock".getBytes();
        String html = "<html><body><h1>Test Bericht</h1></body></html>";

        when(engine.process(eq("test-template"), any(Context.class)))
                .thenReturn(html);
        when(renderer.render(html)).thenReturn(fakePdf);

        PdfReportService service = new PdfReportService(engine, renderer);
        byte[] pdf = service.generatePdf("test-template", Map.of("title", "Test"));

        assertThat(pdf).isEqualTo(fakePdf);
        verify(renderer).render(html);
    }
}
