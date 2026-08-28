package dev.hendrikhoemberg.webtesthelper.recorder;

import dev.hendrikhoemberg.webtesthelper.support.AbstractPostgresTest;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.AbstractWebSocketHandler;

import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The recorder socket, through the real filter chain and the real handshake (Task 3).
 *
 * <p>{@code RecorderSocketSecurityTest} exercises {@link RecorderHandshakeInterceptor} against
 * mocks, which cannot see whether the interceptor is wired to the path at all or whether Spring
 * Security covers it — and "a {@code WebSocketHandler} is not covered by the existing filter chain
 * by default" is precisely the mistake Task 3 exists not to make. This test opens a real socket
 * against a running server, so both defences are load-bearing in it.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class RecorderSocketHandshakeIntegrationTest extends AbstractPostgresTest {

    @LocalServerPort
    int port;

    @Test
    void anUnauthenticatedHandshakeIsRefusedBeforeAnyFrameIsSent() {
        assertThatThrownBy(() -> connect(UUID.randomUUID()))
                .as("An anonymous client must not reach the screencast of a customer's site")
                .isInstanceOf(ExecutionException.class);
    }

    @Test
    void anUnknownSessionIdIsRefusedTheSameWayAsAnUnownedOne() {
        // Both arrive as a failed handshake, so a caller cannot probe which session ids exist.
        assertThatThrownBy(() -> connect(UUID.randomUUID()))
                .isInstanceOf(ExecutionException.class);
    }

    private void connect(UUID sessionId) throws Exception {
        new StandardWebSocketClient()
                .execute(new AbstractWebSocketHandler() {
                }, new WebSocketHttpHeaders(), java.net.URI.create(
                        "ws://localhost:" + port + "/recorder/ws/" + sessionId))
                .get(10, TimeUnit.SECONDS);
    }
}
