package dev.hendrikhoemberg.webtesthelper.model;

/**
 * A script or stylesheet the page loads into itself — {@code <script src>} and
 * {@code <link rel="stylesheet">}.
 *
 * <p>Images, media and frames have their own ref types because checks ask them their own
 * questions (did it render, does it play, is it blocked). These two are here for one reason:
 * they are the subresources a browser <em>hard-blocks</em> over http on an https page, so
 * {@code MIXED_CONTENT} cannot see the failure it exists for without them (spec 7.1).
 */
public record SubresourceRef(SubresourceKind kind, NormalizedUrl target) {
}
