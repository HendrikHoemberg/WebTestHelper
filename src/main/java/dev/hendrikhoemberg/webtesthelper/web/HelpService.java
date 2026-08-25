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
            Parser parser = Parser.builder().build();
            HtmlRenderer renderer = HtmlRenderer.builder().build();

            for (Resource resource : resources) {
                String filename = resource.getFilename();
                if (filename != null && filename.endsWith(".md")) {
                    String id = filename.substring(0, filename.length() - 3);
                    String markdown;
                    try (InputStream is = resource.getInputStream()) {
                        markdown = new String(is.readAllBytes(), StandardCharsets.UTF_8);
                    }
                    HelpTopic topic = parseTopic(id, markdown, parser, renderer);
                    topicsById.put(id, topic);
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load help topics from classpath:help/*.md", e);
        }
    }

    private HelpTopic parseTopic(String id, String markdown, Parser parser, HtmlRenderer renderer) {
        Node document = parser.parse(markdown);
        String title = extractTitle(document, id, markdown);
        String html = renderer.render(document);
        String teaserHtml = extractTeaserHtml(document, renderer);
        return new HelpTopic(id, title, html, teaserHtml);
    }

    private String extractTitle(Node document, String id, String markdown) {
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

    private void extractText(Node node, StringBuilder sb) {
        if (node instanceof Text text) {
            sb.append(text.getLiteral());
        }
        for (Node child = node.getFirstChild(); child != null; child = child.getNext()) {
            extractText(child, sb);
        }
    }

    private String extractTeaserHtml(Node document, HtmlRenderer renderer) {
        for (Node node = document.getFirstChild(); node != null; node = node.getNext()) {
            if (node instanceof Paragraph paragraph) {
                return renderer.render(paragraph).trim();
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
