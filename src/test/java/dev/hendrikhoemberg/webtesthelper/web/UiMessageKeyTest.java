package dev.hendrikhoemberg.webtesthelper.web;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.ResourceBundle;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Spec 13.7 applied to the UI: a missing key renders as ??ui.foo_de?? on screen.
 *
 * <p>This test scans every {@code .html} template for {@code #{...}} message key literals,
 * asserts each resolves in the German resource bundle, and asserts that no template key
 * uses {@code check.} or {@code finding.} prefixes except the check explanation keys
 * ({@code check.*.title}, {@code check.*.description}, {@code check.*.remediation})
 * owned by the catalog.
 *
 * <p><b>Note:</b> Keys built dynamically by concatenation (such as
 * {@code #{'ui.lauf.status.' + lauf.status}}) are skipped by this scanner and are covered
 * by {@code EnumLabelsTest} in Task 3 instead.
 */
class UiMessageKeyTest {

    private static final Path TEMPLATES_DIR = Path.of("src/main/resources/templates");
    private static final Pattern MESSAGE_KEY_PATTERN = Pattern.compile("#\\{([^}]+)\\}");

    @Test
    void allTemplateMessageKeysResolveInGermanBundle() throws IOException {
        assertThat(TEMPLATES_DIR).exists();

        ResourceBundle bundle = ResourceBundle.getBundle("messages", Locale.GERMAN);
        List<String> keys = extractStaticMessageKeys();

        assertThat(keys).isNotEmpty();

        for (String key : keys) {
            assertThat(bundle.containsKey(key))
                    .as("Message key '%s' must exist in messages.properties", key)
                    .isTrue();

            if (key.startsWith("check.")) {
                assertThat(key)
                        .as("check.* key '%s' must be an explanation key (.title, .description, or .remediation)", key)
                        .matches("^check\\.[A-Za-z0-9_]+\\.(title|description|remediation)$");
            } else {
                assertThat(key.startsWith("finding."))
                        .as("Template message key '%s' must not start with 'finding.'", key)
                        .isFalse();
                assertThat(key.startsWith("ui."))
                        .as("Template message key '%s' must start with 'ui.'", key)
                        .isTrue();
            }
        }
    }

    private List<String> extractStaticMessageKeys() throws IOException {
        List<String> keys = new ArrayList<>();

        try (Stream<Path> stream = Files.walk(TEMPLATES_DIR)) {
            List<Path> templateFiles = stream
                    .filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".html"))
                    .toList();

            for (Path file : templateFiles) {
                String content = Files.readString(file);
                Matcher matcher = MESSAGE_KEY_PATTERN.matcher(content);
                while (matcher.find()) {
                    String raw = matcher.group(1).trim();
                    // Skip dynamic expressions / concatenations (handled by EnumLabelsTest)
                    if (raw.contains("'") || raw.contains("+") || raw.contains("$")) {
                        continue;
                    }
                    String key = raw;
                    if (key.contains("(")) {
                        key = key.substring(0, key.indexOf('(')).trim();
                    }
                    keys.add(key);
                }
            }
        }

        return keys;
    }
}
