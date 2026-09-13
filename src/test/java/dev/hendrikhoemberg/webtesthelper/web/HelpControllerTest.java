package dev.hendrikhoemberg.webtesthelper.web;

import dev.hendrikhoemberg.webtesthelper.auth.AppUserService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Optional;

import static org.hamcrest.Matchers.containsStringIgnoringCase;
import static org.hamcrest.Matchers.not;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

@org.junit.jupiter.api.parallel.ResourceLock("spring-context")
@WebMvcTest(HelpController.class)
class HelpControllerTest {

    @Autowired
    MockMvc mvc;

    @MockitoBean
    HelpService helpService;

    @MockitoBean
    AppUserService appUserService;

    @Test
    @WithMockUser
    void getHilfeRedirectsToHandbuch() throws Exception {
        mvc.perform(get("/hilfe"))
                .andExpect(status().is3xxRedirection())
                .andExpect(view().name("redirect:/handbuch"));
    }

    @Test
    @WithMockUser
    void getHandbuchIndexReturnsTopics() throws Exception {
        HelpTopic topic = new HelpTopic("test-topic", "Test Titel", "<p>HTML</p>", "<p>Teaser</p>");
        when(helpService.search(null)).thenReturn(List.of(topic));

        mvc.perform(get("/handbuch"))
                .andExpect(status().isOk())
                .andExpect(view().name("hilfe/index"))
                .andExpect(model().attributeExists("topics"));
    }

    @Test
    @WithMockUser
    void getHandbuchIndexWithQueryReturnsSearchedTopics() throws Exception {
        HelpTopic topic = new HelpTopic("test-topic", "Test Titel", "<p>HTML</p>", "<p>Teaser</p>");
        when(helpService.search("webhook")).thenReturn(List.of(topic));

        mvc.perform(get("/handbuch").param("q", "webhook"))
                .andExpect(status().isOk())
                .andExpect(view().name("hilfe/index"))
                .andExpect(model().attribute("topics", List.of(topic)))
                .andExpect(model().attribute("q", "webhook"))
                .andExpect(content().string(containsStringIgnoringCase("name=\"q\"")))
                .andExpect(content().string(containsStringIgnoringCase("value=\"webhook\"")));
    }

    @Test
    @WithMockUser
    void getHandbuchIndexWithEmptySearchResultsRendersEmptyMessage() throws Exception {
        when(helpService.search("unbekannt")).thenReturn(List.of());

        mvc.perform(get("/handbuch").param("q", "unbekannt"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsStringIgnoringCase("Keine Hilfethemen")));
    }

    @Test
    @WithMockUser
    void getHandbuchIndexWithHtmxRequestReturnsThemenListeFragment() throws Exception {
        HelpTopic topic = new HelpTopic("test-topic", "Test Titel", "<p>HTML</p>", "<p>Teaser</p>");
        when(helpService.search(null)).thenReturn(List.of(topic));

        mvc.perform(get("/handbuch").header("HX-Request", "true"))
                .andExpect(status().isOk())
                .andExpect(view().name("hilfe/index :: themenListe"))
                .andExpect(model().attributeExists("topics"));
    }

    @Test
    @WithMockUser
    void handbuchIndexRendersCardContainerAndStickyActions() throws Exception {
        HelpTopic topic = new HelpTopic("test-topic", "Test Titel", "<p>HTML</p>", "<p>Teaser</p>");
        when(helpService.search(null)).thenReturn(List.of(topic));

        mvc.perform(get("/handbuch"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsStringIgnoringCase("hilfe-themen-liste")))
                .andExpect(content().string(containsStringIgnoringCase("hilfe-kachel-aktion")));
    }

    @Test
    @WithMockUser
    void getHandbuchThemaReturnsTopic() throws Exception {
        HelpTopic topic = new HelpTopic("bericht-lesen", "Berichte lesen", "<p>Inhalt</p>", "<p>Teaser</p>");
        when(helpService.byId("bericht-lesen")).thenReturn(Optional.of(topic));

        mvc.perform(get("/handbuch/bericht-lesen"))
                .andExpect(status().isOk())
                .andExpect(view().name("hilfe/thema"))
                .andExpect(model().attribute("topic", topic));
    }

    @Test
    @WithMockUser
    void getHandbuchThemaNotFoundReturns404() throws Exception {
        when(helpService.byId("unbekannt")).thenReturn(Optional.empty());

        mvc.perform(get("/handbuch/unbekannt"))
                .andExpect(status().isNotFound());
    }

    @Test
    @WithMockUser
    void getHandbuchHinweisFragmentReturnsFragment() throws Exception {
        HelpTopic topic = new HelpTopic("bericht-lesen", "Berichte lesen", "<p>Inhalt</p>", "<p>Teaser</p>");
        when(helpService.byId("bericht-lesen")).thenReturn(Optional.of(topic));

        mvc.perform(get("/handbuch/hinweis/bericht-lesen"))
                .andExpect(status().isOk())
                .andExpect(view().name("fragments/hinweis :: hinweis"))
                .andExpect(model().attribute("thema", topic))
                // The ? affordance swaps this into a div, so the body is the fragment alone —
                // a document wrapper is markup HTMX has to strip before it can swap.
                .andExpect(content().string(not(containsStringIgnoringCase("<!DOCTYPE"))))
                .andExpect(content().string(not(containsStringIgnoringCase("<body"))));
    }

    @Test
    @WithMockUser
    void getHandbuchHinweisNotFoundReturns404() throws Exception {
        when(helpService.byId("unbekannt")).thenReturn(Optional.empty());

        mvc.perform(get("/handbuch/hinweis/unbekannt"))
                .andExpect(status().isNotFound());
    }
}
