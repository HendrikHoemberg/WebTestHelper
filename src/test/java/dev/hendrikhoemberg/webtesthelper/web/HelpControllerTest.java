package dev.hendrikhoemberg.webtesthelper.web;

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
    void getHilfeIndexReturnsTopics() throws Exception {
        HelpTopic topic = new HelpTopic("test-topic", "Test Titel", "<p>HTML</p>", "<p>Teaser</p>");
        when(helpService.all()).thenReturn(List.of(topic));

        mvc.perform(get("/hilfe"))
                .andExpect(status().isOk())
                .andExpect(view().name("hilfe/index"))
                .andExpect(model().attributeExists("topics"));
    }

    @Test
    @WithMockUser
    void getHilfeThemaReturnsTopic() throws Exception {
        HelpTopic topic = new HelpTopic("bericht-lesen", "Berichte lesen", "<p>Inhalt</p>", "<p>Teaser</p>");
        when(helpService.byId("bericht-lesen")).thenReturn(Optional.of(topic));

        mvc.perform(get("/hilfe/bericht-lesen"))
                .andExpect(status().isOk())
                .andExpect(view().name("hilfe/thema"))
                .andExpect(model().attribute("topic", topic));
    }

    @Test
    @WithMockUser
    void getHilfeThemaNotFoundReturns404() throws Exception {
        when(helpService.byId("unbekannt")).thenReturn(Optional.empty());

        mvc.perform(get("/hilfe/unbekannt"))
                .andExpect(status().isNotFound());
    }

    @Test
    @WithMockUser
    void getHilfeHinweisFragmentReturnsFragment() throws Exception {
        HelpTopic topic = new HelpTopic("bericht-lesen", "Berichte lesen", "<p>Inhalt</p>", "<p>Teaser</p>");
        when(helpService.byId("bericht-lesen")).thenReturn(Optional.of(topic));

        mvc.perform(get("/hilfe/hinweis/bericht-lesen"))
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
    void getHilfeHinweisNotFoundReturns404() throws Exception {
        when(helpService.byId("unbekannt")).thenReturn(Optional.empty());

        mvc.perform(get("/hilfe/hinweis/unbekannt"))
                .andExpect(status().isNotFound());
    }
}
