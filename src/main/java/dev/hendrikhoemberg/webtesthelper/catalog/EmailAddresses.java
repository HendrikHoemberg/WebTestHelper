package dev.hendrikhoemberg.webtesthelper.catalog;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * The one shape check and the one list-splitting rule for notification addresses.
 *
 * <p>Deliberately not an RFC parser: the failure mode of a too-strict check here is a colleague who
 * cannot be mailed at all. It lives in one place for the same reason the finding renderer does
 * (D57) — three copies of a validation rule are three rules the first time one of them is softened.
 */
public final class EmailAddresses {

    private static final Pattern SHAPE = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");

    /** Comma, semicolon or whitespace: someone pasting three addresses will use all three. */
    private static final Pattern SEPARATORS = Pattern.compile("[,;\\s]+");

    private EmailAddresses() {
    }

    /** Strips surrounding whitespace and lowercases, so the same address in two cases is one. */
    public static String normalize(String raw) {
        return raw == null ? "" : raw.strip().toLowerCase(Locale.ROOT);
    }

    public static boolean isValid(String raw) {
        String normalized = normalize(raw);
        return !normalized.isEmpty() && SHAPE.matcher(normalized).matches();
    }

    /** Splits a free-text list into normalised, de-duplicated addresses, order preserved. */
    public static List<String> split(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        return Arrays.stream(SEPARATORS.split(raw))
                .map(EmailAddresses::normalize)
                .filter(s -> !s.isEmpty())
                .distinct()
                .toList();
    }

    /** True when every address in a free-text list passes {@link #isValid}. An empty list is fine. */
    public static boolean allValid(String raw) {
        return split(raw).stream().allMatch(EmailAddresses::isValid);
    }
}
