package dev.hendrikhoemberg.webtesthelper.web;

import dev.hendrikhoemberg.webtesthelper.catalog.AppSettings;
import dev.hendrikhoemberg.webtesthelper.catalog.ImapSettings;
import dev.hendrikhoemberg.webtesthelper.catalog.SmtpSettings;
import dev.hendrikhoemberg.webtesthelper.catalog.TlsMode;
import com.icegreen.greenmail.util.GreenMail;
import com.icegreen.greenmail.util.ServerSetupTest;
import dev.hendrikhoemberg.webtesthelper.reporting.DeliveryResult;
import dev.hendrikhoemberg.webtesthelper.reporting.MailRenderer;
import dev.hendrikhoemberg.webtesthelper.reporting.OutboundMail;
import dev.hendrikhoemberg.webtesthelper.reporting.OutboxService;
import dev.hendrikhoemberg.webtesthelper.runner.CapacityService;
import dev.hendrikhoemberg.webtesthelper.runner.SystemCapacity;
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
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
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
@WebMvcTest(SettingsController.class)
class SettingsControllerTest {

    @Autowired
    MockMvc mvc;

    @MockitoBean
    AppSettings appSettings;

    @MockitoBean
    MailRenderer mailRenderer;

    @MockitoBean
    OutboxService outboxService;

    @MockitoBean
    AppUserService appUserService;

    @MockitoBean
    CapacityService capacityService;

    @MockitoBean
    dev.hendrikhoemberg.webtesthelper.reporting.WebhookNotifier webhookNotifier;

    @BeforeEach
    void capacityServiceStub() {
        when(capacityService.current(anyInt())).thenReturn(new SystemCapacity(4, 1, 3, 1, Duration.ofSeconds(30), 2));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void getSettingsAsAdminReturnsOkAndPasswordIsRenderedEmpty() throws Exception {
        when(appSettings.smtp()).thenReturn(new SmtpSettings(
                "smtp.example.com",
                587,
                TlsMode.STARTTLS,
                "admin-smtp",
                "SuperSecretPasswordInDatabase",
                "alerts@example.com"
        ));
        when(appSettings.baseUrl()).thenReturn("https://webtesthelper.example.com");
        when(appSettings.redirectAllMailTo()).thenReturn(Optional.empty());

        mvc.perform(get("/einstellungen"))
                .andExpect(status().isOk())
                .andExpect(view().name("einstellungen/index"))
                .andExpect(model().attributeExists("form"))
                .andExpect(content().string(containsString("smtp.example.com")))
                .andExpect(content().string(containsString("admin-smtp")))
                .andExpect(content().string(org.hamcrest.Matchers.not(containsString("SuperSecretPasswordInDatabase"))));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void alpineIsSeededFromDataAttributesRatherThanFromInterpolatedFormValues() throws Exception {
        // The conditional-field state used to be built by concatenating the stored username into
        // a JavaScript object literal. An apostrophe closes the literal early, which at best
        // breaks the panel and at worst lets one administrator put an expression on another's
        // screen. A data attribute is escaped by the parser and reaches Alpine as a plain string.
        when(appSettings.smtp()).thenReturn(new SmtpSettings(
                "smtp.example.com",
                587,
                TlsMode.STARTTLS,
                "o'brien",
                "irrelevant",
                "alerts@example.com"
        ));
        when(appSettings.baseUrl()).thenReturn("https://webtesthelper.example.com");
        when(appSettings.redirectAllMailTo()).thenReturn(Optional.empty());

        mvc.perform(get("/einstellungen"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("$el.dataset.username")))
                .andExpect(content().string(org.hamcrest.Matchers.not(containsString("username: 'o'"))));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void getSettingsRendersRedirectAllMailWarningSentenceWhenSet() throws Exception {
        when(appSettings.smtp()).thenReturn(new SmtpSettings(
                "smtp.example.com",
                587,
                TlsMode.STARTTLS,
                "admin-smtp",
                "",
                "alerts@example.com"
        ));
        when(appSettings.baseUrl()).thenReturn("https://webtesthelper.example.com");
        when(appSettings.redirectAllMailTo()).thenReturn(Optional.of("staging-catchall@example.com"));

        mvc.perform(get("/einstellungen"))
                .andExpect(status().isOk())
                .andExpect(view().name("einstellungen/index"))
                .andExpect(content().string(containsString("staging-catchall@example.com")))
                .andExpect(content().string(containsString("ausschließlich an diese Adresse")));
    }

    @Test
    @WithMockUser(roles = "USER")
    void getSettingsAsUserIsForbidden() throws Exception {
        mvc.perform(get("/einstellungen"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void postSettingsWithoutCsrfIsForbidden() throws Exception {
        mvc.perform(post("/einstellungen")
                        .param("host", "smtp.example.com")
                        .param("port", "587")
                        .param("tls", "STARTTLS")
                        .param("fromAddress", "alerts@example.com")
                        .param("baseUrl", "https://webtesthelper.example.com"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void postSettingsWithBlankPasswordKeepsExistingPassword() throws Exception {
        when(appSettings.smtp()).thenReturn(new SmtpSettings(
                "smtp.example.com",
                587,
                TlsMode.STARTTLS,
                "old-user",
                "ExistingSecretPassword",
                "alerts@example.com"
        ));

        mvc.perform(post("/einstellungen")
                        .with(csrf())
                        .param("host", "smtp.example.com")
                        .param("port", "587")
                        .param("tls", "STARTTLS")
                        .param("username", "new-user")
                        .param("password", "") // blank means keep existing
                        .param("fromAddress", "alerts@example.com")
                        .param("baseUrl", "https://webtesthelper.example.com/")
                        .param("redirectAllMailTo", ""))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/einstellungen"));

        ArgumentCaptor<SmtpSettings> captor = ArgumentCaptor.forClass(SmtpSettings.class);
        verify(appSettings).saveSmtp(captor.capture());
        assertThat(captor.getValue().password()).isEqualTo("ExistingSecretPassword");
        assertThat(captor.getValue().username()).isEqualTo("new-user");

        verify(appSettings).saveBaseUrl("https://webtesthelper.example.com/");
        verify(appSettings).saveRedirectAllMailTo("");
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void postSettingsWithNewPasswordUpdatesPassword() throws Exception {
        when(appSettings.smtp()).thenReturn(new SmtpSettings(
                "smtp.example.com",
                587,
                TlsMode.STARTTLS,
                "admin",
                "OldPassword",
                "alerts@example.com"
        ));

        mvc.perform(post("/einstellungen")
                        .with(csrf())
                        .param("host", "smtp.example.com")
                        .param("port", "587")
                        .param("tls", "STARTTLS")
                        .param("username", "admin")
                        .param("password", "BrandNewPassword123")
                        .param("fromAddress", "alerts@example.com")
                        .param("baseUrl", "https://webtesthelper.example.com")
                        .param("redirectAllMailTo", "catchall@example.com"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/einstellungen"));

        ArgumentCaptor<SmtpSettings> captor = ArgumentCaptor.forClass(SmtpSettings.class);
        verify(appSettings).saveSmtp(captor.capture());
        assertThat(captor.getValue().password()).isEqualTo("BrandNewPassword123");

        verify(appSettings).saveBaseUrl("https://webtesthelper.example.com");
        verify(appSettings).saveRedirectAllMailTo("catchall@example.com");
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void postSettingsWithBlankBaseUrlReRendersWithFieldErrorAndCallsNothing() throws Exception {
        mvc.perform(post("/einstellungen")
                        .with(csrf())
                        .param("host", "smtp.example.com")
                        .param("port", "587")
                        .param("tls", "STARTTLS")
                        .param("fromAddress", "alerts@example.com")
                        .param("baseUrl", "  "))
                .andExpect(status().isOk())
                .andExpect(view().name("einstellungen/index"))
                .andExpect(model().attributeHasFieldErrors("form", "baseUrl"));

        verify(appSettings, never()).saveSmtp(any());
        verify(appSettings, never()).saveBaseUrl(any());
        verify(appSettings, never()).saveRedirectAllMailTo(any());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void postSettingsWithBaseUrlMissingSchemeReRendersWithFieldErrorAndCallsNothing() throws Exception {
        mvc.perform(post("/einstellungen")
                        .with(csrf())
                        .param("host", "smtp.example.com")
                        .param("port", "587")
                        .param("tls", "STARTTLS")
                        .param("fromAddress", "alerts@example.com")
                        .param("baseUrl", "example.com/no-scheme"))
                .andExpect(status().isOk())
                .andExpect(view().name("einstellungen/index"))
                .andExpect(model().attributeHasFieldErrors("form", "baseUrl"));

        verify(appSettings, never()).saveSmtp(any());
        verify(appSettings, never()).saveBaseUrl(any());
        verify(appSettings, never()).saveRedirectAllMailTo(any());
    }

    @Test
    @WithMockUser(roles = "USER")
    void postTestMailAsUserIsForbidden() throws Exception {
        mvc.perform(post("/einstellungen/testmail").with(csrf()))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void postTestMailAsAdminSuccessFlashesSuccess() throws Exception {
        when(appSettings.smtp()).thenReturn(new SmtpSettings(
                "smtp.example.com",
                587,
                TlsMode.STARTTLS,
                "admin",
                "secret",
                "alerts@example.com"
        ));
        when(appSettings.baseUrl()).thenReturn("https://webtesthelper.example.com");

        OutboundMail mail = new OutboundMail("alerts@example.com", "Test Subject", "<p>HTML</p>", "Text");
        when(mailRenderer.testMail("alerts@example.com", "https://webtesthelper.example.com")).thenReturn(mail);
        when(outboxService.enqueue(mail)).thenReturn(99L);
        when(outboxService.sendNow(99L)).thenReturn(DeliveryResult.successful());

        mvc.perform(post("/einstellungen/testmail").with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/einstellungen"))
                .andExpect(flash().attribute("testmailErfolg", true));

        verify(outboxService).enqueue(mail);
        verify(outboxService).sendNow(99L);
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void postTestMailAsAdminFailureFlashesError() throws Exception {
        when(appSettings.smtp()).thenReturn(new SmtpSettings(
                "smtp.example.com",
                587,
                TlsMode.STARTTLS,
                "admin",
                "secret",
                "alerts@example.com"
        ));
        when(appSettings.baseUrl()).thenReturn("https://webtesthelper.example.com");

        OutboundMail mail = new OutboundMail("alerts@example.com", "Test Subject", "<p>HTML</p>", "Text");
        when(mailRenderer.testMail("alerts@example.com", "https://webtesthelper.example.com")).thenReturn(mail);
        when(outboxService.enqueue(mail)).thenReturn(100L);
        when(outboxService.sendNow(100L)).thenReturn(DeliveryResult.failed("Connection timeout on smtp.example.com:587"));

        mvc.perform(post("/einstellungen/testmail").with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/einstellungen"))
                .andExpect(flash().attribute("testmailFehler", "Connection timeout on smtp.example.com:587"));

        verify(outboxService).enqueue(mail);
        verify(outboxService).sendNow(100L);
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void postTestMailWithFormInputsPersistsSettingsBeforeSending() throws Exception {
        when(appSettings.smtp()).thenReturn(new SmtpSettings(
                "old.example.com", 25, TlsMode.NONE, "olduser", "oldpass", "old@example.com"
        ));
        when(appSettings.baseUrl()).thenReturn("https://example.com");
        OutboundMail mail = new OutboundMail("new@example.com", "Test Subject", "<p>HTML</p>", "Text");
        when(mailRenderer.testMail(eq("new@example.com"), any())).thenReturn(mail);
        when(outboxService.enqueue(mail)).thenReturn(101L);
        when(outboxService.sendNow(101L)).thenReturn(DeliveryResult.successful());

        mvc.perform(post("/einstellungen/testmail")
                        .with(csrf())
                        .param("host", "smtp.newhost.com")
                        .param("port", "587")
                        .param("tls", "STARTTLS")
                        .param("username", "newuser")
                        .param("password", "newsecret")
                        .param("fromAddress", "new@example.com")
                        .param("baseUrl", "https://example.com"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/einstellungen"))
                .andExpect(flash().attribute("testmailErfolg", true));

        verify(appSettings).saveSmtp(argThat(s ->
                "smtp.newhost.com".equals(s.host()) && "new@example.com".equals(s.fromAddress())));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void getSettingsRendersSmtpTestButtonInsideSmtpCard() throws Exception {
        when(appSettings.smtp()).thenReturn(new SmtpSettings(
                "smtp.example.com", 587, TlsMode.STARTTLS, "admin", "secret", "alerts@example.com"
        ));
        when(appSettings.baseUrl()).thenReturn("https://example.com");
        when(appSettings.redirectAllMailTo()).thenReturn(Optional.empty());

        mvc.perform(get("/einstellungen"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("btn-smtp-test")))
                .andExpect(content().string(containsString("Test-E-Mail senden")));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void postSettingsWithSchedulingPausedCheckedPersistsTrue() throws Exception {
        mvc.perform(post("/einstellungen")
                        .with(csrf())
                        .param("host", "smtp.example.com")
                        .param("port", "587")
                        .param("tls", "STARTTLS")
                        .param("password", "secret")
                        .param("fromAddress", "alerts@example.com")
                        .param("baseUrl", "https://webtesthelper.example.com")
                        .param("schedulingPaused", "true"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/einstellungen"));

        verify(appSettings).saveSchedulingPaused(true);
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void postSettingsWithSchedulingPausedUncheckedPersistsFalse() throws Exception {
        mvc.perform(post("/einstellungen")
                        .with(csrf())
                        .param("host", "smtp.example.com")
                        .param("port", "587")
                        .param("tls", "STARTTLS")
                        .param("password", "secret")
                        .param("fromAddress", "alerts@example.com")
                        .param("baseUrl", "https://webtesthelper.example.com"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/einstellungen"));

        // An unchecked checkbox binds null, which must come out as not-paused.
        verify(appSettings).saveSchedulingPaused(false);
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void getSettingsRendersPauseBannerWhenPaused() throws Exception {
        when(appSettings.smtp()).thenReturn(new SmtpSettings(
                "smtp.example.com", 587, TlsMode.STARTTLS, "admin-smtp", "", "alerts@example.com"));
        when(appSettings.baseUrl()).thenReturn("https://webtesthelper.example.com");
        when(appSettings.redirectAllMailTo()).thenReturn(Optional.empty());
        when(appSettings.schedulingPaused()).thenReturn(true);

        mvc.perform(get("/einstellungen"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Die Planung ist angehalten")));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void getSettingsDoesNotRenderPauseBannerWhenNotPaused() throws Exception {
        when(appSettings.smtp()).thenReturn(new SmtpSettings(
                "smtp.example.com", 587, TlsMode.STARTTLS, "admin-smtp", "", "alerts@example.com"));
        when(appSettings.baseUrl()).thenReturn("https://webtesthelper.example.com");
        when(appSettings.redirectAllMailTo()).thenReturn(Optional.empty());
        when(appSettings.schedulingPaused()).thenReturn(false);

        mvc.perform(get("/einstellungen"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.not(containsString("Die Planung ist angehalten"))));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void getSettingsLoadsAndRendersFallbackRecipientsAndHelpAffordance() throws Exception {
        when(appSettings.smtp()).thenReturn(new SmtpSettings(
                "smtp.example.com", 587, TlsMode.STARTTLS, "admin-smtp", "", "alerts@example.com"));
        when(appSettings.baseUrl()).thenReturn("https://webtesthelper.example.com");
        when(appSettings.redirectAllMailTo()).thenReturn(Optional.empty());
        when(appSettings.fallbackRecipients()).thenReturn(List.of("fb1@example.com", "fb2@example.com"));

        mvc.perform(get("/einstellungen"))
                .andExpect(status().isOk())
                .andExpect(view().name("einstellungen/index"))
                .andExpect(content().string(containsString("fb1@example.com")))
                .andExpect(content().string(containsString("fb2@example.com")))
                .andExpect(content().string(containsString("benachrichtigungen")));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void postSettingsWithValidFallbackRecipientsSavesThem() throws Exception {
        mvc.perform(post("/einstellungen")
                        .with(csrf())
                        .param("host", "smtp.example.com")
                        .param("port", "587")
                        .param("tls", "STARTTLS")
                        .param("password", "secret")
                        .param("fromAddress", "alerts@example.com")
                        .param("baseUrl", "https://webtesthelper.example.com")
                        .param("fallbackRecipients", "fb1@example.com, fb2@example.com"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/einstellungen"));

        verify(appSettings).saveFallbackRecipients("fb1@example.com, fb2@example.com");
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void postSettingsWithInvalidFallbackRecipientsReRendersWithFieldErrorAndDoesNotSave() throws Exception {
        mvc.perform(post("/einstellungen")
                        .with(csrf())
                        .param("host", "smtp.example.com")
                        .param("port", "587")
                        .param("tls", "STARTTLS")
                        .param("password", "secret")
                        .param("fromAddress", "alerts@example.com")
                        .param("baseUrl", "https://webtesthelper.example.com")
                        .param("fallbackRecipients", "valid@example.com, not-an-email"))
                .andExpect(status().isOk())
                .andExpect(view().name("einstellungen/index"))
                .andExpect(model().attributeHasFieldErrors("form", "fallbackRecipients"));

        verify(appSettings, never()).saveFallbackRecipients(any());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void getSettingsRendersCapacityPanelReadOnly() throws Exception {
        when(appSettings.smtp()).thenReturn(new SmtpSettings(
                "smtp.example.com", 587, TlsMode.STARTTLS, "admin-smtp", "", "alerts@example.com"));
        when(appSettings.baseUrl()).thenReturn("https://webtesthelper.example.com");
        when(appSettings.redirectAllMailTo()).thenReturn(Optional.empty());
        when(outboxService.failedCount()).thenReturn(2);
        when(capacityService.current(2)).thenReturn(new SystemCapacity(4, 1, 3, 2, Duration.ofSeconds(30), 2));

        String body = mvc.perform(get("/einstellungen"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        int panelStart = body.indexOf("id=\"systemlast\"");
        assertThat(panelStart).isGreaterThan(-1);
        int panelEnd = body.indexOf("</section>", panelStart);
        assertThat(panelEnd).isGreaterThan(panelStart);
        String panel = body.substring(panelStart, panelEnd);

        assertThat(panel)
                .contains("WTH_BROWSER_WORKERS")
                .contains("1 belegt von 4")
                .contains("Neustart")
                .doesNotContain("<input")
                .doesNotContain("<form");
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void getSettingsRendersImapSectionAndPasswordIsRenderedEmpty() throws Exception {
        when(appSettings.smtp()).thenReturn(new SmtpSettings(
                "smtp.example.com", 587, TlsMode.STARTTLS, "admin-smtp", "", "alerts@example.com"));
        when(appSettings.imap()).thenReturn(new ImapSettings(
                "imap.example.com", 993, TlsMode.SSL, "admin-imap", "SuperSecretImapPassword", "INBOX", "verify@example.com"));
        when(appSettings.baseUrl()).thenReturn("https://webtesthelper.example.com");
        when(appSettings.redirectAllMailTo()).thenReturn(Optional.empty());

        mvc.perform(get("/einstellungen"))
                .andExpect(status().isOk())
                .andExpect(view().name("einstellungen/index"))
                .andExpect(content().string(containsString("imap.example.com")))
                .andExpect(content().string(containsString("admin-imap")))
                .andExpect(content().string(containsString("verify@example.com")))
                .andExpect(content().string(org.hamcrest.Matchers.not(containsString("SuperSecretImapPassword"))));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void postSettingsWithBlankImapPasswordKeepsExistingPassword() throws Exception {
        when(appSettings.smtp()).thenReturn(new SmtpSettings(
                "smtp.example.com", 587, TlsMode.STARTTLS, "admin", "secret", "alerts@example.com"));
        when(appSettings.imap()).thenReturn(new ImapSettings(
                "imap.example.com", 993, TlsMode.SSL, "old-imap-user", "ExistingSecretImapPassword", "INBOX", "verify@example.com"));

        mvc.perform(post("/einstellungen")
                        .with(csrf())
                        .param("host", "smtp.example.com")
                        .param("port", "587")
                        .param("tls", "STARTTLS")
                        .param("fromAddress", "alerts@example.com")
                        .param("baseUrl", "https://webtesthelper.example.com")
                        .param("imapHost", "imap.example.com")
                        .param("imapPort", "993")
                        .param("imapTls", "SSL")
                        .param("imapUsername", "new-imap-user")
                        .param("imapPassword", "")
                        .param("imapFolder", "INBOX")
                        .param("imapVerificationAddress", "verify@example.com"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/einstellungen"));

        ArgumentCaptor<ImapSettings> captor = ArgumentCaptor.forClass(ImapSettings.class);
        verify(appSettings).saveImap(captor.capture());
        assertThat(captor.getValue().password()).isEqualTo("ExistingSecretImapPassword");
        assertThat(captor.getValue().username()).isEqualTo("new-imap-user");
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void postSettingsWithNewImapPasswordUpdatesPassword() throws Exception {
        when(appSettings.smtp()).thenReturn(new SmtpSettings(
                "smtp.example.com", 587, TlsMode.STARTTLS, "admin", "secret", "alerts@example.com"));
        when(appSettings.imap()).thenReturn(new ImapSettings(
                "imap.example.com", 993, TlsMode.SSL, "old-imap-user", "OldImapPassword", "INBOX", "verify@example.com"));

        mvc.perform(post("/einstellungen")
                        .with(csrf())
                        .param("host", "smtp.example.com")
                        .param("port", "587")
                        .param("tls", "STARTTLS")
                        .param("fromAddress", "alerts@example.com")
                        .param("baseUrl", "https://webtesthelper.example.com")
                        .param("imapHost", "imap.example.com")
                        .param("imapPort", "993")
                        .param("imapTls", "SSL")
                        .param("imapUsername", "admin-imap")
                        .param("imapPassword", "BrandNewImapPassword123")
                        .param("imapFolder", "INBOX")
                        .param("imapVerificationAddress", "verify@example.com"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/einstellungen"));

        ArgumentCaptor<ImapSettings> captor = ArgumentCaptor.forClass(ImapSettings.class);
        verify(appSettings).saveImap(captor.capture());
        assertThat(captor.getValue().password()).isEqualTo("BrandNewImapPassword123");
    }

    @Test
    @WithMockUser(roles = "USER")
    void postPostfachTestAsUserIsForbidden() throws Exception {
        mvc.perform(post("/einstellungen/postfach-test").with(csrf()))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void postPostfachTestWithoutCsrfIsForbidden() throws Exception {
        mvc.perform(post("/einstellungen/postfach-test"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void postPostfachTestUnconfiguredFlashesError() throws Exception {
        when(appSettings.imap()).thenReturn(new ImapSettings(null, 993, TlsMode.SSL, null, null, "INBOX", null));

        mvc.perform(post("/einstellungen/postfach-test").with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/einstellungen"))
                .andExpect(flash().attributeExists("postfachFehler"));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void postPostfachTestSuccessFlashesMessageCount() throws Exception {
        GreenMail greenMail = new GreenMail(ServerSetupTest.IMAP);
        greenMail.start();
        try {
            greenMail.setUser("verify@example.com", "imap-user", "test-pass");
            when(appSettings.imap()).thenReturn(new ImapSettings(
                    "127.0.0.1",
                    greenMail.getImap().getPort(),
                    TlsMode.NONE,
                    "imap-user",
                    "test-pass",
                    "INBOX",
                    "verify@example.com"
            ));

            mvc.perform(post("/einstellungen/postfach-test").with(csrf()))
                    .andExpect(status().is3xxRedirection())
                    .andExpect(redirectedUrl("/einstellungen"))
                    .andExpect(flash().attribute("postfachErfolg", 0));
        } finally {
            greenMail.stop();
        }
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void postSettings_savesWebhookSettings() throws Exception {
        mvc.perform(post("/einstellungen").with(csrf())
                        .param("baseUrl", "https://example.com")
                        .param("webhookUrl", "https://hooks.slack.com/services/T00/B00/X00")
                        .param("webhookEnabled", "true")
                        .param("webhookOnlyCritical", "false")
                        .param("fallbackRecipients", "admin@example.com"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/einstellungen"))
                .andExpect(flash().attribute("gespeichert", true));

        verify(appSettings).saveWebhookUrl("https://hooks.slack.com/services/T00/B00/X00");
        verify(appSettings).saveWebhookEnabled(true);
        verify(appSettings).saveWebhookOnlyCritical(false);
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void postWebhookTest_callsWebhookNotifierAndRedirects() throws Exception {
        when(webhookNotifier.sendTestNotification("https://hooks.slack.com/services/T00/B00/X00"))
                .thenReturn(new dev.hendrikhoemberg.webtesthelper.reporting.WebhookResult(true, "OK 200"));

        mvc.perform(post("/einstellungen/webhook-test").with(csrf())
                        .param("webhookUrl", "https://hooks.slack.com/services/T00/B00/X00"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/einstellungen"))
                .andExpect(flash().attribute("webhookErfolg", "OK 200"));

        verify(webhookNotifier).sendTestNotification("https://hooks.slack.com/services/T00/B00/X00");
    }
}

