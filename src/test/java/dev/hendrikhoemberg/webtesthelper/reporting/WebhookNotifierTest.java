package dev.hendrikhoemberg.webtesthelper.reporting;

import dev.hendrikhoemberg.webtesthelper.model.RunScope;
import dev.hendrikhoemberg.webtesthelper.model.RunStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class WebhookNotifierTest {

    @Test
    void sendTestNotification_returnsErrorOnBlankUrl() {
        WebhookNotifier notifier = new WebhookNotifier();
        WebhookResult result = notifier.sendTestNotification("   ");
        assertThat(result.success()).isFalse();
        assertThat(result.message()).contains("Keine Webhook-URL angegeben");
    }

    @Test
    void sendTestNotification_successWhenHttp200() throws Exception {
        HttpClient client = mock(HttpClient.class);
        @SuppressWarnings("unchecked")
        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(200);
        when(response.body()).thenReturn("ok");
        when(client.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(response);

        WebhookNotifier notifier = new WebhookNotifier(client);
        WebhookResult result = notifier.sendTestNotification("https://hooks.slack.com/services/T00/B00/X00");

        assertThat(result.success()).isTrue();
        assertThat(result.message()).contains("200");
    }

    @Test
    void sendTestNotification_failsWhenHttp400() throws Exception {
        HttpClient client = mock(HttpClient.class);
        @SuppressWarnings("unchecked")
        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(400);
        when(response.body()).thenReturn("invalid_payload");
        when(client.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(response);

        WebhookNotifier notifier = new WebhookNotifier(client);
        WebhookResult result = notifier.sendTestNotification("https://hooks.slack.com/services/T00/B00/X00");

        assertThat(result.success()).isFalse();
        assertThat(result.message()).contains("400");
    }

    @Test
    void sendTestNotification_eliminatesResponseBodyReflectionOnHttp400() throws Exception {
        HttpClient client = mock(HttpClient.class);
        @SuppressWarnings("unchecked")
        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(400);
        when(response.body()).thenReturn("SECRET_INTERNAL_TOKEN_LEAK");
        when(client.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(response);

        WebhookNotifier notifier = new WebhookNotifier(client);
        WebhookResult result = notifier.sendTestNotification("https://hooks.slack.com/services/T00/B00/X00");

        assertThat(result.success()).isFalse();
        assertThat(result.message()).contains("400");
        assertThat(result.message()).doesNotContain("SECRET_INTERNAL_TOKEN_LEAK");
    }

    @Test
    void sendTestNotification_eliminatesResponseBodyReflectionOnHttp500() throws Exception {
        HttpClient client = mock(HttpClient.class);
        @SuppressWarnings("unchecked")
        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(500);
        when(response.body()).thenReturn("DATABASE_CREDENTIALS_DUMP");
        when(client.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(response);

        WebhookNotifier notifier = new WebhookNotifier(client);
        WebhookResult result = notifier.sendTestNotification("https://hooks.slack.com/services/T00/B00/X00");

        assertThat(result.success()).isFalse();
        assertThat(result.message()).contains("500");
        assertThat(result.message()).doesNotContain("DATABASE_CREDENTIALS_DUMP");
    }

    @Test
    void validateWebhookUrl_rejectsNonHttpSchemes() {
        WebhookNotifier notifier = new WebhookNotifier();
        WebhookResult ftpResult = notifier.sendTestNotification("ftp://example.com/webhook");
        assertThat(ftpResult.success()).isFalse();
        assertThat(ftpResult.message()).contains("Nur HTTP und HTTPS");

        WebhookResult fileResult = notifier.sendTestNotification("file:///etc/passwd");
        assertThat(fileResult.success()).isFalse();
        assertThat(fileResult.message()).contains("Nur HTTP und HTTPS");
    }

    @Test
    void validateWebhookUrl_rejectsMissingHost() {
        WebhookNotifier notifier = new WebhookNotifier();
        WebhookResult result = notifier.sendTestNotification("http:///path");
        assertThat(result.success()).isFalse();
        assertThat(result.message()).contains("Kein Host angegeben");
    }

    @Test
    void validateWebhookUrl_rejectsLocalhostLiteral() {
        WebhookNotifier notifier = new WebhookNotifier();
        WebhookResult result = notifier.sendTestNotification("http://localhost:8080/webhook");
        assertThat(result.success()).isFalse();
        assertThat(result.message()).contains("interne Netzwerkadressen ist untersagt");
    }

    @Test
    void validateWebhookUrl_rejectsLoopbackIpv4AndIpv6() throws Exception {
        WebhookNotifier.HostResolver loopbackResolver = host -> new java.net.InetAddress[]{
                java.net.InetAddress.getByName("127.0.0.1")
        };
        WebhookNotifier notifier = new WebhookNotifier(mock(HttpClient.class), loopbackResolver);
        WebhookResult result = notifier.sendTestNotification("https://internal.service/hook");
        assertThat(result.success()).isFalse();
        assertThat(result.message()).contains("interne Netzwerkadressen ist untersagt");

        WebhookNotifier.HostResolver ipv6LoopbackResolver = host -> new java.net.InetAddress[]{
                java.net.InetAddress.getByName("::1")
        };
        WebhookNotifier notifierIpv6 = new WebhookNotifier(mock(HttpClient.class), ipv6LoopbackResolver);
        WebhookResult resultIpv6 = notifierIpv6.sendTestNotification("https://internal.service/hook");
        assertThat(resultIpv6.success()).isFalse();
        assertThat(resultIpv6.message()).contains("interne Netzwerkadressen ist untersagt");
    }

    @Test
    void validateWebhookUrl_rejectsRfc1918PrivateRanges() throws Exception {
        for (String ip : List.of("10.0.0.1", "10.255.255.254", "172.16.0.1", "172.31.255.254", "192.168.1.1", "192.168.254.254")) {
            WebhookNotifier.HostResolver resolver = host -> new java.net.InetAddress[]{
                    java.net.InetAddress.getByName(ip)
            };
            WebhookNotifier notifier = new WebhookNotifier(mock(HttpClient.class), resolver);
            WebhookResult result = notifier.sendTestNotification("https://internal.service/hook");
            assertThat(result.success()).as("Expected IP %s to be blocked", ip).isFalse();
            assertThat(result.message()).contains("interne Netzwerkadressen ist untersagt");
        }
    }

    @Test
    void validateWebhookUrl_rejectsLinkLocalAndCloudMetadata() throws Exception {
        for (String ip : List.of("169.254.1.1", "169.254.169.254", "fe80::1")) {
            WebhookNotifier.HostResolver resolver = host -> new java.net.InetAddress[]{
                    java.net.InetAddress.getByName(ip)
            };
            WebhookNotifier notifier = new WebhookNotifier(mock(HttpClient.class), resolver);
            WebhookResult result = notifier.sendTestNotification("https://metadata.service/hook");
            assertThat(result.success()).as("Expected IP %s to be blocked", ip).isFalse();
            assertThat(result.message()).contains("interne Netzwerkadressen ist untersagt");
        }
    }

    @Test
    void validateWebhookUrl_rejectsIpv6UlaAndWildcard() throws Exception {
        for (String ip : List.of("fc00::1", "fd12:3456:789a::1", "0.0.0.0", "::")) {
            WebhookNotifier.HostResolver resolver = host -> new java.net.InetAddress[]{
                    java.net.InetAddress.getByName(ip)
            };
            WebhookNotifier notifier = new WebhookNotifier(mock(HttpClient.class), resolver);
            WebhookResult result = notifier.sendTestNotification("https://service/hook");
            assertThat(result.success()).as("Expected IP %s to be blocked", ip).isFalse();
            assertThat(result.message()).contains("interne Netzwerkadressen ist untersagt");
        }
    }

    @Test
    void validateWebhookUrl_rejectsIpv4MappedIpv6() throws Exception {
        WebhookNotifier.HostResolver resolver = host -> new java.net.InetAddress[]{
                java.net.InetAddress.getByName("::ffff:127.0.0.1")
        };
        WebhookNotifier notifier = new WebhookNotifier(mock(HttpClient.class), resolver);
        WebhookResult result = notifier.sendTestNotification("https://mapped.service/hook");
        assertThat(result.success()).isFalse();
        assertThat(result.message()).contains("interne Netzwerkadressen ist untersagt");
    }

    @Test
    void validateWebhookUrl_rejectsMultiAddressWhenOneIsBlocked() throws Exception {
        WebhookNotifier.HostResolver resolver = host -> new java.net.InetAddress[]{
                java.net.InetAddress.getByName("93.184.216.34"), // public
                java.net.InetAddress.getByName("127.0.0.1")       // loopback
        };
        WebhookNotifier notifier = new WebhookNotifier(mock(HttpClient.class), resolver);
        WebhookResult result = notifier.sendTestNotification("https://dual.service/hook");
        assertThat(result.success()).isFalse();
        assertThat(result.message()).contains("interne Netzwerkadressen ist untersagt");
    }

    @Test
    void validateWebhookUrl_rejectsUnknownHost() throws Exception {
        WebhookNotifier.HostResolver resolver = host -> {
            throw new java.net.UnknownHostException("Host not found");
        };
        WebhookNotifier notifier = new WebhookNotifier(mock(HttpClient.class), resolver);
        WebhookResult result = notifier.sendTestNotification("https://unresolvable.invalid/hook");
        assertThat(result.success()).isFalse();
        assertThat(result.message()).contains("Host konnte nicht aufgelöst werden");
    }

    @Test
    void buildDigestBlockKitPayload_containsExpectedBlocks() {
        SiteDigest site1 = new SiteDigest(1L, "Acme Shop", 101L, dev.hendrikhoemberg.webtesthelper.model.RunStatus.COMPLETED,
                java.time.Instant.now(), null, false, new DigestSection(java.util.List.of(), 2), null, 2, 0, 1, 0);
        SiteDigest site2 = new SiteDigest(2L, "Blog", 102L, dev.hendrikhoemberg.webtesthelper.model.RunStatus.FAILED,
                java.time.Instant.now(), "Timeout", false, null, null, 0, 0, 0, 0);
        Digest digest = new Digest(dev.hendrikhoemberg.webtesthelper.model.RunScope.PULSE, java.time.Instant.now(), java.util.List.of(site1, site2));

        String json = WebhookNotifier.buildDigestBlockKitPayload(digest, "https://wth.example.com");

        assertThat(json).contains("\"blocks\":");
        assertThat(json).contains("Täglicher Schnell-Check");
        assertThat(json).contains("Acme Shop");
        assertThat(json).contains("Blog");
        assertThat(json).contains("https://wth.example.com");
        assertThat(json).contains("\"type\":\"actions\"");
    }

    @Test
    void sendDigestNotificationAsync_dispatchesViaHttpClientSendAsync() throws Exception {
        HttpClient client = mock(HttpClient.class);
        @SuppressWarnings("unchecked")
        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(200);
        when(response.body()).thenReturn("ok");
        when(client.sendAsync(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(java.util.concurrent.CompletableFuture.completedFuture(response));

        WebhookNotifier.HostResolver publicResolver = host -> new java.net.InetAddress[]{
                java.net.InetAddress.getByName("93.184.216.34")
        };
        WebhookNotifier notifier = new WebhookNotifier(client, publicResolver);

        SiteDigest site1 = new SiteDigest(1L, "Acme Shop", 101L, dev.hendrikhoemberg.webtesthelper.model.RunStatus.COMPLETED,
                java.time.Instant.now(), null, false, null, null, 1, 0, 0, 0);
        Digest digest = new Digest(dev.hendrikhoemberg.webtesthelper.model.RunScope.PULSE, java.time.Instant.now(), java.util.List.of(site1));

        WebhookResult result = notifier.sendDigestNotificationAsync(digest, "https://hooks.slack.com/services/T00/B00/X00", "https://wth.example.com").get();

        assertThat(result.success()).isTrue();
        assertThat(result.message()).contains("200");
    }

    @Test
    void validateWebhookUrl_rejectsIpv4CompatibleIpv6Addresses() throws Exception {
        List<String> compatIps = List.of(
                "::127.0.0.1",            // Loopback
                "::127.0.0.2",            // Loopback variation
                "::127.255.255.255",      // Loopback subnet boundary
                "::7f00:1",               // Hex format for 127.0.0.1
                "::169.254.169.254",      // Cloud instance metadata
                "::169.254.1.1",          // Link-local
                "::a9fe:a9fe",            // Hex format for 169.254.169.254
                "::10.0.0.1",             // RFC 1918 Class A
                "::172.16.0.1",           // RFC 1918 Class B
                "::192.168.1.1",          // RFC 1918 Class C
                "::100.64.0.1",           // CGNAT (RFC 6598)
                "::0.0.0.0"               // 0.0.0.0/8 wildcard
        );

        for (String ip : compatIps) {
            WebhookNotifier.HostResolver resolver = host -> new InetAddress[]{
                    InetAddress.getByName(ip)
            };
            WebhookNotifier notifier = new WebhookNotifier(mock(HttpClient.class), resolver);
            WebhookResult result = notifier.sendTestNotification("https://compat.service/hook");
            assertThat(result.success())
                    .as("Expected IPv4-compatible IP %s to be blocked", ip)
                    .isFalse();
            assertThat(result.message())
                    .contains("interne Netzwerkadressen ist untersagt");
        }
    }

    @Test
    void isBlockedIp_rejectsRawIpv4CompatibleIpv6ByteArrays() throws Exception {
        // 1. Loopback 127.0.0.1 as raw bytes in IPv4-compatible format (first 12 bytes zero)
        byte[] bytesLoopback = new byte[16];
        bytesLoopback[12] = 127;
        bytesLoopback[15] = 1;
        assertThat(WebhookNotifier.isBlockedIp(InetAddress.getByAddress(bytesLoopback)))
                .as("Raw ::127.0.0.1 byte representation must be blocked")
                .isTrue();

        // 2. Cloud metadata 169.254.169.254 as raw bytes
        byte[] bytesMetadata = new byte[16];
        bytesMetadata[12] = (byte) 169;
        bytesMetadata[13] = (byte) 254;
        bytesMetadata[14] = (byte) 169;
        bytesMetadata[15] = (byte) 254;
        assertThat(WebhookNotifier.isBlockedIp(InetAddress.getByAddress(bytesMetadata)))
                .as("Raw ::169.254.169.254 byte representation must be blocked")
                .isTrue();

        // 3. RFC 1918 10.0.0.1 as raw bytes
        byte[] bytesPrivate = new byte[16];
        bytesPrivate[12] = 10;
        bytesPrivate[15] = 1;
        assertThat(WebhookNotifier.isBlockedIp(InetAddress.getByAddress(bytesPrivate)))
                .as("Raw ::10.0.0.1 byte representation must be blocked")
                .isTrue();

        // 4. CGNAT 100.64.0.1 as raw bytes
        byte[] bytesCgnat = new byte[16];
        bytesCgnat[12] = 100;
        bytesCgnat[13] = 64;
        bytesCgnat[15] = 1;
        assertThat(WebhookNotifier.isBlockedIp(InetAddress.getByAddress(bytesCgnat)))
                .as("Raw ::100.64.0.1 byte representation must be blocked")
                .isTrue();
    }

    @Test
    void validateWebhookUrl_rejectsExpandedIpv4MappedIpv6Ranges() throws Exception {
        List<String> mappedIps = List.of(
                "::ffff:169.254.169.254", // Cloud metadata
                "::ffff:a9fe:a9fe",       // Hex cloud metadata
                "::ffff:10.0.0.1",        // RFC 1918 Class A
                "::ffff:172.16.0.1",      // RFC 1918 Class B
                "::ffff:192.168.1.1",     // RFC 1918 Class C
                "::ffff:100.64.0.1",      // CGNAT
                "::ffff:0.0.0.0"          // Wildcard
        );

        for (String ip : mappedIps) {
            WebhookNotifier.HostResolver resolver = host -> new InetAddress[]{
                    InetAddress.getByName(ip)
            };
            WebhookNotifier notifier = new WebhookNotifier(mock(HttpClient.class), resolver);
            WebhookResult result = notifier.sendTestNotification("https://mapped.service/hook");
            assertThat(result.success())
                    .as("Expected IPv4-mapped IP %s to be blocked", ip)
                    .isFalse();
            assertThat(result.message())
                    .contains("interne Netzwerkadressen ist untersagt");
        }
    }

    @Test
    void validateWebhookUrl_rejectsEmbeddedIpv4In6to4AndNat64() throws Exception {
        List<String> embeddedIps = List.of(
                "2002:7f00:1::",            // 6to4 embedding 127.0.0.1
                "2002:a9fe:a9fe::",         // 6to4 embedding 169.254.169.254
                "2002:0a00:1::",            // 6to4 embedding 10.0.0.1
                "2002:c0a8:101::",          // 6to4 embedding 192.168.1.1
                "64:ff9b::127.0.0.1",       // NAT64 WKP embedding 127.0.0.1
                "64:ff9b::169.254.169.254", // NAT64 WKP embedding 169.254.169.254
                "64:ff9b::10.0.0.1",        // NAT64 WKP embedding 10.0.0.1
                "64:ff9b::192.168.1.1"      // NAT64 WKP embedding 192.168.1.1
        );

        for (String ip : embeddedIps) {
            WebhookNotifier.HostResolver resolver = host -> new InetAddress[]{
                    InetAddress.getByName(ip)
            };
            WebhookNotifier notifier = new WebhookNotifier(mock(HttpClient.class), resolver);
            WebhookResult result = notifier.sendTestNotification("https://embedded.service/hook");
            assertThat(result.success())
                    .as("Expected embedded IPv4 address %s to be blocked", ip)
                    .isFalse();
            assertThat(result.message())
                    .contains("interne Netzwerkadressen ist untersagt");
        }
    }

    @Test
    void validateWebhookUrl_rejectsMulticastBroadcastAndReservedAddresses() throws Exception {
        List<String> specialIps = List.of(
                "224.0.0.1",              // IPv4 All Systems Multicast (224.0.0.0/4)
                "239.255.255.250",        // IPv4 SSDP Multicast
                "240.0.0.1",              // IPv4 Reserved Class E (240.0.0.0/4)
                "255.255.255.255",        // IPv4 Limited Broadcast
                "ff02::1",                // IPv6 Link-Local All Nodes Multicast (ff00::/8)
                "ff05::2"                 // IPv6 Site-Local Multicast
        );

        for (String ip : specialIps) {
            WebhookNotifier.HostResolver resolver = host -> new InetAddress[]{
                    InetAddress.getByName(ip)
            };
            WebhookNotifier notifier = new WebhookNotifier(mock(HttpClient.class), resolver);
            WebhookResult result = notifier.sendTestNotification("https://special.service/hook");
            assertThat(result.success())
                    .as("Expected special/multicast IP %s to be blocked", ip)
                    .isFalse();
            assertThat(result.message())
                    .contains("interne Netzwerkadressen ist untersagt");
        }
    }

    @Test
    void isBlockedIp_permitsLegitimatePublicIpv4AndIpv6Addresses() throws Exception {
        List<String> publicIps = List.of(
                "93.184.216.34",                  // example.com IPv4
                "8.8.8.8",                        // Google Public DNS
                "1.1.1.1",                        // Cloudflare Public DNS
                "11.0.0.1",                       // Adjacent to 10.0.0.0/8
                "172.15.255.255",                 // Below 172.16.0.0/12
                "172.32.0.1",                     // Above 172.31.255.255
                "192.167.1.1",                    // Below 192.168.0.0/16
                "192.169.1.1",                    // Above 192.168.255.255
                "100.63.255.255",                 // Below 100.64.0.0/10
                "100.128.0.1",                    // Above 100.127.255.255
                "2606:4700:4700::1111",           // Cloudflare IPv6
                "2001:4860:4860::8888",           // Google IPv6
                "2a00:1450:4001:830::200e",       // Google Web IPv6
                "::ffff:93.184.216.34"            // IPv4-mapped public IP
        );

        for (String ip : publicIps) {
            InetAddress addr = InetAddress.getByName(ip);
            assertThat(WebhookNotifier.isBlockedIp(addr))
                    .as("Public IP %s must NOT be blocked (false positive)", ip)
                    .isFalse();
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {400, 401, 403, 404, 500, 502, 503, 504})
    void sendTestNotification_neverReflectsResponseBodyAcrossAllErrorStatuses(int statusCode) throws Exception {
        String leakedBody = "{\"error\":\"SENSITIVE_LEAK\",\"secret\":\"SUPER_SECRET_API_KEY_999\",\"trace\":\"db.connect()\"}";
        HttpClient client = mock(HttpClient.class);
        @SuppressWarnings("unchecked")
        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(statusCode);
        when(response.body()).thenReturn(leakedBody);
        when(client.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(response);

        WebhookNotifier.HostResolver publicResolver = host -> new InetAddress[]{
                InetAddress.getByName("93.184.216.34")
        };
        WebhookNotifier notifier = new WebhookNotifier(client, publicResolver);
        WebhookResult result = notifier.sendTestNotification("https://hooks.slack.com/services/T00/B00/X00");

        assertThat(result.success()).isFalse();
        assertThat(result.message()).contains(String.valueOf(statusCode));
        assertThat(result.message()).doesNotContain("SENSITIVE_LEAK");
        assertThat(result.message()).doesNotContain("SUPER_SECRET_API_KEY_999");
        assertThat(result.message()).doesNotContain("db.connect()");
        assertThat(result.message()).doesNotContain("{");
    }

    @Test
    void sendDigestNotification_eliminatesResponseBodyReflectionOnHttpError() throws Exception {
        String leakedBody = "DATABASE_AUTH_FAILED: password=rootpassword";
        HttpClient client = mock(HttpClient.class);
        @SuppressWarnings("unchecked")
        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(502);
        when(response.body()).thenReturn(leakedBody);
        when(client.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(response);

        WebhookNotifier.HostResolver publicResolver = host -> new InetAddress[]{
                InetAddress.getByName("93.184.216.34")
        };
        WebhookNotifier notifier = new WebhookNotifier(client, publicResolver);

        Digest digest = new Digest(RunScope.PULSE, java.time.Instant.now(), List.of());
        WebhookResult result = notifier.sendDigestNotification(digest, "https://hooks.slack.com/services/T00/B00/X00", "https://wth.local");

        assertThat(result.success()).isFalse();
        assertThat(result.message()).contains("502");
        assertThat(result.message()).doesNotContain("DATABASE_AUTH_FAILED");
        assertThat(result.message()).doesNotContain("rootpassword");
    }

    @Test
    void sendDigestNotificationAsync_eliminatesResponseBodyReflectionOnHttpError() throws Exception {
        String leakedBody = "ASYNC_STACK_TRACE: NullPointerException at internal.service.Auth:42";
        HttpClient client = mock(HttpClient.class);
        @SuppressWarnings("unchecked")
        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(500);
        when(response.body()).thenReturn(leakedBody);
        when(client.sendAsync(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(java.util.concurrent.CompletableFuture.completedFuture(response));

        WebhookNotifier.HostResolver publicResolver = host -> new InetAddress[]{
                InetAddress.getByName("93.184.216.34")
        };
        WebhookNotifier notifier = new WebhookNotifier(client, publicResolver);

        Digest digest = new Digest(RunScope.PULSE, java.time.Instant.now(), List.of());
        WebhookResult result = notifier.sendDigestNotificationAsync(digest, "https://hooks.slack.com/services/T00/B00/X00", "https://wth.local").get();

        assertThat(result.success()).isFalse();
        assertThat(result.message()).contains("500");
        assertThat(result.message()).doesNotContain("ASYNC_STACK_TRACE");
        assertThat(result.message()).doesNotContain("NullPointerException");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "http://[::127.0.0.1]:8080/hook",
            "http://[::169.254.169.254]:80/latest/meta-data",
            "http://[::10.0.0.1]:443/webhook",
            "http://[::ffff:127.0.0.1]:8080/hook",
            "http://[::1]:9090/actuator",
            "http://[2002:7f00:1::]:8080/hook",
            "http://[64:ff9b::127.0.0.1]:80/hook"
    })
    void validateWebhookUrl_rejectsBracketedIpv6TargetsWithPortAndPath(String url) {
        WebhookNotifier notifier = new WebhookNotifier();
        assertThatThrownBy(() -> notifier.validateWebhookUrl(url))
                .as("URL %s must be rejected as an internal destination", url)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("interne Netzwerkadressen ist untersagt");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "http://LOCALHOST:8080/hook",
            "http://LocalHost:9090",
            "http://sub.LOCALhost/hook",
            "http://printer.LOCAL/hook",
            "http://METADATA.GOOGLE.INTERNAL/computeMetadata/v1"
    })
    void validateWebhookUrl_rejectsNormalizedInternalHostVariations(String url) {
        WebhookNotifier notifier = new WebhookNotifier();
        assertThatThrownBy(() -> notifier.validateWebhookUrl(url))
                .as("URL %s must be rejected regardless of casing", url)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("interne Netzwerkadressen ist untersagt");
    }
}

