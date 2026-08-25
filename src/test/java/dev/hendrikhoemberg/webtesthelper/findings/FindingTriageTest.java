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

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@Transactional
class FindingTriageTest extends AbstractPostgresTest {

    private static final int MAX_MUTE_DAYS = 365;

    @Autowired
    FindingService service;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    SiteService sites;

    private long siteId;
    private Instant now;

    @BeforeEach
    void setup() {
        siteId = sites.create(new SiteForm(
                "Kunde A", "https://www.example.com/", 100, 3,
                Duration.ofMinutes(10), List.of(), List.of(), true, null, true));
        now = Instant.now().truncatedTo(ChronoUnit.MICROS);
    }

    @Test
    void triagingThreeIdsToAcknowledgedWritesAuditFieldsOnExactlyThoseThree() {
        RunDiff diff = service.record(1, siteId, fourFindings(), fullCoverage(List.of("/a", "/b", "/c", "/d")), now);
        List<Finding> newFindings = diff.of(ReportSection.NEW);
        List<Long> threeIds = newFindings.subList(0, 3).stream().map(Finding::id).toList();
        long untouchedId = newFindings.get(3).id();

        TriageAction action = TriageAction.of(TriageStatus.ACKNOWLEDGED, "Sammel-Freigabe", null, now, MAX_MUTE_DAYS);
        int changed = service.triage(siteId, threeIds, action, "alice", now);

        assertThat(changed).isEqualTo(3);

        for (Long id : threeIds) {
            Finding f = service.byId(id).orElseThrow();
            assertThat(f.triage()).isEqualTo(TriageStatus.ACKNOWLEDGED);
            assertThat(f.triageReason()).isEqualTo("Sammel-Freigabe");
            assertThat(f.triagedBy()).isEqualTo("alice");
            assertThat(triagedAt(id)).isEqualTo(now);
            assertThat(f.mutedUntil()).isNull();
        }

        Finding untouched = service.byId(untouchedId).orElseThrow();
        assertThat(untouched.triage()).isEqualTo(TriageStatus.UNTRIAGED);
        assertThat(untouched.triageReason()).isNull();
        assertThat(untouched.triagedBy()).isNull();
    }

    @Test
    void triagingToMutedWritesMutedUntilAndCanBeReadBackById() {
        RunDiff diff = service.record(1, siteId, List.of(singleFinding("/a")), fullCoverage(List.of("/a")), now);
        long id = diff.of(ReportSection.NEW).get(0).id();

        Instant expiry = now.plus(90, ChronoUnit.DAYS);
        TriageAction action = TriageAction.of(TriageStatus.MUTED, "Warten auf Relaunch", expiry, now, MAX_MUTE_DAYS);
        int changed = service.triage(siteId, List.of(id), action, "bob", now);

        assertThat(changed).isEqualTo(1);

        Finding f = service.byId(id).orElseThrow();
        assertThat(f.triage()).isEqualTo(TriageStatus.MUTED);
        assertThat(f.triageReason()).isEqualTo("Warten auf Relaunch");
        assertThat(f.triagedBy()).isEqualTo("bob");
        assertThat(f.mutedUntil()).isEqualTo(expiry);
    }

    @Test
    void idBelongingToAnotherSiteIsNotChangedAndNotCounted() {
        long siteB = sites.create(new SiteForm(
                "Kunde B", "https://www.other.com/", 100, 3,
                Duration.ofMinutes(10), List.of(), List.of(), true, null, true));

        RunDiff diffA = service.record(1, siteId, List.of(singleFinding("/a")), fullCoverage(List.of("/a")), now);
        long idA = diffA.of(ReportSection.NEW).get(0).id();

        RunDiff diffB = service.record(1, siteB, List.of(singleFinding("/b")), fullCoverage(List.of("/b")), now);
        long idB = diffB.of(ReportSection.NEW).get(0).id();

        TriageAction action = TriageAction.of(TriageStatus.ACKNOWLEDGED, "OK", null, now, MAX_MUTE_DAYS);

        // Attempting to triage Site B's finding under Site A's scope
        int changed = service.triage(siteId, List.of(idB), action, "eve", now);
        assertThat(changed).isZero();
        assertThat(service.byId(idB).orElseThrow().triage()).isEqualTo(TriageStatus.UNTRIAGED);

        // Mixed request only updates Site A's finding
        int mixedChanged = service.triage(siteId, List.of(idA, idB), action, "eve", now);
        assertThat(mixedChanged).isEqualTo(1);
        assertThat(service.byId(idA).orElseThrow().triage()).isEqualTo(TriageStatus.ACKNOWLEDGED);
        assertThat(service.byId(idB).orElseThrow().triage()).isEqualTo(TriageStatus.UNTRIAGED);
    }

    @Test
    void untriagingClearsReasonMutedUntilAndTriagedByWhileLeavingMuteExpiredAtUntouched() {
        RunDiff diff = service.record(1, siteId, List.of(singleFinding("/a")), fullCoverage(List.of("/a")), now);
        long id = diff.of(ReportSection.NEW).get(0).id();

        Instant expiry = now.plus(30, ChronoUnit.DAYS);
        TriageAction mute = TriageAction.of(TriageStatus.MUTED, "Voruebergehend", expiry, now, MAX_MUTE_DAYS);
        service.triage(siteId, List.of(id), mute, "charlie", now);

        Instant expiredStamp = now.minus(1, ChronoUnit.HOURS);
        jdbc.update("UPDATE finding SET mute_expired_at = ? WHERE id = ?", Timestamp.from(expiredStamp), id);

        TriageAction unTriage = TriageAction.of(TriageStatus.UNTRIAGED, "Soll wieder rein", expiry, now, MAX_MUTE_DAYS);
        int changed = service.triage(siteId, List.of(id), unTriage, "charlie", now);

        assertThat(changed).isEqualTo(1);

        Finding f = service.byId(id).orElseThrow();
        assertThat(f.triage()).isEqualTo(TriageStatus.UNTRIAGED);
        assertThat(f.triageReason()).isNull();
        assertThat(f.triagedBy()).isNull();
        assertThat(f.mutedUntil()).isNull();
        assertThat(f.muteExpiredAt()).isEqualTo(expiredStamp);
    }

    @Test
    void bothStatusColumnsStayOrthogonalSoResolvedFindingCanBeTriagedAndStaysResolved() {
        // Run 1 observes finding
        service.record(1, siteId, List.of(singleFinding("/a")), fullCoverage(List.of("/a")), now);

        // Run 2 does not observe it -> resolved
        RunDiff run2 = service.record(2, siteId, List.of(), fullCoverage(List.of("/a")), now);
        Finding resolvedFinding = run2.of(ReportSection.FIXED).get(0);
        assertThat(resolvedFinding.observed()).isEqualTo(ObservedStatus.RESOLVED);

        TriageAction action = TriageAction.of(TriageStatus.WONT_FIX, "Dauerhafter Verzicht", null, now, MAX_MUTE_DAYS);
        service.triage(siteId, List.of(resolvedFinding.id()), action, "dave", now);

        Finding reRead = service.byId(resolvedFinding.id()).orElseThrow();
        assertThat(reRead.observed()).isEqualTo(ObservedStatus.RESOLVED);
        assertThat(reRead.triage()).isEqualTo(TriageStatus.WONT_FIX);
        assertThat(reRead.triageReason()).isEqualTo("Dauerhafter Verzicht");
    }

    private CheckFinding singleFinding(String path) {
        return new CheckFinding(CheckType.DEAD_LINK, Severity.ERROR, "dead:" + path,
                new NormalizedUrl("https", "www.example.com", 443, path, null),
                "finding.DEAD_LINK.dead", List.of(), Evidence.NONE);
    }

    private List<CheckFinding> fourFindings() {
        return List.of(
                singleFinding("/a"),
                singleFinding("/b"),
                singleFinding("/c"),
                singleFinding("/d"));
    }

    private RunCoverage fullCoverage(List<String> paths) {
        List<String> urls = paths.stream().map(p -> "https://www.example.com" + p).toList();
        return RunCoverage.of(
                RunScope.FULL.checkTypes().stream().map(CheckType::name).toList(),
                urls, List.of(), false);
    }

    private Instant triagedAt(long findingId) {
        Timestamp ts = jdbc.queryForObject("SELECT triaged_at FROM finding WHERE id = ?", Timestamp.class, findingId);
        return ts == null ? null : ts.toInstant();
    }
}
