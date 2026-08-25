package dev.hendrikhoemberg.webtesthelper.web;

import dev.hendrikhoemberg.webtesthelper.catalog.AppSettings;
import dev.hendrikhoemberg.webtesthelper.catalog.SmtpSettings;
import dev.hendrikhoemberg.webtesthelper.catalog.TlsMode;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

@WebMvcTest(SettingsController.class)
class SettingsControllerTest {

    @Autowired
    MockMvc mvc;

    @MockitoBean
    AppSettings appSettings;

    @MockitoBean
    AppUserService appUserService;

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
                .andExpect(content().string(containsString("ausschliesslich an diese Adresse")));
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
}
