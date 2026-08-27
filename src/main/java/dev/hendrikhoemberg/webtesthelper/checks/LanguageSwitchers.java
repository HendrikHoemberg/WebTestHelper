package dev.hendrikhoemberg.webtesthelper.checks;

import dev.hendrikhoemberg.webtesthelper.model.NormalizedUrl;
import dev.hendrikhoemberg.webtesthelper.model.SimHash;
import dev.hendrikhoemberg.webtesthelper.model.UrlNormalizer;

import java.text.Normalizer;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

import static java.util.Map.entry;

/**
 * Pure decision logic for the language switcher interaction check (spec 7.2, D83, D84).
 *
 * <p>Selection decides what gets clicked; the verdict evaluates whether language switching
 * succeeded.
 */
public final class LanguageSwitchers {

    public record LocaleLink(int index, String href, String hreflang, String label, boolean visible) {
    }

    public record LocaleTarget(int index, NormalizedUrl url, String label) {
    }

    public record Observation(NormalizedUrl url, String htmlLang, String text) {
    }

    public enum SwitchVerdict {
        OK,
        NO_NAVIGATION,
        LANG_UNCHANGED,
        SAME_CONTENT
    }

    private static final Map<String, String> LANGUAGE_WORDS = Map.ofEntries(
            entry("deutsch", "de"), entry("german", "de"), entry("de", "de"),
            entry("english", "en"), entry("englisch", "en"), entry("en", "en"),
            entry("francais", "fr"), entry("franzosisch", "fr"), entry("fr", "fr"),
            entry("italiano", "it"), entry("italienisch", "it"), entry("it", "it"),
            entry("espanol", "es"), entry("spanisch", "es"), entry("es", "es"),
            entry("nederlands", "nl"), entry("niederlandisch", "nl"), entry("nl", "nl"),
            entry("polski", "pl"), entry("polnisch", "pl"), entry("pl", "pl"));

    private static final Pattern COMBINING_MARKS = Pattern.compile("\\p{M}+");

    private LanguageSwitchers() {
    }

    /**
     * Selects and deduplicates target URLs to test as language switchers.
     *
     * <ol>
     *   <li>Drop invisible links.</li>
     *   <li>Keep links that look like language switchers (non-blank hreflang, label matching
     *       vocabulary, or href whose first path segment is 2 letters or carries lang/hl/locale
     *       query parameter).</li>
     *   <li>Resolve href against current URL; drop unresolvable or cross-site targets.</li>
     *   <li>Drop the active locale's own link (target equals current AND hreflang/label matches
     *       currentLang).</li>
     *   <li>Dedupe by target URL keeping the lowest index, sort by {@link NormalizedUrl#value()},
     *       and truncate to {@code max}.</li>
     * </ol>
     */
    public static List<LocaleTarget> select(List<LocaleLink> harvested, NormalizedUrl current,
                                            String currentLang, int max) {
        if (harvested == null || current == null || max <= 0) {
            return List.of();
        }

        Map<NormalizedUrl, LocaleTarget> targetsByUrl = new LinkedHashMap<>();
        for (LocaleLink link : harvested) {
            if (!link.visible()) {
                continue;
            }
            if (!looksLikeLanguageSwitch(link)) {
                continue;
            }
            Optional<NormalizedUrl> resolved = UrlNormalizer.resolve(current.value(), link.href());
            if (resolved.isEmpty()) {
                continue;
            }
            NormalizedUrl target = resolved.get();
            if (!target.sameSiteAs(current)) {
                continue;
            }
            if (target.equals(current)) {
                boolean hreflangMatches = isSameLanguage(link.hreflang(), currentLang);
                String labelLang = languageFromLabel(link.label());
                boolean labelMatches = isSameLanguage(labelLang, currentLang);
                if (hreflangMatches || labelMatches) {
                    continue;
                }
            }
            LocaleTarget existing = targetsByUrl.get(target);
            if (existing == null || link.index() < existing.index()) {
                targetsByUrl.put(target, new LocaleTarget(link.index(), target, link.label()));
            }
        }

        return targetsByUrl.values().stream()
                .sorted(Comparator.comparing(t -> t.url().value()))
                .limit(max)
                .toList();
    }

    /**
     * Evaluates whether switching language succeeded.
     *
     * <ol>
     *   <li>{@code after.url} equals {@code before.url} &rarr; {@code NO_NAVIGATION}.</li>
     *   <li>{@code after.htmlLang} is blank, or equals {@code before.htmlLang} ignoring case &rarr;
     *       {@code LANG_UNCHANGED}.</li>
     *   <li>Either text has fewer than 20 words &rarr; {@code OK}. Otherwise if
     *       {@code SimHash.hammingDistance} &le; {@code maxTextDistance} &rarr;
     *       {@code SAME_CONTENT}.</li>
     *   <li>Otherwise &rarr; {@code OK}.</li>
     * </ol>
     */
    public static SwitchVerdict verdict(Observation before, Observation after, int maxTextDistance) {
        if (before == null || after == null) {
            return SwitchVerdict.OK;
        }
        if (Objects.equals(after.url(), before.url())) {
            return SwitchVerdict.NO_NAVIGATION;
        }
        if (after.htmlLang() == null || after.htmlLang().isBlank()
                || after.htmlLang().equalsIgnoreCase(before.htmlLang())) {
            return SwitchVerdict.LANG_UNCHANGED;
        }
        if (wordCount(before.text()) < 20 || wordCount(after.text()) < 20) {
            return SwitchVerdict.OK;
        }
        int distance = SimHash.hammingDistance(SimHash.of(before.text()), SimHash.of(after.text()));
        if (distance <= maxTextDistance) {
            return SwitchVerdict.SAME_CONTENT;
        }
        return SwitchVerdict.OK;
    }

    private static boolean looksLikeLanguageSwitch(LocaleLink link) {
        if (link.hreflang() != null && !link.hreflang().isBlank()) {
            return true;
        }
        if (matchesLabelVocabulary(link.label())) {
            return true;
        }
        return looksLikeLanguageHref(link.href());
    }

    static boolean matchesLabelVocabulary(String label) {
        return languageFromLabel(label) != null;
    }

    private static String languageFromLabel(String label) {
        if (label == null || label.isBlank()) {
            return null;
        }
        String trimmed = label.trim();
        if (trimmed.length() > 24) {
            return null;
        }
        String folded = fold(trimmed);
        return LANGUAGE_WORDS.get(folded);
    }

    static String fold(String text) {
        if (text == null) {
            return "";
        }
        String replaced = text.trim().replace("ß", "ss").replace("ẞ", "ss");
        String normalized = Normalizer.normalize(replaced, Normalizer.Form.NFD);
        return COMBINING_MARKS.matcher(normalized).replaceAll("").toLowerCase(Locale.ROOT);
    }

    private static boolean looksLikeLanguageHref(String href) {
        if (href == null || href.isBlank()) {
            return false;
        }
        return hasLanguageQueryParam(href) || isFirstPathSegmentTwoLetterCode(href);
    }

    private static boolean hasLanguageQueryParam(String href) {
        int qIndex = href.indexOf('?');
        if (qIndex < 0) {
            return false;
        }
        int hashIndex = href.indexOf('#', qIndex);
        String query = hashIndex >= 0 ? href.substring(qIndex + 1, hashIndex) : href.substring(qIndex + 1);
        if (query.isEmpty()) {
            return false;
        }
        for (String pair : query.split("&")) {
            if (pair.isEmpty()) {
                continue;
            }
            int eq = pair.indexOf('=');
            String name = eq >= 0 ? pair.substring(0, eq) : pair;
            String trimmedName = name.trim();
            if (trimmedName.equalsIgnoreCase("lang")
                    || trimmedName.equalsIgnoreCase("hl")
                    || trimmedName.equalsIgnoreCase("locale")) {
                return true;
            }
        }
        return false;
    }

    private static boolean isFirstPathSegmentTwoLetterCode(String href) {
        int qIndex = href.indexOf('?');
        int hashIndex = href.indexOf('#');
        int cut = href.length();
        if (qIndex >= 0 && qIndex < cut) {
            cut = qIndex;
        }
        if (hashIndex >= 0 && hashIndex < cut) {
            cut = hashIndex;
        }
        String pathPart = href.substring(0, cut).trim();

        if (pathPart.startsWith("//")) {
            int slash = pathPart.indexOf('/', 2);
            pathPart = slash >= 0 ? pathPart.substring(slash) : "";
        } else if (pathPart.contains("://")) {
            int schemeEnd = pathPart.indexOf("://");
            int slash = pathPart.indexOf('/', schemeEnd + 3);
            pathPart = slash >= 0 ? pathPart.substring(slash) : "";
        }

        for (String segment : pathPart.split("/")) {
            if (!segment.isEmpty()) {
                return isTwoLetterCode(segment);
            }
        }
        return false;
    }

    private static boolean isTwoLetterCode(String s) {
        return s.length() == 2 && isAsciiLetter(s.charAt(0)) && isAsciiLetter(s.charAt(1));
    }

    private static boolean isAsciiLetter(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z');
    }

    private static boolean isSameLanguage(String lang1, String lang2) {
        if (lang1 == null || lang1.isBlank() || lang2 == null || lang2.isBlank()) {
            return false;
        }
        String code1 = extractLanguageCode(lang1);
        String code2 = extractLanguageCode(lang2);
        return !code1.isEmpty() && code1.equalsIgnoreCase(code2);
    }

    private static String extractLanguageCode(String lang) {
        if (lang == null || lang.isBlank()) {
            return "";
        }
        String trimmed = lang.trim().toLowerCase(Locale.ROOT);
        int sep = trimmed.indexOf('-');
        if (sep < 0) {
            sep = trimmed.indexOf('_');
        }
        return sep >= 0 ? trimmed.substring(0, sep) : trimmed;
    }

    private static int wordCount(String text) {
        if (text == null || text.isBlank()) {
            return 0;
        }
        String[] words = text.trim().split("\\s+");
        return words.length;
    }
}
