package dev.hendrikhoemberg.webtesthelper.support;

import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.security.KeyStore;
import java.util.concurrent.Executors;

/**
 * An HTTPS fixture (spec 15): the same shape as {@link FixtureSite} but served over TLS from a
 * self-signed, loopback-only certificate. Used to exercise the certificate check without a
 * customer's real certificate.
 */
public final class FixtureTlsSite implements AutoCloseable {

    private final HttpsServer server;
    private final int port;

    private FixtureTlsSite(HttpsServer server) {
        this.server = server;
        this.port = server.getAddress().getPort();
    }

    public static FixtureTlsSite start() {
        try {
            KeyStore keyStore = KeyStore.getInstance("PKCS12");
            try (InputStream in = FixtureTlsSite.class.getClassLoader()
                    .getResourceAsStream("fixture-tls.p12")) {
                keyStore.load(in, "fixture".toCharArray());
            }
            KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            kmf.init(keyStore, "fixture".toCharArray());
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(kmf.getKeyManagers(), null, null);

            HttpsServer server = HttpsServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.setHttpsConfigurator(new HttpsConfigurator(context));
            server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
            FixtureTlsSite site = new FixtureTlsSite(server);
            server.createContext("/", (exchange) -> {
                byte[] body = "OK".getBytes(java.nio.charset.StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "text/plain; charset=utf-8");
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
                exchange.close();
            });
            server.start();
            return site;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public int port() {
        return port;
    }

    /** The site under test, addressed by its SAN name so the handshake succeeds. */
    public String baseUrl() {
        return "https://localhost:" + port + "/";
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
