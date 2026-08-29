package dev.hendrikhoemberg.webtesthelper.web;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;

import static org.assertj.core.api.Assertions.assertThat;

class SkipErrorDispatchRequestCacheTest {

    private final SkipErrorDispatchRequestCache cache = new SkipErrorDispatchRequestCache();
    private static final String SAVED =
            "SPRING_SECURITY_SAVED_REQUEST";

    @Test
    void errorDispatchIsNeverSaved() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/");
        request.setDispatcherType(jakarta.servlet.DispatcherType.ERROR);
        request.setSession(new MockHttpSession());

        cache.saveRequest(request, new MockHttpServletResponse());

        assertThat(request.getSession().getAttribute(SAVED)).isNull();
    }

    @Test
    void normalRequestIsSavedAsBefore() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/befunde");
        request.setDispatcherType(jakarta.servlet.DispatcherType.REQUEST);
        request.setSession(new MockHttpSession());

        cache.saveRequest(request, new MockHttpServletResponse());

        assertThat(request.getSession().getAttribute(SAVED)).isNotNull();
    }
}
