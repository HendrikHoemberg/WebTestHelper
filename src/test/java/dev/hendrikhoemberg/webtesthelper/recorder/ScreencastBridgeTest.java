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
        properties = new RecorderProperties(1, Duration.ofMinutes(15), 60, 1280, 720, true, Duration.ofMillis(100));
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
            ScreencastFrame frame = frames.poll(1, TimeUnit.SECONDS);
            assertThat(frame).as("Initial frame delivered on attach within a second").isNotNull();
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
            // The page must be laid out before it can scroll; without this the scroll is a no-op
            // whenever navigation has not settled, and the assertion below reads 0.
            session.page().waitForFunction("window.scrollY === 1500");
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
    void framesKeepArrivingWhileTheWorkerThreadSitsIdle() throws Exception {
        // playwright-java dispatches CDP events only while the owning thread is inside a
        // Playwright call. Without a pump the live view repaints once per user input and shows
        // the page as it was before that input - the exact "is the socket broken?" experience
        // D110's on-attach frame exists to prevent, moved one step downstream.
        session.worker().submit(browser -> {
            session.page().navigate(fixtureSite.url("reise/animiert.html"));
            return null;
        });

        BlockingQueue<ScreencastFrame> frames = new LinkedBlockingQueue<>();
        bridge.attach(session, frames::add);
        try {
            frames.poll(3, TimeUnit.SECONDS); // drain the on-attach frame

            // The page repaints ~5x/second. Do nothing at all on the Java side for 3 seconds.
            int delivered = 0;
            long deadline = System.currentTimeMillis() + 3_000;
            while (System.currentTimeMillis() < deadline) {
                if (frames.poll(200, TimeUnit.MILLISECONDS) != null) {
                    delivered++;
                }
            }

            assertThat(delivered)
                    .as("Frames arrive from a self-repainting page with no Java-side activity")
                    .isGreaterThanOrEqualTo(5);
        } finally {
            bridge.detach(session);
            session.worker().submit(browser -> {
                session.page().navigate(fixtureSite.url("reise/start.html"));
                return null;
            });
        }
    }

    @Test
    void everyDeliveredScreencastFrameIsAcknowledgedAndUpdatesActivity() throws Exception {
        BlockingQueue<ScreencastFrame> frames = new LinkedBlockingQueue<>();
        bridge.attach(session, frames::add);
        try {
            Instant beforeActivity = session.lastActivity();

            for (int i = 0; i < 3; i++) {
                final int index = i;
                session.worker().submit(browser -> {
                    session.page().evaluate("document.body.innerHTML = '<h1>Änderung " + index + "</h1>';");
                    return null;
                });
            }

            // Drain until the page goes quiet, so no frame is in flight when the count is read.
            List<ScreencastFrame> delivered = new ArrayList<>();
            ScreencastFrame f;
            while ((f = frames.poll(1, TimeUnit.SECONDS)) != null) {
                delivered.add(f);
            }

            long needingAck = delivered.stream().filter(ScreencastFrame::requiresAck).count();
            assertThat(needingAck).as("The page changed, so screencast frames arrived").isPositive();
            assertThat(bridge.ackCount(session))
                    .as("Every screencast frame is acknowledged exactly once - an unacknowledged "
                            + "frame stops the stream, and a doubled ack would hide that")
                    .isEqualTo(needingAck);
            assertThat(session.lastActivity()).isAfter(beforeActivity);
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
        // The sink counts directly. The previous version of this measurement polled a queue with
        // a 200ms timeout inside its drive loop, so it could never observe more than five frames
        // a second whatever the browser did - the "~5.1 fps" it recorded was the harness, not the
        // screencast.
        java.util.concurrent.atomic.AtomicInteger frameCount = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.atomic.AtomicLong totalBytes = new java.util.concurrent.atomic.AtomicLong();
        FrameSink counting = frame -> {
            frameCount.incrementAndGet();
            totalBytes.addAndGet(frame.data().length());
        };

        session.worker().submit(browser -> {
            session.page().navigate(fixtureSite.url("reise/lang.html"));
            return null;
        });

        bridge.attach(session, counting);
        try {
            Thread.sleep(300);
            frameCount.set(0);
            totalBytes.set(0);

            long startNano = System.nanoTime();
            long deadline = System.currentTimeMillis() + 10_000;
            int step = 0;
            while (System.currentTimeMillis() < deadline) {
                final int i = step++;
                session.worker().submit(browser -> {
                    if (i % 3 == 0) {
                        session.page().evaluate("document.querySelector('h1').textContent = 'Eingabe " + i + "';");
                    } else if (i % 3 == 1) {
                        session.page().evaluate("window.scrollBy(0, 40);");
                    } else {
                        session.page().evaluate("window.scrollBy(0, -40);");
                    }
                    return null;
                });
            }
            double elapsed = (System.nanoTime() - startNano) / 1_000_000_000.0;
            int busyFrames = frameCount.get();
            long busyBytes = totalBytes.get();

            // And the idle half of the budget: change-driven means an untouched page costs nothing.
            Thread.sleep(500); // let the last scroll's frames drain
            frameCount.set(0);
            Thread.sleep(3_000);
            int idleFrames = frameCount.get();

            System.out.println("=== Screencast measurement (pumped) ===");
            System.out.printf("Busy: %.1f s, %d frames, %.2f fps%n", elapsed, busyFrames, busyFrames / elapsed);
            System.out.printf("Busy bytes: %d base64 (%.0f KB), avg %.1f KB/frame, %.0f KB/s%n",
                    busyBytes, busyBytes / 1024.0,
                    busyFrames > 0 ? busyBytes / 1024.0 / busyFrames : 0.0,
                    busyBytes / 1024.0 / elapsed);
            System.out.printf("Idle: %d frames in 3 s on an untouched page%n", idleFrames);

            assertThat(busyFrames).as("Interactive flow produced frames").isPositive();
            assertThat(idleFrames).as("A static page costs nothing (change-driven)").isZero();
        } finally {
            bridge.detach(session);
            session.worker().submit(browser -> {
                session.page().navigate(fixtureSite.url("reise/start.html"));
                return null;
            });
        }
    }

    private record SessionContext(com.microsoft.playwright.BrowserContext context, com.microsoft.playwright.Page page) {}
}
