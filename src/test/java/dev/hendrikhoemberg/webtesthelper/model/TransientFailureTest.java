package dev.hendrikhoemberg.webtesthelper.model;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TransientFailureTest {

    @Test
    void transportErrorsAreTransient() {
        for (String reason : new String[]{
                "net::ERR_NETWORK_CHANGED at https://kunde.de/x",
                "net::ERR_CONNECTION_TIMED_OUT",
                "net::ERR_CONNECTION_REFUSED",
                "net::ERR_CONNECTION_RESET",
                "net::ERR_ABORTED",
                "net::ERR_NAME_NOT_RESOLVED",
                "net::ERR_ADDRESS_UNREACHABLE",
                "net::ERR_HTTP2_PROTOCOL_ERROR",
                "net::ERR_SSL_PROTOCOL_ERROR",
                "SSLHandshakeException: handshake failure",
                "Timeout 5000ms exceeded",
                "net::ERR_TIMED_OUT"}) {
            assertThat(TransientFailure.isTransient(reason))
                    .as("'%s' must be transient", reason).isTrue();
        }
    }

    @Test
    void pageLevelFailuresAndBlanksAreNotTransient() {
        for (String reason : new String[]{
                "net::ERR_TOO_MANY_REDIRECTS",
                "net::ERR_BLOCKED_BY_RESPONSE",
                "net::ERR_CERT_DATE_INVALID",
                "Nicht als URL interpretierbar",
                "",
                null}) {
            assertThat(TransientFailure.isTransient(reason))
                    .as("'%s' must not be transient", reason).isFalse();
        }
    }
}
