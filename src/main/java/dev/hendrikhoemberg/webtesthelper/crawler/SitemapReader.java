package dev.hendrikhoemberg.webtesthelper.crawler;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class SitemapReader {

    private static final Pattern LOC = Pattern.compile("<loc>\\s*(.*?)\\s*</loc>",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    private SitemapReader() {
    }

    public static boolean isIndex(String xml) {
        return xml != null && xml.contains("<sitemapindex");
    }

    public static List<String> locations(String xml) {
        if (xml == null || xml.isBlank()) {
            return List.of();
        }
        List<String> locations = new ArrayList<>();
        Matcher matcher = LOC.matcher(xml);
        while (matcher.find()) {
            String location = matcher.group(1)
                    .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
                    .replace("&quot;", "\"").replace("&apos;", "'");
            if (!location.isBlank()) {
                locations.add(location);
            }
        }
        return List.copyOf(locations);
    }
}