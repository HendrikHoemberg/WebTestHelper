package dev.hendrikhoemberg.webtesthelper.web;

import dev.hendrikhoemberg.webtesthelper.findings.FindingQuery;
import dev.hendrikhoemberg.webtesthelper.model.CheckType;
import dev.hendrikhoemberg.webtesthelper.model.ObservedStatus;
import dev.hendrikhoemberg.webtesthelper.model.Severity;
import dev.hendrikhoemberg.webtesthelper.model.TriageStatus;

import java.util.Set;

public record FindingFilterForm(
        Set<Severity> severities,
        Set<TriageStatus> triageStatuses,
        ObservedStatus observed,
        Set<CheckType> checkTypes,
        Integer page,
        Integer size
) {
    public FindingFilterForm {
        severities = severities == null ? Set.of() : Set.copyOf(severities);
        triageStatuses = triageStatuses == null ? Set.of() : Set.copyOf(triageStatuses);
        checkTypes = checkTypes == null ? Set.of() : Set.copyOf(checkTypes);
        page = (page == null || page < 1) ? 1 : page;
        size = (size == null || size < 1) ? 50 : size;
    }

    public FindingQuery toQuery(long siteId) {
        return new FindingQuery(siteId, severities, triageStatuses, observed, checkTypes, page, size);
    }
}
