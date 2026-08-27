package dev.hendrikhoemberg.webtesthelper.web;

import dev.hendrikhoemberg.webtesthelper.catalog.SiteForm;
import dev.hendrikhoemberg.webtesthelper.catalog.SiteService;
import dev.hendrikhoemberg.webtesthelper.crawler.ProbeEvidence;
import dev.hendrikhoemberg.webtesthelper.crawler.SetupProbe;
import dev.hendrikhoemberg.webtesthelper.findings.FindingProperties;
import dev.hendrikhoemberg.webtesthelper.findings.FindingService;
import dev.hendrikhoemberg.webtesthelper.findings.TriageAction;
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
import dev.hendrikhoemberg.webtesthelper.runner.SetupProbeService;
import dev.hendrikhoemberg.webtesthelper.runner.persistence.RunEntity;
import dev.hendrikhoemberg.webtesthelper.runner.persistence.RunRepository;
import dev.hendrikhoemberg.webtesthelper.support.AbstractPostgresTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The only place plan 9's two features meet: a site is added through guided setup, and the
 * dashboard's tile then says so — plan 7's mute model on plan 9's screen, end to end.
 *
 * <p>The probe is stubbed because task 7 already proves it against real Chromium and the fixture;
 * repeating that here would buy a second ninety-second sweep to re-learn a fact one test already
 * knows. Everything else is real: the site catalog, {@link SetupProbeService}, {@link FindingService}
 * and the dashboard queries.
 */
@AutoConfigureMockMvc
@WithMockUser(roles = "ADMIN")
class DashboardAcceptanceTest extends AbstractPostgresTest {

    private static final String SITE_NAME = "Onboarding Kunde";
    private static final String BASE_URL = "https://www.example.com/";

    @Autowired
    MockMvc mvc;

    @Autowired
    SiteService siteService;

    @Autowired
    FindingService findingService;

    @Autowired
    FindingProperties findingProperties;

    @Autowired
    RunRepository runRepository;

    @Autowired
    JdbcTemplate jdbc;

    @MockitoBean
    SetupProbe setupProbe;

    @BeforeEach
    void resetTables() {
        jdbc.update("DELETE FROM mute_rule");
        jdbc.update("DELETE FROM notification");
        jdbc.update("DELETE FROM crawl_queue_item");
        jdbc.update("DELETE FROM finding_occurrence");
        jdbc.update("DELETE FROM finding");
        jdbc.update("DELETE FROM run");
        jdbc.update("DELETE FROM site_check_setting");
        jdbc.update("DELETE FROM site");
    }

    @Test
    void addingASiteWalksTheWholePlanAndTheDashboardSaysSo() throws Exception {
        when(setupProbe.probe(any())).thenReturn(evidence());

        // 1. POST /websites (ADMIN) creates and redirects to the guided-setup wizard.
        MvcResult createResult = mvc.perform(post("/websites")
                        .with(csrf())
                        .param("name", SITE_NAME)
                        .param("baseUrl", BASE_URL)
                        .param("maxPages", "100")
                        .param("maxDepth", "3")
                        .param("maxDurationMinutes", "10")
                        .param("respectRobots", "true")
                        .param("enabled", "true"))
                .andExpect(status().is3xxRedirection())
                .andReturn();

        String redirect = createResult.getResponse().getRedirectedUrl();
        assertThat(redirect).isNotNull().startsWith("/websites/").endsWith("/einrichtung");
        long siteId = Long.parseLong(redirect.substring("/websites/".length()).split("/")[0]);

        // A browser follows the create redirect; the wizard shell is what starts the probe.
        mvc.perform(get("/websites/" + siteId + "/einrichtung"))
                .andExpect(status().isOk());

        // 2. GET .../einrichtung/stand eventually renders the proposal with SITEMAP_CONSISTENCY
        //    ticked — the one check SiteService.create seeds OFF, so a ticked box proves the probe
        //    changed the configuration rather than echoing the seeded defaults.
        String stand = awaitFertigProposal(siteId);
        assertThat(stand).contains("value=\"SITEMAP_CONSISTENCY\" checked=\"checked\"");
        assertThat(stand).contains("sitemap.xml gefunden");

        // 3. POST .../einrichtung with the proposal's (suggested) checks: the form is authoritative,
        //    and the site's context reports exactly what was confirmed.
        MockHttpServletRequestBuilder apply = post("/websites/" + siteId + "/einrichtung").with(csrf());
        for (CheckType type : CheckType.values()) {
            if (type != CheckType.CONSOLE_ERRORS) {
                apply.param("aktiv", type.name());
            }
        }
        mvc.perform(apply)
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/websites/" + siteId));

        var context = siteService.contextFor(siteId);
        for (CheckType type : CheckType.values()) {
            assertThat(context.enabled(type))
                    .describedAs("Check " + type + " after confirming the proposal")
                    .isEqualTo(type != CheckType.CONSOLE_ERRORS);
        }
        assertThat(context.enabled(CheckType.SITEMAP_CONSISTENCY)).isTrue();

        // 4. GET / — the new site's tile is GELB (nothing has finished yet), with no finding counts.
        String dashboard = mvc.perform(get("/"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(dashboard).contains(SITE_NAME);
        assertThat(dashboard).contains("ampel-gelb");
        assertThat(dashboard).doesNotContain("kennzahl-link");

        // 5. Seed a COMPLETED run and one UNTRIAGED ERROR finding; the polled tile grid shows ROT
        //    with "1 Fehler".
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
        run.setStartedAt(Instant.now().minusSeconds(60));
        run.setFinishedAt(Instant.now().minusSeconds(30));
        run = runRepository.save(run);

        NormalizedUrl page = UrlNormalizer.normalize("https://www.example.com/kontakt").orElseThrow();
        CheckFinding error = new CheckFinding(CheckType.DEAD_LINK, Severity.ERROR,
                "https://www.example.com/dead-link", page, "finding.DEAD_LINK.dead",
                List.of("https://www.example.com/dead-link", "404 Not Found"), Evidence.NONE);
        findingService.record(run.getId(), siteId, List.of(error),
                RunCoverage.of(run.getScope(), run.getCoveredCheckTypes(), run.getCoveredUrls(), List.of(), false),
                Instant.now());

        String grid = mvc.perform(get("/uebersicht/kacheln"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(grid).contains("ampel-rot");
        assertThat(grid).contains("kennzahl-fehler");
        assertThat(grid).contains("1 Fehler");

        // 6. Mute that finding with a reason and an expiry; D62 end to end — the tile goes GRUEN.
        List<Long> findingIds = jdbc.queryForList(
                "SELECT id FROM finding WHERE site_id = ?", Long.class, siteId);
        assertThat(findingIds).hasSize(1);
        long findingId = findingIds.get(0);
        Instant now = Instant.now();
        findingService.triage(siteId, List.of(findingId),
                TriageAction.of(TriageStatus.MUTED, "Anbieter arbeitet an der Adresse, wird behoben",
                        now.plus(1, ChronoUnit.DAYS), now, findingProperties.maxMuteDays()),
                "test", now);

        String mutedGrid = mvc.perform(get("/uebersicht/kacheln"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(mutedGrid).contains("ampel-gruen");
        assertThat(mutedGrid).doesNotContain("kennzahl-fehler");

        // 7. Disable the site; the tile is GRAU and carries no counts at all.
        siteService.update(siteId, new SiteForm(SITE_NAME, BASE_URL, 100, 3,
                Duration.ofMinutes(10), List.of(), List.of(), true, null, false));

        String grauGrid = mvc.perform(get("/uebersicht/kacheln"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(grauGrid).contains("ampel-grau");
        assertThat(grauGrid).contains("Website ist deaktiviert.");
        assertThat(grauGrid).doesNotContain("kennzahl-link");
    }

    /** Bounded poll with deadline (CLAUDE.md: never a bare assertion sleep) until the proposal is FERTIG. */
    private String awaitFertigProposal(long siteId) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            MvcResult result = mvc.perform(get("/websites/" + siteId + "/einrichtung/stand"))
                    .andExpect(status().isOk())
                    .andReturn();
            String body = result.getResponse().getContentAsString();
            if (body.contains("value=\"SITEMAP_CONSISTENCY\" checked=\"checked\"")) {
                return body;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("Vorschlag für Website " + siteId + " blieb läuft statt fertig");
    }

    private static ProbeEvidence evidence() {
        return new ProbeEvidence(true, null,
                List.of(BASE_URL, "https://www.example.com/kontakt"),
                List.of("https://www.example.com/kontakt"),
                List.of("https://www.example.com/medien"),
                List.of("https://www.example.com/karte"),
                Set.of("de", "en"),
                List.of("https://www.example.com/dateien/handbuch.pdf"),
                true, true);
    }
}
