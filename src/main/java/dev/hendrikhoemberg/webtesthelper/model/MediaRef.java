package dev.hendrikhoemberg.webtesthelper.model;

import java.util.List;

/** An embedded audio or video element and what the browser managed to play. */
public record MediaRef(MediaKind kind, List<NormalizedUrl> sources,
                       int readyState, double duration, String errorCode) {

    public MediaRef {
        sources = List.copyOf(sources);
    }

    public boolean playable() {
        return readyState >= 1 && duration > 0 && errorCode == null;
    }
}