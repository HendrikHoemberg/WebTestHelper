package dev.hendrikhoemberg.webtesthelper.crawler;

import dev.hendrikhoemberg.webtesthelper.model.NormalizedUrl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Optional;

/** Fetches robots.txt and sitemaps. No browser needed, and none wanted. */
@Component
public class SiteResourceFetcher {

    private static final Logger log = LoggerFactory.getLogger(SiteResourceFetcher.class);

    private final HttpClient client = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    /** Empty on any transport failure or non-2xx status — an absent robots.txt is normal. */
    public Optional<String> fetchText(NormalizedUrl url, String userAgent) {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url.value()))
                    .timeout(Duration.ofSeconds(10))
                    .header("User-Agent", userAgent)
                    .GET()
                    .build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                return Optional.empty();
            }
            return Optional.of(response.body());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        } catch (Exception e) {
            log.debug("{} nicht abrufbar: {}", url.value(), e.toString());
            return Optional.empty();
        }
    }
}