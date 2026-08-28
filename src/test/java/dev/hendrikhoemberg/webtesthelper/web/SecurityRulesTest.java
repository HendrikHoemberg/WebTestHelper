package dev.hendrikhoemberg.webtesthelper.web;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrlPattern;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(SecurityConfig.class)
class SecurityRulesTest {

    @Autowired
    MockMvc mvc;

    @MockitoBean
    AppUserService appUserService;

    @Test
    void anonymousRootRedirectsToAnmelden() throws Exception {
        mvc.perform(get("/"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/anmelden"));
    }

    @Test
    void anonymousAnmeldenReturnsOk() throws Exception {
        mvc.perform(get("/anmelden"))
                .andExpect(status().isOk());
    }

    @Test
    void anonymousStaticAssetsAreNotRedirectedToLogin() throws Exception {
        mvc.perform(get("/vendor/htmx.min.js"))
                .andExpect(status().is(org.hamcrest.Matchers.in(java.util.List.of(200, 404))))
                .andExpect(header().doesNotExist("Location"));
    }

    @Test
    @WithMockUser(roles = "USER")
    void userCannotAccessSettingsOrOutboxButCanAccessRun() throws Exception {
        mvc.perform(get("/einstellungen"))
                .andExpect(status().isForbidden());

        mvc.perform(get("/postausgang"))
                .andExpect(status().isForbidden());

        mvc.perform(get("/laeufe/1"))
                .andExpect(status().isNotFound());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void adminCanAccessSettings() throws Exception {
        mvc.perform(get("/einstellungen"))
                .andExpect(status().isNotFound());
    }

    @Test
    @WithMockUser(roles = "USER")
    void userCanReachAllSetupPaths() throws Exception {
        mvc.perform(get("/websites/1/einrichtung"))
                .andExpect(status().isNotFound());
        mvc.perform(get("/websites/1/einrichtung/stand"))
                .andExpect(status().isNotFound());
        mvc.perform(post("/websites/1/einrichtung").with(csrf()))
                .andExpect(status().isNotFound());
        mvc.perform(post("/websites/1/einrichtung/neu").with(csrf()))
                .andExpect(status().isNotFound());
    }

    @Test
    void anonymousSetupPathsRedirectToAnmelden() throws Exception {
        mvc.perform(get("/websites/1/einrichtung"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/anmelden"));
        mvc.perform(get("/websites/1/einrichtung/stand"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/anmelden"));
        mvc.perform(post("/websites/1/einrichtung").with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/anmelden"));
        mvc.perform(post("/websites/1/einrichtung/neu").with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/anmelden"));
    }

    @Test
    @WithMockUser(roles = "USER")
    void mutatingActionRequiresCsrf() throws Exception {
        mvc.perform(post("/websites/1/pruefen"))
                .andExpect(status().isForbidden());

        mvc.perform(post("/websites/1/pruefen").with(csrf()))
                .andExpect(status().isNotFound());
    }

    @Test
    @WithMockUser(roles = "USER")
    void logoutRedirectsToAnmeldenWithParam() throws Exception {
        mvc.perform(post("/abmelden").with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/anmelden?abgemeldet"));
    }

    @Test
    void anonymousStummschaltungenRedirectsToAnmelden() throws Exception {
        mvc.perform(get("/stummschaltungen"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/anmelden"));
    }

    @Test
    @WithMockUser(roles = "USER")
    void userCanAccessStummschaltungen() throws Exception {
        mvc.perform(get("/stummschaltungen"))
                .andExpect(status().isNotFound());
    }

    @Test
    @WithMockUser(roles = "USER")
    void userCannotAddOrDeleteSiteRecipients() throws Exception {
        mvc.perform(post("/websites/1/empfaenger").with(csrf()).param("email", "test@example.com"))
                .andExpect(status().isForbidden());

        mvc.perform(post("/websites/1/empfaenger/1/loeschen").with(csrf()))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "USER")
    void userCannotAccessCredentialMutationEndpoints() throws Exception {
        mvc.perform(post("/websites/1/zugangsdaten").with(csrf()))
                .andExpect(status().isForbidden());

        mvc.perform(post("/websites/1/zugangsdaten/1").with(csrf()))
                .andExpect(status().isForbidden());

        mvc.perform(post("/websites/1/zugangsdaten/1/loeschen").with(csrf()))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void adminCanAccessCredentialMutationEndpoints() throws Exception {
        mvc.perform(post("/websites/1/zugangsdaten").with(csrf()))
                .andExpect(status().isNotFound());

        mvc.perform(post("/websites/1/zugangsdaten/1").with(csrf()))
                .andExpect(status().isNotFound());

        mvc.perform(post("/websites/1/zugangsdaten/1/loeschen").with(csrf()))
                .andExpect(status().isNotFound());
    }

    @Test
    @WithMockUser(roles = "USER")
    void userCannotAccessUserAdministration() throws Exception {
        mvc.perform(get("/einstellungen/benutzer"))
                .andExpect(status().isForbidden());

        mvc.perform(post("/einstellungen/benutzer").with(csrf()))
                .andExpect(status().isForbidden());

        mvc.perform(post("/einstellungen/benutzer/1").with(csrf()))
                .andExpect(status().isForbidden());

        mvc.perform(post("/einstellungen/benutzer/1/loeschen").with(csrf()))
                .andExpect(status().isForbidden());
    }

    @Test
    void anonymousRecorderWebSocketRedirectsToAnmelden() throws Exception {
        mvc.perform(get("/recorder/ws/5f01ffa4-9864-4996-b761-bef67e716e76"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/anmelden"));
    }

    @Test
    @WithMockUser(roles = "USER")
    void userCanAccessRecorderWebSocketEndpointInSecurityFilter() throws Exception {
        // Authenticated user passes Spring Security filter chain (returns 404 in mock MVC without controller)
        mvc.perform(get("/recorder/ws/5f01ffa4-9864-4996-b761-bef67e716e76"))
                .andExpect(status().isNotFound());
    }
}
