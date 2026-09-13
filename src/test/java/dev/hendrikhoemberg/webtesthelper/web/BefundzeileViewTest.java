package dev.hendrikhoemberg.webtesthelper.web;

import dev.hendrikhoemberg.webtesthelper.model.UrlNormalizer;
import dev.hendrikhoemberg.webtesthelper.model.Severity;
import dev.hendrikhoemberg.webtesthelper.model.TriageStatus;
import dev.hendrikhoemberg.webtesthelper.model.SiteContext;
import dev.hendrikhoemberg.webtesthelper.reporting.FindingView;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;

import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class BefundzeileViewTest {

    @Autowired
    private TemplateEngine templateEngine;

    @Test
    void rendersMonochromeSplitCardWithSvgCameraAndNoEmojis() {
        FindingView finding = new FindingView(
                42L,
                "Tote Links",
                "Der Verweis führt ins Leere (404 Not Found).",
                "Verweis korrigieren",
                "/kontakt",
                false,
                1,
                Severity.ERROR,
                TriageStatus.UNTRIAGED,
                null,
                null,
                null,
                "https://example.com/broken",
                "/screenshots/shot42.png"
        );

        SiteContext site = new SiteContext(
                1L,
                "Example Site",
                UrlNormalizer.normalize("https://example.com").orElseThrow(),
                null,
                List.of(),
                List.of(),
                List.of(),
                true,
                null,
                Map.of()
        );

        Context context = new Context(Locale.GERMAN);
        context.setVariable("befund", finding);
        context.setVariable("auswaehlbar", false);
        context.setVariable("inGruppe", true);
        context.setVariable("site", site);

        String html = templateEngine.process("fragments/befundzeile", context);

        // 1. No emojis allowed
        assertThat(html)
                .as("Rendered befundzeile must not contain emoji 📷")
                .doesNotContain("📷");

        // 2. Camera SVG is rendered for screenshot
        assertThat(html)
                .as("Rendered befundzeile must include camera SVG icon")
                .contains("<svg class=\"svg-icon\" viewBox=\"0 0 24 24\"")
                .contains("d=\"M14.5 4h-5L7 7H4");

        // 3. In group, redundant 'Ungeprüft' badge should not be rendered
        assertThat(html)
                .as("In group view with default UNREVIEWED, 'Ungeprüft' badge should be suppressed")
                .doesNotContain("Ungeprüft");

        // 4. Broken URL and copy affordance are rendered
        assertThat(html).contains("https://example.com/broken");
        assertThat(html).contains("/befunde/42");
        assertThat(html).contains("/kontakt");
        assertThat(html).contains("Der Verweis führt ins Leere (404 Not Found).");
    }

    @Test
    void rendersStandaloneFindingWithTitleAndTriageBadge() {
        FindingView finding = new FindingView(
                43L,
                "Tote Links",
                "Der Verweis führt ins Leere (404 Not Found).",
                "Verweis korrigieren",
                "/kontakt",
                false,
                1,
                Severity.ERROR,
                TriageStatus.UNTRIAGED,
                null,
                null,
                null,
                "https://example.com/broken",
                null
        );

        Context context = new Context(Locale.GERMAN);
        context.setVariable("befund", finding);
        context.setVariable("auswaehlbar", true);
        context.setVariable("inGruppe", false);

        String html = templateEngine.process("fragments/befundzeile", context);

        assertThat(html).contains("Tote Links");
        assertThat(html).contains("Ungeprüft");
        assertThat(html).contains("input type=\"checkbox\"");
        assertThat(html).doesNotContain("d=\"M14.5 4h-5L7 7H4"); // no camera icon when screenshotUrl is null
    }

    @Test
    void rendersTriageBadgeInGroupWhenTriaged() {
        FindingView finding = new FindingView(
                44L,
                "Tote Links",
                "Der Verweis führt ins Leere (404 Not Found).",
                "Verweis korrigieren",
                "/kontakt",
                false,
                1,
                Severity.ERROR,
                TriageStatus.ACKNOWLEDGED,
                null,
                null,
                null,
                "https://example.com/broken",
                null
        );

        Context context = new Context(Locale.GERMAN);
        context.setVariable("befund", finding);
        context.setVariable("auswaehlbar", false);
        context.setVariable("inGruppe", true);

        String html = templateEngine.process("fragments/befundzeile", context);

        assertThat(html).contains("Zur Kenntnis genommen");
    }
}
