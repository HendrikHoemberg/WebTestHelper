package dev.hendrikhoemberg.webtesthelper.web;

import org.commonmark.node.Heading;
import org.commonmark.node.Node;
import org.commonmark.node.Paragraph;
import org.commonmark.node.Text;
import org.commonmark.parser.Parser;
import org.commonmark.renderer.html.HtmlRenderer;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.text.Collator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

@Component
public class HelpService {

    private static final Parser PARSER = Parser.builder().build();

    /**
     * Raw HTML in a topic is escaped, not passed through. Both templates render a topic with
     * {@code th:utext}, so whatever this returns lands in the page unescaped; the bundled topics
     * are reviewed in the same commit as the code, but a topic pasted in from elsewhere must not
     * be able to bring markup with it.
     */
    private static final HtmlRenderer RENDERER = HtmlRenderer.builder().escapeHtml(true).build();

    private final Map<String, HelpTopic> topicsById;
    private final List<HelpTopic> sortedTopics;

    public HelpService() {
        this.topicsById = new HashMap<>();
        loadTopics();
        List<HelpTopic> list = new ArrayList<>(topicsById.values());
        Collator germanCollator = Collator.getInstance(Locale.GERMAN);
        list.sort(Comparator.comparing(HelpTopic::title, germanCollator));
        this.sortedTopics = Collections.unmodifiableList(list);
    }

    private void loadTopics() {
        try {
            PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
            Resource[] resources = resolver.getResources("classpath*:help/*.md");
            for (Resource resource : resources) {
                String filename = resource.getFilename();
                if (filename != null && filename.endsWith(".md")) {
                    String id = filename.substring(0, filename.length() - 3);
                    String markdown;
                    try (InputStream is = resource.getInputStream()) {
                        markdown = new String(is.readAllBytes(), StandardCharsets.UTF_8);
                    }
                    HelpTopic topic = parseTopic(id, markdown);
                    topicsById.put(id, topic);
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load help topics from classpath:help/*.md", e);
        }
    }

    /** Renders one topic from its Markdown source. The seam {@code HelpServiceTest} renders through. */
    static HelpTopic parseTopic(String id, String markdown) {
        Node document = PARSER.parse(markdown);
        String title = extractTitle(document, id, markdown);
        String html = RENDERER.render(document);
        String teaserHtml = extractTeaserHtml(document);
        return new HelpTopic(id, title, html, teaserHtml);
    }

    private static String extractTitle(Node document, String id, String markdown) {
        for (Node node = document.getFirstChild(); node != null; node = node.getNext()) {
            if (node instanceof Heading heading && heading.getLevel() == 1) {
                StringBuilder sb = new StringBuilder();
                extractText(heading, sb);
                String title = sb.toString().trim();
                if (!title.isEmpty()) {
                    return title;
                }
            }
        }
        for (String line : markdown.lines().toList()) {
            if (line.startsWith("# ")) {
                return line.substring(2).trim();
            }
        }
        return id;
    }

    private static void extractText(Node node, StringBuilder sb) {
        if (node instanceof Text text) {
            sb.append(text.getLiteral());
        }
        for (Node child = node.getFirstChild(); child != null; child = child.getNext()) {
            extractText(child, sb);
        }
    }

    private static String extractTeaserHtml(Node document) {
        for (Node node = document.getFirstChild(); node != null; node = node.getNext()) {
            if (node instanceof Paragraph paragraph) {
                return RENDERER.render(paragraph).trim();
            }
        }
        return "";
    }

    public List<HelpTopic> all() {
        return sortedTopics;
    }

    public Optional<HelpTopic> byId(String id) {
        if (id == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(topicsById.get(id));
    }
}
