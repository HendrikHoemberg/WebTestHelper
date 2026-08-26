package dev.hendrikhoemberg.webtesthelper.model;

import java.util.Locale;

/** A frame or iframe as found in the page. */
public record FrameRef(NormalizedUrl src, String title, boolean loaded,
                       int contentTextLength, boolean sameOrigin) {

    /** A Google Maps embed: a {@code /maps/embed} path, or a Google host whose path carries {@code /maps}. */
    public boolean isMapsEmbed() {
        String path = src.path().toLowerCase(Locale.ROOT);
        return path.contains("/maps/embed")
                || (src.registrableHost().contains("google") && path.contains("/maps"));
    }
}