package dev.hendrikhoemberg.webtesthelper.reporting;

import org.springframework.stereotype.Service;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;

import java.util.Locale;
import java.util.Map;
import java.util.Objects;

@Service
public class PdfReportService {

    private final TemplateEngine templateEngine;
    private final PdfRenderer pdfRenderer;

    public PdfReportService(TemplateEngine templateEngine, PdfRenderer pdfRenderer) {
        this.templateEngine = Objects.requireNonNull(templateEngine, "templateEngine must not be null");
        this.pdfRenderer = Objects.requireNonNull(pdfRenderer, "pdfRenderer must not be null");
    }

    public byte[] generatePdf(String templateName, Map<String, Object> variables) {
        return generatePdf(templateName, variables, Locale.GERMAN);
    }

    public byte[] generatePdf(String templateName, Map<String, Object> variables, Locale locale) {
        Context context = new Context(locale != null ? locale : Locale.GERMAN);
        if (variables != null) {
            context.setVariables(variables);
        }
        String html = templateEngine.process(templateName, context);
        return renderHtmlToPdf(html);
    }

    public byte[] renderHtmlToPdf(String html) {
        return pdfRenderer.render(html);
    }
}
