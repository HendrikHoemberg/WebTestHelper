package dev.hendrikhoemberg.webtesthelper.challenger;

import dev.hendrikhoemberg.webtesthelper.catalog.AppSettings;
import dev.hendrikhoemberg.webtesthelper.catalog.CredentialService;
import dev.hendrikhoemberg.webtesthelper.catalog.RecipientService;
import dev.hendrikhoemberg.webtesthelper.catalog.SiteService;
import dev.hendrikhoemberg.webtesthelper.catalog.SiteSummary;
import dev.hendrikhoemberg.webtesthelper.checks.CheckRegistry;
import dev.hendrikhoemberg.webtesthelper.findings.FindingService;
import dev.hendrikhoemberg.webtesthelper.model.CrawlBudget;
import dev.hendrikhoemberg.webtesthelper.model.FormTestMode;
import dev.hendrikhoemberg.webtesthelper.model.SiteContext;
import dev.hendrikhoemberg.webtesthelper.model.UrlNormalizer;
import dev.hendrikhoemberg.webtesthelper.reporting.FindingViewFactory;
import dev.hendrikhoemberg.webtesthelper.runner.RunService;
import dev.hendrikhoemberg.webtesthelper.scheduling.ScheduleService;
import dev.hendrikhoemberg.webtesthelper.auth.AppUserService;
import dev.hendrikhoemberg.webtesthelper.web.SiteController;
import dev.hendrikhoemberg.webtesthelper.web.SiteDetailModel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

@org.junit.jupiter.api.parallel.ResourceLock("spring-context")
@WebMvcTest(SiteController.class)
@Import(SiteDetailModel.class)
class SiteControllerAdversarialTest {

    @Autowired
    MockMvc mvc;

    @MockitoBean
    SiteService siteService;

    @MockitoBean
    RunService runService;

    @MockitoBean
    CheckRegistry checkRegistry;

    @MockitoBean
    ScheduleService scheduleService;

    @MockitoBean
    RecipientService recipientService;

    @MockitoBean
    CredentialService credentialService;

    @MockitoBean
    AppSettings appSettings;

    @MockitoBean
    AppUserService appUserService;

    @MockitoBean
    FindingService findingService;

    @MockitoBean
    FindingViewFactory findingViewFactory;

    @Test
    @WithMockUser(roles = "ADMIN")
    void postWebsites_completelyEmptyPayload_rendersValidationErrorsWithout500() throws Exception {
        mvc.perform(post("/websites").with(csrf()))
                .andExpect(status().isOk())
                .andExpect(view().name("websites/formular"))
                .andExpect(model().attributeExists("form"))
                .andExpect(model().attributeHasFieldErrors("form", "name", "baseUrl", "maxPages", "maxDepth", "maxDurationMinutes"))
                .andExpect(content().string(containsString("Bitte einen Namen für die Website angeben.")))
                .andExpect(content().string(containsString("Bitte eine Basis-URL angeben.")))
                .andExpect(content().string(containsString("Bitte ein Seitenlimit angeben.")))
                .andExpect(content().string(containsString("Bitte eine maximale Tiefe angeben.")))
                .andExpect(content().string(containsString("Bitte ein Zeitlimit angeben.")))
                .andExpect(content().string(not(containsString("Whitelabel Error Page"))))
                .andExpect(content().string(not(containsString("SpelEvaluationException"))));

        verify(siteService, never()).create(any());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void postWebsites_blankWhitespaceStrings_rendersRequiredFieldErrors() throws Exception {
        mvc.perform(post("/websites")
                        .with(csrf())
                        .param("name", "   ")
                        .param("baseUrl", "   \t  ")
                        .param("maxPages", "")
                        .param("maxDepth", "")
                        .param("maxDurationMinutes", ""))
                .andExpect(status().isOk())
                .andExpect(view().name("websites/formular"))
                .andExpect(model().attributeHasFieldErrors("form", "name", "baseUrl", "maxPages", "maxDepth", "maxDurationMinutes"))
                .andExpect(content().string(containsString("Bitte einen Namen für die Website angeben.")))
                .andExpect(content().string(containsString("Bitte eine Basis-URL angeben.")))
                .andExpect(content().string(containsString("Bitte ein Seitenlimit angeben.")));

        verify(siteService, never()).create(any());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void postWebsites_corruptedNonNumericInput_rendersGermanTypeMismatchFeedback() throws Exception {
        mvc.perform(post("/websites")
                        .with(csrf())
                        .param("name", "Test Site")
                        .param("baseUrl", "https://example.com")
                        .param("maxPages", "not-a-number")
                        .param("maxDepth", "invalid-depth")
                        .param("maxDurationMinutes", "abc"))
                .andExpect(status().isOk())
                .andExpect(view().name("websites/formular"))
                .andExpect(model().attributeHasFieldErrors("form", "maxPages", "maxDepth", "maxDurationMinutes"))
                .andExpect(content().string(containsString("Bitte ein gültiges Seitenlimit als Zahl angeben.")))
                .andExpect(content().string(containsString("Bitte eine gültige maximale Tiefe als Zahl angeben.")))
                .andExpect(content().string(containsString("Bitte ein gültiges Zeitlimit in Minuten angeben.")));

        verify(siteService, never()).create(any());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void postWebsites_negativeAndZeroNumericValues_rendersGermanMinConstraintErrors() throws Exception {
        mvc.perform(post("/websites")
                        .with(csrf())
                        .param("name", "Test Site")
                        .param("baseUrl", "https://example.com")
                        .param("maxPages", "0")
                        .param("maxDepth", "-1")
                        .param("maxDurationMinutes", "0"))
                .andExpect(status().isOk())
                .andExpect(view().name("websites/formular"))
                .andExpect(model().attributeHasFieldErrors("form", "maxPages", "maxDepth", "maxDurationMinutes"))
                .andExpect(content().string(containsString("Das Seitenlimit muss mindestens 1 betragen.")))
                .andExpect(content().string(containsString("Die maximale Tiefe darf nicht negativ sein.")))
                .andExpect(content().string(containsString("Das Zeitlimit muss mindestens 1 Minute betragen.")));

        verify(siteService, never()).create(any());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "ftp://example.com",
            "javascript:alert(1)",
            "file:///etc/passwd",
            "example.com",
            "//example.com",
            "htp://example.com"
    })
    @WithMockUser(roles = "ADMIN")
    void postWebsites_invalidUrlSchemes_rendersGermanUrlFormatError(String badUrl) throws Exception {
        mvc.perform(post("/websites")
                        .with(csrf())
                        .param("name", "Test Site")
                        .param("baseUrl", badUrl)
                        .param("maxPages", "100")
                        .param("maxDepth", "3")
                        .param("maxDurationMinutes", "10"))
                .andExpect(status().isOk())
                .andExpect(view().name("websites/formular"))
                .andExpect(model().attributeHasFieldErrors("form", "baseUrl"))
                .andExpect(content().string(containsString("Die Basis-URL muss mit http:// oder https:// beginnen.")));

        verify(siteService, never()).create(any());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void postWebsites_integerOverflowAttempt_rendersTypeMismatchWithoutCrash() throws Exception {
        mvc.perform(post("/websites")
                        .with(csrf())
                        .param("name", "Test Site")
                        .param("baseUrl", "https://example.com")
                        .param("maxPages", "99999999999999999999999999999999999999999")
                        .param("maxDepth", "3")
                        .param("maxDurationMinutes", "10"))
                .andExpect(status().isOk())
                .andExpect(view().name("websites/formular"))
                .andExpect(model().attributeHasFieldErrors("form", "maxPages"))
                .andExpect(content().string(containsString("Bitte ein gültiges Seitenlimit als Zahl angeben.")));

        verify(siteService, never()).create(any());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void postWebsites_unknownFormTestMode_fallsBackGracefullyWithoutCrash() throws Exception {
        when(siteService.create(any())).thenReturn(101L);

        mvc.perform(post("/websites")
                        .with(csrf())
                        .param("name", "Valid Site")
                        .param("baseUrl", "https://example.com")
                        .param("maxPages", "100")
                        .param("maxDepth", "3")
                        .param("maxDurationMinutes", "10")
                        .param("formTestMode", "ATTACK_MALICIOUS_ENUM_VALUE"))
                .andExpect(status().is3xxRedirection());

        verify(siteService).create(any());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void postWebsitesEdit_completelyEmptyPayload_rendersValidationErrorsWithSiteIdRetained() throws Exception {
        mvc.perform(post("/websites/42").with(csrf()))
                .andExpect(status().isOk())
                .andExpect(view().name("websites/formular"))
                .andExpect(model().attribute("siteId", 42L))
                .andExpect(model().attributeExists("form"))
                .andExpect(model().attributeHasFieldErrors("form", "name", "baseUrl", "maxPages", "maxDepth", "maxDurationMinutes"))
                .andExpect(content().string(containsString("Bitte einen Namen für die Website angeben.")))
                .andExpect(content().string(containsString("Bitte eine Basis-URL angeben.")))
                .andExpect(content().string(containsString("/websites/42")));

        verify(siteService, never()).update(anyLong(), any());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void postWebsitesEdit_corruptedPayload_rendersValidationErrorsWithout500() throws Exception {
        var mvcResult = mvc.perform(post("/websites/42")
                        .with(csrf())
                        .param("name", "")
                        .param("baseUrl", "invalid-url")
                        .param("maxPages", "-5")
                        .param("maxDepth", "abc")
                        .param("maxDurationMinutes", "0"))
                .andExpect(status().isOk())
                .andExpect(view().name("websites/formular"))
                .andExpect(model().attribute("siteId", 42L))
                .andReturn();

        org.springframework.validation.BindingResult br = (org.springframework.validation.BindingResult)
                mvcResult.getModelAndView().getModel().get(org.springframework.validation.BindingResult.MODEL_KEY_PREFIX + "form");
        for (var err : br.getFieldErrors()) {
            System.out.println("  field: " + err.getField() + " code: " + err.getCode() + " defaultMsg: " + err.getDefaultMessage());
        }
        assertThat(br.hasErrors()).isTrue();
        assertThat(br.hasFieldErrors("maxDepth")).isTrue();

        verify(siteService, never()).update(anyLong(), any());
    }
}
