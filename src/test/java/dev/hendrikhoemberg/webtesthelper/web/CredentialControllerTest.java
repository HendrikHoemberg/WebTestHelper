package dev.hendrikhoemberg.webtesthelper.web;

import dev.hendrikhoemberg.webtesthelper.catalog.AppSettings;
import dev.hendrikhoemberg.webtesthelper.catalog.Credential;
import dev.hendrikhoemberg.webtesthelper.catalog.CredentialService;
import dev.hendrikhoemberg.webtesthelper.catalog.RecipientService;
import dev.hendrikhoemberg.webtesthelper.catalog.SiteService;
import dev.hendrikhoemberg.webtesthelper.catalog.SiteSummary;
import dev.hendrikhoemberg.webtesthelper.checks.CheckRegistry;
import dev.hendrikhoemberg.webtesthelper.findings.FindingService;
import dev.hendrikhoemberg.webtesthelper.model.CrawlBudget;
import dev.hendrikhoemberg.webtesthelper.model.SiteContext;
import dev.hendrikhoemberg.webtesthelper.model.UrlNormalizer;
import dev.hendrikhoemberg.webtesthelper.reporting.FindingViewFactory;
import dev.hendrikhoemberg.webtesthelper.runner.RunService;
import dev.hendrikhoemberg.webtesthelper.scheduling.ScheduleService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

@org.junit.jupiter.api.parallel.ResourceLock("spring-context")
@WebMvcTest({CredentialController.class, SiteController.class})
@Import(SiteDetailModel.class)
class CredentialControllerTest {

    @Autowired
    MockMvc mvc;

    @MockitoBean
    CredentialService credentialService;

    @MockitoBean
    RecipientService recipientService;

    @MockitoBean
    AppSettings appSettings;

    @MockitoBean
    SiteService siteService;

    @MockitoBean
    RunService runService;

    @MockitoBean
    CheckRegistry checkRegistry;

    @MockitoBean
    ScheduleService scheduleService;

    @MockitoBean
    AppUserService appUserService;

    @MockitoBean
    FindingService findingService;

    @MockitoBean
    FindingViewFactory findingViewFactory;

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
        when(siteService.summary(1L)).thenReturn(new SiteSummary(1L, "Acme Shop", "https://acme.example.com/", true, 0));
        when(runService.recentForSite(1L, 20)).thenReturn(List.of());
        when(runService.recentForSite(1L, 1)).thenReturn(List.of());
        when(findingService.openCountsBySite()).thenReturn(Map.of());
        when(checkRegistry.all()).thenReturn(CheckRegistry.standard().all());
        when(checkRegistry.category(any())).thenAnswer(inv -> CheckRegistry.standard().category(inv.getArgument(0)));
        when(scheduleService.forSite(1L)).thenReturn(List.of());
        when(recipientService.list(1L)).thenReturn(List.of());
        when(appSettings.fallbackRecipients()).thenReturn(List.of());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void getSiteDetailAsAdminRendersCredentialsTokensAndEmptyRotatePasswordInput() throws Exception {
        when(credentialService.list(1L)).thenReturn(List.of(
                new Credential(10L, 1L, "login", "admin", Instant.parse("2026-08-27T10:00:00Z"), true)
        ));

        mvc.perform(get("/websites/1/konfiguration"))
                .andExpect(status().isOk())
                .andExpect(view().name("websites/konfiguration"))
                .andExpect(content().string(containsString("login")))
                .andExpect(content().string(containsString("admin")))
                .andExpect(content().string(containsString("{{cred.login.password}}")))
                .andExpect(content().string(containsString("{{cred.login.username}}")))
                .andExpect(content().string(containsString("type=\"password\"")))
                .andExpect(content().string(not(containsString("name=\"passwort\" value="))))
                .andExpect(content().string(containsString("Zugangsdaten hinzufügen")));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void postCreateCredentialWithValidParamsCallsCreateAndRedirects() throws Exception {
        when(credentialService.create(1L, "login", "admin", "geheim123")).thenReturn(10L);

        mvc.perform(post("/websites/1/zugangsdaten")
                        .with(csrf())
                        .param("name", "login")
                        .param("benutzername", "admin")
                        .param("passwort", "geheim123"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/websites/1/konfiguration"));

        verify(credentialService).create(1L, "login", "admin", "geheim123");
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void postUpdateCredentialWithBlankPasswordCallsUpdateWithEmptyString() throws Exception {
        mvc.perform(post("/websites/1/zugangsdaten/10")
                        .with(csrf())
                        .param("benutzername", "newadmin")
                        .param("passwort", ""))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/websites/1/konfiguration"));

        verify(credentialService).update(1L, 10L, "newadmin", "");
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void postCreateDuplicateCredentialReRendersWithoutSubmittedPasswordInResponse() throws Exception {
        when(credentialService.list(1L)).thenReturn(List.of());
        when(credentialService.create(1L, "login", "admin", "super-secret-password-123"))
                .thenThrow(new IllegalArgumentException("credential.name.duplicate"));

        mvc.perform(post("/websites/1/zugangsdaten")
                        .with(csrf())
                        .param("name", "login")
                        .param("benutzername", "admin")
                        .param("passwort", "super-secret-password-123"))
                .andExpect(status().isOk())
                .andExpect(view().name("websites/konfiguration"))
                .andExpect(content().string(containsString("bereits Zugangsdaten mit diesem Namen hinterlegt")))
                .andExpect(content().string(not(containsString("super-secret-password-123"))));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void postDeleteCredentialCallsDeleteAndRedirects() throws Exception {
        mvc.perform(post("/websites/1/zugangsdaten/10/loeschen")
                        .with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/websites/1/konfiguration"));

        verify(credentialService).delete(1L, 10L);
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void getSiteDetailWithUnreadableCredentialRendersUnreadableWarning() throws Exception {
        when(credentialService.list(1L)).thenReturn(List.of(
                new Credential(10L, 1L, "broken", "user", Instant.parse("2026-08-27T10:00:00Z"), false)
        ));

        mvc.perform(get("/websites/1/konfiguration"))
                .andExpect(status().isOk())
                .andExpect(view().name("websites/konfiguration"))
                .andExpect(content().string(containsString("Dieses Passwort lässt sich nicht mehr entschlüsseln")));
    }
}
