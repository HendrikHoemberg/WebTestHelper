package dev.hendrikhoemberg.webtesthelper.checks;

import dev.hendrikhoemberg.webtesthelper.model.CheckType;
import org.junit.jupiter.api.Test;
import org.springframework.context.MessageSource;
import org.springframework.context.NoSuchMessageException;
import org.springframework.context.support.ResourceBundleMessageSource;

import java.util.List;
import java.util.Locale;
import java.util.ResourceBundle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Spec 13.7, enforcement 1: every registered check resolves its three explanation keys in every
 * supported locale, and every message key a finding can render resolves too (deviation D14).
 * Documentation that cannot rot is worth more than documentation that is merely thorough.
 *
 * <p>No Spring context: a bundle and a registry are all this needs, and a test that costs a
 * Postgres container is a test people start skipping.
 */
class CheckDocumentationTest {

    /** Spec 12: German is the default and only locale. A second entry here is a real project. */
    private static final List<Locale> SUPPORTED = List.of(Locale.GERMAN);

    private final CheckRegistry registry = CheckRegistry.standard();
    private final MessageSource messages = messageSource();

    private static MessageSource messageSource() {
        ResourceBundleMessageSource source = new ResourceBundleMessageSource();
        source.setBasename("messages");
        source.setDefaultEncoding("UTF-8");
        source.setUseCodeAsDefaultMessage(false);
        return source;
    }

    @Test
    void everyCheckExplainsItselfInEverySupportedLocale() {
        for (Locale locale : SUPPORTED) {
            for (CheckType type : CheckType.values()) {
                String titleKey = "check." + type.name() + ".title";
                String descriptionKey = "check." + type.name() + ".description";
                String remediationKey = "check." + type.name() + ".remediation";
                assertThatCode(() -> messages.getMessage(titleKey, null, locale))
                        .as("%s", titleKey).doesNotThrowAnyException();
                assertThatCode(() -> messages.getMessage(descriptionKey, null, locale))
                        .as("%s", descriptionKey).doesNotThrowAnyException();
                assertThatCode(() -> messages.getMessage(remediationKey, null, locale))
                        .as("%s", remediationKey).doesNotThrowAnyException();
            }
        }
    }

    @Test
    void everyMessageKeyAFindingCanRenderResolves() {
        for (Locale locale : SUPPORTED) {
            assertThat(registry.all()).allSatisfy(check ->
                    assertThat(check.messageKeys()).allSatisfy(key ->
                            assertThatCode(() -> messages.getMessage(key, new Object[]{"1", "2"}, locale))
                                     .as("%s", key).doesNotThrowAnyException()));
        }
    }

    @Test
    void noExplanationLeaksAnInternalIdentifier() {
        // Spec 13.1: "Tote Links", never DEAD_LINK. The audience is a colleague, not a developer.
        for (Locale locale : SUPPORTED) {
            for (CheckType type : CheckType.values()) {
                for (String key : List.of("check." + type.name() + ".title",
                        "check." + type.name() + ".description",
                        "check." + type.name() + ".remediation")) {
                    assertNoCheckTypeName(messages.getMessage(key, null, locale), key);
                }
            }
            assertThat(registry.all()).allSatisfy(check -> {
                for (String key : check.messageKeys()) {
                    assertNoCheckTypeName(messages.getMessage(key, null, locale), key);
                }
            });
        }
    }

    private static void assertNoCheckTypeName(String text, String key) {
        for (CheckType type : CheckType.values()) {
            assertThat(text).as("%s", key).doesNotContain(type.name());
        }
    }

    @Test
    void theBundleCarriesNoKeysForChecksThatDoNotExist() {
        // The other direction: a renamed check leaves dead German prose behind, and nobody ever
        // notices because nothing reads it.
        List<String> declared = java.util.stream.Stream.concat(
                java.util.EnumSet.allOf(CheckType.class).stream()
                        .flatMap(type -> java.util.stream.Stream.of(
                                "check." + type.name() + ".title",
                                "check." + type.name() + ".description",
                                "check." + type.name() + ".remediation")),
                registry.all().stream()
                        .flatMap(check -> check.messageKeys().stream())
        ).toList();
        ResourceBundle bundle = ResourceBundle.getBundle("messages", Locale.GERMAN);
        List<String> orphans = bundle.keySet().stream()
                .filter(key -> key.startsWith("check.") || key.startsWith("finding."))
                .filter(key -> !declared.contains(key))
                .sorted()
                .toList();
        assertThat(orphans).isEmpty();
    }

    @Test
    void aMissingKeyFailsRatherThanRenderingItsOwnName() {
        assertThatCode(() -> messages.getMessage("check.GIBT_ES_NICHT.title", null, Locale.GERMAN))
                .isInstanceOf(NoSuchMessageException.class);
    }
}