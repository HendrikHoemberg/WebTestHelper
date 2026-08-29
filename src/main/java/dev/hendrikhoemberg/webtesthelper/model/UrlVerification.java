package dev.hendrikhoemberg.webtesthelper.model;

import java.time.Instant;

public record UrlVerification(
        String url,
        UrlStatus status,
        int httpStatus,
        String contentType,
        long contentLength,
        String bodyPrefix,
        String failureText,
        Instant checkedAt,
        String requestDetail,
        String responseDetail) {

    /** A verification carries no request/response detail until the verifier has seen one. */
    public UrlVerification(String url, UrlStatus status, int httpStatus, String contentType,
            long contentLength, String bodyPrefix, String failureText, Instant checkedAt) {
        this(url, status, httpStatus, contentType, contentLength, bodyPrefix, failureText,
                checkedAt, null, null);
    }

    public boolean ok() {
        return status == UrlStatus.OK;
    }

    public boolean hasBody() {
        return bodyPrefix != null;
    }

    public static UrlVerification ofSnapshot(PageSnapshot snapshot) {
        if (!snapshot.reachable()) {
            return new UrlVerification(snapshot.url().value(), UrlStatus.DEAD, 0, null, 0,
                    null, snapshot.unreachableReason(), Instant.now());
        }
        return new UrlVerification(snapshot.url().value(),
                UrlStatus.ofHttpStatus(snapshot.httpStatus()), snapshot.httpStatus(),
                snapshot.responseHeaders().get("content-type"),
                contentLengthOf(snapshot.responseHeaders().get("content-length")),
                null, null, Instant.now());
    }

    private static long contentLengthOf(String raw) {
        if (raw == null) {
            return 0;
        }
        try {
            return Long.parseLong(raw);
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
