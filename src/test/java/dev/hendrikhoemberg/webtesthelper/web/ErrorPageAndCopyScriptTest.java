package dev.hendrikhoemberg.webtesthelper.web;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.ResourceBundleMessageSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockServletContext;
import org.thymeleaf.context.WebContext;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;
import org.thymeleaf.web.servlet.JakartaServletWebApplication;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

class ErrorPageAndCopyScriptTest {

    private SpringTemplateEngine templateEngine;

    @BeforeEach
    void setUp() {
        ClassLoaderTemplateResolver resolver = new ClassLoaderTemplateResolver();
        resolver.setPrefix("templates/");
        resolver.setSuffix(".html");
        resolver.setCharacterEncoding("UTF-8");

        ResourceBundleMessageSource messageSource = new ResourceBundleMessageSource();
        messageSource.setBasename("messages");
        messageSource.setDefaultEncoding("UTF-8");

        templateEngine = new SpringTemplateEngine();
        templateEngine.setTemplateResolver(resolver);
        templateEngine.setTemplateEngineMessageSource(messageSource);
    }

    private WebContext webContext(int status) {
        MockServletContext servletContext = new MockServletContext();
        JakartaServletWebApplication webApp = JakartaServletWebApplication.buildApplication(servletContext);
        var exchange = webApp.buildExchange(new MockHttpServletRequest(), new MockHttpServletResponse());
        WebContext ctx = new WebContext(exchange, Locale.GERMAN);
        ctx.setVariable("status", status);
        return ctx;
    }

    @Test
    void errorPageRendersDedicatedGerman403Message() {
        String html = templateEngine.process("error", webContext(403));

        assertThat(html)
                .contains("Zugriff verweigert")
                .contains("keine Berechtigung")
                .doesNotContain("Etwas ist schiefgelaufen");
    }

    @Test
    void errorPageStillRenders404AndGenericErrorMessages() {
        assertThat(templateEngine.process("error", webContext(404))).contains("Seite nicht gefunden");
        assertThat(templateEngine.process("error", webContext(500))).contains("Es ist ein Fehler aufgetreten");
    }

    @Test
    void kopierenJsUsesCurrentTargetOrClosestInsteadOfBareEventTarget() throws Exception {
        String js = Files.readString(Path.of("src/main/resources/static/js/kopieren.js"));
        assertThat(js)
                .doesNotContain("event.target.getAttribute(\"data-url\")")
                .contains("currentTarget");
    }
}
