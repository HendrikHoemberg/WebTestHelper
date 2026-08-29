package dev.hendrikhoemberg.webtesthelper.recorder;

import dev.hendrikhoemberg.webtesthelper.support.FixtureSite;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@Tag("browser")
class RecordingSessionAcceptanceTest {

    private static FixtureSite fixtureSite;
    private static RecorderProperties properties;
    private static RecorderPool pool;
    private static ScreencastBridge bridge;

    @BeforeAll
    static void start() {
        fixtureSite = FixtureSite.start();
        properties = new RecorderProperties(2, Duration.ofMinutes(15), 60, 1280, 720, true, false, Duration.ofMillis(100));
        pool = new RecorderPool(properties);
        bridge = new ScreencastBridge(properties);
    }

    @AfterAll
    static void stop() {
        if (pool != null) {
            pool.close();
        }
        if (fixtureSite != null) {
            fixtureSite.close();
        }
    }

    @Test
    void fullLiveSessionFlowNavigatesAndReleasesWorker() throws Exception {
        RecordingSessionRegistry registry = new RecordingSessionRegistry(pool, properties);
        String startUrl = fixtureSite.url("reise/start.html");

        // 1. Opens a recording session on reise/start.html via RecordingSessionRegistry
        RecordingSession session = registry.open(1L, startUrl, "alice");
        UUID sessionId = session.sessionId();
        assertThat(session).isNotNull();
        assertThat(session.worker()).isNotNull();
        assertThat(session.page()).isNotNull();
        assertThat(pool.busy()).isEqualTo(1);

        BlockingQueue<ScreencastFrame> frames = new LinkedBlockingQueue<>();

        try {
            // 2. Attaches a FrameSink via ScreencastBridge, receives on-attach screenshot frame and verifies frame metadata
            bridge.attach(session, frames::add);

            ScreencastFrame initialFrame = frames.poll(5, TimeUnit.SECONDS);
            assertThat(initialFrame).as("On-attach initial frame received").isNotNull();
            assertThat(initialFrame.data()).as("Frame contains image data").isNotBlank();
            assertThat(initialFrame.metadata()).as("Frame metadata present").isNotNull();
            assertThat(initialFrame.metadata().deviceWidth()).isEqualTo(1280);
            assertThat(initialFrame.metadata().deviceHeight()).isEqualTo(720);

            // 3. Dispatches a translated click to the link/button on reise/start.html
            var box = session.worker().submit(browser ->
                    session.page().locator("#reise-start-link").boundingBox());
            assertThat(box).as("Start link bounding box").isNotNull();

            // Simulate canvas rendered at 640x360 for a 1280x720 frame
            double canvasWidth = 640.0;
            double canvasHeight = 360.0;
            double scaleX = canvasWidth / 1280.0;
            double scaleY = canvasHeight / 720.0;

            double targetX = box.x + box.width / 2.0;
            double targetY = box.y + box.height / 2.0;
            double canvasX = targetX * scaleX;
            double canvasY = targetY * scaleY;

            CanvasGeometry geometry = new CanvasGeometry(
                    (int) canvasWidth, (int) canvasHeight, 1280, 720, 1.0, 0.0);
            ViewportPoint viewportPoint = InputTranslator.toViewport(canvasX, canvasY, geometry);

            assertThat(viewportPoint.x()).isCloseTo(targetX, org.assertj.core.data.Offset.offset(0.1));
            assertThat(viewportPoint.y()).isCloseTo(targetY, org.assertj.core.data.Offset.offset(0.1));

            // Execute the click the way a user does: as a socket message the handler translates
            // and dispatches over CDP. Clicking with page.mouse() here would prove Playwright
            // works and leave the recorder's own input path untested end to end.
            RecorderSocketHandler socketHandler = new RecorderSocketHandler(bridge);
            org.springframework.web.socket.WebSocketSession wsSession =
                    mock(org.springframework.web.socket.WebSocketSession.class);
            when(wsSession.getId()).thenReturn("acceptance-socket");
            when(wsSession.isOpen()).thenReturn(true);
            java.util.Map<String, Object> attrs = new java.util.HashMap<>();
            attrs.put(RecorderHandshakeInterceptor.RECORDING_SESSION_ATTR, session);
            when(wsSession.getAttributes()).thenReturn(attrs);

            com.google.gson.JsonObject clickMsg = new com.google.gson.JsonObject();
            clickMsg.addProperty("type", "click");
            clickMsg.addProperty("canvasX", canvasX);
            clickMsg.addProperty("canvasY", canvasY);
            com.google.gson.JsonObject g = new com.google.gson.JsonObject();
            g.addProperty("canvasWidth", (int) canvasWidth);
            g.addProperty("canvasHeight", (int) canvasHeight);
            g.addProperty("frameWidth", 1280);
            g.addProperty("frameHeight", 720);
            g.addProperty("pageScaleFactor", 1.0);
            g.addProperty("offsetTop", 0.0);
            clickMsg.add("geometry", g);

            socketHandler.handleTextMessage(wsSession,
                    new org.springframework.web.socket.TextMessage(clickMsg.toString()));

            // 4. Observes that the page navigates to schritt2.html and screencast frames continue arriving
            session.worker().submit(browser -> {
                session.page().waitForURL("**/reise/schritt2.html");
                return null;
            });

            String currentUrl = session.worker().submit(browser -> session.page().url());
            assertThat(currentUrl).contains("reise/schritt2.html");

            ScreencastFrame navigationFrame = frames.poll(5, TimeUnit.SECONDS);
            assertThat(navigationFrame).as("Screencast frame received after navigation").isNotNull();
            assertThat(navigationFrame.data()).isNotBlank();
        } finally {
            bridge.detach(session);
            // 5. Closes the session via RecordingSessionRegistry.close(sessionId)
            registry.close(sessionId);
        }

        assertThat(registry.find(sessionId, "alice")).isEmpty();

        // 6. Verifies that the worker is returned to RecorderPool
        assertThat(pool.busy()).isEqualTo(0);

        // Verify full pool capacity can be allocated
        var w1 = pool.allocate();
        var w2 = pool.allocate();
        assertThat(w1).isPresent();
        assertThat(w2).isPresent();
        pool.release(w1.get());
        pool.release(w2.get());
    }

    @Test
    void idleSessionPastTimeoutIsReapedAndWorkerReturnedToPool() throws Exception {
        MutableTestClock clock = new MutableTestClock(Instant.parse("2026-08-28T14:00:00Z"));
        RecordingSessionRegistry registry = new RecordingSessionRegistry(pool, properties, clock);
        String startUrl = fixtureSite.url("reise/start.html");

        // 1. Opens a session
        RecordingSession session = registry.open(1L, startUrl, "bob");
        UUID sessionId = session.sessionId();
        assertThat(session).isNotNull();
        assertThat(pool.busy()).isEqualTo(1);
        assertThat(registry.find(sessionId, "bob")).isPresent();

        try {
            // 2. Advance time past idle timeout (15 minutes + 1 second) and trigger reapIdle()
            clock.advance(properties.idleTimeout().plusSeconds(1));
            registry.reapIdle();

            // 3. Verifies the idle session is closed and its worker returned to the pool
            assertThat(registry.find(sessionId, "bob")).isEmpty();
            assertThat(pool.busy()).isEqualTo(0);

            // Verify full pool capacity can be allocated
            var w1 = pool.allocate();
            var w2 = pool.allocate();
            assertThat(w1).isPresent();
            assertThat(w2).isPresent();
            pool.release(w1.get());
            pool.release(w2.get());
        } finally {
            registry.close(sessionId);
        }
    }

    private static class MutableTestClock extends Clock {
        private Instant current;
        private final ZoneId zone = ZoneOffset.UTC;

        MutableTestClock(Instant initial) {
            this.current = initial;
        }

        void advance(Duration duration) {
            this.current = this.current.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return zone;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return current;
        }
    }
}
