package dev.hendrikhoemberg.webtesthelper.crawler;

import dev.hendrikhoemberg.webtesthelper.model.NormalizedUrl;
import dev.hendrikhoemberg.webtesthelper.model.TlsCertificateFact;

import org.springframework.stereotype.Component;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.X509TrustManager;
import java.net.InetSocketAddress;
import java.security.GeneralSecurityException;
import java.security.cert.X509Certificate;
import java.time.Duration;

@Component
public class TlsProbe {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);

    private final Duration requestTimeout;

    public TlsProbe(VerifierProperties properties) {
        this.requestTimeout = properties.requestTimeout();
    }

    public TlsCertificateFact probe(NormalizedUrl baseUrl) {
        if (!baseUrl.isSecure()) {
            return TlsCertificateFact.NONE;
        }
        SSLSocketFactory factory = permissiveContext().getSocketFactory();
        try (SSLSocket socket = (SSLSocket) factory.createSocket()) {
            socket.connect(new InetSocketAddress(baseUrl.host(), baseUrl.port()),
                    (int) CONNECT_TIMEOUT.toMillis());
            socket.setSoTimeout((int) requestTimeout.toMillis());
            SSLParameters parameters = socket.getSSLParameters();
            parameters.setServerNames(java.util.List.of(new javax.net.ssl.SNIHostName(baseUrl.host())));
            socket.setSSLParameters(parameters);
            socket.startHandshake();
            X509Certificate leaf = (X509Certificate) socket.getSession().getPeerCertificates()[0];
            return new TlsCertificateFact(baseUrl.host(), true, null,
                    leaf.getNotBefore().toInstant(), leaf.getNotAfter().toInstant(),
                    leaf.getIssuerX500Principal().getName());
        } catch (java.io.IOException | RuntimeException e) {
            return new TlsCertificateFact(baseUrl.host(), false, truncate(e.toString(), 500),
                    null, null, null);
        }
    }

    /**
     * An {@code SSLContext} whose trust manager accepts any chain. This check answers "is it
     * expiring", and chain validation would turn every self-signed or private-CA site — the test
     * fixture included — into a handshake failure that says nothing about the expiry date.
     */
    private static SSLContext permissiveContext() {
        try {
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(null, new javax.net.ssl.TrustManager[]{
                    new X509TrustManager() {
                        @Override
                        public void checkClientTrusted(X509Certificate[] chain, String authType) {
                        }

                        @Override
                        public void checkServerTrusted(X509Certificate[] chain, String authType) {
                        }

                        @Override
                        public X509Certificate[] getAcceptedIssuers() {
                            return new X509Certificate[0];
                        }
                    }
            }, new java.security.SecureRandom());
            return context;
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String truncate(String value, int max) {
        if (value == null || value.length() <= max) {
            return value;
        }
        return value.substring(0, max);
    }
}
