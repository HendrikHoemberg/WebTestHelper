package dev.hendrikhoemberg.webtesthelper.reporting;

import org.springframework.stereotype.Service;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

@Service
public class PdfReportService {

    private static final String APP_CSS;

    static {
        String css = "";
        try (var is = PdfReportService.class.getResourceAsStream("/static/css/app.css")) {
            if (is != null) {
                css = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            }
        } catch (IOException ignored) {
        }
        APP_CSS = css;
    }

    private final TemplateEngine templateEngine;
    private final PdfRenderer pdfRenderer;

    public PdfReportService(TemplateEngine templateEngine, PdfRenderer pdfRenderer) {
        this.templateEngine = Objects.requireNonNull(templateEngine, "templateEngine must not be null");
        this.pdfRenderer = Objects.requireNonNull(pdfRenderer, "pdfRenderer must not be null");
        if (!templateEngine.isInitialized()) {
            try {
                var builders = templateEngine.getLinkBuilders();
                boolean hasLenient = builders != null && builders.stream()
                        .anyMatch(lb -> lb instanceof LenientStandardLinkBuilder);
                if (!hasLenient) {
                    templateEngine.setLinkBuilder(new LenientStandardLinkBuilder());
                }
            } catch (Exception ignored) {
                // If templateEngine is a mock
            }
        }
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
        if (APP_CSS != null && !APP_CSS.isBlank() && html.contains("</head>")) {
            html = html.replace("</head>", "<style>\n" + APP_CSS + "\n</style>\n</head>");
        }
        return renderHtmlToPdf(html);
    }

    public byte[] renderHtmlToPdf(String html) {
        return pdfRenderer.render(html);
    }
}
