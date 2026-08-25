package dev.hendrikhoemberg.webtesthelper.web;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * Maps IllegalArgumentException (such as unknown site or run IDs from domain services)
 * to HTTP 404 Not Found.
 */
@ControllerAdvice
public class WebExceptionHandler {

    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public void handleIllegalArgumentException() {
    }
}
