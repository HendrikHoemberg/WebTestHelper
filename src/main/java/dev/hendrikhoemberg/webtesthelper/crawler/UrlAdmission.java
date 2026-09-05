package dev.hendrikhoemberg.webtesthelper.crawler;

import dev.hendrikhoemberg.webtesthelper.model.NormalizedUrl;
import dev.hendrikhoemberg.webtesthelper.model.SiteContext;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/** Decides what may enter the crawl frontier. Pure; one instance per run. */
public final class UrlAdmission {

    public enum Reason { OK, BAD_SCHEME, OFF_SITE, NOT_NAVIGABLE, TOO_DEEP, EXCLUDED,
                         NOT_INCLUDED, ROBOTS }

    public record Decision(boolean admitted, Reason reason) {
        static final Decision OK = new Decision(true, Reason.OK);

        static Decision no(Reason reason) {
            return new Decision(false, reason);
        }
    }

    /**
     * Extensions a browser must not be sent to (deviation D10). These are assets: Plan 3
     * verifies them over HTTP on virtual threads, which is minutes instead of hours (spec 5.3).
     */
    private static final Set<String> NOT_NAVIGABLE = Set.of(
            "pdf", "zip", "rar", "7z", "gz", "tar", "doc", "docx", "xls", "xlsx", "ppt", "pptx",
            "csv", "png", "jpg", "jpeg", "gif", "webp", "avif", "svg", "ico", "bmp", "tif", "tiff",
            "mp3", "mp4", "wav", "ogg", "webm", "mov", "avi", "css", "js", "json", "xml", "rss",
            "woff", "woff2", "ttf", "eot", "exe", "dmg", "apk");

    private final SiteContext site;
    private final RobotsRules robots;
    private final List<Pattern> compiledExcludePatterns;
    private final List<Pattern> compiledIncludePatterns;

    public UrlAdmission(SiteContext site, RobotsRules robots) {
        this.site = Objects.requireNonNull(site, "site must not be null");
        this.robots = Objects.requireNonNull(robots, "robots must not be null");
        this.compiledExcludePatterns = site.excludePatterns().stream().map(UrlAdmission::globOf).toList();
        this.compiledIncludePatterns = site.includePatterns().stream().map(UrlAdmission::globOf).toList();
    }

    public SiteContext site() {
        return site;
    }

    public RobotsRules robots() {
        return robots;
    }

    /**
     * Whether a URL may be verified over HTTP (deviation D19). The verifier's politeness gate:
     * any http(s) URL off the site is fair game, and an internal one is verifiable whenever
     * robots permits it. Depth and include/exclude patterns do not matter here — a URL the
     * crawl chose not to navigate is still something we may ping, and robots is the only
     * exclusion the verifier honours.
     */
    public boolean verifiable(NormalizedUrl url) {
        if (!"http".equals(url.scheme()) && !"https".equals(url.scheme())) {
            return false;
        }
        if (!site.baseUrl().sameSiteAs(url)) {
            return true;
        }
        return !site.respectRobots() || robots.allows(url.locationKey());
    }

    public Decision admit(NormalizedUrl url, int depth) {
        if (!"http".equals(url.scheme()) && !"https".equals(url.scheme())) {
            return Decision.no(Reason.BAD_SCHEME);
        }
        if (!site.baseUrl().sameSiteAs(url)) {
            return Decision.no(Reason.OFF_SITE);
        }
        if (NOT_NAVIGABLE.contains(extensionOf(url.path()))) {
            return Decision.no(Reason.NOT_NAVIGABLE);
        }
        if (depth > site.budget().maxDepth()) {
            return Decision.no(Reason.TOO_DEEP);
        }
        String locationKey = url.locationKey();
        if (matchesAny(compiledExcludePatterns, locationKey)) {
            return Decision.no(Reason.EXCLUDED);
        }
        if (!compiledIncludePatterns.isEmpty() && !matchesAny(compiledIncludePatterns, locationKey)) {
            return Decision.no(Reason.NOT_INCLUDED);
        }
        if (site.respectRobots() && !robots.allows(locationKey)) {
            return Decision.no(Reason.ROBOTS);
        }
        return Decision.OK;
    }

    private static String extensionOf(String path) {
        int slash = path.lastIndexOf('/');
        int dot = path.lastIndexOf('.');
        return dot > slash ? path.substring(dot + 1).toLowerCase(Locale.ROOT) : "";
    }

    private static boolean matchesAny(List<Pattern> patterns, String locationKey) {
        return patterns.stream().anyMatch(pattern -> pattern.matcher(locationKey).matches());
    }

    /** Deviation D8: {@code *} is any run of characters, {@code ?} exactly one, anchored. */
    private static Pattern globOf(String glob) {
        StringBuilder regex = new StringBuilder();
        for (int i = 0; i < glob.length(); i++) {
            char c = glob.charAt(i);
            switch (c) {
                case '*' -> regex.append(".*");
                case '?' -> regex.append('.');
                default -> regex.append(Pattern.quote(String.valueOf(c)));
            }
        }
        return Pattern.compile(regex.toString());
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        UrlAdmission that = (UrlAdmission) o;
        return Objects.equals(site, that.site) && Objects.equals(robots, that.robots);
    }

    @Override
    public int hashCode() {
        return Objects.hash(site, robots);
    }

    @Override
    public String toString() {
        return "UrlAdmission[" +
                "site=" + site + ", " +
                "robots=" + robots + ']';
    }
}