package dev.hendrikhoemberg.webtesthelper.challenger;

import dev.hendrikhoemberg.webtesthelper.model.RunScope;
import dev.hendrikhoemberg.webtesthelper.model.RunStatus;
import dev.hendrikhoemberg.webtesthelper.reporting.Digest;
import dev.hendrikhoemberg.webtesthelper.reporting.DigestSection;
import dev.hendrikhoemberg.webtesthelper.reporting.SiteDigest;
import dev.hendrikhoemberg.webtesthelper.reporting.WebhookNotifier;
import dev.hendrikhoemberg.webtesthelper.reporting.WebhookResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.InetAddress;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class WebhookNotifierAdversarialTest {

    @ParameterizedTest
    @ValueSource(strings = {
            "http://127.0.0.1",
            "http://127.0.0.1:8080/hook",
            "http://127.0.0.2",
            "http://127.255.255.255",
            "http://localhost",
            "http://localhost:9090",
            "http://sub.localhost",
            "http://printer.local",
            "http://metadata.google.internal",
            "http://0.0.0.0",
            "http://0.0.0.0:80",
            "http://[::1]",
            "http://[::1]:8080",
            "http://[0:0:0:0:0:0:0:1]",
            "http://[::]",
            "http://[::ffff:127.0.0.1]",
            "http://[::ffff:7f00:1]",
            "http://[::ffff:10.0.0.1]",
            "http://[::ffff:169.254.169.254]",
            "http://[::ffff:192.168.1.1]",
            "http://[::ffff:100.64.0.1]",
            "http://[fe80::1]",
            "http://[fe80::dead:beef]",
            "http://[fc00::1]",
            "http://[fd00::1]",
            "http://[fd12:3456:789a::1]",
            "http://10.0.0.1",
            "http://10.255.255.255",
            "http://172.16.0.1",
            "http://172.31.255.255",
            "http://192.168.0.1",
            "http://192.168.255.255",
            "http://169.254.169.254",
            "http://169.254.1.1",
            "http://100.64.0.1",
            "http://100.127.255.255"
    })
    void validateWebhookUrl_rejectsKnownBlockedTargets(String targetUrl) {
        WebhookNotifier notifier = new WebhookNotifier();
        assertThatThrownBy(() -> notifier.validateWebhookUrl(targetUrl))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "ftp://127.0.0.1/hook",
            "file:///etc/passwd",
            "gopher://127.0.0.1:70",
            "dict://127.0.0.1:11211",
            "ldap://127.0.0.1:389",
            "javascript:alert(1)",
            "data:text/html,<html>",
            "mailto:admin@example.com",
            "//127.0.0.1/hook",
            "not-a-url"
    })
    void validateWebhookUrl_rejectsNonHttpSchemesAndInvalidUrls(String badUrl) {
        WebhookNotifier notifier = new WebhookNotifier();
        assertThatThrownBy(() -> notifier.validateWebhookUrl(badUrl))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "2130706433",       // 127.0.0.1 in decimal
            "0x7f000001",       // 127.0.0.1 in hex (fails resolution)
            "127.1",            // 127.0.0.1 shorthand
            "0x7f.1",           // 127.0.0.1 hex shorthand (fails resolution)
            "2852039166",       // 169.254.169.254 in decimal
            "3232235521",       // 192.168.0.1 in decimal
            "2886729729",       // 172.16.0.1 in decimal
            "167772161"         // 10.0.0.1 in decimal
    })
    void validateWebhookUrl_withEncodedIpVariants_blocksEitherViaResolutionOrSsrfCheck(String encodedHost) {
        WebhookNotifier notifier = new WebhookNotifier();
        String url = "http://" + encodedHost + "/webhook";
        assertThatThrownBy(() -> notifier.validateWebhookUrl(url))
                .as("URL %s must be rejected", url)
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void validateWebhookUrl_octalDottedDecimal_resolvesAsPublicIpInJava() throws Exception {
        // Java's InetAddress treats "0177.0.0.1" as decimal 177.0.0.1 (not octal 127.0.0.1).
        // 177.0.0.1 is a public IP in AS4788, not loopback.
        InetAddress addr = InetAddress.getByName("0177.0.0.1");
        assertThat(addr.getHostAddress()).isEqualTo("177.0.0.1");
        assertThat(WebhookNotifier.isBlockedIp(addr)).isFalse();
    }

    @Test
    void isBlockedIp_and_validateWebhookUrl_ipv4CompatibleIpv6_CRITICAL_BYPASS() throws Exception {
        // RFC 4291 section 2.5.5.1: IPv4-compatible IPv6 address format ::w.x.y.z
        // First 12 bytes are 0x00; bytes 12..15 are the IPv4 address.
        byte[] bytesLoopback = new byte[16];
        bytesLoopback[12] = 127;
        bytesLoopback[15] = 1;
        InetAddress compat127 = InetAddress.getByAddress(bytesLoopback);

        byte[] bytesMetadata = new byte[16];
        bytesMetadata[12] = (byte) 169;
        bytesMetadata[13] = (byte) 254;
        bytesMetadata[14] = (byte) 169;
        bytesMetadata[15] = (byte) 254;
        InetAddress compatMetadata = InetAddress.getByAddress(bytesMetadata);

        // EMPIRICAL PROOF OF VULNERABILITY:
        // isBlockedIp returns FALSE for both loopback and metadata addresses:
        boolean loopbackBlocked = WebhookNotifier.isBlockedIp(compat127);
        boolean metadataBlocked = WebhookNotifier.isBlockedIp(compatMetadata);

        System.out.println("VULNERABILITY CONFIRMED: isBlockedIp(::127.0.0.1) = " + loopbackBlocked);
        System.out.println("VULNERABILITY CONFIRMED: isBlockedIp(::169.254.169.254) = " + metadataBlocked);

        assertThat(loopbackBlocked)
                .as("IPv4-compatible loopback ::127.0.0.1 must be blocked")
                .isTrue();

        assertThat(metadataBlocked)
                .as("IPv4-compatible metadata ::169.254.169.254 must be blocked")
                .isTrue();

        WebhookNotifier notifier = new WebhookNotifier();
        assertThatThrownBy(() -> notifier.validateWebhookUrl("http://[::127.0.0.1]/hook"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("interne Netzwerkadressen ist untersagt");

        assertThatThrownBy(() -> notifier.validateWebhookUrl("http://[::169.254.169.254]/latest/meta-data"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("interne Netzwerkadressen ist untersagt");
    }

    @ParameterizedTest
    @ValueSource(ints = {400, 401, 403, 404, 500, 502, 503, 504})
    void sendTestNotification_neverReflectsResponseBodyOnAnyErrorStatus(int status) throws Exception {
        String sensitivePayload = "{\"secret_token\":\"SUPER_CONFIDENTIAL_KEY_12345\",\"db_dump\":\"root:password\"}";
        HttpClient client = mock(HttpClient.class);
        @SuppressWarnings("unchecked")
        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(status);
        when(response.body()).thenReturn(sensitivePayload);
        when(client.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(response);

        WebhookNotifier notifier = new WebhookNotifier(client);
        WebhookResult result = notifier.sendTestNotification("https://hooks.slack.com/services/T0/B0/X0");

        assertThat(result.success()).isFalse();
        assertThat(result.message()).contains(String.valueOf(status));
        assertThat(result.message()).doesNotContain("secret_token");
        assertThat(result.message()).doesNotContain("SUPER_CONFIDENTIAL_KEY_12345");
        assertThat(result.message()).doesNotContain("db_dump");
        assertThat(result.message()).doesNotContain("root:password");
        assertThat(result.message()).doesNotContain("{");
    }

    @Test
    void postPayloadAsync_neverReflectsResponseBodyOnErrorStatus() throws Exception {
        String sensitivePayload = "{\"secret_token\":\"ASYNC_CONFIDENTIAL_KEY\",\"aws_creds\":\"AKIAIOSFODNN7EXAMPLE\"}";
        HttpClient client = mock(HttpClient.class);
        @SuppressWarnings("unchecked")
        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(500);
        when(response.body()).thenReturn(sensitivePayload);
        when(client.sendAsync(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(CompletableFuture.completedFuture(response));

        WebhookNotifier notifier = new WebhookNotifier(client);
        WebhookResult result = notifier.postPayloadAsync("https://hooks.slack.com/services/T0/B0/X0", "{}").join();

        assertThat(result.success()).isFalse();
        assertThat(result.message()).contains("500");
        assertThat(result.message()).doesNotContain("ASYNC_CONFIDENTIAL_KEY");
        assertThat(result.message()).doesNotContain("AKIAIOSFODNN7EXAMPLE");
    }

    @Test
    void sendDigestNotification_neverReflectsResponseBodyOnError() throws Exception {
        String sensitivePayload = "STACK TRACE: org.apache.catalina.connector.ResponseFacade: password=leaked";
        HttpClient client = mock(HttpClient.class);
        @SuppressWarnings("unchecked")
        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(502);
        when(response.body()).thenReturn(sensitivePayload);
        when(client.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(response);

        WebhookNotifier notifier = new WebhookNotifier(client);
        Digest digest = new Digest(RunScope.PULSE, Instant.now(), List.of());
        WebhookResult result = notifier.sendDigestNotification(digest, "https://hooks.slack.com/services/T0/B0/X0", "https://wth.local");

        assertThat(result.success()).isFalse();
        assertThat(result.message()).contains("502");
        assertThat(result.message()).doesNotContain("STACK TRACE");
        assertThat(result.message()).doesNotContain("password=leaked");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "http://[::127.0.0.1]/hook",
            "http://[::127.0.0.2]:8080/hook",
            "http://[::169.254.169.254]/latest/meta-data",
            "http://[::169.254.1.1]/hook",
            "http://[::10.0.0.1]/hook",
            "http://[::172.16.0.1]/hook",
            "http://[::192.168.1.1]/hook",
            "http://[::100.64.0.1]/hook",
            "http://[::0.0.0.0]/hook",
            "http://[::7f00:1]/hook",
            "http://[::a9fe:a9fe]/hook",
            "http://[::224.0.0.1]/hook",
            "http://[::255.255.255.255]/hook"
    })
    void validateWebhookUrl_strictlyBlocks_ipv4CompatibleIpv6(String url) {
        WebhookNotifier notifier = new WebhookNotifier();
        assertThatThrownBy(() -> notifier.validateWebhookUrl(url))
                .as("IPv4-compatible IPv6 URL %s must be blocked", url)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("interne Netzwerkadressen ist untersagt");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "http://[2002:7f00:1::]/hook",
            "http://[2002:a9fe:a9fe::]/hook",
            "http://[2002:0a00:1::]/hook",
            "http://[2002:ac10:1::]/hook",
            "http://[2002:c0a8:101::]/hook",
            "http://[2002:6440:1::]/hook",
            "http://[2002:e000:1::]/hook",
            "http://[2002:f000:1::]/hook",
            "http://[2002:c058:6301::]/hook"
    })
    void validateWebhookUrl_strictlyBlocks_6to4(String url) {
        WebhookNotifier notifier = new WebhookNotifier();
        assertThatThrownBy(() -> notifier.validateWebhookUrl(url))
                .as("6to4 URL %s must be blocked", url)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("interne Netzwerkadressen ist untersagt");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "http://[64:ff9b::127.0.0.1]/hook",
            "http://[64:ff9b::169.254.169.254]/hook",
            "http://[64:ff9b::10.0.0.1]/hook",
            "http://[64:ff9b::172.16.0.1]/hook",
            "http://[64:ff9b::192.168.1.1]/hook",
            "http://[64:ff9b::100.64.0.1]/hook",
            "http://[64:ff9b::224.0.0.1]/hook",
            "http://[64:ff9b:1::1]/hook",
            "http://[64:ff9b:1::ffff]/hook"
    })
    void validateWebhookUrl_strictlyBlocks_nat64(String url) {
        WebhookNotifier notifier = new WebhookNotifier();
        assertThatThrownBy(() -> notifier.validateWebhookUrl(url))
                .as("NAT64 URL %s must be blocked", url)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("interne Netzwerkadressen ist untersagt");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "http://[2001:0000:4136:e378:8000:63bf:3fff:fdd2]/hook",
            "http://[2001:0:7f00:1::]/hook",
            "http://[2001:0:a9fe:a9fe::]/hook",
            "http://[2001:0000:0a00:0001:0000:0000:0000:0001]/hook"
    })
    void validateWebhookUrl_strictlyBlocks_teredo(String url) {
        WebhookNotifier notifier = new WebhookNotifier();
        assertThatThrownBy(() -> notifier.validateWebhookUrl(url))
                .as("Teredo URL %s must be blocked", url)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("interne Netzwerkadressen ist untersagt");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "http://224.0.0.1/hook",
            "http://224.0.0.251/hook",
            "http://239.255.255.250/hook",
            "http://240.0.0.1/hook",
            "http://255.255.255.255/hook",
            "http://[ff01::1]/hook",
            "http://[ff02::1]/hook",
            "http://[ff02::2]/hook",
            "http://[ff02::fb]/hook",
            "http://[ff05::2]/hook",
            "http://[ff0e::1]/hook"
    })
    void validateWebhookUrl_strictlyBlocks_multicastAndBroadcast(String url) {
        WebhookNotifier notifier = new WebhookNotifier();
        assertThatThrownBy(() -> notifier.validateWebhookUrl(url))
                .as("Multicast/Broadcast URL %s must be blocked", url)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("interne Netzwerkadressen ist untersagt");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "http://93.184.216.34/hook",
            "https://[2606:4700:4700::1111]/hook",
            "http://8.8.8.8/hook",
            "http://1.1.1.1/hook",
            "https://[2001:4860:4860::8888]/hook",
            "https://[2a00:1450:4001:830::200e]/hook",
            "http://11.0.0.1/hook",
            "http://172.15.255.255/hook",
            "http://172.32.0.1/hook",
            "http://192.167.1.1/hook",
            "http://192.169.1.1/hook",
            "http://100.63.255.255/hook",
            "http://100.128.0.1/hook",
            "http://[::ffff:93.184.216.34]/hook",
            "http://[2002:5db8:d822::]/hook",
            "http://[64:ff9b::93.184.216.34]/hook"
    })
    void validateWebhookUrl_allowsLegitimatePublicIps(String url) {
        WebhookNotifier notifier = new WebhookNotifier();
        assertDoesNotThrow(() -> notifier.validateWebhookUrl(url),
                () -> "Legitimate public IP URL " + url + " must be permitted");
    }
}
