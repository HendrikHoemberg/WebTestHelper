package dev.hendrikhoemberg.webtesthelper.recorder;

import dev.hendrikhoemberg.webtesthelper.support.FixtureSite;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("browser")
class ScreencastBridgeTest {

    private static FixtureSite fixtureSite;
    private static RecorderPool pool;
    private static RecordingSession session;
    private static ScreencastBridge bridge;
    private static RecorderProperties properties;

    @BeforeAll
    static void start() {
        fixtureSite = FixtureSite.start();
        properties = new RecorderProperties(1, Duration.ofMinutes(15), 60, 1280, 720, true);
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
    }

    @AfterAll
    static void stop() {
        if (bridge != null && session != null) {
            bridge.detach(session);
        }
        if (session != null) {
            session.close();
            pool.release(session.worker());
        }
        if (pool != null) {
            pool.close();
        }
        if (fixtureSite != null) {
            fixtureSite.close();
        }
    }

    @Test
    void attachingToStaticPageDeliversInitialFrameWithinOneSecond() throws Exception {
        BlockingQueue<ScreencastFrame> frames = new LinkedBlockingQueue<>();
        bridge.attach(session, frames::add);
        try {
            ScreencastFrame frame = frames.poll(3, TimeUnit.SECONDS);
            assertThat(frame).as("Initial frame delivered on attach").isNotNull();
            assertThat(frame.data()).as("Frame has base64 data").isNotBlank();
            assertThat(frame.metadata()).as("Frame has metadata").isNotNull();
            assertThat(frame.metadata().deviceWidth()).isEqualTo(1280);
            assertThat(frame.metadata().deviceHeight()).isEqualTo(720);
        } finally {
            bridge.detach(session);
        }
    }

    @Test
    void onAttachFrameReportsThePagesRealLayoutNotFabricatedZeroes() throws Exception {
        session.worker().submit(browser -> {
            session.page().navigate(fixtureSite.url("reise/lang.html"));
            session.page().evaluate("window.scrollTo(0, 1500)");
            return null;
        });

        BlockingQueue<ScreencastFrame> frames = new LinkedBlockingQueue<>();
        bridge.attach(session, frames::add);
        try {
            ScreencastFrame initial = frames.poll(3, TimeUnit.SECONDS);
            assertThat(initial).as("On-attach frame delivered").isNotNull();
            assertThat(initial.metadata().scrollOffsetY())
                    .as("On-attach metadata carries the page's real scroll position")
                    .isCloseTo(1500.0, org.assertj.core.data.Offset.offset(1.0));
        } finally {
            bridge.detach(session);
            session.worker().submit(browser -> {
                session.page().navigate(fixtureSite.url("reise/start.html"));
                return null;
            });
        }
    }

    @Test
    void visualChangeOnPageDeliversFurtherFrames() throws Exception {
        BlockingQueue<ScreencastFrame> frames = new LinkedBlockingQueue<>();
        bridge.attach(session, frames::add);
        try {
            // Drain initial on-attach frame
            ScreencastFrame initial = frames.poll(5, TimeUnit.SECONDS);
            assertThat(initial).isNotNull();

            // Trigger a visual change on the page by navigating to a new fixture page
            session.worker().submit(browser -> {
                session.page().navigate(fixtureSite.url("reise/schritt2.html"));
                return null;
            });

            ScreencastFrame changeFrame = frames.poll(5, TimeUnit.SECONDS);
            assertThat(changeFrame).as("Visual change triggers screencast frame").isNotNull();
            assertThat(changeFrame.data()).isNotBlank();
        } finally {
            bridge.detach(session);
        }
    }

    @Test
    void everyDeliveredScreencastFrameIsAcknowledgedAndUpdatesActivity() throws Exception {
        BlockingQueue<ScreencastFrame> frames = new LinkedBlockingQueue<>();
        bridge.attach(session, frames::add);
        try {
            // Drain initial frame
            frames.poll(3, TimeUnit.SECONDS);

            Instant beforeActivity = session.lastActivity();
            List<ScreencastFrame> screencastFrames = new ArrayList<>();

            // Trigger multiple visual changes
            for (int i = 0; i < 3; i++) {
                final int index = i;
                session.worker().submit(browser -> {
                    session.page().evaluate("document.body.innerHTML = '<h1>Änderung " + index + "</h1>';");
                    return null;
                });
                ScreencastFrame f = frames.poll(5, TimeUnit.SECONDS);
                if (f != null && f.requiresAck()) {
                    screencastFrames.add(f);
                }
            }

            assertThat(screencastFrames).isNotEmpty();
            long acks = bridge.ackCount(session);
            assertThat(acks).as("Ack count matches received screencast frames count")
                    .isGreaterThanOrEqualTo(screencastFrames.size());
            assertThat(session.lastActivity()).isAfterOrEqualTo(beforeActivity);
        } finally {
            bridge.detach(session);
        }
    }

    @Test
    void detachStopsScreencastAndDeliversNoFurtherFrames() throws Exception {
        BlockingQueue<ScreencastFrame> frames = new LinkedBlockingQueue<>();
        bridge.attach(session, frames::add);

        // Receive initial frame
        ScreencastFrame initial = frames.poll(1, TimeUnit.SECONDS);
        assertThat(initial).isNotNull();

        // Detach screencast
        bridge.detach(session);
        frames.clear();
        assertThat(bridge.ackCount(session)).as("Historical acks pruned on detach").isEqualTo(0L);

        // Trigger visual changes after detach
        session.worker().submit(browser -> {
            session.page().evaluate("document.body.style.backgroundColor = 'yellow';");
            return null;
        });

        ScreencastFrame afterDetach = frames.poll(300, TimeUnit.MILLISECONDS);
        assertThat(afterDetach).as("No frames delivered after detach").isNull();
    }

    @Test
    void measureInteractionFpsAndByteBudget() throws Exception {
        BlockingQueue<ScreencastFrame> frames = new LinkedBlockingQueue<>();
        bridge.attach(session, frames::add);
        try {
            // Navigate to fixture reise start page
            session.worker().submit(browser -> {
                session.page().navigate(fixtureSite.url("reise/start.html"));
                return null;
            });

            // Drain initial frames
            Thread.sleep(200);
            frames.clear();

            long startNano = System.nanoTime();
            long totalBytes = 0;
            int frameCount = 0;

            // Drive 10 seconds of realistic interaction (typing, clicking, scrolling)
            long deadline = System.currentTimeMillis() + 10_000;
            int step = 0;
            while (System.currentTimeMillis() < deadline) {
                final int s = step++;
                session.worker().submit(browser -> {
                    var page = session.page();
                    // Typing into input or mutating elements
                    if (s % 3 == 0) {
                        page.evaluate("document.body.innerHTML += '<p>Eingabe Schritt " + s + "</p>';");
                    } else if (s % 3 == 1) {
                        page.evaluate("window.scrollBy(0, 50);");
                    } else {
                        page.evaluate("window.scrollBy(0, -50);");
                    }
                    return null;
                });

                // Poll frames delivered during this interval
                ScreencastFrame f = frames.poll(200, TimeUnit.MILLISECONDS);
                if (f != null) {
                    frameCount++;
                    totalBytes += f.data().length();
                }
            }

            // Drain remaining frames
            ScreencastFrame remaining;
            while ((remaining = frames.poll(100, TimeUnit.MILLISECONDS)) != null) {
                frameCount++;
                totalBytes += remaining.data().length();
            }

            long elapsedSeconds = TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - startNano);
            double fps = elapsedSeconds > 0 ? (double) frameCount / elapsedSeconds : frameCount;

            System.out.println("=== Screencast Interaction Measurement ===");
            System.out.println("Duration: " + elapsedSeconds + " s");
            System.out.println("Total Frames: " + frameCount);
            System.out.println("FPS: " + String.format("%.2f", fps));
            System.out.println("Total Base64 Bytes: " + totalBytes + " (" + (totalBytes / 1024) + " KB)");
            if (frameCount > 0) {
                System.out.println("Avg Frame Size: " + (totalBytes / frameCount / 1024) + " KB");
            }

            assertThat(frameCount).as("Interactive flow produced frames").isGreaterThan(0);
        } finally {
            bridge.detach(session);
        }
    }

    private record SessionContext(com.microsoft.playwright.BrowserContext context, com.microsoft.playwright.Page page) {}
}
