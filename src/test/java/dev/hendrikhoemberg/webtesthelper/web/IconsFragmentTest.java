package dev.hendrikhoemberg.webtesthelper.web;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class IconsFragmentTest {

    private static final Path ICONS_FILE = Path.of("src/main/resources/templates/fragments/icons.html");

    @Test
    void iconsFileDefinesCameraAndLightbulbSvgFragments() throws IOException {
        assertThat(ICONS_FILE).exists();
        String content = Files.readString(ICONS_FILE);

        assertThat(content)
                .as("icons.html must contain a camera SVG fragment")
                .contains("th:fragment=\"camera\"")
                .contains("class=\"svg-icon\"")
                .contains("viewBox=\"0 0 24 24\"");

        assertThat(content)
                .as("icons.html must contain a lightbulb SVG fragment")
                .contains("th:fragment=\"lightbulb\"");
    }
}
