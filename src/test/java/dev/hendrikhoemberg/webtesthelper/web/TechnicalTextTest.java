package dev.hendrikhoemberg.webtesthelper.web;

import org.junit.jupiter.api.Test;
import org.springframework.context.MessageSource;
import org.springframework.context.support.ResourceBundleMessageSource;

import java.util.List;
import java.util.Locale;
import java.util.ResourceBundle;

import static org.assertj.core.api.Assertions.assertThat;

class TechnicalTextTest {

    private final MessageSource messageSource = createMessageSource();

    private static MessageSource createMessageSource() {
        ResourceBundleMessageSource source = new ResourceBundleMessageSource();
        source.setBasename("messages");
        source.setDefaultEncoding("UTF-8");
        source.setUseCodeAsDefaultMessage(false);
        return source;
    }

    @Test
    void chromiumNetworkErrorsHumaniseToGermanSentenceWithoutNetOrErr() {
        List<String> chromiumErrors = List.of(
                "net::ERR_NAME_NOT_RESOLVED",
                "net::ERR_CONNECTION_REFUSED",
                "net::ERR_CONNECTION_TIMED_OUT",
                "net::ERR_TOO_MANY_REDIRECTS",
                "net::ERR_BLOCKED_BY_RESPONSE",
                "net::ERR_ABORTED",
                "net::ERR_CERT_DATE_INVALID"
        );

        for (String raw : chromiumErrors) {
            String humanised = TechnicalText.humanise(raw, messageSource, Locale.GERMAN);
            assertThat(humanised)
                    .as("Humanised string for '%s' must not be blank", raw)
                    .isNotBlank();
            assertThat(humanised)
                    .as("Humanised string for '%s' must not contain 'net::'", raw)
                    .doesNotContain("net::");
            assertThat(humanised)
                    .as("Humanised string for '%s' must not contain 'ERR_'", raw)
                    .doesNotContain("ERR_");
        }
    }

    @Test
    void javaExceptionsHumaniseWithoutJavaPackagePrefixAndBareExceptionProducesSentence() {
        String connectError = "java.net.ConnectException: Connection refused";
        String humanisedConnect = TechnicalText.humanise(connectError, messageSource, Locale.GERMAN);
        assertThat(humanisedConnect).isNotBlank();
        assertThat(humanisedConnect)
                .as("Humanised string for '%s' must not contain 'java.'", connectError)
                .doesNotContain("java.");

        String timeoutError = "java.net.SocketTimeoutException";
        String humanisedTimeout = TechnicalText.humanise(timeoutError, messageSource, Locale.GERMAN);
        assertThat(humanisedTimeout).isNotBlank();
        assertThat(humanisedTimeout)
                .as("Bare SocketTimeoutException must produce a sentence without 'java.'")
                .doesNotContain("java.");
    }

    @Test
    void unmappedTechnicalErrorReturnsGenericSentence() {
        String unmapped = "net::ERR_SOMETHING_NEW";
        String humanised = TechnicalText.humanise(unmapped, messageSource, Locale.GERMAN);
        String expectedGeneric = messageSource.getMessage("ui.technisch.unbekannt", null, Locale.GERMAN);

        assertThat(humanised).isEqualTo(expectedGeneric);
        assertThat(humanised).doesNotContain("ERR_SOMETHING_NEW");
        assertThat(humanised).doesNotContain("net::");
    }

    @Test
    void isTechnicalRecognisesTechnicalIdentifiersAndLeavesUrlsUntouched() {
        List<String> technicalStrings = List.of(
                "net::ERR_NAME_NOT_RESOLVED",
                "net::ERR_CONNECTION_REFUSED",
                "net::ERR_CONNECTION_TIMED_OUT",
                "net::ERR_TOO_MANY_REDIRECTS",
                "net::ERR_BLOCKED_BY_RESPONSE",
                "net::ERR_ABORTED",
                "net::ERR_CERT_DATE_INVALID",
                "java.net.ConnectException: Connection refused",
                "java.net.SocketTimeoutException",
                "net::ERR_SOMETHING_NEW"
        );

        for (String technical : technicalStrings) {
            assertThat(TechnicalText.isTechnical(technical))
                    .as("'%s' must be recognized as technical", technical)
                    .isTrue();
        }

        String normalUrl = "https://kunde.de/fehlt.pdf";
        assertThat(TechnicalText.isTechnical(normalUrl))
                .as("'%s' must not be recognized as technical", normalUrl)
                .isFalse();

        assertThat(TechnicalText.humanise(normalUrl, messageSource, Locale.GERMAN))
                .isEqualTo(normalUrl);
    }

    @Test
    void everyTechnicalMessageKeyResolvesInGermanBundle() {
        ResourceBundle bundle = ResourceBundle.getBundle("messages", Locale.GERMAN);
        List<String> keys = List.of(
                "ui.technisch.name_not_resolved",
                "ui.technisch.connection_refused",
                "ui.technisch.connection_timed_out",
                "ui.technisch.too_many_redirects",
                "ui.technisch.blocked_by_response",
                "ui.technisch.aborted",
                "ui.technisch.cert_date_invalid",
                "ui.technisch.ssl_error",
                "ui.technisch.unbekannt"
        );

        for (String key : keys) {
            assertThat(bundle.containsKey(key))
                    .as("Key '%s' must exist in German messages bundle", key)
                    .isTrue();
            assertThat(bundle.getString(key))
                    .as("Key '%s' must not have empty value", key)
                    .isNotBlank();
        }
    }
}
