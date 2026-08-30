package dev.hendrikhoemberg.webtesthelper.model;

/**
 * What the {base}/{uuid} probe learned about the site's not-found page (spec 7.1), plus the
 * fingerprint of the site's root page as a known-real anchor.
 *
 * <p>The probe page is a candidate "this is the site's not-found page" measurement; the root
 * reference is the counter-measurement. A real root page proves how distinguishable the
 * not-found fingerprint is from a real page of the same site. When the site answers every
 * path (including "/") with the same shell, both fingerprints are identical and the check
 * must stay silent rather than flagging every shell-like page.
 */
public record SoftNotFoundProbe(int httpStatus, long simhash, int textLength,
                                int referenceStatus, long referenceSimhash, int referenceTextLength) {

    public static final SoftNotFoundProbe NONE = new SoftNotFoundProbe(0, 0L, 0);

    /** Legacy view: a probe without a reference (unit tests, pre-reference runs). */
    public SoftNotFoundProbe(int httpStatus, long simhash, int textLength) {
        this(httpStatus, simhash, textLength, 0, 0L, 0);
    }

    public boolean usable() {
        return httpStatus == 200 && textLength > 0;
    }

    public boolean referenceUsable() {
        return referenceStatus == 200 && referenceTextLength > 0;
    }
}