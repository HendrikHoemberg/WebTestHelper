package dev.hendrikhoemberg.webtesthelper.reporting;

import dev.hendrikhoemberg.webtesthelper.catalog.SiteForm;
import dev.hendrikhoemberg.webtesthelper.catalog.SiteService;
import dev.hendrikhoemberg.webtesthelper.findings.FindingService;
import dev.hendrikhoemberg.webtesthelper.findings.ReportSection;
import dev.hendrikhoemberg.webtesthelper.findings.RunDiff;
import dev.hendrikhoemberg.webtesthelper.findings.TriageAction;
import dev.hendrikhoemberg.webtesthelper.model.CheckFinding;
import dev.hendrikhoemberg.webtesthelper.model.CheckType;
import dev.hendrikhoemberg.webtesthelper.model.Evidence;
import dev.hendrikhoemberg.webtesthelper.model.NormalizedUrl;
import dev.hendrikhoemberg.webtesthelper.model.RunCoverage;
import dev.hendrikhoemberg.webtesthelper.model.RunScope;
import dev.hendrikhoemberg.webtesthelper.model.RunStatus;
import dev.hendrikhoemberg.webtesthelper.model.RunTrigger;
import dev.hendrikhoemberg.webtesthelper.model.Severity;
import dev.hendrikhoemberg.webtesthelper.model.TriageStatus;
import dev.hendrikhoemberg.webtesthelper.runner.RunSummary;
import dev.hendrikhoemberg.webtesthelper.support.AbstractPostgresTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

@Transactional
class DigestAssemblerTest extends AbstractPostgresTest {

    @Autowired
    DigestAssembler assembler;

    @Autowired
    FindingService findingService;

    @Autowired
    SiteService siteService;

    @Autowired
    ReportingProperties properties;

    private long siteId;
    private Instant now;

    @BeforeEach
    void setup() {
        siteId = siteService.create(new SiteForm(
                "Kunde A", "https://www.example.com/", 100, 3,
                Duration.ofMinutes(10), List.of(), List.of(), true, null, true));
        now = Instant.now().truncatedTo(ChronoUnit.MICROS);
    }

    @Test
    void assembleCompletedRunWithNewsAndRegressions() {
        List<CheckFinding> findings = List.of(
                new CheckFinding(CheckType.PAGE_STATUS, Severity.ERROR, "status:500:/err", page("/err"), "finding.PAGE_STATUS.httpError", List.of("500"), Evidence.NONE),
                new CheckFinding(CheckType.DEAD_LINK, Severity.WARN, "dead:/warn", page("/warn"), "finding.DEAD_LINK.dead", List.of("https://example.com/dead", "404 Not Found"), Evidence.NONE),
                new CheckFinding(CheckType.REDIRECT_CHAIN, Severity.INFO, "redirect:/info", page("/info"), "finding.REDIRECT_CHAIN.tooManyHops", List.of("2", "https://example.com/target"), Evidence.NONE)
        );

        findingService.record(1L, siteId, findings, fullCoverage(List.of("/err", "/warn", "/info")), now);

        RunSummary runSummary = new RunSummary(
                1L, siteId, RunStatus.COMPLETED, RunTrigger.SCHEDULED, RunScope.FULL,
                now.minusSeconds(60), now.minusSeconds(50), now,
                3, 0, 3, 3, 0, false, null, false, null, Set.of()
        );

        DigestWindow window = new DigestWindow(RunScope.FULL, List.of(runSummary), now);

        Digest digest = assembler.assemble(window, Locale.GERMAN);

        assertThat(digest.scope()).isEqualTo(RunScope.FULL);
        assertThat(digest.closedAt()).isEqualTo(now);
        assertThat(digest.sites()).hasSize(1);

        SiteDigest siteDigest = digest.sites().get(0);
        assertThat(siteDigest.siteId()).isEqualTo(siteId);
        assertThat(siteDigest.siteName()).isEqualTo("Kunde A");
        assertThat(siteDigest.runId()).isEqualTo(1L);
        assertThat(siteDigest.status()).isEqualTo(RunStatus.COMPLETED);
        assertThat(siteDigest.finishedAt()).isEqualTo(now);
        assertThat(siteDigest.errorMessage()).isNull();
        assertThat(siteDigest.partialCoverage()).isFalse();

        // News: ERROR and WARN shown, INFO excluded from shown but included in total
        assertThat(siteDigest.news().shown()).hasSize(2);
        assertThat(siteDigest.news().shown().get(0).severity()).isEqualTo(Severity.ERROR);
        assertThat(siteDigest.news().shown().get(1).severity()).isEqualTo(Severity.WARN);
        assertThat(siteDigest.news().total()).isEqualTo(3);
        assertThat(siteDigest.news().omitted()).isEqualTo(1);

        // Regressions: empty
        assertThat(siteDigest.regressions().shown()).isEmpty();
        assertThat(siteDigest.regressions().total()).isZero();

        // Counts: errorCount counts ERROR only (1), not WARN or INFO
        assertThat(siteDigest.errorCount()).isEqualTo(1);
        assertThat(siteDigest.fixedCount()).isZero();
        assertThat(siteDigest.stillOpenCount()).isZero();
        assertThat(siteDigest.knownCount()).isZero();
    }

    @Test
    void assembleWithMutedFindingCountsAsKnown() {
        CheckFinding finding = new CheckFinding(
                CheckType.PAGE_STATUS, Severity.ERROR, "status:500:/err", page("/err"),
                "finding.PAGE_STATUS.httpError", List.of("500"), Evidence.NONE);

        RunDiff run1 = findingService.record(1L, siteId, List.of(finding), fullCoverage(List.of("/err")), now);
        long findingId = run1.of(ReportSection.NEW).get(0).id();

        findingService.triage(
                siteId,
                List.of(findingId),
                TriageAction.of(TriageStatus.MUTED, "Wartung", now.plus(30, ChronoUnit.DAYS), now, 365),
                "tester",
                now
        );

        findingService.record(2L, siteId, List.of(finding), fullCoverage(List.of("/err")), now);

        RunSummary runSummary = new RunSummary(
                2L, siteId, RunStatus.COMPLETED, RunTrigger.SCHEDULED, RunScope.FULL,
                now.minusSeconds(60), now.minusSeconds(50), now,
                1, 0, 1, 0, 0, false, null, false, null, Set.of()
        );

        DigestWindow window = new DigestWindow(RunScope.FULL, List.of(runSummary), now);

        Digest digest = assembler.assemble(window, Locale.GERMAN);

        SiteDigest siteDigest = digest.sites().get(0);
        assertThat(siteDigest.knownCount()).isEqualTo(1);
        assertThat(siteDigest.news().shown()).isEmpty();
        assertThat(siteDigest.news().total()).isZero();
        assertThat(siteDigest.regressions().shown()).isEmpty();
        assertThat(siteDigest.regressions().total()).isZero();
        assertThat(siteDigest.errorCount()).isZero();
        assertThat(siteDigest.stillOpenCount()).isZero();
        assertThat(siteDigest.fixedCount()).isZero();
    }

    @Test
    void assembleWithFixedAndStillOpenCountsAndRegressions() {
        List<CheckFinding> threeFindings = List.of(
                new CheckFinding(CheckType.DEAD_LINK, Severity.ERROR, "dead:/a", page("/a"), "finding.DEAD_LINK.dead", List.of("https://example.com/a", "404"), Evidence.NONE),
                new CheckFinding(CheckType.DEAD_LINK, Severity.WARN, "dead:/b", page("/b"), "finding.DEAD_LINK.dead", List.of("https://example.com/b", "404"), Evidence.NONE),
                new CheckFinding(CheckType.DEAD_LINK, Severity.ERROR, "dead:/c", page("/c"), "finding.DEAD_LINK.dead", List.of("https://example.com/c", "404"), Evidence.NONE)
        );

        findingService.record(1L, siteId, threeFindings, fullCoverage(List.of("/a", "/b", "/c")), now);

        List<CheckFinding> droppedC = List.of(threeFindings.get(0), threeFindings.get(1));
        findingService.record(2L, siteId, droppedC, fullCoverage(List.of("/a", "/b", "/c")), now);

        // Run 2: /c is FIXED, /a and /b are STILL_OPEN
        RunSummary run2Summary = new RunSummary(
                2L, siteId, RunStatus.COMPLETED, RunTrigger.SCHEDULED, RunScope.FULL,
                now.minusSeconds(60), now.minusSeconds(50), now,
                2, 0, 2, 0, 1, false, null, false, null, Set.of()
        );
        DigestWindow window2 = new DigestWindow(RunScope.FULL, List.of(run2Summary), now);
        Digest digest2 = assembler.assemble(window2, Locale.GERMAN);
        SiteDigest siteDigest2 = digest2.sites().get(0);

        assertThat(siteDigest2.fixedCount()).isEqualTo(1);
        assertThat(siteDigest2.stillOpenCount()).isEqualTo(2);
        assertThat(siteDigest2.errorCount()).isZero(); // /a is STILL_OPEN ERROR, not NEW or REGRESSED
        assertThat(siteDigest2.regressions().total()).isZero();

        // Run 3: /c reappears -> REGRESSED
        findingService.record(3L, siteId, threeFindings, fullCoverage(List.of("/a", "/b", "/c")), now);
        RunSummary run3Summary = new RunSummary(
                3L, siteId, RunStatus.COMPLETED, RunTrigger.SCHEDULED, RunScope.FULL,
                now.minusSeconds(60), now.minusSeconds(50), now,
                3, 0, 3, 0, 0, false, null, false, null, Set.of()
        );
        DigestWindow window3 = new DigestWindow(RunScope.FULL, List.of(run3Summary), now);
        Digest digest3 = assembler.assemble(window3, Locale.GERMAN);
        SiteDigest siteDigest3 = digest3.sites().get(0);

        assertThat(siteDigest3.regressions().shown()).hasSize(1);
        assertThat(siteDigest3.regressions().shown().get(0).severity()).isEqualTo(Severity.ERROR);
        assertThat(siteDigest3.regressions().total()).isEqualTo(1);
        assertThat(siteDigest3.errorCount()).isEqualTo(1); // /c is REGRESSED ERROR
        assertThat(siteDigest3.stillOpenCount()).isEqualTo(2); // /a and /b
        assertThat(siteDigest3.fixedCount()).isZero();
    }

    @Test
    void assembleEnforcesMaxFindingsCapPreservingDiffOrder() {
        List<CheckFinding> twelveFindings = new ArrayList<>();
        List<String> paths = new ArrayList<>();

        // 5 ERRORs
        for (int i = 0; i < 5; i++) {
            String path = "/err" + i;
            paths.add(path);
            twelveFindings.add(new CheckFinding(
                    CheckType.DEAD_LINK, Severity.ERROR, "dead:" + path, page(path),
                    "finding.DEAD_LINK.dead", List.of("https://example.com" + path, "404"), Evidence.NONE));
        }
        // 7 WARNs
        for (int i = 0; i < 7; i++) {
            String path = "/warn" + i;
            paths.add(path);
            twelveFindings.add(new CheckFinding(
                    CheckType.DEAD_LINK, Severity.WARN, "dead:" + path, page(path),
                    "finding.DEAD_LINK.dead", List.of("https://example.com" + path, "404"), Evidence.NONE));
        }

        findingService.record(1L, siteId, twelveFindings, fullCoverage(paths), now);

        RunSummary runSummary = new RunSummary(
                1L, siteId, RunStatus.COMPLETED, RunTrigger.SCHEDULED, RunScope.FULL,
                now.minusSeconds(60), now.minusSeconds(50), now,
                12, 0, 12, 12, 0, false, null, false, null, Set.of()
        );

        DigestWindow window = new DigestWindow(RunScope.FULL, List.of(runSummary), now);

        assertThat(properties.digestMaxFindings()).isEqualTo(10);

        Digest digest = assembler.assemble(window, Locale.GERMAN);
        SiteDigest siteDigest = digest.sites().get(0);

        assertThat(siteDigest.news().shown()).hasSize(10);
        assertThat(siteDigest.news().total()).isEqualTo(12);
        assertThat(siteDigest.news().omitted()).isEqualTo(2);

        // First 5 are ERROR, next 5 are WARN
        for (int i = 0; i < 5; i++) {
            assertThat(siteDigest.news().shown().get(i).severity()).isEqualTo(Severity.ERROR);
        }
        for (int i = 5; i < 10; i++) {
            assertThat(siteDigest.news().shown().get(i).severity()).isEqualTo(Severity.WARN);
        }
        assertThat(siteDigest.errorCount()).isEqualTo(5);
    }

    @Test
    void assembleFailedRunSkipsDiffAndSetsErrorMessage() {
        // Run with status FAILED and error message, without any findingService.record call
        RunSummary runSummary = new RunSummary(
                99L, siteId, RunStatus.FAILED, RunTrigger.SCHEDULED, RunScope.FULL,
                now.minusSeconds(60), now.minusSeconds(50), now,
                0, 1, 0, 0, 0, false, null, false, "Crawler timed out", Set.of()
        );

        DigestWindow window = new DigestWindow(RunScope.FULL, List.of(runSummary), now);

        Digest digest = assembler.assemble(window, Locale.GERMAN);
        SiteDigest siteDigest = digest.sites().get(0);

        assertThat(siteDigest.failed()).isTrue();
        assertThat(siteDigest.status()).isEqualTo(RunStatus.FAILED);
        assertThat(siteDigest.errorMessage()).isEqualTo("Crawler timed out");
        assertThat(siteDigest.news().shown()).isEmpty();
        assertThat(siteDigest.news().total()).isZero();
        assertThat(siteDigest.regressions().shown()).isEmpty();
        assertThat(siteDigest.regressions().total()).isZero();
        assertThat(siteDigest.errorCount()).isZero();
        assertThat(siteDigest.fixedCount()).isZero();
        assertThat(siteDigest.stillOpenCount()).isZero();
        assertThat(siteDigest.knownCount()).isZero();
    }

    @Test
    void assembleMultipleSitesInWindow() {
        long siteId2 = siteService.create(new SiteForm(
                "Kunde B", "https://www.example2.com/", 100, 3,
                Duration.ofMinutes(10), List.of(), List.of(), true, null, true));

        List<CheckFinding> site1Findings = List.of(
                new CheckFinding(CheckType.PAGE_STATUS, Severity.ERROR, "status:500:/err", page("/err"),
                        "finding.PAGE_STATUS.httpError", List.of("500"), Evidence.NONE)
        );
        findingService.record(1L, siteId, site1Findings, fullCoverage(List.of("/err")), now);

        RunSummary run1 = new RunSummary(
                1L, siteId, RunStatus.COMPLETED, RunTrigger.SCHEDULED, RunScope.FULL,
                now.minusSeconds(60), now.minusSeconds(50), now,
                1, 0, 1, 1, 0, false, null, false, null, Set.of()
        );
        RunSummary run2 = new RunSummary(
                2L, siteId2, RunStatus.FAILED, RunTrigger.SCHEDULED, RunScope.FULL,
                now.minusSeconds(60), now.minusSeconds(50), now,
                0, 1, 0, 0, 0, false, null, false, "Connection refused", Set.of()
        );

        DigestWindow window = new DigestWindow(RunScope.FULL, List.of(run1, run2), now);

        Digest digest = assembler.assemble(window, Locale.GERMAN);

        assertThat(digest.sites()).hasSize(2);

        SiteDigest siteDigest1 = digest.sites().get(0);
        assertThat(siteDigest1.siteId()).isEqualTo(siteId);
        assertThat(siteDigest1.siteName()).isEqualTo("Kunde A");
        assertThat(siteDigest1.status()).isEqualTo(RunStatus.COMPLETED);
        assertThat(siteDigest1.errorCount()).isEqualTo(1);
        assertThat(siteDigest1.news().shown()).hasSize(1);

        SiteDigest siteDigest2 = digest.sites().get(1);
        assertThat(siteDigest2.siteId()).isEqualTo(siteId2);
        assertThat(siteDigest2.siteName()).isEqualTo("Kunde B");
        assertThat(siteDigest2.status()).isEqualTo(RunStatus.FAILED);
        assertThat(siteDigest2.errorMessage()).isEqualTo("Connection refused");
        assertThat(siteDigest2.errorCount()).isZero();
    }

    private NormalizedUrl page(String path) {
        return new NormalizedUrl("https", "www.example.com", 443, path, null);
    }

    private RunCoverage fullCoverage(List<String> locationKeys) {
        List<String> urls = locationKeys.stream().map(k -> "https://www.example.com" + k).toList();
        return RunCoverage.of(RunScope.FULL.checkTypes().stream().map(CheckType::name).toList(), urls, List.of(), false);
    }
}
