package dev.hendrikhoemberg.webtesthelper.support;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class FixtureSiteTest {

    private static FixtureSite site;
    private static HttpClient client;

    @BeforeAll
    static void startSite() {
        site = FixtureSite.start();
        client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(Duration.ofSeconds(2))
                .build();
    }

    @AfterAll
    static void stopSite() {
        site.close();
    }

    private HttpResponse<byte[]> get(String path) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create(site.url(path))).build(),
                HttpResponse.BodyHandlers.ofByteArray());
    }

    @Test
    void staticPagesAreServedAndPortPlaceholdersAreSubstituted() throws Exception {
        HttpResponse<byte[]> response = get("");
        assertThat(response.statusCode()).isEqualTo(200);
        String body = new String(response.body(), java.nio.charset.StandardCharsets.UTF_8);
        assertThat(body).contains("Startseite");
        assertThat(body).doesNotContain("{{PORT}}");
        assertThat(body).contains("http://localhost:" + site.port() + "/extern/ok");
    }

    @Test
    void theMissingFooterImageIs404ButTheLogoIsAValidPng() throws Exception {
        assertThat(get("assets/fehlt.png").statusCode()).isEqualTo(404);

        HttpResponse<byte[]> logo = get("assets/logo.png");
        assertThat(logo.statusCode()).isEqualTo(200);
        assertThat(logo.headers().firstValue("content-type")).contains("image/png");
        assertThat(logo.body()).startsWith((byte) 0x89, (byte) 'P', (byte) 'N', (byte) 'G');
    }

    @Test
    void theRealPdfHasMagicBytesAndTheTrapServesHtml() throws Exception {
        HttpResponse<byte[]> pdf = get("dateien/handbuch.pdf");
        assertThat(pdf.headers().firstValue("content-type")).contains("application/pdf");
        assertThat(new String(pdf.body(), 0, 5, java.nio.charset.StandardCharsets.US_ASCII))
                .isEqualTo("%PDF-");
        assertThat(pdf.body().length).isGreaterThan(1024);

        HttpResponse<byte[]> trap = get("dateien/preisliste.pdf");
        assertThat(trap.statusCode()).isEqualTo(200);
        assertThat(trap.headers().firstValue("content-type")).hasValue("text/html; charset=utf-8");
    }

    @Test
    void redirectsFormAChainAndALoop() throws Exception {
        assertThat(get("weiter/1").statusCode()).isEqualTo(302);
        assertThat(get("weiter/1").headers().firstValue("location")).contains("/weiter/2");
        assertThat(get("weiter/3").headers().firstValue("location")).contains("/ziel.html");

        assertThat(get("schleife/a").headers().firstValue("location")).contains("/schleife/b");
        assertThat(get("schleife/b").headers().firstValue("location")).contains("/schleife/a");
    }

    @Test
    void theAudioFileIsAPlayableWav() throws Exception {
        HttpResponse<byte[]> wav = get("medien/ton.wav");
        assertThat(wav.headers().firstValue("content-type")).contains("audio/wav");
        assertThat(new String(wav.body(), 0, 4, java.nio.charset.StandardCharsets.US_ASCII))
                .isEqualTo("RIFF");
        assertThat(get("medien/fehlt.mp4").statusCode()).isEqualTo(404);
    }

    @Test
    void theFrameRoutesReproduceTheirRealWorldFailures() throws Exception {
        String maps = new String(get("maps/embed/v1/place").body(),
                java.nio.charset.StandardCharsets.UTF_8);
        assertThat(maps).contains("ApiNotActivatedMapError");
        assertThat(get("blockiert").headers().firstValue("x-frame-options")).contains("DENY");
    }

    @Test
    void theGreyMapRoutePaintsNothingAndTheHealthyOnePaints() throws Exception {
        String grey = new String(get("maps/embed/v1/place-grau").body(),
                java.nio.charset.StandardCharsets.UTF_8);
        // The console scan misses this slot: a sized canvas that stays blank and NO provider error.
        assertThat(grey).contains("<canvas");
        assertThat(grey).contains("For development purposes only");
        assertThat(grey).doesNotContain("ApiNotActivatedMapError");

        String healthy = new String(get("maps/embed/v1/place-gesund").body(),
                java.nio.charset.StandardCharsets.UTF_8);
        assertThat(healthy).contains("fillRect");
        assertThat(healthy).doesNotContain("For development purposes only");

        // The late-painting route must eventually paint, so the settle-confirmed probe can keep it
        // healthy instead of reporting a transient NOT_PAINTED.
        String late = new String(get("maps/embed/v1/place-spaet").body(),
                java.nio.charset.StandardCharsets.UTF_8);
        assertThat(late).contains("setTimeout");
        assertThat(late).contains("fillRect");
    }

    @Test
    void unknownPathsAreSoft404sAndOnlyHart404IsAHardOne() throws Exception {
        HttpResponse<byte[]> soft = get("gibt-es-nicht-" + java.util.UUID.randomUUID());
        assertThat(soft.statusCode()).isEqualTo(200);
        assertThat(new String(soft.body(), java.nio.charset.StandardCharsets.UTF_8))
                .contains("Seite nicht gefunden");

        assertThat(get("hart-404").statusCode()).isEqualTo(404);
    }

    @Test
    void twoProbesOfDifferentUnknownPathsProduceTheSameBody() throws Exception {
        // The soft-404 probe compares a random path's body against later pages. If the
        // not-found page varied per request, every page would look like a soft 404.
        String first = new String(get("aaa-" + java.util.UUID.randomUUID()).body(),
                java.nio.charset.StandardCharsets.UTF_8);
        String second = new String(get("bbb-" + java.util.UUID.randomUUID()).body(),
                java.nio.charset.StandardCharsets.UTF_8);
        assertThat(first).isEqualTo(second);
    }

    @Test
    void robotsDisallowsTheSecretAreaThatIsNeverthelessReachable() throws Exception {
        assertThat(new String(get("robots.txt").body(), java.nio.charset.StandardCharsets.UTF_8))
                .contains("Disallow: /geheim/");
        assertThat(get("geheim/intern.html").statusCode()).isEqualTo(200);
    }

    @Test
    void theSameServerAnsweredUnderLocalhostCountsAsExternal() throws Exception {
        HttpResponse<byte[]> external = client.send(
                HttpRequest.newBuilder(URI.create(site.externalBase() + "extern/ok")).build(),
                HttpResponse.BodyHandlers.ofByteArray());
        assertThat(external.statusCode()).isEqualTo(200);
    }

    @Test
    void theFlatterhaftSlotAnswers503FirstThen200() throws Exception {
        HttpResponse<byte[]> first = get("extern/flatterhaft");
        assertThat(first.statusCode()).isEqualTo(503);

        HttpResponse<byte[]> second = get("extern/flatterhaft");
        assertThat(second.statusCode()).isEqualTo(200);
    }

    private HttpResponse<byte[]> head(String path) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create(site.url(path)))
                        .method("HEAD", HttpRequest.BodyPublishers.noBody()).build(),
                HttpResponse.BodyHandlers.ofByteArray());
    }

    @Test
    void headCarriesTheRealContentLengthAndNoBody() throws Exception {
        HttpResponse<byte[]> head = head("dateien/handbuch.pdf");
        assertThat(head.statusCode()).isEqualTo(200);
        assertThat(head.headers().firstValue("content-length"))
                .hasValueSatisfying(len -> assertThat(Long.parseLong(len)).isGreaterThan(1024));
        assertThat(head.body()).isEmpty();
    }

    @Test
    void theKeinHeadSlotRefusesHeadButAnswersGet() throws Exception {
        assertThat(head("kein-head").statusCode()).isEqualTo(405);
        assertThat(get("kein-head").statusCode()).isEqualTo(200);
    }

    @Test
    void theBlockedSlotAnswers403() throws Exception {
        assertThat(get("geblockt-403").statusCode()).isEqualTo(403);
    }

    @Test
    void theTinyPdfIsARightTypeFileUnderAKilobyte() throws Exception {
        HttpResponse<byte[]> pdf = get("dateien/winzig.pdf");
        assertThat(pdf.statusCode()).isEqualTo(200);
        assertThat(pdf.headers().firstValue("content-type")).contains("application/pdf");
        assertThat(pdf.body().length).isLessThan(1024);
    }

    @Test
    void echoAnswersWithTheUserAgentItReceived() throws Exception {
        HttpResponse<byte[]> response = client.send(
                HttpRequest.newBuilder(URI.create(site.url("echo")))
                        .header("User-Agent", "FixtureSiteTest/2.0")
                        .build(),
                HttpResponse.BodyHandlers.ofByteArray());
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(new String(response.body(), java.nio.charset.StandardCharsets.UTF_8))
                .isEqualTo("FixtureSiteTest/2.0");
    }
}