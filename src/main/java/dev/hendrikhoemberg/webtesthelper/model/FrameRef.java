package dev.hendrikhoemberg.webtesthelper.model;

import java.util.Locale;

/** A frame or iframe as found in the page. */
public record FrameRef(NormalizedUrl src, String title, boolean loaded,
                       int contentTextLength, boolean sameOrigin,
                       MapPaintState mapPaintState) {

    /** A frame captured without a canvas signal is {@link MapPaintState#UNKNOWN}. */
    public FrameRef(NormalizedUrl src, String title, boolean loaded,
                    int contentTextLength, boolean sameOrigin) {
        this(src, title, loaded, contentTextLength, sameOrigin, MapPaintState.UNKNOWN);
    }

    /** Same frame with a different paint state — the cross-origin enrichment's result. */
    public FrameRef withMapPaintState(MapPaintState mapPaintState) {
        return new FrameRef(src, title, loaded, contentTextLength, sameOrigin, mapPaintState);
    }

    /** A Google Maps embed: a {@code /maps/embed} path, or a Google host whose path carries {@code /maps}. */
    public boolean isMapsEmbed() {
        String path = src.path().toLowerCase(Locale.ROOT);
        return path.contains("/maps/embed")
                || (src.registrableHost().contains("google") && path.contains("/maps"));
    }
}