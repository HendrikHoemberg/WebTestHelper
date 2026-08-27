package dev.hendrikhoemberg.webtesthelper.model;

import java.time.Duration;

/**
 * Port representing the verification mailbox used by contact form checks (spec 7.2, D89).
 *
 * <p>The mailbox can be polled for a specific token that was submitted through a form.
 * The three results distinguish between a successful delivery ({@link Result#FOUND}),
 * a delivery timeout ({@link Result#NOT_FOUND}), and an infrastructure error ({@link Result#UNAVAILABLE}).
 */
public interface Mailbox {

    enum Result {
        FOUND,
        NOT_FOUND,
        UNAVAILABLE
    }

    /**
     * The verification email address to be typed into a form's email field.
     */
    String address();

    /**
     * Polls the mailbox for an email containing the given token until found or until
     * the time budget has expired.
     */
    Result awaitToken(String token, Duration budget);

    /**
     * Null-object instance representing an unconfigured mailbox.
     */
    Mailbox UNCONFIGURED = new Mailbox() {
        @Override
        public String address() {
            return "";
        }

        @Override
        public Result awaitToken(String token, Duration budget) {
            return Result.UNAVAILABLE;
        }
    };
}
