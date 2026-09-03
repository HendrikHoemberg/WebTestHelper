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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Acceptance test covering the complete 11-step lifecycle of the noisy site (spec 6.3).
 * Exercises the entire chain in order: baseline acceptance, mute rules, human mutes,
 * flap detection, and scheduled expiry sweeps.
 */
@Transactional
class TriageAcceptanceTest extends AbstractPostgresTest {

    private static final String BASE_URL = "https://www.noisy-site.example.com";

    @Autowired
    FindingService findingService;
    @Autowired
    MuteRuleService muteRuleService;
    @Autowired
    MuteRuleApplier muteRuleApplier;
    @Autowired
    MuteExpiryService muteExpiryService;
    @Autowired
    SiteService sites;
    @Autowired
    org.springframework.jdbc.core.JdbcTemplate jdbc;

    private long siteId;
    private Instant t0;

    @BeforeEach
    void setup() {
        jdbc.update("DELETE FROM mute_rule");
        siteId = sites.create(new SiteForm(
                "Noisy Customer Site", BASE_URL + "/", 500, 3,
                Duration.ofMinutes(10), List.of(), List.of(), true, null, true));
        t0 = Instant.parse("2026-08-25T10:00:00Z");
    }

    @Test
    @DisplayName("Complete 11-step lifecycle of the noisy site (spec 6.3)")
    void noisySiteTriageLifecycle() {
        // =========================================================================================
        // 1. Run 1 produces 200 findings across three check types.
        //    NEW is 200; nothing else has anything. (This is the screen §6.3 says is not triageable).
        // =========================================================================================
        List<CheckFinding> run1Findings = new ArrayList<>();
        // 10 LinkedIn dead links matching the future rule
        for (int i = 0; i < 10; i++) {
            run1Findings.add(finding(CheckType.DEAD_LINK, "https://www.linkedin.com/company/noisy-" + i, "/team"));
        }
        // 90 other dead links
        for (int i = 10; i < 100; i++) {
            run1Findings.add(finding(CheckType.DEAD_LINK, "https://www.other-site.example.com/link-" + i, "/page-a"));
        }
        // 50 page status findings
        for (int i = 0; i < 50; i++) {
            run1Findings.add(finding(CheckType.PAGE_STATUS, "status:404-" + i, "/status-" + i));
        }
        // 50 console error findings
        for (int i = 0; i < 50; i++) {
            run1Findings.add(finding(CheckType.CONSOLE_ERRORS, "console:error-" + i, "/script-" + i));
        }
        assertThat(run1Findings).hasSize(200);

        RunCoverage run1Coverage = coverageFor(run1Findings);
        RunDiff run1 = findingService.record(1L, siteId, run1Findings, run1Coverage, t0);

        assertThat(run1.count(ReportSection.NEW)).isEqualTo(200);
        assertThat(run1.count(ReportSection.KNOWN)).isZero();
        assertThat(run1.count(ReportSection.STILL_OPEN)).isZero();
        assertThat(run1.count(ReportSection.FIXED)).isZero();
        assertThat(run1.count(ReportSection.REGRESSED)).isZero();

        // =========================================================================================
        // 2. Accept the baseline. All 200 are ACKNOWLEDGED.
        // =========================================================================================
        int accepted = findingService.acceptBaseline(siteId, 1L);
        assertThat(accepted).isEqualTo(200);

        List<Finding> baselineFindings = run1.of(ReportSection.NEW).stream()
                .map(f -> findingService.byId(f.id()).orElseThrow())
                .toList();
        assertThat(baselineFindings).allMatch(f -> f.triage() == TriageStatus.ACKNOWLEDGED
                && f.triageReason() != null
                && f.mutedByRuleId() == null);

        // =========================================================================================
        // 3. Run 2, same 200 findings plus 2 genuinely new ones.
        //    NEW is 2, KNOWN is 200, STILL_OPEN is 0. The diff model starts paying out.
        // =========================================================================================
        Instant t1 = t0.plus(1, ChronoUnit.DAYS);
        CheckFinding newFindingA = finding(CheckType.DEAD_LINK, "https://www.broken-partner.example.com/a", "/rebuilding");
        CheckFinding newFindingB = finding(CheckType.DEAD_LINK, "https://www.broken-partner.example.com/b", "/rebuilding");

        List<CheckFinding> run2Findings = new ArrayList<>(run1Findings);
        run2Findings.add(newFindingA);
        run2Findings.add(newFindingB);

        RunCoverage run2Coverage = coverageFor(run2Findings);
        RunDiff run2 = findingService.record(2L, siteId, run2Findings, run2Coverage, t1);

        assertThat(run2.count(ReportSection.NEW)).isEqualTo(2);
        assertThat(run2.count(ReportSection.KNOWN)).isEqualTo(200);
        assertThat(run2.count(ReportSection.STILL_OPEN)).isZero();
        assertThat(run2.count(ReportSection.FIXED)).isZero();
        assertThat(run2.count(ReportSection.REGRESSED)).isZero();

        long findingIdA = run2.of(ReportSection.NEW).stream()
                .filter(f -> f.subjectKey().equals("https://www.broken-partner.example.com/a"))
                .findFirst().orElseThrow().id();
        long findingIdB = run2.of(ReportSection.NEW).stream()
                .filter(f -> f.subjectKey().equals("https://www.broken-partner.example.com/b"))
                .findFirst().orElseThrow().id();

        // =========================================================================================
        // 4. Create a rule — check type DEAD_LINK, subject *linkedin.com*, 90 days, with a reason.
        //    Some of the 200 already match it, and every one of those is ACKNOWLEDGED from step 2, so
        //    applyRule reports 0 findings muted (D51 — the rule matches them and must change none of them).
        //    Assert the zero: it is the deviation's whole content.
        // =========================================================================================
        Instant ruleExpiry = t1.plus(90, ChronoUnit.DAYS);
        MuteRuleForm ruleForm = new MuteRuleForm(
                siteId, CheckType.DEAD_LINK, "*linkedin.com*", null, "LinkedIn drosselt unseren Prüfer", ruleExpiry);
        long ruleId = muteRuleService.create(ruleForm, "alice", t1);

        MuteRule rule = muteRuleService.byId(ruleId).orElseThrow();
        int retroMuted = muteRuleApplier.applyRule(rule, t1);
        assertThat(retroMuted).isZero();

        // Assert all 10 matching baseline findings remain ACKNOWLEDGED and unmodified
        for (int i = 0; i < 10; i++) {
            String subj = "https://www.linkedin.com/company/noisy-" + i;
            Finding f = baselineFindings.stream()
                    .filter(b -> b.subjectKey().equals(subj))
                    .findFirst().orElseThrow();
            Finding current = findingService.byId(f.id()).orElseThrow();
            assertThat(current.triage()).isEqualTo(TriageStatus.ACKNOWLEDGED);
            assertThat(current.mutedByRuleId()).isNull();
        }

        // =========================================================================================
        // 5. Run 3 brings 5 new LinkedIn dead links — UNTRIAGED, so the rule does reach them.
        //    They land in KNOWN, not NEW. NEW is 0. Nothing about this run would mail.
        // =========================================================================================
        Instant t2 = t1.plus(1, ChronoUnit.DAYS);
        List<CheckFinding> newLinkedInFindings = new ArrayList<>();
        for (int i = 1; i <= 5; i++) {
            newLinkedInFindings.add(finding(CheckType.DEAD_LINK, "https://www.linkedin.com/in/new-" + i, "/team"));
        }

        List<CheckFinding> run3Findings = new ArrayList<>(run2Findings);
        run3Findings.addAll(newLinkedInFindings);

        RunCoverage run3Coverage = coverageFor(run3Findings);
        RunDiff run3 = findingService.record(3L, siteId, run3Findings, run3Coverage, t2);

        assertThat(run3.count(ReportSection.NEW)).isZero();
        assertThat(run3.count(ReportSection.KNOWN)).isEqualTo(205); // 200 baseline + 5 rule-muted
        assertThat(run3.count(ReportSection.STILL_OPEN)).isEqualTo(2); // The 2 untriaged from run 2
        assertThat(run3.count(ReportSection.FIXED)).isZero();
        assertThat(run3.count(ReportSection.REGRESSED)).isZero();

        for (int i = 1; i <= 5; i++) {
            String subj = "https://www.linkedin.com/in/new-" + i;
            Finding lf = run3.of(ReportSection.KNOWN).stream()
                    .filter(f -> f.subjectKey().equals(subj))
                    .findFirst().orElseThrow();
            assertThat(lf.triage()).isEqualTo(TriageStatus.MUTED);
            assertThat(lf.mutedByRuleId()).isEqualTo(ruleId);
            assertThat(lf.triageReason()).isEqualTo("LinkedIn drosselt unseren Prüfer");
        }

        // =========================================================================================
        // 6. Mute one page's findings by hand until a date 10 days out, with a reason.
        // =========================================================================================
        Instant handMuteExpiry = t2.plus(10, ChronoUnit.DAYS);
        TriageAction handMuteAction = TriageAction.of(TriageStatus.MUTED, "Seite im Umbau", handMuteExpiry, t2, 365);
        int handTriaged = findingService.triage(siteId, List.of(findingIdA, findingIdB), handMuteAction, "bob", t2);
        assertThat(handTriaged).isEqualTo(2);

        // =========================================================================================
        // 7. Run 4 does not report them. KNOWN grows, STILL_OPEN shrinks by the same number.
        // =========================================================================================
        Instant t3 = t2.plus(1, ChronoUnit.DAYS);
        RunDiff run4 = findingService.record(4L, siteId, run3Findings, run3Coverage, t3);

        assertThat(run4.count(ReportSection.KNOWN)).isEqualTo(207); // 200 baseline + 5 rule + 2 hand
        assertThat(run4.count(ReportSection.STILL_OPEN)).isZero();  // Shrank by 2 from 2 to 0
        assertThat(run4.count(ReportSection.NEW)).isZero();
        assertThat(run4.count(ReportSection.FIXED)).isZero();
        assertThat(run4.count(ReportSection.REGRESSED)).isZero();

        // =========================================================================================
        // 8. One acknowledged finding is fixed in run 5, then breaks again in run 6.
        //    It appears in FIXED, then in REGRESSED — the acknowledgement did not silence a real regression (D47).
        // 9. A muted finding is fixed in run 5. It appears in FIXED — good news outranks a mute.
        // =========================================================================================
        Instant t4 = t3.plus(1, ChronoUnit.DAYS);
        CheckFinding ackFinding = run1Findings.stream()
                .filter(f -> f.locationKey().equals("/status-0"))
                .findFirst().orElseThrow();

        // Run 5: omit ackFinding and newFindingA (hand-muted)
        List<CheckFinding> run5Findings = new ArrayList<>(run3Findings);
        run5Findings.remove(ackFinding);
        run5Findings.remove(newFindingA);

        RunCoverage persistentCoverage = run3Coverage; // Covers all URLs tested across runs
        RunDiff run5 = findingService.record(5L, siteId, run5Findings, persistentCoverage, t4);

        assertThat(run5.count(ReportSection.FIXED)).isEqualTo(2);
        assertThat(run5.of(ReportSection.FIXED)).extracting(Finding::locationKey)
                .containsExactlyInAnyOrder("/status-0", "/rebuilding");
        assertThat(run5.count(ReportSection.NEW)).isZero();
        assertThat(run5.count(ReportSection.REGRESSED)).isZero();

        // Run 6: ackFinding breaks again, newFindingA remains fixed
        Instant t5 = t4.plus(1, ChronoUnit.DAYS);
        List<CheckFinding> run6Findings = new ArrayList<>(run5Findings);
        run6Findings.add(ackFinding);

        RunDiff run6 = findingService.record(6L, siteId, run6Findings, persistentCoverage, t5);

        assertThat(run6.count(ReportSection.REGRESSED)).isEqualTo(1);
        Finding regressed = run6.of(ReportSection.REGRESSED).get(0);
        assertThat(regressed.locationKey()).isEqualTo("/status-0");
        assertThat(regressed.triage()).isEqualTo(TriageStatus.ACKNOWLEDGED);
        assertThat(run6.count(ReportSection.NEW)).isZero();
        assertThat(run6.count(ReportSection.FIXED)).isZero();

        // =========================================================================================
        // 10. Sweep at day 11. The hand-mute expires: the finding is UNTRIAGED, mute_expired_at is
        //     stamped, the reason is still readable, and its section in run 7 is STILL_OPEN — not
        //     NEW, not REGRESSED. (This is the assertion the whole plan exists to make true).
        // =========================================================================================
        Instant day11 = t2.plus(11, ChronoUnit.DAYS);
        MuteSweepResult sweep11 = muteExpiryService.sweep(day11);
        assertThat(sweep11.findingsUnmuted()).isGreaterThanOrEqualTo(1);

        Finding findingB = findingService.byId(findingIdB).orElseThrow();
        assertThat(findingB.triage()).isEqualTo(TriageStatus.UNTRIAGED);
        assertThat(findingB.mutedUntil()).isNull();
        assertThat(findingB.mutedByRuleId()).isNull();
        assertThat(findingB.muteExpiredAt()).isEqualTo(day11);
        assertThat(findingB.triageReason()).isEqualTo("Seite im Umbau");

        Instant t6 = day11.plus(1, ChronoUnit.HOURS);
        // Run 7 observes findingB (which was unmuted at day 11)
        List<CheckFinding> run7Findings = new ArrayList<>(run6Findings);
        RunDiff run7 = findingService.record(7L, siteId, run7Findings, persistentCoverage, t6);

        assertThat(run7.count(ReportSection.NEW)).isZero();
        assertThat(run7.count(ReportSection.REGRESSED)).isZero();
        assertThat(run7.of(ReportSection.STILL_OPEN)).extracting(Finding::id).contains(findingIdB);

        // =========================================================================================
        // 11. Sweep at day 91. The rule expires, its findings return, and the same section rule
        //     holds for all five at once.
        // =========================================================================================
        Instant day91 = t1.plus(91, ChronoUnit.DAYS);
        MuteSweepResult sweep91 = muteExpiryService.sweep(day91);
        assertThat(sweep91.rulesExpired()).isEqualTo(1);
        assertThat(sweep91.findingsUnmuted()).isEqualTo(5);

        for (int i = 1; i <= 5; i++) {
            String subj = "https://www.linkedin.com/in/new-" + i;
            Finding lf = run3.of(ReportSection.KNOWN).stream()
                    .filter(f -> f.subjectKey().equals(subj))
                    .findFirst().orElseThrow();
            Finding updated = findingService.byId(lf.id()).orElseThrow();
            assertThat(updated.triage()).isEqualTo(TriageStatus.UNTRIAGED);
            assertThat(updated.mutedByRuleId()).isNull();
            assertThat(updated.mutedUntil()).isNull();
            assertThat(updated.muteExpiredAt()).isEqualTo(day91);
            assertThat(updated.triageReason()).isEqualTo("LinkedIn drosselt unseren Prüfer");
        }

        Instant t7 = day91.plus(1, ChronoUnit.HOURS);
        List<CheckFinding> run8Findings = new ArrayList<>(run7Findings);
        RunDiff run8 = findingService.record(8L, siteId, run8Findings, persistentCoverage, t7);

        assertThat(run8.count(ReportSection.NEW)).isZero();
        assertThat(run8.count(ReportSection.REGRESSED)).isZero();

        List<Finding> stillOpenRun8 = run8.of(ReportSection.STILL_OPEN);
        for (int i = 1; i <= 5; i++) {
            String subj = "https://www.linkedin.com/in/new-" + i;
            assertThat(stillOpenRun8).extracting(Finding::subjectKey).contains(subj);
        }
    }

    private CheckFinding finding(CheckType type, String subject, String location) {
        return new CheckFinding(type, Severity.ERROR, subject, page(location), "msg.key", List.of(), Evidence.NONE);
    }

    private NormalizedUrl page(String path) {
        return new NormalizedUrl("https", "www.noisy-site.example.com", 443, path, null);
    }

    private RunCoverage coverageFor(List<CheckFinding> findings) {
        List<String> urls = findings.stream()
                .map(f -> BASE_URL + f.locationKey())
                .distinct()
                .toList();
        return RunCoverage.of(
                RunScope.FULL,
                RunScope.FULL.checkTypes().stream().map(CheckType::name).toList(),
                urls,
                List.of(),
                false
        );
    }
}
