package dev.hendrikhoemberg.webtesthelper.runner;

import com.icegreen.greenmail.util.GreenMail;
import com.icegreen.greenmail.util.ServerSetup;
import dev.hendrikhoemberg.webtesthelper.catalog.AppSettings;
import dev.hendrikhoemberg.webtesthelper.catalog.ImapSettings;
import dev.hendrikhoemberg.webtesthelper.catalog.SiteForm;
import dev.hendrikhoemberg.webtesthelper.catalog.SiteService;
import dev.hendrikhoemberg.webtesthelper.catalog.TlsMode;
import dev.hendrikhoemberg.webtesthelper.findings.Finding;
import dev.hendrikhoemberg.webtesthelper.findings.FindingService;
import dev.hendrikhoemberg.webtesthelper.findings.ReportSection;
import dev.hendrikhoemberg.webtesthelper.findings.RunDiff;
import dev.hendrikhoemberg.webtesthelper.model.CheckType;
import dev.hendrikhoemberg.webtesthelper.model.FormTestMode;
import dev.hendrikhoemberg.webtesthelper.model.ObservedStatus;
import dev.hendrikhoemberg.webtesthelper.model.RunScope;
import dev.hendrikhoemberg.webtesthelper.model.RunStatus;
import dev.hendrikhoemberg.webtesthelper.model.RunTrigger;
import dev.hendrikhoemberg.webtesthelper.model.Severity;
import dev.hendrikhoemberg.webtesthelper.reporting.FindingView;
import dev.hendrikhoemberg.webtesthelper.reporting.FindingViewFactory;
import dev.hendrikhoemberg.webtesthelper.support.AbstractPostgresTest;
import dev.hendrikhoemberg.webtesthelper.support.FixtureSite;
import dev.hendrikhoemberg.webtesthelper.web.ArtifactController;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end acceptance test for the contact form check (spec 7.2, 15; D89, D90, D91, D94, D95).
 * Three runs, real Postgres, real Chromium, real GreenMail, real registry.
 * Proves delivery, non-delivery, honeypot traps, and that a FULL run does not falsely resolve findings.
 */
@Tag("browser")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ContactFormAcceptanceTest extends AbstractPostgresTest {

    @Autowired
    SiteService sites;

    @Autowired
    RunService runs;

    @Autowired
    RunWorker worker;

    @Autowired
    FindingService findingService;

    @Autowired
    FindingViewFactory findingViewFactory;

    @Autowired
    ArtifactController artifactController;

    @Autowired
    AppSettings appSettings;

    @Autowired
    JdbcTemplate jdbc;

    private FixtureSite site;
    private GreenMail greenMail;

    @BeforeAll
    void startServices() {
        site = FixtureSite.start();
        ServerSetup smtpSetup = new ServerSetup(0, "127.0.0.1", ServerSetup.PROTOCOL_SMTP);
        ServerSetup imapSetup = new ServerSetup(0, "127.0.0.1", ServerSetup.PROTOCOL_IMAP);
        greenMail = new GreenMail(new ServerSetup[]{smtpSetup, imapSetup});
        greenMail.start();
        greenMail.setUser("verify@example.com", "verifyuser", "secretpass");
    }

    @AfterAll
    void stopServices() {
        if (greenMail != null) {
            greenMail.stop();
        }
        if (site != null) {
            site.close();
        }
    }

    @Test
    void threeRunStoryForDeliveryNonDeliveryAndScopeGate() throws IOException {
        jdbc.update("DELETE FROM notification");
        jdbc.update("DELETE FROM finding_occurrence");
        jdbc.update("DELETE FROM finding");
        jdbc.update("DELETE FROM crawl_queue_item");
        jdbc.update("DELETE FROM run");
        jdbc.update("DELETE FROM site_check_setting");
        jdbc.update("DELETE FROM site");
        greenMail.reset();
        greenMail.setUser("verify@example.com", "verifyuser", "secretpass");

        appSettings.saveImap(new ImapSettings(
                "127.0.0.1",
                greenMail.getImap().getPort(),
                TlsMode.NONE,
                "verifyuser",
                "secretpass",
                "INBOX",
                "verify@example.com"
        ));
        site.setMailRelay("127.0.0.1", greenMail.getSmtp().getPort(), "verify@example.com");

        // =========================================================================
        // Run 1: DEEP against formular-still.html (form answers success but never relays)
        // =========================================================================
        long siteId = sites.create(new SiteForm(
                "Kontaktformular Akzeptanz",
                site.url("interaktiv/formular-still.html"),
                30,
                3,
                Duration.ofMinutes(3),
                List.of(),
                List.of(),
                false,
                null,
                true,
                List.of(site.url("interaktiv/formular-still.html")),
                FormTestMode.SUBMIT_AND_VERIFY_MAIL));

        long runId1 = runs.enqueue(siteId, RunTrigger.MANUAL, RunScope.DEEP);
        assertThat(worker.workOnce()).isTrue();

        RunSummary summary1 = runs.summary(runId1);
        assertThat(summary1.status()).isEqualTo(RunStatus.COMPLETED);
        assertThat(summary1.coveredInteractionCheckTypes()).contains(CheckType.CONTACT_FORM);
        assertThat(site.requestCount("/kontakt/still")).isEqualTo(1);

        RunDiff diff1 = findingService.diffOf(siteId, runId1);
        List<Finding> contactFindings1 = diff1.of(ReportSection.NEW).stream()
                .filter(f -> f.type() == CheckType.CONTACT_FORM && "/interaktiv/formular-still.html".equals(f.locationKey()))
                .toList();
        assertThat(contactFindings1).hasSize(1);

        Finding finding1 = contactFindings1.get(0);
        assertThat(finding1.severity()).isEqualTo(Severity.ERROR);
        assertThat(finding1.messageKey()).isEqualTo("finding.CONTACT_FORM.notDelivered");
        assertThat(finding1.locationKey()).isEqualTo("/interaktiv/formular-still.html");

        FindingView view1 = findingViewFactory.of(finding1, Locale.GERMAN);
        assertThat(view1.title()).isEqualTo("Kontaktformular");
        assertThat(view1.message()).isEqualTo("Das Formular auf " + site.url("interaktiv/formular-still.html")
                + " meldet „Vielen Dank, Ihre Nachricht wurde versendet.“, aber die Nachricht ist innerhalb von 60 Sekunden nicht im Prüfpostfach angekommen.");
        assertThat(view1.remediation()).isEqualTo("Das Formular selbst ausfüllen und abschicken. Lässt es sich nicht absenden, fehlt meist eine Auswahlmöglichkeit in einem Pflichtfeld; kommt keine Nachricht an, prüfen Sie die Empfängeradresse im Redaktionssystem.");
        assertThat(view1.title()).doesNotContain("CONTACT_FORM");
        assertThat(view1.message()).doesNotContain("CONTACT_FORM");
        assertThat(view1.remediation()).doesNotContain("CONTACT_FORM");

        String screenshotPath = finding1.evidence().screenshotPath();
        assertThat(screenshotPath).isNotNull().matches("^[0-9a-f]{32}\\.png$");

        ResponseEntity<Resource> response = artifactController.screenshot(runId1, screenshotPath);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.IMAGE_PNG);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().exists()).isTrue();
        assertThat(response.getBody().contentLength()).isGreaterThan(0);

        // =========================================================================
        // Run 2: FULL against same site — finding must survive unchanged (D90)
        // =========================================================================
        long runId2 = runs.enqueue(siteId, RunTrigger.MANUAL, RunScope.FULL);
        assertThat(worker.workOnce()).isTrue();

        RunSummary summary2 = runs.summary(runId2);
        assertThat(summary2.status()).isEqualTo(RunStatus.COMPLETED);
        assertThat(summary2.coveredInteractionCheckTypes()).doesNotContain(CheckType.CONTACT_FORM);

        RunDiff diff2 = findingService.diffOf(siteId, runId2);
        assertThat(diff2.of(ReportSection.FIXED).stream().noneMatch(f -> f.id() == finding1.id())).isTrue();

        String finding1StatusRun2 = jdbc.queryForObject(
                "SELECT observed_status FROM finding WHERE id = ?", String.class, finding1.id());
        assertThat(finding1StatusRun2).isEqualTo(ObservedStatus.ACTIVE.name());
        assertThat(site.requestCount("/kontakt/still")).isEqualTo(1);

        // =========================================================================
        // Run 3: DEEP against formular.html (relays to GreenMail)
        // =========================================================================
        sites.update(siteId, new SiteForm(
                "Kontaktformular Akzeptanz",
                site.url("interaktiv/formular.html"),
                30,
                3,
                Duration.ofMinutes(3),
                List.of(),
                List.of(),
                false,
                null,
                true,
                List.of(site.url("interaktiv/formular.html")),
                FormTestMode.SUBMIT_AND_VERIFY_MAIL));

        long runId3 = runs.enqueue(siteId, RunTrigger.MANUAL, RunScope.DEEP);
        assertThat(worker.workOnce()).isTrue();

        RunSummary summary3 = runs.summary(runId3);
        assertThat(summary3.status()).isEqualTo(RunStatus.COMPLETED);
        assertThat(summary3.coveredInteractionCheckTypes()).contains(CheckType.CONTACT_FORM);

        MimeMessage[] receivedMessages = greenMail.getReceivedMessages();
        assertThat(receivedMessages).isNotEmpty();

        RunDiff diff3 = findingService.diffOf(siteId, runId3);
        List<Finding> newFindings3 = diff3.of(ReportSection.NEW).stream()
                .filter(f -> f.type() == CheckType.CONTACT_FORM && "/interaktiv/formular.html".equals(f.locationKey()))
                .toList();
        assertThat(newFindings3).isEmpty();

        List<Finding> notDeliveredFindings3 = diff3.of(ReportSection.NEW).stream()
                .filter(f -> f.type() == CheckType.CONTACT_FORM && "finding.CONTACT_FORM.notDelivered".equals(f.messageKey()))
                .toList();
        assertThat(notDeliveredFindings3).isEmpty();

        String postedBody = site.lastPostedBody("/kontakt/gesendet");
        assertThat(postedBody).isNotNull();
        Map<String, String> params = parseFormBody(postedBody);
        assertThat(params).containsEntry("csrf", "abc123");
        assertThat(params.getOrDefault("website", "")).isEmpty();
        assertThat(params.getOrDefault("fax", "")).isEmpty();
        assertThat(params.getOrDefault("url2", "")).isEmpty();
        assertThat(params.getOrDefault("company2", "")).isEmpty();

        assertThat(diff3.of(ReportSection.FIXED).stream().noneMatch(f -> f.id() == finding1.id())).isTrue();
        String finding1StatusRun3 = jdbc.queryForObject(
                "SELECT observed_status FROM finding WHERE id = ?", String.class, finding1.id());
        assertThat(finding1StatusRun3).isEqualTo(ObservedStatus.ACTIVE.name());
    }

    private static Map<String, String> parseFormBody(String body) {
        Map<String, String> map = new LinkedHashMap<>();
        if (body == null || body.isBlank()) {
            return map;
        }
        for (String pair : body.split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0) {
                String key = URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8);
                String val = URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
                map.put(key, val);
            } else if (eq < 0) {
                String key = URLDecoder.decode(pair, StandardCharsets.UTF_8);
                map.put(key, "");
            }
        }
        return map;
    }
}
