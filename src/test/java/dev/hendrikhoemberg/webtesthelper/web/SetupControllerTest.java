package dev.hendrikhoemberg.webtesthelper.web;

import dev.hendrikhoemberg.webtesthelper.catalog.SiteService;
import dev.hendrikhoemberg.webtesthelper.catalog.SiteSummary;
import dev.hendrikhoemberg.webtesthelper.crawler.ProbeEvidence;
import dev.hendrikhoemberg.webtesthelper.model.CheckType;
import dev.hendrikhoemberg.webtesthelper.runner.CheckProposal;
import dev.hendrikhoemberg.webtesthelper.runner.ProbeState;
import dev.hendrikhoemberg.webtesthelper.runner.ProbeStatus;
import dev.hendrikhoemberg.webtesthelper.runner.SetupProbeService;
import dev.hendrikhoemberg.webtesthelper.runner.SetupProposal;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
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

@WebMvcTest(SetupController.class)
class SetupControllerTest {

    private static final long SITE_ID = 42L;

    private static final List<CheckType> BASELINE = List.of(
            CheckType.PAGE_STATUS, CheckType.PAGE_UNREACHABLE, CheckType.DEAD_LINK,
            CheckType.REDIRECT_CHAIN, CheckType.IMAGE_BROKEN);

    @Autowired
    MockMvc mvc;

    @MockitoBean
    SetupProbeService setupProbeService;

    @MockitoBean
    SiteService siteService;

    @MockitoBean
    AppUserService appUserService;

    private void siteExists() {
        when(siteService.summary(SITE_ID))
                .thenReturn(new SiteSummary(SITE_ID, "Acme Shop", "https://acme.example.com/", true, 10));
    }

    @Test
    @WithMockUser(roles = "USER")
    void getEinrichtungStartsProbeWhenNoneIsHeld() throws Exception {
        siteExists();
        when(setupProbeService.stateOf(SITE_ID))
                .thenReturn(Optional.empty(), Optional.of(laeuft()));

        mvc.perform(get("/websites/" + SITE_ID + "/einrichtung"))
                .andExpect(status().isOk())
                .andExpect(view().name("einrichtung/index"));

        verify(setupProbeService).start(SITE_ID);
    }

    @Test
    @WithMockUser(roles = "USER")
    void getEinrichtungDoesNotRestartAnExistingProbe() throws Exception {
        siteExists();
        when(setupProbeService.stateOf(SITE_ID)).thenReturn(Optional.of(laeuft()));

        mvc.perform(get("/websites/" + SITE_ID + "/einrichtung"))
                .andExpect(status().isOk())
                .andExpect(view().name("einrichtung/index"));

        verify(setupProbeService, never()).start(SITE_ID);
    }

    @Test
    @WithMockUser(roles = "USER")
    void getEinrichtungUnknownSiteReturns404() throws Exception {
        when(siteService.summary(SITE_ID)).thenThrow(new IllegalArgumentException("Site existiert nicht: " + SITE_ID));

        mvc.perform(get("/websites/" + SITE_ID + "/einrichtung"))
                .andExpect(status().isNotFound());
    }

    @Test
    @WithMockUser(roles = "USER")
    void standWhileRunningCarriesTheTriggerAndTheWaitingSentence() throws Exception {
        when(siteService.summary(SITE_ID)).thenReturn(summary());
        when(setupProbeService.stateOf(SITE_ID)).thenReturn(Optional.of(laeuft()));

        MvcResult result = mvc.perform(get("/websites/" + SITE_ID + "/einrichtung/stand"))
                .andExpect(status().isOk())
                .andExpect(view().name("fragments/einrichtungsstand :: stand"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("hx-trigger=\"every 2s\"")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Wir untersuchen die Website")))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("<!DOCTYPE"))))
                .andReturn();

        String body = result.getResponse().getContentAsString();
        assertThat(body).doesNotContain("name=\"aktiv\"");
    }

    @Test
    @WithMockUser(roles = "USER")
    void standWhenFinishedRendersOneCheckboxPerCheckTypeWithReasonFromBundleAndNoTrigger() throws Exception {
        when(setupProbeService.stateOf(SITE_ID)).thenReturn(Optional.of(fertig()));

        MvcResult result = mvc.perform(get("/websites/" + SITE_ID + "/einrichtung/stand"))
                .andExpect(status().isOk())
                .andExpect(view().name("fragments/einrichtungsstand :: stand"))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("hx-trigger"))))
                .andReturn();

        String body = result.getResponse().getContentAsString();
        assertThat(countOccurrences(body, "name=\"aktiv\"")).isEqualTo(CheckType.values().length);
        // German titles, never the internal constant (§13.1)
        assertThat(body).contains("Tote Links");
        assertThat(body).contains("Video und Ton");
        assertThat(body).contains("Inhaltsverzeichnis der Website");
        // Reason sentences come from the message bundle
        assertThat(body).contains("Grundprüfung, immer sinnvoll");
        assertThat(body).contains("Video oder Audio auf https://acme.example.com/medien gefunden");
        assertThat(body).contains("2 Sprachfassungen gefunden");
        assertThat(body).contains("sitemap.xml gefunden");
        // The found form is information only: a sentence, with no checkbox beside it
        assertThat(body).contains("Kontaktformular auf https://acme.example.com/kontakt gefunden");
        assertThat(body).contains("Standardmäßig abgeschaltet, weil die Erstprüfung dazu nichts belegt");
    }

    @Test
    @WithMockUser(roles = "USER")
    void standWhenFailedRendersErrorRetryAndBaselineChecksChecked() throws Exception {
        when(setupProbeService.stateOf(SITE_ID)).thenReturn(Optional.of(failed("Website nicht erreichbar")));

        MvcResult result = mvc.perform(get("/websites/" + SITE_ID + "/einrichtung/stand"))
                .andExpect(status().isOk())
                .andExpect(view().name("fragments/einrichtungsstand :: stand"))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("hx-trigger"))))
                .andReturn();

        String body = result.getResponse().getContentAsString();
        assertThat(body).contains("Website nicht erreichbar");
        assertThat(body).contains("Erneut versuchen");
        assertThat(body).contains("Übernehmen");
        // The five always-on baseline checks are ticked, so Übernehmen never turns them off —
        // a probe that saw nothing must not silently disable a seeded-on check.
        assertThat(countOccurrences(body, "name=\"aktiv\"")).isEqualTo(5);
        for (CheckType baseline : BASELINE) {
            assertThat(body).contains("value=\"" + baseline + "\" checked=\"checked\"");
        }
        assertThat(body).contains("Grundprüfung, immer sinnvoll");
        assertThat(body).doesNotContain("value=\"CONSOLE_ERRORS\"");
    }

    @Test
    @WithMockUser(roles = "USER")
    void applyWithTwoTickedIsAuthoritativeNotAdditive() throws Exception {
        siteExists();

        mvc.perform(post("/websites/" + SITE_ID + "/einrichtung")
                        .with(csrf())
                        .param("aktiv", CheckType.PAGE_STATUS.name())
                        .param("aktiv", CheckType.CONSOLE_ERRORS.name()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/websites/" + SITE_ID));

        for (CheckType type : CheckType.values()) {
            boolean ticked = type == CheckType.PAGE_STATUS || type == CheckType.CONSOLE_ERRORS;
            verify(siteService).setCheckEnabled(eq(SITE_ID), eq(type), eq(ticked));
        }
        // Could stay on from SiteService.create's seeding (it is seeded on) but is not ticked: off.
        verify(siteService).setCheckEnabled(eq(SITE_ID), eq(CheckType.TLS_CERT), eq(false));
        // The one check SiteService.create seeds off is ticked here: on, the form decides.
        verify(siteService).setCheckEnabled(eq(SITE_ID), eq(CheckType.CONSOLE_ERRORS), eq(true));
        verify(setupProbeService).clear(SITE_ID);
    }

    @Test
    @WithMockUser(roles = "USER")
    void applyBaselineFromFailedProbeEnablesExactlyTheFiveAlwaysOnChecks() throws Exception {
        siteExists();

        mvc.perform(post("/websites/" + SITE_ID + "/einrichtung")
                        .with(csrf())
                        .param("aktiv", CheckType.PAGE_STATUS.name())
                        .param("aktiv", CheckType.PAGE_UNREACHABLE.name())
                        .param("aktiv", CheckType.DEAD_LINK.name())
                        .param("aktiv", CheckType.REDIRECT_CHAIN.name())
                        .param("aktiv", CheckType.IMAGE_BROKEN.name()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/websites/" + SITE_ID));

        for (CheckType type : CheckType.values()) {
            boolean enabled = BASELINE.contains(type);
            verify(siteService).setCheckEnabled(eq(SITE_ID), eq(type), eq(enabled));
        }
        // A seeded-on check outside the baseline is disabled by the failed-probe form.
        verify(siteService).setCheckEnabled(eq(SITE_ID), eq(CheckType.TLS_CERT), eq(false));
        verify(setupProbeService).clear(SITE_ID);
    }

    @Test
    @WithMockUser(roles = "USER")
    void neuClearsAndRestartsTheProbeAndRedirectsBack() throws Exception {
        siteExists();

        mvc.perform(post("/websites/" + SITE_ID + "/einrichtung/neu").with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/websites/" + SITE_ID + "/einrichtung"));

        verify(setupProbeService).clear(SITE_ID);
        verify(setupProbeService).start(SITE_ID);
    }

    private static SiteSummary summary() {
        return new SiteSummary(SITE_ID, "Acme Shop", "https://acme.example.com/", true, 10);
    }

    private static ProbeState laeuft() {
        return new ProbeState(ProbeStatus.LAEUFT, Instant.now(), null, null);
    }

    private static ProbeState failed(String error) {
        return new ProbeState(ProbeStatus.FEHLGESCHLAGEN, Instant.now(), null, error);
    }

    private static ProbeState fertig() {
        return new ProbeState(ProbeStatus.FERTIG, Instant.now(), proposal(), null);
    }

    private static SetupProposal proposal() {
        ProbeEvidence evidence = new ProbeEvidence(true, null,
                List.of("https://acme.example.com/", "https://acme.example.com/kontakt"),
                List.of("https://acme.example.com/kontakt"),
                List.of("https://acme.example.com/medien"),
                List.of("https://acme.example.com/karte"),
                Set.of("de", "en"),
                List.of("https://acme.example.com/download.pdf"),
                true, true);
        List<CheckProposal> checks = new ArrayList<>(CheckType.values().length);
        for (CheckType type : CheckType.values()) {
            switch (type) {
                case MEDIA_PLAYABLE ->
                        checks.add(new CheckProposal(type, true, "ui.einrichtung.grund.media",
                                List.of("https://acme.example.com/medien")));
                case IFRAME_EMBED ->
                        checks.add(new CheckProposal(type, true, "ui.einrichtung.grund.karte",
                                List.of("https://acme.example.com/karte")));
                case HREFLANG ->
                        checks.add(new CheckProposal(type, true, "ui.einrichtung.grund.sprachen", List.of("2")));
                case FILE_DOWNLOAD ->
                        checks.add(new CheckProposal(type, true, "ui.einrichtung.grund.dokument",
                                List.of("https://acme.example.com/download.pdf")));
                case SITEMAP_CONSISTENCY ->
                        checks.add(new CheckProposal(type, true, "ui.einrichtung.grund.sitemap", List.of()));
                case TLS_CERT, MIXED_CONTENT ->
                        checks.add(new CheckProposal(type, true, "ui.einrichtung.grund.https", List.of()));
                case CONSOLE_ERRORS ->
                        checks.add(new CheckProposal(type, false, "ui.einrichtung.grund.standard", List.of()));
                default ->
                        checks.add(new CheckProposal(type, true, "ui.einrichtung.grund.basis", List.of()));
            }
        }
        return new SetupProposal(evidence, checks);
    }

    private static int countOccurrences(String haystack, String needle) {
        int count = 0;
        int index = 0;
        while ((index = haystack.indexOf(needle, index)) != -1) {
            count++;
            index += needle.length();
        }
        return count;
    }
}
