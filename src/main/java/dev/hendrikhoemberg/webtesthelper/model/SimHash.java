package dev.hendrikhoemberg.webtesthelper.model;

import java.util.Locale;

/**
 * 64-bit SimHash over word trigrams. Near-duplicate detection for soft-404s (spec 7.1):
 * a not-found page that echoes the requested path differs textually from the probe but must
 * still be recognised as the same page.
 *
 * <p>Trigrams rather than single words: single words make every German page of similar
 * vocabulary look alike, which is how a soft-404 detector starts eating real pages.
 */
public final class SimHash {

    private SimHash() {
    }

    public static long of(String text) {
        if (text == null || text.isBlank()) {
            return 0L;
        }
        String[] words = text.toLowerCase(Locale.ROOT).split("\\W+");
        int[] bits = new int[64];
        int shingles = 0;
        for (int i = 0; i + 2 < words.length; i++) {
            if (words[i].isEmpty()) {
                continue;
            }
            long hash = hash64(words[i] + ' ' + words[i + 1] + ' ' + words[i + 2]);
            shingles++;
            for (int bit = 0; bit < 64; bit++) {
                bits[bit] += ((hash >>> bit) & 1L) == 1L ? 1 : -1;
            }
        }
        if (shingles == 0) {
            return hash64(text.toLowerCase(Locale.ROOT));
        }
        long result = 0L;
        for (int bit = 0; bit < 64; bit++) {
            if (bits[bit] > 0) {
                result |= 1L << bit;
            }
        }
        return result;
    }

    public static int hammingDistance(long a, long b) {
        return Long.bitCount(a ^ b);
    }

    /** FNV-1a, 64-bit. Deterministic across JVMs — String.hashCode is only 32 bits. */
    private static long hash64(String value) {
        long hash = 0xcbf29ce484222325L;
        for (int i = 0; i < value.length(); i++) {
            hash ^= value.charAt(i);
            hash *= 0x100000001b3L;
        }
        return hash;
    }
}