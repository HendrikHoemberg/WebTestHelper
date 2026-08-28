package dev.hendrikhoemberg.webtesthelper.recorder;

import com.google.gson.JsonObject;
import com.microsoft.playwright.CDPSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * Bridges Chromium CDP screencast frames to a {@link FrameSink} (§10.1, D110).
 *
 * <p>Manages the CDP session on the session's worker thread to preserve Playwright thread confinement.
 * Screencast is change-driven: an initial frame is captured and delivered on attach (D110),
 * and subsequent frames are emitted when page visual updates occur.
 * Every received screencast frame is acknowledged via {@code Page.screencastFrameAck}, updating
 * the session's activity timestamp.
 *
 * <p><strong>An attached session is pumped.</strong> playwright-java has no dispatcher thread:
 * {@code Connection.processOneMessage()} runs only while the owning thread is inside a Playwright
 * call, so a CDP listener on an otherwise idle worker never fires. Measured against a page
 * repainting five times a second with an idle worker: zero frames in three seconds, and a single
 * API call releasing exactly one - one, because the ack that unblocks the next frame is itself
 * sent from inside the dispatch. A pump thread therefore keeps the worker inside
 * {@code page.waitForTimeout(pumpInterval)} back to back for as long as a sink is attached,
 * yielding between waits so queued input never waits longer than one interval.
 */
@Component
public class ScreencastBridge {

    private static final Logger log = LoggerFactory.getLogger(ScreencastBridge.class);

    private final RecorderProperties properties;
    private final Map<UUID, Attachment> activeAttachments = new ConcurrentHashMap<>();
    private final Map<UUID, AtomicLong> historicalAcks = new ConcurrentHashMap<>();
    private final ExecutorService pumpExecutor = Executors.newCachedThreadPool(runnable -> {
        Thread t = new Thread(runnable, "recorder-pump");
        t.setDaemon(true);
        return t;
    });

    @Autowired
    public ScreencastBridge(RecorderProperties properties) {
        this.properties = Objects.requireNonNull(properties, "properties must not be null");
    }

    /**
     * Attaches a {@link FrameSink} to the given recording session and starts screencasting.
     *
     * @param session the active recording session
     * @param sink    the consumer receiving screencast frames
     * @throws IllegalStateException if the session is closed or if CDP setup fails
     */
    public void attach(RecordingSession session, FrameSink sink) {
        Objects.requireNonNull(session, "session must not be null");
        Objects.requireNonNull(sink, "sink must not be null");

        if (session.isClosed()) {
            throw new IllegalStateException("Aufnahmesitzung " + session.sessionId() + " ist bereits geschlossen");
        }

        // If an attachment already exists for this session, detach it first
        detach(session);

        Attachment attachment = new Attachment(sink);
        activeAttachments.put(session.sessionId(), attachment);
        historicalAcks.computeIfAbsent(session.sessionId(), k -> new AtomicLong(0));

        session.worker().submit(browser -> {
            if (session.isClosed() || attachment.detached.get()) {
                return null;
            }
            if (session.context() == null || session.page() == null) {
                throw new IllegalStateException("Sitzung besitzt weder BrowserContext noch Page");
            }

            CDPSession cdp = session.context().newCDPSession(session.page());
            session.setCdpSession(cdp);
            attachment.cdpSession = cdp;

            Consumer<JsonObject> frameListener = event -> {
                if (attachment.detached.get()) {
                    return;
                }
                try {
                    String data = event.get("data").getAsString();
                    int frameSessionId = event.get("sessionId").getAsInt();
                    JsonObject metaObj = event.has("metadata") && event.get("metadata").isJsonObject()
                            ? event.getAsJsonObject("metadata")
                            : null;
                    ScreencastMetadata metadata = parseMetadata(metaObj);
                    ScreencastFrame frame = new ScreencastFrame(data, metadata, frameSessionId);
                    log.debug("ScreencastBridge empfängt CDP-Frame: sessionId={}, bytes={}", frameSessionId, data.length());

                    try {
                        sink.onFrame(frame);
                    } catch (Exception e) {
                        log.warn("Fehler in FrameSink bei Frame {}: {}", frameSessionId, e.getMessage());
                    }

                    // Acknowledge frame to keep CDP stream alive
                    JsonObject ack = new JsonObject();
                    ack.addProperty("sessionId", frameSessionId);
                    cdp.send("Page.screencastFrameAck", ack);
                    log.debug("ScreencastBridge sendet screencastFrameAck für sessionId={}", frameSessionId);

                    AtomicLong total = historicalAcks.get(session.sessionId());
                    if (total != null) {
                        total.incrementAndGet();
                    }
                    session.recordActivity();
                } catch (Exception e) {
                    log.debug("Fehler bei Verarbeitung von screencastFrame: {}", e.getMessage());
                }
            };

            attachment.frameListener = frameListener;
            cdp.on("Page.screencastFrame", frameListener);

            // Start screencast
            JsonObject startParams = new JsonObject();
            startParams.addProperty("format", "jpeg");
            startParams.addProperty("quality", properties.frameQuality());
            startParams.addProperty("maxWidth", properties.viewportWidth());
            startParams.addProperty("maxHeight", properties.viewportHeight());
            startParams.addProperty("everyNthFrame", 1);
            cdp.send("Page.startScreencast", startParams);

            // Deliver initial on-attach screenshot (D110)
            try {
                JsonObject captureParams = new JsonObject();
                captureParams.addProperty("format", "jpeg");
                captureParams.addProperty("quality", properties.frameQuality());
                JsonObject captureResult = cdp.send("Page.captureScreenshot", captureParams);
                if (captureResult != null && captureResult.has("data")) {
                    String initialData = captureResult.get("data").getAsString();
                    ScreencastFrame initialFrame =
                            new ScreencastFrame(initialData, currentLayout(cdp), 0);
                    if (!attachment.detached.get()) {
                        sink.onFrame(initialFrame);
                    }
                }
            } catch (Exception e) {
                log.warn("Initialer on-attach Screenshot konnte nicht erfasst werden: {}", e.getMessage());
            }

            return null;
        });

        // Outside the submit above: starting it inside would deadlock on the worker's own thread.
        attachment.pumpTask = pumpExecutor.submit(() -> pump(session, attachment));
    }

    /**
     * Holds the worker thread inside a Playwright call so CDP events keep being dispatched.
     *
     * <p>Runs on a pump thread and yields between waits, so an input task submitted by
     * {@link RecorderSocketHandler} queues behind at most one {@code pumpInterval}.
     */
    private void pump(RecordingSession session, Attachment attachment) {
        long millis = Math.max(1L, properties.pumpInterval().toMillis());
        while (!attachment.detached.get() && !session.isClosed()) {
            try {
                session.worker().submit(browser -> {
                    session.page().waitForTimeout(millis);
                    return null;
                });
            } catch (RuntimeException e) {
                if (!attachment.detached.get() && !session.isClosed()) {
                    log.debug("Pump für Sitzung {} beendet: {}", session.sessionId(), e.getMessage());
                }
                return;
            }
        }
    }

    /**
     * Detaches the active screencast bridge for the given session and stops frame delivery.
     *
     * @param session the recording session to detach from
     */
    public void detach(RecordingSession session) {
        if (session == null) {
            return;
        }
        historicalAcks.remove(session.sessionId());
        Attachment attachment = activeAttachments.remove(session.sessionId());
        if (attachment == null) {
            return;
        }

        attachment.detached.set(true);
        if (attachment.pumpTask != null) {
            attachment.pumpTask.cancel(true);
        }
        try {
            if (!session.isClosed() && session.worker() != null) {
                session.worker().submit(browser -> {
                    CDPSession cdp = attachment.cdpSession;
                    if (cdp != null) {
                        if (attachment.frameListener != null) {
                            try {
                                cdp.off("Page.screencastFrame", attachment.frameListener);
                            } catch (Exception ignored) {
                            }
                        }
                        try {
                            cdp.send("Page.stopScreencast");
                        } catch (Exception ignored) {
                        }
                        try {
                            cdp.detach();
                        } catch (Exception ignored) {
                        }
                    }
                    if (session.cdpSession() == cdp) {
                        session.setCdpSession(null);
                    }
                    return null;
                });
            }
        } catch (Exception e) {
            log.debug("Fehler beim Beenden des ScreencastBridge für Sitzung {}: {}", session.sessionId(), e.getMessage());
        }
    }

    /**
     * Returns the number of frame acknowledgments sent for the specified session.
     */
    public long ackCount(RecordingSession session) {
        if (session == null) {
            return 0L;
        }
        AtomicLong count = historicalAcks.get(session.sessionId());
        return count != null ? count.get() : 0L;
    }

    /**
     * Reads the page's real layout state for the on-attach frame (D110).
     *
     * <p>The on-attach frame comes from {@code Page.captureScreenshot}, which carries no
     * screencast metadata of its own. Fabricating zeroes here would make the client translate
     * its first click against a page it believes is unscrolled, so the state is read from
     * {@code Page.getLayoutMetrics} instead.
     */
    private ScreencastMetadata currentLayout(CDPSession cdp) {
        try {
            JsonObject metrics = cdp.send("Page.getLayoutMetrics", new JsonObject());
            JsonObject visual = metrics != null && metrics.has("cssVisualViewport")
                    ? metrics.getAsJsonObject("cssVisualViewport")
                    : null;
            if (visual != null) {
                return new ScreencastMetadata(
                        0.0,
                        visual.has("scale") ? visual.get("scale").getAsDouble() : 1.0,
                        visual.has("clientWidth") ? (int) visual.get("clientWidth").getAsDouble() : properties.viewportWidth(),
                        visual.has("clientHeight") ? (int) visual.get("clientHeight").getAsDouble() : properties.viewportHeight(),
                        visual.has("pageX") ? visual.get("pageX").getAsDouble() : 0.0,
                        visual.has("pageY") ? visual.get("pageY").getAsDouble() : 0.0,
                        System.currentTimeMillis() / 1000.0);
            }
        } catch (Exception e) {
            log.debug("Page.getLayoutMetrics nicht verfügbar: {}", e.getMessage());
        }
        return parseMetadata(null);
    }

    /** Stops every pump thread when the application context goes down. */
    @jakarta.annotation.PreDestroy
    public void shutdown() {
        pumpExecutor.shutdownNow();
    }

    private ScreencastMetadata parseMetadata(JsonObject meta) {
        if (meta == null) {
            return new ScreencastMetadata(
                    0.0, 1.0, properties.viewportWidth(), properties.viewportHeight(),
                    0.0, 0.0, System.currentTimeMillis() / 1000.0);
        }
        double offsetTop = meta.has("offsetTop") ? meta.get("offsetTop").getAsDouble() : 0.0;
        double pageScaleFactor = meta.has("pageScaleFactor") ? meta.get("pageScaleFactor").getAsDouble() : 1.0;
        int deviceWidth = meta.has("deviceWidth") ? meta.get("deviceWidth").getAsInt() : properties.viewportWidth();
        int deviceHeight = meta.has("deviceHeight") ? meta.get("deviceHeight").getAsInt() : properties.viewportHeight();
        double scrollOffsetX = meta.has("scrollOffsetX") ? meta.get("scrollOffsetX").getAsDouble() : 0.0;
        double scrollOffsetY = meta.has("scrollOffsetY") ? meta.get("scrollOffsetY").getAsDouble() : 0.0;
        double timestamp = meta.has("timestamp") ? meta.get("timestamp").getAsDouble() : System.currentTimeMillis() / 1000.0;
        return new ScreencastMetadata(offsetTop, pageScaleFactor, deviceWidth, deviceHeight, scrollOffsetX, scrollOffsetY, timestamp);
    }

    private static class Attachment {
        final FrameSink sink;
        final AtomicBoolean detached = new AtomicBoolean(false);
        volatile CDPSession cdpSession;
        volatile Consumer<JsonObject> frameListener;
        volatile Future<?> pumpTask;

        Attachment(FrameSink sink) {
            this.sink = sink;
        }
    }
}
