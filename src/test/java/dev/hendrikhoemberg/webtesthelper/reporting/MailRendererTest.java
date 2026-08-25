package dev.hendrikhoemberg.webtesthelper.reporting;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.ResourceBundleMessageSource;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.templatemode.TemplateMode;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;

import java.nio.charset.StandardCharsets;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class MailRendererTest {

    private MailRenderer mailRenderer;

    @BeforeEach
    void setUp() {
        ResourceBundleMessageSource messageSource = new ResourceBundleMessageSource();
        messageSource.setBasename("messages");
        messageSource.setDefaultEncoding("UTF-8");

        ClassLoaderTemplateResolver htmlResolver = new ClassLoaderTemplateResolver();
        htmlResolver.setPrefix("templates/");
        htmlResolver.setTemplateMode(TemplateMode.HTML);
        htmlResolver.setCharacterEncoding(StandardCharsets.UTF_8.name());
        htmlResolver.setResolvablePatterns(Set.of("*.html", "mail/*.html"));

        ClassLoaderTemplateResolver textResolver = new ClassLoaderTemplateResolver();
        textResolver.setPrefix("templates/");
        textResolver.setTemplateMode(TemplateMode.TEXT);
        textResolver.setCharacterEncoding(StandardCharsets.UTF_8.name());
        textResolver.setResolvablePatterns(Set.of("*.txt", "mail/*.txt"));

        SpringTemplateEngine templateEngine = new SpringTemplateEngine();
        templateEngine.addTemplateResolver(htmlResolver);
        templateEngine.addTemplateResolver(textResolver);
        templateEngine.setMessageSource(messageSource);

        mailRenderer = new MailRenderer(templateEngine, messageSource);
    }

    @Test
    void testMailRendersSubjectAndBothPartsWithBaseUrlAndNoUnresolvedKeys() {
        String recipient = "admin@example.com";
        String baseUrl = "https://wth.example.com";

        OutboundMail mail = mailRenderer.testMail(recipient, baseUrl);

        assertThat(mail.recipient()).isEqualTo("admin@example.com");
        assertThat(mail.subject()).isNotBlank();
        assertThat(mail.html()).isNotBlank();
        assertThat(mail.text()).isNotBlank();

        // HTML part contains baseUrl as link
        assertThat(mail.html())
                .contains("href=\"" + baseUrl + "\"")
                .doesNotContain("??")
                .doesNotContain("Exception");

        // Text part contains baseUrl as bare text
        assertThat(mail.text())
                .contains(baseUrl)
                .doesNotContain("<a ")
                .doesNotContain("??")
                .doesNotContain("Exception");
    }
}
