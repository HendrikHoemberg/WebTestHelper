package dev.hendrikhoemberg.webtesthelper.reporting;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Arrays;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;

@Component
public class WebhookNotifier {

    private static final Logger log = LoggerFactory.getLogger(WebhookNotifier.class);

    @FunctionalInterface
    public interface HostResolver {
        InetAddress[] resolve(String host) throws UnknownHostException;
    }

    private final HttpClient httpClient;
    private final HostResolver hostResolver;

    public WebhookNotifier() {
        this(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build(), InetAddress::getAllByName);
    }

    public WebhookNotifier(HttpClient httpClient) {
        this(httpClient, InetAddress::getAllByName);
    }

    public WebhookNotifier(HttpClient httpClient, HostResolver hostResolver) {
        this.httpClient = httpClient;
        this.hostResolver = hostResolver != null ? hostResolver : InetAddress::getAllByName;
    }

    public void validateWebhookUrl(String rawUrl) {
        if (rawUrl == null || rawUrl.isBlank()) {
            throw new IllegalArgumentException("Keine Webhook-URL angegeben.");
        }
        URI uri;
        try {
            uri = URI.create(rawUrl.strip());
        } catch (Exception e) {
            throw new IllegalArgumentException("Ungültiges URL-Format.");
        }

        String scheme = uri.getScheme();
        if (scheme == null || (!scheme.equalsIgnoreCase("http") && !scheme.equalsIgnoreCase("https"))) {
            throw new IllegalArgumentException("Nur HTTP und HTTPS sind als Protokolle zulässig.");
        }

        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("Ungültige Webhook-URL: Kein Host angegeben.");
        }

        String normalizedHost = host.strip().toLowerCase(Locale.ROOT);
        if (normalizedHost.equals("localhost") || normalizedHost.endsWith(".localhost")
                || normalizedHost.endsWith(".local") || normalizedHost.equals("metadata.google.internal")) {
            throw new IllegalArgumentException("Zugriff auf interne Netzwerkadressen ist untersagt.");
        }

        InetAddress[] addresses;
        try {
            addresses = hostResolver.resolve(host);
        } catch (UnknownHostException e) {
            throw new IllegalArgumentException("Host konnte nicht aufgelöst werden.");
        }

        if (addresses == null || addresses.length == 0) {
            throw new IllegalArgumentException("Host konnte nicht aufgelöst werden.");
        }

        for (InetAddress address : addresses) {
            if (isBlockedIp(address)) {
                throw new IllegalArgumentException("Zugriff auf interne Netzwerkadressen ist untersagt.");
            }
        }
    }

    public static boolean isBlockedIp(InetAddress address) {
        if (address == null) {
            return true;
        }
        if (address.isLoopbackAddress() || address.isAnyLocalAddress()) {
            return true;
        }
        if (address.isSiteLocalAddress() || address.isLinkLocalAddress()) {
            return true;
        }
        if (address.isMulticastAddress()) {
            return true;
        }

        byte[] bytes = address.getAddress();
        if (bytes.length == 4) {
            int b0 = bytes[0] & 0xFF;
            int b1 = bytes[1] & 0xFF;
            int b2 = bytes[2] & 0xFF;

            if (b0 == 0) return true;                               // 0.0.0.0/8 (Current network)
            if (b0 == 10) return true;                              // 10.0.0.0/8 (Private)
            if (b0 == 100 && (b1 >= 64 && b1 <= 127)) return true; // 100.64.0.0/10 (CGNAT)
            if (b0 == 127) return true;                             // 127.0.0.0/8 (Loopback)
            if (b0 == 169 && b1 == 254) return true;                // 169.254.0.0/16 (Link-Local / Metadata)
            if (b0 == 172 && (b1 >= 16 && b1 <= 31)) return true;  // 172.16.0.0/12 (Private)
            if (b0 == 192 && b1 == 168) return true;                // 192.168.0.0/16 (Private)
            if (b0 == 192 && b1 == 0 && b2 == 0) return true;       // 192.0.0.0/24 (IETF Protocol Assignments)
            if (b0 == 192 && b1 == 0 && b2 == 2) return true;       // 192.0.2.0/24 (TEST-NET-1 Documentation)
            if (b0 == 192 && b1 == 88 && b2 == 99) return true;    // 192.88.99.0/24 (6to4 Relay Anycast RFC 7526)
            if (b0 == 198 && (b1 == 18 || b1 == 19)) return true;  // 198.18.0.0/15 (Benchmarking RFC 2544)
            if (b0 == 198 && b1 == 51 && b2 == 100) return true;   // 198.51.100.0/24 (TEST-NET-2 Documentation)
            if (b0 == 203 && b1 == 0 && b2 == 113) return true;    // 203.0.113.0/24 (TEST-NET-3 Documentation)
            if (b0 >= 224) return true;                             // 224.0.0.0/4 Multicast (224-239) & 240.0.0.0/4 Reserved (240-255)

        } else if (bytes.length == 16) {
            int b0 = bytes[0] & 0xFF;
            int b1 = bytes[1] & 0xFF;
            int b2 = bytes[2] & 0xFF;
            int b3 = bytes[3] & 0xFF;

            // IPv6 Multicast (ff00::/8)
            if (b0 == 0xFF) {
                return true;
            }

            // IPv6 Unique Local Address (ULA) (fc00::/7 -> fc00... to fdff...)
            if ((b0 & 0xFE) == 0xFC) {
                return true;
            }

            // IPv6 Link-Local (fe80::/10 -> fe80... to febf...)
            if (b0 == 0xFE && (b1 & 0xC0) == 0x80) {
                return true;
            }

            // IPv6 Site-Local deprecated (fec0::/10 -> fec0... to feff...)
            if (b0 == 0xFE && (b1 & 0xC0) == 0xC0) {
                return true;
            }

            // IPv6 Loopback (::1) and Unspecified (::)
            boolean allZeroExceptLast = true;
            for (int i = 0; i < 15; i++) {
                if (bytes[i] != 0) { allZeroExceptLast = false; break; }
            }
            if (allZeroExceptLast && (bytes[15] == 0 || (bytes[15] & 0xFF) == 1)) {
                return true;
            }

            // IPv4-compatible IPv6 (::w.x.y.z, RFC 4291 § 2.5.5.1) - first 12 bytes 0
            boolean isCompatible = true;
            for (int i = 0; i < 12; i++) {
                if (bytes[i] != 0) { isCompatible = false; break; }
            }
            if (isCompatible) {
                return checkEmbeddedIpv4(bytes, 12);
            }

            // IPv4-mapped IPv6 (::ffff:w.x.y.z, RFC 4291 § 2.5.5.2) - bytes 0..9 are 0, bytes 10..11 are 0xFF
            boolean isMapped = true;
            for (int i = 0; i < 10; i++) {
                if (bytes[i] != 0) { isMapped = false; break; }
            }
            if (isMapped && (bytes[10] & 0xFF) == 0xFF && (bytes[11] & 0xFF) == 0xFF) {
                return checkEmbeddedIpv4(bytes, 12);
            }

            // IPv4-translated / SIIT (::ffff:0:w.x.y.z, RFC 2765 § 2.1 / RFC 7915) - bytes 0..7 are 0, 8..9 are 0xFF, 10..11 are 0
            boolean isSiit = true;
            for (int i = 0; i < 8; i++) {
                if (bytes[i] != 0) { isSiit = false; break; }
            }
            if (isSiit && (bytes[8] & 0xFF) == 0xFF && (bytes[9] & 0xFF) == 0xFF && bytes[10] == 0 && bytes[11] == 0) {
                return checkEmbeddedIpv4(bytes, 12);
            }

            // NAT64 Well-Known Prefix (64:ff9b::/96, RFC 6052 § 2.1) - bytes 0..3 are 00 64 ff 9b, bytes 4..11 are 0
            if (b0 == 0x00 && b1 == 0x64 && b2 == 0xFF && b3 == 0x9B) {
                boolean bytes4to11Zero = true;
                for (int i = 4; i < 12; i++) {
                    if (bytes[i] != 0) { bytes4to11Zero = false; break; }
                }
                if (bytes4to11Zero) {
                    return checkEmbeddedIpv4(bytes, 12);
                }
                // NAT64 Local-Use Prefix (64:ff9b:1::/48, RFC 8215)
                if (bytes[4] == 0x00 && bytes[5] == 0x01) {
                    return true;
                }
            }

            // 6to4 Tunneling (2002::/16, RFC 3056) - bytes 0..1 are 0x20, 0x02, embedded IPv4 in bytes 2..5
            if (b0 == 0x20 && b1 == 0x02) {
                return checkEmbeddedIpv4(bytes, 2);
            }

            // Teredo Tunneling (2001:0000::/32, RFC 4380) - bytes 0..3 are 20 01 00 00
            // Evaluates embedded server IPv4 (bytes 4..7) and inverted client IPv4 (bytes 12..15)
            if (b0 == 0x20 && b1 == 0x01 && b2 == 0x00 && b3 == 0x00) {
                if (checkEmbeddedIpv4(bytes, 4)) {
                    return true;
                }
                byte[] clientBytes = new byte[] {
                        (byte) (bytes[12] ^ 0xFF),
                        (byte) (bytes[13] ^ 0xFF),
                        (byte) (bytes[14] ^ 0xFF),
                        (byte) (bytes[15] ^ 0xFF)
                };
                try {
                    if (isBlockedIp(InetAddress.getByAddress(clientBytes))) {
                        return true;
                    }
                } catch (UnknownHostException e) {
                    return true;
                }
                return true; // Teredo is legacy NAT-traversal tunneling; not used for legitimate server webhooks
            }

            // IPv6 Documentation prefix (2001:db8::/32, RFC 3849)
            if (b0 == 0x20 && b1 == 0x01 && b2 == 0x0D && b3 == 0xB8) {
                return true;
            }

            // Discard-Only prefix (100::/64, RFC 6666)
            if (b0 == 0x01 && b1 == 0x00) {
                boolean discardZeros = true;
                for (int i = 2; i < 8; i++) {
                    if (bytes[i] != 0) { discardZeros = false; break; }
                }
                if (discardZeros) return true;
            }

            // IPv6 Benchmarking (2001:2::/48, RFC 5180)
            if (b0 == 0x20 && b1 == 0x01 && b2 == 0x00 && b3 == 0x02) {
                return true;
            }

            // ORCHIDv2 (2001:20::/28, RFC 7343)
            if (b0 == 0x20 && b1 == 0x01 && b2 == 0x00 && (b3 & 0xF0) == 0x20) {
                return true;
            }

            // 6bone (3ffe::/16, RFC 3701)
            if (b0 == 0x3F && b1 == 0xFE) {
                return true;
            }

            // ISATAP interface identifier (RFC 5214): *::0000:5efe:w.x.y.z or *::0200:5efe:w.x.y.z
            // Bytes 8..11: 00:00:5e:fe or 02:00:5e:fe
            if ((bytes[8] == 0x00 || bytes[8] == 0x02) && bytes[9] == 0x00
                    && (bytes[10] & 0xFF) == 0x5E && (bytes[11] & 0xFF) == 0xFE) {
                return checkEmbeddedIpv4(bytes, 12);
            }
        }
        return false;
    }

    private static boolean checkEmbeddedIpv4(byte[] bytes, int offset) {
        try {
            return isBlockedIp(InetAddress.getByAddress(Arrays.copyOfRange(bytes, offset, offset + 4)));
        } catch (UnknownHostException e) {
            return true;
        }
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
            validateWebhookUrl(webhookUrl);
        } catch (IllegalArgumentException e) {
            return new WebhookResult(false, e.getMessage());
        }
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
                return new WebhookResult(false, "Webhook-Endpunkt meldete HTTP " + status + ".");
            }
        } catch (Exception e) {
            log.error("Fehler beim Senden des Webhooks an {}: {}", webhookUrl, e.getMessage());
            return new WebhookResult(false, "Verbindungsfehler: " + e.getMessage());
        }
    }

    public CompletableFuture<WebhookResult> postPayloadAsync(String webhookUrl, String jsonPayload) {
        try {
            validateWebhookUrl(webhookUrl);
        } catch (IllegalArgumentException e) {
            return CompletableFuture.completedFuture(new WebhookResult(false, e.getMessage()));
        }
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
                            return new WebhookResult(false, "Webhook-Endpunkt meldete HTTP " + status + ".");
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
