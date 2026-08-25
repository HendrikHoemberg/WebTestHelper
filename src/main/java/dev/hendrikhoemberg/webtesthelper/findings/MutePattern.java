package dev.hendrikhoemberg.webtesthelper.findings;

/**
 * Translates human-entered glob patterns to SQL LIKE expressions for mute rule matching.
 * Wildcard semantics (§13.1, D48): '*' is the only wildcard supported; everything else is a literal.
 */
public final class MutePattern {

    private MutePattern() {
    }

    /**
     * Translates a glob pattern to a SQL LIKE pattern.
     * '*' becomes '%', while '%', '_', and '\' are escaped with backslash.
     */
    public static String toLikePattern(String glob) {
        if (glob == null) {
            return null;
        }
        StringBuilder out = new StringBuilder(glob.length() + 4);
        for (char c : glob.toCharArray()) {
            switch (c) {
                case '*' -> out.append('%');
                case '%', '_', '\\' -> out.append('\\').append(c);
                default -> out.append(c);
            }
        }
        return out.toString();
    }

    /**
     * Checks if a glob pattern is null, empty, or whitespace-only.
     */
    public static boolean isBlank(String glob) {
        return glob == null || glob.isBlank();
    }
}
