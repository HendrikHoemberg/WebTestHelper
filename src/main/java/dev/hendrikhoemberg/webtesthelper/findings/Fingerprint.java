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
 * <p>The four fields are joined by a single control-byte separator, U+0001, which cannot
 * occur in any valid input: site ids are decimal, {@link CheckType} names are ASCII
 * identifiers, and subject/location keys are derived from URLs (which contain no control
 * bytes). Because the separator is absent from every field, the join is injective over the
 * real domain — splitting on U+0001 recovers the four components exactly, so a finding's
 * identity can never be spliced from a different {@code (siteId, checkType, subjectKey,
 * locationKey)}. The test pins this with synthetic inputs that smuggle a control byte into a
 * value; those collide only if the separator can appear inside a field, which it cannot here.
 */
public final class Fingerprint {

    private static final String SEP = "\u0001";

    private Fingerprint() {
    }

    public static String of(long siteId, CheckType type, String subjectKey, String locationKey) {
        String joined = siteId + SEP + type.name() + SEP + subjectKey + SEP + locationKey;
        byte[] digest = sha256(joined.getBytes(StandardCharsets.UTF_8));
        return HexFormat.of().formatHex(digest);
    }

    private static byte[] sha256(byte[] input) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(input);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required but unavailable", e);
        }
    }
}
