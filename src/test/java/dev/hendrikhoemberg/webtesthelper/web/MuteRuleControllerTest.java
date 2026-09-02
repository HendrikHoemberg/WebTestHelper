package dev.hendrikhoemberg.webtesthelper.web;

import dev.hendrikhoemberg.webtesthelper.catalog.SiteForm;
import dev.hendrikhoemberg.webtesthelper.catalog.SiteService;
import dev.hendrikhoemberg.webtesthelper.findings.Finding;
import dev.hendrikhoemberg.webtesthelper.findings.FindingService;
import dev.hendrikhoemberg.webtesthelper.findings.MuteRule;
import dev.hendrikhoemberg.webtesthelper.findings.MuteRuleForm;
import dev.hendrikhoemberg.webtesthelper.findings.MuteRuleService;
import dev.hendrikhoemberg.webtesthelper.findings.persistence.MuteRuleEntity;
import dev.hendrikhoemberg.webtesthelper.findings.persistence.MuteRuleRepository;
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

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
class MuteRuleControllerTest extends AbstractPostgresTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    MuteRuleService muteRuleService;

    @Autowired
    MuteRuleRepository muteRuleRepository;

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

    private long siteA;
    private long siteB;
    private Instant now;

    @BeforeEach
    void setup() {
        jdbc.update("DELETE FROM notification");
        jdbc.update("DELETE FROM crawl_queue_item");
        jdbc.update("DELETE FROM finding_occurrence");
        jdbc.update("DELETE FROM finding");
        jdbc.update("DELETE FROM mute_rule");
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

    private long createFinding(long siteId, String subjectUrl, String locationPath) {
        RunEntity run = new RunEntity();
        run.setSiteId(siteId);
        run.setStatus(RunStatus.COMPLETED);
        run.setTriggerType(RunTrigger.MANUAL);
        run.setScope(RunScope.FULL);
        run.setPagesVisited(1);
        run.setPagesFailed(0);
        run.setPartialCoverage(false);
        run.setCoveredCheckTypes(RunScope.FULL.checkTypes().stream().map(CheckType::name).toList());
        run.setCoveredUrls(List.of("https://www.example.com" + locationPath));
        run.setStartedAt(now.minusSeconds(60));
        run.setFinishedAt(now.minusSeconds(30));
        run = runRepository.save(run);

        NormalizedUrl page = UrlNormalizer.normalize("https://www.example.com" + locationPath).orElseThrow();
        CheckFinding cf = new CheckFinding(CheckType.DEAD_LINK, Severity.ERROR, subjectUrl, page,
                "finding.DEAD_LINK.dead", List.of(subjectUrl, "404 Not Found"), Evidence.NONE);
        RunCoverage coverage = RunCoverage.of(
                RunScope.FULL,
                RunScope.FULL.checkTypes().stream().map(CheckType::name).toList(),
                List.of("https://www.example.com" + locationPath),
                List.of(),
                false);
        findingService.record(run.getId(), siteId, List.of(cf), coverage, now);

        return jdbc.queryForObject(
                "SELECT id FROM finding WHERE site_id = ? AND location_key = ?", Long.class, siteId, locationPath);
    }

    @Test
    @WithMockUser(username = "alice", roles = "USER")
    void userCreatesSiteScopedRule_appearsInListWithGermanDateExpiry() throws Exception {
        LocalDate expiryDate = LocalDate.now().plusDays(90);

        mvc.perform(post("/stummschaltungen")
                        .with(csrf())
                        .param("siteId", String.valueOf(siteA))
                        .param("checkType", "DEAD_LINK")
                        .param("subjectPattern", "*linkedin.com*")
                        .param("reason", "LinkedIn drosselt Anfragen")
                        .param("expiresAt", expiryDate.toString()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/stummschaltungen"));

        List<MuteRule> rules = muteRuleService.all();
        assertThat(rules).hasSize(1);
        MuteRule rule = rules.get(0);
        assertThat(rule.siteId()).isEqualTo(siteA);
        assertThat(rule.checkType()).isEqualTo(CheckType.DEAD_LINK);
        assertThat(rule.subjectPattern()).isEqualTo("*linkedin.com*");
        assertThat(rule.reason()).isEqualTo("LinkedIn drosselt Anfragen");
        assertThat(rule.createdBy()).isEqualTo("alice");

        // Format German date dd.MM.yyyy
        String expectedGermanDate = String.format("%02d.%02d.%04d",
                expiryDate.getDayOfMonth(), expiryDate.getMonthValue(), expiryDate.getYear());

        mvc.perform(get("/stummschaltungen"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("LinkedIn drosselt Anfragen")))
                .andExpect(content().string(containsString("Kunde A")))
                .andExpect(content().string(containsString(expectedGermanDate)))
                .andExpect(content().string(not(containsString("T23:59:59"))))
                .andExpect(content().string(not(containsString("T00:00:00"))));
    }

    @Test
    @WithMockUser(username = "alice", roles = "USER")
    void indexPageRendersExplanatoryHelpForSubjectAndLocationPatterns() throws Exception {
        mvc.perform(get("/stummschaltungen"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Betreff-Muster (Was ist der Fehler?)")))
                .andExpect(content().string(containsString("Fundort-Muster (Wo tritt der Fehler auf?)")));
    }

    @Test
    @WithMockUser(username = "bob", roles = "USER")
    void userCreatingGlobalRule_is403_andWritesNothing() throws Exception {
        LocalDate expiryDate = LocalDate.now().plusDays(90);

        mvc.perform(post("/stummschaltungen")
                        .with(csrf())
                        .param("siteId", "")
                        .param("checkType", "DEAD_LINK")
                        .param("subjectPattern", "*linkedin.com*")
                        .param("reason", "Globale Regel")
                        .param("expiresAt", expiryDate.toString()))
                .andExpect(status().isForbidden());

        assertThat(muteRuleService.all()).isEmpty();
    }

    @Test
    @WithMockUser(username = "charlie_admin", roles = "ADMIN")
    void adminCreatingGlobalRule_succeeds() throws Exception {
        LocalDate expiryDate = LocalDate.now().plusDays(90);

        mvc.perform(post("/stummschaltungen")
                        .with(csrf())
                        .param("siteId", "")
                        .param("checkType", "DEAD_LINK")
                        .param("subjectPattern", "*linkedin.com*")
                        .param("reason", "Globale Regel durch Admin")
                        .param("expiresAt", expiryDate.toString()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/stummschaltungen"));

        List<MuteRule> rules = muteRuleService.all();
        assertThat(rules).hasSize(1);
        MuteRule rule = rules.get(0);
        assertThat(rule.siteId()).isNull();
        assertThat(rule.reason()).isEqualTo("Globale Regel durch Admin");
        assertThat(rule.createdBy()).isEqualTo("charlie_admin");
    }

    @Test
    @WithMockUser(username = "dan_user", roles = "USER")
    void userDeletingGlobalRuleIs403_deletingSiteRuleSucceedsAndUnmutesFindings() throws Exception {
        // Setup: Admin creates a global rule
        long globalRuleId = muteRuleService.create(new MuteRuleForm(
                null, CheckType.DEAD_LINK, "*twitter.com*", null, "Global Twitter",
                now.plus(30, ChronoUnit.DAYS)), "admin", now);

        // USER tries to delete global rule -> 403 Forbidden
        mvc.perform(post("/stummschaltungen/" + globalRuleId + "/loeschen")
                        .with(csrf()))
                .andExpect(status().isForbidden());

        assertThat(muteRuleService.byId(globalRuleId)).isPresent();

        // Setup: Finding on site A
        long findingId = createFinding(siteA, "https://linkedin.com/page", "/team");
        Finding fBefore = findingService.byId(findingId).orElseThrow();
        assertThat(fBefore.triage()).isEqualTo(TriageStatus.UNTRIAGED);

        // Create site rule on site A that mutes this finding
        long siteRuleId = muteRuleService.create(new MuteRuleForm(
                siteA, CheckType.DEAD_LINK, "*linkedin.com*", null, "Site LinkedIn",
                now.plus(30, ChronoUnit.DAYS)), "dan_user", now);

        Finding fMuted = findingService.byId(findingId).orElseThrow();
        assertThat(fMuted.triage()).isEqualTo(TriageStatus.MUTED);
        assertThat(fMuted.mutedByRuleId()).isEqualTo(siteRuleId);

        // USER deletes site rule -> succeeds and un-mutes finding
        mvc.perform(post("/stummschaltungen/" + siteRuleId + "/loeschen")
                        .with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/stummschaltungen"));

        assertThat(muteRuleService.byId(siteRuleId)).isEmpty();

        Finding fAfter = findingService.byId(findingId).orElseThrow();
        assertThat(fAfter.triage()).isEqualTo(TriageStatus.UNTRIAGED);
        assertThat(fAfter.mutedByRuleId()).isNull();
    }

    @Test
    @WithMockUser(roles = "USER")
    void muteFormOffersPatternChipsAndDatalistSuggestions() throws Exception {
        mvc.perform(get("/stummschaltungen"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("id=\"betreff-muster-vorschlaege\"")))
                .andExpect(content().string(containsString("id=\"fundort-muster-vorschlaege\"")))
                .andExpect(content().string(containsString("data-muster=\"*/archiv/*\"")))
                .andExpect(content().string(containsString("data-muster=\"*linkedin.com*\"")))
                .andExpect(content().string(containsString("Aus Feststellungen übernehmen")));
    }

    @Test
    @WithMockUser(roles = "USER")
    void muteFormWithoutPattern_showsNeutralPreviewHintInsteadOfNull() throws Exception {
        mvc.perform(get("/stummschaltungen"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Geben Sie ein Muster ein")))
                .andExpect(content().string(not(containsString("null Feststellungen"))));
    }

    @Test
    @WithMockUser(roles = "USER")
    void auswahlListsOpenFindingsAsPrefillButtons() throws Exception {
        createFinding(siteA, "https://linkedin.com/user1", "/team");
        createFinding(siteA, "https://facebook.com/user2", "/archiv");

        mvc.perform(get("/stummschaltungen/auswahl")
                        .param("siteId", String.valueOf(siteA)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Tote Links")))
                .andExpect(content().string(containsString("data-check-type=\"DEAD_LINK\"")))
                .andExpect(content().string(containsString("data-location-muster=\"*/team*\"")))
                .andExpect(content().string(containsString("data-location-muster=\"*/archiv*\"")));
    }

    @Test
    @WithMockUser(roles = "USER")
    void auswahlExcludesAlreadyMutedFindings() throws Exception {
        createFinding(siteA, "https://linkedin.com/user1", "/team");
        muteRuleService.create(new MuteRuleForm(
                siteA, CheckType.DEAD_LINK, "*linkedin.com*", null, "bereits stumm",
                now.plus(30, ChronoUnit.DAYS)), "alice", now);

        mvc.perform(get("/stummschaltungen/auswahl")
                        .param("siteId", String.valueOf(siteA)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Keine offenen Feststellungen")))
                .andExpect(content().string(not(containsString("data-location-muster=\"*/team*\""))));
    }

    @Test
    @WithMockUser(roles = "USER")
    void auswahlWithoutSiteShowsHintInsteadOfFindings() throws Exception {
        createFinding(siteA, "https://linkedin.com/user1", "/team");

        mvc.perform(get("/stummschaltungen/auswahl"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Zuerst eine Website auswählen")))
                .andExpect(content().string(not(containsString("data-location-muster"))));
    }

    @Test
    @WithMockUser(roles = "USER")
    void previewReturnsFragmentWithMatchingCount_andZeroForNoMatch() throws Exception {
        createFinding(siteA, "https://linkedin.com/user1", "/page1");
        createFinding(siteA, "https://linkedin.com/user2", "/page2");
        createFinding(siteA, "https://facebook.com/user3", "/page3");

        // Preview matching *linkedin.com* on siteA -> count = 2
        mvc.perform(get("/stummschaltungen/vorschau")
                        .param("siteId", String.valueOf(siteA))
                        .param("subjectPattern", "*linkedin.com*"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("2")));

        // Preview matching something non-existent -> count = 0, no error
        mvc.perform(get("/stummschaltungen/vorschau")
                        .param("siteId", String.valueOf(siteA))
                        .param("subjectPattern", "*nonexistent*"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("0")));
    }

    @Test
    @WithMockUser(roles = "USER")
    void expiredRuleIsListed_visiblyMarked_andCannotBeEdited() throws Exception {
        // Create an expired rule directly in repository
        MuteRuleEntity expiredEntity = new MuteRuleEntity();
        expiredEntity.setSiteId(siteA);
        expiredEntity.setCheckType(CheckType.DEAD_LINK);
        expiredEntity.setSubjectPattern("*old.example.com*");
        expiredEntity.setReason("Alte Aktion abgelaufen");
        expiredEntity.setCreatedBy("past_user");
        expiredEntity.setExpiresAt(now.minus(10, ChronoUnit.DAYS));
        expiredEntity.setExpiredAt(now.minus(10, ChronoUnit.DAYS));
        expiredEntity.setCreatedAt(now.minus(40, ChronoUnit.DAYS));
        muteRuleRepository.save(expiredEntity);

        mvc.perform(get("/stummschaltungen"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Alte Aktion abgelaufen")))
                .andExpect(content().string(containsString("Abgelaufen")))
                // Ensure no edit affordance exists for rules
                .andExpect(content().string(not(containsString("bearbeiten"))))
                .andExpect(content().string(not(containsString("/stummschaltungen/" + expiredEntity.getId() + "/bearbeiten"))));
    }
}
