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

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@Transactional
class FindingServiceDiffTest extends AbstractPostgresTest {

    @Autowired
    FindingService service;
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
                java.time.Duration.ofMinutes(10), List.of(), List.of(), true, null, true));
        observedAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
    }

    @Test
    void run1ShowsThreeNewAndNothingStillOpen() {
        RunDiff diff = service.record(1, siteId, threeFindings(), fullCoverage(List.of("/a", "/b", "/c")), observedAt);

        assertThat(diff.count(ReportSection.NEW)).isEqualTo(3);
        assertThat(diff.count(ReportSection.STILL_OPEN)).isZero();
    }

    @Test
    void run2WithTheSameThreeIsStableNotNewEveryTime() {
        RunDiff run1 = service.record(1, siteId, threeFindings(), fullCoverage(List.of("/a", "/b", "/c")), observedAt);
        List<String> firstPass = fingerprints(run1, ReportSection.NEW);

        RunDiff run2 = service.record(2, siteId, threeFindings(), fullCoverage(List.of("/a", "/b", "/c")), observedAt);
        List<String> secondPass = fingerprints(run2, ReportSection.STILL_OPEN);

        assertThat(run2.count(ReportSection.STILL_OPEN)).isEqualTo(3);
        assertThat(run2.count(ReportSection.NEW)).isZero();
        assertThat(run2.count(ReportSection.FIXED)).isZero();
        assertThat(secondPass).containsExactlyInAnyOrderElementsOf(firstPass);
    }

    @Test
    void run3DroppingOneWithFullCoverageFixesIt() {
        service.record(1, siteId, threeFindings(), fullCoverage(List.of("/a", "/b", "/c")), observedAt);
        service.record(2, siteId, threeFindings(), fullCoverage(List.of("/a", "/b", "/c")), observedAt);

        List<CheckFinding> dropped = threeFindings().stream()
                .filter(f -> !"/c".equals(f.locationKey())).toList();
        RunDiff run3 = service.record(3, siteId, dropped, fullCoverage(List.of("/a", "/b", "/c")), observedAt);

        assertThat(run3.count(ReportSection.FIXED)).isEqualTo(1);
        Finding fixed = run3.of(ReportSection.FIXED).get(0);
        assertThat(fixed.locationKey()).isEqualTo("/c");
        assertThat(fixed.resolvedAtRun()).isEqualTo(3);
        assertThat(run3.count(ReportSection.STILL_OPEN)).isEqualTo(2);
    }

    @Test
    void run4SeeingTheDroppedOneAgainIsRegressedNotNew() {
        service.record(1, siteId, threeFindings(), fullCoverage(List.of("/a", "/b", "/c")), observedAt);
        service.record(2, siteId, threeFindings(), fullCoverage(List.of("/a", "/b", "/c")), observedAt);
        List<CheckFinding> dropped = threeFindings().stream()
                .filter(f -> !"/c".equals(f.locationKey())).toList();
        service.record(3, siteId, dropped, fullCoverage(List.of("/a", "/b", "/c")), observedAt);

        RunDiff run4 = service.record(4, siteId, threeFindings(), fullCoverage(List.of("/a", "/b", "/c")), observedAt);

        assertThat(run4.count(ReportSection.REGRESSED)).isEqualTo(1);
        assertThat(run4.count(ReportSection.NEW)).isZero();
        Finding regressed = run4.of(ReportSection.REGRESSED).get(0);
        assertThat(regressed.locationKey()).isEqualTo("/c");
        assertThat(regressed.firstSeenRun()).isEqualTo(1);
    }

    @Test
    void anAcknowledgedFindingShowsAsKnownAndAReturningOneAsRegressed() {
        service.record(1, siteId, threeFindings(), fullCoverage(List.of("/a", "/b", "/c")), observedAt);
        String acked = threeFindings().get(0).locationKey();
        String fp = Fingerprint.of(siteId, CheckType.DEAD_LINK, "dead:" + acked, acked);
        jdbc.update("UPDATE finding SET triage_status = 'ACKNOWLEDGED', triaged_at = ? WHERE fingerprint = ?",
                java.sql.Timestamp.from(observedAt), fp);

        RunDiff run2 = service.record(2, siteId, threeFindings(), fullCoverage(List.of("/a", "/b", "/c")), observedAt);
        assertThat(run2.count(ReportSection.KNOWN)).isEqualTo(1);
        assertThat(locationOf(run2, ReportSection.KNOWN)).containsExactly(acked);
        assertThat(run2.count(ReportSection.STILL_OPEN)).isEqualTo(2);

        List<CheckFinding> dropped = threeFindings().stream()
                .filter(f -> !acked.equals(f.locationKey())).toList();
        service.record(3, siteId, dropped, fullCoverage(List.of("/a", "/b", "/c")), observedAt);

        RunDiff run4 = service.record(4, siteId, threeFindings(), fullCoverage(List.of("/a", "/b", "/c")), observedAt);
        assertThat(run4.count(ReportSection.REGRESSED)).isEqualTo(1);
        assertThat(run4.count(ReportSection.KNOWN)).isZero();
        assertThat(locationOf(run4, ReportSection.REGRESSED)).containsExactly(acked);
    }

    @Test
    void aPulseRunDoesNotResolveWhatAFullCrawlFound() {
        NormalizedUrl pulsePage = page("/pulse");
        NormalizedUrl otherPage = page("/other");
        CheckFinding fileDownload = new CheckFinding(CheckType.FILE_DOWNLOAD, Severity.ERROR, "file:other",
                otherPage, "m", List.of(), Evidence.NONE);
        service.record(1, siteId, List.of(fileDownload), fullCoverage(List.of("/other")), observedAt);

        RunCoverage pulse = RunCoverage.of(
                RunScope.PULSE,
                RunScope.PULSE.checkTypes().stream().map(CheckType::name).toList(),
                List.of("https://www.example.com/pulse"), List.of(), false);
        CheckFinding pulseFinding = new CheckFinding(CheckType.PAGE_STATUS, Severity.WARN, "status:pulse",
                pulsePage, "m", List.of(), Evidence.NONE);
        RunDiff pulseDiff = service.record(2, siteId, List.of(pulseFinding), pulse, observedAt);

        String fp = Fingerprint.of(siteId, CheckType.FILE_DOWNLOAD, "file:other", "/other");
        assertThat(observedStatus(fp)).isEqualTo(dev.hendrikhoemberg.webtesthelper.model.ObservedStatus.ACTIVE);
        assertThat(lastSeenRun(fp)).isEqualTo(1);
        assertThat(noSectionContains(pulseDiff, fp)).isTrue();
    }

    @Test
    void aPulseRunDoesNotResolveASiteWideFinding() {
        // A pulse frontier is seeded from the pinned set and never discovers, so it always drains
        // and reports partialCoverage=false. Reading that as "complete coverage" would let eleven
        // key pages disprove a finding the full crawl saw on six hundred (spec 6.2's '*', spec
        // 6.4): the next full crawl re-promotes it and reports it REGRESSED, every week forever.
        List<CheckFinding> onSixPages = perPage(CheckType.DEAD_LINK, "dead:partner", 6);
        RunDiff full = service.record(1, siteId, onSixPages, fullCoverage(pageKeys(6)), observedAt);
        assertThat(locationOf(full, ReportSection.NEW)).containsExactly("*");

        RunDiff pulse = service.record(2, siteId, List.of(), pulseCoverage(List.of("/", "/kontakt")), observedAt);

        String fp = Fingerprint.of(siteId, CheckType.DEAD_LINK, "dead:partner", "*");
        assertThat(observedStatus(fp)).isEqualTo(dev.hendrikhoemberg.webtesthelper.model.ObservedStatus.ACTIVE);
        assertThat(pulse.count(ReportSection.FIXED)).isZero();
    }

    @Test
    void aCompletedFullCrawlStillResolvesASiteWideFinding() {
        // The other half of the rule: silence from a run that did crawl the whole site is
        // evidence, and a site-wide finding it no longer sees is fixed.
        service.record(1, siteId, perPage(CheckType.DEAD_LINK, "dead:partner", 6),
                fullCoverage(pageKeys(6)), observedAt);

        RunDiff run2 = service.record(2, siteId, List.of(), fullCoverage(pageKeys(6)), observedAt);

        assertThat(locationOf(run2, ReportSection.FIXED)).containsExactly("*");
    }

    @Test
    void promotionBoundaryCrossingYieldsThreeNewAndOneFixed() {
        List<CheckFinding> six = perPage(CheckType.DEAD_LINK, "dead:promo", 6);
        service.record(1, siteId, six, fullCoverage(pageKeys(6)), observedAt);

        List<CheckFinding> three = perPage(CheckType.DEAD_LINK, "dead:promo", 3);
        RunDiff run2 = service.record(2, siteId, three, fullCoverage(pageKeys(3)), observedAt);

        assertThat(run2.count(ReportSection.NEW)).isEqualTo(3);
        assertThat(run2.count(ReportSection.FIXED)).isEqualTo(1);
    }

    @Test
    void anEmptyRecordWithFullCoverageResolvesEverythingPreviouslyActive() {
        service.record(1, siteId, threeFindings(), fullCoverage(List.of("/a", "/b", "/c")), observedAt);

        RunDiff empty = service.record(2, siteId, List.of(), fullCoverage(List.of("/a", "/b", "/c")), observedAt);

        assertThat(empty.count(ReportSection.NEW)).isZero();
        assertThat(empty.count(ReportSection.STILL_OPEN)).isZero();
        assertThat(empty.count(ReportSection.FIXED)).isEqualTo(3);
        assertThat(locationOf(empty, ReportSection.FIXED)).containsExactlyInAnyOrder("/a", "/b", "/c");
    }

    @Test
    void aRegressionIsReportedOnceAndSettlesBackToStillOpen() {
        // A regression is news in the run that brings it back — and only in that run. Leaving it
        // REGRESSED forever is spec 6.4's "every week, forever" failure arriving through the
        // section rule, and it would mail on every run under spec 11.1.
        service.record(1, siteId, threeFindings(), fullCoverage(List.of("/a", "/b", "/c")), observedAt);
        List<CheckFinding> dropped = threeFindings().stream()
                .filter(f -> !"/c".equals(f.locationKey())).toList();
        service.record(2, siteId, dropped, fullCoverage(List.of("/a", "/b", "/c")), observedAt);
        RunDiff run3 = service.record(3, siteId, threeFindings(), fullCoverage(List.of("/a", "/b", "/c")), observedAt);
        assertThat(run3.count(ReportSection.REGRESSED)).isEqualTo(1);

        RunDiff run4 = service.record(4, siteId, threeFindings(), fullCoverage(List.of("/a", "/b", "/c")), observedAt);

        assertThat(run4.count(ReportSection.REGRESSED)).isZero();
        assertThat(run4.count(ReportSection.STILL_OPEN)).isEqualTo(3);
    }

    @Test
    void anAcknowledgedFindingReturnsToKnownTheRunAfterItsRegression() {
        // Triage is human-owned: once the regression has been reported, an acknowledged finding
        // must go back to being quiet, or acknowledging it can never take effect again.
        service.record(1, siteId, threeFindings(), fullCoverage(List.of("/a", "/b", "/c")), observedAt);
        String acked = "/a";
        String fp = Fingerprint.of(siteId, CheckType.DEAD_LINK, "dead:" + acked, acked);
        jdbc.update("UPDATE finding SET triage_status = 'ACKNOWLEDGED', triaged_at = ? WHERE fingerprint = ?",
                java.sql.Timestamp.from(observedAt), fp);
        List<CheckFinding> dropped = threeFindings().stream()
                .filter(f -> !acked.equals(f.locationKey())).toList();
        service.record(2, siteId, dropped, fullCoverage(List.of("/a", "/b", "/c")), observedAt);
        service.record(3, siteId, threeFindings(), fullCoverage(List.of("/a", "/b", "/c")), observedAt);

        RunDiff run4 = service.record(4, siteId, threeFindings(), fullCoverage(List.of("/a", "/b", "/c")), observedAt);

        assertThat(run4.count(ReportSection.REGRESSED)).isZero();
        assertThat(locationOf(run4, ReportSection.KNOWN)).containsExactly(acked);
    }

    @Test
    void resolvedAtRunSurvivesTheRegressionAsHistory() {
        // The regression flag moves to its own column; resolved_at_run stays as the record of when
        // the finding was last believed fixed.
        service.record(1, siteId, threeFindings(), fullCoverage(List.of("/a", "/b", "/c")), observedAt);
        List<CheckFinding> dropped = threeFindings().stream()
                .filter(f -> !"/c".equals(f.locationKey())).toList();
        service.record(2, siteId, dropped, fullCoverage(List.of("/a", "/b", "/c")), observedAt);

        RunDiff run3 = service.record(3, siteId, threeFindings(), fullCoverage(List.of("/a", "/b", "/c")), observedAt);

        Finding regressed = run3.of(ReportSection.REGRESSED).get(0);
        assertThat(regressed.resolvedAtRun()).isEqualTo(2);
        assertThat(regressed.regressedAtRun()).isEqualTo(3);
    }

    private List<CheckFinding> threeFindings() {
        return List.of(
                new CheckFinding(CheckType.DEAD_LINK, Severity.ERROR, "dead:/a", page("/a"), "m", List.of(), Evidence.NONE),
                new CheckFinding(CheckType.DEAD_LINK, Severity.ERROR, "dead:/b", page("/b"), "m", List.of(), Evidence.NONE),
                new CheckFinding(CheckType.DEAD_LINK, Severity.ERROR, "dead:/c", page("/c"), "m", List.of(), Evidence.NONE));
    }

    private List<CheckFinding> perPage(CheckType type, String subject, int n) {
        List<CheckFinding> out = new java.util.ArrayList<>();
        for (int i = 0; i < n; i++) {
            out.add(new CheckFinding(type, Severity.ERROR, subject, page("/x" + i), "m", List.of(), Evidence.NONE));
        }
        return out;
    }

    private NormalizedUrl page(String path) {
        return new NormalizedUrl("https", "www.example.com", 443, path, null);
    }

    private List<String> pageKeys(int n) {
        List<String> keys = new java.util.ArrayList<>();
        for (int i = 0; i < n; i++) {
            keys.add("/x" + i);
        }
        return keys;
    }

    private RunCoverage pulseCoverage(List<String> locationKeys) {
        List<String> urls = locationKeys.stream().map(k -> "https://www.example.com" + k).toList();
        return RunCoverage.of(RunScope.PULSE,
                RunScope.PULSE.checkTypes().stream().map(CheckType::name).toList(), urls, List.of(), false);
    }

    private RunCoverage fullCoverage(List<String> locationKeys) {
        List<String> urls = locationKeys.stream().map(k -> "https://www.example.com" + k).toList();
        return RunCoverage.of(RunScope.FULL, RunScope.FULL.checkTypes().stream().map(CheckType::name).toList(), urls, List.of(), false);
    }

    private List<String> fingerprints(RunDiff diff, ReportSection section) {
        return diff.of(section).stream().map(Finding::fingerprint).toList();
    }

    private List<String> locationOf(RunDiff diff, ReportSection section) {
        return diff.of(section).stream().map(Finding::locationKey).toList();
    }

    private boolean noSectionContains(RunDiff diff, String fp) {
        for (ReportSection section : ReportSection.values()) {
            if (diff.of(section).stream().anyMatch(f -> f.fingerprint().equals(fp))) {
                return false;
            }
        }
        return true;
    }

    private dev.hendrikhoemberg.webtesthelper.model.ObservedStatus observedStatus(String fp) {
        return dev.hendrikhoemberg.webtesthelper.model.ObservedStatus.valueOf(jdbc.queryForObject(
                "SELECT observed_status FROM finding WHERE fingerprint = ?", String.class, fp));
    }

    private long lastSeenRun(String fp) {
        return jdbc.queryForObject("SELECT last_seen_run FROM finding WHERE fingerprint = ?", Long.class, fp);
    }
}
