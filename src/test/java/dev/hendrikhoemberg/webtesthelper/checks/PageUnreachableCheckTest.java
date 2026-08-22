package dev.hendrikhoemberg.webtesthelper.checks;

import dev.hendrikhoemberg.webtesthelper.model.CheckFinding;
import dev.hendrikhoemberg.webtesthelper.model.CheckType;
import dev.hendrikhoemberg.webtesthelper.model.Severity;
import dev.hendrikhoemberg.webtesthelper.support.Snapshots;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PageUnreachableCheckTest {

    private final PageUnreachableCheck check = new PageUnreachableCheck();

    @Test
    void aPageThatTimedOutIsReported() {
        List<CheckFinding> findings = check.evaluate(
                Snapshots.page("https://example.com/langsam").unreachable("Timeout 30000ms exceeded"),
                Snapshots.config(check, Snapshots.facts()));

        assertThat(findings).singleElement().satisfies(finding -> {
            assertThat(finding.type()).isEqualTo(CheckType.PAGE_UNREACHABLE);
            assertThat(finding.severity()).isEqualTo(Severity.ERROR);
            assertThat(finding.subjectKey()).isEqualTo("https://example.com/langsam");
            assertThat(finding.locationKey()).isEqualTo("/langsam");
            assertThat(finding.messageArgs()).containsExactly("Timeout 30000ms exceeded");
            assertThat(check.messageKeys()).contains(finding.messageKey());
        });
    }

    @Test
    void aReachablePageIsNotReported() {
        assertThat(check.evaluate(Snapshots.page("https://example.com/").build(),
                Snapshots.config(check, Snapshots.facts()))).isEmpty();
    }

    @Test
    void aRedirectLoopIsLeftToTheRedirectCheckSoOneBrokenPageYieldsOneFinding() {
        // Measured: Chromium fails a redirect loop with net::ERR_TOO_MANY_REDIRECTS, so the page
        // arrives here as unreachable. Reporting it under two names is the noise spec 8 exists
        // to prevent — REDIRECT_CHAIN owns it because it can say what is actually wrong.
        assertThat(check.evaluate(
                Snapshots.page("https://example.com/schleife")
                        .unreachable("net::ERR_TOO_MANY_REDIRECTS at https://example.com/schleife"),
                Snapshots.config(check, Snapshots.facts()))).isEmpty();
    }

    @Test
    void theRawBrowserErrorIsKeptAsEvidenceRatherThanShownAsProse() {
        CheckFinding finding = check.evaluate(
                Snapshots.page("https://example.com/x").unreachable("Timeout 30000ms\n  at Frame.goto"),
                Snapshots.config(check, Snapshots.facts())).getFirst();

        assertThat(finding.messageArgs()).containsExactly("Timeout 30000ms");   // first line only
        assertThat(finding.evidence().responseDetail()).contains("at Frame.goto");
    }
}