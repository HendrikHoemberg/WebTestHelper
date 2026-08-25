package dev.hendrikhoemberg.webtesthelper.findings;

import java.util.List;

/**
 * A paginated result slice of findings.
 *
 * @param findings the findings in the current page
 * @param page the 1-based current page index
 * @param size the maximum page size
 * @param total the total number of findings matching the query before pagination
 */
public record FindingPage(List<Finding> findings, int page, int size, long total) {
    public FindingPage {
        findings = findings == null ? List.of() : List.copyOf(findings);
    }

    public int pageCount() {
        if (total <= 0 || size <= 0) {
            return 0;
        }
        return (int) Math.ceil((double) total / size);
    }
}
