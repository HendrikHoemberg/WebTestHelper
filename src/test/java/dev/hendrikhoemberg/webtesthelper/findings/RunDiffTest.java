package dev.hendrikhoemberg.webtesthelper.findings;

import dev.hendrikhoemberg.webtesthelper.model.CheckType;
import dev.hendrikhoemberg.webtesthelper.model.Evidence;
import dev.hendrikhoemberg.webtesthelper.model.ObservedStatus;
import dev.hendrikhoemberg.webtesthelper.model.Severity;
import dev.hendrikhoemberg.webtesthelper.model.TriageStatus;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class RunDiffTest {

    private static Finding finding(long id, TriageStatus triage) {
        Instant t = Instant.parse("2026-08-25T10:00:00Z");
        return new Finding(id, 42L, "fp-" + id, CheckType.DEAD_LINK, "https://example.com/" + id,
                "https://example.com/", Severity.ERROR, "finding.DEAD_LINK.dead", List.of(),
                Evidence.NONE, ObservedStatus.ACTIVE, triage, null, 10L, 10L, null, null, 1, 1, t, t);
    }

    @Test
    void untriagedOpenCountSkipsTriagedAndFixedFindings() {
        RunDiff diff = new RunDiff(10L, Map.of(
                ReportSection.NEW, List.of(finding(1, TriageStatus.UNTRIAGED), finding(2, TriageStatus.UNTRIAGED)),
                ReportSection.KNOWN, List.of(finding(3, TriageStatus.ACKNOWLEDGED)),
                ReportSection.FIXED, List.of(finding(4, TriageStatus.UNTRIAGED))));

        assertThat(diff.observedTotal()).isEqualTo(3);
        assertThat(diff.untriagedOpenCount()).isEqualTo(2);
    }
}
