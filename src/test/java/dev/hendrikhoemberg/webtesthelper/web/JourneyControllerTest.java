package dev.hendrikhoemberg.webtesthelper.web;

import dev.hendrikhoemberg.webtesthelper.catalog.JourneyHealth;
import dev.hendrikhoemberg.webtesthelper.catalog.JourneyHealthService;
import dev.hendrikhoemberg.webtesthelper.catalog.JourneyService;
import dev.hendrikhoemberg.webtesthelper.catalog.SiteService;
import dev.hendrikhoemberg.webtesthelper.model.AssertionType;
import dev.hendrikhoemberg.webtesthelper.model.CrawlBudget;
import dev.hendrikhoemberg.webtesthelper.model.JourneyDefinition;
import dev.hendrikhoemberg.webtesthelper.model.JourneyStep;
import dev.hendrikhoemberg.webtesthelper.model.LocatorCandidate;
import dev.hendrikhoemberg.webtesthelper.model.LocatorStrategy;
import dev.hendrikhoemberg.webtesthelper.model.SiteContext;
import dev.hendrikhoemberg.webtesthelper.model.StepAction;
import dev.hendrikhoemberg.webtesthelper.model.StepAssertion;
import dev.hendrikhoemberg.webtesthelper.model.UrlNormalizer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

@WebMvcTest(JourneyController.class)
class JourneyControllerTest {

    @Autowired
    MockMvc mvc;

    @MockitoBean
    SiteService siteService;

    @MockitoBean
    JourneyService journeyService;

    @MockitoBean
    JourneyHealthService journeyHealthService;

    @MockitoBean
    AppUserService appUserService;

    private SiteContext testSite;

    @BeforeEach
    void setUp() {
        testSite = new SiteContext(
                1L,
                "Acme Shop",
                UrlNormalizer.normalize("https://acme.example.com/").orElseThrow(),
                new CrawlBudget(120, 3, Duration.ofMinutes(15)),
                List.of(), List.of(), List.of(),
                true, "AcmeBot/2.0", Map.of()
        );
        when(siteService.contextFor(1L)).thenReturn(testSite);
    }

    @Test
    @WithMockUser(roles = "USER")
    void listJourneys_rendersJourneysWithStepCountsAndEnabledState() throws Exception {
        JourneyStep step1 = new JourneyStep(UUID.randomUUID(), 0, StepAction.GOTO, List.of(), "https://acme.example.com/login", null, false, 5000);
        JourneyStep step2 = new JourneyStep(UUID.randomUUID(), 1, StepAction.CLICK, List.of(new LocatorCandidate(LocatorStrategy.ROLE, "submit", 0)), null, null, false, 5000);

        JourneyDefinition j1 = new JourneyDefinition(10L, 1L, "Checkout-Ablauf", true, List.of(step1, step2));
        JourneyDefinition j2 = new JourneyDefinition(20L, 1L, "Wunschliste-Ablauf", false, List.of(step1));

        when(journeyService.findBySite(1L)).thenReturn(List.of(j1, j2));
        when(journeyHealthService.healthBySite(1L)).thenReturn(Map.of(
                10L, new JourneyHealth(Instant.parse("2026-08-28T10:00:00Z"), 0, 0),
                20L, new JourneyHealth(null, 2, 0)
        ));

        mvc.perform(get("/sites/1/journeys"))
                .andExpect(status().isOk())
                .andExpect(view().name("journey/list"))
                .andExpect(model().attributeExists("site", "journeys", "healthByJourneyId"))
                .andExpect(content().string(containsString("Checkout-Ablauf")))
                .andExpect(content().string(containsString("Wunschliste-Ablauf")))
                .andExpect(content().string(containsString("2")))
                .andExpect(content().string(containsString("1")))
                .andExpect(content().string(containsString("Aktiv")))
                .andExpect(content().string(containsString("Inaktiv")))
                .andExpect(content().string(containsString("/sites/1/journeys/10")))
                .andExpect(content().string(containsString("/sites/1/journeys/20")))
                // Health info: last success and failure streak
                .andExpect(content().string(containsString("28.08.2026")))
                .andExpect(content().string(containsString("Noch nie")))
                .andExpect(content().string(not(containsString("Neuaufzeichnung erforderlich"))));
    }

    @Test
    @WithMockUser(roles = "USER")
    void listJourneys_whenJourneyNeedsRerecording_rendersExplainingState() throws Exception {
        JourneyStep step1 = new JourneyStep(UUID.randomUUID(), 0, StepAction.GOTO, List.of(), "https://acme.example.com/login", null, false, 5000);
        JourneyDefinition j1 = new JourneyDefinition(10L, 1L, "Checkout-Ablauf", true, List.of(step1));
        JourneyDefinition j2 = new JourneyDefinition(20L, 1L, "Wunschliste-Ablauf", true, List.of(step1));

        when(journeyService.findBySite(1L)).thenReturn(List.of(j1, j2));
        // j1 meets threshold: 3 failures >= 3 && drift 2 > 0 -> needsRerecording = true
        // j2 below threshold: 3 failures >= 3 && drift 0 == 0 -> needsRerecording = false
        when(journeyHealthService.healthBySite(1L)).thenReturn(Map.of(
                10L, new JourneyHealth(Instant.parse("2026-08-28T10:00:00Z"), 3, 2),
                20L, new JourneyHealth(Instant.parse("2026-08-28T10:00:00Z"), 3, 0)
        ));

        mvc.perform(get("/sites/1/journeys"))
                .andExpect(status().isOk())
                .andExpect(view().name("journey/list"))
                .andExpect(model().attributeExists("site", "journeys", "healthByJourneyId"))
                // Explanatory copy for needsRerecording (§13.2)
                .andExpect(content().string(containsString("Neuaufzeichnung erforderlich")))
                .andExpect(content().string(containsString("Dieser Ablauf schlägt nach wiederholten Selektor-Abweichungen (Drift) fehl.")));
    }

    @Test
    @WithMockUser(roles = "USER")
    void listJourneys_whenEmpty_rendersEmptyState() throws Exception {
        when(journeyService.findBySite(1L)).thenReturn(List.of());
        when(journeyHealthService.healthBySite(1L)).thenReturn(Map.of());

        mvc.perform(get("/sites/1/journeys"))
                .andExpect(status().isOk())
                .andExpect(view().name("journey/list"))
                .andExpect(model().attributeExists("site", "journeys", "healthByJourneyId"))
                .andExpect(content().string(containsString("Für diese Website sind noch keine Abläufe hinterlegt.")));
    }

    @Test
    @WithMockUser(roles = "USER")
    void detailJourney_rendersStepsInOrdinalOrderWithActionLocatorsValueAssertionAndNeverSecret() throws Exception {
        JourneyStep step0 = new JourneyStep(
                UUID.randomUUID(), 0, StepAction.GOTO,
                List.of(), "https://acme.example.com/login", null, false, 5000);

        JourneyStep step1 = new JourneyStep(
                UUID.randomUUID(), 1, StepAction.FILL,
                List.of(new LocatorCandidate(LocatorStrategy.TEST_ID, "username-field", 0)),
                "{{cred.login.username}}", null, false, 5000);

        JourneyStep step2 = new JourneyStep(
                UUID.randomUUID(), 2, StepAction.FILL,
                List.of(
                        new LocatorCandidate(LocatorStrategy.LABEL, "Passwort", 0),
                        new LocatorCandidate(LocatorStrategy.CSS, "#pwd-input", 1)
                ),
                "{{cred.login.password}}", null, false, 5000);

        JourneyStep step3 = new JourneyStep(
                UUID.randomUUID(), 3, StepAction.CLICK,
                List.of(new LocatorCandidate(LocatorStrategy.ROLE, "button-login", 0)),
                null, null, false, 5000);

        JourneyStep step4 = new JourneyStep(
                UUID.randomUUID(), 4, StepAction.ASSERT,
                List.of(new LocatorCandidate(LocatorStrategy.TEXT, "Willkommen zurück", 0)),
                null, new StepAssertion(AssertionType.VISIBLE, null), true, 3000);

        JourneyDefinition journey = new JourneyDefinition(10L, 1L, "Anmeldung", true,
                List.of(step0, step1, step2, step3, step4));

        when(journeyService.findDefinition(10L)).thenReturn(Optional.of(journey));
        when(journeyHealthService.health(10L)).thenReturn(Optional.of(new JourneyHealth(Instant.parse("2026-08-28T10:00:00Z"), 3, 2)));

        mvc.perform(get("/sites/1/journeys/10"))
                .andExpect(status().isOk())
                .andExpect(view().name("journey/detail"))
                .andExpect(model().attributeExists("site", "journey", "health"))
                .andExpect(content().string(containsString("Anmeldung")))
                .andExpect(content().string(containsString("Aktiv")))
                // Health stats: drift count, last success, consecutive failures, needs re-recording
                .andExpect(content().string(containsString("2")))
                .andExpect(content().string(containsString("3")))
                .andExpect(content().string(containsString("28.08.2026")))
                .andExpect(content().string(containsString("Neuaufzeichnung erforderlich")))
                .andExpect(content().string(containsString("Dieser Ablauf schlägt nach wiederholten Selektor-Abweichungen (Drift) fehl.")))
                // Actions
                .andExpect(content().string(containsString("GOTO")))
                .andExpect(content().string(containsString("FILL")))
                .andExpect(content().string(containsString("CLICK")))
                .andExpect(content().string(containsString("ASSERT")))
                // Strategies & Locators
                .andExpect(content().string(containsString("TEST_ID")))
                .andExpect(content().string(containsString("username-field")))
                .andExpect(content().string(containsString("LABEL")))
                .andExpect(content().string(containsString("Passwort")))
                .andExpect(content().string(containsString("CSS")))
                .andExpect(content().string(containsString("#pwd-input")))
                .andExpect(content().string(containsString("ROLE")))
                .andExpect(content().string(containsString("button-login")))
                .andExpect(content().string(containsString("TEXT")))
                .andExpect(content().string(containsString("Willkommen zurück")))
                // Credential templates rendered verbatim as text, NEVER resolved secret
                .andExpect(content().string(containsString("{{cred.login.username}}")))
                .andExpect(content().string(containsString("{{cred.login.password}}")))
                .andExpect(content().string(not(containsString("super-secret-password-123"))))
                // Assertion
                .andExpect(content().string(containsString("VISIBLE")))
                // Optional flag
                .andExpect(content().string(containsString("Optional")))
                // Timeout
                .andExpect(content().string(containsString("3000")));
    }

    @Test
    @WithMockUser(roles = "USER")
    void detailJourney_whenHealthy_rendersZeroDriftAndNoRerecordingWarning() throws Exception {
        JourneyStep step0 = new JourneyStep(
                UUID.randomUUID(), 0, StepAction.GOTO,
                List.of(), "https://acme.example.com/login", null, false, 5000);
        JourneyDefinition journey = new JourneyDefinition(10L, 1L, "Anmeldung", true, List.of(step0));

        when(journeyService.findDefinition(10L)).thenReturn(Optional.of(journey));
        when(journeyHealthService.health(10L)).thenReturn(Optional.of(new JourneyHealth(Instant.parse("2026-08-28T10:00:00Z"), 0, 0)));

        mvc.perform(get("/sites/1/journeys/10"))
                .andExpect(status().isOk())
                .andExpect(view().name("journey/detail"))
                .andExpect(model().attributeExists("site", "journey", "health"))
                .andExpect(content().string(containsString("Anmeldung")))
                .andExpect(content().string(not(containsString("Neuaufzeichnung erforderlich"))));
    }

    @Test
    @WithMockUser(roles = "USER")
    void detailJourney_whenNotFound_returns404() throws Exception {
        when(journeyService.findDefinition(999L)).thenReturn(Optional.empty());

        mvc.perform(get("/sites/1/journeys/999"))
                .andExpect(status().isNotFound());
    }

    @Test
    @WithMockUser(roles = "USER")
    void detailJourney_whenBelongsToDifferentSite_returns404() throws Exception {
        JourneyDefinition journey = new JourneyDefinition(10L, 2L, "Fremder Ablauf", true, List.of());
        when(journeyService.findDefinition(10L)).thenReturn(Optional.of(journey));

        mvc.perform(get("/sites/1/journeys/10"))
                .andExpect(status().isNotFound());
    }

    @Test
    void unauthenticatedAccess_redirectsToLogin() throws Exception {
        mvc.perform(get("/sites/1/journeys"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/anmelden"));

        mvc.perform(get("/sites/1/journeys/10"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/anmelden"));
    }
}
