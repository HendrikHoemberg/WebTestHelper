package dev.hendrikhoemberg.webtesthelper.crawler;

import dev.hendrikhoemberg.webtesthelper.model.NormalizedUrl;
import dev.hendrikhoemberg.webtesthelper.model.TlsCertificateFact;
import dev.hendrikhoemberg.webtesthelper.model.UrlNormalizer;
import dev.hendrikhoemberg.webtesthelper.support.FixtureTlsSite;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;

class TlsProbeTest {

    private final TlsProbe probe = new TlsProbe(
            new VerifierProperties(4, Duration.ofSeconds(10), Duration.ZERO, Duration.ZERO, 2,
                    Duration.ofSeconds(2)));

    @Test
    void aReachableHttpsSiteReportsItsLeafCertificate() {
        try (FixtureTlsSite site = FixtureTlsSite.start()) {
            NormalizedUrl baseUrl = UrlNormalizer.normalize(site.baseUrl()).orElseThrow();

            TlsCertificateFact fact = probe.probe(baseUrl);

            assertThat(fact.handshakeOk()).isTrue();
            assertThat(fact.host()).isEqualTo("localhost");
            assertThat(fact.notAfter()).isNotNull();
            assertThat(ChronoUnit.DAYS.between(Instant.now(), fact.notAfter())).isGreaterThan(3000);
            assertThat(fact.issuer()).contains("WebTestHelper Fixture");
            assertThat(fact.failureText()).isNull();
        }
    }

    @Test
    void aRefusedConnectionReturnsRatherThanThrowing() {
        NormalizedUrl baseUrl = UrlNormalizer.normalize("https://localhost:9/").orElseThrow();

        TlsCertificateFact fact = probe.probe(baseUrl);

        assertThat(fact.handshakeOk()).isFalse();
        assertThat(fact.failureText()).isNotBlank();
        assertThat(fact.notBefore()).isNull();
        assertThat(fact.notAfter()).isNull();
    }

    @Test
    void anHttpSiteHasNoCertificateFact() {
        NormalizedUrl baseUrl = UrlNormalizer.normalize("http://localhost/").orElseThrow();

        TlsCertificateFact fact = probe.probe(baseUrl);

        assertThat(fact).isEqualTo(TlsCertificateFact.NONE);
        assertThat(fact.applicable()).isFalse();
    }

    @Test
    void aServerThatNeverSpeaksFailsWithinTheTimeoutRatherThanHanging() {
        try (ServerSocket server = new ServerSocket()) {
            server.bind(new InetSocketAddress("127.0.0.1", 0));
            Thread acceptor = new Thread(() -> {
                try (Socket ignored = server.accept()) {
                    Thread.sleep(60_000);
                } catch (Exception ignored) {
                }
            });
            acceptor.start();

            TlsProbe quick = new TlsProbe(
                    new VerifierProperties(4, Duration.ofSeconds(2), Duration.ZERO, Duration.ZERO,
                            2, Duration.ofSeconds(2)));
            NormalizedUrl baseUrl = UrlNormalizer.normalize(
                    "https://127.0.0.1:" + server.getLocalPort() + "/").orElseThrow();

            TlsCertificateFact fact = quick.probe(baseUrl);

            assertThat(fact.handshakeOk()).isFalse();
            assertThat(fact.failureText()).isNotBlank();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
