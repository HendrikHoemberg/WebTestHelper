package dev.hendrikhoemberg.webtesthelper.reporting;

import dev.hendrikhoemberg.webtesthelper.crawler.BrowserPool;
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
    void generatePdf_delegatesToBrowserPoolSubmit() {
        TemplateEngine engine = mock(TemplateEngine.class);
        BrowserPool pool = mock(BrowserPool.class);
        byte[] fakePdf = "%PDF-1.4 mock".getBytes();

        when(engine.process(eq("test-template"), any(Context.class)))
                .thenReturn("<html><body><h1>Test Bericht</h1></body></html>");
        when(pool.submit(any())).thenReturn(fakePdf);

        PdfReportService service = new PdfReportService(engine, pool);
        byte[] pdf = service.generatePdf("test-template", Map.of("title", "Test"));

        assertThat(pdf).isEqualTo(fakePdf);
        verify(pool).submit(any());
    }
}
