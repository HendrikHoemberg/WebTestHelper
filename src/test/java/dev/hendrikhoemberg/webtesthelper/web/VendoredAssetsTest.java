package dev.hendrikhoemberg.webtesthelper.web;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Spec 12: HTMX and Alpine must be vendored locally and never loaded from a CDN.
 * This browser-free test ensures the vendored assets exist, exceed 10 KB, and no
 * templates contain off-host CDN references.
 */
class VendoredAssetsTest {

    private static final Path VENDOR_DIR = Path.of("src/main/resources/static/vendor");
    private static final Path TEMPLATES_DIR = Path.of("src/main/resources/templates");
    private static final long MIN_ASSET_SIZE_BYTES = 10 * 1024; // 10 KB

    private static final List<String> FORBIDDEN_CDN_PATTERNS = List.of(
            "//unpkg.com",
            "//cdn.",
            "//ajax.googleapis.com",
            "https://cdn"
    );

    @Test
    void vendoredAssetsExistAndExceed10Kb() throws IOException {
        Path htmx = VENDOR_DIR.resolve("htmx.min.js");
        Path alpine = VENDOR_DIR.resolve("alpine.min.js");

        assertThat(htmx).exists();
        assertThat(alpine).exists();

        assertThat(Files.size(htmx))
                .as("htmx.min.js size in bytes")
                .isGreaterThan(MIN_ASSET_SIZE_BYTES);

        assertThat(Files.size(alpine))
                .as("alpine.min.js size in bytes")
                .isGreaterThan(MIN_ASSET_SIZE_BYTES);
    }

    @Test
    void noTemplatesContainCdnReferences() throws IOException {
        assertThat(TEMPLATES_DIR).exists();

        try (Stream<Path> stream = Files.walk(TEMPLATES_DIR)) {
            List<Path> templateFiles = stream
                    .filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".html"))
                    .toList();

            assertThat(templateFiles).isNotEmpty();

            for (Path template : templateFiles) {
                String content = Files.readString(template);
                for (String forbidden : FORBIDDEN_CDN_PATTERNS) {
                    assertThat(content)
                            .as("Template %s contains forbidden CDN pattern %s", template, forbidden)
                            .doesNotContain(forbidden);
                }
            }
        }
    }
}
