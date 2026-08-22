package dev.hendrikhoemberg.webtesthelper.checks;

import dev.hendrikhoemberg.webtesthelper.checks.CheckConfig;
import dev.hendrikhoemberg.webtesthelper.model.CheckFinding;
import dev.hendrikhoemberg.webtesthelper.model.CheckType;
import dev.hendrikhoemberg.webtesthelper.model.CrawlBudget;
import dev.hendrikhoemberg.webtesthelper.model.PageSnapshot;
import dev.hendrikhoemberg.webtesthelper.model.RunFacts;
import dev.hendrikhoemberg.webtesthelper.model.RunScope;
import dev.hendrikhoemberg.webtesthelper.model.RunSnapshots;
import dev.hendrikhoemberg.webtesthelper.model.Severity;
import dev.hendrikhoemberg.webtesthelper.model.SimHash;
import dev.hendrikhoemberg.webtesthelper.model.SiteContext;
import dev.hendrikhoemberg.webtesthelper.model.SoftNotFoundProbe;
import dev.hendrikhoemberg.webtesthelper.model.TlsCertificateFact;
import dev.hendrikhoemberg.webtesthelper.model.UrlStatus;
import dev.hendrikhoemberg.webtesthelper.model.UrlVerification;
import dev.hendrikhoemberg.webtesthelper.model.UrlVerifications;
import dev.hendrikhoemberg.webtesthelper.support.Snapshots;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SitemapConsistencyCheckTest {

    private final SitemapConsistencyCheck check = new SitemapConsistencyCheck();

    private static SiteContext site() {
        return new SiteContext(1L, "Beispiel", Snapshots.url("https://example.com/"),
                CrawlBudget.DEFAULT, List.of(), List.of(), List.of(), true, null, Map.of());
    }

    private static RunSnapshots snapshots(PageSnapshot... pages) {
        return new RunSnapshots(1L, site(), List.of(pages), SoftNotFoundProbe.NONE);
    }

    private static RunFacts facts(List<String> sitemap) {
        return facts(sitemap, SoftNotFoundProbe.NONE);
    }

    private static RunFacts facts(List<String> sitemap, SoftNotFoundProbe probe) {
        return new RunFacts(1L, RunScope.FULL, Instant.EPOCH, probe, UrlVerifications.EMPTY,
                TlsCertificateFact.NONE, sitemap);
    }

    private static CheckConfig config(RunFacts facts) {
        return new CheckConfig(Severity.WARN, Map.of(), facts);
    }

    @Test
    void aSiteWithoutASitemapHasNoFindings() {
        PageSnapshot page = Snapshots.page("https://example.com/a").build();

        assertThat(check.evaluate(snapshots(page), site(), config(facts(List.of()))))
                .isEmpty();
    }

    @Test
    void aSitemapEntryWhosePageIsNotTwoHundredIsReportedDead() {
        PageSnapshot target = Snapshots.page("https://example.com/b").status(500).build();

        assertThat(check.evaluate(snapshots(target), site(),
                config(facts(List.of("https://example.com/b")))))
                .singleElement()
                .satisfies(finding -> {
                    assertThat(finding.messageKey())
                            .isEqualTo("finding.SITEMAP_CONSISTENCY.deadEntry");
                    assertThat(finding.subjectKey()).isEqualTo("https://example.com/b");
                    assertThat(finding.locationKey()).isEqualTo("*");
                    assertThat(finding.messageArgs()).containsExactly("https://example.com/b");
                });
    }

    @Test
    void aSitemapEntryWhoseVerificationIsDeadIsReportedDead() {
        UrlVerification dead = new UrlVerification("https://example.com/b", UrlStatus.DEAD, 0,
                null, 0, null, "tot", Instant.now());

        RunFacts facts = new RunFacts(1L, RunScope.FULL, Instant.EPOCH, SoftNotFoundProbe.NONE,
                UrlVerifications.of(List.of(dead)), TlsCertificateFact.NONE,
                List.of("https://example.com/b"));

        assertThat(check.evaluate(snapshots(), site(), config(facts)))
                .singleElement()
                .satisfies(finding -> assertThat(finding.messageKey())
                        .isEqualTo("finding.SITEMAP_CONSISTENCY.deadEntry"));
    }

    @Test
    void aSitemapEntryThatIsASoftNotFoundReportsNothing() {
        String notFound = "Diese Seite gibt es leider nicht.";
        long hash = SimHash.of(notFound);
        SoftNotFoundProbe probe = new SoftNotFoundProbe(200, hash, notFound.length());
        PageSnapshot target = Snapshots.page("https://example.com/a").text(notFound).build();

        assertThat(check.evaluate(snapshots(target), site(),
                config(facts(List.of("https://example.com/a"), probe))))
                .isEmpty();
    }

    @Test
    void aCrawledPageMissingFromTheSitemapIsReported() {
        PageSnapshot page = Snapshots.page("https://example.com/a").build();

        assertThat(check.evaluate(snapshots(page), site(),
                config(facts(List.of("https://example.com/other")))))
                .singleElement()
                .satisfies(finding -> {
                    assertThat(finding.messageKey())
                            .isEqualTo("finding.SITEMAP_CONSISTENCY.missingPage");
                    assertThat(finding.subjectKey()).isEqualTo("https://example.com/a");
                    assertThat(finding.observedOn())
                            .isEqualTo(Snapshots.url("https://example.com/a"));
                });
    }

    @Test
    void aBrokenPageMissingFromTheSitemapIsNotReported() {
        PageSnapshot page = Snapshots.page("https://example.com/a").status(404).build();

        assertThat(check.evaluate(snapshots(page), site(),
                config(facts(List.of("https://example.com/other")))))
                .isEmpty();
    }

    @Test
    void trailingSlashFormsCompareEqual() {
        PageSnapshot page = Snapshots.page("https://example.com/a").build();

        assertThat(check.evaluate(snapshots(page), site(),
                config(facts(List.of("https://example.com/a/")))))
                .isEmpty();
    }
}
