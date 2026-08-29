package dev.hendrikhoemberg.webtesthelper.checks;

import dev.hendrikhoemberg.webtesthelper.model.CheckFinding;
import dev.hendrikhoemberg.webtesthelper.model.UrlStatus;
import dev.hendrikhoemberg.webtesthelper.model.UrlVerification;
import dev.hendrikhoemberg.webtesthelper.support.Snapshots;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class FileDownloadCheckTest {

    private final FileDownloadCheck check = new FileDownloadCheck();

    @Test
    void aPdfThatArrivesAsPdfProducesNoFinding() {
        UrlVerification pdf = new UrlVerification("https://example.com/info.pdf",
                UrlStatus.OK, 200, "application/pdf", 4096, "%PDF-1.4", null, Instant.EPOCH);

        assertThat(check.evaluate(
                Snapshots.page("https://example.com/seite")
                        .link("https://example.com/info.pdf", false).build(),
                Snapshots.config(check, Snapshots.facts(pdf)))).isEmpty();
    }

    @Test
    void aPdfServedAsHtmlIsAWrongType() {
        UrlVerification pdf = new UrlVerification("https://example.com/info.pdf",
                UrlStatus.OK, 200, "text/html", 4096, "<html>", null, Instant.EPOCH);

        CheckFinding finding = check.evaluate(
                Snapshots.page("https://example.com/seite")
                        .link("https://example.com/info.pdf", false).build(),
                Snapshots.config(check, Snapshots.facts(pdf))).getFirst();

        assertThat(finding.messageKey()).isEqualTo("finding.FILE_DOWNLOAD.wrongType");
        assertThat(finding.subjectKey()).isEqualTo("https://example.com/info.pdf");
        assertThat(finding.severity()).isEqualTo(check.defaultSeverity());
        assertThat(finding.messageArgs()).containsExactly("https://example.com/info.pdf",
                "text/html");
    }

    @Test
    void aPdfWhoseBodyIsHtmlIsNotAPdf() {
        UrlVerification pdf = new UrlVerification("https://example.com/info.pdf",
                UrlStatus.OK, 200, "application/pdf", 4096, "<!doctype html>", null, Instant.EPOCH);

        CheckFinding finding = check.evaluate(
                Snapshots.page("https://example.com/seite")
                        .link("https://example.com/info.pdf", false).build(),
                Snapshots.config(check, Snapshots.facts(pdf))).getFirst();

        assertThat(finding.messageKey()).isEqualTo("finding.FILE_DOWNLOAD.notAPdf");
        assertThat(finding.messageArgs()).containsExactly("https://example.com/info.pdf");
    }

    @Test
    void aPdfTooSmallToHoldContentIsTooSmall() {
        UrlVerification pdf = new UrlVerification("https://example.com/info.pdf",
                UrlStatus.OK, 200, "application/pdf", 900, "%PDF", null, Instant.EPOCH);

        CheckFinding finding = check.evaluate(
                Snapshots.page("https://example.com/seite")
                        .link("https://example.com/info.pdf", false).build(),
                Snapshots.config(check, Snapshots.facts(pdf))).getFirst();

        assertThat(finding.messageKey()).isEqualTo("finding.FILE_DOWNLOAD.tooSmall");
        assertThat(finding.messageArgs()).containsExactly("https://example.com/info.pdf", "900");
    }

    @Test
    void aChunkedPdfWithNoContentLengthAndValidPrefixProducesNoFinding() {
        UrlVerification pdf = new UrlVerification("https://example.com/info.pdf",
                UrlStatus.OK, 200, "application/pdf", 0, "%PDF-1.4", null, Instant.EPOCH);

        assertThat(check.evaluate(
                Snapshots.page("https://example.com/seite")
                        .link("https://example.com/info.pdf", false).build(),
                Snapshots.config(check, Snapshots.facts(pdf)))).isEmpty();
    }

    @Test
    void aDocxServedAsHtmlIsAWrongType() {
        UrlVerification docx = new UrlVerification("https://example.com/info.docx",
                UrlStatus.OK, 200, "text/html", 4096, "<html>", null, Instant.EPOCH);

        CheckFinding finding = check.evaluate(
                Snapshots.page("https://example.com/seite")
                        .link("https://example.com/info.docx", false).build(),
                Snapshots.config(check, Snapshots.facts(docx))).getFirst();

        assertThat(finding.messageKey()).isEqualTo("finding.FILE_DOWNLOAD.wrongType");
        assertThat(finding.messageArgs()).containsExactly("https://example.com/info.docx",
                "text/html");
    }

    @Test
    void aDocxServedWithItsOfficeTypeProducesNoFinding() {
        UrlVerification docx = new UrlVerification("https://example.com/info.docx",
                UrlStatus.OK, 200,
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                4096, "PK\u0003\u0004", null, Instant.EPOCH);

        assertThat(check.evaluate(
                Snapshots.page("https://example.com/seite")
                        .link("https://example.com/info.docx", false).build(),
                Snapshots.config(check, Snapshots.facts(docx)))).isEmpty();
    }

    @Test
    void aDeadPdfProducesNoFinding() {
        UrlVerification dead = new UrlVerification("https://example.com/info.pdf",
                UrlStatus.DEAD, 404, null, 0, null, "Not Found", Instant.EPOCH);

        assertThat(check.evaluate(
                Snapshots.page("https://example.com/seite")
                        .link("https://example.com/info.pdf", false).build(),
                Snapshots.config(check, Snapshots.facts(dead)))).isEmpty();
    }

    @Test
    void aPdfWithNoVerificationEntryProducesNoFinding() {
        assertThat(check.evaluate(
                Snapshots.page("https://example.com/seite")
                        .link("https://example.com/info.pdf", false).build(),
                Snapshots.config(check, Snapshots.facts()))).isEmpty();
    }

    @Test
    void aPdfWithoutACapturedBodyProducesNoFinding() {
        UrlVerification pdf = new UrlVerification("https://example.com/info.pdf",
                UrlStatus.OK, 200, "application/pdf", 4096, null, null, Instant.EPOCH);

        assertThat(check.evaluate(
                Snapshots.page("https://example.com/seite")
                        .link("https://example.com/info.pdf", false).build(),
                Snapshots.config(check, Snapshots.facts(pdf)))).isEmpty();
    }

    @Test
    void anHtmlLinkIsOutOfScope() {
        UrlVerification html = new UrlVerification("https://example.com/info.html",
                UrlStatus.OK, 200, "text/html", 4096, "<html>", null, Instant.EPOCH);

        assertThat(check.evaluate(
                Snapshots.page("https://example.com/seite")
                        .link("https://example.com/info.html", false).build(),
                Snapshots.config(check, Snapshots.facts(html)))).isEmpty();
    }

    @Test
    void theFirstFailingRuleWinsForAWebPagePdf() {
        UrlVerification pdf = new UrlVerification("https://example.com/info.pdf",
                UrlStatus.OK, 200, "text/html", 500, "<html>", null, Instant.EPOCH);

        List<CheckFinding> findings = check.evaluate(
                Snapshots.page("https://example.com/seite")
                        .link("https://example.com/info.pdf", false).build(),
                Snapshots.config(check, Snapshots.facts(pdf)));

        assertThat(findings).hasSize(1);
        assertThat(findings.getFirst().messageKey())
                .isEqualTo("finding.FILE_DOWNLOAD.wrongType");
    }

    @Test
    void twoLinksToTheSameFileProduceOneFinding() {
        UrlVerification pdf = new UrlVerification("https://example.com/info.pdf",
                UrlStatus.OK, 200, "text/html", 4096, "<html>", null, Instant.EPOCH);

        List<CheckFinding> findings = check.evaluate(
                Snapshots.page("https://example.com/seite")
                        .link("https://example.com/info.pdf", false)
                        .link("https://example.com/info.pdf", false).build(),
                Snapshots.config(check, Snapshots.facts(pdf)));

        assertThat(findings).hasSize(1);
    }

    @Test
    void aWrongTypePdfCarriesTheRequestAndResponseAsEvidence() {
        UrlVerification pdf = new UrlVerification("https://example.com/info.pdf",
                UrlStatus.OK, 200, "text/html", 4096, "<html>", null, Instant.EPOCH,
                "GET https://example.com/info.pdf\nRange: bytes=0-1023\n",
                "200\ncontent-type: text/html\ncontent-length: 4096\n<html>");

        CheckFinding finding = check.evaluate(
                Snapshots.page("https://example.com/seite")
                        .link("https://example.com/info.pdf", false).build(),
                Snapshots.config(check, Snapshots.facts(pdf))).getFirst();

        assertThat(finding.evidence().httpStatus()).isEqualTo(200);
        assertThat(finding.evidence().requestDetail()).contains("GET https://example.com/info.pdf");
        assertThat(finding.evidence().responseDetail()).contains("content-type: text/html");
        assertThat(finding.evidence().responseDetail()).contains("<html>");
    }
}
