package dev.hendrikhoemberg.webtesthelper.web;

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
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

@org.junit.jupiter.api.parallel.ResourceLock("spring-context")
@WebMvcTest(JourneyEditController.class)
class JourneyEditControllerTest {

    @Autowired
    MockMvc mvc;

    @MockitoBean
    SiteService siteService;

    @MockitoBean
    JourneyService journeyService;

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
    void editJourney_get_rendersFormWithJourneyDetailsAndStepsVerbatim() throws Exception {
        UUID step0Id = UUID.randomUUID();
        UUID step1Id = UUID.randomUUID();
        UUID step2Id = UUID.randomUUID();

        JourneyStep step0 = new JourneyStep(
                step0Id, 0, StepAction.GOTO,
                List.of(), "https://acme.example.com/login", null, false, 5000);
        JourneyStep step1 = new JourneyStep(
                step1Id, 1, StepAction.FILL,
                List.of(new LocatorCandidate(LocatorStrategy.LABEL, "Passwort", 0)),
                "{{cred.login.password}}", null, false, 5000);
        JourneyStep step2 = new JourneyStep(
                step2Id, 2, StepAction.ASSERT,
                List.of(new LocatorCandidate(LocatorStrategy.TEXT, "Willkommen", 0)),
                null, new StepAssertion(AssertionType.TEXT_CONTAINS, "Willkommen"), true, 3000);

        JourneyDefinition journey = new JourneyDefinition(10L, 1L, "Anmeldung", true,
                List.of(step0, step1, step2));

        when(journeyService.findDefinition(10L)).thenReturn(Optional.of(journey));

        mvc.perform(get("/websites/1/journeys/10/bearbeiten"))
                .andExpect(status().isOk())
                .andExpect(view().name("journey/edit"))
                .andExpect(model().attributeExists("site", "journey", "form"))
                .andExpect(content().string(containsString("Anmeldung")))
                .andExpect(content().string(containsString("GOTO")))
                .andExpect(content().string(containsString("FILL")))
                .andExpect(content().string(containsString("ASSERT")))
                // Credential templates rendered verbatim in form value attribute, never resolved secret
                .andExpect(content().string(containsString("{{cred.login.password}}")))
                .andExpect(content().string(not(containsString("secret-pass"))))
                .andExpect(content().string(containsString("TEXT_CONTAINS")))
                .andExpect(content().string(containsString("VISIBLE")))
                .andExpect(content().string(containsString("URL_MATCHES")))
                .andExpect(content().string(containsString("COUNT")))
                .andExpect(content().string(containsString("Ablauf löschen")))
                .andExpect(content().string(containsString("/websites/1/journeys/10/loeschen")));
    }

    @Test
    @WithMockUser(roles = "USER")
    void editJourney_rendersGermanActionAndStrategyLabels() throws Exception {
        UUID step0Id = UUID.randomUUID();
        JourneyStep step0 = new JourneyStep(
                step0Id, 0, StepAction.GOTO,
                List.of(new LocatorCandidate(LocatorStrategy.TEST_ID, "main-nav", 0)),
                "https://acme.example.com/login", null, false, 5000);
        JourneyDefinition journey = new JourneyDefinition(10L, 1L, "Anmeldung", true, List.of(step0));
        when(journeyService.findDefinition(10L)).thenReturn(Optional.of(journey));

        mvc.perform(get("/websites/1/journeys/10/bearbeiten"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Seite aufrufen")))
                .andExpect(content().string(containsString("Test-ID")));
    }

    @Test
    @WithMockUser(roles = "USER")
    void editJourney_post_deletingStepLeavesDenseOrdinalsAndPreservesAllOtherStepUuids() throws Exception {
        UUID id0 = UUID.randomUUID();
        UUID id1 = UUID.randomUUID();
        UUID id2 = UUID.randomUUID(); // to delete (step 3 of 5, 0-indexed index 2)
        UUID id3 = UUID.randomUUID();
        UUID id4 = UUID.randomUUID();

        JourneyStep s0 = new JourneyStep(id0, 0, StepAction.GOTO, List.of(), "https://acme.example.com/", null, false, 5000);
        JourneyStep s1 = new JourneyStep(id1, 1, StepAction.CLICK, List.of(new LocatorCandidate(LocatorStrategy.ROLE, "btn-a", 0)), null, null, false, 5000);
        JourneyStep s2 = new JourneyStep(id2, 2, StepAction.CLICK, List.of(new LocatorCandidate(LocatorStrategy.ROLE, "btn-b", 0)), null, null, false, 5000);
        JourneyStep s3 = new JourneyStep(id3, 3, StepAction.CLICK, List.of(new LocatorCandidate(LocatorStrategy.ROLE, "btn-c", 0)), null, null, false, 5000);
        JourneyStep s4 = new JourneyStep(id4, 4, StepAction.CLICK, List.of(new LocatorCandidate(LocatorStrategy.ROLE, "btn-d", 0)), null, null, false, 5000);

        JourneyDefinition journey = new JourneyDefinition(10L, 1L, "5-Schritt-Ablauf", true, List.of(s0, s1, s2, s3, s4));
        when(journeyService.findDefinition(10L)).thenReturn(Optional.of(journey));

        // Submit form containing s0, s1, s3, s4 (s2 omitted / deleted)
        mvc.perform(post("/websites/1/journeys/10/bearbeiten")
                        .with(csrf())
                        .param("name", "5-Schritt-Ablauf")
                        .param("enabled", "true")
                        .param("steps[0].id", id0.toString())
                        .param("steps[0].ordinal", "0")
                        .param("steps[0].action", "GOTO")
                        .param("steps[0].value", "https://acme.example.com/")
                        .param("steps[1].id", id1.toString())
                        .param("steps[1].ordinal", "1")
                        .param("steps[1].action", "CLICK")
                        .param("steps[2].id", id3.toString())
                        .param("steps[2].ordinal", "2")
                        .param("steps[2].action", "CLICK")
                        .param("steps[3].id", id4.toString())
                        .param("steps[3].ordinal", "3")
                        .param("steps[3].action", "CLICK"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/websites/1/journeys/10"));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<JourneyStep>> captor = ArgumentCaptor.forClass(List.class);
        verify(journeyService).update(eq(10L), eq("5-Schritt-Ablauf"), eq(true), captor.capture());

        List<JourneyStep> updatedSteps = captor.getValue();
        assertThat(updatedSteps).hasSize(4);
        assertThat(updatedSteps.get(0).id()).isEqualTo(id0);
        assertThat(updatedSteps.get(1).id()).isEqualTo(id1);
        assertThat(updatedSteps.get(2).id()).isEqualTo(id3);
        assertThat(updatedSteps.get(3).id()).isEqualTo(id4);
    }

    @Test
    @WithMockUser(roles = "USER")
    void editJourney_post_reorderingStepsChangesOrdinalsNotUuids() throws Exception {
        UUID id0 = UUID.randomUUID();
        UUID id1 = UUID.randomUUID();

        JourneyStep s0 = new JourneyStep(id0, 0, StepAction.GOTO, List.of(), "https://acme.example.com/", null, false, 5000);
        JourneyStep s1 = new JourneyStep(id1, 1, StepAction.CLICK, List.of(new LocatorCandidate(LocatorStrategy.ROLE, "btn", 0)), null, null, false, 5000);

        JourneyDefinition journey = new JourneyDefinition(10L, 1L, "Reihenfolge-Test", true, List.of(s0, s1));
        when(journeyService.findDefinition(10L)).thenReturn(Optional.of(journey));

        // Submit form swapping order: s1 first (ordinal 0), s0 second (ordinal 1)
        mvc.perform(post("/websites/1/journeys/10/bearbeiten")
                        .with(csrf())
                        .param("name", "Reihenfolge-Test")
                        .param("enabled", "true")
                        .param("steps[0].id", id1.toString())
                        .param("steps[0].ordinal", "0")
                        .param("steps[0].action", "CLICK")
                        .param("steps[1].id", id0.toString())
                        .param("steps[1].ordinal", "1")
                        .param("steps[1].action", "GOTO")
                        .param("steps[1].value", "https://acme.example.com/"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/websites/1/journeys/10"));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<JourneyStep>> captor = ArgumentCaptor.forClass(List.class);
        verify(journeyService).update(eq(10L), eq("Reihenfolge-Test"), eq(true), captor.capture());

        List<JourneyStep> updatedSteps = captor.getValue();
        assertThat(updatedSteps).hasSize(2);
        assertThat(updatedSteps.get(0).id()).isEqualTo(id1);
        assertThat(updatedSteps.get(1).id()).isEqualTo(id0);
    }

    @Test
    @WithMockUser(roles = "USER")
    void editJourney_post_editingValuePersistsItIncludingCredentialTemplates() throws Exception {
        UUID id0 = UUID.randomUUID();
        UUID id1 = UUID.randomUUID();

        JourneyStep s0 = new JourneyStep(id0, 0, StepAction.GOTO, List.of(), "https://acme.example.com/", null, false, 5000);
        JourneyStep s1 = new JourneyStep(id1, 1, StepAction.FILL, List.of(new LocatorCandidate(LocatorStrategy.LABEL, "Passwort", 0)), "altesPasswort", null, false, 5000);

        JourneyDefinition journey = new JourneyDefinition(10L, 1L, "Passwort-Test", true, List.of(s0, s1));
        when(journeyService.findDefinition(10L)).thenReturn(Optional.of(journey));

        mvc.perform(post("/websites/1/journeys/10/bearbeiten")
                        .with(csrf())
                        .param("name", "Passwort-Test")
                        .param("enabled", "true")
                        .param("steps[0].id", id0.toString())
                        .param("steps[0].ordinal", "0")
                        .param("steps[0].action", "GOTO")
                        .param("steps[0].value", "https://acme.example.com/")
                        .param("steps[1].id", id1.toString())
                        .param("steps[1].ordinal", "1")
                        .param("steps[1].action", "FILL")
                        .param("steps[1].value", "{{cred.login.password}}"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/websites/1/journeys/10"));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<JourneyStep>> captor = ArgumentCaptor.forClass(List.class);
        verify(journeyService).update(eq(10L), eq("Passwort-Test"), eq(true), captor.capture());

        List<JourneyStep> updatedSteps = captor.getValue();
        assertThat(updatedSteps.get(1).value()).isEqualTo("{{cred.login.password}}");
    }

    @Test
    @WithMockUser(roles = "USER")
    void editJourney_post_markingStepOptionalPersistsIt() throws Exception {
        UUID id0 = UUID.randomUUID();
        JourneyStep s0 = new JourneyStep(id0, 0, StepAction.GOTO, List.of(), "https://acme.example.com/", null, false, 5000);
        JourneyDefinition journey = new JourneyDefinition(10L, 1L, "Optional-Test", true, List.of(s0));
        when(journeyService.findDefinition(10L)).thenReturn(Optional.of(journey));

        mvc.perform(post("/websites/1/journeys/10/bearbeiten")
                        .with(csrf())
                        .param("name", "Optional-Test")
                        .param("enabled", "true")
                        .param("steps[0].id", id0.toString())
                        .param("steps[0].ordinal", "0")
                        .param("steps[0].action", "GOTO")
                        .param("steps[0].value", "https://acme.example.com/")
                        .param("steps[0].optional", "true"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/websites/1/journeys/10"));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<JourneyStep>> captor = ArgumentCaptor.forClass(List.class);
        verify(journeyService).update(eq(10L), eq("Optional-Test"), eq(true), captor.capture());

        List<JourneyStep> updatedSteps = captor.getValue();
        assertThat(updatedSteps.get(0).optional()).isTrue();
    }

    @Test
    @WithMockUser(roles = "USER")
    void editJourney_post_addingAssertionsOfAllFourTypesPersistsThem() throws Exception {
        UUID id0 = UUID.randomUUID();
        UUID id1 = UUID.randomUUID();
        UUID id2 = UUID.randomUUID();
        UUID id3 = UUID.randomUUID();

        JourneyStep s0 = new JourneyStep(id0, 0, StepAction.GOTO, List.of(), "https://acme.example.com/", null, false, 5000);
        JourneyStep s1 = new JourneyStep(id1, 1, StepAction.CLICK, List.of(new LocatorCandidate(LocatorStrategy.ROLE, "b1", 0)), null, null, false, 5000);
        JourneyStep s2 = new JourneyStep(id2, 2, StepAction.CLICK, List.of(new LocatorCandidate(LocatorStrategy.ROLE, "b2", 0)), null, null, false, 5000);
        JourneyStep s3 = new JourneyStep(id3, 3, StepAction.CLICK, List.of(new LocatorCandidate(LocatorStrategy.ROLE, "b3", 0)), null, null, false, 5000);

        JourneyDefinition journey = new JourneyDefinition(10L, 1L, "Assertion-Test", true, List.of(s0, s1, s2, s3));
        when(journeyService.findDefinition(10L)).thenReturn(Optional.of(journey));

        mvc.perform(post("/websites/1/journeys/10/bearbeiten")
                        .with(csrf())
                        .param("name", "Assertion-Test")
                        .param("enabled", "true")
                        // s0 with URL_MATCHES
                        .param("steps[0].id", id0.toString())
                        .param("steps[0].ordinal", "0")
                        .param("steps[0].action", "GOTO")
                        .param("steps[0].value", "https://acme.example.com/")
                        .param("steps[0].assertionType", "URL_MATCHES")
                        .param("steps[0].assertionExpected", "https://acme.example.com/.*")
                        // s1 with TEXT_CONTAINS
                        .param("steps[1].id", id1.toString())
                        .param("steps[1].ordinal", "1")
                        .param("steps[1].action", "CLICK")
                        .param("steps[1].assertionType", "TEXT_CONTAINS")
                        .param("steps[1].assertionExpected", "Erfolgreich gespeichert")
                        // s2 with VISIBLE
                        .param("steps[2].id", id2.toString())
                        .param("steps[2].ordinal", "2")
                        .param("steps[2].action", "CLICK")
                        .param("steps[2].assertionType", "VISIBLE")
                        // s3 with COUNT
                        .param("steps[3].id", id3.toString())
                        .param("steps[3].ordinal", "3")
                        .param("steps[3].action", "CLICK")
                        .param("steps[3].assertionType", "COUNT")
                        .param("steps[3].assertionExpected", "3"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/websites/1/journeys/10"));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<JourneyStep>> captor = ArgumentCaptor.forClass(List.class);
        verify(journeyService).update(eq(10L), eq("Assertion-Test"), eq(true), captor.capture());

        List<JourneyStep> updatedSteps = captor.getValue();
        assertThat(updatedSteps.get(0).assertion()).isEqualTo(new StepAssertion(AssertionType.URL_MATCHES, "https://acme.example.com/.*"));
        assertThat(updatedSteps.get(1).assertion()).isEqualTo(new StepAssertion(AssertionType.TEXT_CONTAINS, "Erfolgreich gespeichert"));
        assertThat(updatedSteps.get(2).assertion()).isEqualTo(new StepAssertion(AssertionType.VISIBLE, null));
        assertThat(updatedSteps.get(3).assertion()).isEqualTo(new StepAssertion(AssertionType.COUNT, "3"));
    }

    @Test
    @WithMockUser(roles = "USER")
    void editJourney_post_removingAssertionSetsItToNull() throws Exception {
        UUID id0 = UUID.randomUUID();
        JourneyStep s0 = new JourneyStep(id0, 0, StepAction.GOTO, List.of(), "https://acme.example.com/",
                new StepAssertion(AssertionType.VISIBLE, null), false, 5000);
        JourneyDefinition journey = new JourneyDefinition(10L, 1L, "Remove-Assertion", true, List.of(s0));
        when(journeyService.findDefinition(10L)).thenReturn(Optional.of(journey));

        // Submit with empty assertionType
        mvc.perform(post("/websites/1/journeys/10/bearbeiten")
                        .with(csrf())
                        .param("name", "Remove-Assertion")
                        .param("enabled", "true")
                        .param("steps[0].id", id0.toString())
                        .param("steps[0].ordinal", "0")
                        .param("steps[0].action", "GOTO")
                        .param("steps[0].value", "https://acme.example.com/")
                        .param("steps[0].assertionType", ""))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/websites/1/journeys/10"));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<JourneyStep>> captor = ArgumentCaptor.forClass(List.class);
        verify(journeyService).update(eq(10L), eq("Remove-Assertion"), eq(true), captor.capture());

        List<JourneyStep> updatedSteps = captor.getValue();
        assertThat(updatedSteps.get(0).assertion()).isNull();
    }

    @Test
    @WithMockUser(roles = "USER")
    void editJourney_post_whenNameBlankOrDuplicate_rendersFormWithError() throws Exception {
        UUID id0 = UUID.randomUUID();
        JourneyStep s0 = new JourneyStep(id0, 0, StepAction.GOTO, List.of(), "https://acme.example.com/", null, false, 5000);
        JourneyDefinition journey = new JourneyDefinition(10L, 1L, "Original-Name", true, List.of(s0));
        when(journeyService.findDefinition(10L)).thenReturn(Optional.of(journey));
        doThrow(new IllegalArgumentException("journey.name.duplicate"))
                .when(journeyService).update(eq(10L), eq("Duplikat"), anyBoolean(), any());

        mvc.perform(post("/websites/1/journeys/10/bearbeiten")
                        .with(csrf())
                        .param("name", "Duplikat")
                        .param("enabled", "true")
                        .param("steps[0].id", id0.toString())
                        .param("steps[0].ordinal", "0")
                        .param("steps[0].action", "GOTO")
                        .param("steps[0].value", "https://acme.example.com/"))
                .andExpect(status().isOk())
                .andExpect(view().name("journey/edit"))
                .andExpect(model().attributeExists("errorMessage"));
    }

    @Test
    @WithMockUser(roles = "USER")
    void editJourney_post_submittingNewStepWithoutId_createsStepWithGeneratedUuid() throws Exception {
        UUID id0 = UUID.randomUUID();
        JourneyStep s0 = new JourneyStep(id0, 0, StepAction.GOTO, List.of(), "https://acme.example.com/", null, false, 5000);
        JourneyDefinition journey = new JourneyDefinition(10L, 1L, "Anmeldung", true, List.of(s0));
        when(journeyService.findDefinition(10L)).thenReturn(Optional.of(journey));

        mvc.perform(post("/websites/1/journeys/10/bearbeiten")
                        .with(csrf())
                        .param("name", "Anmeldung")
                        .param("enabled", "true")
                        .param("steps[0].id", id0.toString())
                        .param("steps[0].ordinal", "0")
                        .param("steps[0].action", "GOTO")
                        .param("steps[0].value", "https://acme.example.com/")
                        // Step 1 is newly added without ID
                        .param("steps[1].ordinal", "1")
                        .param("steps[1].action", "CLICK")
                        .param("steps[1].selector", "#submit-btn")
                        .param("steps[1].timeoutMs", "3000"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/websites/1/journeys/10"));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<JourneyStep>> captor = ArgumentCaptor.forClass(List.class);
        verify(journeyService).update(eq(10L), eq("Anmeldung"), eq(true), captor.capture());

        List<JourneyStep> saved = captor.getValue();
        assertThat(saved).hasSize(2);
        assertThat(saved.get(0).id()).isEqualTo(id0);
        assertThat(saved.get(1).id()).isNotNull().isNotEqualTo(id0);
        assertThat(saved.get(1).action()).isEqualTo(StepAction.CLICK);
        assertThat(saved.get(1).locatorCandidates()).hasSize(1);
        assertThat(saved.get(1).locatorCandidates().get(0).strategy()).isEqualTo(LocatorStrategy.CSS);
        assertThat(saved.get(1).locatorCandidates().get(0).value()).isEqualTo("#submit-btn");
        assertThat(saved.get(1).ordinal()).isEqualTo(1);
    }

    @Test
    @WithMockUser(roles = "USER")
    void editJourney_whenNotFound_orSiteMismatch_returns404() throws Exception {
        when(journeyService.findDefinition(999L)).thenReturn(Optional.empty());

        mvc.perform(get("/websites/1/journeys/999/bearbeiten"))
                .andExpect(status().isNotFound());

        JourneyDefinition otherSiteJourney = new JourneyDefinition(10L, 2L, "Fremde Site", true, List.of());
        when(journeyService.findDefinition(10L)).thenReturn(Optional.of(otherSiteJourney));

        mvc.perform(get("/websites/1/journeys/10/bearbeiten"))
                .andExpect(status().isNotFound());
    }

    @Test
    void editJourney_unauthenticatedAccess_redirectsToLogin() throws Exception {
        mvc.perform(get("/websites/1/journeys/10/bearbeiten"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/anmelden"));

        mvc.perform(post("/websites/1/journeys/10/bearbeiten").with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/anmelden"));
    }
}
