package dev.hendrikhoemberg.webtesthelper.reporting;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

@Component
public class WebhookNotifier {

    private static final Logger log = LoggerFactory.getLogger(WebhookNotifier.class);
    private final HttpClient httpClient;

    public WebhookNotifier() {
        this(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build());
    }

    public WebhookNotifier(HttpClient httpClient) {
        this.httpClient = httpClient;
    }

    public WebhookResult sendTestNotification(String webhookUrl) {
        if (webhookUrl == null || webhookUrl.isBlank()) {
            return new WebhookResult(false, "Keine Webhook-URL angegeben.");
        }
        String payload = """
                {
                  "text": "WebTestHelper: Test-Benachrichtigung erfolgreich empfangen!",
                  "blocks": [
                    {
                      "type": "section",
                      "text": {
                        "type": "mrkdwn",
                        "text": "*WebTestHelper: Verbindung erfolgreich!*\nDies ist eine Testnachricht zur Überprüfung der Webhook-Konfiguration (Slack / Microsoft Teams / Mattermost)."
                      }
                    }
                  ]
                }
                """;
        return postPayload(webhookUrl, payload);
    }

    public WebhookResult sendDigestNotification(Digest digest, String webhookUrl, String baseUrl) {
        if (webhookUrl == null || webhookUrl.isBlank()) {
            return new WebhookResult(false, "Keine Webhook-URL angegeben.");
        }
        String text = formatDigestText(digest, baseUrl);
        String escaped = escapeJson(text);
        String payload = "{\"text\":\"" + escaped + "\"}";
        return postPayload(webhookUrl, payload);
    }

    public WebhookResult postPayload(String webhookUrl, String jsonPayload) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(webhookUrl.strip()))
                    .timeout(Duration.ofSeconds(10))
                    .header("Content-Type", "application/json; charset=utf-8")
                    .POST(HttpRequest.BodyPublishers.ofString(jsonPayload))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            int status = response.statusCode();
            if (status >= 200 && status < 300) {
                return new WebhookResult(true, "Nachricht erfolgreich übermittelt (HTTP " + status + ").");
            } else {
                return new WebhookResult(false, "Webhook-Endpunkt meldete HTTP " + status + ": " + response.body());
            }
        } catch (Exception e) {
            log.error("Fehler beim Senden des Webhooks an {}: {}", webhookUrl, e.getMessage());
            return new WebhookResult(false, "Verbindungsfehler: " + e.getMessage());
        }
    }

    private String formatDigestText(Digest digest, String baseUrl) {
        StringBuilder sb = new StringBuilder();
        sb.append("⚠️ WebTestHelper: Neue Prüfergebnisse liegen vor.\n");
        if (baseUrl != null && !baseUrl.isBlank()) {
            sb.append("Zum Dashboard: ").append(baseUrl).append("\n");
        }
        return sb.toString();
    }

    private String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\b", "\\b")
                .replace("\f", "\\f")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }
}
