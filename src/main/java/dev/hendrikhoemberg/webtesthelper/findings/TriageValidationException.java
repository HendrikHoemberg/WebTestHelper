package dev.hendrikhoemberg.webtesthelper.findings;

/**
 * Thrown when a triage action fails validation (e.g. missing reason or invalid expiry).
 * Carries a message key for the controller to render (spec 6.3).
 */
public class TriageValidationException extends RuntimeException {

    private final String messageKey;

    public TriageValidationException(String messageKey) {
        super(messageKey);
        this.messageKey = messageKey;
    }

    public String messageKey() {
        return messageKey;
    }
}
