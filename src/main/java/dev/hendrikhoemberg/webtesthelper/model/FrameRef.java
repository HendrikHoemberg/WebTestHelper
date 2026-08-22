package dev.hendrikhoemberg.webtesthelper.model;

/** A frame or iframe as found in the page. */
public record FrameRef(NormalizedUrl src, String title, boolean loaded,
                       int contentTextLength, boolean sameOrigin) {}