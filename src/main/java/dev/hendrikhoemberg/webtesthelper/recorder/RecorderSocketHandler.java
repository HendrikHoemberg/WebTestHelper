package dev.hendrikhoemberg.webtesthelper.recorder;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.microsoft.playwright.CDPSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * WebSocket handler streaming screencast frames and forwarding user input events (§10.1, D109, D110).
 *
 * <p>On connection open, attaches the recording session to {@link ScreencastBridge} so live visual frames
 * flow to the client. On connection close, cleanly detaches screencasting.
 *
 * <p>Incoming JSON messages carry mouse clicks and keystrokes. Coordinates are translated from canvas space
 * to viewport space via {@link InputTranslator} and dispatched using CDP {@code Input.dispatchMouseEvent}
 * and {@code Input.dispatchKeyEvent} on the dedicated single-thread worker. Every input updates the session's
 * activity timestamp.
 */
@Component
public class RecorderSocketHandler extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(RecorderSocketHandler.class);

    private final ScreencastBridge screencastBridge;
    private final Map<String, WebSocketSession> openSockets = new ConcurrentHashMap<>();

    @Autowired
    public RecorderSocketHandler(ScreencastBridge screencastBridge) {
        this.screencastBridge = screencastBridge;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        openSockets.put(session.getId(), session);
        RecordingSession recordingSession = (RecordingSession) session.getAttributes()
                .get(RecorderHandshakeInterceptor.RECORDING_SESSION_ATTR);
        log.info("WebSocket-Verbindung etabliert: session={}, recordingSession={}",
                session.getId(), recordingSession != null ? recordingSession.sessionId() : null);

        if (recordingSession != null && !recordingSession.isClosed()) {
            if (screencastBridge != null) {
                screencastBridge.attach(recordingSession, frame -> {
                    try {
                        if (session.isOpen()) {
                            JsonObject msg = new JsonObject();
                            msg.addProperty("type", "frame");
                            msg.addProperty("data", frame.data());
                            if (frame.metadata() != null) {
                                JsonObject meta = new JsonObject();
                                meta.addProperty("offsetTop", frame.metadata().offsetTop());
                                meta.addProperty("pageScaleFactor", frame.metadata().pageScaleFactor());
                                meta.addProperty("deviceWidth", frame.metadata().deviceWidth());
                                meta.addProperty("deviceHeight", frame.metadata().deviceHeight());
                                meta.addProperty("scrollOffsetX", frame.metadata().scrollOffsetX());
                                meta.addProperty("scrollOffsetY", frame.metadata().scrollOffsetY());
                                meta.addProperty("timestamp", frame.metadata().timestamp());
                                msg.add("metadata", meta);
                            }
                            synchronized (session) {
                                if (session.isOpen()) {
                                    session.sendMessage(new TextMessage(msg.toString()));
                                }
                            }
                        }
                    } catch (Exception e) {
                        log.warn("Fehler beim Senden des Screencast-Frames über WebSocket {}: {}", session.getId(), e.getMessage());
                    }
                });
            }

            if (recordingSession.intentCapture() != null) {
                recordingSession.intentCapture().addListener(event -> {
                    try {
                        if (session.isOpen()) {
                            JsonObject msg = new JsonObject();
                            msg.addProperty("type", "step_captured");
                            msg.addProperty("kind", event.kind().name());
                            msg.addProperty("description", formatStepDescription(event));
                            synchronized (session) {
                                if (session.isOpen()) {
                                    session.sendMessage(new TextMessage(msg.toString()));
                                }
                            }
                        }
                    } catch (Exception e) {
                        log.warn("Fehler beim Senden des Schritt-Feedbacks über WebSocket {}: {}", session.getId(), e.getMessage());
                    }
                });
            }
        }
    }

    static String formatStepDescription(CapturedEvent event) {
        if (event == null) {
            return "Schritt ausgeführt";
        }
        String label = null;
        if (event.accessibleName() != null && !event.accessibleName().isBlank()) {
            label = event.accessibleName().trim();
        } else if (event.labelText() != null && !event.labelText().isBlank()) {
            label = event.labelText().trim();
        } else if (event.textContent() != null && !event.textContent().isBlank() && event.textContent().trim().length() <= 30) {
            label = event.textContent().trim();
        } else if (event.id() != null && !event.id().isBlank()) {
            label = "#" + event.id().trim();
        }

        return switch (event.kind()) {
            case CLICK -> label != null ? "Klick auf „" + label + "“" : "Element angeklickt";
            case INPUT, CHANGE -> {
                if (label != null) {
                    yield "Eingabe in „" + label + "“";
                }
                String val = event.value() != null ? event.value() : "";
                yield val.isBlank() ? "Textfeld bearbeitet" : "Texteingabe: " + val;
            }
            case SUBMIT -> label != null ? "Formular „" + label + "“ abgesendet" : "Formular abgesendet";
        };
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) throws Exception {
        openSockets.remove(session.getId());
        RecordingSession recordingSession = (RecordingSession) session.getAttributes()
                .get(RecorderHandshakeInterceptor.RECORDING_SESSION_ATTR);
        log.info("WebSocket-Verbindung geschlossen: session={}, status={}, recordingSession={}",
                session.getId(), status, recordingSession != null ? recordingSession.sessionId() : null);

        if (recordingSession != null && screencastBridge != null) {
            screencastBridge.detach(recordingSession);
        }
    }

    @Override
    public void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        RecordingSession recordingSession = (RecordingSession) session.getAttributes()
                .get(RecorderHandshakeInterceptor.RECORDING_SESSION_ATTR);
        if (recordingSession == null || recordingSession.isClosed()) {
            log.warn("Eingabe für nicht vorhandene oder geschlossene Sitzung ignoriert (session={})", session.getId());
            return;
        }

        // Stamping activity on every user input
        recordingSession.recordActivity();

        String payload = message.getPayload();
        if (payload == null || payload.isBlank()) {
            return;
        }

        try {
            JsonObject json = JsonParser.parseString(payload).getAsJsonObject();
            String type = json.has("type") ? json.get("type").getAsString() : "";

            switch (type) {
                case "click", "mouseClick" -> handleClick(recordingSession, json);
                case "mousePressed", "mouseReleased", "mouseMoved" -> handleMouseEvent(recordingSession, json, type);
                case "wheel", "scroll" -> handleWheel(recordingSession, json);
                case "key", "press" -> handleKey(recordingSession, json);
                case "keyDown", "keyUp", "rawKeyDown", "char" -> handleKeyEvent(recordingSession, json, type);
                default -> log.debug("WebSocket-Nachricht mit Typ '{}' in Sitzung {} empfangen", type, recordingSession.sessionId());
            }
        } catch (Exception e) {
            log.warn("Fehler bei der Verarbeitung der WebSocket-Eingabe: {}", e.getMessage());
        }
    }

    public int activeSocketCount() {
        return openSockets.size();
    }

    private void handleClick(RecordingSession recordingSession, JsonObject json) {
        double x = json.has("canvasX") ? json.get("canvasX").getAsDouble() : (json.has("x") ? json.get("x").getAsDouble() : 0.0);
        double y = json.has("canvasY") ? json.get("canvasY").getAsDouble() : (json.has("y") ? json.get("y").getAsDouble() : 0.0);
        CanvasGeometry geometry = parseGeometry(json);
        ViewportPoint vp = InputTranslator.toViewport(x, y, geometry);

        String button = json.has("button") ? json.get("button").getAsString() : "left";
        int buttons = getButtonsBitmask(button, true);

        recordingSession.worker().submit(browser -> {
            CDPSession cdp = ensureCdp(recordingSession);
            if (cdp != null) {
                // mousePressed (§10.5 no drag: mousePressed and mouseReleased at point)
                JsonObject press = new JsonObject();
                press.addProperty("type", "mousePressed");
                press.addProperty("x", vp.x());
                press.addProperty("y", vp.y());
                press.addProperty("button", button);
                press.addProperty("buttons", buttons);
                press.addProperty("clickCount", 1);
                if (json.has("modifiers")) {
                    press.addProperty("modifiers", json.get("modifiers").getAsInt());
                }
                cdp.send("Input.dispatchMouseEvent", press);

                // mouseReleased
                JsonObject release = new JsonObject();
                release.addProperty("type", "mouseReleased");
                release.addProperty("x", vp.x());
                release.addProperty("y", vp.y());
                release.addProperty("button", button);
                release.addProperty("buttons", 0);
                release.addProperty("clickCount", 1);
                if (json.has("modifiers")) {
                    release.addProperty("modifiers", json.get("modifiers").getAsInt());
                }
                cdp.send("Input.dispatchMouseEvent", release);
            }
            return null;
        });
    }

    /**
     * Scrolls the page under the cursor (§10.1).
     *
     * <p>§10.5 excludes multiple tabs, uploads, downloads and drag - not scrolling, and without it
     * a recorder can only reach what happens to be above the fold on a 720px viewport.
     */
    private void handleWheel(RecordingSession recordingSession, JsonObject json) {
        double x = json.has("canvasX") ? json.get("canvasX").getAsDouble() : 0.0;
        double y = json.has("canvasY") ? json.get("canvasY").getAsDouble() : 0.0;
        ViewportPoint vp = InputTranslator.toViewport(x, y, parseGeometry(json));

        double deltaX = json.has("deltaX") ? json.get("deltaX").getAsDouble() : 0.0;
        double deltaY = json.has("deltaY") ? json.get("deltaY").getAsDouble() : 0.0;

        recordingSession.worker().submit(browser -> {
            CDPSession cdp = ensureCdp(recordingSession);
            if (cdp != null) {
                JsonObject event = new JsonObject();
                event.addProperty("type", "mouseWheel");
                event.addProperty("x", vp.x());
                event.addProperty("y", vp.y());
                event.addProperty("deltaX", deltaX);
                event.addProperty("deltaY", deltaY);
                event.addProperty("button", "none");
                event.addProperty("buttons", 0);
                if (json.has("modifiers")) {
                    event.addProperty("modifiers", json.get("modifiers").getAsInt());
                }
                cdp.send("Input.dispatchMouseEvent", event);
            }
            return null;
        });
    }

    private void handleMouseEvent(RecordingSession recordingSession, JsonObject json, String eventType) {
        double x = json.has("canvasX") ? json.get("canvasX").getAsDouble() : (json.has("x") ? json.get("x").getAsDouble() : 0.0);
        double y = json.has("canvasY") ? json.get("canvasY").getAsDouble() : (json.has("y") ? json.get("y").getAsDouble() : 0.0);
        CanvasGeometry geometry = parseGeometry(json);
        ViewportPoint vp = InputTranslator.toViewport(x, y, geometry);

        String button = json.has("button") ? json.get("button").getAsString() : "none";
        int buttons = json.has("buttons")
                ? json.get("buttons").getAsInt()
                : getButtonsBitmask(button, "mousePressed".equals(eventType));
        int clickCount = json.has("clickCount") ? json.get("clickCount").getAsInt() : 1;

        recordingSession.worker().submit(browser -> {
            CDPSession cdp = ensureCdp(recordingSession);
            if (cdp != null) {
                JsonObject event = new JsonObject();
                event.addProperty("type", eventType);
                event.addProperty("x", vp.x());
                event.addProperty("y", vp.y());
                event.addProperty("button", button);
                event.addProperty("buttons", buttons);
                event.addProperty("clickCount", clickCount);
                if (json.has("modifiers")) {
                    event.addProperty("modifiers", json.get("modifiers").getAsInt());
                }
                cdp.send("Input.dispatchMouseEvent", event);
            }
            return null;
        });
    }

    private void handleKey(RecordingSession recordingSession, JsonObject json) {
        String key = json.has("key") ? json.get("key").getAsString() : "";
        String text = json.has("text") ? json.get("text").getAsString() : (key.length() == 1 ? key : "");
        String code = json.has("code") ? json.get("code").getAsString() : "";
        int modifiers = json.has("modifiers") ? json.get("modifiers").getAsInt() : 0;
        int windowsVirtualKeyCode = json.has("windowsVirtualKeyCode") ? json.get("windowsVirtualKeyCode").getAsInt() : 0;

        recordingSession.worker().submit(browser -> {
            CDPSession cdp = ensureCdp(recordingSession);
            if (cdp != null) {
                JsonObject down = new JsonObject();
                down.addProperty("type", "rawKeyDown");
                if (!key.isEmpty()) down.addProperty("key", key);
                if (!code.isEmpty()) down.addProperty("code", code);
                if (!text.isEmpty()) {
                    down.addProperty("text", text);
                    down.addProperty("unmodifiedText", text);
                }
                if (modifiers > 0) down.addProperty("modifiers", modifiers);
                if (windowsVirtualKeyCode > 0) down.addProperty("windowsVirtualKeyCode", windowsVirtualKeyCode);
                cdp.send("Input.dispatchKeyEvent", down);

                if (!text.isEmpty()) {
                    JsonObject charEvt = new JsonObject();
                    charEvt.addProperty("type", "char");
                    charEvt.addProperty("text", text);
                    charEvt.addProperty("unmodifiedText", text);
                    if (!key.isEmpty()) charEvt.addProperty("key", key);
                    if (!code.isEmpty()) charEvt.addProperty("code", code);
                    if (modifiers > 0) charEvt.addProperty("modifiers", modifiers);
                    cdp.send("Input.dispatchKeyEvent", charEvt);
                }

                JsonObject up = new JsonObject();
                up.addProperty("type", "keyUp");
                if (!key.isEmpty()) up.addProperty("key", key);
                if (!code.isEmpty()) up.addProperty("code", code);
                if (modifiers > 0) up.addProperty("modifiers", modifiers);
                if (windowsVirtualKeyCode > 0) up.addProperty("windowsVirtualKeyCode", windowsVirtualKeyCode);
                cdp.send("Input.dispatchKeyEvent", up);
            }
            return null;
        });
    }

    private void handleKeyEvent(RecordingSession recordingSession, JsonObject json, String eventType) {
        recordingSession.worker().submit(browser -> {
            CDPSession cdp = ensureCdp(recordingSession);
            if (cdp != null) {
                JsonObject event = new JsonObject();
                event.addProperty("type", eventType);
                if (json.has("key")) event.addProperty("key", json.get("key").getAsString());
                if (json.has("code")) event.addProperty("code", json.get("code").getAsString());
                if (json.has("text")) event.addProperty("text", json.get("text").getAsString());
                if (json.has("unmodifiedText")) event.addProperty("unmodifiedText", json.get("unmodifiedText").getAsString());
                if (json.has("modifiers")) event.addProperty("modifiers", json.get("modifiers").getAsInt());
                if (json.has("windowsVirtualKeyCode")) event.addProperty("windowsVirtualKeyCode", json.get("windowsVirtualKeyCode").getAsInt());
                if (json.has("nativeVirtualKeyCode")) event.addProperty("nativeVirtualKeyCode", json.get("nativeVirtualKeyCode").getAsInt());
                if (json.has("autoRepeat")) event.addProperty("autoRepeat", json.get("autoRepeat").getAsBoolean());
                if (json.has("isKeypad")) event.addProperty("isKeypad", json.get("isKeypad").getAsBoolean());
                if (json.has("isSystemKey")) event.addProperty("isSystemKey", json.get("isSystemKey").getAsBoolean());
                cdp.send("Input.dispatchKeyEvent", event);
            }
            return null;
        });
    }

    private CDPSession ensureCdp(RecordingSession recordingSession) {
        CDPSession cdp = recordingSession.cdpSession();
        if (cdp == null && recordingSession.context() != null && recordingSession.page() != null) {
            cdp = recordingSession.context().newCDPSession(recordingSession.page());
            recordingSession.setCdpSession(cdp);
        }
        return cdp;
    }

    private CanvasGeometry parseGeometry(JsonObject json) {
        JsonObject g = json.has("geometry") && json.get("geometry").isJsonObject()
                ? json.getAsJsonObject("geometry")
                : json;

        int canvasWidth = g.has("canvasWidth") ? g.get("canvasWidth").getAsInt() : 1280;
        int canvasHeight = g.has("canvasHeight") ? g.get("canvasHeight").getAsInt() : 720;
        int frameWidth = g.has("frameWidth") ? g.get("frameWidth").getAsInt()
                : (g.has("deviceWidth") ? g.get("deviceWidth").getAsInt() : canvasWidth);
        int frameHeight = g.has("frameHeight") ? g.get("frameHeight").getAsInt()
                : (g.has("deviceHeight") ? g.get("deviceHeight").getAsInt() : canvasHeight);
        double pageScaleFactor = g.has("pageScaleFactor") ? g.get("pageScaleFactor").getAsDouble() : 1.0;
        double offsetTop = g.has("offsetTop") ? g.get("offsetTop").getAsDouble() : 0.0;

        return new CanvasGeometry(canvasWidth, canvasHeight, frameWidth, frameHeight, pageScaleFactor, offsetTop);
    }

    private int getButtonsBitmask(String button, boolean isPressed) {
        if (!isPressed) {
            return 0;
        }
        if (button == null) {
            return 1;
        }
        return switch (button.toLowerCase()) {
            case "right" -> 2;
            case "middle" -> 4;
            case "back" -> 8;
            case "forward" -> 16;
            case "none" -> 0;
            default -> 1;
        };
    }
}
