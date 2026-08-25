package dev.hendrikhoemberg.webtesthelper.web;

import dev.hendrikhoemberg.webtesthelper.findings.TriageValidationException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.context.MessageSource;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.Locale;

/**
 * Maps IllegalArgumentException (such as unknown site or run IDs from domain services)
 * to HTTP 404 Not Found, and maps TriageValidationException to a flash error and redirect back.
 */
@ControllerAdvice
public class WebExceptionHandler {

    private final MessageSource messageSource;

    public WebExceptionHandler(MessageSource messageSource) {
        this.messageSource = messageSource;
    }

    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public void handleIllegalArgumentException() {
    }

    @ExceptionHandler(TriageValidationException.class)
    public String handleTriageValidationException(
            TriageValidationException ex,
            HttpServletRequest request,
            RedirectAttributes redirectAttributes,
            Locale locale) {
        String errorMsg = messageSource.getMessage(ex.messageKey(), null, ex.messageKey(), locale);
        redirectAttributes.addFlashAttribute("flashError", errorMsg);
        redirectAttributes.addFlashAttribute("triageError", errorMsg);

        String uri = request.getRequestURI();
        if (uri != null && uri.matches(".*/befunde/\\d+/bewerten.*")) {
            String findingId = uri.replaceAll(".*/befunde/(\\d+)/bewerten.*", "$1");
            return "redirect:/befunde/" + findingId;
        } else if (uri != null && uri.matches(".*/websites/\\d+/befunde/bewerten.*")) {
            String siteId = uri.replaceAll(".*/websites/(\\d+)/befunde/bewerten.*", "$1");
            return "redirect:/websites/" + siteId + "/befunde";
        }
        return "redirect:/";
    }
}

