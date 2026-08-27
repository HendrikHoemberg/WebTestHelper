package dev.hendrikhoemberg.webtesthelper.web;

import org.junit.jupiter.api.Test;

import java.text.Collator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class HelpServiceTest {

    private final HelpService helpService = new HelpService();
    private final Collator germanOrder = Collator.getInstance(Locale.GERMAN);

    @Test
    void allFindsAllBundledTopicsSortedByTitle() {
        List<HelpTopic> topics = helpService.all();

        assertThat(topics)
                .hasSize(11)
                .extracting(HelpTopic::title)
                .isSortedAccordingTo(germanOrder);

        assertThat(topics)
                .extracting(HelpTopic::id)
                .containsExactlyInAnyOrder("bericht-lesen", "ausgangsbestand", "smtp-einrichten",
                        "zeitplaene", "stummschaltungen", "benachrichtigungen", "uebersicht",
                        "einrichtung", "cookie-hinweis", "sprachumschalter", "schaltflaechen");
    }

    @Test
    void byIdResolvesTopicHeadingAsTitleNotFilename() {
        Optional<HelpTopic> topic = helpService.byId("bericht-lesen");

        assertThat(topic).isPresent();
        assertThat(topic.get().id()).isEqualTo("bericht-lesen");
        assertThat(topic.get().title()).isEqualTo("Berichte lesen und verstehen");
        assertThat(topic.get().title()).isNotEqualTo("bericht-lesen");
    }

    @Test
    void rawHtmlInATopicIsEscapedRatherThanRendered() {
        // The three bundled topics are clean today, and the assertion below proves only that.
        // The renderer itself has to be the guard: `th:utext` drops this straight into the page,
        // so a topic pasted in from somewhere else must not be able to bring markup with it.
        HelpTopic topic = HelpService.parseTopic("boeswillig", """
                # Ein Thema

                Ein Absatz <script>alert('xss')</script> mit Markup.
                """);

        assertThat(topic.html()).doesNotContain("<script");
        assertThat(topic.html()).contains("&lt;script&gt;");
        assertThat(topic.teaserHtml()).doesNotContain("<script");
    }

    @Test
    void renderedHtmlOfEveryBundledTopicIsNonEmptyAndContainsNoScript() {
        List<HelpTopic> topics = helpService.all();

        assertThat(topics).isNotEmpty();
        for (HelpTopic topic : topics) {
            assertThat(topic.html())
                    .as("Topic '%s' html must not be blank", topic.id())
                    .isNotBlank();
            assertThat(topic.html().toLowerCase())
                    .as("Topic '%s' html must not contain script tags", topic.id())
                    .doesNotContain("<script");

            assertThat(topic.teaserHtml())
                    .as("Topic '%s' teaserHtml must not be blank", topic.id())
                    .isNotBlank();
            assertThat(topic.teaserHtml().toLowerCase())
                    .as("Topic '%s' teaserHtml must not contain script tags", topic.id())
                    .doesNotContain("<script");
            assertThat(topic.teaserHtml())
                    .as("Topic '%s' teaserHtml should start with <p>", topic.id())
                    .startsWith("<p>");
        }
    }

    @Test
    void byIdReturnsEmptyForUnknownTopicWithoutException() {
        Optional<HelpTopic> missing = helpService.byId("gibt-es-nicht");

        assertThat(missing).isEmpty();
    }
}
