package dev.hendrikhoemberg.webtesthelper.reporting;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;

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
        String payload = buildDigestBlockKitPayload(digest, baseUrl);
        return postPayload(webhookUrl, payload);
    }

    public CompletableFuture<WebhookResult> sendDigestNotificationAsync(Digest digest, String webhookUrl, String baseUrl) {
        if (webhookUrl == null || webhookUrl.isBlank()) {
            return CompletableFuture.completedFuture(new WebhookResult(false, "Keine Webhook-URL angegeben."));
        }
        String payload = buildDigestBlockKitPayload(digest, baseUrl);
        return postPayloadAsync(webhookUrl, payload);
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

    public CompletableFuture<WebhookResult> postPayloadAsync(String webhookUrl, String jsonPayload) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(webhookUrl.strip()))
                    .timeout(Duration.ofSeconds(10))
                    .header("Content-Type", "application/json; charset=utf-8")
                    .POST(HttpRequest.BodyPublishers.ofString(jsonPayload))
                    .build();

            return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                    .thenApply(response -> {
                        int status = response.statusCode();
                        if (status >= 200 && status < 300) {
                            return new WebhookResult(true, "Nachricht erfolgreich übermittelt (HTTP " + status + ").");
                        } else {
                            return new WebhookResult(false, "Webhook-Endpunkt meldete HTTP " + status + ": " + response.body());
                        }
                    })
                    .exceptionally(e -> {
                        log.error("Fehler beim asynchronen Senden des Webhooks an {}: {}", webhookUrl, e.getMessage());
                        return new WebhookResult(false, "Verbindungsfehler: " + e.getMessage());
                    });
        } catch (Exception e) {
            log.error("Fehler beim Erstellen der Webhook-Anfrage an {}: {}", webhookUrl, e.getMessage());
            return CompletableFuture.completedFuture(new WebhookResult(false, "Fehler: " + e.getMessage()));
        }
    }

    public static String buildDigestBlockKitPayload(Digest digest, String baseUrl) {
        String scopeName = switch (digest.scope()) {
            case PULSE -> "Täglicher Schnell-Check";
            case FULL -> "Wöchentlicher Voll-Prüflauf";
            case DEEP -> "Monatlicher Tiefenlauf";
        };

        int totalErrors = digest.errorTotal();
        int failedCount = digest.failedRuns();
        int sitesCount = digest.sites().size();

        String headline = (totalErrors > 0 || failedCount > 0)
                ? totalErrors + " Mängel bei " + sitesCount + " geprüften Website(s)"
                : "Alle " + sitesCount + " Website(s) ohne festgestellte Mängel";

        JsonObject root = new JsonObject();
        root.addProperty("text", "WebTestHelper: " + headline + " (" + scopeName + ")");

        JsonArray blocks = new JsonArray();

        // Header block
        JsonObject header = new JsonObject();
        header.addProperty("type", "header");
        JsonObject headerText = new JsonObject();
        headerText.addProperty("type", "plain_text");
        headerText.addProperty("text", "WebTestHelper: " + scopeName);
        headerText.addProperty("emoji", true);
        header.add("text", headerText);
        blocks.add(header);

        // Summary section
        JsonObject summarySection = new JsonObject();
        summarySection.addProperty("type", "section");
        JsonObject summaryText = new JsonObject();
        summaryText.addProperty("type", "mrkdwn");

        StringBuilder mrkdwn = new StringBuilder();
        if (totalErrors > 0 || failedCount > 0) {
            mrkdwn.append("🚨 *").append(headline).append("*\n");
        } else {
            mrkdwn.append("✅ *").append(headline).append("*\n");
        }

        if (!digest.loudSites().isEmpty()) {
            mrkdwn.append("\n*Betroffene Websites:*");
            for (SiteDigest s : digest.loudSites()) {
                mrkdwn.append("\n• *").append(s.siteName()).append("*");
                if (s.failed()) {
                    mrkdwn.append(" _(Prüflauf fehlgeschlagen)_");
                } else if (s.errorCount() > 0) {
                    mrkdwn.append(" (").append(s.errorCount()).append(" Fehler)");
                }
            }
        }
        summaryText.addProperty("text", mrkdwn.toString());
        summarySection.add("text", summaryText);
        blocks.add(summarySection);

        // Actions block (Dashboard button)
        if (baseUrl != null && !baseUrl.isBlank()) {
            JsonObject actions = new JsonObject();
            actions.addProperty("type", "actions");
            JsonArray elements = new JsonArray();

            JsonObject button = new JsonObject();
            button.addProperty("type", "button");
            JsonObject btnText = new JsonObject();
            btnText.addProperty("type", "plain_text");
            btnText.addProperty("text", "Dashboard öffnen");
            btnText.addProperty("emoji", true);
            button.add("text", btnText);
            button.addProperty("url", baseUrl.strip());
            button.addProperty("style", "primary");

            elements.add(button);
            actions.add("elements", elements);
            blocks.add(actions);
        }

        root.add("blocks", blocks);
        return root.toString();
    }
}
