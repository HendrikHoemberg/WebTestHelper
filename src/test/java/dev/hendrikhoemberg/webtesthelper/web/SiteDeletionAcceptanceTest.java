package dev.hendrikhoemberg.webtesthelper.web;

import dev.hendrikhoemberg.webtesthelper.catalog.SiteForm;
import dev.hendrikhoemberg.webtesthelper.catalog.SiteService;
import dev.hendrikhoemberg.webtesthelper.findings.FindingService;
import dev.hendrikhoemberg.webtesthelper.findings.MuteRuleForm;
import dev.hendrikhoemberg.webtesthelper.findings.MuteRuleService;
import dev.hendrikhoemberg.webtesthelper.model.CheckFinding;
import dev.hendrikhoemberg.webtesthelper.model.CheckType;
import dev.hendrikhoemberg.webtesthelper.model.Evidence;
import dev.hendrikhoemberg.webtesthelper.model.NormalizedUrl;
import dev.hendrikhoemberg.webtesthelper.model.RunCoverage;
import dev.hendrikhoemberg.webtesthelper.model.RunScope;
import dev.hendrikhoemberg.webtesthelper.model.RunStatus;
import dev.hendrikhoemberg.webtesthelper.model.RunTrigger;
import dev.hendrikhoemberg.webtesthelper.model.Severity;
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
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The last word on site deletion (help/zugangsdaten.md: "Wird eine Website gelöscht, werden
 * auch alle zugehörigen Zugangsdaten automatisch entfernt"): the FK cascades carry every
 * dependent row with the site — credentials, journeys, runs, findings, site-scoped mute
 * rules, schedules, recipients and check settings — while global mute rules (site_id NULL)
 * and other sites are untouched.
 */
@AutoConfigureMockMvc
class SiteDeletionAcceptanceTest extends AbstractPostgresTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    SiteService siteService;

    @Autowired
    ScheduleService scheduleService;

    @Autowired
    RunRepository runRepository;

    @Autowired
    FindingService findingService;

    @Autowired
    MuteRuleService muteRuleService;

    private long siteA;
    private long siteB;
    private Instant now;

    @BeforeEach
    void setup() {
        jdbc.update("DELETE FROM notification");
        jdbc.update("DELETE FROM notification_recipient");
        jdbc.update("DELETE FROM crawl_queue_item");
        jdbc.update("DELETE FROM finding_occurrence");
        jdbc.update("DELETE FROM finding");
        jdbc.update("DELETE FROM mute_rule");
        jdbc.update("DELETE FROM run");
        jdbc.update("DELETE FROM schedule");
        jdbc.update("DELETE FROM site_check_setting");
        jdbc.update("DELETE FROM credential");
        jdbc.update("DELETE FROM journey");
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

    private long createFinding(long siteId) {
        RunEntity run = new RunEntity();
        run.setSiteId(siteId);
        run.setStatus(RunStatus.COMPLETED);
        run.setTriggerType(RunTrigger.MANUAL);
        run.setScope(RunScope.FULL);
        run.setPagesVisited(1);
        run.setPagesFailed(0);
        run.setPartialCoverage(false);
        run.setCoveredCheckTypes(RunScope.FULL.checkTypes().stream().map(CheckType::name).toList());
        run.setCoveredUrls(List.of("https://www.example.com/kontakt"));
        run.setStartedAt(now.minusSeconds(60));
        run.setFinishedAt(now.minusSeconds(30));
        run = runRepository.save(run);

        NormalizedUrl page = UrlNormalizer.normalize("https://www.example.com/kontakt").orElseThrow();
        CheckFinding cf = new CheckFinding(CheckType.DEAD_LINK, Severity.ERROR, "https://lauf.de/404",
                page, "finding.DEAD_LINK.dead", List.of("https://lauf.de/404", "404 Not Found"), Evidence.NONE);
        RunCoverage coverage = RunCoverage.of(
                RunScope.FULL,
                RunScope.FULL.checkTypes().stream().map(CheckType::name).toList(),
                List.of("https://www.example.com/kontakt"),
                List.of(),
                false);
        findingService.record(run.getId(), siteId, List.of(cf), coverage, now);

        return jdbc.queryForObject(
                "SELECT id FROM finding WHERE site_id = ?", Long.class, siteId);
    }

    private long count(String table) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table, Long.class);
    }

    @Test
    @WithMockUser(username = "admin", roles = "ADMIN")
    void adminDeletingSiteRemovesAllRelatedRowsAndShowsFlash() throws Exception {
        jdbc.update("INSERT INTO credential (site_id, name, username, secret) VALUES (?, 'login', 'alice', 'cipher')", siteA);
        jdbc.update("INSERT INTO notification_recipient (site_id, email) VALUES (?, 'team@example.com')", siteA);
        long findingId = createFinding(siteA);
        muteRuleService.create(new MuteRuleForm(
                siteA, CheckType.DEAD_LINK, "*lauf.de*", null, "Kaputte Seite",
                now.plus(30, ChronoUnit.DAYS)), "admin", now);
        muteRuleService.create(new MuteRuleForm(
                null, CheckType.DEAD_LINK, "*twitter.com*", null, "Global",
                now.plus(30, ChronoUnit.DAYS)), "admin", now);

        assertThat(count("credential")).isEqualTo(1);
        assertThat(count("finding")).isEqualTo(1);
        assertThat(count("mute_rule")).isEqualTo(2);

        mvc.perform(post("/websites/" + siteA + "/loeschen").with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/websites"))
                .andExpect(flash().attribute("flashMessage", containsString("Kunde A")));

        assertThat(count("site")).isEqualTo(1);
        assertThat(count("credential")).isZero();
        assertThat(count("notification_recipient")).isZero();
        assertThat(count("run")).isZero();
        assertThat(count("finding")).isZero();
        assertThat(count("schedule")).isEqualTo(3);
        assertThat(count("site_check_setting")).isEqualTo(siteCheckSettingsCount(siteB));
        assertThat(count("mute_rule")).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM mute_rule WHERE site_id IS NULL", Long.class)).isEqualTo(1L);

        mvc.perform(get("/websites"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Kunde B")))
                .andExpect(content().string(not(containsString("/websites/" + siteA))))
                .andExpect(content().string(not(containsString("wurde gelöscht"))));
    }

    private long siteCheckSettingsCount(long siteId) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM site_check_setting WHERE site_id = ?", Long.class, siteId);
    }

    @Test
    @WithMockUser(roles = "USER")
    void userDeletingSiteIsForbiddenAndSiteStays() throws Exception {
        jdbc.update("INSERT INTO credential (site_id, name, username, secret) VALUES (?, 'login', 'alice', 'cipher')", siteA);

        mvc.perform(post("/websites/" + siteA + "/loeschen").with(csrf()))
                .andExpect(status().isForbidden());

        assertThat(count("site")).isEqualTo(2);
        assertThat(count("credential")).isEqualTo(1);
    }

    @org.junit.jupiter.api.AfterEach
    void tearDown() {
        jdbc.update("DELETE FROM notification");
        jdbc.update("DELETE FROM notification_recipient");
        jdbc.update("DELETE FROM crawl_queue_item");
        jdbc.update("DELETE FROM finding_occurrence");
        jdbc.update("DELETE FROM finding");
        jdbc.update("DELETE FROM mute_rule");
        jdbc.update("DELETE FROM run");
        jdbc.update("DELETE FROM schedule");
        jdbc.update("DELETE FROM site_check_setting");
        jdbc.update("DELETE FROM credential");
        jdbc.update("DELETE FROM journey");
        jdbc.update("DELETE FROM site");
    }
}
