package dev.hendrikhoemberg.webtesthelper.reporting;

import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class PdfRendererTest {

    @Test
    void launchOptions_setsHeadlessAndNoSandbox() {
        assertThat(PdfRenderer.launchOptions(true, false).headless).isTrue();
        assertThat(PdfRenderer.launchOptions(false, false).headless).isFalse();
        assertThat(PdfRenderer.launchOptions(true, true).args).contains("--no-sandbox");
        assertThat(PdfRenderer.launchOptions(true, false).args).isNullOrEmpty();
    }

    @Test
    void closeMethodHasPreDestroyAnnotation() throws NoSuchMethodException {
        assertThat(PdfRenderer.class.getMethod("close").isAnnotationPresent(jakarta.annotation.PreDestroy.class))
                .isTrue();
    }

    @Test
    void close_terminatesCleanlyWhenUnused() {
        PdfRenderer renderer = new PdfRenderer(false);
        renderer.close(1, TimeUnit.SECONDS);
    }

    @Test
    @org.junit.jupiter.api.Tag("browser")
    @org.junit.jupiter.api.parallel.ResourceLock("browser")
    void render_blocksOutboundNetworkRequests() throws Exception {
        java.util.concurrent.atomic.AtomicInteger requests = new java.util.concurrent.atomic.AtomicInteger();
        com.sun.net.httpserver.HttpServer server = com.sun.net.httpserver.HttpServer.create(
                new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            requests.incrementAndGet();
            byte[] body = "ok".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (var os = exchange.getResponseBody()) {
                os.write(body);
            }
        });
        server.start();
        PdfRenderer renderer = new PdfRenderer(true);
        try {
            int port = server.getAddress().getPort();
            String html = "<html><head><link rel=\"stylesheet\" href=\"http://127.0.0.1:" + port + "/a.css\"></head>"
                    + "<body><h1>Bericht</h1>"
                    + "<img src=\"http://127.0.0.1:" + port + "/pixel.png\">"
                    + "<iframe src=\"http://127.0.0.1:" + port + "/frame\"></iframe>"
                    + "</body></html>";

            byte[] pdf = renderer.render(html);

            assertThat(pdf).isNotEmpty();
            assertThat(new String(java.util.Arrays.copyOfRange(pdf, 0, 4), java.nio.charset.StandardCharsets.US_ASCII))
                    .isEqualTo("%PDF");
            assertThat(requests.get())
                    .as("PdfRenderer must abort all outbound network requests while rendering HTML")
                    .isZero();
        } finally {
            renderer.close();
            server.stop(0);
        }
    }
}
