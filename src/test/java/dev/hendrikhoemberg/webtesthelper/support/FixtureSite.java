package dev.hendrikhoemberg.webtesthelper.support;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The fixture site (spec 15): a small static site containing one of every failure mode,
 * served from loopback. Nothing in CI ever touches a real customer site.
 *
 * <p>Served as {@code 127.0.0.1} while {@link #externalBase()} addresses the same server as
 * {@code localhost}, so a link between the two is "external" by registrable host without
 * leaving the machine.
 *
 * <p>Unknown paths answer <strong>200</strong> with a not-found body: the fixture is a
 * soft-404 site by design, which is what gives the {@code {baseUrl}/{uuid}} probe something
 * to fingerprint. {@code /hart-404} is the only genuine 404 page.
 */
public final class FixtureSite implements AutoCloseable {

    private static final byte[] PNG_1X1 = Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==");

    private static final String SOFT_404_BODY = """
            <!doctype html><html lang="de"><head><meta charset="utf-8">
            <title>Seite nicht gefunden</title></head>
            <body><h1>Seite nicht gefunden</h1>
            <p>Die gewünschte Seite existiert leider nicht. <a href="/">Zur Startseite</a></p>
            </body></html>
            """;

    private static final String MAPS_BODY = """
            <!doctype html><html lang="en"><head><meta charset="utf-8"><title>Map</title></head>
            <body style="margin:0"><div style="width:100%;height:100%;background:#e5e3df">
            <p>For development purposes only</p></div>
            <script>console.error("Google Maps JavaScript API error: ApiNotActivatedMapError");</script>
            </body></html>
            """;

    private final HttpServer server;
    private final int port;

    private final AtomicInteger echoInFlight = new AtomicInteger();
    private final AtomicInteger maxConcurrent = new AtomicInteger();
    private final Map<String, AtomicInteger> requestCounts = new ConcurrentHashMap<>();

    private FixtureSite(HttpServer server) {
        this.server = server;
        this.port = server.getAddress().getPort();
    }

    public static FixtureSite start() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
            FixtureSite site = new FixtureSite(server);
            server.createContext("/", site::dispatch);
            server.start();
            return site;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public int port() {
        return port;
    }

    /** The site under test: {@code http://127.0.0.1:{port}/}. */
    public String baseUrl() {
        return "http://127.0.0.1:" + port + "/";
    }

    /** The same server under a different host name, so links to it count as external. */
    public String externalBase() {
        return "http://localhost:" + port + "/";
    }

    /** @param path without a leading slash */
    public String url(String path) {
        return baseUrl() + path;
    }

    public int maxConcurrent() {
        return maxConcurrent.get();
    }

    public int requestCount(String path) {
        return requestCounts.getOrDefault(path, new AtomicInteger()).get();
    }

    @Override
    public void close() {
        server.stop(0);
    }

    private void dispatch(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        requestCounts.computeIfAbsent(path, ignored -> new AtomicInteger()).incrementAndGet();
        try {
            switch (path) {
                case "/assets/logo.png" -> send(exchange, 200, "image/png", PNG_1X1);
                case "/assets/fehlt.png" -> send(exchange, 404, "text/plain", "nicht gefunden".getBytes(StandardCharsets.UTF_8));
                case "/assets/stil.css" -> send(exchange, 200, "text/css",
                        "body { font-family: sans-serif; }".getBytes(StandardCharsets.UTF_8));
                case "/assets/skript.js" -> send(exchange, 200, "text/javascript",
                        "window.fixtureGeladen = true;".getBytes(StandardCharsets.UTF_8));
                case "/dateien/handbuch.pdf" -> send(exchange, 200, "application/pdf", pdf());
                case "/dateien/preisliste.pdf" -> sendHtml(exchange, 200,
                        "<!doctype html><html lang=\"de\"><body><h1>Bitte anmelden</h1></body></html>");
                case "/dateien/winzig.pdf" -> send(exchange, 200, "application/pdf",
                        "%PDF-1.4\n".getBytes(StandardCharsets.US_ASCII));
                case "/weiter/1" -> redirect(exchange, "/weiter/2");
                case "/weiter/2" -> redirect(exchange, "/weiter/3");
                case "/weiter/3" -> redirect(exchange, "/ziel.html");
                case "/schleife/a" -> redirect(exchange, "/schleife/b");
                case "/schleife/b" -> redirect(exchange, "/schleife/a");
                case "/medien/ton.wav" -> send(exchange, 200, "audio/wav", wav());
                case "/medien/fehlt.mp4" -> send(exchange, 404, "text/plain", "weg".getBytes(StandardCharsets.UTF_8));
                case "/maps/embed/v1/place" -> sendHtml(exchange, 200, MAPS_BODY);
                case "/blockiert" -> {
                    exchange.getResponseHeaders().add("X-Frame-Options", "DENY");
                    sendHtml(exchange, 200, "<!doctype html><html lang=\"de\"><body><p>Bewertungen</p></body></html>");
                }
                case "/hart-404" -> sendHtml(exchange, 404, SOFT_404_BODY);
                case "/geblockt-403" -> sendHtml(exchange, 403,
                        "<!doctype html><html lang=\"de\"><body><h1>Zugriff verweigert</h1></body></html>");
                case "/kein-head" -> {
                    if ("HEAD".equals(exchange.getRequestMethod())) {
                        exchange.sendResponseHeaders(405, -1);
                    } else {
                        sendHtml(exchange, 200,
                                "<!doctype html><html lang=\"de\"><body><p>Ohne HEAD</p></body></html>");
                    }
                }
                case "/echo" -> {
                    int inFlight = echoInFlight.incrementAndGet();
                    try {
                        maxConcurrent.accumulateAndGet(inFlight, Math::max);
                        sleep(50);
                        String agent = exchange.getRequestHeaders().getFirst("User-Agent");
                        send(exchange, 200, "text/plain; charset=utf-8",
                                (agent == null ? "" : agent).getBytes(StandardCharsets.UTF_8));
                    } finally {
                        echoInFlight.decrementAndGet();
                    }
                }
                case "/extern/ok" -> sendHtml(exchange, 200,
                        "<!doctype html><html lang=\"de\"><body><h1>Partnerseite</h1></body></html>");
                case "/langsam" -> {
                    sleep();
                    sendHtml(exchange, 200, "<!doctype html><html lang=\"de\"><body><h1>Endlich</h1></body></html>");
                }
                default -> serveStaticOrSoft404(exchange, path);
            }
        } finally {
            exchange.close();
        }
    }

    private void serveStaticOrSoft404(HttpExchange exchange, String path) throws IOException {
        String resource = "fixture-site" + (path.endsWith("/") ? path + "index.html" : path);
        try (InputStream in = FixtureSite.class.getClassLoader().getResourceAsStream(resource)) {
            if (in == null) {
                sendHtml(exchange, 200, SOFT_404_BODY);   // the soft 404 — deliberately not 404
                return;
            }
            byte[] body = in.readAllBytes();
            String contentType = contentTypeOf(path);
            if (contentType.startsWith("text/html") || contentType.startsWith("application/xml")) {
                send(exchange, 200, contentType,
                        new String(body, StandardCharsets.UTF_8)
                                .replace("{{PORT}}", String.valueOf(port))
                                .getBytes(StandardCharsets.UTF_8));
            } else {
                send(exchange, 200, contentType, body);
            }
        }
    }

    private static String contentTypeOf(String path) {
        int dot = path.lastIndexOf('.');
        String extension = dot < 0 ? "" : path.substring(dot + 1);
        return switch (extension) {
            case "html", "" -> "text/html; charset=utf-8";
            case "xml" -> "application/xml; charset=utf-8";
            case "txt" -> "text/plain; charset=utf-8";
            case "png" -> "image/png";
            case "css" -> "text/css";
            case "js" -> "text/javascript";
            default -> "application/octet-stream";
        };
    }

    private static void redirect(HttpExchange exchange, String location) throws IOException {
        exchange.getResponseHeaders().add("Location", location);
        exchange.sendResponseHeaders(302, -1);
    }

    private static void sendHtml(HttpExchange exchange, int status, String body) throws IOException {
        send(exchange, status, "text/html; charset=utf-8", body.getBytes(StandardCharsets.UTF_8));
    }

    private static void send(HttpExchange exchange, int status, String contentType, byte[] body)
            throws IOException {                          // HEAD: length as a header, no body
        exchange.getResponseHeaders().add("Content-Type", contentType);
        if ("HEAD".equals(exchange.getRequestMethod())) {
            exchange.getResponseHeaders().add("Content-Length", String.valueOf(body.length));
            exchange.sendResponseHeaders(status, -1);     // -1: headers only
            return;
        }
        exchange.sendResponseHeaders(status, body.length);
        exchange.getResponseBody().write(body);
    }

    /**
     * A PDF that satisfies the three things FILE_DOWNLOAD asserts (spec 7.1): the %PDF magic
     * bytes, a matching content type and a non-trivial size. Padded past 1 KB with a comment.
     */
    private static byte[] pdf() {
        String body = """
                %PDF-1.4
                1 0 obj<</Type/Catalog/Pages 2 0 R>>endobj
                2 0 obj<</Type/Pages/Kids[3 0 R]/Count 1>>endobj
                3 0 obj<</Type/Page/Parent 2 0 R/MediaBox[0 0 200 200]>>endobj
                trailer<</Root 1 0 R>>
                """
                + "%" + "Fülltext ".repeat(160) + "\n%%EOF\n";
        return body.getBytes(StandardCharsets.ISO_8859_1);
    }

    /** Half a second of 440 Hz PCM — enough for readyState >= 1 and duration > 0. */
    private static byte[] wav() {
        int sampleRate = 8000;
        int frames = sampleRate / 2;
        ByteBuffer buffer = ByteBuffer.allocate(44 + frames * 2).order(ByteOrder.LITTLE_ENDIAN);
        buffer.put("RIFF".getBytes(StandardCharsets.US_ASCII)).putInt(36 + frames * 2);
        buffer.put("WAVE".getBytes(StandardCharsets.US_ASCII));
        buffer.put("fmt ".getBytes(StandardCharsets.US_ASCII)).putInt(16);
        buffer.putShort((short) 1).putShort((short) 1).putInt(sampleRate).putInt(sampleRate * 2);
        buffer.putShort((short) 2).putShort((short) 16);
        buffer.put("data".getBytes(StandardCharsets.US_ASCII)).putInt(frames * 2);
        for (int i = 0; i < frames; i++) {
            buffer.putShort((short) (Math.sin(2 * Math.PI * 440 * i / sampleRate) * 8000));
        }
        return buffer.array();
    }

    /**
     * Outlasts the test profile's navigation timeout (5s) with enough margin that a loaded
     * machine cannot make /langsam answer in time, and no more — every second beyond that is
     * paid twice, once per test that drives this slot.
     */
    private static void sleep() {
        sleep(8000);
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}