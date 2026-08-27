package dev.hendrikhoemberg.webtesthelper.checks;

import dev.hendrikhoemberg.webtesthelper.model.NormalizedUrl;
import dev.hendrikhoemberg.webtesthelper.model.UrlNormalizer;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Pure candidate selection and safety rules for button reachability (spec 7.2, D83, D85).
 *
 * <p>Decides which interactive controls are safe to click during interaction checks,
 * ensuring forms, purchases, destructive actions, external navigations, and consent
 * overlays are never triggered.
 */
public final class Clickables {

    public record Clickable(int index, String tag, String type, String label, String href,
                            boolean inForm, boolean disabled, boolean visible, String target) {
    }

    private static final List<String> NEVER_CLICK = List.of(
            "loschen", "entfernen", "delete", "remove", "leeren", "zurucksetzen",
            "abmelden", "logout", "anmelden", "login", "registrieren", "abbestellen", "kundigen",
            "bestellen", "kaufen", "zahlungspflichtig", "warenkorb", "bezahlen",
            "absenden", "abschicken", "senden", "submit", "download", "herunterladen", "drucken");

    private static final Set<String> COOKIE_VOCABULARY = Stream.concat(
            CookieBanner.ACCEPT_LABELS.stream(),
            Stream.of("Nur notwendige", "Ablehnen")
    ).map(LanguageSwitchers::fold).collect(Collectors.toUnmodifiableSet());

    private Clickables() {
    }

    /**
     * Selects safe clickable candidates from harvested DOM elements.
     *
     * <ol>
     *   <li>Drop invisible or disabled controls.</li>
     *   <li>Drop controls in forms or with type submit/reset.</li>
     *   <li>Drop controls with target {@code _blank}.</li>
     *   <li>Drop anchor tags whose href resolves to anything other than the current page.</li>
     *   <li>Drop controls whose folded label contains a never-click token.</li>
     *   <li>Drop controls whose folded label matches the cookie consent vocabulary.</li>
     *   <li>Dedupe by {@code (label, index)}, sort by {@code index}, and truncate to {@code max}.</li>
     * </ol>
     */
    public static List<Clickable> select(List<Clickable> harvested, NormalizedUrl current, int max) {
        if (harvested == null || current == null || max <= 0) {
            return List.of();
        }

        Map<ClickableKey, Clickable> deduped = new LinkedHashMap<>();
        for (Clickable candidate : harvested) {
            if (candidate == null) {
                continue;
            }
            if (!isSafe(candidate, current)) {
                continue;
            }
            deduped.putIfAbsent(new ClickableKey(candidate.label(), candidate.index()), candidate);
        }

        return deduped.values().stream()
                .sorted(Comparator.comparingInt(Clickable::index))
                .limit(max)
                .toList();
    }

    private static boolean isSafe(Clickable c, NormalizedUrl current) {
        // 1. Not visible, or disabled -> drop
        if (!c.visible() || c.disabled()) {
            return false;
        }

        // 2. inForm, or type is submit or reset -> drop
        if (c.inForm() || isSubmitOrReset(c.type())) {
            return false;
        }

        // 3. target is _blank -> drop
        if (c.target() != null && "_blank".equalsIgnoreCase(c.target().trim())) {
            return false;
        }

        // 4. tag is a and href resolves to something other than current -> drop
        if (c.tag() != null && "a".equalsIgnoreCase(c.tag().trim())) {
            if (c.href() != null && !c.href().isBlank()) {
                Optional<NormalizedUrl> resolved = UrlNormalizer.resolve(current.value(), c.href());
                if (resolved.isPresent() && !resolved.get().equals(current)) {
                    return false;
                }
            }
        }

        String foldedLabel = LanguageSwitchers.fold(c.label());

        // 5. Folded label contains a never-click token -> drop
        if (!foldedLabel.isEmpty()) {
            for (String token : NEVER_CLICK) {
                if (foldedLabel.contains(token)) {
                    return false;
                }
            }
        }

        // 6. Folded label matches CookieBanner accept vocabulary or "Nur notwendige" / "Ablehnen" -> drop
        if (!foldedLabel.isEmpty() && COOKIE_VOCABULARY.contains(foldedLabel)) {
            return false;
        }

        return true;
    }

    private static boolean isSubmitOrReset(String type) {
        if (type == null || type.isBlank()) {
            return false;
        }
        String trimmed = type.trim();
        return "submit".equalsIgnoreCase(trimmed) || "reset".equalsIgnoreCase(trimmed);
    }

    private record ClickableKey(String label, int index) {
    }
}
