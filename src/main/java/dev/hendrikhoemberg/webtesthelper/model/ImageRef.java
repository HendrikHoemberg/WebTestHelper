package dev.hendrikhoemberg.webtesthelper.model;

/** An image as found in the page. */
public record ImageRef(String rawSource, NormalizedUrl target, String alt,
                       int naturalWidth, int naturalHeight, ImageOrigin origin,
                       ImageState state) {

    /** Backwards-compatible constructor: state defaults to dimension-based inference. */
    public ImageRef(String rawSource, NormalizedUrl target, String alt,
                    int naturalWidth, int naturalHeight, ImageOrigin origin) {
        this(rawSource, target, alt, naturalWidth, naturalHeight, origin,
                (naturalWidth > 0 && naturalHeight > 0) ? ImageState.DECODED : ImageState.BROKEN);
    }

    /** Status 200 is not enough (spec 7.1) — a broken image still returns bytes sometimes. */
    public boolean rendered() {
        return naturalWidth > 0 && naturalHeight > 0;
    }
}
