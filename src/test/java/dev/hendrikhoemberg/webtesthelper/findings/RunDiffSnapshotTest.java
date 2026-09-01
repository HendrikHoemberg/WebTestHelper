package dev.hendrikhoemberg.webtesthelper.findings;

import dev.hendrikhoemberg.webtesthelper.catalog.SiteService;
import dev.hendrikhoemberg.webtesthelper.model.CheckFinding;
import dev.hendrikhoemberg.webtesthelper.model.CheckType;
import dev.hendrikhoemberg.webtesthelper.model.Evidence;
import dev.hendrikhoemberg.webtesthelper.model.NormalizedUrl;
import dev.hendrikhoemberg.webtesthelper.model.RunCoverage;
import dev.hendrikhoemberg.webtesthelper.model.RunScope;
import dev.hendrikhoemberg.webtesthelper.model.Severity;
import dev.hendrikhoemberg.webtesthelper.support.AbstractPostgresTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@Transactional
class RunDiffSnapshotTest extends AbstractPostgresTest {

    @Autowired
    FindingService service;
    @Autowired
    FindingStore store;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    SiteService sites;

    private long siteId;
    private Instant observedAt;

    @BeforeEach
    void setup() {
        siteId = sites.create(new dev.hendrikhoemberg.webtesthelper.catalog.SiteForm(
                "Kunde", "https://www.example.com/", 100, 3,
                Duration.ofMinutes(10), List.of(), List.of(), true, null, true));
        observedAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
    }

    @Test
    void reportSnapshotKeepsAnEarlierRunSectionAfterLaterRunsReobserveFindings() {
        List<CheckFinding> all = threeFindings();
        service.record(1, siteId, all, fullCoverage(List.of("/a", "/b", "/c")), observedAt);

        List<CheckFinding> two = all.subList(0, 2);
        service.record(2, siteId, two, fullCoverage(List.of("/a", "/b", "/c")), observedAt);

        assertThat(service.diffOf(siteId, 1).of(ReportSection.NEW))
                .extracting(Finding::locationKey)
                .containsExactly("/c");
        assertThat(service.diffForReport(siteId, 1).of(ReportSection.NEW))
                .extracting(Finding::locationKey)
                .containsExactlyInAnyOrder("/a", "/b", "/c");
    }

    @Test
    void reportSnapshotFallsBackToLiveDiffForLegacyRun() {
        service.record(1, siteId, threeFindings(), fullCoverage(List.of("/a", "/b", "/c")), observedAt);
        jdbc.update("DELETE FROM run_finding_section WHERE run_id = ?", 1L);
        jdbc.update("DELETE FROM run_report_snapshot WHERE run_id = ?", 1L);

        assertThat(service.diffForReport(siteId, 1).bySection())
                .isEqualTo(service.diffOf(siteId, 1).bySection());
    }

    @Test
    void emptyCompletedRunHasAPresentEmptySnapshot() {
        service.record(1, siteId, List.of(), fullCoverage(List.of()), observedAt);

        assertThat(store.snapshotOf(siteId, 1)).isPresent()
                .get()
                .satisfies(diff -> assertThat(diff.bySection().values())
                        .allSatisfy(findings -> assertThat(findings).isEmpty()));
    }

    @Test
    void reportSnapshotKeepsTheFixedSectionForTheRunThatResolvedFindings() {
        service.record(1, siteId, threeFindings(), fullCoverage(List.of("/a", "/b", "/c")), observedAt);

        service.record(2, siteId, List.of(), fullCoverage(List.of("/a", "/b", "/c")), observedAt);

        assertThat(service.diffForReport(siteId, 2).of(ReportSection.FIXED))
                .extracting(Finding::locationKey)
                .containsExactlyInAnyOrder("/a", "/b", "/c");
    }

    private List<CheckFinding> threeFindings() {
        List<CheckFinding> findings = new ArrayList<>();
        for (String path : List.of("/a", "/b", "/c")) {
            findings.add(new CheckFinding(CheckType.DEAD_LINK, Severity.ERROR, "dead:" + path,
                    page(path), "finding.DEAD_LINK.dead", List.of(), Evidence.NONE));
        }
        return findings;
    }

    private NormalizedUrl page(String path) {
        return new NormalizedUrl("https", "www.example.com", 443, path, null);
    }

    private RunCoverage fullCoverage(List<String> locationKeys) {
        List<String> urls = locationKeys.stream().map(k -> "https://www.example.com" + k).toList();
        return RunCoverage.of(RunScope.FULL,
                RunScope.FULL.checkTypes().stream().map(CheckType::name).toList(),
                urls, List.of(), false);
    }
}
