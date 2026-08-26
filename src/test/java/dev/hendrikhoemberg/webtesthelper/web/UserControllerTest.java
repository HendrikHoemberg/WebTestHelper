package dev.hendrikhoemberg.webtesthelper.web;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.Mockito.doThrow;
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

@WebMvcTest(UserController.class)
class UserControllerTest {

    @Autowired
    MockMvc mvc;

    @MockitoBean
    AppUserService appUserService;

    private static AppUserSummary benutzer(long id, String name, AppRole role, boolean aktiv) {
        return new AppUserSummary(id, name, role, aktiv, Instant.now());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void lastEnabledAdminRowHasNoDisableDemoteOrDeleteControls() throws Exception {
        when(appUserService.list()).thenReturn(List.of(benutzer(1, "root", AppRole.ADMIN, true)));
        when(appUserService.enabledAdminCount()).thenReturn(1L);

        mvc.perform(get("/einstellungen/benutzer"))
                .andExpect(status().isOk())
                .andExpect(view().name("einstellungen/benutzer"))
                .andExpect(content().string(containsString("Administrator")))
                .andExpect(content().string(not(containsString("ADMIN"))))
                .andExpect(content().string(not(containsString("Deaktivieren"))))
                .andExpect(content().string(not(containsString("zurückstufen"))))
                .andExpect(content().string(not(containsString("Löschen"))));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void userRowShowsControlsWhileLastAdminRowHidesThem() throws Exception {
        when(appUserService.list()).thenReturn(List.of(
                benutzer(1, "alice", AppRole.USER, true),
                benutzer(2, "bob", AppRole.ADMIN, true)));
        when(appUserService.enabledAdminCount()).thenReturn(1L);

        mvc.perform(get("/einstellungen/benutzer"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Zum Administrator machen")))
                .andExpect(content().string(containsString("Deaktivieren")))
                .andExpect(content().string(containsString("Löschen")))
                .andExpect(content().string(containsString("Administrator")))
                .andExpect(content().string(not(containsString("ADMIN"))))
                .andExpect(content().string(not(containsString("zurückstufen"))));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void createUserRedirectsToUserListWithFlash() throws Exception {
        when(appUserService.create("carol", "geheim123", AppRole.USER)).thenReturn(7L);

        mvc.perform(post("/einstellungen/benutzer")
                        .with(csrf())
                        .param("username", "carol")
                        .param("password", "geheim123"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/einstellungen/benutzer"))
                .andExpect(flash().attribute("benutzerAngelegt", true));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void createWithDuplicateUsernameReRendersFormWithFieldErrorAndIntactList() throws Exception {
        when(appUserService.list()).thenReturn(List.of(benutzer(1, "alice", AppRole.USER, true)));
        doThrow(new UserValidationException("user.username.duplicate", "bob"))
                .when(appUserService).create("bob", "geheim123", AppRole.USER);

        mvc.perform(post("/einstellungen/benutzer")
                        .with(csrf())
                        .param("username", "bob")
                        .param("password", "geheim123"))
                .andExpect(status().isOk())
                .andExpect(view().name("einstellungen/benutzer"))
                .andExpect(model().attributeHasFieldErrors("form", "username"))
                .andExpect(model().attributeExists("benutzer"))
                .andExpect(content().string(containsString("alice")));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void disablingTheLastEnabledAdminFlashesErrorAndRedirects() throws Exception {
        doThrow(new UserValidationException("user.lastAdmin", "root"))
                .when(appUserService).setEnabled(5L, false);

        mvc.perform(post("/einstellungen/benutzer/5")
                        .with(csrf())
                        .param("aktion", "aktiv")
                        .param("aktiv", "false"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/einstellungen/benutzer"))
                .andExpect(flash().attribute("flashError", containsString("kann nicht deaktiviert")));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void deleteUserRedirectsToUserListWithFlash() throws Exception {
        mvc.perform(post("/einstellungen/benutzer/5/loeschen")
                        .with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/einstellungen/benutzer"))
                .andExpect(flash().attribute("benutzerGeloescht", true));
    }
}
