package dev.hendrikhoemberg.webtesthelper.recorder;

import dev.hendrikhoemberg.webtesthelper.checks.CookieBanner;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Registry managing active interactive recording sessions (§10.1, D109).
 *
 * <p>Handles session allocation against {@link RecorderPool}, session lookup with ownership enforcement,
 * explicit session termination, and periodic reaping of idle sessions.
 */
@Component
public class RecordingSessionRegistry implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(RecordingSessionRegistry.class);

    private final RecorderPool pool;
    private final RecorderProperties properties;
    private final Clock clock;
    private final Map<UUID, RecordingSession> sessions = new ConcurrentHashMap<>();

    @Autowired
    public RecordingSessionRegistry(RecorderPool pool, RecorderProperties properties) {
        this(pool, properties, Clock.systemUTC());
    }

    public RecordingSessionRegistry(RecorderPool pool, RecorderProperties properties, Clock clock) {
        this.pool = Objects.requireNonNull(pool, "pool must not be null");
        this.properties = Objects.requireNonNull(properties, "properties must not be null");
        this.clock = clock != null ? clock : Clock.systemUTC();
    }

    /**
     * Opens a new recording session on an allocated worker.
     *
     * @param siteId   the id of the site being recorded
     * @param startUrl the initial URL to navigate to
     * @param username the username of the authenticated owner
     * @return the opened {@link RecordingSession}
     * @throws RecorderCapacityException if every worker is already allocated
     * @throws IllegalStateException     if the browser cannot be started for this session
     */
    public RecordingSession open(long siteId, String startUrl, String username) {
        Objects.requireNonNull(startUrl, "startUrl must not be null");
        Objects.requireNonNull(username, "username must not be null");

        RecorderWorker worker = pool.allocate()
                .orElseThrow(() -> new RecorderCapacityException(properties.maxSessions()));

        UUID sessionId = UUID.randomUUID();
        try {
            BrowserSessionContext bsc = worker.submit(browser -> {
                if (browser == null) {
                    return null;
                }
                var contextOptions = new com.microsoft.playwright.Browser.NewContextOptions()
                        .setViewportSize(properties.viewportWidth(), properties.viewportHeight());
                var context = browser.newContext(contextOptions);
                var intentCapture = IntentCapture.install(context);
                var page = context.newPage();
                page.navigate(startUrl);
                try {
                    CookieBanner.accept(page, CookieBanner.DISMISSAL_WAIT);
                } catch (RuntimeException e) {
                    log.warn("Cookie-Banner im Recorder nicht akzeptiert: {}", e.getMessage());
                }
                return new BrowserSessionContext(context, page, intentCapture);
            });
            var context = bsc != null ? bsc.context() : null;
            var page = bsc != null ? bsc.page() : null;
            var intentCapture = bsc != null ? bsc.intentCapture() : null;
            RecordingSession session = new RecordingSession(
                    sessionId, siteId, startUrl, username, worker, context, page, intentCapture, clock);
            sessions.put(sessionId, session);
            log.info("Aufnahmesitzung {} für Benutzer '{}' auf Site {} geöffnet", sessionId, username, siteId);
            return session;
        } catch (Exception e) {
            pool.release(worker);
            throw new IllegalStateException("Aufnahmesitzung konnte nicht gestartet werden", e);
        }
    }

    /**
     * Finds an active recording session by ID and verifies ownership.
     *
     * <p>Returns {@link Optional#empty()} if the session does not exist or if {@code username}
     * does not match the session owner.
     */
    public Optional<RecordingSession> find(UUID sessionId, String username) {
        if (sessionId == null || username == null) {
            return Optional.empty();
        }
        RecordingSession session = sessions.get(sessionId);
        if (session == null || !username.equals(session.username())) {
            return Optional.empty();
        }
        return Optional.of(session);
    }

    /**
     * Closes an active recording session and releases its worker back to the pool.
     */
    public void close(UUID sessionId) {
        if (sessionId == null) {
            return;
        }
        RecordingSession session = sessions.remove(sessionId);
        if (session == null) {
            return;
        }
        try {
            session.close();
        } catch (Exception e) {
            log.warn("Fehler beim Schließen der Aufnahmesitzung {}: {}", sessionId, e.getMessage());
        } finally {
            pool.release(session.worker());
        }
    }

    /**
     * Reaps sessions that have been idle longer than {@link RecorderProperties#idleTimeout()},
     * returning their workers to the pool even if closing the browser context throws an exception.
     */
    public void reapIdle() {
        Instant now = clock.instant();
        Duration idleTimeout = properties.idleTimeout();
        List<UUID> toReap = new ArrayList<>();
        for (RecordingSession session : sessions.values()) {
            Duration idleTime = Duration.between(session.lastActivity(), now);
            if (idleTime.compareTo(idleTimeout) >= 0) {
                toReap.add(session.sessionId());
            }
        }
        for (UUID id : toReap) {
            log.info("Bereinige inaktive Aufnahmesitzung {}", id);
            close(id);
        }
    }

    /**
     * Number of currently active sessions.
     */
    public int activeSessions() {
        return sessions.size();
    }

    /**
     * Number of currently active sessions owned by the given user.
     */
    public int activeSessionsForUser(String username) {
        if (username == null) {
            return 0;
        }
        int count = 0;
        for (RecordingSession session : sessions.values()) {
            if (username.equals(session.username())) {
                count++;
            }
        }
        return count;
    }

    /**
     * Closes all active sessions owned by the given user and releases their workers to the pool.
     *
     * @param username the username of the owner
     * @return the number of closed sessions
     */
    public int closeAllForUser(String username) {
        if (username == null) {
            return 0;
        }
        List<UUID> toClose = new ArrayList<>();
        for (RecordingSession session : sessions.values()) {
            if (username.equals(session.username())) {
                toClose.add(session.sessionId());
            }
        }
        for (UUID id : toClose) {
            close(id);
        }
        return toClose.size();
    }

    /**
     * Closes all active sessions across all users and releases workers to the pool.
     *
     * @return the number of closed sessions
     */
    public int closeAll() {
        List<UUID> ids = new ArrayList<>(sessions.keySet());
        for (UUID id : ids) {
            close(id);
        }
        return ids.size();
    }

    @Override
    @PreDestroy
    public void close() {
        closeAll();
    }

    private record BrowserSessionContext(com.microsoft.playwright.BrowserContext context, com.microsoft.playwright.Page page, IntentCapture intentCapture) {}
}
