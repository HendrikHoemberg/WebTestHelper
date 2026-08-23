package dev.hendrikhoemberg.webtesthelper.findings;

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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@Transactional
class BaselineAcceptanceTest extends AbstractPostgresTest {

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
                java.time.Duration.ofMinutes(10), List.of(), List.of(), true, null));
        observedAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
    }

    @Test
    void acceptBaselineTriagesOnlyTheFindingsThatRunObserved() {
        // Run 1 carries three UNTRIAGED findings, plus one already triaged as WONT_FIX.
        List<CheckFinding> run1 = new java.util.ArrayList<>(threeFindings());
        run1.add(wontFixFinding());
        service.record(1, siteId, run1, fullCoverage(run1), observedAt);

        // A finding of the same site that run 1 never observed — accepting a baseline is a
        // statement about what that run saw, so it must stay untouched.
        String orphanFp = insertOrphanFinding();

        // Pre-dispose the fourth run-1 finding as WONT_FIX with its own reason.
        String wontFixFp = Fingerprint.of(siteId, CheckType.DEAD_LINK, "dead:/w", "/w");
        jdbc.update("UPDATE finding SET triage_status = 'WONT_FIX', triage_reason = ? WHERE fingerprint = ?",
                "Eigenes Risiko.", wontFixFp);

        int moved = service.acceptBaseline(siteId, 1);

        // Group 1: the three UNTRIAGED findings move to ACKNOWLEDGED with a plain-German reason.
        assertThat(moved).isEqualTo(3);
        for (String fp : List.of(
                Fingerprint.of(siteId, CheckType.DEAD_LINK, "dead:/a", "/a"),
                Fingerprint.of(siteId, CheckType.DEAD_LINK, "dead:/b", "/b"),
                Fingerprint.of(siteId, CheckType.DEAD_LINK, "dead:/c", "/c"))) {
            assertThat(triageStatus(fp)).isEqualTo(TriageStatus.ACKNOWLEDGED);
            String reason = triageReason(fp);
            assertThat(reason).isNotBlank();
            assertNoInternalIdentifier(reason);
            assertThat(triagedAt(fp)).isNotNull();
        }

        // Group 2: the WONT_FIX finding keeps its status and its own reason.
        assertThat(triageStatus(wontFixFp)).isEqualTo(TriageStatus.WONT_FIX);
        assertThat(triageReason(wontFixFp)).isEqualTo("Eigenes Risiko.");

        // Group 3: the orphan (no occurrence in run 1) is untouched.
        assertThat(triageStatus(orphanFp)).isEqualTo(TriageStatus.UNTRIAGED);
        assertThat(triageReason(orphanFp)).isNull();

        // Group 4: a second call is a no-op.
        int second = service.acceptBaseline(siteId, 1);
        assertThat(second).isZero();
        assertThat(triageStatus(Fingerprint.of(siteId, CheckType.DEAD_LINK, "dead:/a", "/a")))
                .isEqualTo(TriageStatus.ACKNOWLEDGED);

        // Group 5: the next run's diff lists them under KNOWN, not STILL_OPEN — the whole point.
        RunDiff run2 = service.record(2, siteId, threeFindings(), fullCoverage(threeFindings()), observedAt);
        assertThat(run2.count(ReportSection.KNOWN)).isEqualTo(3);
        assertThat(run2.count(ReportSection.STILL_OPEN)).isZero();
    }

    private void assertNoInternalIdentifier(String reason) {
        // Spec 13.1: a colleague reads the reason, so no check type name or run/site id leaks.
        for (CheckType type : CheckType.values()) {
            assertThat(reason).doesNotContain(type.name());
        }
        assertThat(reason).doesNotContainPattern("\\d");
    }

    private List<CheckFinding> threeFindings() {
        return List.of(
                new CheckFinding(CheckType.DEAD_LINK, Severity.ERROR, "dead:/a", page("/a"), "m", List.of(), Evidence.NONE),
                new CheckFinding(CheckType.DEAD_LINK, Severity.ERROR, "dead:/b", page("/b"), "m", List.of(), Evidence.NONE),
                new CheckFinding(CheckType.DEAD_LINK, Severity.ERROR, "dead:/c", page("/c"), "m", List.of(), Evidence.NONE));
    }

    private CheckFinding wontFixFinding() {
        return new CheckFinding(CheckType.DEAD_LINK, Severity.ERROR, "dead:/w", page("/w"), "m", List.of(), Evidence.NONE);
    }

    private String insertOrphanFinding() {
        String fp = Fingerprint.of(siteId, CheckType.DEAD_LINK, "dead:/orphan", "/orphan");
        long id = jdbc.queryForObject("""
                        INSERT INTO finding (site_id, fingerprint, check_type, subject_key, location_key, severity,
                            message_key, message_args, evidence, observed_status, triage_status,
                            first_seen_run, last_seen_run, page_count, first_seen_at, last_seen_at)
                        VALUES (?, ?, 'DEAD_LINK', ?, ?, 'ERROR', 'm', '[]'::jsonb, NULL, 'ACTIVE', 'UNTRIAGED', ?, ?, 1, ?, ?)
                        RETURNING id
                        """, Long.class, siteId, fp, "/orphan", "/orphan", 0L, 0L,
                Timestamp.from(observedAt), Timestamp.from(observedAt));
        jdbc.update("""
                        INSERT INTO finding_occurrence (finding_id, run_id, page_url, severity, message_key,
                            message_args, evidence, observed_at)
                        VALUES (?, 7, ?, 'ERROR', 'm', '[]'::jsonb, NULL, ?)
                        """, id, "/orphan", Timestamp.from(observedAt));
        return fp;
    }

    private NormalizedUrl page(String path) {
        return new NormalizedUrl("https", "www.example.com", 443, path, null);
    }

    private RunCoverage fullCoverage(List<CheckFinding> findings) {
        List<String> urls = findings.stream()
                .map(f -> "https://www.example.com" + f.locationKey()).toList();
        return RunCoverage.of(RunScope.FULL.checkTypes().stream().map(CheckType::name).toList(),
                urls, List.of(), false);
    }

    private TriageStatus triageStatus(String fp) {
        return TriageStatus.valueOf(jdbc.queryForObject(
                "SELECT triage_status FROM finding WHERE fingerprint = ?", String.class, fp));
    }

    private String triageReason(String fp) {
        return jdbc.queryForObject("SELECT triage_reason FROM finding WHERE fingerprint = ?", String.class, fp);
    }

    private Instant triagedAt(String fp) {
        Timestamp ts = jdbc.queryForObject("SELECT triaged_at FROM finding WHERE fingerprint = ?", Timestamp.class, fp);
        return ts == null ? null : ts.toInstant();
    }
}
