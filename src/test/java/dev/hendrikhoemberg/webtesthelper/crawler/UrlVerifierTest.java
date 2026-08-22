package dev.hendrikhoemberg.webtesthelper.crawler;

import dev.hendrikhoemberg.webtesthelper.model.DocumentTypes;
import dev.hendrikhoemberg.webtesthelper.model.NormalizedUrl;
import dev.hendrikhoemberg.webtesthelper.model.UrlStatus;
import dev.hendrikhoemberg.webtesthelper.model.UrlNormalizer;
import dev.hendrikhoemberg.webtesthelper.model.UrlVerification;
import dev.hendrikhoemberg.webtesthelper.support.FixtureSite;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class UrlVerifierTest {

    private static final String AGENT = "WebTestHelper-Test/1.0";

    private static final CrawlerProperties CRAWLER = new CrawlerProperties(4, 20,
            Duration.ofSeconds(30), Duration.ZERO, Path.of("/tmp"), true);

    private static FixtureSite site;
    private static UrlVerifier verifier;

    @BeforeAll
    static void start() {
        site = FixtureSite.start();
        verifier = new UrlVerifier(new VerifierProperties(4, Duration.ofSeconds(10),
                Duration.ofHours(24), Duration.ofHours(1)), new HostThrottle(), CRAWLER);
    }

    @AfterAll
    static void stop() {
        site.close();
    }

    private static NormalizedUrl url(String value) {
        return UrlNormalizer.normalize(value).orElseThrow();
    }

    @Test
    void anAliveLinkIsOKAndADeadOneIsDEAD() {
        assertThat(verifier.verify(url(site.url("extern/ok")), AGENT, false).status())
                .isEqualTo(UrlStatus.OK);
        assertThat(verifier.verify(url(site.url("extern/ok")), AGENT, false).httpStatus())
                .isEqualTo(200);

        UrlVerification dead = verifier.verify(url(site.url("hart-404")), AGENT, false);
        assertThat(dead.status()).isEqualTo(UrlStatus.DEAD);
        assertThat(dead.httpStatus()).isEqualTo(404);
    }

    @Test
    void aBlockingWallIsUnverifiableNotDead() {
        UrlVerification blocked = verifier.verify(url(site.url("geblockt-403")), AGENT, false);
        assertThat(blocked.status()).isEqualTo(UrlStatus.UNVERIFIABLE);
        assertThat(blocked.httpStatus()).isEqualTo(403);
    }

    @Test
    void aBlockingWallIsUnverifiableEvenWhenABodyWasRequested() {
        UrlVerification blocked = verifier.verify(url(site.url("geblockt-403")), AGENT, true);
        assertThat(blocked.status()).isEqualTo(UrlStatus.UNVERIFIABLE);
        assertThat(blocked.httpStatus()).isEqualTo(403);
    }

    @Test
    void aTransportFailureIsDEADWithAReasonAndNoStatus() {
        UrlVerification dead = verifier.verify(url("http://127.0.0.1:9/tot"), AGENT, false);
        assertThat(dead.status()).isEqualTo(UrlStatus.DEAD);
        assertThat(dead.httpStatus()).isZero();
        assertThat(dead.failureText()).isNotBlank();
    }

    @Test
    void headAloneGivesTheLengthWithoutABody() {
        UrlVerification verification =
                verifier.verify(url(site.url("dateien/handbuch.pdf")), AGENT, false);
        assertThat(verification.ok()).isTrue();
        assertThat(verification.contentLength()).isGreaterThan(1024);
        assertThat(verification.bodyPrefix()).isNull();
    }

    @Test
    void aDocumentBodyIsReadAsAPrefixNotTheWholeFile() {
        UrlVerification verification =
                verifier.verify(url(site.url("dateien/handbuch.pdf")), AGENT, true);
        assertThat(verification.ok()).isTrue();
        assertThat(verification.bodyPrefix()).startsWith("%PDF");
        assertThat(verification.bodyPrefix().getBytes(StandardCharsets.ISO_8859_1).length)
                .isLessThanOrEqualTo(1024);
    }

    @Test
    void theTrapPdfIsOKButClearlyNotAPdf() {
        UrlVerification trap =
                verifier.verify(url(site.url("dateien/preisliste.pdf")), AGENT, true);
        assertThat(trap.ok()).isTrue();
        assertThat(trap.contentType()).contains("text/html");
        assertThat(trap.bodyPrefix()).doesNotStartWith("%PDF");
    }

    @Test
    void a405HeadFallsBackToGetInsteadOfBeingReportedDead() {
        UrlVerification verification = verifier.verify(url(site.url("kein-head")), AGENT, false);
        assertThat(verification.ok()).isTrue();
        assertThat(verification.httpStatus()).isEqualTo(200);
    }

    @Test
    void verifyAllAnswersEveryUrlKeyedByItsValue() {
        Map<String, UrlVerification> results = verifier.verifyAll(
                List.of(url(site.url("extern/ok")), url(site.url("hart-404")),
                        url(site.url("geblockt-403")), url(site.url("dateien/handbuch.pdf")),
                        url(site.url("dateien/preisliste.pdf")), url(site.url("kein-head"))),
                AGENT, DocumentTypes::isDocument);

        assertThat(results).hasSize(6);
        assertThat(results.keySet()).containsExactlyInAnyOrder(
                url(site.url("extern/ok")).value(), url(site.url("hart-404")).value(),
                url(site.url("geblockt-403")).value(), url(site.url("dateien/handbuch.pdf")).value(),
                url(site.url("dateien/preisliste.pdf")).value(), url(site.url("kein-head")).value());
        assertThat(results.get(url(site.url("geblockt-403")).value()).status())
                .isEqualTo(UrlStatus.UNVERIFIABLE);
        assertThat(results.get(url(site.url("dateien/handbuch.pdf")).value()).bodyPrefix())
                .startsWith("%PDF");
    }

    @Test
    void verifyAllBoundsConcurrencyPerRegistrableHost() {
        UrlVerifier twoPermits = new UrlVerifier(new VerifierProperties(2, Duration.ofSeconds(10),
                Duration.ofHours(24), Duration.ofHours(1)), new HostThrottle(), CRAWLER);
        NormalizedUrl echo = url(site.url("echo"));
        twoPermits.verifyAll(Collections.nCopies(20, echo), AGENT, ignored -> false);
        assertThat(site.maxConcurrent()).isLessThanOrEqualTo(2);
    }

    @Test
    void echoEchoesTheUserAgentTheCallerPassed() {
        UrlVerification echoed = verifier.verify(url(site.url("echo")), AGENT, true);
        assertThat(echoed.ok()).isTrue();
        assertThat(echoed.bodyPrefix()).isEqualTo(AGENT);
    }
}