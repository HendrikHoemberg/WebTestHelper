package dev.hendrikhoemberg.webtesthelper.recorder;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Plain WebSocketHandler for streaming live recorder frames and receiving user input (§10.1, D109).
 *
 * <p>Carries screencast frames to the browser and input events back to the session worker.
 * Handshake and session authorization are handled by {@link RecorderHandshakeInterceptor}.
 */
@Component
public class RecorderSocketHandler extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(RecorderSocketHandler.class);

    private final Map<String, WebSocketSession> openSockets = new ConcurrentHashMap<>();

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        openSockets.put(session.getId(), session);
        RecordingSession recordingSession = (RecordingSession) session.getAttributes()
                .get(RecorderHandshakeInterceptor.RECORDING_SESSION_ATTR);
        log.info("WebSocket-Verbindung etabliert: session={}, recordingSession={}",
                session.getId(), recordingSession != null ? recordingSession.sessionId() : null);
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) throws Exception {
        openSockets.remove(session.getId());
        RecordingSession recordingSession = (RecordingSession) session.getAttributes()
                .get(RecorderHandshakeInterceptor.RECORDING_SESSION_ATTR);
        log.info("WebSocket-Verbindung geschlossen: session={}, status={}, recordingSession={}",
                session.getId(), status, recordingSession != null ? recordingSession.sessionId() : null);
    }

    public int activeSocketCount() {
        return openSockets.size();
    }
}
