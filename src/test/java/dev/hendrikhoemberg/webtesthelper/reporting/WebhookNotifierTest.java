package dev.hendrikhoemberg.webtesthelper.reporting;

import dev.hendrikhoemberg.webtesthelper.model.RunScope;
import dev.hendrikhoemberg.webtesthelper.model.RunStatus;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;
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

        SiteDigest site1 = new SiteDigest(1L, "Acme Shop", 101L, dev.hendrikhoemberg.webtesthelper.model.RunStatus.COMPLETED,
                java.time.Instant.now(), null, false, null, null, 1, 0, 0, 0);
        Digest digest = new Digest(dev.hendrikhoemberg.webtesthelper.model.RunScope.PULSE, java.time.Instant.now(), java.util.List.of(site1));

        WebhookNotifier notifier = new WebhookNotifier(client);
        WebhookResult result = notifier.sendDigestNotificationAsync(digest, "https://hooks.slack.com/services/T00/B00/X00", "https://wth.example.com").get();

        assertThat(result.success()).isTrue();
        assertThat(result.message()).contains("200");
    }
}
