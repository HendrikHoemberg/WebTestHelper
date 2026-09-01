package dev.hendrikhoemberg.webtesthelper.recorder;

import com.google.gson.JsonObject;
import dev.hendrikhoemberg.webtesthelper.support.FixtureSite;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@Tag("browser")
@org.junit.jupiter.api.parallel.ResourceLock("browser")
class RecorderInputTest {

    private static FixtureSite fixtureSite;
    private static RecorderPool pool;
    private static RecordingSession session;
    private static ScreencastBridge bridge;
    private static RecorderSocketHandler socketHandler;
    private static RecorderProperties properties;

    @BeforeAll
    static void start() {
        fixtureSite = FixtureSite.start();
        properties = new RecorderProperties(1, Duration.ofMinutes(15), 60, 1280, 720, true, false, Duration.ofMillis(100));
        pool = new RecorderPool(properties);
        RecorderWorker worker = pool.allocate().orElseThrow();
        var bsc = worker.submit(browser -> {
            var context = browser.newContext(new com.microsoft.playwright.Browser.NewContextOptions()
                    .setViewportSize(properties.viewportWidth(), properties.viewportHeight()));
            var page = context.newPage();
            page.navigate(fixtureSite.url("reise/start.html"));
            return new SessionContext(context, page);
        });
        session = new RecordingSession(
                UUID.randomUUID(), 1L, fixtureSite.url("reise/start.html"), "alice",
                worker, bsc.context(), bsc.page(), Clock.systemUTC());
        bridge = new ScreencastBridge(properties);
        socketHandler = new RecorderSocketHandler(bridge);
    }

    @AfterAll
    static void stop() {
        if (bridge != null && session != null) {
            bridge.detach(session);
        }
        if (session != null) {
            session.close();
        }
        if (pool != null) {
            pool.close();
        }
        if (fixtureSite != null) {
            fixtureSite.close();
        }
    }

    @Test
    void canvasClickOnAScrolledPageLandsOnTheElementUnderTheCursor() throws Exception {
        session.worker().submit(browser -> {
            session.page().navigate(fixtureSite.url("reise/lang.html"));
            session.page().evaluate("window.scrollTo(0, 1500)");
            session.page().waitForFunction("window.scrollY === 1500");
            return null;
        });

        java.util.concurrent.BlockingQueue<ScreencastFrame> frames = new java.util.concurrent.LinkedBlockingQueue<>();
        bridge.attach(session, frames::add);
        WebSocketSession wsSession = createMockWebSocketSession("ws-scrolled-click");
        try {
            ScreencastFrame frame = frames.poll(5, java.util.concurrent.TimeUnit.SECONDS);
            assertThat(frame).as("A frame arrives so the client has metadata to send back").isNotNull();

            var box = session.worker().submit(browser ->
                    session.page().locator("#reise-tief-link").boundingBox());
            assertThat(box).as("Link is inside the viewport after scrolling").isNotNull();
            assertThat(box.y).isBetween(0.0, 720.0);

            // The client renders the 1280x720 frame into a 640x360 canvas and reports the
            // geometry it was given by the frame metadata, exactly as record.html does.
            double scaleFactor = 640.0 / 1280.0;
            CanvasGeometry geometry = new CanvasGeometry(640, 360, frame.metadata());
            JsonObject clickMsg = clickMessage(
                    (box.x + 20) * scaleFactor, (box.y + box.height / 2) * scaleFactor, geometry);

            socketHandler.handleTextMessage(wsSession, new TextMessage(clickMsg.toString()));

            session.worker().submit(browser -> {
                session.page().waitForURL("**/reise/ziel.html");
                return null;
            });
            String currentUrl = session.worker().submit(browser -> session.page().url());
            assertThat(currentUrl).contains("reise/ziel.html");
        } finally {
            socketHandler.afterConnectionClosed(wsSession, CloseStatus.NORMAL);
            bridge.detach(session);
        }
    }

    @Test
    void aWheelEventScrollsThePageSoElementsBelowTheFoldCanBeReached() throws Exception {
        session.worker().submit(browser -> {
            session.page().navigate(fixtureSite.url("reise/lang.html"));
            session.page().evaluate("window.scrollTo(0, 0)");
            session.page().waitForFunction("document.body.scrollHeight > 2000");
            return null;
        });

        WebSocketSession wsSession = createMockWebSocketSession("ws-wheel-test");
        try {
            JsonObject wheel = new JsonObject();
            wheel.addProperty("type", "wheel");
            wheel.addProperty("canvasX", 320.0);
            wheel.addProperty("canvasY", 180.0);
            wheel.addProperty("deltaX", 0.0);
            wheel.addProperty("deltaY", 600.0);
            JsonObject g = new JsonObject();
            g.addProperty("canvasWidth", 640);
            g.addProperty("canvasHeight", 360);
            g.addProperty("frameWidth", 1280);
            g.addProperty("frameHeight", 720);
            g.addProperty("pageScaleFactor", 1.0);
            g.addProperty("offsetTop", 0.0);
            wheel.add("geometry", g);

            socketHandler.handleTextMessage(wsSession, new TextMessage(wheel.toString()));

            session.worker().submit(browser -> {
                session.page().waitForFunction("window.scrollY > 100");
                return null;
            });
            Double scrollY = session.worker().submit(browser ->
                    ((Number) session.page().evaluate("window.scrollY")).doubleValue());
            assertThat(scrollY).as("Wheel event scrolled the page down").isGreaterThan(100.0);
        } finally {
            socketHandler.afterConnectionClosed(wsSession, CloseStatus.NORMAL);
        }
    }

    private static JsonObject clickMessage(double canvasX, double canvasY, CanvasGeometry geometry) {
        JsonObject msg = new JsonObject();
        msg.addProperty("type", "click");
        msg.addProperty("canvasX", canvasX);
        msg.addProperty("canvasY", canvasY);
        JsonObject g = new JsonObject();
        g.addProperty("canvasWidth", geometry.canvasWidth());
        g.addProperty("canvasHeight", geometry.canvasHeight());
        g.addProperty("frameWidth", geometry.frameWidth());
        g.addProperty("frameHeight", geometry.frameHeight());
        g.addProperty("pageScaleFactor", geometry.pageScaleFactor());
        g.addProperty("offsetTop", geometry.offsetTop());
        msg.add("geometry", g);
        return msg;
    }

    @Test
    void canvasClickOnStartLinkNavigatesToSchritt2() throws Exception {
        // Reset page to start.html
        session.worker().submit(browser -> {
            session.page().navigate(fixtureSite.url("reise/start.html"));
            return null;
        });

        WebSocketSession wsSession = createMockWebSocketSession("ws-input-test-1");
        socketHandler.afterConnectionEstablished(wsSession);

        try {
            // Get bounding box of #reise-start-link
            var box = session.worker().submit(browser ->
                    session.page().locator("#reise-start-link").boundingBox());
            assertThat(box).isNotNull();

            // Simulate canvas rendered at half size (640x360 for a 1280x720 frame)
            double canvasWidth = 640.0;
            double canvasHeight = 360.0;
            double scaleFactor = 640.0 / 1280.0;

            double targetX = box.x + box.width / 2.0;
            double targetY = box.y + box.height / 2.0;
            double canvasX = targetX * scaleFactor;
            double canvasY = targetY * scaleFactor;

            Instant beforeInput = session.lastActivity();

            // Send click message over websocket
            JsonObject clickMsg = new JsonObject();
            clickMsg.addProperty("type", "click");
            clickMsg.addProperty("canvasX", canvasX);
            clickMsg.addProperty("canvasY", canvasY);
            JsonObject geometry = new JsonObject();
            geometry.addProperty("canvasWidth", (int) canvasWidth);
            geometry.addProperty("canvasHeight", (int) canvasHeight);
            geometry.addProperty("frameWidth", 1280);
            geometry.addProperty("frameHeight", 720);
            geometry.addProperty("pageScaleFactor", 1.0);
            geometry.addProperty("offsetTop", 0.0);
            clickMsg.add("geometry", geometry);

            socketHandler.handleTextMessage(wsSession, new TextMessage(clickMsg.toString()));

            // Wait for navigation
            session.worker().submit(browser -> {
                session.page().waitForURL("**/reise/schritt2.html");
                return null;
            });

            String currentUrl = session.worker().submit(browser -> session.page().url());
            assertThat(currentUrl).contains("reise/schritt2.html");
            assertThat(session.lastActivity()).isAfterOrEqualTo(beforeInput);
        } finally {
            socketHandler.afterConnectionClosed(wsSession, CloseStatus.NORMAL);
        }
    }

    @Test
    void canvasClickAndKeyEventsFillInputAndSubmitForm() throws Exception {
        session.worker().submit(browser -> {
            session.page().navigate(fixtureSite.url("reise/schritt2.html"));
            return null;
        });

        WebSocketSession wsSession = createMockWebSocketSession("ws-input-test-2");
        socketHandler.afterConnectionEstablished(wsSession);

        try {
            // Find #reise-name bounding box
            var box = session.worker().submit(browser ->
                    session.page().locator("#reise-name").boundingBox());
            assertThat(box).isNotNull();

            // Click into #reise-name to focus
            JsonObject clickMsg = new JsonObject();
            clickMsg.addProperty("type", "click");
            clickMsg.addProperty("canvasX", box.x + box.width / 2.0);
            clickMsg.addProperty("canvasY", box.y + box.height / 2.0);
            JsonObject geometry = new JsonObject();
            geometry.addProperty("canvasWidth", 1280);
            geometry.addProperty("canvasHeight", 720);
            geometry.addProperty("frameWidth", 1280);
            geometry.addProperty("frameHeight", 720);
            geometry.addProperty("pageScaleFactor", 1.0);
            geometry.addProperty("offsetTop", 0.0);
            clickMsg.add("geometry", geometry);

            socketHandler.handleTextMessage(wsSession, new TextMessage(clickMsg.toString()));

            // Type "Alice" via key events
            for (char ch : "Alice".toCharArray()) {
                JsonObject keyMsg = new JsonObject();
                keyMsg.addProperty("type", "key");
                keyMsg.addProperty("key", String.valueOf(ch));
                keyMsg.addProperty("text", String.valueOf(ch));
                socketHandler.handleTextMessage(wsSession, new TextMessage(keyMsg.toString()));
            }

            // Also fill required email field via keyboard focus/typing
            var emailBox = session.worker().submit(browser ->
                    session.page().locator("input[type=email]").boundingBox());
            assertThat(emailBox).isNotNull();

            JsonObject clickEmail = new JsonObject();
            clickEmail.addProperty("type", "click");
            clickEmail.addProperty("canvasX", emailBox.x + emailBox.width / 2.0);
            clickEmail.addProperty("canvasY", emailBox.y + emailBox.height / 2.0);
            clickEmail.add("geometry", geometry);
            socketHandler.handleTextMessage(wsSession, new TextMessage(clickEmail.toString()));

            for (char ch : "alice@test.de".toCharArray()) {
                JsonObject keyMsg = new JsonObject();
                keyMsg.addProperty("type", "key");
                keyMsg.addProperty("key", String.valueOf(ch));
                keyMsg.addProperty("text", String.valueOf(ch));
                socketHandler.handleTextMessage(wsSession, new TextMessage(keyMsg.toString()));
            }

            // Click submit button
            var submitBox = session.worker().submit(browser ->
                    session.page().locator("#reise-submit").boundingBox());
            assertThat(submitBox).isNotNull();

            JsonObject clickSubmit = new JsonObject();
            clickSubmit.addProperty("type", "click");
            clickSubmit.addProperty("canvasX", submitBox.x + submitBox.width / 2.0);
            clickSubmit.addProperty("canvasY", submitBox.y + submitBox.height / 2.0);
            clickSubmit.add("geometry", geometry);
            socketHandler.handleTextMessage(wsSession, new TextMessage(clickSubmit.toString()));

            // Verify navigation to ziel.html
            session.worker().submit(browser -> {
                session.page().waitForURL("**/reise/ziel.html**");
                return null;
            });

            String finalUrl = session.worker().submit(browser -> session.page().url());
            assertThat(finalUrl).contains("reise/ziel.html");
        } finally {
            socketHandler.afterConnectionClosed(wsSession, CloseStatus.NORMAL);
        }
    }

    private WebSocketSession createMockWebSocketSession(String id) {
        WebSocketSession wsSession = mock(WebSocketSession.class);
        when(wsSession.getId()).thenReturn(id);
        when(wsSession.isOpen()).thenReturn(true);
        Map<String, Object> attrs = new HashMap<>();
        attrs.put(RecorderHandshakeInterceptor.RECORDING_SESSION_ATTR, session);
        when(wsSession.getAttributes()).thenReturn(attrs);
        return wsSession;
    }

    private record SessionContext(com.microsoft.playwright.BrowserContext context, com.microsoft.playwright.Page page) {}
}
