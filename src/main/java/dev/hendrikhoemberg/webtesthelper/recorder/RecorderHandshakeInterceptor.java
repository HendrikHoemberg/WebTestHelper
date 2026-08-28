package dev.hendrikhoemberg.webtesthelper.recorder;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

import java.security.Principal;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Interceptor enforcing authentication and session ownership during WebSocket handshake (§10.1, D109).
 *
 * <p>Validates that:
 * <ul>
 *   <li>The incoming handshake request has an authenticated {@link Principal}.</li>
 *   <li>The session ID is a valid UUID and matches an active session owned by the user.</li>
 * </ul>
 * Rejections for non-existent and non-owned sessions are uniform (HTTP 403) to avoid disclosing
 * session existence.
 */
@Component
public class RecorderHandshakeInterceptor implements HandshakeInterceptor {

    private static final Logger log = LoggerFactory.getLogger(RecorderHandshakeInterceptor.class);

    public static final String RECORDING_SESSION_ATTR = "recordingSession";
    public static final String SESSION_ID_ATTR = "sessionId";

    private final RecordingSessionRegistry sessionRegistry;

    public RecorderHandshakeInterceptor(RecordingSessionRegistry sessionRegistry) {
        this.sessionRegistry = Objects.requireNonNull(sessionRegistry, "sessionRegistry must not be null");
    }

    @Override
    public boolean beforeHandshake(ServerHttpRequest request,
                                   ServerHttpResponse response,
                                   WebSocketHandler wsHandler,
                                   Map<String, Object> attributes) throws Exception {
        Principal principal = request.getPrincipal();
        if (principal == null || principal.getName() == null || principal.getName().isBlank()) {
            log.warn("WebSocket-Handshake ohne authentifizierten Benutzer abgewiesen: {}", request.getURI());
            response.setStatusCode(HttpStatus.UNAUTHORIZED);
            return false;
        }

        UUID sessionId = extractSessionId(request);
        if (sessionId == null) {
            log.warn("WebSocket-Handshake mit ungültiger Sitzungs-ID abgewiesen: {}", request.getURI());
            response.setStatusCode(HttpStatus.FORBIDDEN);
            return false;
        }

        String username = principal.getName();
        Optional<RecordingSession> session = sessionRegistry.find(sessionId, username);
        if (session.isEmpty()) {
            log.warn("WebSocket-Handshake für Sitzung {} von Benutzer '{}' abgewiesen (nicht gefunden oder kein Zugriff)",
                    sessionId, username);
            response.setStatusCode(HttpStatus.FORBIDDEN);
            return false;
        }

        attributes.put(RECORDING_SESSION_ATTR, session.get());
        attributes.put(SESSION_ID_ATTR, sessionId);
        log.info("WebSocket-Handshake für Sitzung {} von Benutzer '{}' akzeptiert", sessionId, username);
        return true;
    }

    @Override
    public void afterHandshake(ServerHttpRequest request,
                               ServerHttpResponse response,
                               WebSocketHandler wsHandler,
                               Exception exception) {
        // No-op
    }

    private UUID extractSessionId(ServerHttpRequest request) {
        if (request instanceof ServletServerHttpRequest servletRequest) {
            HttpServletRequest httpRequest = servletRequest.getServletRequest();
            @SuppressWarnings("unchecked")
            Map<String, String> uriVars = (Map<String, String>) httpRequest.getAttribute(
                    HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE);
            if (uriVars != null && uriVars.containsKey("sessionId")) {
                try {
                    return UUID.fromString(uriVars.get("sessionId"));
                } catch (IllegalArgumentException e) {
                    return null;
                }
            }
        }
        String path = request.getURI() != null ? request.getURI().getPath() : null;
        if (path != null) {
            int idx = path.lastIndexOf('/');
            if (idx >= 0 && idx < path.length() - 1) {
                String candidate = path.substring(idx + 1);
                try {
                    return UUID.fromString(candidate);
                } catch (IllegalArgumentException e) {
                    return null;
                }
            }
        }
        return null;
    }
}
