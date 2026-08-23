package dev.hendrikhoemberg.webtesthelper.findings;

import dev.hendrikhoemberg.webtesthelper.catalog.SiteService;
import dev.hendrikhoemberg.webtesthelper.model.CheckType;
import dev.hendrikhoemberg.webtesthelper.model.Evidence;
import dev.hendrikhoemberg.webtesthelper.model.ObservedStatus;
import dev.hendrikhoemberg.webtesthelper.model.RunCoverage;
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
class FindingStoreTest extends AbstractPostgresTest {

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
                java.time.Duration.ofMinutes(10), List.of(), List.of(), true, null));
        observedAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
    }

    @Test
    void upsertReturnsIdsAndReUpsertKeepsFirstSeen() {
        MaterialisedFinding a = finding(CheckType.DEAD_LINK, "s1", "/a", Evidence.NONE, List.of());
        MaterialisedFinding b = finding(CheckType.DEAD_LINK, "s2", "/b", Evidence.NONE, List.of());

        List<Long> ids = store.upsertAll(siteId, 1, List.of(a, b), observedAt);
        assertThat(ids).hasSize(2);
        assertThat(ids.get(0)).isNotEqualTo(ids.get(1));

        Instant second = observedAt.plusSeconds(60);
        List<Long> again = store.upsertAll(siteId, 2, List.of(a), second);
        assertThat(again).containsExactly(ids.get(0));

        assertThat(firstSeenRun(a.fingerprint())).isEqualTo(1);
        assertThat(firstSeenAt(a.fingerprint())).isEqualTo(observedAt);
    }

    @Test
    void reUpsertDoesNotEraseAnAcknowledgement() {
        MaterialisedFinding a = finding(CheckType.DEAD_LINK, "s1", "/a", Evidence.NONE, List.of());
        store.upsertAll(siteId, 1, List.of(a), observedAt);
        jdbc.update("UPDATE finding SET triage_status = 'ACKNOWLEDGED', triaged_at = ? WHERE fingerprint = ?",
                java.sql.Timestamp.from(observedAt), a.fingerprint());

        store.upsertAll(siteId, 2, List.of(a), observedAt.plusSeconds(60));

        assertThat(triageStatus(a.fingerprint())).isEqualTo("ACKNOWLEDGED");
        assertThat(observedStatus(a.fingerprint())).isEqualTo(ObservedStatus.ACTIVE);
    }

    @Test
    void evidenceAndMessageArgsSurviveJsonbRoundTrip() {
        Evidence evidence = new Evidence("/screens/shot.png", 404, null, null, List.of("console line 1", "console line 2"));
        MaterialisedFinding a = finding(CheckType.PAGE_STATUS, "s1", "/a", evidence, List.of("arg-one", "arg-two"));

        store.upsertAll(siteId, 1, List.of(a), observedAt);

        Finding stored = store.diffOf(siteId, 1).of(ReportSection.NEW).get(0);
        assertThat(stored.evidence()).isEqualTo(evidence);
        assertThat(stored.messageArgs()).containsExactly("arg-one", "arg-two");
    }

    @Test
    void recountMatchesRowCountAndTheWholeSequenceIsIdempotent() {
        MaterialisedFinding a = finding(CheckType.DEAD_LINK, "s1", "/a", Evidence.NONE, List.of(),
                List.of("/p1", "/p2", "/p3"));

        long id = store.upsertAll(siteId, 1, List.of(a), observedAt).get(0);
        store.insertOccurrences(List.of(id), 1, List.of(a), observedAt);
        store.recountOccurrences(List.of(id));
        assertThat(occurrenceCount(a.fingerprint())).isEqualTo(3);
        assertThat(occurrenceRows(a.fingerprint())).isEqualTo(3);

        store.upsertAll(siteId, 1, List.of(a), observedAt);
        store.insertOccurrences(List.of(id), 1, List.of(a), observedAt);
        store.recountOccurrences(List.of(id));
        assertThat(occurrenceCount(a.fingerprint())).isEqualTo(3);
        assertThat(occurrenceRows(a.fingerprint())).isEqualTo(3);
    }

    @Test
    void resolveOutsideRunRespectsCoverageAxes() {
        MaterialisedFinding covered = finding(CheckType.DEAD_LINK, "s-cov", "/p1", Evidence.NONE, List.of());
        MaterialisedFinding wrongType = finding(CheckType.FILE_DOWNLOAD, "s-type", "/p1", Evidence.NONE, List.of());
        MaterialisedFinding wrongLocation = finding(CheckType.DEAD_LINK, "s-loc", "/p2", Evidence.NONE, List.of());
        MaterialisedFinding siteWide = finding(CheckType.DEAD_LINK, "s-star", "*", Evidence.NONE, List.of());

        store.upsertAll(siteId, 1, List.of(covered, wrongType, wrongLocation, siteWide), observedAt);

        RunCoverage partial = RunCoverage.of(
                List.of(CheckType.DEAD_LINK.name()),
                List.of("https://www.example.com/p1"), List.of(), true);
        int resolvedPartial = store.resolveOutsideRun(siteId, 2, partial);
        assertThat(resolvedPartial).isEqualTo(1);
        assertThat(observedStatus(covered.fingerprint())).isEqualTo(ObservedStatus.RESOLVED);
        assertThat(resolvedAtRun(covered.fingerprint())).isEqualTo(2);
        assertThat(observedStatus(wrongType.fingerprint())).isEqualTo(ObservedStatus.ACTIVE);
        assertThat(observedStatus(wrongLocation.fingerprint())).isEqualTo(ObservedStatus.ACTIVE);
        assertThat(observedStatus(siteWide.fingerprint())).isEqualTo(ObservedStatus.ACTIVE);

        RunCoverage complete = RunCoverage.of(
                List.of(CheckType.DEAD_LINK.name()),
                List.of("https://www.example.com/p1"), List.of(), false);
        assertThat(store.resolveOutsideRun(siteId, 3, complete)).isEqualTo(1);
        assertThat(observedStatus(siteWide.fingerprint())).isEqualTo(ObservedStatus.RESOLVED);
        assertThat(resolvedAtRun(siteWide.fingerprint())).isEqualTo(3);
    }

    private MaterialisedFinding finding(CheckType type, String subject, String location, Evidence evidence,
            List<String> messageArgs) {
        java.util.List<String> pages = new java.util.ArrayList<>();
        if (location.equals("*")) {
            pages.add(null);
        } else {
            pages.add(location);
        }
        return finding(type, subject, location, evidence, messageArgs, pages);
    }

    private MaterialisedFinding finding(CheckType type, String subject, String location, Evidence evidence,
            List<String> messageArgs, List<String> pageUrls) {
        List<FindingOccurrence> occurrences = pageUrls.stream()
                .map(p -> new FindingOccurrence(p, Severity.ERROR, "m", messageArgs, evidence))
                .toList();
        return new MaterialisedFinding(Fingerprint.of(siteId, type, subject, location), type, Severity.ERROR,
                subject, location, "m", messageArgs, evidence, occurrences);
    }

    private long firstSeenRun(String fp) {
        return jdbc.queryForObject("SELECT first_seen_run FROM finding WHERE fingerprint = ?", Long.class, fp);
    }

    private Instant firstSeenAt(String fp) {
        return jdbc.queryForObject("SELECT first_seen_at FROM finding WHERE fingerprint = ?",
                java.sql.Timestamp.class, fp).toInstant();
    }

    private String triageStatus(String fp) {
        return jdbc.queryForObject("SELECT triage_status FROM finding WHERE fingerprint = ?", String.class, fp);
    }

    private ObservedStatus observedStatus(String fp) {
        return ObservedStatus.valueOf(jdbc.queryForObject(
                "SELECT observed_status FROM finding WHERE fingerprint = ?", String.class, fp));
    }

    private Long resolvedAtRun(String fp) {
        Long r = jdbc.queryForObject("SELECT resolved_at_run FROM finding WHERE fingerprint = ?", Long.class, fp);
        return r;
    }

    private int occurrenceCount(String fp) {
        return jdbc.queryForObject("SELECT occurrence_count FROM finding WHERE fingerprint = ?", Integer.class, fp);
    }

    private int occurrenceRows(String fp) {
        return jdbc.queryForObject("""
                SELECT count(*) FROM finding_occurrence fo JOIN finding f ON f.id = fo.finding_id
                 WHERE f.fingerprint = ?""", Integer.class, fp);
    }
}
