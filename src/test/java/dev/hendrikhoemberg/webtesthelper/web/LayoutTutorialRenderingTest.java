package dev.hendrikhoemberg.webtesthelper.web;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@org.junit.jupiter.api.parallel.ResourceLock("spring-context")
@WebMvcTest(HelpController.class)
class LayoutTutorialRenderingTest {

    @Autowired
    MockMvc mvc;

    @MockitoBean
    HelpService helpService;

    @MockitoBean
    AppUserService appUserService;

    @Test
    @WithMockUser(username = "otto")
    void authenticatedLayoutIncludesDriverAssetsAndConfig() throws Exception {
        when(helpService.all()).thenReturn(List.of());
        when(appUserService.isTutorialAbgeschlossen("otto")).thenReturn(false);

        mvc.perform(get("/hilfe"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("vendor/driver.css")))
                .andExpect(content().string(containsString("vendor/driver.js")))
                .andExpect(content().string(containsString("js/tutorial.js")))
                .andExpect(content().string(containsString("id=\"wth-tutorial-config\"")))
                .andExpect(content().string(containsString("data-auto-start=\"true\"")))
                .andExpect(content().string(containsString("/tutorial/neustarten")));
    }
}
