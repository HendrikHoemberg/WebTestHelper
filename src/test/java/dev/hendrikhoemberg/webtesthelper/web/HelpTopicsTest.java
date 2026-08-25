package dev.hendrikhoemberg.webtesthelper.web;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Spec 13.7 Enforcement 2:
 * 1. Every help topic id referenced by a ? affordance or /hilfe/{id} link resolves to a bundled Markdown file.
 * 2. At least three affordances are wired in templates.
 * 3. HelpService.all() covers the bundled help directory listing exactly.
 */
class HelpTopicsTest {

    private static final Path TEMPLATES_DIR = Path.of("src/main/resources/templates");
    private static final Path HELP_DIR = Path.of("src/main/resources/help");

    private static final Pattern HINWEIS_AFFORDANCE_PATTERN = Pattern.compile("class=\"[^\"]*hinweis-schalter[^\"]*\"");
    private static final Pattern HINWEIS_URL_PATTERN = Pattern.compile("/hilfe/hinweis/([a-zA-Z0-9_-]+)");
    private static final Pattern HINWEIS_PARAM_PATTERN = Pattern.compile("/hilfe/hinweis/\\{id\\}\\(id='([a-zA-Z0-9_-]+)'\\)");
    private static final Pattern HILFE_URL_PATTERN = Pattern.compile("/hilfe/([a-zA-Z0-9_-]+)");
    private static final Pattern HILFE_PARAM_PATTERN = Pattern.compile("/hilfe/\\{id\\}\\(id='([a-zA-Z0-9_-]+)'\\)");

    @Test
    void allTemplateHelpTopicReferencesResolveToExistingMarkdownFiles() throws IOException {
        assertThat(TEMPLATES_DIR).exists();
        assertThat(HELP_DIR).exists();

        Set<String> referencedTopicIds = new HashSet<>();

        try (Stream<Path> stream = Files.walk(TEMPLATES_DIR)) {
            List<Path> templateFiles = stream
                    .filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".html"))
                    .toList();

            for (Path file : templateFiles) {
                String content = Files.readString(file);

                // Check /hilfe/hinweis/{id}(id='...')
                Matcher mParamHinweis = HINWEIS_PARAM_PATTERN.matcher(content);
                while (mParamHinweis.find()) {
                    referencedTopicIds.add(mParamHinweis.group(1));
                }

                // Check /hilfe/hinweis/<id>
                Matcher mHinweis = HINWEIS_URL_PATTERN.matcher(content);
                while (mHinweis.find()) {
                    String id = mHinweis.group(1);
                    if (!id.equals("{id}")) {
                        referencedTopicIds.add(id);
                    }
                }

                // Check /hilfe/{id}(id='...')
                Matcher mParamHilfe = HILFE_PARAM_PATTERN.matcher(content);
                while (mParamHilfe.find()) {
                    referencedTopicIds.add(mParamHilfe.group(1));
                }

                // Check /hilfe/<id>
                Matcher mHilfe = HILFE_URL_PATTERN.matcher(content);
                while (mHilfe.find()) {
                    String id = mHilfe.group(1);
                    if (!id.equals("hinweis") && !id.equals("{id}")) {
                        referencedTopicIds.add(id);
                    }
                }
            }
        }

        assertThat(referencedTopicIds)
                .as("Templates must reference at least one help topic")
                .isNotEmpty();

        for (String topicId : referencedTopicIds) {
            Path mdFile = HELP_DIR.resolve(topicId + ".md");
            assertThat(Files.exists(mdFile))
                    .as("Help topic '%s' referenced in templates must exist at %s", topicId, mdFile)
                    .isTrue();
        }
    }

    @Test
    void atLeastThreeAffordancesAreWiredInTemplates() throws IOException {
        int affordanceCount = 0;

        try (Stream<Path> stream = Files.walk(TEMPLATES_DIR)) {
            List<Path> templateFiles = stream
                    .filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".html"))
                    .toList();

            for (Path file : templateFiles) {
                String content = Files.readString(file);
                Matcher matcher = HINWEIS_AFFORDANCE_PATTERN.matcher(content);
                while (matcher.find()) {
                    affordanceCount++;
                }
            }
        }

        assertThat(affordanceCount)
                .as("At least three ? affordances (hinweis-schalter) must be wired in templates")
                .isGreaterThanOrEqualTo(3);
    }

    @Test
    void helpServiceAllCoversHelpDirectoryListingExactly() throws IOException {
        HelpService helpService = new HelpService();
        List<String> serviceTopicIds = helpService.all().stream()
                .map(HelpTopic::id)
                .toList();

        List<String> fileTopicIds = new ArrayList<>();
        try (Stream<Path> stream = Files.list(HELP_DIR)) {
            fileTopicIds = stream
                    .filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".md"))
                    .map(p -> {
                        String name = p.getFileName().toString();
                        return name.substring(0, name.length() - 3);
                    })
                    .sorted()
                    .toList();
        }

        assertThat(serviceTopicIds)
                .as("HelpService.all() topic IDs must match the files in src/main/resources/help/*.md")
                .containsExactlyInAnyOrderElementsOf(fileTopicIds);
    }
}
