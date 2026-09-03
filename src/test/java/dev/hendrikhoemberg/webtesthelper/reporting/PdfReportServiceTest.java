package dev.hendrikhoemberg.webtesthelper.reporting;

import org.junit.jupiter.api.Test;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PdfReportServiceTest {

    @Test
    void generatePdf_rendersTemplateAndCallsPlaywrightPdf() {
        TemplateEngine engine = mock(TemplateEngine.class);
        when(engine.process(eq("test-template"), any(Context.class)))
                .thenReturn("<html><body><h1>Test Bericht</h1></body></html>");

        PdfReportService service = new PdfReportService(engine);
        byte[] pdf = service.generatePdf("test-template", Map.of("title", "Test"));

        assertThat(pdf).isNotNull();
        assertThat(pdf.length).isGreaterThan(0);
        // Standard PDF magic number %PDF-
        assertThat(new String(pdf, 0, 5)).isEqualTo("%PDF-");
    }
}
