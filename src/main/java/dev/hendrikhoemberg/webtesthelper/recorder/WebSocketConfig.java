package dev.hendrikhoemberg.webtesthelper.recorder;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

import java.util.Objects;

/**
 * WebSocket endpoint registration for the recorder (§10.1, D109).
 *
 * <p>Registers a plain {@link RecorderSocketHandler} at {@value #SOCKET_PATH} guarded by
 * {@link RecorderHandshakeInterceptor}.
 */
@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    public static final String SOCKET_PATH = "/recorder/ws/{sessionId}";

    private final RecorderSocketHandler socketHandler;
    private final RecorderHandshakeInterceptor handshakeInterceptor;

    public WebSocketConfig(RecorderSocketHandler socketHandler, RecorderHandshakeInterceptor handshakeInterceptor) {
        this.socketHandler = Objects.requireNonNull(socketHandler, "socketHandler must not be null");
        this.handshakeInterceptor = Objects.requireNonNull(handshakeInterceptor, "handshakeInterceptor must not be null");
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(socketHandler, SOCKET_PATH)
                .addInterceptors(handshakeInterceptor);
    }
}
