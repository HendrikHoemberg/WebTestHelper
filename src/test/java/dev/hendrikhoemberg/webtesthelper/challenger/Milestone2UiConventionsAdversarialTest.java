package dev.hendrikhoemberg.webtesthelper.challenger;

import dev.hendrikhoemberg.webtesthelper.catalog.SiteService;
import dev.hendrikhoemberg.webtesthelper.catalog.SiteSummary;
import dev.hendrikhoemberg.webtesthelper.catalog.persistence.SiteCheckSettingEntity;
import dev.hendrikhoemberg.webtesthelper.catalog.persistence.SiteCheckSettingRepository;
import dev.hendrikhoemberg.webtesthelper.catalog.persistence.SiteEntity;
import dev.hendrikhoemberg.webtesthelper.catalog.persistence.SiteRepository;
import dev.hendrikhoemberg.webtesthelper.findings.FindingService;
import dev.hendrikhoemberg.webtesthelper.model.CheckType;
import dev.hendrikhoemberg.webtesthelper.model.LocatorStrategy;
import dev.hendrikhoemberg.webtesthelper.model.RunScope;
import dev.hendrikhoemberg.webtesthelper.model.RunStatus;
import dev.hendrikhoemberg.webtesthelper.model.RunTrigger;
import dev.hendrikhoemberg.webtesthelper.runner.RunService;
import dev.hendrikhoemberg.webtesthelper.runner.RunSummary;
import dev.hendrikhoemberg.webtesthelper.auth.AppUserService;
import dev.hendrikhoemberg.webtesthelper.web.RunController;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.MessageSource;
import org.springframework.context.support.ResourceBundleMessageSource;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.net.URI;
import java.text.MessageFormat;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.ResourceBundle;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Adversarial boundary verification suite for Milestone 2 UI & Conventions:
 * - UX-04: All LocatorStrategy enum values have valid localized labels
 * - UX-05: fragments/fortschritt.html polling and cancel buttons omitted on terminal runs
 * - CONV-01: SiteService counts only active checks and uses localized German copy
 */
@org.junit.jupiter.api.parallel.ResourceLock("spring-context")
@WebMvcTest(RunController.class)
class Milestone2UiConventionsAdversarialTest {

    @Autowired
    MockMvc mvc;

    @MockitoBean
    RunService runService;

    @MockitoBean
    SiteService siteService;

    @MockitoBean
    FindingService findingService;

    @MockitoBean
    dev.hendrikhoemberg.webtesthelper.reporting.FindingViewFactory findingViewFactory;

    @MockitoBean
    dev.hendrikhoemberg.webtesthelper.reporting.PdfReportService pdfReportService;

    @MockitoBean
    AppUserService appUserService;

    // =========================================================================
    // 1. UX-04: LocatorStrategy i18n Completeness
    // =========================================================================
    @Nested
    @DisplayName("UX-04: LocatorStrategy i18n Completeness")
    class Ux04LocatorStrategyI18nTests {

        @Test
        void everyLocatorStrategyEnumConstantHasValidTranslation() {
            ResourceBundleMessageSource messageSource = new ResourceBundleMessageSource();
            messageSource.setBasename("messages");
            messageSource.setDefaultEncoding("UTF-8");

            for (LocatorStrategy strategy : LocatorStrategy.values()) {
                String key = "ui.journey.strategy." + strategy.name();
                String translation = messageSource.getMessage(key, null, Locale.GERMAN);

                assertThat(translation)
                        .as("Missing translation for LocatorStrategy: " + strategy.name() + " (key: " + key + ")")
                        .isNotBlank()
                        .doesNotContain("???");
            }
        }

        @Test
        void idStrategyTranslatesToHtmlId() {
            ResourceBundle bundle = ResourceBundle.getBundle("messages", Locale.GERMAN);
            String label = bundle.getString("ui.journey.strategy.ID");

            assertThat(label).isEqualTo("HTML-ID (Attribut id)");
        }
    }

    // =========================================================================
    // 2. UX-05: Progress Polling and Cancellation on Terminal Runs
    // =========================================================================
    @Nested
    @DisplayName("UX-05: Progress Fragment Cleanliness")
    class Ux05ProgressFragmentTests {

        private RunSummary createRunSummary(long id, RunStatus status) {
            return new RunSummary(
                    id,
                    42L,
                    status,
                    RunTrigger.MANUAL,
                    RunScope.FULL,
                    Instant.parse("2026-08-25T10:00:00Z"),
                    Instant.parse("2026-08-25T10:00:05Z"),
                    Instant.parse("2026-08-25T10:02:30Z"),
                    85,
                    2,
                    4,
                    2,
                    1,
                    false,
                    null,
                    false,
                    null,
                    Set.of(CheckType.PAGE_STATUS, CheckType.DEAD_LINK)
            );
        }

        @ParameterizedTest(name = "Terminal run status {0} omits hx-trigger, hx-get, and cancel button")
        @EnumSource(value = RunStatus.class, names = {"COMPLETED", "FAILED", "CANCELLED"})
        @WithMockUser(roles = "USER")
        void terminalRunOmitsPollingAndCancelButton(RunStatus terminalStatus) throws Exception {
            long runId = 200L + terminalStatus.ordinal();
            RunSummary summary = createRunSummary(runId, terminalStatus);
            when(runService.summary(runId)).thenReturn(summary);

            mvc.perform(get("/laeufe/" + runId + "/fortschritt"))
                    .andExpect(status().isOk())
                    .andExpect(content().string(not(containsString("hx-trigger"))))
                    .andExpect(content().string(not(containsString("hx-get"))))
                    .andExpect(content().string(not(containsString("Lauf abbrechen"))))
                    .andExpect(content().string(not(containsString("$dispatch('abbrechen-offen')"))));
        }

        @Test
        @WithMockUser(roles = "USER")
        void runningStatusIncludes3sPollingAndCancelButton() throws Exception {
            long runId = 250L;
            RunSummary summary = createRunSummary(runId, RunStatus.RUNNING);
            when(runService.summary(runId)).thenReturn(summary);

            mvc.perform(get("/laeufe/" + runId + "/fortschritt"))
                    .andExpect(status().isOk())
                    .andExpect(content().string(containsString("hx-trigger=\"every 3s\"")))
                    .andExpect(content().string(containsString("hx-get=\"/laeufe/250/fortschritt\"")))
                    .andExpect(content().string(containsString("Lauf abbrechen")))
                    .andExpect(content().string(containsString("$dispatch('abbrechen-offen')")));
        }

        @Test
        @WithMockUser(roles = "USER")
        void queuedStatusIncludesCancelButton() throws Exception {
            long runId = 251L;
            RunSummary summary = createRunSummary(runId, RunStatus.QUEUED);
            when(runService.summary(runId)).thenReturn(summary);

            mvc.perform(get("/laeufe/" + runId + "/fortschritt"))
                    .andExpect(status().isOk())
                    .andExpect(content().string(containsString("Lauf abbrechen")))
                    .andExpect(content().string(containsString("$dispatch('abbrechen-offen')")));
        }
    }

    // =========================================================================
    // 3. CONV-01: Active Check Counter and Localization
    // =========================================================================
    @Nested
    @DisplayName("CONV-01: Enabled Check Counting and Copy")
    class Conv01ActiveCheckCounterTests {

        @Test
        void siteServiceSummariesCountsOnlyEnabledChecks() {
            SiteRepository siteRepo = mock(SiteRepository.class);
            SiteCheckSettingRepository checkRepo = mock(SiteCheckSettingRepository.class);
            SiteService service = new SiteService(siteRepo, checkRepo);

            SiteEntity site = new SiteEntity();
            site.setId(10L);
            site.setName("Test Site");
            site.setBaseUrl("https://example.com/");
            site.setEnabled(true);

            when(siteRepo.findAll()).thenReturn(List.of(site));

            List<SiteCheckSettingEntity> settings = new ArrayList<>();
            // 7 enabled checks
            for (int i = 0; i < 7; i++) {
                SiteCheckSettingEntity e = new SiteCheckSettingEntity();
                e.setSiteId(10L);
                e.setCheckType(CheckType.values()[i]);
                e.setEnabled(true);
                settings.add(e);
            }
            // 5 disabled checks
            for (int i = 7; i < 12; i++) {
                SiteCheckSettingEntity e = new SiteCheckSettingEntity();
                e.setSiteId(10L);
                e.setCheckType(CheckType.values()[i]);
                e.setEnabled(false);
                settings.add(e);
            }

            when(checkRepo.findBySiteIdIn(List.of(10L))).thenReturn(settings);

            List<SiteSummary> summaries = service.summaries();
            assertThat(summaries).hasSize(1);
            // Adversarially assert only the 7 enabled checks are counted, NOT all 12 configured!
            assertThat(summaries.get(0).settingCount()).isEqualTo(7);
        }

        @Test
        void siteServiceSummaryByIdCountsOnlyEnabledChecks() {
            SiteRepository siteRepo = mock(SiteRepository.class);
            SiteCheckSettingRepository checkRepo = mock(SiteCheckSettingRepository.class);
            SiteService service = new SiteService(siteRepo, checkRepo);

            SiteEntity site = new SiteEntity();
            site.setId(10L);
            site.setName("Test Site");
            site.setBaseUrl("https://example.com/");
            site.setEnabled(true);

            when(siteRepo.findById(10L)).thenReturn(Optional.of(site));

            List<SiteCheckSettingEntity> settings = new ArrayList<>();
            // 4 enabled checks
            for (int i = 0; i < 4; i++) {
                SiteCheckSettingEntity e = new SiteCheckSettingEntity();
                e.setSiteId(10L);
                e.setCheckType(CheckType.values()[i]);
                e.setEnabled(true);
                settings.add(e);
            }
            // 6 disabled checks
            for (int i = 4; i < 10; i++) {
                SiteCheckSettingEntity e = new SiteCheckSettingEntity();
                e.setSiteId(10L);
                e.setCheckType(CheckType.values()[i]);
                e.setEnabled(false);
                settings.add(e);
            }

            when(checkRepo.findBySiteId(10L)).thenReturn(settings);

            SiteSummary summary = service.summary(10L);
            assertThat(summary.settingCount()).isEqualTo(4);
        }

        @Test
        void activeCheckMessageKeyFormatsProperly() {
            ResourceBundle bundle = ResourceBundle.getBundle("messages", Locale.GERMAN);
            String pattern = bundle.getString("ui.websites.tabelle.pruefungen.aktiv");
            assertThat(pattern).isEqualTo("{0} aktiv");

            String formatted = MessageFormat.format(pattern, 10);
            assertThat(formatted).isEqualTo("10 aktiv");
        }
    }
}
