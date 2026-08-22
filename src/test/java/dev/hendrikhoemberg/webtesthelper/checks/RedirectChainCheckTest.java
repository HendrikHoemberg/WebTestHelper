package dev.hendrikhoemberg.webtesthelper.checks;

import dev.hendrikhoemberg.webtesthelper.model.CheckFinding;
import dev.hendrikhoemberg.webtesthelper.support.Snapshots;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class RedirectChainCheckTest {

    private final RedirectChainCheck check = new RedirectChainCheck();

    @Test
    void aChainWithinTheHopLimitIsNotReported() {
        // Measured against the fixture: /weiter/1 takes exactly three hops, which is also the
        // default limit. http to https to www to page is three legitimate hops on a real site.
        assertThat(check.evaluate(
                Snapshots.page("https://example.com/ziel")
                        .redirectChain("https://example.com/w1", "https://example.com/w2",
                                "https://example.com/w3", "https://example.com/ziel").build(),
                Snapshots.config(check, Snapshots.facts()))).isEmpty();
    }

    @Test
    void aLongerChainIsReportedWithItsHopCountAndDestination() {
        CheckFinding finding = check.evaluate(
                Snapshots.page("https://example.com/ziel")
                        .redirectChain("https://example.com/a", "https://example.com/b",
                                "https://example.com/c", "https://example.com/d",
                                "https://example.com/ziel").build(),
                Snapshots.config(check, Snapshots.facts())).getFirst();

        assertThat(finding.messageKey()).isEqualTo("finding.REDIRECT_CHAIN.tooManyHops");
        assertThat(finding.messageArgs()).containsExactly("4", "https://example.com/ziel");
        assertThat(finding.subjectKey()).isEqualTo("https://example.com/a");   // the entry point
        assertThat(check.messageKeys()).contains(finding.messageKey());
    }

    @Test
    void theHopLimitIsOverridablePerSite() {
        assertThat(check.evaluate(
                Snapshots.page("https://example.com/ziel")
                        .redirectChain("https://example.com/w1", "https://example.com/w2",
                                "https://example.com/w3", "https://example.com/ziel").build(),
                Snapshots.config(check, Snapshots.facts(), Map.of("maxHops", 2)))).hasSize(1);
    }

    @Test
    void aPageThatFailedWithARedirectLoopIsReportedAsALoop() {
        // Measured: this is how a loop actually arrives — Chromium refuses to finish the
        // navigation, so there is no chain to inspect, only the error.
        CheckFinding finding = check.evaluate(
                Snapshots.page("https://example.com/schleife")
                        .unreachable("net::ERR_TOO_MANY_REDIRECTS at https://example.com/schleife"),
                Snapshots.config(check, Snapshots.facts())).getFirst();

        assertThat(finding.messageKey()).isEqualTo("finding.REDIRECT_CHAIN.loop");
        assertThat(finding.messageArgs()).isEmpty();
    }

    @Test
    void aChainThatVisitsTheSameUrlTwiceIsALoopEvenIfTheBrowserEscapedIt() {
        CheckFinding finding = check.evaluate(
                Snapshots.page("https://example.com/ende")
                        .redirectChain("https://example.com/a", "https://example.com/b",
                                "https://example.com/a", "https://example.com/ende").build(),
                Snapshots.config(check, Snapshots.facts())).getFirst();

        assertThat(finding.messageKey()).isEqualTo("finding.REDIRECT_CHAIN.loop");
    }

    @Test
    void aPageReachedWithoutAnyRedirectIsNotReported() {
        assertThat(check.evaluate(Snapshots.page("https://example.com/").build(),
                Snapshots.config(check, Snapshots.facts()))).isEmpty();
    }

    @Test
    void anUnreachablePageWithSomeOtherReasonIsNotReported() {
        assertThat(check.evaluate(Snapshots.page("https://example.com/x").unreachable("Timeout"),
                Snapshots.config(check, Snapshots.facts()))).isEmpty();
    }
}