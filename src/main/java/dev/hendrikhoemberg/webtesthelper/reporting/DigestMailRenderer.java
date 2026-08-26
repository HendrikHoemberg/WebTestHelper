package dev.hendrikhoemberg.webtesthelper.reporting;

import org.springframework.context.MessageSource;
import org.springframework.stereotype.Component;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Multipart HTML and plain-text email renderer for multi-site digests (§11.5, §13.1).
 */
@Component
public class DigestMailRenderer {

    private final TemplateEngine templateEngine;
    private final MessageSource messageSource;

    public DigestMailRenderer(TemplateEngine templateEngine, MessageSource messageSource) {
        this.templateEngine = templateEngine;
        this.messageSource = messageSource;
    }

    public OutboundMail render(String recipient, Digest digest, String baseUrl, Locale locale) {
        Locale effectiveLocale = (locale != null) ? locale : Locale.GERMAN;
        String subject = assembleSubject(digest, effectiveLocale);

        String cleanBaseUrl = (baseUrl != null) ? baseUrl.strip().replaceAll("/+$", "") : "";

        Context context = new Context(effectiveLocale);
        context.setVariable("recipient", recipient);
        context.setVariable("digest", digest);
        context.setVariable("baseUrl", cleanBaseUrl);

        String html = templateEngine.process("mail/digest.html", context);
        String text = templateEngine.process("mail/digest.txt", context);

        return new OutboundMail(recipient, subject, html, text);
    }

    private String assembleSubject(Digest digest, Locale locale) {
        String scopeKey = "ui.runscope." + digest.scope().name();
        String scopeLabel = messageSource.getMessage(scopeKey, null, digest.scope().name(), locale);

        List<String> fragments = new ArrayList<>();
        if (digest.errorTotal() == 1) {
            fragments.add(messageSource.getMessage("ui.mail.digest.betreff.fehler_einzahl", null, locale));
        } else if (digest.errorTotal() > 1) {
            fragments.add(messageSource.getMessage("ui.mail.digest.betreff.fehler", new Object[]{digest.errorTotal()}, locale));
        }

        if (digest.failedRuns() == 1) {
            fragments.add(messageSource.getMessage("ui.mail.digest.betreff.fehlgeschlagen_einzahl", null, locale));
        } else if (digest.failedRuns() > 1) {
            fragments.add(messageSource.getMessage("ui.mail.digest.betreff.fehlgeschlagen", new Object[]{digest.failedRuns()}, locale));
        }

        if (fragments.isEmpty()) {
            fragments.add(messageSource.getMessage("ui.mail.digest.betreff.alles_gut", null, locale));
        }

        String joinedFragments = String.join(", ", fragments);
        return messageSource.getMessage("ui.mail.digest.betreff", new Object[]{scopeLabel, joinedFragments}, locale);
    }
}
