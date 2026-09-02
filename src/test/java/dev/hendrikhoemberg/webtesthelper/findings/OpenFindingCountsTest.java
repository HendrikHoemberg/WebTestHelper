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
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@Transactional
class OpenFindingCountsTest extends AbstractPostgresTest {

    private static final int MAX_MUTE_DAYS = 365;

    @Autowired
    FindingService service;
    @Autowired
    SiteService sites;

    @Test
    void groupsOpenFindingsPerSiteExcludingSilencedResolvedAndEmptySites() {
        long siteA = sites.create(form("Kunde A", "https://www.example.com/"));
        long siteB = sites.create(form("Kunde B", "https://www.other.com/"));
        long siteC = sites.create(form("Kunde C", "https://www.empty.com/"));

        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);

        RunDiff diffA = service.record(1, siteA, List.of(
                        finding("/a", Severity.ERROR),
                        finding("/b", Severity.WARN),
                        finding("/c", Severity.ERROR),
                        finding("/d", Severity.INFO)),
                fullCoverage("https://www.example.com", List.of("/a", "/b", "/c", "/d")), now);
        List<Finding> newA = diffA.of(ReportSection.NEW);
        long warnId = idOf(newA, Severity.WARN, "/b");
        long mutedErrorId = idOf(newA, Severity.ERROR, "/c");
        service.triage(siteA, List.of(warnId),
                TriageAction.of(TriageStatus.ACKNOWLEDGED, "OK", null, now, MAX_MUTE_DAYS), "alice", now);
        service.triage(siteA, List.of(mutedErrorId),
                TriageAction.of(TriageStatus.MUTED, "Warten auf Relaunch", now.plus(30, ChronoUnit.DAYS),
                        now, MAX_MUTE_DAYS), "bob", now);

        service.record(1, siteB, List.of(finding("/b", Severity.ERROR)),
                fullCoverage("https://www.other.com", List.of("/b")), now);
        service.record(2, siteB, List.of(), fullCoverage("https://www.other.com", List.of("/b")), now);

        Map<Long, OpenFindingCounts> counts = service.openCountsBySite();

        assertThat(counts).containsOnlyKeys(siteA);
        assertThat(counts.get(siteA)).isEqualTo(new OpenFindingCounts(1, 1, 1, 2, 1, 0, 1));

        OpenFindingCounts a = counts.get(siteA);
        assertThat(a.untriaged()).isLessThanOrEqualTo(a.errors() + a.warnings() + a.infos());
    }

    @Test
    void untriagedCountIsASubsetOfTheOpenCountOnEveryCountedSite() {
        long siteA = sites.create(form("Kunde A", "https://www.example.com/"));
        long siteB = sites.create(form("Kunde B", "https://www.other.com/"));

        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);

        RunDiff diffA = service.record(1, siteA, List.of(
                        finding("/a", Severity.ERROR),
                        finding("/b", Severity.WARN),
                        finding("/c", Severity.ERROR),
                        finding("/d", Severity.INFO)),
                fullCoverage("https://www.example.com", List.of("/a", "/b", "/c", "/d")), now);
        service.triage(siteA, List.of(idOf(diffA.of(ReportSection.NEW), Severity.ERROR, "/c")),
                TriageAction.of(TriageStatus.MUTED, "Warten", now.plus(30, ChronoUnit.DAYS), now, MAX_MUTE_DAYS),
                "bob", now);

        service.record(1, siteB, List.of(
                        finding("/x", Severity.WARN),
                        finding("/y", Severity.WARN),
                        finding("/z", Severity.INFO)),
                fullCoverage("https://www.other.com", List.of("/x", "/y", "/z")), now);

        Map<Long, OpenFindingCounts> counts = service.openCountsBySite();

        assertThat(counts).containsOnlyKeys(siteA, siteB);
        for (OpenFindingCounts c : counts.values()) {
            assertThat(c.untriaged()).isLessThanOrEqualTo(c.errors() + c.warnings() + c.infos());
        }
    }

    private static SiteForm form(String name, String url) {
        return new SiteForm(name, url, 100, 3,
                Duration.ofMinutes(10), List.of(), List.of(), true, null, true);
    }

    private static CheckFinding finding(String path, Severity severity) {
        return new CheckFinding(CheckType.DEAD_LINK, severity, "subject:" + path,
                new NormalizedUrl("https", "www.example.com", 443, path, null),
                "finding.DEAD_LINK.dead", List.of(), Evidence.NONE);
    }

    private static long idOf(List<Finding> findings, Severity severity, String path) {
        return findings.stream()
                .filter(f -> f.severity() == severity && f.subjectKey().equals("subject:" + path))
                .findFirst().orElseThrow().id();
    }

    private static RunCoverage fullCoverage(String baseUrl, List<String> paths) {
        List<String> urls = paths.stream().map(p -> baseUrl + p).toList();
        return RunCoverage.of(
                RunScope.FULL,
                RunScope.FULL.checkTypes().stream().map(CheckType::name).toList(),
                urls, List.of(), false);
    }
}
