package dev.hendrikhoemberg.webtesthelper.reporting;

import java.util.List;

/**
 * A section in the digest email presenting a subset of findings and the total count.
 */
public record DigestSection(
        List<FindingView> shown,
        int total
) {
    public DigestSection {
        shown = shown == null ? List.of() : List.copyOf(shown);
    }

    public int omitted() {
        return Math.max(0, total - shown.size());
    }
}
