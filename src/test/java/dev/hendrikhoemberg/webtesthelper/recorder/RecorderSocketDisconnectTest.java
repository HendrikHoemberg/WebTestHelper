package dev.hendrikhoemberg.webtesthelper.recorder;

import org.junit.jupiter.api.Test;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;

import java.time.Clock;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * A closed browser tab must not hold a recorder slot until the 15-minute idle timeout: the socket
 * handler marks the session disconnected so the registry can reap it after a short grace period.
 */
class RecorderSocketDisconnectTest {

    @Test
    void closingTheSocketMarksTheRecordingSessionDisconnected() throws Exception {
        RecordingSession recording = new RecordingSession(UUID.randomUUID(), 1L, "https://example.com/",
                "alice", null, null, null, Clock.systemUTC());
        WebSocketSession socket = mock(WebSocketSession.class);
        when(socket.getId()).thenReturn("s1");
        when(socket.getAttributes()).thenReturn(new HashMap<>(
                Map.of(RecorderHandshakeInterceptor.RECORDING_SESSION_ATTR, recording)));

        new RecorderSocketHandler(null).afterConnectionClosed(socket, CloseStatus.GOING_AWAY);

        assertThat(recording.disconnectedAt()).isPresent();
    }
}
