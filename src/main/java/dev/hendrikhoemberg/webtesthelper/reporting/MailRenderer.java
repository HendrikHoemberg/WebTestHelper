package dev.hendrikhoemberg.webtesthelper.reporting;

import org.springframework.context.MessageSource;
import org.springframework.stereotype.Component;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;

import java.util.Locale;

@Component
public class MailRenderer {

    private final TemplateEngine templateEngine;
    private final MessageSource messageSource;

    public MailRenderer(TemplateEngine templateEngine, MessageSource messageSource) {
        this.templateEngine = templateEngine;
        this.messageSource = messageSource;
    }

    public OutboundMail testMail(String recipient, String baseUrl) {
        Locale locale = Locale.GERMAN;
        String subject = messageSource.getMessage("ui.mail.testmail.betreff", null, "WebTestHelper: Testnachricht", locale);

        Context context = new Context(locale);
        context.setVariable("recipient", recipient);
        context.setVariable("baseUrl", baseUrl != null ? baseUrl : "");

        String html = templateEngine.process("mail/testmail.html", context);
        String text = templateEngine.process("mail/testmail.txt", context);

        return new OutboundMail(recipient, subject, html, text);
    }
}
