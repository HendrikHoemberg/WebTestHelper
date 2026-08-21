package dev.hendrikhoemberg.webtesthelper.model;

import java.net.IDN;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Reduces raw {@code href}s to their canonical {@link NormalizedUrl} form.
 *
 * <p>This class is the crawl frontier's dedupe key, the external URL cache's primary key and a
 * finding's {@code subjectKey}. Without this normalisation the same dead link fingerprints
 * differently when found on two pages and the diff is worthless; a bug here shows up as duplicate
 * crawling, duplicate findings, or findings that flicker between runs.
 *
 * <p>Every public method is a total function: it returns {@link Optional#empty()} (or a fallback)
 * rather than throwing. There is no I/O, no Spring and no logging.
 *
 * <p>Deviation D4: scheme and host are lowercased but the path and query keep their case, because
 * lowercasing the whole URL would merge distinct resources on a case-sensitive server and corrupt
 * both the frontier and the URL cache.
 */
public final class UrlNormalizer {

    private UrlNormalizer() {
    }

    /** Canonicalise {@code value}. */
    public static Optional<NormalizedUrl> normalize(String value) {
        return parse(value).flatMap(UrlNormalizer::build);
    }

    /** Canonical string form, used as the {@code subjectKey} and the external URL cache key. */
    public static Optional<String> key(String value) {
        return normalize(value).map(NormalizedUrl::value);
    }

    /** Resolve {@code href} against {@code base} and canonicalise the result. */
    public static Optional<NormalizedUrl> resolve(String base, String href) {
        if (base == null || href == null) {
            return Optional.empty();
        }
        String cleaned = cleanHref(href);
        if (cleaned.isEmpty() || cleaned.startsWith("#")) {
            return Optional.empty();
        }
        if (hasNonWebScheme(cleaned)) {
            return Optional.empty();
        }
        try {
            URI baseUri = new URI(base);
            URI resolved = baseUri.resolve(cleaned);
            return parse(resolved.toString()).flatMap(UrlNormalizer::build);
        } catch (IllegalArgumentException | URISyntaxException e) {
            return Optional.empty();
        }
    }

    /** {@code locationKey} of the spec: path plus surviving query. */
    public static String locationKeyOf(String value) {
        return normalize(value).map(NormalizedUrl::locationKey).orElse(value);
    }

    /** True when two URLs share a registrable host, ignoring a leading {@code www.}. */
    public static boolean isSameSite(NormalizedUrl a, NormalizedUrl b) {
        return a != null && a.sameSiteAs(b);
    }

    /**
     * True when {@code value} carries a legal but non-web scheme (e.g. {@code mailto:}, {@code
     * tel:}, {@code javascript:}, {@code ftp:}). Scheme-less relative references and
     * protocol-relative refs (which have no scheme before a colon) pass through to resolution.
     * Requires a legal scheme grammar (letters/digits/{@code + - .}, starting with a letter) so a
     * path such as {@code /pfad:mit-doppelpunkt} is not misread as a scheme.
     */
    private static boolean hasNonWebScheme(String value) {
        int colon = value.indexOf(':');
        if (colon <= 0) {
            return false;
        }
        String scheme = value.substring(0, colon);
        if (!Character.isLetter(scheme.charAt(0))) {
            return false;
        }
        for (int i = 1; i < scheme.length(); i++) {
            char c = scheme.charAt(i);
            if (!Character.isLetterOrDigit(c) && c != '+' && c != '-' && c != '.') {
                return false;
            }
        }
        return !"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme);
    }

    /** Per WHATWG, tabs, newlines and carriage returns inside an href are removed, not encoded. */
    private static String cleanHref(String href) {
        StringBuilder sb = new StringBuilder(href.length());
        for (int i = 0; i < href.length(); i++) {
            char c = href.charAt(i);
            if (c != '\t' && c != '\n' && c != '\r') {
                sb.append(c);
            }
        }
        return sb.toString().trim();
    }

    /**
     * Parse {@code value} into a {@link URI}. On syntax failure the raw text is percent-encoded
     * (space and controls {@code <= 0x20}, non-ASCII {@code >= 0x7f}, and the characters
     * {@code " < > [ ] \ ^ ` { | }}) as UTF-8 and the parse is retried once.
     */
    private static Optional<URI> parse(String value) {
        if (value == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(new URI(value));
        } catch (URISyntaxException e) {
            try {
                return Optional.of(new URI(encodeIllegal(value)));
            } catch (URISyntaxException | IllegalArgumentException e2) {
                return Optional.empty();
            }
        }
    }

    private static String encodeIllegal(String value) {
        StringBuilder sb = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c <= 0x20 || c >= 0x7f || "\"<>[\\]^`{|}".indexOf(c) >= 0) {
                appendPercent(sb, c);
            } else if (c == '%') {
                if (i + 2 < value.length() && isHex(value.charAt(i + 1)) && isHex(value.charAt(i + 2))) {
                    sb.append(c).append(value.charAt(i + 1)).append(value.charAt(i + 2));
                    i += 2;
                } else {
                    appendPercent(sb, c);
                }
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private static boolean isHex(char c) {
        return (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
    }

    private static void appendPercent(StringBuilder sb, char c) {
        byte[] utf8 = String.valueOf(c).getBytes(java.nio.charset.StandardCharsets.UTF_8);
        for (byte b : utf8) {
            sb.append('%');
            sb.append(String.format(Locale.ROOT, "%02X", b & 0xff));
        }
    }

    /** Build a canonical {@link NormalizedUrl} from a parsed {@link URI}. */
    private static Optional<NormalizedUrl> build(URI uri) {
        if (uri.isOpaque() || uri.getScheme() == null) {
            return Optional.empty();
        }
        String scheme = uri.getScheme().toLowerCase(Locale.ROOT);
        if (!"http".equals(scheme) && !"https".equals(scheme)) {
            return Optional.empty();
        }

        AuthorityParts authority = authorityOf(uri, scheme);
        if (authority == null || authority.host() == null || authority.host().isEmpty()) {
            return Optional.empty();
        }

        String host = normalizeHost(authority.host());
        if (host == null || host.isEmpty()) {
            return Optional.empty();
        }

        String rawPath = uri.getRawPath();
        String path = normalizePath(rawPath == null || rawPath.isEmpty() ? "/" : rawPath);

        Map<String, List<String>> query = splitQuery(uri.getRawQuery());
        String queryString = sortAndFilter(query);

        return Optional.of(new NormalizedUrl(scheme, host, authority.port(), path, queryString));
    }

    /** Host and effective port, or empty when the authority is unparseable. */
    private static AuthorityParts authorityOf(URI uri, String scheme) {
        String host = uri.getHost();
        if (host != null) {
            return new AuthorityParts(host, portOf(uri.getPort(), scheme));
        }
        if (uri.isOpaque()) {
            return null;
        }
        String rawAuthority = uri.getRawAuthority();
        if (rawAuthority == null || rawAuthority.isEmpty()) {
            return null;
        }
        int at = rawAuthority.lastIndexOf('@');
        String authority = at >= 0 ? rawAuthority.substring(at + 1) : rawAuthority;
        if (authority.startsWith("[")) {
            int close = authority.indexOf(']');
            if (close <= 0) {
                return null;
            }
            String ipv6 = authority.substring(1, close);
            String h;
            try {
                h = java.net.InetAddress.getByName(ipv6).getHostAddress();
            } catch (java.net.UnknownHostException e) {
                h = ipv6.startsWith("::") ? ipv6 : null;
            }
            if (h == null) {
                return null;
            }
            int p = portFromText(authority.substring(close + 1), scheme);
            return p < 0 ? null : new AuthorityParts(h, p);
        }
        int colon = authority.lastIndexOf(':');
        String hostPart = colon >= 0 ? authority.substring(0, colon) : authority;
        if (hostPart.isEmpty()) {
            return null;
        }
        String portText = colon >= 0 ? authority.substring(colon + 1) : null;
        int p = portFromText(portText, scheme);
        return p < 0 ? null : new AuthorityParts(hostPart, p);
    }

    /** Port from a URI host when the host parses natively. */
    private static int portOf(int uriPort, String scheme) {
        if (uriPort >= 0 && uriPort <= 65535) {
            return uriPort;
        }
        if (uriPort < -1) { // URI semantics: -1 means absent, < -1 means undefined
            return -1;
        }
        return NormalizedUrl.defaultPort(scheme);
    }

    /** Port from an authority suffix such as {@code :8080}, or the default when absent. */
    private static int portFromText(String text, String scheme) {
        if (text == null || text.isEmpty()) {
            return NormalizedUrl.defaultPort(scheme);
        }
        if (!text.chars().allMatch(Character::isDigit)) {
            return -1;
        }
        try {
            int p = Integer.parseInt(text);
            return p >= 1 && p <= 65535 ? p : -1;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private record AuthorityParts(String host, int port) {
    }

    /**
     * Lowercase, punycode an IDN and drop a trailing root label dot {@code example.com. → example.com}.
     */
    private static String normalizeHost(String host) {
        String lower = host.toLowerCase(Locale.ROOT);
        String withoutDot = lower.endsWith(".") ? lower.substring(0, lower.length() - 1) : lower;
        try {
            return IDN.toASCII(withoutDot);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static String normalizePath(String rawPath) {
        String path = removeDotSegmentsAndCollapse(rawPath);
        return normalizePercentEncoding(cleanAndEncode(path));
    }

    /**
     * Remove dot segments per RFC 3986 §5.2.4 on the raw path (before percent-decoding, so {@code
     * %2F} stays a literal segment separator) and collapse runs of slashes. {@code /a/b/../c →
     * /a/c}, {@code /a//b → /a/b}. Then strip a trailing slash except at the root.
     */
    private static String removeDotSegmentsAndCollapse(String path) {
        boolean endsWithSlash = path.endsWith("/");
        String collapsed = path.replaceAll("/{2,}", "/");
        String normalized = URI.create("http://x" + collapsed).normalize().getRawPath();
        if (normalized.isEmpty()) {
            normalized = "/";
        }
        normalized = removeLeadingRelativeSegments(normalized);
        if (endsWithSlash && normalized.length() > 1 && !normalized.endsWith("/")) {
            normalized = normalized + "/";
        }
        if (normalized.length() > 1 && normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    /**
     * RFC 3986 remove_dot_segments removes dot segments wherever they occur, including leading
     * {@code ..} segments that would otherwise climb above the root (which URI#normalize leaves
     * behind). {@code /../../ → /}, {@code /a/../.. → /}.
     */
    private static String removeLeadingRelativeSegments(String path) {
        String[] segments = path.split("/", -1);
        List<String> out = new ArrayList<>();
        for (String segment : segments) {
            if (segment.equals("..") || segment.equals(".")) {
                continue;
            }
            out.add(segment);
        }
        String joined = String.join("/", out);
        return joined.isEmpty() ? "/" : joined;
    }

    /**
     * Encode characters real markup contains and RFC 3986 forbids (space/controls, quotes, angle
     * brackets, {@code \ ^ ` { | }}, non-ASCII), then uppercase existing percent-escape hex digits
     * and decode escapes of unreserved characters.
     */
    private static String cleanAndEncode(String path) {
        StringBuilder sb = new StringBuilder(path.length());
        for (int i = 0; i < path.length(); i++) {
            char c = path.charAt(i);
            if (c <= 0x20 || c >= 0x7f || "\"<>\\^`{|}".indexOf(c) >= 0) {
                appendPercent(sb, c);
            } else if (c == '%') {
                if (i + 2 < path.length() && isHex(path.charAt(i + 1)) && isHex(path.charAt(i + 2))) {
                    sb.append(c).append(path.charAt(i + 1)).append(path.charAt(i + 2));
                    i += 2;
                } else {
                    sb.append('%');
                }
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    /**
     * Uppercase percent-escape hex (RFC 3986 §6.2.2.1) and decode escapes of unreserved characters
     * so {@code /a%2fb%7ec%41d → /a%2Fb~cAd}.
     */
    private static String normalizePercentEncoding(String path) {
        StringBuilder sb = new StringBuilder(path.length());
        for (int i = 0; i < path.length(); i++) {
            char c = path.charAt(i);
            if (c == '%' && i + 2 < path.length() && isHex(path.charAt(i + 1)) && isHex(path.charAt(i + 2))) {
                int hi = Character.digit(path.charAt(i + 1), 16);
                int lo = Character.digit(path.charAt(i + 2), 16);
                int code = hi * 16 + lo;
                if (isUnreserved(code)) {
                    sb.append((char) code);
                } else {
                    sb.append('%');
                    sb.append(toUpperHex(path.charAt(i + 1)));
                    sb.append(toUpperHex(path.charAt(i + 2)));
                }
                i += 2;
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private static char toUpperHex(char c) {
        return Character.toUpperCase(c);
    }

    /** RFC 3986 unreserved: ALPHA / DIGIT / {@code - . _ ~}. */
    private static boolean isUnreserved(int code) {
        return (code >= 'a' && code <= 'z') || (code >= 'A' && code <= 'Z')
                || (code >= '0' && code <= '9') || code == '-' || code == '.' || code == '_' || code == '~';
    }

    /**
     * Query key/value pairs. Each name and value is run through the same conservative percent
     * normalisation the path uses (RFC 3986 §6.2.2.1): raw non-ASCII and illegal characters are
     * percent-encoded, unreserved single-byte escapes are decoded and everything else (notably
     * UTF-8 sequences and {@code %2F}) stays percent-encoded. This way {@code q=über} and
     * {@code q=%C3%BCber} fingerprint identically instead of mojibake'ing apart.
     */
    private static Map<String, List<String>> splitQuery(String rawQuery) {
        Map<String, List<String>> map = new LinkedHashMap<>();
        if (rawQuery == null || rawQuery.isEmpty()) {
            return map;
        }
        for (String pair : rawQuery.split("&")) {
            if (pair.isEmpty()) {
                continue;
            }
            int eq = pair.indexOf('=');
            String key;
            String value;
            if (eq >= 0) {
                key = normalizePercentEncoding(cleanAndEncode(pair.substring(0, eq)));
                value = normalizePercentEncoding(cleanAndEncode(pair.substring(eq + 1)));
            } else {
                key = normalizePercentEncoding(cleanAndEncode(pair));
                value = "";
            }
            map.computeIfAbsent(key, k -> new ArrayList<>()).add(value);
        }
        return map;
    }

    /**
     * Retain only the pairs that survive tracking-parameter stripping, sorted by name then value.
     * An empty result means the query has no surviving pairs and is dropped entirely.
     */
    private static String sortAndFilter(Map<String, List<String>> query) {
        if (query.isEmpty()) {
            return null;
        }
        List<Map.Entry<String, List<String>>> kept = new ArrayList<>();
        for (Map.Entry<String, List<String>> entry : query.entrySet()) {
            if (!isTracking(entry.getKey())) {
                kept.add(entry);
            }
        }
        if (kept.isEmpty()) {
            return null;
        }
        kept.sort(Comparator.comparing(Map.Entry<String, List<String>>::getKey)
                .thenComparing(e -> String.join("\u0000", e.getValue())));
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, List<String>> entry : kept) {
            List<String> values = entry.getValue();
            values.sort(Comparator.naturalOrder());
            for (String value : values) {
                if (sb.length() > 0) {
                    sb.append('&');
                }
                sb.append(entry.getKey());
                if (!value.isEmpty()) {
                    sb.append('=').append(value);
                }
            }
        }
        return sb.toString();
    }

    private static boolean isTracking(String key) {
        return TRACKING_PARAMS.contains(key)
                || key.startsWith("utm_")
                || key.startsWith("matomo_")
                || key.startsWith("piwik_");
    }

    private static final Set<String> TRACKING_PARAMS = java.util.Collections.unmodifiableSet(
            new LinkedHashSet<>(List.of(
                    "gclid", "gclsrc", "dclid", "fbclid", "msclkid", "yclid", "igshid", "twclid",
                    "mc_cid", "mc_eid", "_ga", "_gl", "vero_id", "wickedid", "oly_enc_id",
                    "oly_anon_id", "hsa_acc", "hsa_cam", "hsa_grp", "hsa_ad", "hsa_src", "hsa_tgt",
                    "hsa_kw", "hsa_mt", "hsa_net", "hsa_ver", "_hsenc", "_hsmi", "pk_campaign",
                    "pk_kwd", "pk_source", "pk_medium", "mtm_campaign", "mtm_keyword", "mtm_source",
                    "mtm_medium")));
}
