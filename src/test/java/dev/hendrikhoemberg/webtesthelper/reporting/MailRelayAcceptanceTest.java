package dev.hendrikhoemberg.webtesthelper.reporting;

import com.icegreen.greenmail.configuration.GreenMailConfiguration;
import com.icegreen.greenmail.junit5.GreenMailExtension;
import com.icegreen.greenmail.util.ServerSetup;
import dev.hendrikhoemberg.webtesthelper.catalog.AppSettings;
import dev.hendrikhoemberg.webtesthelper.model.RunScope;
import dev.hendrikhoemberg.webtesthelper.model.RunStatus;
import dev.hendrikhoemberg.webtesthelper.model.RunTrigger;
import dev.hendrikhoemberg.webtesthelper.reporting.persistence.NotificationEntity;
import dev.hendrikhoemberg.webtesthelper.reporting.persistence.NotificationRepository;
import dev.hendrikhoemberg.webtesthelper.runner.RunService;
import dev.hendrikhoemberg.webtesthelper.runner.RunSummary;
import dev.hendrikhoemberg.webtesthelper.runner.persistence.RunEntity;
import dev.hendrikhoemberg.webtesthelper.runner.persistence.RunRepository;
import dev.hendrikhoemberg.webtesthelper.support.AbstractPostgresTest;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

/**
 * Acceptance test proving §11.4's promise that the relay is working and §11.5's health banner
 * visibility when mail delivery fails, while ensuring runs remain untouched (§11.3).
 */
@AutoConfigureMockMvc
@WithMockUser(roles = "ADMIN")
class MailRelayAcceptanceTest extends AbstractPostgresTest {

    @RegisterExtension
    static GreenMailExtension greenMail = new GreenMailExtension(
            new ServerSetup(0, "127.0.0.1", ServerSetup.PROTOCOL_SMTP)
    ).withConfiguration(GreenMailConfiguration.aConfig().withDisabledAuthentication());

    @Autowired
    MockMvc mvc;

    @Autowired
    AppSettings appSettings;

    @Autowired
    OutboxService outboxService;

    @Autowired
    OutboxDispatcher dispatcher;

    @Autowired
    ReportingProperties reportingProperties;

    @Autowired
    NotificationRepository notificationRepository;

    @Autowired
    RunRepository runRepository;

    @Autowired
    RunService runService;

    @Autowired
    JdbcTemplate jdbc;

    private long siteId;
    private long existingRunId;

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM notification");
        jdbc.update("DELETE FROM crawl_queue_item");
        jdbc.update("DELETE FROM finding_occurrence");
        jdbc.update("DELETE FROM finding");
        jdbc.update("DELETE FROM run");
        jdbc.update("DELETE FROM site_check_setting");
        jdbc.update("DELETE FROM site");
        greenMail.reset();

        siteId = jdbc.queryForObject(
                "INSERT INTO site (name, base_url) VALUES (?, ?) RETURNING id",
                Long.class, "Acme Corp", "https://acme.example.com/");

        RunEntity existingRun = new RunEntity();
        existingRun.setSiteId(siteId);
        existingRun.setStatus(RunStatus.COMPLETED);
        existingRun.setTriggerType(RunTrigger.SCHEDULED);
        existingRun.setScope(RunScope.FULL);
        existingRun.setPagesVisited(42);
        existingRun.setPagesFailed(0);
        existingRun.setFindingsTotal(5);
        existingRun.setFindingsNew(1);
        existingRun.setFindingsResolved(2);
        existingRun.setStartedAt(Instant.now().minusSeconds(120));
        existingRun.setFinishedAt(Instant.now().minusSeconds(60));
        existingRun = runRepository.save(existingRun);
        existingRunId = existingRun.getId();
    }

    @Test
    void mailRelayConfigurationDeliveryFailureAndHealthBannerLifecycle() throws Exception {
        // 1. POST /einstellungen with GreenMail's host and port, a from-address and a base URL, as ADMIN.
        mvc.perform(post("/einstellungen")
                        .with(csrf())
                        .param("host", "127.0.0.1")
                        .param("port", String.valueOf(greenMail.getSmtp().getPort()))
                        .param("tls", "NONE")
                        .param("fromAddress", "alerts@example.com")
                        .param("baseUrl", "https://webtesthelper.example.com")
                        .param("redirectAllMailTo", ""))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/einstellungen"));

        assertThat(appSettings.smtp().host()).isEqualTo("127.0.0.1");
        assertThat(appSettings.smtp().port()).isEqualTo(greenMail.getSmtp().getPort());
        assertThat(appSettings.smtp().fromAddress()).isEqualTo("alerts@example.com");
        assertThat(appSettings.baseUrl()).isEqualTo("https://webtesthelper.example.com");

        // 2. POST /einstellungen/testmail: GreenMail receives one multipart message;
        //    the flash says delivered; GET /postausgang shows the row as SENT.
        mvc.perform(post("/einstellungen/testmail").with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/einstellungen"))
                .andExpect(flash().attribute("testmailErfolg", true));

        MimeMessage[] messages = greenMail.getReceivedMessages();
        assertThat(messages).hasSize(1);
        MimeMessage message = messages[0];
        assertThat(message.getContent()).isInstanceOf(MimeMultipart.class);
        assertThat(message.getFrom()[0].toString()).contains("alerts@example.com");
        assertThat(message.getAllRecipients()[0].toString()).contains("alerts@example.com");

        mvc.perform(get("/postausgang"))
                .andExpect(status().isOk())
                .andExpect(view().name("postausgang/index"))
                .andExpect(content().string(containsString("status-SENT")))
                .andExpect(content().string(containsString("alerts@example.com")))
                .andExpect(content().string(containsString("Zugestellt")));

        // 3. Reconfigure to a dead port, enqueue another mail, dispatch maxAttempts times:
        //    the row is FAILED, /postausgang shows the relay's error text, and
        //    the health banner is now on the run list too (D35).
        int deadPort = 65432;
        mvc.perform(post("/einstellungen")
                        .with(csrf())
                        .param("host", "127.0.0.1")
                        .param("port", String.valueOf(deadPort))
                        .param("tls", "NONE")
                        .param("fromAddress", "alerts@example.com")
                        .param("baseUrl", "https://webtesthelper.example.com")
                        .param("redirectAllMailTo", ""))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/einstellungen"));

        long failedMailId = outboxService.enqueue(new OutboundMail(
                "reports@example.com",
                "Monatsbericht",
                "<html><body><p>HTML Bericht</p></body></html>",
                "Text Bericht"
        ));

        int maxAttempts = reportingProperties.maxAttempts();
        for (int i = 1; i <= maxAttempts; i++) {
            NotificationEntity row = notificationRepository.findById(failedMailId).orElseThrow();
            row.setNextAttemptAt(Instant.now().minusSeconds(1));
            notificationRepository.save(row);
            dispatcher.dispatchCycle();
        }

        NotificationEntity failedRow = notificationRepository.findById(failedMailId).orElseThrow();
        assertThat(failedRow.getState()).isEqualTo(NotificationState.FAILED);
        assertThat(failedRow.getAttempts()).isEqualTo(maxAttempts);
        assertThat(failedRow.getLastError()).isNotBlank();
        assertThat(failedRow.getLastError()).contains("Connection refused");

        // /postausgang shows the relay's error text
        mvc.perform(get("/postausgang"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("status-FAILED")))
                .andExpect(content().string(containsString("Fehlgeschlagen")))
                .andExpect(content().string(containsString("Connection refused")))
                .andExpect(content().string(containsString(String.valueOf(deadPort))));

        // The health banner is now on the run list too (D35)
        mvc.perform(get("/websites/" + siteId + "/laeufe"))
                .andExpect(status().isOk())
                .andExpect(view().name("websites/laeufe"))
                .andExpect(content().string(containsString("Verlauf der Prüfläufe")))
                .andExpect(content().string(containsString("gesundheits-banner warnung")))
                .andExpect(content().string(containsString("1 fehlgeschlagen")))
                .andExpect(content().string(containsString("/postausgang")));

        // 4. Assert the site's runs are untouched (§11.3 — a run must never fail because the mail relay is down).
        RunSummary summary = runService.summary(existingRunId);
        assertThat(summary.status()).isEqualTo(RunStatus.COMPLETED);
        assertThat(summary.errorMessage()).isNull();
        assertThat(summary.pagesVisited()).isEqualTo(42);
        assertThat(summary.findingsTotal()).isEqualTo(5);
        assertThat(summary.findingsNew()).isEqualTo(1);
        assertThat(summary.findingsResolved()).isEqualTo(2);

        int totalRuns = jdbc.queryForObject(
                "SELECT count(*) FROM run WHERE site_id = ?", Integer.class, siteId);
        assertThat(totalRuns).isEqualTo(1);
        int failedRuns = jdbc.queryForObject(
                "SELECT count(*) FROM run WHERE site_id = ? AND status = 'FAILED'", Integer.class, siteId);
        assertThat(failedRuns).isZero();
    }
}
