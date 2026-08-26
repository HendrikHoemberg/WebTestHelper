package dev.hendrikhoemberg.webtesthelper.reporting;

import org.springframework.context.MessageSource;

import java.util.Locale;

/**
 * Humanises low-level technical identifiers and exception strings into plain-German sentences
 * (D32 / Spec 13.1), preventing raw technical error codes from leaking into colleague-facing reports.
 */
public final class TechnicalText {

    private TechnicalText() {
    }

    /**
     * Determines whether a raw string represents a technical identifier or exception dump.
     * URLs and normal human text return false.
     */
    public static boolean isTechnical(String raw) {
        if (raw == null || raw.isBlank()) {
            return false;
        }
        String trimmed = raw.trim();
        if (trimmed.startsWith("http://") || trimmed.startsWith("https://") || trimmed.startsWith("/")) {
            return false;
        }
        if (trimmed.contains("net::") || trimmed.contains("ERR_")) {
            return true;
        }
        if (trimmed.contains("java.") || trimmed.contains("javax.")) {
            return true;
        }
        if (trimmed.contains("Exception")) {
            return true;
        }
        return false;
    }

    /**
     * Transforms a technical error or raw identifier into a human-readable German explanation.
     * Unmapped technical strings map to the generic {@code ui.technisch.unbekannt} key.
     * Non-technical strings are returned unmodified.
     */
    public static String humanise(String raw, MessageSource messages, Locale locale) {
        if (raw == null) {
            return "";
        }
        if (!isTechnical(raw)) {
            return raw;
        }

        String key;
        if (raw.contains("ERR_NAME_NOT_RESOLVED") || raw.contains("UnknownHostException")) {
            key = "ui.technisch.name_not_resolved";
        } else if (raw.contains("ERR_CONNECTION_REFUSED") || raw.contains("ConnectException")) {
            key = "ui.technisch.connection_refused";
        } else if (raw.contains("ERR_CONNECTION_TIMED_OUT") || raw.contains("SocketTimeoutException")
                || raw.contains("TimeoutException")) {
            key = "ui.technisch.connection_timed_out";
        } else if (raw.contains("ERR_TOO_MANY_REDIRECTS")) {
            key = "ui.technisch.too_many_redirects";
        } else if (raw.contains("ERR_BLOCKED_BY_RESPONSE")) {
            key = "ui.technisch.blocked_by_response";
        } else if (raw.contains("ERR_ABORTED")) {
            key = "ui.technisch.aborted";
        } else if (raw.contains("ERR_CERT_DATE_INVALID") || raw.contains("CertificateExpiredException")
                || raw.contains("CertificateNotYetValidException")) {
            key = "ui.technisch.cert_date_invalid";
        } else if (raw.contains("SSLHandshakeException") || raw.contains("SSLException")
                || raw.contains("ERR_CERT_") || raw.contains("ERR_SSL_")) {
            key = "ui.technisch.ssl_error";
        } else {
            key = "ui.technisch.unbekannt";
        }

        return messages.getMessage(key, null, locale);
    }
}
