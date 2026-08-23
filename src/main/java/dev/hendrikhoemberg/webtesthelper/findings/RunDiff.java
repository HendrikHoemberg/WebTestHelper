package dev.hendrikhoemberg.webtesthelper.findings;

import java.util.List;
import java.util.Map;

/**
 * The diff of one run, partitioned into the {@link ReportSection}s of spec 6.4. The sections are
 * derived once in SQL ({@link FindingStore#diffOf}) so the number shown in the report is the
 * number that lives in the table — there is no second, in-memory derivation that can disagree.
 */
public record RunDiff(long runId, Map<ReportSection, List<Finding>> bySection) {

    /** The findings in a section, or an empty list when the section is absent. */
    public List<Finding> of(ReportSection section) {
        return bySection.getOrDefault(section, List.of());
    }

    /** How many findings fall in a section. */
    public int count(ReportSection section) {
        return of(section).size();
    }

    /** Everything that is still open or moved: every section except {@code FIXED}. */
    public int observedTotal() {
        int total = 0;
        for (Map.Entry<ReportSection, List<Finding>> e : bySection.entrySet()) {
            if (e.getKey() != ReportSection.FIXED) {
                total += e.getValue().size();
            }
        }
        return total;
    }
}
