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
    public FindingQuery {
        severities = severities == null ? Set.of() : Set.copyOf(severities);
        triageStatuses = triageStatuses == null ? Set.of() : Set.copyOf(triageStatuses);
        checkTypes = checkTypes == null ? Set.of() : Set.copyOf(checkTypes);
        if (page < 1) {
            page = 1;
        }
        if (size < 1) {
            size = 50;
        }
    }

    public static FindingQuery forSite(long siteId) {
        return new FindingQuery(siteId, Set.of(), Set.of(), null, Set.of(), 1, 50);
    }
}
