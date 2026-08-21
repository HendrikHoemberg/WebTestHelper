package dev.hendrikhoemberg.webtesthelper.model;

import java.util.EnumSet;
import java.util.Set;

/** Tier scope (spec 9). Only FULL is reachable in Phase 1; the others exist for Phase 2. */
public enum RunScope {

    PULSE, FULL, DEEP;

    /** Which checks a scope is allowed to run. Drives the run's coverage (spec 6.4). */
    public Set<CheckType> checkTypes() {
        return switch (this) {
            case PULSE -> EnumSet.of(CheckType.PAGE_STATUS, CheckType.PAGE_UNREACHABLE,
                    CheckType.DEAD_LINK, CheckType.IMAGE_BROKEN, CheckType.REDIRECT_CHAIN,
                    CheckType.MIXED_CONTENT, CheckType.CONSOLE_ERRORS);
            case FULL, DEEP -> EnumSet.allOf(CheckType.class);
        };
    }

    /** PULSE crawls only the site's pinned key pages, never the discovered frontier (spec 9). */
    public boolean crawlsWholeSite() {
        return this != PULSE;
    }
}
