package dev.hendrikhoemberg.webtesthelper.findings;

import dev.hendrikhoemberg.webtesthelper.model.CheckType;
import dev.hendrikhoemberg.webtesthelper.model.ObservedStatus;
import dev.hendrikhoemberg.webtesthelper.model.Severity;
import dev.hendrikhoemberg.webtesthelper.model.TriageStatus;

import java.util.Set;

/**
 * Filter and pagination criteria for searching findings on a site.
 * An empty set on any filter axis means "no filter on this axis".
 */
public record FindingQuery(
        long siteId,
        Set<Severity> severities,
        Set<TriageStatus> triageStatuses,
        ObservedStatus observed,
        Set<CheckType> checkTypes,
        int page,
        int size
) {
    /** Hard ceiling on a page, whatever the query string asks for. */
    public static final int MAX_SIZE = 200;

    /** Fallback when no page size is configured or supplied. */
    public static final int DEFAULT_SIZE = 50;

    public FindingQuery {
        severities = severities == null ? Set.of() : Set.copyOf(severities);
        triageStatuses = triageStatuses == null ? Set.of() : Set.copyOf(triageStatuses);
        checkTypes = checkTypes == null ? Set.of() : Set.copyOf(checkTypes);
        if (page < 1) {
            page = 1;
        }
        if (size < 1) {
            size = DEFAULT_SIZE;
        }
        // ?size=100000 is one request away, and every row it returns is a checkbox the bulk
        // endpoint would then refuse at its own cap of 200.
        if (size > MAX_SIZE) {
            size = MAX_SIZE;
        }
    }

    public static FindingQuery forSite(long siteId) {
        return new FindingQuery(siteId, Set.of(), Set.of(), null, Set.of(), 1, DEFAULT_SIZE);
    }
}
