package dev.hendrikhoemberg.webtesthelper.model;

import java.util.EnumSet;
import java.util.Set;

/** Tier scope (spec 9). Only FULL is reachable in Phase 1; the others exist for Phase 2. */
public enum RunScope {

    PULSE, FULL, DEEP;

    /**
     * Which checks a scope is allowed to run. Drives the run's coverage (spec 6.4).
     *
     * <p>PULSE is spec 9's "page checks only, no submits": every {@code PageCheck} of spec 7.1 and
     * none of the three site checks, which reason over the whole crawled set and cannot be answered
     * from a dozen pinned pages. The page checks cost nothing extra to include — the crawl captures
     * their evidence into the {@code PageSnapshot} on every run whatever the tier, and this set only
     * decides whether it is read or discarded. {@code ScopeCheckSetTest} asserts the split against
     * {@code CheckRegistry}, so an eleventh check fails the build until a tier claims it.
     */
    public Set<CheckType> checkTypes() {
        return switch (this) {
            case PULSE -> EnumSet.of(CheckType.PAGE_STATUS, CheckType.PAGE_UNREACHABLE,
                    CheckType.DEAD_LINK, CheckType.IMAGE_BROKEN, CheckType.REDIRECT_CHAIN,
                    CheckType.MIXED_CONTENT, CheckType.CONSOLE_ERRORS,
                    CheckType.FILE_DOWNLOAD, CheckType.MEDIA_PLAYABLE, CheckType.IFRAME_EMBED);
            case FULL, DEEP -> EnumSet.allOf(CheckType.class);
        };
    }

    /** PULSE crawls only the site's pinned key pages, never the discovered frontier (spec 9). */
    public boolean crawlsWholeSite() {
        return this != PULSE;
    }

    /** The tier's default cron, evaluated in the site's timezone (spec 9). */
    public String defaultCron() {
        return switch (this) {
            case PULSE -> "0 0 3 * * *";
            case FULL -> "0 0 3 * * SUN";
            case DEEP -> "0 0 3 1 * *";
        };
    }
}
