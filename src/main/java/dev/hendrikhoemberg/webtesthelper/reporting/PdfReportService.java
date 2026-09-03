package dev.hendrikhoemberg.webtesthelper.reporting;

import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.Margin;
import com.microsoft.playwright.options.WaitUntilState;
import dev.hendrikhoemberg.webtesthelper.crawler.BrowserPool;
import org.springframework.stereotype.Service;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;

import java.util.Locale;
import java.util.Map;
import java.util.Objects;

@Service
public class PdfReportService {

    private final TemplateEngine templateEngine;
    private final BrowserPool browserPool;

    public PdfReportService(TemplateEngine templateEngine, BrowserPool browserPool) {
        this.templateEngine = Objects.requireNonNull(templateEngine, "templateEngine must not be null");
        this.browserPool = Objects.requireNonNull(browserPool, "browserPool must not be null");
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
        return browserPool.submit(browser -> {
            try (BrowserContext browserContext = browser.newContext();
                 Page page = browserContext.newPage()) {
                page.setContent(html, new Page.SetContentOptions().setWaitUntil(WaitUntilState.LOAD));
                return page.pdf(new Page.PdfOptions()
                        .setFormat("A4")
                        .setPrintBackground(true)
                        .setMargin(new Margin()
                                .setTop("15mm")
                                .setBottom("15mm")
                                .setLeft("15mm")
                                .setRight("15mm")));
            }
        });
    }
}
