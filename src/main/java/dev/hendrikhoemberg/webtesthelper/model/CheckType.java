package dev.hendrikhoemberg.webtesthelper.model;

import java.util.EnumSet;
import java.util.Set;

/** Every check the system can run. Persisted by name; never rendered to a user (spec 13.1). */
public enum CheckType {
    PAGE_STATUS,
    PAGE_UNREACHABLE,
    DEAD_LINK,
    REDIRECT_CHAIN,
    IMAGE_BROKEN,
    FILE_DOWNLOAD,
    MEDIA_PLAYABLE,
    IFRAME_EMBED,
    MIXED_CONTENT,
    CONSOLE_ERRORS,
    TLS_CERT,
    HREFLANG,
    SITEMAP_CONSISTENCY,
    COOKIE_BANNER,
    LANGUAGE_SWITCHER,
    BUTTON_REACHABILITY,
    CONTACT_FORM;

    private static final Set<CheckType> INTERACTION =
            EnumSet.of(COOKIE_BANNER, LANGUAGE_SWITCHER, BUTTON_REACHABILITY, CONTACT_FORM);

    /**
     * Whether this type is driven in a live browser after the crawl (spec 7.2, D72).
     *
     * <p>{@code CheckRegistry} is the truth and this is a copy of it, kept here because the two
     * modules that need the answer — {@code findings} and {@code crawler} — may not see {@code
     * checks} (spec 5.1). {@code CheckRegistryTest} fails the build when the two disagree.
     *
     * <p>The property is load-bearing twice: an interaction type resolves only within the pages it
     * was actually driven on (D74), and its findings are never re-probed over HTTP, because a
     * browser interaction cannot be replayed by fetching a URL (D78).
     */
    public boolean interaction() {
        return INTERACTION.contains(this);
    }
}
