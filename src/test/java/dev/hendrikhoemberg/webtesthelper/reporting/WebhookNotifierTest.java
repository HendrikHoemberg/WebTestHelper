package dev.hendrikhoemberg.webtesthelper.reporting;

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
}
