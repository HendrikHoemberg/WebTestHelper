package dev.hendrikhoemberg.webtesthelper.model;

import java.time.Instant;

public record TlsCertificateFact(String host, boolean handshakeOk, String failureText,
                                  Instant notBefore, Instant notAfter, String issuer) {

    public static final TlsCertificateFact NONE = new TlsCertificateFact(null, false, null,
            null, null, null);

    public boolean applicable() {
        return host != null;
    }
}
