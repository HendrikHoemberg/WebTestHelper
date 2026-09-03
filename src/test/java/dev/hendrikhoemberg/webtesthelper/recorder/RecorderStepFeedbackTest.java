package dev.hendrikhoemberg.webtesthelper.recorder;

import dev.hendrikhoemberg.webtesthelper.recorder.CapturedEvent.EventKind;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class RecorderStepFeedbackTest {

    @Test
    void intentCapture_notifiesRegisteredListener_whenEventRecorded() {
        IntentCapture capture = IntentCapture.createForTesting();
        AtomicReference<CapturedEvent> received = new AtomicReference<>();
        capture.addListener(received::set);

        CapturedEvent event = new CapturedEvent(
                EventKind.CLICK, "button", "btn-sub", null, "button", "Absenden", null, "Absenden", null, "button#btn-sub"
        );
        capture.recordForTesting(event);

        assertThat(received.get()).isNotNull();
        assertThat(received.get().accessibleName()).isEqualTo("Absenden");
    }

    @Test
    void recorderSocketHandler_broadcastsStepCapturedMessage_onIntentEvent() throws Exception {
        ScreencastBridge bridge = mock(ScreencastBridge.class);
        RecorderSocketHandler handler = new RecorderSocketHandler(bridge);

        IntentCapture capture = IntentCapture.createForTesting();
        RecordingSession session = mock(RecordingSession.class);
        when(session.intentCapture()).thenReturn(capture);
        when(session.isClosed()).thenReturn(false);

        WebSocketSession ws = mock(WebSocketSession.class);
        when(ws.getId()).thenReturn("ws-1");
        when(ws.isOpen()).thenReturn(true);
        when(ws.getAttributes()).thenReturn(Map.of(RecorderHandshakeInterceptor.RECORDING_SESSION_ATTR, session));

        handler.afterConnectionEstablished(ws);

        CapturedEvent event = new CapturedEvent(
                EventKind.CLICK, "button", "btn-login", null, "button", "Anmelden", null, "Anmelden", null, "button#btn-login"
        );
        capture.recordForTesting(event);

        ArgumentCaptor<TextMessage> captor = ArgumentCaptor.forClass(TextMessage.class);
        verify(ws, atLeastOnce()).sendMessage(captor.capture());

        TextMessage msg = captor.getValue();
        assertThat(msg.getPayload()).contains("step_captured");
        assertThat(msg.getPayload()).contains("Anmelden");
    }
}
