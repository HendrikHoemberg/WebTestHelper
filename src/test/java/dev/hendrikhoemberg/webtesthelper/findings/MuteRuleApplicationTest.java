package dev.hendrikhoemberg.webtesthelper.findings;

import dev.hendrikhoemberg.webtesthelper.catalog.SiteForm;
import dev.hendrikhoemberg.webtesthelper.catalog.SiteService;
import dev.hendrikhoemberg.webtesthelper.model.CheckFinding;
import dev.hendrikhoemberg.webtesthelper.model.CheckType;
import dev.hendrikhoemberg.webtesthelper.model.Evidence;
import dev.hendrikhoemberg.webtesthelper.model.NormalizedUrl;
import dev.hendrikhoemberg.webtesthelper.model.ObservedStatus;
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
class MuteRuleApplicationTest extends AbstractPostgresTest {

    @Autowired
    FindingService findingService;
    @Autowired
    MuteRuleService muteRuleService;
    @Autowired
    MuteRuleApplier muteRuleApplier;
    @Autowired
    SiteService sites;
    @Autowired
    JdbcTemplate jdbc;

    private long siteA;
    private long siteB;
    private Instant now;

    @BeforeEach
    void setup() {
        siteA = sites.create(new SiteForm(
                "Site A", "https://www.site-a.com/", 100, 3,
                Duration.ofMinutes(10), List.of(), List.of(), true, null, true));
        siteB = sites.create(new SiteForm(
                "Site B", "https://www.site-b.com/", 100, 3,
                Duration.ofMinutes(10), List.of(), List.of(), true, null, true));
        now = Instant.now().truncatedTo(ChronoUnit.MICROS);
    }

    @Test
    void ruleCreatedBeforeRun2PutsMatchingFindingFirstSeenInRun2IntoKnownInRun2Diff() {
        // Run 1 sees finding 1
        findingService.record(1, siteA, List.of(finding(CheckType.DEAD_LINK, "dead:/old", "/old")),
                fullCoverage(List.of("/old")), now);

        // Rule created before Run 2 matching dead links to /new
        Instant expiry = now.plus(30, ChronoUnit.DAYS);
        muteRuleService.create(new MuteRuleForm(siteA, CheckType.DEAD_LINK, "dead:/new", null, "Ignore new dead link", expiry),
                "alice", now);

        // Run 2 sees finding 1 and new finding 2
        RunDiff diffRun2 = findingService.record(2, siteA, List.of(
                        finding(CheckType.DEAD_LINK, "dead:/old", "/old"),
                        finding(CheckType.DEAD_LINK, "dead:/new", "/new")),
                fullCoverage(List.of("/old", "/new")), now);

        // Finding 2 should be in KNOWN in run 2's own diff directly (not NEW)
        assertThat(diffRun2.count(ReportSection.NEW)).isZero();
        assertThat(diffRun2.count(ReportSection.KNOWN)).isEqualTo(1);
        assertThat(diffRun2.of(ReportSection.KNOWN).get(0).subjectKey()).isEqualTo("dead:/new");
        assertThat(diffRun2.of(ReportSection.KNOWN).get(0).triage()).isEqualTo(TriageStatus.MUTED);
        assertThat(diffRun2.count(ReportSection.STILL_OPEN)).isEqualTo(1);
    }

    @Test
    void ruleMatchingCheckTypeAloneMutesEveryFindingOfThatCheckAndNothingElse() {
        Instant expiry = now.plus(30, ChronoUnit.DAYS);
        muteRuleService.create(new MuteRuleForm(siteA, CheckType.PAGE_STATUS, null, null, "Mute all status issues", expiry),
                "alice", now);

        RunDiff diff = findingService.record(1, siteA, List.of(
                        finding(CheckType.PAGE_STATUS, "status:404", "/page1"),
                        finding(CheckType.PAGE_STATUS, "status:500", "/page2"),
                        finding(CheckType.DEAD_LINK, "dead:/page3", "/page3")),
                fullCoverage(List.of("/page1", "/page2", "/page3")), now);

        assertThat(diff.count(ReportSection.KNOWN)).isEqualTo(2);
        assertThat(diff.of(ReportSection.KNOWN)).allMatch(f -> f.type() == CheckType.PAGE_STATUS && f.triage() == TriageStatus.MUTED);

        assertThat(diff.count(ReportSection.NEW)).isEqualTo(1);
        assertThat(diff.of(ReportSection.NEW).get(0).type()).isEqualTo(CheckType.DEAD_LINK);
        assertThat(diff.of(ReportSection.NEW).get(0).triage()).isEqualTo(TriageStatus.UNTRIAGED);
    }

    @Test
    void ruleMatchingSubjectOrLocationPatternAloneMutesRegardlessOfOtherField() {
        Instant expiry = now.plus(30, ChronoUnit.DAYS);
        // Subject pattern matching *linkedin*
        muteRuleService.create(new MuteRuleForm(siteA, null, "*linkedin*", null, "Mute LinkedIn", expiry),
                "alice", now);
        // Location pattern matching /archiv/* (also testing case-insensitivity: rule has /Archiv/*)
        muteRuleService.create(new MuteRuleForm(siteA, null, null, "/Archiv/*", "Mute Archive", expiry),
                "alice", now);

        RunDiff diff = findingService.record(1, siteA, List.of(
                        finding(CheckType.DEAD_LINK, "https://www.linkedin.com/in/foo", "/team"),
                        finding(CheckType.PAGE_STATUS, "status:500", "/archiv/2020"),
                        finding(CheckType.DEAD_LINK, "https://www.other.com", "/blog")),
                fullCoverage(List.of("/team", "/archiv/2020", "/blog")), now);

        assertThat(diff.count(ReportSection.KNOWN)).isEqualTo(2);
        assertThat(diff.count(ReportSection.NEW)).isEqualTo(1);
        assertThat(diff.of(ReportSection.NEW).get(0).subjectKey()).isEqualTo("https://www.other.com");
    }

    @Test
    void bothPatternsSetAreAndNotOr() {
        Instant expiry = now.plus(30, ChronoUnit.DAYS);
        // Both subject and location pattern must match
        muteRuleService.create(new MuteRuleForm(siteA, null, "*partner*", "/partners/*", "Mute partner on partner page", expiry),
                "alice", now);

        RunDiff diff = findingService.record(1, siteA, List.of(
                        finding(CheckType.DEAD_LINK, "https://partner.com", "/partners/list"),   // Both match -> MUTED
                        finding(CheckType.DEAD_LINK, "https://partner.com", "/about"),           // Only subject matches -> UNTRIAGED
                        finding(CheckType.DEAD_LINK, "https://other.com", "/partners/list")),     // Only location matches -> UNTRIAGED
                fullCoverage(List.of("/partners/list", "/about")), now);

        assertThat(diff.count(ReportSection.KNOWN)).isEqualTo(1);
        assertThat(diff.of(ReportSection.KNOWN).get(0).locationKey()).isEqualTo("/partners/list");
        assertThat(diff.of(ReportSection.KNOWN).get(0).subjectKey()).isEqualTo("https://partner.com");

        assertThat(diff.count(ReportSection.NEW)).isEqualTo(2);
    }

    @Test
    void d51HumanTriagedAcknowledgedAndWontFixAreLeftUntouchedByMatchingRule() {
        // Run 1 records findings
        findingService.record(1, siteA, List.of(
                        finding(CheckType.DEAD_LINK, "dead:/a", "/a"),
                        finding(CheckType.DEAD_LINK, "dead:/b", "/b"),
                        finding(CheckType.DEAD_LINK, "dead:/c", "/c")),
                fullCoverage(List.of("/a", "/b", "/c")), now);

        Finding findingA = findingService.diffOf(siteA, 1).of(ReportSection.NEW).stream()
                .filter(f -> f.locationKey().equals("/a")).findFirst().orElseThrow();
        Finding findingB = findingService.diffOf(siteA, 1).of(ReportSection.NEW).stream()
                .filter(f -> f.locationKey().equals("/b")).findFirst().orElseThrow();
        Finding findingC = findingService.diffOf(siteA, 1).of(ReportSection.NEW).stream()
                .filter(f -> f.locationKey().equals("/c")).findFirst().orElseThrow();

        // Human triages finding A as ACKNOWLEDGED and finding B as WONT_FIX
        findingService.triage(siteA, List.of(findingA.id()), new TriageAction(TriageStatus.ACKNOWLEDGED, "Known issue", null), "bob", now);
        findingService.triage(siteA, List.of(findingB.id()), new TriageAction(TriageStatus.WONT_FIX, "Will not fix", null), "carol", now);

        // Now a mute rule is created matching all DEAD_LINK findings
        Instant expiry = now.plus(30, ChronoUnit.DAYS);
        muteRuleService.create(new MuteRuleForm(siteA, CheckType.DEAD_LINK, null, null, "Rule for all dead links", expiry),
                "alice", now);

        Finding updatedA = findingService.byId(findingA.id()).orElseThrow();
        Finding updatedB = findingService.byId(findingB.id()).orElseThrow();

        // D51: Human triage preserved exactly
        assertThat(updatedA.triage()).isEqualTo(TriageStatus.ACKNOWLEDGED);
        assertThat(updatedA.triageReason()).isEqualTo("Known issue");
        assertThat(updatedA.triagedBy()).isEqualTo("bob");
        assertThat(updatedA.mutedByRuleId()).isNull();

        assertThat(updatedB.triage()).isEqualTo(TriageStatus.WONT_FIX);
        assertThat(updatedB.triageReason()).isEqualTo("Will not fix");
        assertThat(updatedB.triagedBy()).isEqualTo("carol");
        assertThat(updatedB.mutedByRuleId()).isNull();

        // Finding C (which was UNTRIAGED) should now be MUTED by the rule
        Finding updatedC = findingService.byId(findingC.id()).orElseThrow();
        assertThat(updatedC.triage()).isEqualTo(TriageStatus.MUTED);
        assertThat(updatedC.mutedByRuleId()).isNotNull();
    }

    @Test
    void globalRuleMutesFindingsOnSiteWithoutRules() {
        Instant expiry = now.plus(30, ChronoUnit.DAYS);
        // Create global rule (siteId = null)
        muteRuleService.create(new MuteRuleForm(null, CheckType.TLS_CERT, null, null, "Global TLS cert mute", expiry),
                "admin", now);

        // Site B has no site-specific rules
        RunDiff diffSiteB = findingService.record(1, siteB, List.of(
                        finding(CheckType.TLS_CERT, "tls:cert-expired", "/")),
                fullCoverage(List.of("/")), now);

        assertThat(diffSiteB.count(ReportSection.KNOWN)).isEqualTo(1);
        Finding f = diffSiteB.of(ReportSection.KNOWN).get(0);
        assertThat(f.triage()).isEqualTo(TriageStatus.MUTED);
        assertThat(f.triagedBy()).isEqualTo("admin");
        assertThat(f.triageReason()).isEqualTo("Global TLS cert mute");
    }

    @Test
    void expiredRuleMutesNothingOnNextRun() {
        Instant expiry = now.plus(30, ChronoUnit.DAYS);
        long ruleId = muteRuleService.create(new MuteRuleForm(siteA, CheckType.DEAD_LINK, null, null, "Expiring rule", expiry),
                "alice", now);

        // Future run after rule expiration
        Instant futureNow = now.plus(31, ChronoUnit.DAYS);

        RunDiff diff = findingService.record(1, siteA, List.of(
                        finding(CheckType.DEAD_LINK, "dead:/test", "/test")),
                fullCoverage(List.of("/test")), futureNow);

        assertThat(diff.count(ReportSection.NEW)).isEqualTo(1);
        assertThat(diff.count(ReportSection.KNOWN)).isZero();
        assertThat(diff.of(ReportSection.NEW).get(0).triage()).isEqualTo(TriageStatus.UNTRIAGED);
    }

    @Test
    void applyRuleOnCreationMutesExistingActiveAndResolvedFindingsAndReturnsCount() {
        // Run 1 sees finding 1 and finding 2
        findingService.record(1, siteA, List.of(
                        finding(CheckType.DEAD_LINK, "dead:/a", "/a"),
                        finding(CheckType.DEAD_LINK, "dead:/resolved", "/resolved")),
                fullCoverage(List.of("/a", "/resolved")), now);

        // Run 2 drops finding 2 -> resolves it
        findingService.record(2, siteA, List.of(
                        finding(CheckType.DEAD_LINK, "dead:/a", "/a")),
                fullCoverage(List.of("/a", "/resolved")), now);

        Finding findingResolved = findingService.byId(
                jdbc.queryForObject("SELECT id FROM finding WHERE location_key = '/resolved'", Long.class)).orElseThrow();
        assertThat(findingResolved.observed()).isEqualTo(ObservedStatus.RESOLVED);
        assertThat(findingResolved.triage()).isEqualTo(TriageStatus.UNTRIAGED);

        // Create rule matching all DEAD_LINK
        Instant expiry = now.plus(30, ChronoUnit.DAYS);
        MuteRuleForm form = new MuteRuleForm(siteA, CheckType.DEAD_LINK, null, null, "Mute all dead links", expiry);
        long ruleId = muteRuleService.create(form, "alice", now);

        // Verify both ACTIVE and RESOLVED findings were muted
        Finding activeFinding = findingService.byId(
                jdbc.queryForObject("SELECT id FROM finding WHERE location_key = '/a'", Long.class)).orElseThrow();
        Finding resolvedFinding = findingService.byId(findingResolved.id()).orElseThrow();

        assertThat(activeFinding.triage()).isEqualTo(TriageStatus.MUTED);
        assertThat(activeFinding.mutedByRuleId()).isEqualTo(ruleId);

        assertThat(resolvedFinding.triage()).isEqualTo(TriageStatus.MUTED);
        assertThat(resolvedFinding.mutedByRuleId()).isEqualTo(ruleId);

        // Direct applyRule test to assert returned count
        MuteRule rule = muteRuleService.byId(ruleId).orElseThrow();
        int affected = muteRuleApplier.applyRule(rule, now);
        // Already muted, so re-applying returns 0 because UNTRIAGED guard applies
        assertThat(affected).isZero();
    }

    @Test
    void unmuteRuleReturnsFindingsToUntriagedClearsRuleIdStampsExpiryAndLeavesHumanMutesAlone() {
        // Create 2 findings on Site A
        findingService.record(1, siteA, List.of(
                        finding(CheckType.DEAD_LINK, "dead:/rule-muted", "/rule-muted"),
                        finding(CheckType.DEAD_LINK, "dead:/human-muted", "/human-muted")),
                fullCoverage(List.of("/rule-muted", "/human-muted")), now);

        Finding fRule = findingService.byId(jdbc.queryForObject("SELECT id FROM finding WHERE location_key = '/rule-muted'", Long.class)).orElseThrow();
        Finding fHuman = findingService.byId(jdbc.queryForObject("SELECT id FROM finding WHERE location_key = '/human-muted'", Long.class)).orElseThrow();

        // Human mutes finding 2
        findingService.triage(siteA, List.of(fHuman.id()), new TriageAction(TriageStatus.MUTED, "Human mute", now.plus(10, ChronoUnit.DAYS)), "bob", now);

        // Rule mutes finding 1
        Instant expiry = now.plus(30, ChronoUnit.DAYS);
        long ruleId = muteRuleService.create(new MuteRuleForm(siteA, CheckType.DEAD_LINK, "dead:/rule-muted", null, "Rule mute", expiry), "alice", now);

        Finding beforeUnmuteRule = findingService.byId(fRule.id()).orElseThrow();
        assertThat(beforeUnmuteRule.triage()).isEqualTo(TriageStatus.MUTED);
        assertThat(beforeUnmuteRule.mutedByRuleId()).isEqualTo(ruleId);

        Finding beforeUnmuteHuman = findingService.byId(fHuman.id()).orElseThrow();
        assertThat(beforeUnmuteHuman.triage()).isEqualTo(TriageStatus.MUTED);
        assertThat(beforeUnmuteHuman.mutedByRuleId()).isNull();

        // Delete rule (which triggers unmuteRule)
        muteRuleService.delete(ruleId);

        // Rule-muted finding must be UNTRIAGED, muted_by_rule_id cleared, mute_expired_at stamped.
        // The reason survives, exactly as it does when the sweep expires a mute (D50): the screen
        // prints it as "Damalige Begründung" next to a live occurrence count.
        Finding afterUnmuteRule = findingService.byId(fRule.id()).orElseThrow();
        assertThat(afterUnmuteRule.triage()).isEqualTo(TriageStatus.UNTRIAGED);
        assertThat(afterUnmuteRule.mutedByRuleId()).isNull();
        assertThat(afterUnmuteRule.triageReason()).isEqualTo("Rule mute");
        assertThat(afterUnmuteRule.triagedBy()).isEqualTo("alice");
        assertThat(afterUnmuteRule.mutedUntil()).isNull();
        assertThat(afterUnmuteRule.muteExpiredAt()).isNotNull();

        // Human-muted finding must remain untouched
        Finding afterUnmuteHuman = findingService.byId(fHuman.id()).orElseThrow();
        assertThat(afterUnmuteHuman.triage()).isEqualTo(TriageStatus.MUTED);
        assertThat(afterUnmuteHuman.triageReason()).isEqualTo("Human mute");
        assertThat(afterUnmuteHuman.triagedBy()).isEqualTo("bob");
        assertThat(afterUnmuteHuman.mutedByRuleId()).isNull();
        assertThat(afterUnmuteHuman.muteExpiredAt()).isNull();
    }

    @Test
    void mutingCopiesRuleExpiryIntoFindingMutedUntil() {
        Instant expiry = now.plus(45, ChronoUnit.DAYS);
        long ruleId = muteRuleService.create(new MuteRuleForm(siteA, CheckType.DEAD_LINK, null, null, "Copy expiry test", expiry), "alice", now);

        findingService.record(1, siteA, List.of(
                        finding(CheckType.DEAD_LINK, "dead:/test", "/test")),
                fullCoverage(List.of("/test")), now);

        Finding f = findingService.byId(jdbc.queryForObject("SELECT id FROM finding WHERE location_key = '/test'", Long.class)).orElseThrow();
        assertThat(f.triage()).isEqualTo(TriageStatus.MUTED);
        assertThat(f.mutedUntil()).isEqualTo(expiry.truncatedTo(ChronoUnit.MICROS));
        assertThat(f.mutedByRuleId()).isEqualTo(ruleId);
    }

    @Test
    void deletingARuleLeavesAHumanWhoOverrodeItsMuteUntouched() {
        // D51's second clause: deleting the rule cannot un-triage. The finding was muted BY the
        // rule first, so muted_by_rule_id was set — and then a human made their own decision.
        findingService.record(1, siteA, List.of(finding(CheckType.DEAD_LINK, "dead:/override", "/override")),
                fullCoverage(List.of("/override")), now);

        long ruleId = muteRuleService.create(new MuteRuleForm(
                siteA, CheckType.DEAD_LINK, null, null, "Regel-Grund", now.plus(30, ChronoUnit.DAYS)), "alice", now);

        Finding muted = findingService.byId(
                jdbc.queryForObject("SELECT id FROM finding WHERE location_key = '/override'", Long.class)).orElseThrow();
        assertThat(muted.mutedByRuleId()).isEqualTo(ruleId);

        findingService.triage(siteA, List.of(muted.id()),
                new TriageAction(TriageStatus.WONT_FIX, "Kunde will das so", null), "bob", now);

        Finding overridden = findingService.byId(muted.id()).orElseThrow();
        assertThat(overridden.mutedByRuleId())
                .as("a human's decision detaches the finding from the rule")
                .isNull();

        muteRuleService.delete(ruleId);

        Finding afterDelete = findingService.byId(muted.id()).orElseThrow();
        assertThat(afterDelete.triage()).isEqualTo(TriageStatus.WONT_FIX);
        assertThat(afterDelete.triageReason()).isEqualTo("Kunde will das so");
        assertThat(afterDelete.triagedBy()).isEqualTo("bob");
        assertThat(afterDelete.muteExpiredAt()).isNull();
    }

    private CheckFinding finding(CheckType type, String subject, String location) {
        return new CheckFinding(type, Severity.ERROR, subject, page(location), "message", List.of(), Evidence.NONE);
    }

    private NormalizedUrl page(String path) {
        return new NormalizedUrl("https", "www.site-a.com", 443, path, null);
    }

    private RunCoverage fullCoverage(List<String> locationKeys) {
        List<String> urls = locationKeys.stream().map(k -> "https://www.site-a.com" + k).toList();
        return RunCoverage.of(RunScope.FULL, RunScope.FULL.checkTypes().stream().map(CheckType::name).toList(), urls, List.of(), false);
    }
}
