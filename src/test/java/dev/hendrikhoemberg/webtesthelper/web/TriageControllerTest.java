package dev.hendrikhoemberg.webtesthelper.web;

import dev.hendrikhoemberg.webtesthelper.catalog.SiteForm;
import dev.hendrikhoemberg.webtesthelper.catalog.SiteService;
import dev.hendrikhoemberg.webtesthelper.findings.Finding;
import dev.hendrikhoemberg.webtesthelper.findings.FindingProperties;
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
import dev.hendrikhoemberg.webtesthelper.model.TriageStatus;
import dev.hendrikhoemberg.webtesthelper.model.UrlNormalizer;
import dev.hendrikhoemberg.webtesthelper.runner.persistence.RunEntity;
import dev.hendrikhoemberg.webtesthelper.runner.persistence.RunRepository;
import dev.hendrikhoemberg.webtesthelper.scheduling.ScheduleService;
import dev.hendrikhoemberg.webtesthelper.support.AbstractPostgresTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
class TriageControllerTest extends AbstractPostgresTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    FindingService findingService;

    @Autowired
    SiteService siteService;

    @Autowired
    ScheduleService scheduleService;

    @Autowired
    RunRepository runRepository;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    FindingProperties findingProperties;

    private long siteA;
    private long siteB;
    private Instant now;

    @BeforeEach
    void setup() {
        jdbc.update("DELETE FROM notification");
        jdbc.update("DELETE FROM crawl_queue_item");
        jdbc.update("DELETE FROM finding_occurrence");
        jdbc.update("DELETE FROM finding");
        jdbc.update("DELETE FROM run");
        jdbc.update("DELETE FROM schedule");
        jdbc.update("DELETE FROM site_check_setting");
        jdbc.update("DELETE FROM site");

        siteA = siteService.create(new SiteForm(
                "Kunde A", "https://www.example.com/", 100, 3,
                Duration.ofMinutes(10), List.of(), List.of(), true, null, true));
        siteB = siteService.create(new SiteForm(
                "Kunde B", "https://www.other.com/", 100, 3,
                Duration.ofMinutes(10), List.of(), List.of(), true, null, true));

        now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        scheduleService.seedDefaults(siteA, now);
        scheduleService.seedDefaults(siteB, now);
    }

    private long createFinding(long siteId, String path) {
        RunEntity run = new RunEntity();
        run.setSiteId(siteId);
        run.setStatus(RunStatus.COMPLETED);
        run.setTriggerType(RunTrigger.MANUAL);
        run.setScope(RunScope.FULL);
        run.setPagesVisited(1);
        run.setPagesFailed(0);
        run.setPartialCoverage(false);
        run.setCoveredCheckTypes(RunScope.FULL.checkTypes().stream().map(CheckType::name).toList());
        run.setCoveredUrls(List.of("https://www.example.com" + path));
        run.setStartedAt(now.minusSeconds(60));
        run.setFinishedAt(now.minusSeconds(30));
        run = runRepository.save(run);

        NormalizedUrl page = UrlNormalizer.normalize("https://www.example.com" + path).orElseThrow();
        CheckFinding cf = new CheckFinding(CheckType.DEAD_LINK, Severity.ERROR, "dead:" + path, page,
                "finding.DEAD_LINK.dead", List.of("https://www.example.com" + path, "404 Not Found"), Evidence.NONE);
        RunCoverage coverage = RunCoverage.of(
                RunScope.FULL,
                RunScope.FULL.checkTypes().stream().map(CheckType::name).toList(),
                List.of("https://www.example.com" + path),
                List.of(),
                false);
        findingService.record(run.getId(), siteId, List.of(cf), coverage, now);

        return jdbc.queryForObject(
                "SELECT id FROM finding WHERE site_id = ? AND location_key = ?", Long.class, siteId, path);
    }

    @Test
    @WithMockUser(username = "alice", roles = "USER")
    void mutingOneFindingWithReasonAndDate_writesMuted_mutedUntilEndOfDayInSiteTimezone_andTriagedBy() throws Exception {
        long findingId = createFinding(siteA, "/page-1");
        LocalDate muteDate = LocalDate.now().plusDays(90);

        mvc.perform(post("/befunde/" + findingId + "/bewerten")
                        .with(csrf())
                        .param("aktion", "MUTED")
                        .param("grund", "Warten auf Relaunch")
                        .param("stummBis", muteDate.toString()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/befunde/" + findingId));

        Finding finding = findingService.byId(findingId).orElseThrow();
        assertThat(finding.triage()).isEqualTo(TriageStatus.MUTED);
        assertThat(finding.triageReason()).isEqualTo("Warten auf Relaunch");
        assertThat(finding.triagedBy()).isEqualTo("alice");

        Instant expectedMutedUntil = muteDate.atTime(LocalTime.MAX).atZone(ZoneId.of("Europe/Berlin")).toInstant()
                .truncatedTo(ChronoUnit.MICROS);
        assertThat(finding.mutedUntil()).isEqualTo(expectedMutedUntil);

    }

    @Test
    @WithMockUser(username = "bob", roles = "USER")
    void mutingWithBlankReason_rerendersWithFieldError_andWritesNothing() throws Exception {
        long findingId = createFinding(siteA, "/page-2");
        LocalDate muteDate = LocalDate.now().plusDays(30);

        mvc.perform(post("/befunde/" + findingId + "/bewerten")
                        .with(csrf())
                        .param("aktion", "MUTED")
                        .param("grund", "   ")
                        .param("stummBis", muteDate.toString()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/befunde/" + findingId))
                .andExpect(flash().attributeExists("flashError"));

        Finding finding = findingService.byId(findingId).orElseThrow();
        assertThat(finding.triage()).isEqualTo(TriageStatus.UNTRIAGED);
        assertThat(finding.triageReason()).isNull();
        assertThat(finding.triagedBy()).isNull();
        assertThat(finding.mutedUntil()).isNull();
    }

    @Test
    @WithMockUser(username = "carol", roles = "USER")
    void mutingWithDateInPast_andDateBeyondMaximum_bothRerenderWithError() throws Exception {
        long findingId = createFinding(siteA, "/page-3");

        // Date in the past
        mvc.perform(post("/befunde/" + findingId + "/bewerten")
                        .with(csrf())
                        .param("aktion", "MUTED")
                        .param("grund", "Vergangenes Datum")
                        .param("stummBis", "2020-01-01"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/befunde/" + findingId))
                .andExpect(flash().attributeExists("flashError"));

        Finding fPast = findingService.byId(findingId).orElseThrow();
        assertThat(fPast.triage()).isEqualTo(TriageStatus.UNTRIAGED);

        // Date beyond maximum (e.g. 400 days in future)
        LocalDate tooFar = LocalDate.now().plusDays(findingProperties.maxMuteDays() + 30);
        mvc.perform(post("/befunde/" + findingId + "/bewerten")
                        .with(csrf())
                        .param("aktion", "MUTED")
                        .param("grund", "Zu weites Datum")
                        .param("stummBis", tooFar.toString()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/befunde/" + findingId))
                .andExpect(flash().attributeExists("flashError"));

        Finding fTooFar = findingService.byId(findingId).orElseThrow();
        assertThat(fTooFar.triage()).isEqualTo(TriageStatus.UNTRIAGED);
    }

    @Test
    @WithMockUser(username = "dave", roles = "USER")
    void acknowledgedWithNoReason_succeeds() throws Exception {
        long findingId = createFinding(siteA, "/page-4");

        mvc.perform(post("/befunde/" + findingId + "/bewerten")
                        .with(csrf())
                        .param("aktion", "ACKNOWLEDGED")
                        .param("grund", ""))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/befunde/" + findingId));

        Finding finding = findingService.byId(findingId).orElseThrow();
        assertThat(finding.triage()).isEqualTo(TriageStatus.ACKNOWLEDGED);
        assertThat(finding.triageReason()).isEmpty();
        assertThat(finding.triagedBy()).isEqualTo("dave");
        assertThat(finding.mutedUntil()).isNull();
    }

    @Test
    @WithMockUser(username = "eve", roles = "USER")
    void bulkPostWithThreeIds_movesThree_andFlashMessageReportsThree() throws Exception {
        long f1 = createFinding(siteA, "/bulk-1");
        long f2 = createFinding(siteA, "/bulk-2");
        long f3 = createFinding(siteA, "/bulk-3");

        mvc.perform(post("/websites/" + siteA + "/befunde/bewerten")
                        .with(csrf())
                        .param("aktion", "ACKNOWLEDGED")
                        .param("grund", "Sammelfreigabe")
                        .param("ids", String.valueOf(f1), String.valueOf(f2), String.valueOf(f3)))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/websites/" + siteA + "/befunde"))
                .andExpect(flash().attribute("flashMessage", containsString("3")));

        assertThat(findingService.byId(f1).orElseThrow().triage()).isEqualTo(TriageStatus.ACKNOWLEDGED);
        assertThat(findingService.byId(f2).orElseThrow().triage()).isEqualTo(TriageStatus.ACKNOWLEDGED);
        assertThat(findingService.byId(f3).orElseThrow().triage()).isEqualTo(TriageStatus.ACKNOWLEDGED);
    }

    @Test
    @WithMockUser(username = "frank", roles = "USER")
    void bulkPostContainingIdFromAnotherSite_movesOnlyIdsBelongingToPathSite() throws Exception {
        long fA = createFinding(siteA, "/site-a-page");
        long fB = createFinding(siteB, "/site-b-page");

        mvc.perform(post("/websites/" + siteA + "/befunde/bewerten")
                        .with(csrf())
                        .param("aktion", "ACKNOWLEDGED")
                        .param("ids", String.valueOf(fA), String.valueOf(fB)))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/websites/" + siteA + "/befunde"))
                .andExpect(flash().attribute("flashMessage", containsString("1")));

        assertThat(findingService.byId(fA).orElseThrow().triage()).isEqualTo(TriageStatus.ACKNOWLEDGED);
        assertThat(findingService.byId(fB).orElseThrow().triage()).isEqualTo(TriageStatus.UNTRIAGED);
    }

    @Test
    @WithMockUser(roles = "USER")
    void bulkPostWithMoreThanCap_isRejectedWith400() throws Exception {
        MockHttpServletRequestBuilder request = post("/websites/" + siteA + "/befunde/bewerten")
                .with(csrf())
                .param("aktion", "ACKNOWLEDGED");

        for (int i = 1; i <= 201; i++) {
            request.param("ids", String.valueOf(i));
        }

        mvc.perform(request)
                .andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser(roles = "USER")
    void bulkPostWithEmptySelection_isNoOpWithFlashMessage() throws Exception {
        mvc.perform(post("/websites/" + siteA + "/befunde/bewerten")
                        .with(csrf())
                        .param("aktion", "ACKNOWLEDGED"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/websites/" + siteA + "/befunde"))
                .andExpect(flash().attributeExists("flashMessage"));
    }
}
