package dev.hendrikhoemberg.webtesthelper.model;

import java.util.Locale;

/** A link as found in the page's HTML. */
public record LinkRef(String rawHref, NormalizedUrl target, String anchorText,
                      boolean internal, String rel) {

    public boolean nofollow() {
        return rel != null && rel.toLowerCase(Locale.ROOT).contains("nofollow");
    }
}