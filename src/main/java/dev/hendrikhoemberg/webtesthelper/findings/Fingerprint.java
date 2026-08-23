package dev.hendrikhoemberg.webtesthelper.findings;

import dev.hendrikhoemberg.webtesthelper.model.CheckType;

import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Deterministic identity of a finding.
 *
 * <p>The fingerprint is the join key across runs: a finding seen on page A in run 1 and page B
 * in run 2 must carry the same id, or the diff (plan 4, task 4) is worthless. It is therefore a
 * pure function of {@code (siteId, checkType, subjectKey, locationKey)} — nothing time-dependent
 * or random.
 *
 * <p>The four fields are joined with a NUL separator. A URL cannot contain a NUL, which is
 * precisely why NUL is the separator. To keep the boundary unambiguous when a value itself
 * carries a NUL (it never will for a real URL-derived key, but the format must not be
 * spliceable), embedded NULs are escaped to a double NUL: {@code "a\0b" + "\0" + "c"} then
 * joins to {@code …a\0\0b\0c}, distinct from {@code "a" + "\0" + "b\0c"} → {@code …a\0b\0\0c}.
 * Without that escape the two byte strings are identical and the fingerprint collides.
 */
public final class Fingerprint {

    private static final String SEP = "\0";

    private Fingerprint() {
    }

    public static String of(long siteId, CheckType type, String subjectKey, String locationKey) {
        String joined = siteId + SEP + type.name() + SEP
                + escape(subjectKey) + SEP + escape(locationKey);
        byte[] digest = sha256(joined.getBytes(StandardCharsets.UTF_8));
        return HexFormat.of().formatHex(digest);
    }

    private static String escape(String value) {
        return value.replace(SEP, SEP + SEP);
    }

    private static byte[] sha256(byte[] input) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(input);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required but unavailable", e);
        }
    }
}
