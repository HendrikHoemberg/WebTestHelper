package dev.hendrikhoemberg.webtesthelper.crawler;

import dev.hendrikhoemberg.webtesthelper.model.NormalizedUrl;
import dev.hendrikhoemberg.webtesthelper.model.UrlStatus;
import dev.hendrikhoemberg.webtesthelper.model.UrlVerification;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.function.Predicate;

@Component
public class UrlVerifier {

    public static final int PREFIX_BYTES = 1024;

    static final int HEADER_VALUE_LIMIT = 256;
    static final int DETAIL_LIMIT = 4096;

    private static final Set<String> BANNED_REQUEST_HEADERS = Set.of("authorization", "cookie");
    private static final Set<String> BANNED_RESPONSE_HEADERS = Set.of("set-cookie");

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final int FAILURE_TEXT_LIMIT = 500;

    private final HttpClient client = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(CONNECT_TIMEOUT)
            .build();

    private final VerifierProperties properties;
    private final HostThrottle throttle;
    private final CrawlerProperties crawler;
    private final Map<String, Semaphore> permits = new ConcurrentHashMap<>();

    public UrlVerifier(VerifierProperties properties, HostThrottle throttle,
                       CrawlerProperties crawler) {
        this.properties = properties;
        this.throttle = throttle;
        this.crawler = crawler;
    }

    public UrlVerification verify(NormalizedUrl url, String userAgent, boolean wantBody) {
        Instant checkedAt = Instant.now();
        try {
            if (wantBody) {
                return withBodyPrefix(url.value(), get(url, userAgent), checkedAt);
            }
            Exchange exchange = safeHead(url, userAgent);
            if (exchange == null || exchange.response().statusCode() == 405
                    || exchange.response().statusCode() == 501) {
                return withBodyPrefix(url.value(), get(url, userAgent), checkedAt);
            }
            return fromResponse(url.value(), exchange.requestDetail(), exchange.response(),
                    null, null, checkedAt);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return dead(url.value(), "Verbindung unterbrochen", 0, checkedAt);
        } catch (Exception e) {
            return dead(url.value(), truncate(e.toString(), FAILURE_TEXT_LIMIT), 0, checkedAt);
        }
    }

    public Map<String, UrlVerification> verifyAll(Collection<NormalizedUrl> urls, String userAgent,
            Predicate<NormalizedUrl> wantBody) {
        Map<String, UrlVerification> results = new ConcurrentHashMap<>();
        try (ExecutorService fanOut = Executors.newVirtualThreadPerTaskExecutor()) {
            for (NormalizedUrl url : urls) {
                fanOut.submit(() -> {
                    Semaphore host = permits.computeIfAbsent(url.registrableHost(),
                            ignored -> new Semaphore(properties.perHostPermits()));
                    try {
                        host.acquire();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        results.put(url.value(),
                                dead(url.value(), "Verbindung unterbrochen", 0, Instant.now()));
                        return null;
                    }
                    try {
                        throttle.await(url.host(), crawler.perHostDelay());
                        results.put(url.value(), verify(url, userAgent, wantBody.test(url)));
                    } finally {
                        host.release();
                    }
                    return null;
                });
            }
        }   // close() waits for every task; a pile of waiting virtual threads costs nothing
        return Map.copyOf(results);
    }

    private Exchange safeHead(NormalizedUrl url, String userAgent)
            throws InterruptedException {
        try {
            return head(url, userAgent);
        } catch (IOException e) {
            return null;
        }
    }

    private Exchange head(NormalizedUrl url, String userAgent)
            throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url.value()))
                .timeout(properties.requestTimeout())
                .header("User-Agent", userAgent)
                .method("HEAD", HttpRequest.BodyPublishers.noBody())
                .build();
        return new Exchange(requestDetailOf(request),
                client.send(request, HttpResponse.BodyHandlers.ofInputStream()));
    }

    private Exchange get(NormalizedUrl url, String userAgent)
            throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url.value()))
                .timeout(properties.requestTimeout())
                .header("User-Agent", userAgent)
                .header("Range", "bytes=0-" + (PREFIX_BYTES - 1))
                .GET()
                .build();
        return new Exchange(requestDetailOf(request),
                client.send(request, HttpResponse.BodyHandlers.ofInputStream()));
    }

    private static String readPrefix(InputStream body) throws IOException {
        try (InputStream in = body) {                    // BodyHandlers.ofInputStream()
            byte[] prefix = in.readNBytes(PREFIX_BYTES);            // 1024
            return new String(prefix, StandardCharsets.ISO_8859_1);
        }                                              // closing aborts the transfer
    }

    private static UrlVerification withBodyPrefix(String url, Exchange exchange,
            Instant checkedAt) throws IOException {
        try (InputStream body = exchange.response().body()) {
            String bodyPrefix = exchange.response().statusCode() < 400 ? readPrefix(body) : null;
            return fromResponse(url, exchange.requestDetail(), exchange.response(), bodyPrefix,
                    null, checkedAt);
        }
    }

    private static UrlVerification fromResponse(String url, String requestDetail,
            HttpResponse<InputStream> response, String bodyPrefix, String failureText,
            Instant checkedAt) {
        long contentLength = response.headers().firstValue("content-length")
                .map(UrlVerifier::parseLong).orElse(0L);
        String contentType = response.headers().firstValue("content-type").orElse(null);
        return new UrlVerification(url, UrlStatus.ofHttpStatus(response.statusCode()),
                response.statusCode(), contentType, contentLength, bodyPrefix, failureText,
                checkedAt, requestDetail, responseDetailOf(response, bodyPrefix));
    }

    /**
     * The method and URI of a sent request, plus its sanitised headers: credentials ({@code
     * Authorization}, {@code Cookie}) are never attached and every value is capped so a long
     * header cannot bloat a stored finding.
     */
    static String requestDetailOf(HttpRequest request) {
        StringBuilder detail = new StringBuilder();
        detail.append(request.method()).append(' ').append(request.uri()).append('\n');
        request.headers().map().entrySet().stream()
                .filter(entry -> !BANNED_REQUEST_HEADERS.contains(key(entry.getKey())))
                .filter(entry -> !entry.getValue().isEmpty())
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> appendHeader(detail, entry.getKey(), entry.getValue()));
        return truncate(detail.toString(), DETAIL_LIMIT);
    }

    /**
     * The status and headers of a received response, plus its body prefix. {@code Set-Cookie} is
     * dropped so a session token neither reaches the evidence nor a log line.
     */
    static String responseDetailOf(HttpResponse<?> response, String bodyPrefix) {
        StringBuilder detail = new StringBuilder();
        detail.append(response.statusCode()).append('\n');
        response.headers().map().entrySet().stream()
                .filter(entry -> !BANNED_RESPONSE_HEADERS.contains(key(entry.getKey())))
                .filter(entry -> !entry.getValue().isEmpty())
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> appendHeader(detail, entry.getKey(), entry.getValue()));
        if (bodyPrefix != null) {
            detail.append('\n').append(bodyPrefix);
        }
        return truncate(detail.toString(), DETAIL_LIMIT);
    }

    private static void appendHeader(StringBuilder detail, String name, Collection<String> values) {
        for (String value : values) {
            detail.append(name).append(": ").append(truncate(value, HEADER_VALUE_LIMIT)).append('\n');
        }
    }

    private static String key(String headerName) {
        return headerName.toLowerCase(Locale.ROOT);
    }

    private record Exchange(String requestDetail, HttpResponse<InputStream> response) {
    }

    private static UrlVerification dead(String url, String failureText, int httpStatus,
            Instant checkedAt) {
        return new UrlVerification(url, UrlStatus.DEAD, httpStatus, null, 0, null, failureText,
                checkedAt);
    }

    private static long parseLong(String value) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static String truncate(String message, int max) {
        if (message == null || message.length() <= max) {
            return message;
        }
        return message.substring(0, max);
    }
}
