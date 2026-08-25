package dev.hendrikhoemberg.webtesthelper.findings;

import dev.hendrikhoemberg.webtesthelper.catalog.SiteForm;
import dev.hendrikhoemberg.webtesthelper.catalog.SiteService;
import dev.hendrikhoemberg.webtesthelper.model.CheckFinding;
import dev.hendrikhoemberg.webtesthelper.model.CheckType;
import dev.hendrikhoemberg.webtesthelper.model.Evidence;
import dev.hendrikhoemberg.webtesthelper.model.NormalizedUrl;
import dev.hendrikhoemberg.webtesthelper.model.RunCoverage;
import dev.hendrikhoemberg.webtesthelper.model.RunScope;
import dev.hendrikhoemberg.webtesthelper.model.Severity;
import dev.hendrikhoemberg.webtesthelper.model.TriageStatus;
import dev.hendrikhoemberg.webtesthelper.support.AbstractPostgresTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@Transactional
class FindingTriageSectionTest extends AbstractPostgresTest {

    private static final int MAX_MUTE_DAYS = 365;

    @Autowired
    FindingService service;
    @Autowired
    SiteService sites;

    private long siteId;
    private Instant now;

    @BeforeEach
    void setup() {
        siteId = sites.create(new SiteForm(
                "Kunde", "https://www.example.com/", 100, 3,
                Duration.ofMinutes(10), List.of(), List.of(), true, null, true));
        now = Instant.now().truncatedTo(ChronoUnit.MICROS);
    }

    @Test
    void mutedThenStillObservedYieldsKnownNotStillOpen() {
        RunDiff run1 = service.record(1, siteId, List.of(singleFinding("/a")), fullCoverage(List.of("/a")), now);
        long id = run1.of(ReportSection.NEW).get(0).id();

        triage(id, TriageStatus.MUTED);

        RunDiff run2 = service.record(2, siteId, List.of(singleFinding("/a")), fullCoverage(List.of("/a")), now);

        assertThat(run2.count(ReportSection.KNOWN)).isEqualTo(1);
        assertThat(run2.count(ReportSection.STILL_OPEN)).isZero();
        assertThat(run2.count(ReportSection.NEW)).isZero();
        assertThat(run2.count(ReportSection.REGRESSED)).isZero();
        assertThat(run2.count(ReportSection.FIXED)).isZero();
        assertThat(locationOf(run2, ReportSection.KNOWN)).containsExactly("/a");
    }

    @Test
    void mutedResolvedByRun2ObservedAgainByRun3YieldsKnownNotRegressed() {
        RunDiff run1 = service.record(1, siteId, List.of(singleFinding("/a")), fullCoverage(List.of("/a")), now);
        long id = run1.of(ReportSection.NEW).get(0).id();

        triage(id, TriageStatus.MUTED);

        RunDiff run2 = service.record(2, siteId, List.of(), fullCoverage(List.of("/a")), now);
        assertThat(run2.count(ReportSection.FIXED)).isEqualTo(1);

        RunDiff run3 = service.record(3, siteId, List.of(singleFinding("/a")), fullCoverage(List.of("/a")), now);

        assertThat(run3.count(ReportSection.KNOWN)).isEqualTo(1);
        assertThat(run3.count(ReportSection.REGRESSED)).isZero();
        assertThat(locationOf(run3, ReportSection.KNOWN)).containsExactly("/a");
    }

    @Test
    void acknowledgedResolvedByRun2ObservedAgainByRun3YieldsRegressedNotKnown() {
        RunDiff run1 = service.record(1, siteId, List.of(singleFinding("/a")), fullCoverage(List.of("/a")), now);
        long id = run1.of(ReportSection.NEW).get(0).id();

        triage(id, TriageStatus.ACKNOWLEDGED);

        RunDiff run2 = service.record(2, siteId, List.of(), fullCoverage(List.of("/a")), now);
        assertThat(run2.count(ReportSection.FIXED)).isEqualTo(1);

        RunDiff run3 = service.record(3, siteId, List.of(singleFinding("/a")), fullCoverage(List.of("/a")), now);

        assertThat(run3.count(ReportSection.REGRESSED)).isEqualTo(1);
        assertThat(run3.count(ReportSection.KNOWN)).isZero();
        assertThat(locationOf(run3, ReportSection.REGRESSED)).containsExactly("/a");
    }

    @Test
    void wontFixRegressedYieldsKnownNotRegressed() {
        RunDiff run1 = service.record(1, siteId, List.of(singleFinding("/a")), fullCoverage(List.of("/a")), now);
        long id = run1.of(ReportSection.NEW).get(0).id();

        triage(id, TriageStatus.WONT_FIX);

        RunDiff run2 = service.record(2, siteId, List.of(), fullCoverage(List.of("/a")), now);
        assertThat(run2.count(ReportSection.FIXED)).isEqualTo(1);

        RunDiff run3 = service.record(3, siteId, List.of(singleFinding("/a")), fullCoverage(List.of("/a")), now);

        assertThat(run3.count(ReportSection.KNOWN)).isEqualTo(1);
        assertThat(run3.count(ReportSection.REGRESSED)).isZero();
        assertThat(locationOf(run3, ReportSection.KNOWN)).containsExactly("/a");
    }

    @Test
    void findingFirstSeenInRun2MutedInSameRunYieldsKnownNotNew() {
        service.record(1, siteId, List.of(), fullCoverage(List.of("/a", "/b")), now);

        RunDiff run2 = service.record(2, siteId, List.of(singleFinding("/b")), fullCoverage(List.of("/a", "/b")), now);
        long id = run2.of(ReportSection.NEW).get(0).id();

        triage(id, TriageStatus.MUTED);

        RunDiff diff = service.diffOf(siteId, 2);

        assertThat(diff.count(ReportSection.KNOWN)).isEqualTo(1);
        assertThat(diff.count(ReportSection.NEW)).isZero();
        assertThat(locationOf(diff, ReportSection.KNOWN)).containsExactly("/b");
    }

    @Test
    void mutedThenAbsentFromFullyCoveringRunYieldsFixed() {
        RunDiff run1 = service.record(1, siteId, List.of(singleFinding("/a")), fullCoverage(List.of("/a")), now);
        long id = run1.of(ReportSection.NEW).get(0).id();

        triage(id, TriageStatus.MUTED);

        RunDiff run2 = service.record(2, siteId, List.of(), fullCoverage(List.of("/a")), now);

        assertThat(run2.count(ReportSection.FIXED)).isEqualTo(1);
        assertThat(run2.count(ReportSection.KNOWN)).isZero();
        assertThat(run2.count(ReportSection.STILL_OPEN)).isZero();
        assertThat(locationOf(run2, ReportSection.FIXED)).containsExactly("/a");
    }

    private void triage(long findingId, TriageStatus status) {
        Instant expiry = status == TriageStatus.MUTED ? now.plus(30, ChronoUnit.DAYS) : null;
        TriageAction action = TriageAction.of(status, "Triage-Test", expiry, now, MAX_MUTE_DAYS);
        service.triage(siteId, List.of(findingId), action, "tester", now);
    }

    private CheckFinding singleFinding(String path) {
        return new CheckFinding(CheckType.DEAD_LINK, Severity.ERROR, "dead:" + path,
                new NormalizedUrl("https", "www.example.com", 443, path, null),
                "finding.DEAD_LINK.dead", List.of(), Evidence.NONE);
    }

    private RunCoverage fullCoverage(List<String> paths) {
        List<String> urls = paths.stream().map(p -> "https://www.example.com" + p).toList();
        return RunCoverage.of(
                RunScope.FULL.checkTypes().stream().map(CheckType::name).toList(),
                urls, List.of(), false);
    }

    private List<String> locationOf(RunDiff diff, ReportSection section) {
        return diff.of(section).stream().map(Finding::locationKey).toList();
    }
}
