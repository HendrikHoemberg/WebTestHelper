package dev.hendrikhoemberg.webtesthelper.web;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.web.savedrequest.HttpSessionRequestCache;

/**
 * A request cache that never saves error dispatches. The {@code ExceptionTranslationFilter}
 * saves the original request when an anonymous user hits a protected URL; for an error
 * dispatch (a 404 beneath a missing asset, say) the saved value is a bogus {@code /error}
 * URL that the login success handler then redirects to — the classic login loop. Real user
 * requests are unaffected; only internal error/async dispatches are skipped.
 */
public class SkipErrorDispatchRequestCache extends HttpSessionRequestCache {

    @Override
    public void saveRequest(HttpServletRequest request, HttpServletResponse response) {
        if (request.getDispatcherType() == DispatcherType.ERROR) {
            return;
        }
        super.saveRequest(request, response);
    }
}
