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
import dev.hendrikhoemberg.webtesthelper.model.SiteContext;
import dev.hendrikhoemberg.webtesthelper.model.SoftNotFoundProbe;
import dev.hendrikhoemberg.webtesthelper.model.UrlStatus;
import dev.hendrikhoemberg.webtesthelper.model.UrlVerification;
import dev.hendrikhoemberg.webtesthelper.model.UrlVerifications;
import dev.hendrikhoemberg.webtesthelper.support.Snapshots;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class HreflangCheckTest {

    private final HreflangCheck check = new HreflangCheck();

    private static SiteContext site() {
        return new SiteContext(1L, "Beispiel", Snapshots.url("https://example.com/"),
                CrawlBudget.DEFAULT, List.of(), List.of(), List.of(), true, null, Map.of());
    }

    private static RunSnapshots snapshots(PageSnapshot... pages) {
        return new RunSnapshots(1L, site(), List.of(pages), SoftNotFoundProbe.NONE);
    }

    private static CheckConfig config(UrlVerification... verifications) {
        return new CheckConfig(Severity.WARN, Map.of(),
                new RunFacts(1L, RunScope.FULL, Instant.EPOCH, SoftNotFoundProbe.NONE,
                        UrlVerifications.of(List.of(verifications)), TlsNone(), List.of()));
    }

    private static dev.hendrikhoemberg.webtesthelper.model.TlsCertificateFact TlsNone() {
        return dev.hendrikhoemberg.webtesthelper.model.TlsCertificateFact.NONE;
    }

    @Test
    void anInvalidHreflangValueIsReportedOnThePageThatDeclaresIt() {
        PageSnapshot page = Snapshots.page("https://example.com/a")
                .alternate("deutsch", "https://example.com/b").build();

        assertThat(check.evaluate(snapshots(page), site(), config()))
                .singleElement()
                .satisfies(finding -> {
                    assertThat(finding.messageKey()).isEqualTo("finding.HREFLANG.invalidLanguage");
                    assertThat(finding.subjectKey()).isEqualTo("https://example.com/a");
                    assertThat(finding.messageArgs()).containsExactly("deutsch",
                            "https://example.com/a");
                    assertThat(finding.observedOn()).isEqualTo(Snapshots.url("https://example.com/a"));
                });
    }

    @Test
    void anAlternateWhoseTargetIsNotTwoHundredIsReportedDead() {
        PageSnapshot page = Snapshots.page("https://example.com/a")
                .alternate("en", "https://example.com/b").build();
        PageSnapshot target = Snapshots.page("https://example.com/b").status(500).build();

        assertThat(check.evaluate(snapshots(page, target), site(), config()))
                .singleElement()
                .satisfies(finding -> {
                    assertThat(finding.messageKey()).isEqualTo("finding.HREFLANG.deadAlternate");
                    assertThat(finding.subjectKey()).isEqualTo("https://example.com/b");
                    assertThat(finding.messageArgs()).containsExactly("https://example.com/b", "en");
                });
    }

    @Test
    void anAlternateWhoseTargetVerificationIsDeadIsReportedDead() {
        PageSnapshot page = Snapshots.page("https://example.com/a")
                .alternate("en", "https://example.com/b").build();
        UrlVerification dead = new UrlVerification("https://example.com/b", UrlStatus.DEAD, 0,
                null, 0, null, "tot", Instant.now());

        assertThat(check.evaluate(snapshots(page), site(), config(dead)))
                .singleElement()
                .satisfies(finding -> assertThat(finding.messageKey())
                        .isEqualTo("finding.HREFLANG.deadAlternate"));
    }

    @Test
    void aDeadAlternateCarriesTheVerificationAsEvidence() {
        PageSnapshot page = Snapshots.page("https://example.com/a")
                .alternate("en", "https://example.com/b").build();
        UrlVerification dead = new UrlVerification("https://example.com/b", UrlStatus.DEAD, 404,
                "text/html", 0, null, "Not Found", Instant.now(),
                "HEAD https://example.com/b\nUser-Agent: WebTestHelper/1.0\n",
                "404\ncontent-type: text/html\n");

        assertThat(check.evaluate(snapshots(page), site(), config(dead)))
                .singleElement()
                .satisfies(finding -> {
                    assertThat(finding.messageKey()).isEqualTo("finding.HREFLANG.deadAlternate");
                    assertThat(finding.evidence().httpStatus()).isEqualTo(404);
                    assertThat(finding.evidence().requestDetail())
                            .contains("HEAD https://example.com/b");
                    assertThat(finding.evidence().responseDetail()).contains("404");
                });
    }

    @Test
    void anAlternateWhoseTargetDoesNotLinkBackIsReported() {
        PageSnapshot page = Snapshots.page("https://example.com/a")
                .alternate("en", "https://example.com/b").build();
        PageSnapshot target = Snapshots.page("https://example.com/b").build();

        assertThat(check.evaluate(snapshots(page, target), site(), config()))
                .singleElement()
                .satisfies(finding -> {
                    assertThat(finding.messageKey()).isEqualTo("finding.HREFLANG.notReciprocated");
                    assertThat(finding.subjectKey()).isEqualTo("https://example.com/b");
                    assertThat(finding.messageArgs()).containsExactly("https://example.com/a",
                            "https://example.com/b");
                });
    }

    @Test
    void aCorrectlyReciprocatedPairReportsNothing() {
        PageSnapshot a = Snapshots.page("https://example.com/a")
                .alternate("en", "https://example.com/b").build();
        PageSnapshot b = Snapshots.page("https://example.com/b")
                .alternate("de", "https://example.com/a").build();

        assertThat(check.evaluate(snapshots(a, b), site(), config())).isEmpty();
    }

    @Test
    void aSelfReferencingAlternateReportsNothing() {
        PageSnapshot a = Snapshots.page("https://example.com/a")
                .alternate("en", "https://example.com/a").build();

        assertThat(check.evaluate(snapshots(a), site(), config())).isEmpty();
    }

    @Test
    void anAlternateToAnUncrawledTargetWithNoVerificationReportsNothing() {
        PageSnapshot a = Snapshots.page("https://example.com/a")
                .alternate("en", "https://example.com/b").build();

        assertThat(check.evaluate(snapshots(a), site(), config())).isEmpty();
    }

    @Test
    void anAlternateWhoseTargetFailedTransientlyIsNotReportedDead() {
        PageSnapshot page = Snapshots.page("https://example.com/a")
                .alternate("en", "https://example.com/b").build();
        PageSnapshot target = Snapshots.page("https://example.com/b")
                .unreachable("net::ERR_NETWORK_CHANGED at https://example.com/b");

        assertThat(check.evaluate(snapshots(page, target), site(), config())).isEmpty();
    }

    @Test
    void anAlternateWhoseTargetFailedForAPageReasonIsReportedDead() {
        PageSnapshot page = Snapshots.page("https://example.com/a")
                .alternate("en", "https://example.com/b").build();
        PageSnapshot target = Snapshots.page("https://example.com/b")
                .unreachable("net::ERR_TOO_MANY_REDIRECTS at https://example.com/b");

        assertThat(check.evaluate(snapshots(page, target), site(), config()))
                .singleElement()
                .satisfies(finding -> assertThat(finding.messageKey())
                        .isEqualTo("finding.HREFLANG.deadAlternate"));
    }

    @Test
    void aPageWithoutAlternatesReportsNothing() {
        PageSnapshot a = Snapshots.page("https://example.com/a").build();

        assertThat(check.evaluate(snapshots(a), site(), config())).isEmpty();
    }

    @Test
    void xDefaultIsExemptFromReciprocationButStillCheckedForLife() {
        PageSnapshot page = Snapshots.page("https://example.com/a")
                .alternate("x-default", "https://example.com/b").build();
        PageSnapshot deadTarget = Snapshots.page("https://example.com/b").status(404).build();

        assertThat(check.evaluate(snapshots(page, deadTarget), site(), config()))
                .singleElement()
                .satisfies(finding -> assertThat(finding.messageKey())
                        .isEqualTo("finding.HREFLANG.deadAlternate"));
    }

    @Test
    void xDefaultNotReciprocatedButAliveReportsNothing() {
        PageSnapshot page = Snapshots.page("https://example.com/a")
                .alternate("x-default", "https://example.com/b").build();
        PageSnapshot target = Snapshots.page("https://example.com/b").build();

        assertThat(check.evaluate(snapshots(page, target), site(), config())).isEmpty();
    }
}
