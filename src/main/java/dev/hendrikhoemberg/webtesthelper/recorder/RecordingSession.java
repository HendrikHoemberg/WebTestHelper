package dev.hendrikhoemberg.webtesthelper.recorder;

import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Represents an active interactive recording session (spec 10.1, D109).
 *
 * <p>A session owns one Chromium context and page on its dedicated {@link RecorderWorker} thread.
 * It tracks its last activity timestamp (updated on user input and screencast frame acknowledgments)
 * so that idle sessions can be reaped automatically.
 */
public class RecordingSession implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(RecordingSession.class);

    private final UUID sessionId;
    private final long siteId;
    private final String startUrl;
    private final String username;
    private final RecorderWorker worker;
    private final BrowserContext context;
    private final Page page;
    private final IntentCapture intentCapture;
    private final Clock clock;
    private volatile Instant lastActivity;
    private volatile com.microsoft.playwright.CDPSession cdpSession;
    private final AtomicBoolean closed = new AtomicBoolean(false);

    public RecordingSession(UUID sessionId, long siteId, String startUrl, String username,
                            RecorderWorker worker, BrowserContext context, Page page, Clock clock) {
        this(sessionId, siteId, startUrl, username, worker, context, page, null, clock);
    }

    public RecordingSession(UUID sessionId, long siteId, String startUrl, String username,
                            RecorderWorker worker, BrowserContext context, Page page,
                            IntentCapture intentCapture, Clock clock) {
        this.sessionId = Objects.requireNonNull(sessionId, "sessionId must not be null");
        this.siteId = siteId;
        this.startUrl = Objects.requireNonNull(startUrl, "startUrl must not be null");
        this.username = Objects.requireNonNull(username, "username must not be null");
        this.worker = worker;
        this.context = context;
        this.page = page;
        this.intentCapture = intentCapture;
        this.clock = clock != null ? clock : Clock.systemUTC();
        this.lastActivity = this.clock.instant();
    }

    public UUID sessionId() {
        return sessionId;
    }

    public long siteId() {
        return siteId;
    }

    public String startUrl() {
        return startUrl;
    }

    public String username() {
        return username;
    }

    public RecorderWorker worker() {
        return worker;
    }

    public BrowserContext context() {
        return context;
    }

    public Page page() {
        return page;
    }

    public IntentCapture intentCapture() {
        return intentCapture;
    }

    public com.microsoft.playwright.CDPSession cdpSession() {
        return cdpSession;
    }

    public void setCdpSession(com.microsoft.playwright.CDPSession cdpSession) {
        this.cdpSession = cdpSession;
    }

    public Instant lastActivity() {
        return lastActivity;
    }

    /**
     * Updates the last activity timestamp to the current time of the configured clock.
     */
    public void recordActivity() {
        this.lastActivity = clock.instant();
    }

    /**
     * Updates the last activity timestamp to an explicit instant.
     */
    public void recordActivity(Instant activityAt) {
        this.lastActivity = Objects.requireNonNull(activityAt, "activityAt must not be null");
    }

    public boolean isClosed() {
        return closed.get();
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            if (worker != null) {
                worker.submit(browser -> {
                    if (cdpSession != null) {
                        try {
                            cdpSession.detach();
                        } catch (Exception ignored) {
                        }
                        cdpSession = null;
                    }
                    if (context != null) {
                        context.close();
                    }
                    return null;
                });
            }
        }
    }
}
