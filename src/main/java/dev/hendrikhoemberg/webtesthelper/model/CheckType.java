package dev.hendrikhoemberg.webtesthelper.model;

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
    COOKIE_BANNER
}
