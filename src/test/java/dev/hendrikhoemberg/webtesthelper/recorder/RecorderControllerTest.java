package dev.hendrikhoemberg.webtesthelper.recorder;

import dev.hendrikhoemberg.webtesthelper.catalog.JourneyService;
import dev.hendrikhoemberg.webtesthelper.catalog.SiteService;
import dev.hendrikhoemberg.webtesthelper.model.CrawlBudget;
import dev.hendrikhoemberg.webtesthelper.model.JourneyStep;
import dev.hendrikhoemberg.webtesthelper.model.SiteContext;
import dev.hendrikhoemberg.webtesthelper.model.StepAction;
import dev.hendrikhoemberg.webtesthelper.model.UrlNormalizer;
import dev.hendrikhoemberg.webtesthelper.web.AppUserService;
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
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

@org.junit.jupiter.api.parallel.ResourceLock("spring-context")
@WebMvcTest(RecorderController.class)
class RecorderControllerTest {

    @Autowired
    MockMvc mvc;

    @MockitoBean
    SiteService siteService;

    @MockitoBean
    RecordingSessionRegistry sessionRegistry;

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
        when(journeyService.resolveUniqueName(anyLong(), anyString())).thenAnswer(inv -> inv.getArgument(1));
    }

    @Test
    @WithMockUser(username = "alice", roles = "USER")
    void record_opensSessionAndRendersCanvasScreen_whenCapacityAvailable() throws Exception {
        UUID sessionId = UUID.randomUUID();
        RecordingSession session = mock(RecordingSession.class);
        when(session.sessionId()).thenReturn(sessionId);
        when(session.siteId()).thenReturn(1L);
        when(session.startUrl()).thenReturn("https://acme.example.com/");
        when(session.username()).thenReturn("alice");

        when(sessionRegistry.open(1L, "https://acme.example.com/", "alice")).thenReturn(session);

        mvc.perform(get("/websites/1/aufzeichnen"))
                .andExpect(status().isOk())
                .andExpect(view().name("journey/record"))
                .andExpect(model().attributeExists("site", "sessionId", "wsUrl"))
                .andExpect(content().string(containsString("<canvas id=\"recorder-canvas\"")))
                .andExpect(content().string(containsString("/recorder/ws/" + sessionId)))
                .andExpect(content().string(containsString("Aufzeichnung abbrechen")))
                .andExpect(content().string(not(containsString("Aufzeichnung beenden"))))
                .andExpect(content().string(not(containsString("Maximale Anzahl gleichzeitiger Aufzeichnungssitzungen"))));

        verify(sessionRegistry).open(1L, "https://acme.example.com/", "alice");
    }

    @Test
    @WithMockUser(username = "alice", roles = "USER")
    void activeRecorderHeaderDoesNotContainDuplicateEndButton() throws Exception {
        UUID sessionId = UUID.randomUUID();
        RecordingSession session = mock(RecordingSession.class);
        when(session.sessionId()).thenReturn(sessionId);
        when(session.siteId()).thenReturn(1L);
        when(session.startUrl()).thenReturn("https://acme.example.com/");
        when(session.username()).thenReturn("alice");
        when(sessionRegistry.open(1L, "https://acme.example.com/", "alice")).thenReturn(session);

        org.springframework.test.web.servlet.MvcResult result = mvc.perform(get("/websites/1/aufzeichnen"))
                .andExpect(status().isOk())
                .andReturn();
        String html = result.getResponse().getContentAsString();
        int headerStart = html.indexOf("seiten-kopf-aktionen");
        int headerEnd = html.indexOf("</header>", headerStart);
        String headerActions = html.substring(headerStart, headerEnd);
        assertThat(headerActions).doesNotContain("Aufzeichnung abbrechen");
    }

    @Test
    @WithMockUser(username = "alice", roles = "USER")
    void record_rendersScriptThatMarksSessionClosedOnFormSubmit_preventingUnloadBeaconRace() throws Exception {
        UUID sessionId = UUID.randomUUID();
        RecordingSession session = mock(RecordingSession.class);
        when(session.sessionId()).thenReturn(sessionId);
        when(session.siteId()).thenReturn(1L);
        when(session.startUrl()).thenReturn("https://acme.example.com/");
        when(session.username()).thenReturn("alice");
        when(sessionRegistry.open(1L, "https://acme.example.com/", "alice")).thenReturn(session);

        mvc.perform(get("/websites/1/aufzeichnen"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("addEventListener('submit', function()")));
    }

    @Test
    @WithMockUser(username = "alice", roles = "USER")
    void record_whenCapacityExceeded_rendersCapacityExceededStateInGermanWithoutOfferingSession() throws Exception {
        when(sessionRegistry.open(eq(1L), any(), eq("alice")))
                .thenThrow(new RecorderCapacityException(2));

        mvc.perform(get("/websites/1/aufzeichnen"))
                .andExpect(status().isOk())
                .andExpect(view().name("journey/record"))
                .andExpect(model().attributeExists("site", "capacityExceeded"))
                .andExpect(content().string(containsString("Maximale Anzahl gleichzeitiger Aufzeichnungssitzungen (2) erreicht.")))
                .andExpect(content().string(containsString("Bitte beenden Sie eine laufende Sitzung oder versuchen Sie es später erneut.")))
                .andExpect(content().string(containsString("Eigene Aufzeichnungen beenden")))
                .andExpect(content().string(containsString("/recorder/meine-sitzungen-beenden")))
                .andExpect(content().string(not(containsString("Alle Aufzeichnungen zurücksetzen"))))
                .andExpect(content().string(not(containsString("<canvas id=\"recorder-canvas\""))));
    }

    @Test
    @WithMockUser(username = "admin", roles = "ADMIN")
    void record_whenCapacityExceeded_asAdmin_rendersBothResetOptions() throws Exception {
        when(sessionRegistry.open(eq(1L), any(), eq("admin")))
                .thenThrow(new RecorderCapacityException(2));

        mvc.perform(get("/websites/1/aufzeichnen"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Eigene Aufzeichnungen beenden")))
                .andExpect(content().string(containsString("Alle Aufzeichnungen zurücksetzen")))
                .andExpect(content().string(containsString("/recorder/alle-beenden")));
    }

    @Test
    @WithMockUser(username = "alice", roles = "USER")
    void record_whenCustomStartUrlProvided_opensSessionWithProvidedUrl() throws Exception {
        UUID sessionId = UUID.randomUUID();
        RecordingSession session = mock(RecordingSession.class);
        when(session.sessionId()).thenReturn(sessionId);
        when(session.siteId()).thenReturn(1L);
        when(session.startUrl()).thenReturn("https://acme.example.com/checkout");
        when(session.username()).thenReturn("alice");

        when(sessionRegistry.open(1L, "https://acme.example.com/checkout", "alice")).thenReturn(session);

        mvc.perform(get("/websites/1/aufzeichnen").param("startUrl", "https://acme.example.com/checkout"))
                .andExpect(status().isOk())
                .andExpect(view().name("journey/record"))
                .andExpect(model().attributeExists("site", "sessionId", "wsUrl"));

        verify(sessionRegistry).open(1L, "https://acme.example.com/checkout", "alice");
    }

    @Test
    @WithMockUser(username = "alice", roles = "USER")
    void closeSession_viaPost_closesSessionAndRedirects() throws Exception {
        UUID sessionId = UUID.randomUUID();
        RecordingSession session = mock(RecordingSession.class);
        when(session.sessionId()).thenReturn(sessionId);
        when(session.siteId()).thenReturn(1L);
        when(sessionRegistry.find(sessionId, "alice")).thenReturn(Optional.of(session));

        mvc.perform(post("/recorder/" + sessionId + "/beenden")
                        .with(csrf())
                        .param("siteId", "1"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/websites/1/journeys"));

        verify(sessionRegistry).close(sessionId);
    }

    @Test
    @WithMockUser(username = "bob", roles = "USER")
    void closeSession_byAnyoneButTheOwner_leavesTheSessionRunning() throws Exception {
        // Ownership is checked at lookup so there is one place to get it right (Task 3).
        // Closing bypassed it, so any authenticated user holding a session id could end
        // somebody else's recording.
        UUID aliceSession = UUID.randomUUID();
        when(sessionRegistry.find(eq(aliceSession), eq("bob"))).thenReturn(Optional.empty());

        mvc.perform(post("/recorder/" + aliceSession + "/beenden")
                        .with(csrf())
                        .param("siteId", "1"))
                .andExpect(status().isNotFound());

        verify(sessionRegistry, never()).close(aliceSession);
    }

    @Test
    @WithMockUser(username = "alice", roles = "USER")
    void record_whenTheBrowserFailsToStart_doesNotBlameTheCapacityLimit() throws Exception {
        // open() wraps every failure in IllegalStateException. Treating all of them as
        // "come back later, two people are recording" tells the user to wait for something
        // that will never free up.
        when(sessionRegistry.open(eq(1L), any(), eq("alice")))
                .thenThrow(new IllegalStateException("Aufnahmesitzung konnte nicht gestartet werden",
                        new RuntimeException("Chromium ist nicht startbar")));

        mvc.perform(get("/websites/1/aufzeichnen"))
                .andExpect(status().isOk())
                .andExpect(model().attribute("capacityExceeded", false))
                .andExpect(model().attribute("startFailed", true))
                .andExpect(content().string(containsString("Der Aufnahme-Browser konnte nicht gestartet werden.")))
                .andExpect(content().string(not(containsString("Maximale Anzahl gleichzeitiger Aufzeichnungssitzungen"))))
                .andExpect(content().string(not(containsString("<canvas id=\"recorder-canvas\""))));
    }

    @Test
    @WithMockUser(username = "alice", roles = "USER")
    void record_whenCapacityExceeded_namesTheConfiguredLimitRatherThanAHardcodedTwo() throws Exception {
        when(sessionRegistry.open(eq(1L), any(), eq("alice")))
                .thenThrow(new RecorderCapacityException(2));

        mvc.perform(get("/websites/1/aufzeichnen"))
                .andExpect(model().attribute("capacityLimit", 2));
    }

    @Test
    @WithMockUser(username = "alice", roles = "USER")
    void record_whenSiteNotFound_returns404() throws Exception {
        when(siteService.contextFor(999L)).thenThrow(new IllegalArgumentException("Site existiert nicht: 999"));

        mvc.perform(get("/websites/999/aufzeichnen"))
                .andExpect(status().isNotFound());
    }

    @Test
    @WithMockUser(username = "alice", roles = "USER")
    void saveSession_drainsEventsBuildsStepsCreatesJourneyClosesSessionAndRedirectsToEditScreen() throws Exception {
        UUID sessionId = UUID.randomUUID();
        RecordingSession session = mock(RecordingSession.class);
        IntentCapture capture = mock(IntentCapture.class);
        CapturedEvent event = new CapturedEvent(
                CapturedEvent.EventKind.CLICK, "button", null, "login-btn", "button",
                "Anmelden", null, "Anmelden", null, "button#login-btn");

        when(session.sessionId()).thenReturn(sessionId);
        when(session.siteId()).thenReturn(1L);
        when(session.startUrl()).thenReturn("https://acme.example.com/");
        when(session.intentCapture()).thenReturn(capture);
        when(capture.drain()).thenReturn(List.of(event));
        when(sessionRegistry.find(sessionId, "alice")).thenReturn(Optional.of(session));
        when(journeyService.create(eq(1L), eq("Mein Ablauf"), any())).thenReturn(42L);

        mvc.perform(post("/recorder/" + sessionId + "/speichern")
                        .with(csrf())
                        .param("name", "Mein Ablauf"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/websites/1/journeys/42/bearbeiten"));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<JourneyStep>> captor = ArgumentCaptor.forClass(List.class);
        verify(journeyService).create(eq(1L), eq("Mein Ablauf"), captor.capture());
        List<JourneyStep> steps = captor.getValue();
        assertThat(steps).hasSize(2);
        assertThat(steps.get(0).action()).isEqualTo(StepAction.GOTO);
        assertThat(steps.get(0).value()).isEqualTo("https://acme.example.com/");
        assertThat(steps.get(1).action()).isEqualTo(StepAction.CLICK);

        verify(capture).drain();
        verify(sessionRegistry).close(sessionId);
    }

    @Test
    @WithMockUser(username = "alice", roles = "USER")
    void saveSession_disambiguatesDuplicateNameWhenSaving() throws Exception {
        UUID sessionId = UUID.randomUUID();
        RecordingSession session = mock(RecordingSession.class);
        IntentCapture capture = mock(IntentCapture.class);

        when(session.sessionId()).thenReturn(sessionId);
        when(session.siteId()).thenReturn(1L);
        when(session.startUrl()).thenReturn("https://acme.example.com/");
        when(session.intentCapture()).thenReturn(capture);
        when(capture.drain()).thenReturn(List.of());
        when(sessionRegistry.find(sessionId, "alice")).thenReturn(Optional.of(session));
        when(journeyService.resolveUniqueName(1L, "Neuer Ablauf")).thenReturn("Neuer Ablauf 2");
        when(journeyService.create(eq(1L), eq("Neuer Ablauf 2"), any())).thenReturn(43L);

        mvc.perform(post("/recorder/" + sessionId + "/speichern")
                        .with(csrf())
                        .param("name", "Neuer Ablauf"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/websites/1/journeys/43/bearbeiten"));

        verify(journeyService).create(eq(1L), eq("Neuer Ablauf 2"), any());
        verify(sessionRegistry).close(sessionId);
    }

    @Test
    @WithMockUser(username = "alice", roles = "USER")
    void saveSession_whenCreateFails_redirectsWithFlashErrorWithout404() throws Exception {
        UUID sessionId = UUID.randomUUID();
        RecordingSession session = mock(RecordingSession.class);
        IntentCapture capture = mock(IntentCapture.class);

        when(session.sessionId()).thenReturn(sessionId);
        when(session.siteId()).thenReturn(1L);
        when(session.startUrl()).thenReturn("https://acme.example.com/");
        when(session.intentCapture()).thenReturn(capture);
        when(capture.drain()).thenReturn(List.of());
        when(sessionRegistry.find(sessionId, "alice")).thenReturn(Optional.of(session));
        when(journeyService.create(eq(1L), anyString(), any()))
                .thenThrow(new IllegalArgumentException("journey.name.duplicate"));

        mvc.perform(post("/recorder/" + sessionId + "/speichern")
                        .with(csrf())
                        .param("name", "Duplikat"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/websites/1/journeys"))
                .andExpect(flash().attribute("flashError", "journey.name.duplicate"));
    }

    @Test
    @WithMockUser(username = "bob", roles = "USER")
    void saveSession_byAnyoneButTheOwner_leavesTheSessionRunning() throws Exception {
        UUID aliceSession = UUID.randomUUID();
        when(sessionRegistry.find(eq(aliceSession), eq("bob"))).thenReturn(Optional.empty());

        mvc.perform(post("/recorder/" + aliceSession + "/speichern")
                        .with(csrf())
                        .param("name", "Gekaperter Ablauf"))
                .andExpect(status().isNotFound());

        verify(sessionRegistry, never()).close(aliceSession);
        verify(journeyService, never()).create(anyLong(), anyString(), any());
    }

    @Test
    @WithMockUser(username = "alice", roles = "USER")
    void closeMySessions_asAuthenticatedUser_closesUserSessionsAndRedirects() throws Exception {
        when(sessionRegistry.closeAllForUser("alice")).thenReturn(2);

        mvc.perform(post("/recorder/meine-sitzungen-beenden")
                        .with(csrf())
                        .param("siteId", "1"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/websites/1/journeys"))
                .andExpect(flash().attributeExists("flashMessage"));

        verify(sessionRegistry).closeAllForUser("alice");
    }

    @Test
    @WithMockUser(username = "admin", roles = "ADMIN")
    void closeAllSessions_asAdmin_closesAllSessionsAndRedirects() throws Exception {
        when(sessionRegistry.closeAll()).thenReturn(2);

        mvc.perform(post("/recorder/alle-beenden")
                        .with(csrf())
                        .param("siteId", "1"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/websites/1/journeys"))
                .andExpect(flash().attributeExists("flashMessage"));

        verify(sessionRegistry).closeAll();
    }

    @Test
    @WithMockUser(username = "alice", roles = "USER")
    void closeAllSessions_asRegularUser_isForbidden() throws Exception {
        mvc.perform(post("/recorder/alle-beenden")
                        .with(csrf())
                        .param("siteId", "1"))
                .andExpect(status().isForbidden());

        verify(sessionRegistry, never()).closeAll();
    }

    @Test
    void unauthenticatedAccess_redirectsToLogin() throws Exception {
        UUID sessionId = UUID.randomUUID();

        mvc.perform(get("/websites/1/aufzeichnen"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/anmelden"));

        mvc.perform(post("/recorder/" + sessionId + "/beenden").with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/anmelden"));

        mvc.perform(post("/recorder/" + sessionId + "/speichern").with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/anmelden"));
    }
}
