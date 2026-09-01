package dev.hendrikhoemberg.webtesthelper.web;

import dev.hendrikhoemberg.webtesthelper.catalog.SiteService;
import dev.hendrikhoemberg.webtesthelper.findings.FindingService;
import dev.hendrikhoemberg.webtesthelper.reporting.FindingViewFactory;
import dev.hendrikhoemberg.webtesthelper.runner.RunService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = {FindingController.class, RunController.class})
@WithMockUser(roles = "USER")
class BarePath404Test {

    @Autowired
    MockMvc mvc;

    @MockitoBean
    FindingService findingService;

    @MockitoBean
    FindingViewFactory findingViewFactory;

    @MockitoBean
    RunService runService;

    @MockitoBean
    SiteService siteService;

    @Test
    void bareBefundeRedirectsToWebsitesOverview() throws Exception {
        mvc.perform(get("/befunde"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/websites"));
    }

    @Test
    void bareLaeufeRedirectsToWebsitesOverview() throws Exception {
        mvc.perform(get("/laeufe"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/websites"));
    }
}
