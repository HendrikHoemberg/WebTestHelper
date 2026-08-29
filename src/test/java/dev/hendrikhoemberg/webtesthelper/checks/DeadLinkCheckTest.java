package dev.hendrikhoemberg.webtesthelper.checks;

import dev.hendrikhoemberg.webtesthelper.model.CheckFinding;
import dev.hendrikhoemberg.webtesthelper.model.UrlStatus;
import dev.hendrikhoemberg.webtesthelper.model.UrlVerification;
import dev.hendrikhoemberg.webtesthelper.support.Snapshots;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DeadLinkCheckTest {

    private final DeadLinkCheck check = new DeadLinkCheck();

    @Test
    void aDeadLinkProducesOneFinding() {
        UrlVerification dead = new UrlVerification("https://example.com/tot",
                UrlStatus.DEAD, 404, "text/html", 0, null, "Not Found", Instant.EPOCH);

        CheckFinding finding = check.evaluate(
                Snapshots.page("https://example.com/seite")
                        .link("https://example.com/tot", true).build(),
                Snapshots.config(check, Snapshots.facts(dead))).getFirst();

        assertThat(finding.messageKey()).isEqualTo("finding.DEAD_LINK.dead");
        assertThat(finding.subjectKey()).isEqualTo("https://example.com/tot");
        assertThat(finding.observedOn()).isEqualTo(Snapshots.url("https://example.com/seite"));
        assertThat(finding.severity()).isEqualTo(check.defaultSeverity());
        assertThat(finding.messageArgs()).containsExactly("https://example.com/tot", "404");
    }

    @Test
    void anUnverifiableLinkProducesUnverifiableAtInfo() {
        UrlVerification unverifiable = new UrlVerification("https://example.com/fremd",
                UrlStatus.UNVERIFIABLE, 403, null, 0, null, "Forbidden", Instant.EPOCH);

        CheckFinding finding = check.evaluate(
                Snapshots.page("https://example.com/seite")
                        .link("https://example.com/fremd", false).build(),
                Snapshots.config(check, Snapshots.facts(unverifiable))).getFirst();

        assertThat(finding.messageKey()).isEqualTo("finding.DEAD_LINK.unverifiable");
        assertThat(finding.severity()).isEqualTo(dev.hendrikhoemberg.webtesthelper.model.Severity.INFO);
    }

    @Test
    void anUnreachablePageProducesNoFindings() {
        assertThat(check.evaluate(
                Snapshots.page("https://example.com/seite")
                        .unreachable("Timeout"),
                Snapshots.config(check, Snapshots.facts()))).isEmpty();
    }

    @Test
    void anOkLinkProducesNoFindings() {
        UrlVerification ok = new UrlVerification("https://example.com/ziel",
                UrlStatus.OK, 200, "text/html", 1024, null, null, Instant.EPOCH);

        assertThat(check.evaluate(
                Snapshots.page("https://example.com/seite")
                        .link("https://example.com/ziel", true).build(),
                Snapshots.config(check, Snapshots.facts(ok)))).isEmpty();
    }

    @Test
    void aLinkWithNoVerificationEntryProducesNoFindings() {
        assertThat(check.evaluate(
                Snapshots.page("https://example.com/seite")
                        .link("https://example.com/ziel", true).build(),
                Snapshots.config(check, Snapshots.facts()))).isEmpty();
    }

    @Test
    void twoLinksToTheSameTargetOnOnePageProduceOneFinding() {
        UrlVerification dead = new UrlVerification("https://example.com/tot",
                UrlStatus.DEAD, 404, "text/html", 0, null, "Not Found", Instant.EPOCH);

        List<CheckFinding> findings = check.evaluate(
                Snapshots.page("https://example.com/seite")
                        .link("https://example.com/tot", true)
                        .link("https://example.com/tot", true).build(),
                Snapshots.config(check, Snapshots.facts(dead)));

        assertThat(findings).hasSize(1);
    }

    @Test
    void aDeadFrameSrcProducesAFinding() {
        UrlVerification dead = new UrlVerification("https://example.com/frame",
                UrlStatus.DEAD, 404, "text/html", 0, null, "Not Found", Instant.EPOCH);

        CheckFinding finding = check.evaluate(
                Snapshots.page("https://example.com/seite")
                        .frame("https://example.com/frame", true, 0).build(),
                Snapshots.config(check, Snapshots.facts(dead))).getFirst();

        assertThat(finding.messageKey()).isEqualTo("finding.DEAD_LINK.dead");
        assertThat(finding.subjectKey()).isEqualTo("https://example.com/frame");
    }

    @Test
    void theSameInputTwiceYieldsTheSameFindingsInTheSameOrder() {
        UrlVerification dead = new UrlVerification("https://example.com/tot",
                UrlStatus.DEAD, 404, "text/html", 0, null, "Not Found", Instant.EPOCH);

        var snapshot = Snapshots.page("https://example.com/seite")
                .link("https://example.com/tot", true).build();
        var config = Snapshots.config(check, Snapshots.facts(dead));

        assertThat(check.evaluate(snapshot, config)).isEqualTo(check.evaluate(snapshot, config));
    }

    @Test
    void aDeadLinkWithZeroHttpStatusUsesFailureTextAsArg() {
        UrlVerification dead = new UrlVerification("https://example.com/tot",
                UrlStatus.DEAD, 0, null, 0, null, "Connection refused", Instant.EPOCH);

        CheckFinding finding = check.evaluate(
                Snapshots.page("https://example.com/seite")
                        .link("https://example.com/tot", true).build(),
                Snapshots.config(check, Snapshots.facts(dead))).getFirst();

        assertThat(finding.messageArgs()).containsExactly("https://example.com/tot",
                "Connection refused");
    }

    @Test
    void aDeadLinkCarriesTheRequestAndResponseAsEvidence() {
        UrlVerification dead = new UrlVerification("https://example.com/tot",
                UrlStatus.DEAD, 404, "text/html", 0, null, "Not Found", Instant.EPOCH,
                "HEAD https://example.com/tot\nUser-Agent: WebTestHelper/1.0\n",
                "404\ncontent-type: text/html\ncontent-length: 0\n");

        CheckFinding finding = check.evaluate(
                Snapshots.page("https://example.com/seite")
                        .link("https://example.com/tot", true).build(),
                Snapshots.config(check, Snapshots.facts(dead))).getFirst();

        assertThat(finding.evidence().httpStatus()).isEqualTo(404);
        assertThat(finding.evidence().requestDetail()).contains("HEAD https://example.com/tot");
        assertThat(finding.evidence().responseDetail()).contains("404");
        assertThat(finding.evidence().responseDetail()).contains("content-type: text/html");
    }

    @Test
    void aDeadLinkWithNoHttpResponseLeavesNoRequestOrResponseDetail() {
        UrlVerification dead = new UrlVerification("https://example.com/tot",
                UrlStatus.DEAD, 0, null, 0, null, "Connection refused", Instant.EPOCH);

        CheckFinding finding = check.evaluate(
                Snapshots.page("https://example.com/seite")
                        .link("https://example.com/tot", true).build(),
                Snapshots.config(check, Snapshots.facts(dead))).getFirst();

        assertThat(finding.evidence().httpStatus()).isNull();
        assertThat(finding.evidence().requestDetail()).isNull();
        assertThat(finding.evidence().responseDetail()).isNull();
    }
}
