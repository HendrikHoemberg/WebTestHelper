package dev.hendrikhoemberg.webtesthelper.web;

import dev.hendrikhoemberg.webtesthelper.auth.AppUserService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
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
        MockHttpSession session = new MockHttpSession();

        mvc.perform(post("/tutorial/abschliessen").with(csrf()).session(session))
                .andExpect(status().isNoContent());

        verify(appUserService).setTutorialAbgeschlossen("hans", true);
        assertThat(session.getAttribute(TutorialAdvice.SESSION_KEY)).isEqualTo(false);
    }

    @Test
    @WithMockUser(username = "hans")
    void neustartenResetsFlagAndRedirects() throws Exception {
        MockHttpSession session = new MockHttpSession();

        mvc.perform(post("/tutorial/neustarten").with(csrf()).session(session))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/?tour=start"));

        verify(appUserService).setTutorialAbgeschlossen("hans", false);
        assertThat(session.getAttribute(TutorialAdvice.SESSION_KEY)).isEqualTo(true);
    }

    @Test
    void unauthenticatedRejected() throws Exception {
        mvc.perform(post("/tutorial/abschliessen").with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(header().string("Location", org.hamcrest.Matchers.containsString("/anmelden")));
    }
}
