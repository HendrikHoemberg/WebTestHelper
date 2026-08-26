package dev.hendrikhoemberg.webtesthelper.web;

import dev.hendrikhoemberg.webtesthelper.catalog.AppSettings;
import dev.hendrikhoemberg.webtesthelper.catalog.Recipient;
import dev.hendrikhoemberg.webtesthelper.catalog.RecipientService;
import dev.hendrikhoemberg.webtesthelper.catalog.SiteService;
import dev.hendrikhoemberg.webtesthelper.checks.CheckRegistry;
import dev.hendrikhoemberg.webtesthelper.model.CrawlBudget;
import dev.hendrikhoemberg.webtesthelper.model.SiteContext;
import dev.hendrikhoemberg.webtesthelper.model.UrlNormalizer;
import dev.hendrikhoemberg.webtesthelper.runner.RunService;
import dev.hendrikhoemberg.webtesthelper.scheduling.ScheduleService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

@WebMvcTest({RecipientController.class, SiteController.class})
@Import(SiteDetailModel.class)
class RecipientControllerTest {

    @Autowired
    MockMvc mvc;

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

    private SiteContext testSite;

    @BeforeEach
    void setUp() {
        testSite = new SiteContext(
                42L,
                "Acme Shop",
                UrlNormalizer.normalize("https://acme.example.com/").orElseThrow(),
                new CrawlBudget(120, 3, Duration.ofMinutes(15)),
                List.of(), List.of(), List.of(),
                true, "AcmeBot/2.0", Map.of()
        );
        when(siteService.contextFor(42L)).thenReturn(testSite);
        when(runService.recentForSite(42L, 20)).thenReturn(List.of());
        when(checkRegistry.all()).thenReturn(CheckRegistry.standard().all());
        when(scheduleService.forSite(42L)).thenReturn(List.of());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void getSiteDetailWithTwoRecipientsRendersBothAddressesAndAdminDeleteControls() throws Exception {
        when(recipientService.list(42L)).thenReturn(List.of(
                new Recipient(1L, 42L, "alice@example.com"),
                new Recipient(2L, 42L, "bob@example.com")
        ));
        when(appSettings.fallbackRecipients()).thenReturn(List.of("fallback@example.com"));

        mvc.perform(get("/websites/42"))
                .andExpect(status().isOk())
                .andExpect(view().name("websites/detail"))
                .andExpect(content().string(containsString("alice@example.com")))
                .andExpect(content().string(containsString("bob@example.com")))
                .andExpect(content().string(containsString("/websites/42/empfaenger/1/loeschen")))
                .andExpect(content().string(containsString("/websites/42/empfaenger/2/loeschen")))
                .andExpect(content().string(containsString("Empfänger hinzufügen")));
    }

    @Test
    @WithMockUser(roles = "USER")
    void getSiteDetailAsUserRendersRecipientsWithoutDeleteControlsOrAddForm() throws Exception {
        when(recipientService.list(42L)).thenReturn(List.of(
                new Recipient(1L, 42L, "alice@example.com")
        ));
        when(appSettings.fallbackRecipients()).thenReturn(List.of());

        mvc.perform(get("/websites/42"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("alice@example.com")))
                .andExpect(content().string(not(containsString("/websites/42/empfaenger/1/loeschen"))))
                .andExpect(content().string(not(containsString("Empfänger hinzufügen"))));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void getSiteDetailWithNoRecipientsAndFallbackExistsRendersFallbackAndSection134Sentence() throws Exception {
        when(recipientService.list(42L)).thenReturn(List.of());
        when(appSettings.fallbackRecipients()).thenReturn(List.of("fallback@example.com"));

        mvc.perform(get("/websites/42"))
                .andExpect(status().isOk())
                .andExpect(view().name("websites/detail"))
                .andExpect(content().string(containsString("fallback@example.com")))
                .andExpect(content().string(containsString("Ausweichadresse")));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void getSiteDetailWithNoRecipientsAndNoFallbackRendersWarningThatNoMailWillBeSent() throws Exception {
        when(recipientService.list(42L)).thenReturn(List.of());
        when(appSettings.fallbackRecipients()).thenReturn(List.of());

        mvc.perform(get("/websites/42"))
                .andExpect(status().isOk())
                .andExpect(view().name("websites/detail"))
                .andExpect(content().string(containsString("Keine Empfänger hinterlegt. Zu dieser Website werden keine Benachrichtigungen versendet.")));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void postRecipientWithValidEmailAddsRecipientAndRedirects() throws Exception {
        when(recipientService.add(42L, "neuer-empfaenger@example.com")).thenReturn(10L);

        mvc.perform(post("/websites/42/empfaenger")
                        .with(csrf())
                        .param("email", "neuer-empfaenger@example.com"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/websites/42"));

        verify(recipientService).add(42L, "neuer-empfaenger@example.com");
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void postRecipientWithMalformedEmailReRendersWithFieldErrorAndDoesNotAdd() throws Exception {
        when(recipientService.list(42L)).thenReturn(List.of());
        when(appSettings.fallbackRecipients()).thenReturn(List.of());

        mvc.perform(post("/websites/42/empfaenger")
                        .with(csrf())
                        .param("email", "keine-gueltige-email"))
                .andExpect(status().isOk())
                .andExpect(view().name("websites/detail"))
                .andExpect(content().string(containsString("Bitte eine gültige E-Mail-Adresse angeben.")));

        verify(recipientService, never()).add(eq(42L), any());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void postRecipientWithDuplicateEmailReRendersWithDuplicateError() throws Exception {
        when(recipientService.list(42L)).thenReturn(List.of(new Recipient(1L, 42L, "alice@example.com")));
        when(appSettings.fallbackRecipients()).thenReturn(List.of());
        when(recipientService.add(42L, "alice@example.com"))
                .thenThrow(new IllegalArgumentException("recipient.email.duplicate"));

        mvc.perform(post("/websites/42/empfaenger")
                        .with(csrf())
                        .param("email", "alice@example.com"))
                .andExpect(status().isOk())
                .andExpect(view().name("websites/detail"))
                .andExpect(content().string(containsString("bereits hinterlegt")));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void postDeleteRecipientCallsServiceAndRedirects() throws Exception {
        mvc.perform(post("/websites/42/empfaenger/1/loeschen")
                        .with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/websites/42"));

        verify(recipientService).remove(42L, 1L);
    }
}
