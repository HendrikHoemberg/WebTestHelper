package dev.hendrikhoemberg.webtesthelper.reporting;

import java.time.Instant;
import java.util.List;

/**
 * Detailed view representation of a finding including evidence, occurrences, and technical details (Spec 13.2).
 */
public record FindingDetailView(
        FindingView summary,
        String description,
        String technicalDetail,
        String rawTechnicalDetail,
        Integer httpStatus,
        String requestDetail,
        String responseDetail,
        List<String> consoleExcerpt,
        String screenshotUrl,
        List<String> pages,
        int pageTotal,
        Instant firstSeenAt,
        Instant lastSeenAt,
        String triageReason
) {
    public FindingDetailView {
        consoleExcerpt = consoleExcerpt == null ? List.of() : List.copyOf(consoleExcerpt);
        pages = pages == null ? List.of() : List.copyOf(pages);
    }
}
