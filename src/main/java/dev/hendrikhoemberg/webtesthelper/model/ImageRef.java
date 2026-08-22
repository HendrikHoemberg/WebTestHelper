package dev.hendrikhoemberg.webtesthelper.model;

/** An image as found in the page. */
public record ImageRef(String rawSource, NormalizedUrl target, String alt,
                       int naturalWidth, int naturalHeight, ImageOrigin origin) {

    /** Status 200 is not enough (spec 7.1) — a broken image still returns bytes sometimes. */
    public boolean rendered() {
        return naturalWidth > 0 && naturalHeight > 0;
    }
}