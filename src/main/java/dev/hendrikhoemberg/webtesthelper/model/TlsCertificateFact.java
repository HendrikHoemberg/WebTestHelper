package dev.hendrikhoemberg.webtesthelper.model;

import java.time.Instant;

/**
 * What the TLS handshake for a host produced (Task 5 uses this). Created here so that
 * {@link RunFacts} is reshaped once instead of twice.
 */
public record TlsCertificateFact(String host, boolean handshakeOk, String failureText,
                                  Instant notBefore, Instant notAfter, String issuer) {

    public static final TlsCertificateFact NONE = new TlsCertificateFact(null, false, null,
            null, null, null);

    public boolean applicable() {
        return host != null;
    }
}
