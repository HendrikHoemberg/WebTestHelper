package dev.hendrikhoemberg.webtesthelper.web;

/**
 * A bundled handbook / inline help topic loaded from classpath:help/*.md.
 *
 * @param id topic identifier, matching the markdown file basename (e.g. "bericht-lesen")
 * @param title heading 1 of the markdown document
 * @param html full rendered HTML representation
 * @param teaserHtml rendered HTML of the first paragraph, used for inline hints
 */
public record HelpTopic(String id, String title, String html, String teaserHtml) {
}
