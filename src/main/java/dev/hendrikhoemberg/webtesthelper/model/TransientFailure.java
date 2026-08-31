package dev.hendrikhoemberg.webtesthelper.model;

import java.util.List;

/**
 * Decides whether a navigation failure is the kind that comes and goes — a network change,
 * a DNS hiccup, a reset, a timeout — rather than a property of the page (a redirect loop, a
 * blocking response). Transient failures are worth one retry and never warrant a dead verdict
 * (spec 8): a check that reports a healthy site as broken is worse than no check at all.
 */
public final class TransientFailure {

    private static final List<String> TRANSIENT_MARKERS = List.of(
            "ERR_NETWORK_CHANGED",
            "ERR_CONNECTION_TIMED_OUT",
            "ERR_TIMED_OUT",
            "ERR_CONNECTION_REFUSED",
            "ERR_CONNECTION_RESET",
            "ERR_ABORTED",
            "ERR_NAME_NOT_RESOLVED",
            "ERR_ADDRESS_UNREACHABLE",
            "ERR_HTTP2_PROTOCOL_ERROR",
            "ERR_SSL_PROTOCOL_ERROR",
            "SSLHandshakeException",
            "Timeout");

    private TransientFailure() {
    }

    public static boolean isTransient(String reason) {
        if (reason == null || reason.isBlank()) {
            return false;
        }
        return TRANSIENT_MARKERS.stream().anyMatch(reason::contains);
    }
}
