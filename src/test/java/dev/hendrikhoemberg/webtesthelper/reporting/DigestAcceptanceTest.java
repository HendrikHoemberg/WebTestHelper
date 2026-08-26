package dev.hendrikhoemberg.webtesthelper.reporting;

import com.icegreen.greenmail.configuration.GreenMailConfiguration;
import com.icegreen.greenmail.junit5.GreenMailExtension;
import com.icegreen.greenmail.util.ServerSetup;
import dev.hendrikhoemberg.webtesthelper.catalog.AppSettings;
import dev.hendrikhoemberg.webtesthelper.catalog.RecipientService;
import dev.hendrikhoemberg.webtesthelper.catalog.SiteForm;
import dev.hendrikhoemberg.webtesthelper.catalog.SiteService;
import dev.hendrikhoemberg.webtesthelper.catalog.SmtpSettings;
import dev.hendrikhoemberg.webtesthelper.catalog.TlsMode;
import dev.hendrikhoemberg.webtesthelper.findings.FindingService;
import dev.hendrikhoemberg.webtesthelper.model.CheckFinding;
import dev.hendrikhoemberg.webtesthelper.model.CheckType;
import dev.hendrikhoemberg.webtesthelper.model.Evidence;
import dev.hendrikhoemberg.webtesthelper.model.NormalizedUrl;
import dev.hendrikhoemberg.webtesthelper.model.RunCoverage;
import dev.hendrikhoemberg.webtesthelper.model.RunScope;
import dev.hendrikhoemberg.webtesthelper.model.RunStatus;
import dev.hendrikhoemberg.webtesthelper.model.RunTrigger;
import dev.hendrikhoemberg.webtesthelper.model.Severity;
import dev.hendrikhoemberg.webtesthelper.runner.persistence.RunEntity;
import dev.hendrikhoemberg.webtesthelper.runner.persistence.RunRepository;
import dev.hendrikhoemberg.webtesthelper.support.AbstractPostgresTest;
import jakarta.mail.BodyPart;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DigestAcceptanceTest extends AbstractPostgresTest {

    @RegisterExtension
    static GreenMailExtension greenMail = new GreenMailExtension(
            new ServerSetup(0, "127.0.0.1", ServerSetup.PROTOCOL_SMTP)
    ).withConfiguration(GreenMailConfiguration.aConfig().withDisabledAuthentication());

    @Autowired
    DigestService digestService;

    @Autowired
    OutboxDispatcher dispatcher;

    @Autowired
    SiteService siteService;

    @Autowired
    RecipientService recipientService;

    @Autowired
    FindingService findingService;

    @Autowired
    RunRepository runRepository;

    @Autowired
    AppSettings appSettings;

    @Autowired
    ReportingProperties properties;

    @Autowired
    JdbcTemplate jdbc;

    private long siteA;
    private long siteB;
    private long siteC;

    @BeforeEach
    void setUp() {
        cleanup();
        greenMail.reset();

        siteA = siteService.create(new SiteForm(
                "Kundenseite A", "https://site-a.example.test/", 100, 3,
                Duration.ofMinutes(10), List.of(), List.of(), true, null, true));
        siteB = siteService.create(new SiteForm(
                "Kundenseite B", "https://site-b.example.test/", 100, 3,
                Duration.ofMinutes(10), List.of(), List.of(), true, null, true));
        siteC = siteService.create(new SiteForm(
                "Kundenseite C", "https://site-c.example.test/", 100, 3,
                Duration.ofMinutes(10), List.of(), List.of(), true, null, true));

        recipientService.add(siteA, "betreuer-a@example.test");
        recipientService.add(siteB, "betreuer-a@example.test");
        recipientService.add(siteC, "betreuer-c@example.test");

        appSettings.saveSmtp(new SmtpSettings(
                "127.0.0.1",
                greenMail.getSmtp().getPort(),
                TlsMode.NONE,
                null,
                null,
                "noreply@wth.example"
        ));
        appSettings.saveBaseUrl("https://wth.example");
        appSettings.saveRedirectAllMailTo("");
        appSettings.saveFallbackRecipients("");
    }

    @AfterEach
    void tearDown() {
        cleanup();
        greenMail.reset();
    }

    private void cleanup() {
        jdbc.update("DELETE FROM notification");
        jdbc.update("DELETE FROM crawl_queue_item");
        jdbc.update("DELETE FROM finding_occurrence");
        jdbc.update("DELETE FROM finding");
        jdbc.update("DELETE FROM run");
        jdbc.update("DELETE FROM notification_recipient");
        jdbc.update("DELETE FROM site_check_setting");
        jdbc.update("DELETE FROM site");
    }

    @Test
    void nightlyDigestEndToEndThroughRealSmtp() throws Exception {
        Instant t0 = Instant.parse("2026-08-26T03:00:00Z");

        // Site A: 1 NEW ERROR + 1 NEW WARN
        Instant finishedA = t0.plusSeconds(300);
        RunEntity runA = new RunEntity();
        runA.setSiteId(siteA);
        runA.setScope(RunScope.PULSE);
        runA.setTriggerType(RunTrigger.SCHEDULED);
        runA.setStatus(RunStatus.COMPLETED);
        runA.setQueuedAt(t0.plusSeconds(60));
        runA.setStartedAt(t0.plusSeconds(120));
        runA.setFinishedAt(finishedA);
        runA.setPagesVisited(10);
        runA.setFindingsTotal(2);
        runA.setFindingsNew(2);
        runA = runRepository.save(runA);

        CheckFinding errorFinding = new CheckFinding(
                CheckType.PAGE_STATUS,
                Severity.ERROR,
                "status:500:/err",
                page("site-a.example.test", "/err"),
                "finding.PAGE_STATUS.httpError",
                List.of("500"),
                Evidence.NONE
        );
        CheckFinding warnFinding = new CheckFinding(
                CheckType.MIXED_CONTENT,
                Severity.WARN,
                "mixed:http://insecure.example.test/img.png",
                page("site-a.example.test", "/"),
                "finding.MIXED_CONTENT.insecureSubresource",
                List.of("http://insecure.example.test/img.png"),
                Evidence.NONE
        );
        findingService.record(
                runA.getId(),
                siteA,
                List.of(errorFinding, warnFinding),
                coverage("https://site-a.example.test", List.of("/err", "/")),
                finishedA
        );

        // Site B: COMPLETED run, nothing changed (quiet)
        Instant finishedB = t0.plusSeconds(400);
        RunEntity runB = new RunEntity();
        runB.setSiteId(siteB);
        runB.setScope(RunScope.PULSE);
        runB.setTriggerType(RunTrigger.SCHEDULED);
        runB.setStatus(RunStatus.COMPLETED);
        runB.setQueuedAt(t0.plusSeconds(70));
        runB.setStartedAt(t0.plusSeconds(130));
        runB.setFinishedAt(finishedB);
        runB.setPagesVisited(8);
        runB.setFindingsTotal(0);
        runB = runRepository.save(runB);

        findingService.record(
                runB.getId(),
                siteB,
                List.of(),
                coverage("https://site-b.example.test", List.of("/")),
                finishedB
        );

        // Site C: FAILED run
        Instant finishedC = t0.plusSeconds(500);
        RunEntity runC = new RunEntity();
        runC.setSiteId(siteC);
        runC.setScope(RunScope.PULSE);
        runC.setTriggerType(RunTrigger.SCHEDULED);
        runC.setStatus(RunStatus.FAILED);
        runC.setQueuedAt(t0.plusSeconds(80));
        runC.setStartedAt(t0.plusSeconds(140));
        runC.setFinishedAt(finishedC);
        runC.setPagesFailed(1);
        runC.setErrorMessage("Verbindung abgelehnt: Connection refused");
        runC = runRepository.save(runC);

        // 1. Cycle at settle + 1 min, then run the outbox dispatcher.
        Instant cycleTime1 = finishedC.plus(properties.digestSettle()).plus(Duration.ofMinutes(1));
        int enqueued1 = digestService.runCycle(cycleTime1);
        assertThat(enqueued1).isEqualTo(2);

        int dispatched1 = dispatcher.dispatchCycle();
        assertThat(dispatched1).isEqualTo(2);

        // 2. GreenMail received two messages, not three and not one per site.
        MimeMessage[] messages1 = greenMail.getReceivedMessages();
        assertThat(messages1).hasSize(2);

        MimeMessage mailA = findMessageFor(messages1, "betreuer-a@example.test");
        MimeMessage mailC = findMessageFor(messages1, "betreuer-c@example.test");

        // 3. betreuer-a@example.test's message is multipart, its subject names 1 new error
        //    (the WARN is listed but not counted by §11.1's predicate), HTML names both A and B
        //    with B as a quiet count, and it carries https://wth.example/befunde/.
        assertThat(mailA.getContent()).isInstanceOf(MimeMultipart.class);
        assertThat(mailA.getSubject()).contains("1 neuer oder wiederkehrender Fehler");
        assertThat(mailA.getSubject()).doesNotContain("Warnung");
        assertThat(mailA.getSubject()).doesNotContain("2 ");

        StringBuilder textA = new StringBuilder();
        StringBuilder htmlA = new StringBuilder();
        extractParts((MimeMultipart) mailA.getContent(), textA, htmlA);

        assertThat(htmlA.toString()).contains("Kundenseite A");
        assertThat(htmlA.toString()).contains("Kundenseite B");
        assertThat(htmlA.toString()).doesNotContain("Kundenseite C");
        assertThat(htmlA.toString()).contains("https://wth.example/befunde/");
        assertThat(htmlA.toString()).contains("Fehler");
        assertThat(htmlA.toString()).contains("Warnung");

        // 4. betreuer-c@example.test's message names the failed run and neither A nor B.
        assertThat(mailC.getContent()).isInstanceOf(MimeMultipart.class);
        assertThat(mailC.getSubject()).contains("1 Prüflauf fehlgeschlagen");

        StringBuilder textC = new StringBuilder();
        StringBuilder htmlC = new StringBuilder();
        extractParts((MimeMultipart) mailC.getContent(), textC, htmlC);

        assertThat(htmlC.toString()).contains("Kundenseite C");
        assertThat(htmlC.toString()).contains("Der Prüflauf ist fehlgeschlagen");
        assertThat(htmlC.toString()).contains("Verbindung abgelehnt: Connection refused");
        assertThat(htmlC.toString()).doesNotContain("Kundenseite A");
        assertThat(htmlC.toString()).doesNotContain("Kundenseite B");

        // 5. A second cycle plus dispatch adds no message.
        Instant cycleTime2 = cycleTime1.plus(Duration.ofMinutes(2));
        int enqueued2 = digestService.runCycle(cycleTime2);
        assertThat(enqueued2).isZero();
        int dispatched2 = dispatcher.dispatchCycle();
        assertThat(dispatched2).isZero();
        assertThat(greenMail.getReceivedMessages()).hasSize(2);

        // 6. A DEEP run for site A with nothing changed, one more cycle and dispatch:
        //    1 further message to betreuer-a@example.test whose subject ends "alles in Ordnung"
        //    (§11.1's periodic proof the system is alive).
        Instant finishedDeep = cycleTime2.plus(Duration.ofMinutes(10));
        RunEntity deepRunA = new RunEntity();
        deepRunA.setSiteId(siteA);
        deepRunA.setScope(RunScope.DEEP);
        deepRunA.setTriggerType(RunTrigger.SCHEDULED);
        deepRunA.setStatus(RunStatus.COMPLETED);
        deepRunA.setQueuedAt(finishedDeep.minusSeconds(120));
        deepRunA.setStartedAt(finishedDeep.minusSeconds(100));
        deepRunA.setFinishedAt(finishedDeep);
        deepRunA.setPagesVisited(50);
        deepRunA.setFindingsTotal(0);
        deepRunA = runRepository.save(deepRunA);

        findingService.record(
                deepRunA.getId(),
                siteA,
                List.of(),
                coverage("https://site-a.example.test", List.of("/err", "/")),
                finishedDeep
        );

        Instant cycleTime3 = finishedDeep.plus(properties.digestSettle()).plus(Duration.ofMinutes(1));
        int enqueued3 = digestService.runCycle(cycleTime3);
        assertThat(enqueued3).isEqualTo(1);
        int dispatched3 = dispatcher.dispatchCycle();
        assertThat(dispatched3).isEqualTo(1);

        MimeMessage[] messages3 = greenMail.getReceivedMessages();
        assertThat(messages3).hasSize(3);

        List<MimeMessage> aMessagesAfterDeep = findMessagesFor(messages3, "betreuer-a@example.test");
        assertThat(aMessagesAfterDeep).hasSize(2);

        MimeMessage mailDeep = aMessagesAfterDeep.get(1);
        assertThat(mailDeep.getAllRecipients()[0].toString()).contains("betreuer-a@example.test");
        assertThat(mailDeep.getSubject()).contains("Tiefenprüfung");
        assertThat(mailDeep.getSubject()).endsWith("alles in Ordnung");

        StringBuilder textDeep = new StringBuilder();
        StringBuilder htmlDeep = new StringBuilder();
        extractParts((MimeMultipart) mailDeep.getContent(), textDeep, htmlDeep);
        assertThat(htmlDeep.toString()).contains("Auf allen geprüften Websites ist alles in Ordnung.");

        // 7. The same DEEP-less PULSE case for a quiet window sends nothing, and the run is stamped all the same.
        Instant finishedPulse2 = cycleTime3.plus(Duration.ofMinutes(10));
        RunEntity pulse2RunA = new RunEntity();
        pulse2RunA.setSiteId(siteA);
        pulse2RunA.setScope(RunScope.PULSE);
        pulse2RunA.setTriggerType(RunTrigger.SCHEDULED);
        pulse2RunA.setStatus(RunStatus.COMPLETED);
        pulse2RunA.setQueuedAt(finishedPulse2.minusSeconds(60));
        pulse2RunA.setStartedAt(finishedPulse2.minusSeconds(50));
        pulse2RunA.setFinishedAt(finishedPulse2);
        pulse2RunA.setPagesVisited(5);
        pulse2RunA.setFindingsTotal(0);
        pulse2RunA = runRepository.save(pulse2RunA);

        findingService.record(
                pulse2RunA.getId(),
                siteA,
                List.of(),
                coverage("https://site-a.example.test", List.of("/err", "/")),
                finishedPulse2
        );

        Instant cycleTime4 = finishedPulse2.plus(properties.digestSettle()).plus(Duration.ofMinutes(1));
        int enqueued4 = digestService.runCycle(cycleTime4);
        assertThat(enqueued4).isZero();
        int dispatched4 = dispatcher.dispatchCycle();
        assertThat(dispatched4).isZero();
        assertThat(greenMail.getReceivedMessages()).hasSize(3);

        RunEntity stampedPulse2 = runRepository.findById(pulse2RunA.getId()).orElseThrow();
        assertThat(stampedPulse2.getDigestSentAt()).isEqualTo(cycleTime4);
    }

    private List<MimeMessage> findMessagesFor(MimeMessage[] messages, String recipient) {
        return Arrays.stream(messages)
                .filter(m -> {
                    try {
                        return Arrays.stream(m.getAllRecipients())
                                .anyMatch(r -> r.toString().contains(recipient));
                    } catch (Exception e) {
                        return false;
                    }
                })
                .toList();
    }

    private MimeMessage findMessageFor(MimeMessage[] messages, String recipient) {
        List<MimeMessage> matches = findMessagesFor(messages, recipient);
        assertThat(matches).isNotEmpty();
        return matches.get(0);
    }

    private void extractParts(MimeMultipart multipart, StringBuilder text, StringBuilder html) throws Exception {
        for (int i = 0; i < multipart.getCount(); i++) {
            BodyPart part = multipart.getBodyPart(i);
            if (part.isMimeType("text/plain")) {
                text.append(part.getContent());
            } else if (part.isMimeType("text/html")) {
                html.append(part.getContent());
            } else if (part.getContent() instanceof MimeMultipart child) {
                extractParts(child, text, html);
            }
        }
    }

    private NormalizedUrl page(String host, String path) {
        return new NormalizedUrl("https", host, 443, path, null);
    }

    private RunCoverage coverage(String base, List<String> paths) {
        List<String> urls = paths.stream().map(p -> base + p).toList();
        return RunCoverage.of(RunScope.FULL.checkTypes().stream().map(CheckType::name).toList(), urls, List.of(), false);
    }
}
