package dev.hendrikhoemberg.webtesthelper.challenger;

import dev.hendrikhoemberg.webtesthelper.catalog.SiteService;
import dev.hendrikhoemberg.webtesthelper.checks.CheckRegistry;
import dev.hendrikhoemberg.webtesthelper.runner.RunService;
import dev.hendrikhoemberg.webtesthelper.scheduling.Schedule;
import dev.hendrikhoemberg.webtesthelper.scheduling.ScheduleService;
import dev.hendrikhoemberg.webtesthelper.auth.AppRole;
import dev.hendrikhoemberg.webtesthelper.auth.AppUserService;
import dev.hendrikhoemberg.webtesthelper.web.ScheduleController;
import dev.hendrikhoemberg.webtesthelper.web.ScheduleFormModel;
import dev.hendrikhoemberg.webtesthelper.web.SecurityConfig;
import dev.hendrikhoemberg.webtesthelper.web.SiteDetailModel;
import dev.hendrikhoemberg.webtesthelper.auth.UserValidationException;
import dev.hendrikhoemberg.webtesthelper.auth.persistence.AppUserEntity;
import dev.hendrikhoemberg.webtesthelper.auth.persistence.AppUserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.ui.ConcurrentModel;
import org.springframework.ui.Model;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.BindingResult;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

/**
 * Adversarial boundary verification suite for Milestone 2:
 * - SEC-04: Sub-resource authorization consistency (ROLE_USER vs ROLE_ADMIN)
 * - SEC-02: Boundary password length validation in AppUserService
 * - SEC-03: Defensive null and empty schedule handling in ScheduleController
 */
@org.junit.jupiter.api.parallel.ResourceLock("spring-context")
@WebMvcTest({SecurityConfig.class, ScheduleController.class})
class Milestone2SecurityAdversarialTest {

    @Autowired
    MockMvc mvc;

    @MockitoBean
    AppUserService appUserService;

    @MockitoBean
    ScheduleService scheduleService;

    @MockitoBean
    SiteService siteService;

    @MockitoBean
    RunService runService;

    @MockitoBean
    CheckRegistry checkRegistry;

    @MockitoBean
    SiteDetailModel siteDetailModel;

    // =========================================================================
    // 1. SEC-04: Sub-Resource Authorization Consistency
    // =========================================================================
    @Nested
    @DisplayName("SEC-04: Sub-Resource Authorization Consistency")
    class Sec04SubResourceAuthTests {

        @Test
        @WithMockUser(roles = "USER")
        void userRoleBlockedFromPruefungenPost() throws Exception {
            mvc.perform(post("/websites/42/pruefungen").with(csrf()))
                    .andExpect(status().isForbidden());

            mvc.perform(post("/websites/42/pruefungen/subpath").with(csrf()))
                    .andExpect(status().isForbidden());
        }

        @Test
        @WithMockUser(roles = "ADMIN")
        void adminRoleAllowedForPruefungenPost() throws Exception {
            // Passes Spring Security filter chain; in this slice test, not mapped so returns 404
            mvc.perform(post("/websites/42/pruefungen").with(csrf()))
                    .andExpect(status().isNotFound());
        }

        @Test
        @WithMockUser(roles = "USER")
        void userRoleBlockedFromAusgangsbestandPost() throws Exception {
            mvc.perform(post("/laeufe/101/ausgangsbestand").with(csrf()))
                    .andExpect(status().isForbidden());

            mvc.perform(post("/laeufe/101/ausgangsbestand/subpath").with(csrf()))
                    .andExpect(status().isForbidden());
        }

        @Test
        @WithMockUser(roles = "ADMIN")
        void adminRoleAllowedForAusgangsbestandPost() throws Exception {
            mvc.perform(post("/laeufe/101/ausgangsbestand").with(csrf()))
                    .andExpect(status().isNotFound());
        }

        @Test
        @WithMockUser(roles = "USER")
        void userRoleBlockedFromJourneyDeletePost() throws Exception {
            mvc.perform(post("/websites/42/journeys/7/loeschen").with(csrf()))
                    .andExpect(status().isForbidden());
        }

        @Test
        @WithMockUser(roles = "ADMIN")
        void adminRoleAllowedForJourneyDeletePost() throws Exception {
            mvc.perform(post("/websites/42/journeys/7/loeschen").with(csrf()))
                    .andExpect(status().isNotFound());
        }

        @Test
        @WithMockUser(roles = "USER")
        void userRoleBlockedFromJourneyEditGetAndPost() throws Exception {
            mvc.perform(get("/websites/42/journeys/7/bearbeiten"))
                    .andExpect(status().isForbidden());

            mvc.perform(post("/websites/42/journeys/7/bearbeiten").with(csrf()))
                    .andExpect(status().isForbidden());

            mvc.perform(get("/websites/42/journeys/7/bearbeiten/step/1"))
                    .andExpect(status().isForbidden());

            mvc.perform(post("/websites/42/journeys/7/bearbeiten/step/1").with(csrf()))
                    .andExpect(status().isForbidden());
        }

        @Test
        @WithMockUser(roles = "ADMIN")
        void adminRoleAllowedForJourneyEditGetAndPost() throws Exception {
            mvc.perform(get("/websites/42/journeys/7/bearbeiten"))
                    .andExpect(status().isNotFound());

            mvc.perform(post("/websites/42/journeys/7/bearbeiten").with(csrf()))
                    .andExpect(status().isNotFound());
        }

        @Test
        void anonymousRedirectedToLogin() throws Exception {
            mvc.perform(get("/websites/42/journeys/7/bearbeiten"))
                    .andExpect(status().is3xxRedirection())
                    .andExpect(redirectedUrl("/anmelden"));

            mvc.perform(post("/websites/42/journeys/7/loeschen").with(csrf()))
                    .andExpect(status().is3xxRedirection())
                    .andExpect(redirectedUrl("/anmelden"));

            mvc.perform(post("/websites/42/pruefungen").with(csrf()))
                    .andExpect(status().is3xxRedirection())
                    .andExpect(redirectedUrl("/anmelden"));

            mvc.perform(post("/laeufe/101/ausgangsbestand").with(csrf()))
                    .andExpect(status().is3xxRedirection())
                    .andExpect(redirectedUrl("/anmelden"));
        }
    }

    // =========================================================================
    // 2. SEC-02: Boundary Password Lengths in AppUserService
    // =========================================================================
    @Nested
    @DisplayName("SEC-02: Password Boundary Verification")
    class Sec02PasswordBoundaryTests {

        private AppUserRepository repository = mock(AppUserRepository.class);
        private PasswordEncoder encoder = PasswordEncoderFactories.createDelegatingPasswordEncoder();
        private AppUserService service = new AppUserService(repository, encoder);

        @ParameterizedTest(name = "setPassword rejects invalid boundary password: ''{0}''")
        @NullAndEmptySource
        @ValueSource(strings = {"1", "ab", "abc", "abcd", "abcde", "abcdef", "1234567"}) // lengths 0..7
        void setPasswordRejectsUnderEightCharacters(String password) {
            assertThatThrownBy(() -> service.setPassword(42L, password))
                    .isInstanceOfSatisfying(UserValidationException.class, ex -> {
                        assertThat(ex.messageKey()).isEqualTo("user.password.tooShort");
                        assertThat(ex.args()).containsExactly(8);
                    });

            verify(repository, never()).save(any());
        }

        @Test
        void setPasswordAcceptsExactlyEightCharacters() {
            AppUserEntity entity = new AppUserEntity();
            entity.setId(42L);
            entity.setUsername("tester");
            entity.setRole(AppRole.USER);
            when(repository.findById(42L)).thenReturn(Optional.of(entity));

            service.setPassword(42L, "12345678"); // 8 chars boundary

            assertThat(encoder.matches("12345678", entity.getPasswordHash())).isTrue();
        }

        @Test
        void setPasswordAcceptsMoreThanEightCharacters() {
            AppUserEntity entity = new AppUserEntity();
            entity.setId(42L);
            entity.setUsername("tester");
            entity.setRole(AppRole.USER);
            when(repository.findById(42L)).thenReturn(Optional.of(entity));

            service.setPassword(42L, "123456789_long_password");

            assertThat(encoder.matches("123456789_long_password", entity.getPasswordHash())).isTrue();
        }

        @ParameterizedTest(name = "create rejects invalid boundary password: ''{0}''")
        @NullAndEmptySource
        @ValueSource(strings = {"a", "1234567"})
        void createRejectsUnderEightCharacters(String password) {
            assertThatThrownBy(() -> service.create("newuser", password, AppRole.USER))
                    .isInstanceOfSatisfying(UserValidationException.class, ex -> {
                        assertThat(ex.messageKey()).isEqualTo("user.password.tooShort");
                        assertThat(ex.args()).containsExactly(8);
                    });

            verify(repository, never()).save(any());
        }

        @Test
        void createAcceptsExactlyEightCharacters() {
            when(repository.existsByUsernameIgnoreCase("boundaryUser")).thenReturn(false);
            when(repository.save(any(AppUserEntity.class))).thenAnswer(inv -> {
                AppUserEntity e = inv.getArgument(0);
                e.setId(99L);
                return e;
            });

            long id = service.create("boundaryUser", "12345678", AppRole.USER);
            assertThat(id).isEqualTo(99L);
        }
    }

    // =========================================================================
    // 3. SEC-03: Defensive Validation in ScheduleController
    // =========================================================================
    @Nested
    @DisplayName("SEC-03: ScheduleController Defensive Validation")
    class Sec03ScheduleDefensiveValidationTests {

        @Test
        @WithMockUser(roles = "ADMIN")
        void postEmptyFormViaMockMvcReturnsCleanGermanErrorWithoutNpe() throws Exception {
            when(scheduleService.forSite(1L)).thenReturn(List.of());
            org.mockito.Mockito.doAnswer(inv -> {
                Model m = inv.getArgument(1);
                m.addAttribute("site", new dev.hendrikhoemberg.webtesthelper.model.SiteContext(1L, "Acme",
                        dev.hendrikhoemberg.webtesthelper.model.UrlNormalizer.normalize("https://acme.example.com/").orElseThrow(),
                        new dev.hendrikhoemberg.webtesthelper.model.CrawlBudget(10, 1, java.time.Duration.ofMinutes(1)),
                        List.of(), List.of(), List.of(), true, "bot", java.util.Map.of()));
                m.addAttribute("checkRows", List.of());
                m.addAttribute("checkCategories", List.of());
                m.addAttribute("recipients", List.of());
                m.addAttribute("fallbackRecipients", List.of());
                m.addAttribute("credentials", List.of());
                m.addAttribute("trafficLight", dev.hendrikhoemberg.webtesthelper.reporting.TrafficLight.NEU);
                return null;
            }).when(siteDetailModel).populateConfigContext(org.mockito.ArgumentMatchers.eq(1L), any(Model.class));

            mvc.perform(post("/websites/1/zeitplaene").with(csrf()))
                    .andExpect(status().isOk())
                    .andExpect(view().name("websites/konfiguration"))
                    .andExpect(model().attributeHasErrors("zeitplaene"))
                    .andExpect(model().errorCount(1));

            verify(scheduleService, never()).update(anyLong(), anyString(), anyString(), anyBoolean(), any());
        }

        @Test
        void directInvocationWithNullFormDoesNotNpe() {
            ScheduleController controller = new ScheduleController(
                    scheduleService, siteService, runService, checkRegistry, siteDetailModel);

            when(scheduleService.forSite(1L)).thenReturn(List.of());

            BindingResult bindingResult = new BeanPropertyBindingResult(new ScheduleFormModel(null), "zeitplaene");
            Model model = new ConcurrentModel();

            String view = controller.save(1L, null, bindingResult, model);

            assertThat(view).isEqualTo("websites/konfiguration");
            assertThat(bindingResult.hasGlobalErrors()).isTrue();
            assertThat(bindingResult.getGlobalError().getCode()).isEqualTo("ui.zeitplan.fehler.leer");
            assertThat(bindingResult.getGlobalError().getDefaultMessage()).isEqualTo("Es muss mindestens ein Zeitplan definiert sein.");
            verify(scheduleService, never()).update(anyLong(), anyString(), anyString(), anyBoolean(), any());
        }

        @Test
        void directInvocationWithNullRowsDoesNotNpe() {
            ScheduleController controller = new ScheduleController(
                    scheduleService, siteService, runService, checkRegistry, siteDetailModel);

            when(scheduleService.forSite(1L)).thenReturn(List.of());

            ScheduleFormModel form = new ScheduleFormModel(null);
            BindingResult bindingResult = new BeanPropertyBindingResult(form, "zeitplaene");
            Model model = new ConcurrentModel();

            String view = controller.save(1L, form, bindingResult, model);

            assertThat(view).isEqualTo("websites/konfiguration");
            assertThat(bindingResult.hasGlobalErrors()).isTrue();
            assertThat(bindingResult.getGlobalError().getCode()).isEqualTo("ui.zeitplan.fehler.leer");
            verify(scheduleService, never()).update(anyLong(), anyString(), anyString(), anyBoolean(), any());
        }

        @Test
        void directInvocationWithEmptyRowsDoesNotNpe() {
            ScheduleController controller = new ScheduleController(
                    scheduleService, siteService, runService, checkRegistry, siteDetailModel);

            when(scheduleService.forSite(1L)).thenReturn(List.of());

            ScheduleFormModel form = new ScheduleFormModel(List.of());
            BindingResult bindingResult = new BeanPropertyBindingResult(form, "zeitplaene");
            Model model = new ConcurrentModel();

            String view = controller.save(1L, form, bindingResult, model);

            assertThat(view).isEqualTo("websites/konfiguration");
            assertThat(bindingResult.hasGlobalErrors()).isTrue();
            assertThat(bindingResult.getGlobalError().getCode()).isEqualTo("ui.zeitplan.fehler.leer");
            verify(scheduleService, never()).update(anyLong(), anyString(), anyString(), anyBoolean(), any());
        }
    }
}
