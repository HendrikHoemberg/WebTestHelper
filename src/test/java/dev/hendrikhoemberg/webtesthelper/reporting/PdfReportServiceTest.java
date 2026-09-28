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

    @Test
    void generatePdf_rendersDruckTemplateWithLenientLinkBuilderAndInlinedCss() {
        org.springframework.context.support.ResourceBundleMessageSource messageSource = new org.springframework.context.support.ResourceBundleMessageSource();
        messageSource.setBasename("messages");
        messageSource.setDefaultEncoding("UTF-8");

        org.thymeleaf.templateresolver.ClassLoaderTemplateResolver resolver = new org.thymeleaf.templateresolver.ClassLoaderTemplateResolver();
        resolver.setPrefix("templates/");
        resolver.setSuffix(".html");
        resolver.setTemplateMode(org.thymeleaf.templatemode.TemplateMode.HTML);
        resolver.setCharacterEncoding(java.nio.charset.StandardCharsets.UTF_8.name());

        org.thymeleaf.spring6.SpringTemplateEngine templateEngine = new org.thymeleaf.spring6.SpringTemplateEngine();
        templateEngine.setTemplateResolver(resolver);
        templateEngine.setMessageSource(messageSource);

        PdfRenderer renderer = mock(PdfRenderer.class);
        org.mockito.ArgumentCaptor<String> htmlCaptor = org.mockito.ArgumentCaptor.forClass(String.class);
        byte[] fakePdf = "%PDF-1.4 test".getBytes();
        when(renderer.render(htmlCaptor.capture())).thenReturn(fakePdf);

        // Constructor automatically attaches LenientStandardLinkBuilder to uninitialized templateEngine
        PdfReportService service = new PdfReportService(templateEngine, renderer);

        dev.hendrikhoemberg.webtesthelper.runner.RunSummary summary = new dev.hendrikhoemberg.webtesthelper.runner.RunSummary(
                11L,
                42L,
                dev.hendrikhoemberg.webtesthelper.model.RunStatus.COMPLETED,
                dev.hendrikhoemberg.webtesthelper.model.RunTrigger.MANUAL,
                dev.hendrikhoemberg.webtesthelper.model.RunScope.FULL,
                java.time.Instant.parse("2026-08-25T10:00:00Z"),
                java.time.Instant.parse("2026-08-25T10:00:05Z"),
                java.time.Instant.parse("2026-08-25T10:02:30Z"),
                85, 2, 4, 2, 1, false, null, false, null, java.util.Set.of()
        );
        dev.hendrikhoemberg.webtesthelper.model.SiteContext site = new dev.hendrikhoemberg.webtesthelper.model.SiteContext(
                42L,
                "Acme Shop",
                dev.hendrikhoemberg.webtesthelper.model.UrlNormalizer.normalize("https://acme.example.com/").orElseThrow(),
                new dev.hendrikhoemberg.webtesthelper.model.CrawlBudget(120, 3, java.time.Duration.ofMinutes(15)),
                java.util.List.of(), java.util.List.of(), java.util.List.of(), true, null, java.util.Map.of()
        );
        dev.hendrikhoemberg.webtesthelper.findings.RunDiff diff = new dev.hendrikhoemberg.webtesthelper.findings.RunDiff(11L, java.util.Map.of());
        FindingView viewNew = new FindingView(1L, "Tote Links", "Link tot", "Korrigieren",
                "https://acme.example.com/a", false, 1, dev.hendrikhoemberg.webtesthelper.model.Severity.ERROR, dev.hendrikhoemberg.webtesthelper.model.TriageStatus.UNTRIAGED);
        java.util.Map<dev.hendrikhoemberg.webtesthelper.findings.ReportSection, java.util.List<FindingView>> sections = java.util.Map.of(
                dev.hendrikhoemberg.webtesthelper.findings.ReportSection.NEW, java.util.List.of(viewNew)
        );

        java.util.Map<String, Object> variables = java.util.Map.of(
                "run", summary,
                "site", site,
                "diff", diff,
                "sections", sections
        );

        byte[] pdf = service.generatePdf("laeufe/druck", variables, java.util.Locale.GERMAN);
        assertThat(pdf).isEqualTo(fakePdf);

        String capturedHtml = htmlCaptor.getValue();
        assertThat(capturedHtml).contains("href=\"/css/app.css\"");
        assertThat(capturedHtml).contains("href=\"/laeufe/11\"");
        assertThat(capturedHtml).contains("href=\"/laeufe/11/bericht/pdf\"");
        assertThat(capturedHtml).contains("<style>");
        assertThat(capturedHtml).contains("--bg-canvas");
        assertThat(capturedHtml).contains("Tote Links");
    }

    @Test
    @org.junit.jupiter.api.Tag("browser")
    void generatePdf_realBrowserProducesValidPdf() {
        org.springframework.context.support.ResourceBundleMessageSource messageSource = new org.springframework.context.support.ResourceBundleMessageSource();
        messageSource.setBasename("messages");
        messageSource.setDefaultEncoding("UTF-8");

        org.thymeleaf.templateresolver.ClassLoaderTemplateResolver resolver = new org.thymeleaf.templateresolver.ClassLoaderTemplateResolver();
        resolver.setPrefix("templates/");
        resolver.setSuffix(".html");
        resolver.setTemplateMode(org.thymeleaf.templatemode.TemplateMode.HTML);
        resolver.setCharacterEncoding(java.nio.charset.StandardCharsets.UTF_8.name());

        org.thymeleaf.spring6.SpringTemplateEngine templateEngine = new org.thymeleaf.spring6.SpringTemplateEngine();
        templateEngine.setTemplateResolver(resolver);
        templateEngine.setMessageSource(messageSource);

        PdfRenderer renderer = new PdfRenderer(true);
        PdfReportService service = new PdfReportService(templateEngine, renderer);

        dev.hendrikhoemberg.webtesthelper.runner.RunSummary summary = new dev.hendrikhoemberg.webtesthelper.runner.RunSummary(
                11L,
                42L,
                dev.hendrikhoemberg.webtesthelper.model.RunStatus.COMPLETED,
                dev.hendrikhoemberg.webtesthelper.model.RunTrigger.MANUAL,
                dev.hendrikhoemberg.webtesthelper.model.RunScope.FULL,
                java.time.Instant.parse("2026-08-25T10:00:00Z"),
                java.time.Instant.parse("2026-08-25T10:00:05Z"),
                java.time.Instant.parse("2026-08-25T10:02:30Z"),
                85, 2, 4, 2, 1, false, null, false, null, java.util.Set.of()
        );
        dev.hendrikhoemberg.webtesthelper.model.SiteContext site = new dev.hendrikhoemberg.webtesthelper.model.SiteContext(
                42L,
                "Acme Shop",
                dev.hendrikhoemberg.webtesthelper.model.UrlNormalizer.normalize("https://acme.example.com/").orElseThrow(),
                new dev.hendrikhoemberg.webtesthelper.model.CrawlBudget(120, 3, java.time.Duration.ofMinutes(15)),
                java.util.List.of(), java.util.List.of(), java.util.List.of(), true, null, java.util.Map.of()
        );
        dev.hendrikhoemberg.webtesthelper.findings.RunDiff diff = new dev.hendrikhoemberg.webtesthelper.findings.RunDiff(11L, java.util.Map.of());
        FindingView viewNew = new FindingView(1L, "Tote Links", "Link tot", "Korrigieren",
                "https://acme.example.com/a", false, 1, dev.hendrikhoemberg.webtesthelper.model.Severity.ERROR, dev.hendrikhoemberg.webtesthelper.model.TriageStatus.UNTRIAGED);
        java.util.Map<dev.hendrikhoemberg.webtesthelper.findings.ReportSection, java.util.List<FindingView>> sections = java.util.Map.of(
                dev.hendrikhoemberg.webtesthelper.findings.ReportSection.NEW, java.util.List.of(viewNew)
        );

        java.util.Map<String, Object> variables = java.util.Map.of(
                "run", summary,
                "site", site,
                "diff", diff,
                "sections", sections
        );

        try {
            byte[] pdf = service.generatePdf("laeufe/druck", variables, java.util.Locale.GERMAN);
            assertThat(pdf).isNotEmpty();
            assertThat(new String(java.util.Arrays.copyOfRange(pdf, 0, 4), java.nio.charset.StandardCharsets.US_ASCII))
                    .isEqualTo("%PDF");
        } finally {
            renderer.close();
        }
    }
}

