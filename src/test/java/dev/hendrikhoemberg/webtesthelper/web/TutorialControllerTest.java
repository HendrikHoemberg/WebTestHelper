package dev.hendrikhoemberg.webtesthelper.web;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@org.junit.jupiter.api.parallel.ResourceLock("spring-context")
@WebMvcTest(TutorialController.class)
class TutorialControllerTest {

    @Autowired
    MockMvc mvc;

    @MockitoBean
    AppUserService appUserService;

    @Test
    @WithMockUser(username = "hans")
    void abschliessenReturns204AndMarksComplete() throws Exception {
        mvc.perform(post("/tutorial/abschliessen").with(csrf()))
                .andExpect(status().isNoContent());

        verify(appUserService).setTutorialAbgeschlossen("hans", true);
    }

    @Test
    @WithMockUser(username = "hans")
    void neustartenResetsFlagAndRedirects() throws Exception {
        mvc.perform(post("/tutorial/neustarten").with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/?tour=start"));

        verify(appUserService).setTutorialAbgeschlossen("hans", false);
    }

    @Test
    void unauthenticatedRejected() throws Exception {
        mvc.perform(post("/tutorial/abschliessen").with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(header().string("Location", org.hamcrest.Matchers.containsString("/anmelden")));
    }
}
