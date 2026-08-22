package dev.hendrikhoemberg.webtesthelper.crawler;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * The {@code User-agent: *} group of a robots.txt (deviation D9). Politeness is on by default
 * and overridable per site, because the company hosts these sites (spec 8).
 *
 * <p>Longest match wins between Allow and Disallow, with Allow breaking ties — the rule every
 * major crawler implements, and the reason a bare {@code Allow:} line can carve an exception
 * out of a broader {@code Disallow:}.
 */
public record RobotsRules(List<Rule> allow, List<Rule> disallow, List<String> sitemaps) {

    public record Rule(String source, Pattern pattern) {
    }

    public static final RobotsRules ALLOW_ALL = new RobotsRules(List.of(), List.of(), List.of());

    public RobotsRules {
        allow = List.copyOf(allow);
        disallow = List.copyOf(disallow);
        sitemaps = List.copyOf(sitemaps);
    }

    public static RobotsRules parse(String body) {
        if (body == null || body.isBlank()) {
            return ALLOW_ALL;
        }
        List<Rule> allow = new ArrayList<>();
        List<Rule> disallow = new ArrayList<>();
        List<String> sitemaps = new ArrayList<>();
        boolean inStarGroup = false;
        boolean previousLineWasAgent = false;
        for (String rawLine : body.split("\\R")) {
            String line = rawLine;
            int comment = line.indexOf('#');
            if (comment >= 0) {
                line = line.substring(0, comment);
            }
            line = line.trim();
            if (line.isEmpty()) {
                continue;
            }
            int colon = line.indexOf(':');
            if (colon < 0) {
                continue;
            }
            String field = line.substring(0, colon).trim().toLowerCase(Locale.ROOT);
            String value = line.substring(colon + 1).trim();
            switch (field) {
                case "user-agent" -> {
                    // Consecutive User-agent lines share one group.
                    inStarGroup = previousLineWasAgent ? (inStarGroup || "*".equals(value))
                            : "*".equals(value);
                    previousLineWasAgent = true;
                    continue;
                }
                case "disallow" -> {
                    if (inStarGroup && !value.isEmpty()) {
                        disallow.add(rule(value));
                    }
                }
                case "allow" -> {
                    if (inStarGroup && !value.isEmpty()) {
                        allow.add(rule(value));
                    }
                }
                case "sitemap" -> sitemaps.add(value);
                default -> {
                }
            }
            previousLineWasAgent = false;
        }
        return new RobotsRules(allow, disallow, sitemaps);
    }

    /** @param path the URL's path plus query, i.e. {@code NormalizedUrl.locationKey()} */
    public boolean allows(String path) {
        int allowLength = longestMatch(allow, path);
        int disallowLength = longestMatch(disallow, path);
        return disallowLength < 0 || allowLength >= disallowLength;
    }

    private static int longestMatch(List<Rule> rules, String path) {
        int longest = -1;
        for (Rule rule : rules) {
            if (rule.pattern().matcher(path).find()) {
                longest = Math.max(longest, rule.source().length());
            }
        }
        return longest;
    }

    /** {@code *} is any run of characters, {@code $} anchors the end; everything else is literal. */
    private static Rule rule(String source) {
        StringBuilder regex = new StringBuilder("^");
        for (int i = 0; i < source.length(); i++) {
            char c = source.charAt(i);
            if (c == '*') {
                regex.append(".*");
            } else if (c == '$' && i == source.length() - 1) {
                regex.append('$');
            } else {
                regex.append(Pattern.quote(String.valueOf(c)));
            }
        }
        return new Rule(source, Pattern.compile(regex.toString()));
    }
}