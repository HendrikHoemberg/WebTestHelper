package dev.hendrikhoemberg.webtesthelper.recorder;

import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistration;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

import java.net.URI;
import java.security.Principal;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RecorderSocketSecurityTest {

    private RecordingSessionRegistry registry;
    private RecorderHandshakeInterceptor interceptor;
    private RecorderSocketHandler socketHandler;
    private WebSocketHandler wsHandler;

    @BeforeEach
    void setup() {
        registry = mock(RecordingSessionRegistry.class);
        interceptor = new RecorderHandshakeInterceptor(registry);
        socketHandler = new RecorderSocketHandler();
        wsHandler = mock(WebSocketHandler.class);
    }

    @Test
    void unauthenticatedHandshakeIsRejectedAtHandshake() throws Exception {
        UUID sessionId = UUID.randomUUID();
        ServerHttpRequest request = mock(ServerHttpRequest.class);
        ServerHttpResponse response = mock(ServerHttpResponse.class);
        when(request.getURI()).thenReturn(URI.create("http://localhost/recorder/ws/" + sessionId));
        when(request.getPrincipal()).thenReturn(null);

        Map<String, Object> attributes = new HashMap<>();
        boolean accepted = interceptor.beforeHandshake(request, response, wsHandler, attributes);

        assertThat(accepted).isFalse();
        verify(response).setStatusCode(HttpStatus.UNAUTHORIZED);
        assertThat(attributes).isEmpty();
    }

    @Test
    void authenticatedUserNotOwningSessionIsRejected() throws Exception {
        UUID sessionId = UUID.randomUUID();
        Principal bob = () -> "bob";
        ServerHttpRequest request = mock(ServerHttpRequest.class);
        ServerHttpResponse response = mock(ServerHttpResponse.class);
        when(request.getURI()).thenReturn(URI.create("http://localhost/recorder/ws/" + sessionId));
        when(request.getPrincipal()).thenReturn(bob);

        // Registry returns empty when bob is not owner
        when(registry.find(sessionId, "bob")).thenReturn(Optional.empty());

        Map<String, Object> attributes = new HashMap<>();
        boolean accepted = interceptor.beforeHandshake(request, response, wsHandler, attributes);

        assertThat(accepted).isFalse();
        verify(response).setStatusCode(HttpStatus.FORBIDDEN);
        assertThat(attributes).isEmpty();
    }

    @Test
    void unknownSessionIdIsRejectedWithoutDisclosingExistence() throws Exception {
        UUID unknownSessionId = UUID.randomUUID();
        Principal alice = () -> "alice";
        ServerHttpRequest request = mock(ServerHttpRequest.class);
        ServerHttpResponse response = mock(ServerHttpResponse.class);
        when(request.getURI()).thenReturn(URI.create("http://localhost/recorder/ws/" + unknownSessionId));
        when(request.getPrincipal()).thenReturn(alice);

        when(registry.find(unknownSessionId, "alice")).thenReturn(Optional.empty());

        Map<String, Object> attributes = new HashMap<>();
        boolean accepted = interceptor.beforeHandshake(request, response, wsHandler, attributes);

        assertThat(accepted).isFalse();
        // Returns the same FORBIDDEN status code as non-owner, disclosing nothing
        verify(response).setStatusCode(HttpStatus.FORBIDDEN);
        assertThat(attributes).isEmpty();
    }

    @Test
    void malformedSessionIdIsRejectedWithoutDisclosingExistence() throws Exception {
        Principal alice = () -> "alice";
        ServerHttpRequest request = mock(ServerHttpRequest.class);
        ServerHttpResponse response = mock(ServerHttpResponse.class);
        when(request.getURI()).thenReturn(URI.create("http://localhost/recorder/ws/not-a-valid-uuid"));
        when(request.getPrincipal()).thenReturn(alice);

        Map<String, Object> attributes = new HashMap<>();
        boolean accepted = interceptor.beforeHandshake(request, response, wsHandler, attributes);

        assertThat(accepted).isFalse();
        verify(response).setStatusCode(HttpStatus.FORBIDDEN);
        assertThat(attributes).isEmpty();
    }

    @Test
    void validHandshakeForOwnedSessionIsAcceptedAndSessionStoredInAttributes() throws Exception {
        UUID sessionId = UUID.randomUUID();
        Principal alice = () -> "alice";
        RecordingSession mockSession = mock(RecordingSession.class);
        when(mockSession.sessionId()).thenReturn(sessionId);
        when(mockSession.username()).thenReturn("alice");

        when(registry.find(sessionId, "alice")).thenReturn(Optional.of(mockSession));

        ServerHttpRequest request = mock(ServerHttpRequest.class);
        ServerHttpResponse response = mock(ServerHttpResponse.class);
        when(request.getURI()).thenReturn(URI.create("http://localhost/recorder/ws/" + sessionId));
        when(request.getPrincipal()).thenReturn(alice);

        Map<String, Object> attributes = new HashMap<>();
        boolean accepted = interceptor.beforeHandshake(request, response, wsHandler, attributes);

        assertThat(accepted).isTrue();
        assertThat(attributes.get(RecorderHandshakeInterceptor.RECORDING_SESSION_ATTR)).isSameAs(mockSession);
        assertThat(attributes.get(RecorderHandshakeInterceptor.SESSION_ID_ATTR)).isEqualTo(sessionId);
    }

    @Test
    void sessionIdExtractedFromUriTemplateVariablesAttributeIfPresent() throws Exception {
        UUID sessionId = UUID.randomUUID();
        Principal alice = () -> "alice";
        RecordingSession mockSession = mock(RecordingSession.class);
        when(registry.find(sessionId, "alice")).thenReturn(Optional.of(mockSession));

        HttpServletRequest servletRequest = mock(HttpServletRequest.class);
        when(servletRequest.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE))
                .thenReturn(Map.of("sessionId", sessionId.toString()));

        ServletServerHttpRequest request = mock(ServletServerHttpRequest.class);
        when(request.getServletRequest()).thenReturn(servletRequest);
        when(request.getURI()).thenReturn(URI.create("http://localhost/recorder/ws/" + sessionId));
        when(request.getPrincipal()).thenReturn(alice);

        ServerHttpResponse response = mock(ServerHttpResponse.class);
        Map<String, Object> attributes = new HashMap<>();
        boolean accepted = interceptor.beforeHandshake(request, response, wsHandler, attributes);

        assertThat(accepted).isTrue();
        assertThat(attributes.get(RecorderHandshakeInterceptor.RECORDING_SESSION_ATTR)).isSameAs(mockSession);
    }

    @Test
    void socketHandlerTracksOpenAndClosedSessions() throws Exception {
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.getId()).thenReturn("ws-test-1");
        RecordingSession recordingSession = mock(RecordingSession.class);
        when(session.getAttributes()).thenReturn(Map.of(
                RecorderHandshakeInterceptor.RECORDING_SESSION_ATTR, recordingSession
        ));

        assertThat(socketHandler.activeSocketCount()).isEqualTo(0);

        socketHandler.afterConnectionEstablished(session);
        assertThat(socketHandler.activeSocketCount()).isEqualTo(1);

        socketHandler.afterConnectionClosed(session, CloseStatus.NORMAL);
        assertThat(socketHandler.activeSocketCount()).isEqualTo(0);
    }

    @Test
    void webSocketConfigRegistersHandlerWithInterceptor() {
        WebSocketHandlerRegistry handlerRegistry = mock(WebSocketHandlerRegistry.class);
        WebSocketHandlerRegistration registration = mock(WebSocketHandlerRegistration.class);
        when(handlerRegistry.addHandler(socketHandler, WebSocketConfig.SOCKET_PATH)).thenReturn(registration);
        when(registration.addInterceptors(interceptor)).thenReturn(registration);

        WebSocketConfig config = new WebSocketConfig(socketHandler, interceptor);
        config.registerWebSocketHandlers(handlerRegistry);

        verify(handlerRegistry).addHandler(socketHandler, WebSocketConfig.SOCKET_PATH);
        verify(registration).addInterceptors(interceptor);
    }
}
