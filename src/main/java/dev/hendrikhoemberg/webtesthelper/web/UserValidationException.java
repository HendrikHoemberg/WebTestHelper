package dev.hendrikhoemberg.webtesthelper.web;

/**
 * Thrown when a user-management write fails validation (blank or duplicate username, short
 * password) or the D71 last-admin guard. Carries a message key — for the {@code WebExceptionHandler}
 * to resolve and render — plus any formatters for a parameterised key.
 */
public class UserValidationException extends RuntimeException {

    private final String messageKey;
    private final Object[] args;

    public UserValidationException(String messageKey, Object... args) {
        super(messageKey);
        this.messageKey = messageKey;
        this.args = args;
    }

    public String messageKey() {
        return messageKey;
    }

    public Object[] args() {
        return args;
    }
}
