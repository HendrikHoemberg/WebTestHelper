package dev.hendrikhoemberg.webtesthelper.reporting;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.options.Margin;
import com.microsoft.playwright.options.WaitUntilState;
import org.springframework.stereotype.Service;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;

import java.util.Locale;
import java.util.Map;
import java.util.Objects;

@Service
public class PdfReportService {

    private final TemplateEngine templateEngine;

    public PdfReportService(TemplateEngine templateEngine) {
        this.templateEngine = Objects.requireNonNull(templateEngine, "templateEngine must not be null");
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
        try (Playwright playwright = Playwright.create()) {
            Browser browser = playwright.chromium().launch(new BrowserType.LaunchOptions().setHeadless(true));
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
            } finally {
                browser.close();
            }
        }
    }
}
