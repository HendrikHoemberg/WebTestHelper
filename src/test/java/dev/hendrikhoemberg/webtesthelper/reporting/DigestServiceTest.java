package dev.hendrikhoemberg.webtesthelper.reporting;

import dev.hendrikhoemberg.webtesthelper.catalog.AppSettings;
import dev.hendrikhoemberg.webtesthelper.catalog.RecipientService;
import dev.hendrikhoemberg.webtesthelper.catalog.SiteForm;
import dev.hendrikhoemberg.webtesthelper.catalog.SiteService;
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
import dev.hendrikhoemberg.webtesthelper.reporting.persistence.NotificationEntity;
import dev.hendrikhoemberg.webtesthelper.reporting.persistence.NotificationRepository;
import dev.hendrikhoemberg.webtesthelper.runner.persistence.RunEntity;
import dev.hendrikhoemberg.webtesthelper.runner.persistence.RunRepository;
import dev.hendrikhoemberg.webtesthelper.support.AbstractPostgresTest;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@Transactional
class DigestServiceTest extends AbstractPostgresTest {

    @Autowired
    DigestService digestService;

    @Autowired
    SiteService siteService;

    @Autowired
    RecipientService recipientService;

    @Autowired
    AppSettings appSettings;

    @Autowired
    FindingService findingService;

    @Autowired
    RunRepository runRepository;

    @Autowired
    NotificationRepository notificationRepository;

    @Autowired
    ReportingProperties properties;

    @Autowired
    EntityManager entityManager;

    @Autowired
    JdbcTemplate jdbc;

    private long siteA;
    private long siteB;
    private Instant t0;

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM notification");
        jdbc.update("DELETE FROM crawl_queue_item");
        jdbc.update("DELETE FROM finding_occurrence");
        jdbc.update("DELETE FROM finding");
        jdbc.update("DELETE FROM run");
        jdbc.update("DELETE FROM notification_recipient");
        jdbc.update("DELETE FROM site_check_setting");
        jdbc.update("DELETE FROM site");

        siteA = siteService.create(new SiteForm(
                "Site Alpha", "https://alpha.example.com/", 100, 3,
                Duration.ofMinutes(10), List.of(), List.of(), true, null, true));
        siteB = siteService.create(new SiteForm(
                "Site Beta", "https://beta.example.com/", 100, 3,
                Duration.ofMinutes(10), List.of(), List.of(), true, null, true));

        appSettings.saveBaseUrl("https://wth.example.com");
        t0 = Instant.parse("2026-08-26T10:00:00Z");
    }

    @Test
    void cycleBeforeSettleElapsesEnqueuesNothingAndStampsNothing() {
        recipientService.add(siteA, "a@example.test");
        appSettings.saveFallbackRecipients("team@example.test");

        long runA = seedCompletedRunWithError(siteA, RunScope.PULSE, t0);
        long runB = seedFailedRun(siteB, RunScope.PULSE, t0, "Connection refused");

        // Cycle at t0 + 3m (settle is 5m)
        Instant cycleTime = t0.plus(Duration.ofMinutes(3));
        int enqueued = digestService.runCycle(cycleTime);

        assertThat(enqueued).isZero();
        assertThat(notificationRepository.findAll()).isEmpty();

        entityManager.flush();
        entityManager.clear();

        RunEntity entityA = runRepository.findById(runA).orElseThrow();
        RunEntity entityB = runRepository.findById(runB).orElseThrow();
        assertThat(entityA.getDigestSentAt()).isNull();
        assertThat(entityB.getDigestSentAt()).isNull();
    }

    @Test
    void cycleAtSettlePlusOneMinuteEnqueuesTwoNotificationsRespectingBoundariesAndStampsBothRuns() {
        recipientService.add(siteA, "a@example.test");
        appSettings.saveFallbackRecipients("team@example.test");

        long runA = seedCompletedRunWithError(siteA, RunScope.PULSE, t0);
        long runB = seedFailedRun(siteB, RunScope.PULSE, t0, "Connection refused");

        // Cycle at t0 + 6m (settle + 1 min)
        Instant cycleTime = t0.plus(Duration.ofMinutes(6));
        int enqueued = digestService.runCycle(cycleTime);

        assertThat(enqueued).isEqualTo(2);

        List<NotificationEntity> notifications = notificationRepository.findAll();
        assertThat(notifications).hasSize(2);

        NotificationEntity notifA = notifications.stream()
                .filter(n -> n.getRecipient().equals("a@example.test"))
                .findFirst().orElseThrow();
        assertThat(notifA.getBodyHtml()).contains("Site Alpha");
        assertThat(notifA.getBodyHtml()).doesNotContain("Site Beta");

        NotificationEntity notifTeam = notifications.stream()
                .filter(n -> n.getRecipient().equals("team@example.test"))
                .findFirst().orElseThrow();
        assertThat(notifTeam.getBodyHtml()).contains("Site Beta");
        assertThat(notifTeam.getBodyHtml()).contains("Connection refused");
        assertThat(notifTeam.getBodyHtml()).doesNotContain("Site Alpha");

        entityManager.flush();
        entityManager.clear();

        RunEntity entityA = runRepository.findById(runA).orElseThrow();
        RunEntity entityB = runRepository.findById(runB).orElseThrow();
        assertThat(entityA.getDigestSentAt()).isEqualTo(cycleTime);
        assertThat(entityB.getDigestSentAt()).isEqualTo(cycleTime);

        // Second cycle enqueues nothing because runs are already stamped
        int secondCycle = digestService.runCycle(cycleTime.plus(Duration.ofMinutes(2)));
        assertThat(secondCycle).isZero();
        assertThat(notificationRepository.findAll()).hasSize(2);
    }

    @Test
    void quietPulseWindowEnqueuesNothingButStampsRun() {
        recipientService.add(siteA, "a@example.test");

        // Completed run with NO errors or failures
        long runA = seedCompletedRunQuiet(siteA, RunScope.PULSE, t0);

        Instant cycleTime = t0.plus(Duration.ofMinutes(6));
        int enqueued = digestService.runCycle(cycleTime);

        assertThat(enqueued).isZero();
        assertThat(notificationRepository.findAll()).isEmpty();

        entityManager.flush();
        entityManager.clear();

        RunEntity entityA = runRepository.findById(runA).orElseThrow();
        assertThat(entityA.getDigestSentAt()).isEqualTo(cycleTime);
    }

    @Test
    void siteWithNoRecipientAndNoFallbackLogsWarnStampsRunAndEnqueuesNothing() {
        appSettings.saveFallbackRecipients("");

        long runA = seedCompletedRunWithError(siteA, RunScope.PULSE, t0);

        Instant cycleTime = t0.plus(Duration.ofMinutes(6));
        int enqueued = digestService.runCycle(cycleTime);

        assertThat(enqueued).isZero();
        assertThat(notificationRepository.findAll()).isEmpty();

        entityManager.flush();
        entityManager.clear();

        RunEntity entityA = runRepository.findById(runA).orElseThrow();
        assertThat(entityA.getDigestSentAt()).isEqualTo(cycleTime);
    }

    @Test
    void deepScopeAlwaysEnqueuesNotificationEvenWhenQuiet() {
        recipientService.add(siteA, "a@example.test");

        long runA = seedCompletedRunQuiet(siteA, RunScope.DEEP, t0);

        Instant cycleTime = t0.plus(Duration.ofMinutes(6));
        int enqueued = digestService.runCycle(cycleTime);

        assertThat(enqueued).isEqualTo(1);

        List<NotificationEntity> notifications = notificationRepository.findAll();
        assertThat(notifications).hasSize(1);
        assertThat(notifications.get(0).getRecipient()).isEqualTo("a@example.test");
        assertThat(notifications.get(0).getSubject()).contains("Tiefenprüfung");
        assertThat(notifications.get(0).getSubject()).contains("alles in Ordnung");

        entityManager.flush();
        entityManager.clear();

        RunEntity entityA = runRepository.findById(runA).orElseThrow();
        assertThat(entityA.getDigestSentAt()).isEqualTo(cycleTime);
    }

    @Test
    void inFlightRunsPreventWindowFromClosingUntilMaxWait() {
        recipientService.add(siteA, "a@example.test");

        long completedRun = seedCompletedRunWithError(siteA, RunScope.PULSE, t0);
        long inFlightRun = seedInFlightRun(siteB, RunScope.PULSE);

        // At settle + 1m, inFlight prevents closing
        Instant cycleTime1 = t0.plus(Duration.ofMinutes(6));
        int enqueued1 = digestService.runCycle(cycleTime1);
        assertThat(enqueued1).isZero();

        entityManager.flush();
        entityManager.clear();
        assertThat(runRepository.findById(completedRun).orElseThrow().getDigestSentAt()).isNull();

        // At maxWait + 1m (maxWait is 6h), overdue window closes despite inFlight
        Instant cycleTime2 = t0.plus(Duration.ofHours(6).plusMinutes(1));
        int enqueued2 = digestService.runCycle(cycleTime2);
        assertThat(enqueued2).isEqualTo(1);

        entityManager.flush();
        entityManager.clear();
        assertThat(runRepository.findById(completedRun).orElseThrow().getDigestSentAt()).isEqualTo(cycleTime2);
    }

    private long seedCompletedRunWithError(long siteId, RunScope scope, Instant finishedAt) {
        RunEntity run = new RunEntity();
        run.setSiteId(siteId);
        run.setScope(scope);
        run.setTriggerType(RunTrigger.SCHEDULED);
        run.setStatus(RunStatus.COMPLETED);
        run.setQueuedAt(finishedAt.minusSeconds(60));
        run.setStartedAt(finishedAt.minusSeconds(50));
        run.setFinishedAt(finishedAt);
        run.setPagesVisited(5);
        run.setFindingsTotal(1);
        run.setFindingsNew(1);
        run = runRepository.save(run);

        List<CheckFinding> findings = List.of(
                new CheckFinding(CheckType.PAGE_STATUS, Severity.ERROR, "status:500:/err",
                        page("/err"), "finding.PAGE_STATUS.httpError", List.of("500"), Evidence.NONE)
        );
        findingService.record(run.getId(), siteId, findings, fullCoverage(List.of("/err")), finishedAt);
        return run.getId();
    }

    private long seedCompletedRunQuiet(long siteId, RunScope scope, Instant finishedAt) {
        RunEntity run = new RunEntity();
        run.setSiteId(siteId);
        run.setScope(scope);
        run.setTriggerType(RunTrigger.SCHEDULED);
        run.setStatus(RunStatus.COMPLETED);
        run.setQueuedAt(finishedAt.minusSeconds(60));
        run.setStartedAt(finishedAt.minusSeconds(50));
        run.setFinishedAt(finishedAt);
        run.setPagesVisited(5);
        run.setFindingsTotal(0);
        run = runRepository.save(run);

        findingService.record(run.getId(), siteId, List.of(), fullCoverage(List.of("/")), finishedAt);
        return run.getId();
    }

    private long seedFailedRun(long siteId, RunScope scope, Instant finishedAt, String errorMessage) {
        RunEntity run = new RunEntity();
        run.setSiteId(siteId);
        run.setScope(scope);
        run.setTriggerType(RunTrigger.SCHEDULED);
        run.setStatus(RunStatus.FAILED);
        run.setQueuedAt(finishedAt.minusSeconds(60));
        run.setStartedAt(finishedAt.minusSeconds(50));
        run.setFinishedAt(finishedAt);
        run.setPagesFailed(1);
        run.setErrorMessage(errorMessage);
        return runRepository.save(run).getId();
    }

    private long seedInFlightRun(long siteId, RunScope scope) {
        RunEntity run = new RunEntity();
        run.setSiteId(siteId);
        run.setScope(scope);
        run.setTriggerType(RunTrigger.SCHEDULED);
        run.setStatus(RunStatus.RUNNING);
        run.setQueuedAt(t0);
        run.setStartedAt(t0.plusSeconds(10));
        return runRepository.save(run).getId();
    }

    private NormalizedUrl page(String path) {
        return new NormalizedUrl("https", "example.com", 443, path, null);
    }

    private RunCoverage fullCoverage(List<String> pages) {
        List<String> urls = pages.stream().map(p -> "https://example.com" + p).toList();
        return RunCoverage.of(RunScope.FULL.checkTypes().stream().map(CheckType::name).toList(), urls, List.of(), false);
    }
}
