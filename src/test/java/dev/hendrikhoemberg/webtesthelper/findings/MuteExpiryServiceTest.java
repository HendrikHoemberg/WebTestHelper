package dev.hendrikhoemberg.webtesthelper.findings;

import dev.hendrikhoemberg.webtesthelper.catalog.SiteForm;
import dev.hendrikhoemberg.webtesthelper.catalog.SiteService;
import dev.hendrikhoemberg.webtesthelper.findings.persistence.MuteRuleEntity;
import dev.hendrikhoemberg.webtesthelper.findings.persistence.MuteRuleRepository;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@Transactional
class MuteExpiryServiceTest extends AbstractPostgresTest {

    private static final int MAX_MUTE_DAYS = 365;

    @Autowired
    MuteExpiryService muteExpiryService;
    @Autowired
    FindingService findingService;
    @Autowired
    MuteRuleService muteRuleService;
    @Autowired
    MuteRuleRepository muteRuleRepository;
    @Autowired
    SiteService sites;
    @Autowired
    JdbcTemplate jdbc;

    private long siteId;
    private Instant now;

    @BeforeEach
    void setup() {
        siteId = sites.create(new SiteForm(
                "Kunde Expiry", "https://www.example.com/", 100, 3,
                Duration.ofMinutes(10), List.of(), List.of(), true, null, true));
        now = Instant.parse("2026-08-25T12:00:00Z");
    }

    @Test
    void findingMutedUntilYesterdayIsUntriagedAfterSweepWithMuteExpiredAtStampedAndTriageReasonUnchanged() {
        // Record run 1
        Instant runTime = now.minus(10, ChronoUnit.DAYS);
        RunDiff diff = findingService.record(1, siteId, List.of(finding(CheckType.DEAD_LINK, "dead:/old", "/page1")),
                fullCoverage(List.of("/page1")), runTime);
        long findingId = diff.of(ReportSection.NEW).get(0).id();

        // Mute until yesterday
        Instant yesterday = now.minus(1, ChronoUnit.DAYS);
        TriageAction action = TriageAction.of(TriageStatus.MUTED, "Temporär stumm", yesterday, runTime, MAX_MUTE_DAYS);
        findingService.triage(siteId, List.of(findingId), action, "alice", runTime);

        // Sweep at now
        MuteSweepResult result = muteExpiryService.sweep(now);
        assertThat(result.findingsUnmuted()).isEqualTo(1);
        assertThat(result.rulesExpired()).isZero();

        // Verify finding state
        Finding finding = findingService.byId(findingId).orElseThrow();
        assertThat(finding.triage()).isEqualTo(TriageStatus.UNTRIAGED);
        assertThat(finding.mutedUntil()).isNull();
        assertThat(finding.mutedByRuleId()).isNull();
        assertThat(finding.muteExpiredAt()).isEqualTo(now);
        // D50: triage_reason remains unchanged
        assertThat(finding.triageReason()).isEqualTo("Temporär stumm");
        assertThat(finding.triagedBy()).isEqualTo("alice");
    }

    @Test
    void findingMutedUntilNextWeekIsUntouched() {
        Instant runTime = now.minus(2, ChronoUnit.DAYS);
        RunDiff diff = findingService.record(1, siteId, List.of(finding(CheckType.DEAD_LINK, "dead:/future", "/page1")),
                fullCoverage(List.of("/page1")), runTime);
        long findingId = diff.of(ReportSection.NEW).get(0).id();

        Instant nextWeek = now.plus(7, ChronoUnit.DAYS);
        TriageAction action = TriageAction.of(TriageStatus.MUTED, "Noch gültig", nextWeek, runTime, MAX_MUTE_DAYS);
        findingService.triage(siteId, List.of(findingId), action, "bob", runTime);

        MuteSweepResult result = muteExpiryService.sweep(now);
        assertThat(result.findingsUnmuted()).isZero();
        assertThat(result.rulesExpired()).isZero();

        Finding finding = findingService.byId(findingId).orElseThrow();
        assertThat(finding.triage()).isEqualTo(TriageStatus.MUTED);
        assertThat(finding.mutedUntil()).isEqualTo(nextWeek);
        assertThat(finding.muteExpiredAt()).isNull();
        assertThat(finding.triageReason()).isEqualTo("Noch gültig");
    }

    @Test
    void sweepIsIdempotent() {
        Instant runTime = now.minus(5, ChronoUnit.DAYS);
        RunDiff diff = findingService.record(1, siteId, List.of(finding(CheckType.DEAD_LINK, "dead:/old", "/page1")),
                fullCoverage(List.of("/page1")), runTime);
        long findingId = diff.of(ReportSection.NEW).get(0).id();

        Instant expiredTime = now.minus(1, ChronoUnit.DAYS);
        TriageAction action = TriageAction.of(TriageStatus.MUTED, "Idempotenz-Test", expiredTime, runTime, MAX_MUTE_DAYS);
        findingService.triage(siteId, List.of(findingId), action, "alice", runTime);

        // First sweep
        MuteSweepResult firstResult = muteExpiryService.sweep(now);
        assertThat(firstResult.findingsUnmuted()).isEqualTo(1);
        assertThat(firstResult.rulesExpired()).isZero();

        Finding afterFirst = findingService.byId(findingId).orElseThrow();
        assertThat(afterFirst.triage()).isEqualTo(TriageStatus.UNTRIAGED);
        assertThat(afterFirst.muteExpiredAt()).isEqualTo(now);

        // Second sweep 1 hour later
        Instant oneHourLater = now.plus(1, ChronoUnit.HOURS);
        MuteSweepResult secondResult = muteExpiryService.sweep(oneHourLater);
        assertThat(secondResult.findingsUnmuted()).isZero();
        assertThat(secondResult.rulesExpired()).isZero();

        Finding afterSecond = findingService.byId(findingId).orElseThrow();
        assertThat(afterSecond.triage()).isEqualTo(TriageStatus.UNTRIAGED);
        // mute_expired_at must NOT be re-stamped with oneHourLater
        assertThat(afterSecond.muteExpiredAt()).isEqualTo(now);
    }

    @Test
    void rulePastExpiresAtGetsExpiredAtStampedAndItsFindingsUnmutedInSameSweep() {
        Instant creationTime = now.minus(20, ChronoUnit.DAYS);
        Instant ruleExpiry = now.minus(2, ChronoUnit.DAYS);

        long ruleId = muteRuleService.create(new MuteRuleForm(
                siteId, CheckType.DEAD_LINK, "dead:/rule-target", null, "Regel abgelaufen", ruleExpiry),
                "admin", creationTime);

        // Finding recorded before rule expired
        RunDiff diff = findingService.record(1, siteId, List.of(finding(CheckType.DEAD_LINK, "dead:/rule-target", "/page1")),
                fullCoverage(List.of("/page1")), creationTime);

        long findingId = diff.of(ReportSection.KNOWN).get(0).id();
        Finding beforeSweep = findingService.byId(findingId).orElseThrow();
        assertThat(beforeSweep.triage()).isEqualTo(TriageStatus.MUTED);
        assertThat(beforeSweep.mutedByRuleId()).isEqualTo(ruleId);

        // Sweep at now (which is after ruleExpiry)
        MuteSweepResult result = muteExpiryService.sweep(now);
        assertThat(result.findingsUnmuted()).isEqualTo(1);
        assertThat(result.rulesExpired()).isEqualTo(1);

        // Verify rule entity has expired_at stamped
        MuteRuleEntity ruleEntity = muteRuleRepository.findById(ruleId).orElseThrow();
        assertThat(ruleEntity.getExpiredAt()).isEqualTo(now);

        // Verify finding unmuted
        Finding afterSweep = findingService.byId(findingId).orElseThrow();
        assertThat(afterSweep.triage()).isEqualTo(TriageStatus.UNTRIAGED);
        assertThat(afterSweep.mutedUntil()).isNull();
        assertThat(afterSweep.mutedByRuleId()).isNull();
        assertThat(afterSweep.muteExpiredAt()).isEqualTo(now);
        assertThat(afterSweep.triageReason()).isEqualTo("Regel abgelaufen");

        // Second sweep expires no rules and un-mutes no findings
        MuteSweepResult secondResult = muteExpiryService.sweep(now.plus(1, ChronoUnit.HOURS));
        assertThat(secondResult.findingsUnmuted()).isZero();
        assertThat(secondResult.rulesExpired()).isZero();
    }

    @Test
    void ruleDeletedBetweenTwoSweepsLeavesNothingForSecondToDo() {
        Instant creationTime = now.minus(20, ChronoUnit.DAYS);
        Instant ruleExpiry = now.plus(10, ChronoUnit.DAYS);

        long ruleId = muteRuleService.create(new MuteRuleForm(
                siteId, CheckType.DEAD_LINK, "dead:/to-delete", null, "Wird geloescht", ruleExpiry),
                "admin", creationTime);

        RunDiff diff = findingService.record(1, siteId, List.of(finding(CheckType.DEAD_LINK, "dead:/to-delete", "/page1")),
                fullCoverage(List.of("/page1")), creationTime);
        long findingId = diff.of(ReportSection.KNOWN).get(0).id();

        // First sweep at now (before rule expiry) - nothing to expire
        MuteSweepResult firstResult = muteExpiryService.sweep(now);
        assertThat(firstResult.findingsUnmuted()).isZero();
        assertThat(firstResult.rulesExpired()).isZero();

        // Delete rule now (unmutes finding immediately)
        muteRuleService.delete(ruleId);

        Finding afterDelete = findingService.byId(findingId).orElseThrow();
        assertThat(afterDelete.triage()).isEqualTo(TriageStatus.UNTRIAGED);

        // Second sweep at future time after original expiry
        Instant future = now.plus(20, ChronoUnit.DAYS);
        MuteSweepResult secondResult = muteExpiryService.sweep(future);
        assertThat(secondResult.findingsUnmuted()).isZero();
        assertThat(secondResult.rulesExpired()).isZero();
    }

    @Test
    void sweepIsTimeDrivenNotCoverageDriven() {
        // Mute on /unvisited-page until now.plus(10 days)
        Instant runTime = now.minus(5, ChronoUnit.DAYS);
        RunDiff diff = findingService.record(1, siteId, List.of(
                finding(CheckType.DEAD_LINK, "dead:/unvisited", "/unvisited-page")),
                fullCoverage(List.of("/unvisited-page")), runTime);
        long findingId = diff.of(ReportSection.NEW).get(0).id();

        Instant muteUntil = now.plus(10, ChronoUnit.DAYS);
        findingService.triage(siteId, List.of(findingId),
                TriageAction.of(TriageStatus.MUTED, "Unvisited mute", muteUntil, runTime, MAX_MUTE_DAYS),
                "alice", runTime);

        // Record a PULSE run whose coverage excludes /unvisited-page
        findingService.record(2, siteId, List.of(), pulseCoverage(List.of("/visited-page")), now);

        // Sweep at now: mute is still valid, must keep MUTED status
        MuteSweepResult result1 = muteExpiryService.sweep(now);
        assertThat(result1.findingsUnmuted()).isZero();

        Finding stillMuted = findingService.byId(findingId).orElseThrow();
        assertThat(stillMuted.triage()).isEqualTo(TriageStatus.MUTED);
        assertThat(stillMuted.mutedUntil()).isEqualTo(muteUntil);
        assertThat(stillMuted.muteExpiredAt()).isNull();

        // Advance past expiry: now sweep expires it
        Instant pastExpiry = now.plus(15, ChronoUnit.DAYS);
        MuteSweepResult result2 = muteExpiryService.sweep(pastExpiry);
        assertThat(result2.findingsUnmuted()).isEqualTo(1);

        Finding expiredFinding = findingService.byId(findingId).orElseThrow();
        assertThat(expiredFinding.triage()).isEqualTo(TriageStatus.UNTRIAGED);
        assertThat(expiredFinding.muteExpiredAt()).isEqualTo(pastExpiry);
    }

    @Test
    void unmutedFindingNextAppearanceIsStillOpenNotNewAndNotRegressed() {
        // Run 1: finding first seen
        Instant t1 = now.minus(20, ChronoUnit.DAYS);
        RunDiff run1 = findingService.record(1, siteId, List.of(
                finding(CheckType.DEAD_LINK, "dead:/reappearing", "/page1")),
                fullCoverage(List.of("/page1")), t1);
        assertThat(run1.count(ReportSection.NEW)).isEqualTo(1);
        long findingId = run1.of(ReportSection.NEW).get(0).id();

        // Triage to MUTED until t1 + 10 days
        Instant muteUntil = t1.plus(10, ChronoUnit.DAYS);
        findingService.triage(siteId, List.of(findingId),
                TriageAction.of(TriageStatus.MUTED, "Temporär", muteUntil, t1, MAX_MUTE_DAYS),
                "alice", t1);

        // Run 2: during mute (t1 + 5 days), finding still observed -> KNOWN, last_seen_run updated to 2
        Instant t2 = t1.plus(5, ChronoUnit.DAYS);
        RunDiff run2 = findingService.record(2, siteId, List.of(
                finding(CheckType.DEAD_LINK, "dead:/reappearing", "/page1")),
                fullCoverage(List.of("/page1")), t2);
        assertThat(run2.count(ReportSection.KNOWN)).isEqualTo(1);

        // Sweep at t1 + 12 days (past expiry) -> finding un-muted
        Instant sweepTime = t1.plus(12, ChronoUnit.DAYS);
        MuteSweepResult sweepResult = muteExpiryService.sweep(sweepTime);
        assertThat(sweepResult.findingsUnmuted()).isEqualTo(1);

        Finding findingAfterSweep = findingService.byId(findingId).orElseThrow();
        assertThat(findingAfterSweep.triage()).isEqualTo(TriageStatus.UNTRIAGED);
        assertThat(findingAfterSweep.firstSeenRun()).isEqualTo(1L);
        assertThat(findingAfterSweep.lastSeenRun()).isEqualTo(2L);
        assertThat(findingAfterSweep.resolvedAtRun()).isNull();
        assertThat(findingAfterSweep.regressedAtRun()).isNull();

        // Run 3: after sweep (t1 + 15 days), finding observed again
        Instant t3 = t1.plus(15, ChronoUnit.DAYS);
        RunDiff run3 = findingService.record(3, siteId, List.of(
                finding(CheckType.DEAD_LINK, "dead:/reappearing", "/page1")),
                fullCoverage(List.of("/page1")), t3);

        // Assert section: STILL_OPEN, not NEW and not REGRESSED
        assertThat(run3.count(ReportSection.NEW)).isZero();
        assertThat(run3.count(ReportSection.REGRESSED)).isZero();
        assertThat(run3.count(ReportSection.KNOWN)).isZero();
        assertThat(run3.count(ReportSection.STILL_OPEN)).isEqualTo(1);
        Finding reported = run3.of(ReportSection.STILL_OPEN).get(0);
        assertThat(reported.id()).isEqualTo(findingId);
    }

    private CheckFinding finding(CheckType type, String subject, String location) {
        return new CheckFinding(type, Severity.WARN, subject, page(location), "msg.key", List.of(), Evidence.NONE);
    }

    private NormalizedUrl page(String path) {
        return new NormalizedUrl("https", "www.example.com", 443, path, null);
    }

    private RunCoverage fullCoverage(List<String> pages) {
        List<String> urls = pages.stream().map(p -> "https://www.example.com" + p).toList();
        return RunCoverage.of(RunScope.FULL.checkTypes().stream().map(CheckType::name).toList(), urls, List.of(), false);
    }

    private RunCoverage pulseCoverage(List<String> pages) {
        List<String> urls = pages.stream().map(p -> "https://www.example.com" + p).toList();
        return RunCoverage.of(RunScope.PULSE.checkTypes().stream().map(CheckType::name).toList(), urls, List.of(), false);
    }
}
