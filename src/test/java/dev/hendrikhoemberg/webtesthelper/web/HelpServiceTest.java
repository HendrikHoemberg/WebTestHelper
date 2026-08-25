package dev.hendrikhoemberg.webtesthelper.web;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class HelpServiceTest {

    private final HelpService helpService = new HelpService();

    @Test
    void allFindsAllThreeTopicsSortedByTitle() {
        List<HelpTopic> topics = helpService.all();

        assertThat(topics)
                .hasSize(3)
                .extracting(HelpTopic::title)
                .isSorted();

        assertThat(topics)
                .extracting(HelpTopic::id)
                .containsExactlyInAnyOrder("bericht-lesen", "ausgangsbestand", "smtp-einrichten");
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
