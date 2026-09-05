package dev.hendrikhoemberg.webtesthelper.challenger;

import dev.hendrikhoemberg.webtesthelper.auth.AppUserService;
import dev.hendrikhoemberg.webtesthelper.findings.OpenFindingCounts;
import dev.hendrikhoemberg.webtesthelper.model.RunScope;
import dev.hendrikhoemberg.webtesthelper.model.RunStatus;
import dev.hendrikhoemberg.webtesthelper.reporting.DashboardService;
import dev.hendrikhoemberg.webtesthelper.reporting.DashboardView;
import dev.hendrikhoemberg.webtesthelper.reporting.SiteTile;
import dev.hendrikhoemberg.webtesthelper.reporting.TrafficLight;
import dev.hendrikhoemberg.webtesthelper.runner.DashboardProperties;
import dev.hendrikhoemberg.webtesthelper.runner.LastRun;
import dev.hendrikhoemberg.webtesthelper.runner.SystemCapacity;
import dev.hendrikhoemberg.webtesthelper.scheduling.Schedule;
import dev.hendrikhoemberg.webtesthelper.web.DashboardController;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Adversarial UI & Convention Verification Suite for Milestone 3:
 * - CONV-02: Boundary verification of negative durations in relativzeit.html (overdue vs clock skew).
 * - CONV-03 & UX-02: String concatenation elimination and message key resolution.
 * - CONV-04 & UX-03: Proper th:style syntax in befundzeile.html and context path URLs in druck.html.
 * - UX-06: Severities/open counts mapping to localized warning labels.
 * - UX-08: Accessible name aria-label attributes on interactive controls.
 */
@org.junit.jupiter.api.parallel.ResourceLock("spring-context")
@WebMvcTest(DashboardController.class)
class Milestone3UiConventionsAdversarialTest {

    @Autowired
    MockMvc mvc;

    @MockitoBean
    DashboardService dashboardService;

    @MockitoBean
    DashboardProperties dashboardProperties;

    @MockitoBean
    AppUserService appUserService;

    @BeforeEach
    void setupProperties() {
        when(dashboardProperties.pollInterval()).thenReturn(Duration.ofSeconds(30));
    }

    private DashboardView createViewWithNextFireAt(Instant nextFireAt) {
        Schedule schedule = (nextFireAt != null)
                ? new Schedule(10L, 1L, RunScope.FULL, "0 3 * * *", "Europe/Berlin", true,
                Instant.now().minus(Duration.ofHours(24)), nextFireAt)
                : null;

        SiteTile tile = new SiteTile(
                1L,
                "Test Site",
                "https://test.example.com/",
                true,
                TrafficLight.GRUEN,
                new LastRun(1L, 101L, RunStatus.COMPLETED, Instant.now().minus(Duration.ofHours(1)), false),
                new OpenFindingCounts(0, 0, 0, 0),
                schedule
        );

        return new DashboardView(
                List.of(tile),
                new OpenFindingCounts(0, 0, 0, 0),
                0,
                nextFireAt,
                false,
                new SystemCapacity(2, 0, 4, 0, Duration.ofSeconds(30), 5)
        );
    }

    // =========================================================================
    // 1. CONV-02: Relativzeit Negative Duration Boundaries
    // =========================================================================
    @Nested
    @DisplayName("CONV-02: Relativzeit Negative Duration Boundary Verification")
    class RelativzeitBoundaryTests {

        @ParameterizedTest(name = "Past duration {0}s within 60s clock skew emits 'in Kürze'")
        @ValueSource(longs = {5, 15, 30, 45, 55})
        @WithMockUser(roles = "USER")
        void pastDurationWithinClockSkewEmitsInKuerze(long pastSeconds) throws Exception {
            Instant nextFireAt = Instant.now().minus(Duration.ofSeconds(pastSeconds));
            when(dashboardService.overview()).thenReturn(createViewWithNextFireAt(nextFireAt));

            mvc.perform(get("/uebersicht/kacheln"))
                    .andExpect(status().isOk())
                    .andExpect(content().string(containsString("<span>in Kürze</span>")))
                    .andExpect(content().string(not(containsString("<span>überfällig</span>"))))
                    .andExpect(content().string(not(containsString("<span>-</span>"))));
        }

        @ParameterizedTest(name = "Past duration {0}s >= 60s overdue emits 'überfällig'")
        @ValueSource(longs = {65, 90, 120, 3600, 7200, 86400})
        @WithMockUser(roles = "USER")
        void pastDurationOverdueEmitsUeberfaellig(long pastSeconds) throws Exception {
            Instant nextFireAt = Instant.now().minus(Duration.ofSeconds(pastSeconds));
            when(dashboardService.overview()).thenReturn(createViewWithNextFireAt(nextFireAt));

            mvc.perform(get("/uebersicht/kacheln"))
                    .andExpect(status().isOk())
                    .andExpect(content().string(containsString("<span>überfällig</span>")))
                    // CRITICAL: Must NEVER emit "in Kürze" for overdue schedules!
                    .andExpect(content().string(not(containsString("<span>in Kürze</span>"))))
                    // Must NEVER evaluate negative numbers into future units like "in 1 Stunde" or "in 1 Tag"
                    .andExpect(content().string(not(containsString("in 1 Stunde"))))
                    .andExpect(content().string(not(containsString("in 1 Tag"))))
                    .andExpect(content().string(not(containsString("in 2 Tagen"))))
                    .andExpect(content().string(not(containsString("in -"))));
        }

        @ParameterizedTest(name = "Future duration {0}s emits correct localized unit '{1}'")
        @CsvSource({
                "15, in Kürze",
                "90, in 1 Minute",
                "305, in 5 Minuten",
                "3900, in 1 Stunde",
                "7500, in 2 Stunden",
                "93600, in 1 Tag",
                "180000, in 2 Tagen"
        })
        @WithMockUser(roles = "USER")
        void futureDurationsEmitExpectedGermanUnits(long futureSeconds, String expectedText) throws Exception {
            Instant nextFireAt = Instant.now().plus(Duration.ofSeconds(futureSeconds));
            when(dashboardService.overview()).thenReturn(createViewWithNextFireAt(nextFireAt));

            mvc.perform(get("/uebersicht/kacheln"))
                    .andExpect(status().isOk())
                    .andExpect(content().string(containsString(expectedText)))
                    .andExpect(content().string(not(containsString("überfällig"))));
        }

        @Test
        @WithMockUser(roles = "USER")
        void nullScheduleRendersGracefullyWithoutNextRun() throws Exception {
            when(dashboardService.overview()).thenReturn(createViewWithNextFireAt(null));

            mvc.perform(get("/uebersicht/kacheln"))
                    .andExpect(status().isOk())
                    .andExpect(content().string(not(containsString("Nächster Lauf"))));
        }
    }

    // =========================================================================
    // 2. UI Polish & Template Integrity Checks
    // =========================================================================
    @Nested
    @DisplayName("UI Polish, Syntax & Convention Verification")
    class TemplateConventionsTests {

        @Test
        void befundzeileTemplateUsesThStyleRatherThanRawStyle() throws IOException {
            ClassPathResource resource = new ClassPathResource("templates/fragments/befundzeile.html");
            String content = new String(resource.getInputStream().readAllBytes(), StandardCharsets.UTF_8);

            // Must NOT contain raw unparsed style="${...}"
            assertThat(content).doesNotContain(" style=\"${");
            // Must contain th:style
            assertThat(content).contains("th:style=\"${auswaehlbar ? 'margin-left: 1.5rem;' : 'margin-left: 0;'}\"");
        }

        @Test
        void journeyListAndDetailDoNotContainHardcodedGermanStrings() throws IOException {
            ClassPathResource listResource = new ClassPathResource("templates/journey/list.html");
            String listContent = new String(listResource.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            assertThat(listContent).doesNotContain("+ ' Schritte'");
            assertThat(listContent).contains("ui.journey.tabelle.schritte_anzahl");

            ClassPathResource detailResource = new ClassPathResource("templates/journey/detail.html");
            String detailContent = new String(detailResource.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            assertThat(detailContent).doesNotContain("+ ' s'");
            assertThat(detailContent).doesNotContain("+ ' ms'");
            assertThat(detailContent).contains("ui.journey.detail.schritte.timeout_sekunden");
            assertThat(detailContent).contains("ui.journey.detail.schritte.timeout_millisekunden");
        }

        @Test
        void systemlastFragmentDoesNotContainHardcodedBelegtString() throws IOException {
            ClassPathResource resource = new ClassPathResource("templates/fragments/systemlast.html");
            String content = new String(resource.getInputStream().readAllBytes(), StandardCharsets.UTF_8);

            assertThat(content).doesNotContain("+ ' belegt'");
            assertThat(content).contains("ui.einstellungen.systemlast.anteil_belegt");
        }

        @Test
        void druckTemplateUsesContextPathThHref() throws IOException {
            ClassPathResource resource = new ClassPathResource("templates/laeufe/druck.html");
            String content = new String(resource.getInputStream().readAllBytes(), StandardCharsets.UTF_8);

            assertThat(content).contains("th:href=\"@{/css/app.css}\"");
            assertThat(content).doesNotContain("th:href=\"|/laeufe/");
            assertThat(content).contains("th:href=\"@{/laeufe/{id}(id=${run.id})}\"");
            assertThat(content).contains("th:href=\"@{/laeufe/{id}/bericht/pdf(id=${run.id})}\"");
        }

        @Test
        void websitesOverviewUsesWarnungenKeyForWarnings() throws IOException {
            ClassPathResource resource = new ClassPathResource("templates/websites/uebersicht.html");
            String content = new String(resource.getInputStream().readAllBytes(), StandardCharsets.UTF_8);

            assertThat(content).contains("#{ui.websites.detail.status.warnungen}");
        }

        @Test
        void accessibilityAriaLabelsArePresentOnButtonsAndCheckboxes() throws IOException {
            ClassPathResource layoutRes = new ClassPathResource("templates/layout.html");
            String layout = new String(layoutRes.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            assertThat(layout).contains("th:attr=\"aria-label=#{ui.tutorial.schaltflaeche.neustart}\"");
            assertThat(layout).contains("th:attr=\"aria-label=#{ui.nav.abmelden}\"");

            ClassPathResource befundzeileRes = new ClassPathResource("templates/fragments/befundzeile.html");
            String befundzeile = new String(befundzeileRes.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            assertThat(befundzeile).contains("th:attr=\"aria-label=${befund.title}\"");

            ClassPathResource befundeRes = new ClassPathResource("templates/websites/befunde.html");
            String befunde = new String(befundeRes.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            assertThat(befunde).contains("th:attr=\"aria-label=#{ui.befunde.liste.alle_auswaehlen}\"");

            ClassPathResource stummRes = new ClassPathResource("templates/stummschaltungen/index.html");
            String stumm = new String(stummRes.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            assertThat(stumm).contains("th:attr=\"aria-label=#{ui.stummschaltungen.modus.placeholder}\"");
        }

        @Test
        void allMilestone3MessageKeysExistInMessagesBundle() throws IOException {
            ClassPathResource resource = new ClassPathResource("messages.properties");
            Properties props = new Properties();
            props.load(resource.getInputStream());

            String[] expectedKeys = {
                    "ui.uebersicht.naechster.ueberfaellig",
                    "ui.uebersicht.naechster.kurz",
                    "ui.journey.tabelle.schritte_anzahl",
                    "ui.journey.detail.schritte.timeout_sekunden",
                    "ui.journey.detail.schritte.timeout_millisekunden",
                    "ui.einstellungen.systemlast.anteil_belegt",
                    "ui.befund.detail.breadcrumbs.lauf",
                    "ui.websites.detail.status.warnungen",
                    "ui.befund.detail.schweregrad",
                    "ui.befund.detail.status",
                    "ui.befund.detail.screenshot_alt",
                    "ui.befund.detail.zurueck_zu_befunden",
                    "ui.befunde.liste.alle_auswaehlen",
                    "ui.stummschaltungen.modus.placeholder"
            };

            for (String key : expectedKeys) {
                assertThat(props.getProperty(key))
                        .as("Missing message bundle key: '%s'", key)
                        .isNotBlank();
            }
        }
    }
}
