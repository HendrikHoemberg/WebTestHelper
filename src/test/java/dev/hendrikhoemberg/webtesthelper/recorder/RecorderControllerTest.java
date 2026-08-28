package dev.hendrikhoemberg.webtesthelper.recorder;

import dev.hendrikhoemberg.webtesthelper.catalog.SiteService;
import dev.hendrikhoemberg.webtesthelper.model.CrawlBudget;
import dev.hendrikhoemberg.webtesthelper.model.SiteContext;
import dev.hendrikhoemberg.webtesthelper.model.UrlNormalizer;
import dev.hendrikhoemberg.webtesthelper.web.AppUserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
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

@WebMvcTest(RecorderController.class)
class RecorderControllerTest {

    @Autowired
    MockMvc mvc;

    @MockitoBean
    SiteService siteService;

    @MockitoBean
    RecordingSessionRegistry sessionRegistry;

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
                .andExpect(content().string(containsString("Aufzeichnung beenden")))
                .andExpect(content().string(not(containsString("Maximale Anzahl gleichzeitiger Aufzeichnungssitzungen"))));

        verify(sessionRegistry).open(1L, "https://acme.example.com/", "alice");
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
                .andExpect(content().string(not(containsString("<canvas id=\"recorder-canvas\""))));
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
                .andExpect(redirectedUrl("/sites/1/journeys"));

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
    void unauthenticatedAccess_redirectsToLogin() throws Exception {
        UUID sessionId = UUID.randomUUID();

        mvc.perform(get("/websites/1/aufzeichnen"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/anmelden"));

        mvc.perform(post("/recorder/" + sessionId + "/beenden").with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/anmelden"));
    }
}
