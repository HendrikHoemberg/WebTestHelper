package dev.hendrikhoemberg.webtesthelper.recorder;

import com.microsoft.playwright.BrowserContext;
import dev.hendrikhoemberg.webtesthelper.checks.CookieBanner;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
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

    /**
     * How long a session whose browser tab went away is kept: the save POST races the page unload,
     * and a reload reconnects. Past this, the slot goes back to the pool instead of waiting out
     * the full idle timeout.
     */
    static final Duration DISCONNECT_GRACE = Duration.ofSeconds(60);

    private final RecorderPool pool;
    private final RecorderProperties properties;
    private final Clock clock;
    private final boolean allowLoopback;
    private final Map<UUID, RecordingSession> sessions = new ConcurrentHashMap<>();

    @Autowired
    public RecordingSessionRegistry(
            RecorderPool pool,
            RecorderProperties properties,
            @Value("${webtesthelper.recorder.allow-loopback:false}") boolean allowLoopback) {
        this(pool, properties, Clock.systemUTC(), allowLoopback);
    }

    public RecordingSessionRegistry(RecorderPool pool, RecorderProperties properties) {
        this(pool, properties, Clock.systemUTC(), false);
    }

    public RecordingSessionRegistry(RecorderPool pool, RecorderProperties properties, Clock clock) {
        this(pool, properties, clock, false);
    }

    public RecordingSessionRegistry(RecorderPool pool, RecorderProperties properties, Clock clock,
                                    boolean allowLoopback) {
        this.pool = Objects.requireNonNull(pool, "pool must not be null");
        this.properties = Objects.requireNonNull(properties, "properties must not be null");
        this.clock = clock != null ? clock : Clock.systemUTC();
        this.allowLoopback = allowLoopback;
    }

    /**
     * Opens a new recording session on an allocated worker.
     *
     * @param siteId   the id of the site being recorded
     * @param startUrl the initial URL to navigate to
     * @param username the username of the authenticated owner
     * @return the opened {@link RecordingSession}
     * @throws RecorderCapacityException if every worker is already allocated
     * @throws IllegalArgumentException  if the startUrl uses a disallowed scheme or target address
     * @throws IllegalStateException     if the browser cannot be started for this session
     */
    public RecordingSession open(long siteId, String startUrl, String username) {
        Objects.requireNonNull(startUrl, "startUrl must not be null");
        Objects.requireNonNull(username, "username must not be null");

        if (!isAllowedUrl(startUrl, allowLoopback, false)) {
            throw new IllegalArgumentException("Unzulässige Recorder-Ziel-URL: " + startUrl);
        }

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
                try {
                    installRouteGuard(context);
                    var intentCapture = IntentCapture.install(context);
                    var page = context.newPage();
                    page.navigate(startUrl);
                    try {
                        CookieBanner.accept(page, CookieBanner.DISMISSAL_WAIT);
                    } catch (RuntimeException e) {
                        log.warn("Cookie-Banner im Recorder nicht akzeptiert: {}", e.getMessage());
                    }
                    return new BrowserSessionContext(context, page, intentCapture);
                } catch (RuntimeException e) {
                    try {
                        context.close();
                    } catch (RuntimeException closeEx) {
                        e.addSuppressed(closeEx);
                    }
                    throw e;
                }
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

    private void installRouteGuard(BrowserContext context) {
        context.route("**/*", route -> {
            String requestUrl = route.request().url();
            if (isAllowedUrl(requestUrl, allowLoopback, true)) {
                route.resume();
            } else {
                log.warn("Recorder blockiert unzulässige Anfrage: {}", requestUrl);
                route.abort("blockedbyclient");
            }
        });
    }

    static boolean isAllowedUrl(String rawUrl, boolean allowLoopback, boolean resolveDns) {
        if (rawUrl == null || rawUrl.isBlank()) {
            return false;
        }
        URI uri;
        try {
            uri = URI.create(rawUrl.strip());
        } catch (IllegalArgumentException e) {
            return false;
        }
        String scheme = uri.getScheme();
        if (scheme == null || (!scheme.equalsIgnoreCase("http") && !scheme.equalsIgnoreCase("https"))) {
            return false;
        }
        String rawHost = uri.getHost();
        if (rawHost == null || rawHost.isBlank()) {
            return false;
        }
        String host = normalizeHost(rawHost);
        if (host.isEmpty() || host.equals("metadata.google.internal")) {
            return false;
        }
        if (!allowLoopback && (host.equals("localhost") || host.endsWith(".localhost"))) {
            return false;
        }

        if (isIpLiteral(host)) {
            try {
                InetAddress addr = InetAddress.getByName(host);
                return !isBlockedAddress(addr, allowLoopback);
            } catch (UnknownHostException e) {
                return false;
            }
        }

        if (resolveDns) {
            try {
                InetAddress[] addresses = InetAddress.getAllByName(host);
                if (addresses == null || addresses.length == 0) {
                    return false;
                }
                for (InetAddress address : addresses) {
                    if (isBlockedAddress(address, allowLoopback)) {
                        return false;
                    }
                }
            } catch (UnknownHostException e) {
                return false;
            }
        }
        return true;
    }

    private static String normalizeHost(String rawHost) {
        String host = rawHost.strip().toLowerCase(Locale.ROOT);
        if (host.startsWith("[") && host.endsWith("]") && host.length() > 2) {
            host = host.substring(1, host.length() - 1);
        }
        while (host.endsWith(".")) {
            host = host.substring(0, host.length() - 1);
        }
        return host;
    }

    private static boolean isIpLiteral(String host) {
        if (host.contains(":")) {
            return true;
        }
        boolean hasDot = false;
        for (int i = 0; i < host.length(); i++) {
            char c = host.charAt(i);
            if (c == '.') {
                hasDot = true;
            } else if (c < '0' || c > '9') {
                return false;
            }
        }
        return hasDot;
    }

    /**
     * Blocks link-local / cloud metadata ({@code 169.254.0.0/16}, {@code fe80::/10}),
     * unspecified ({@code 0.0.0.0/8}, {@code ::}), and (unless {@code allowLoopback}) loopback
     * ({@code 127.0.0.0/8}, {@code ::1}), while allowing RFC1918 LAN targets for internal staging sites.
     */
    static boolean isBlockedAddress(InetAddress address, boolean allowLoopback) {
        if (address == null) {
            return true;
        }
        if (address.isAnyLocalAddress() || address.isLinkLocalAddress()) {
            return true;
        }
        if (!allowLoopback && address.isLoopbackAddress()) {
            return true;
        }
        byte[] bytes = address.getAddress();
        if (bytes.length == 4) {
            int b0 = bytes[0] & 0xFF;
            int b1 = bytes[1] & 0xFF;
            if (b0 == 0) {
                return true;
            }
            if (b0 == 169 && b1 == 254) {
                return true;
            }
            if (!allowLoopback && b0 == 127) {
                return true;
            }
        } else if (bytes.length == 16) {
            int b0 = bytes[0] & 0xFF;
            int b1 = bytes[1] & 0xFF;
            if (b0 == 0xFE && (b1 & 0xC0) == 0x80) {
                return true;
            }
            boolean first12Zero = true;
            for (int i = 0; i < 12; i++) {
                if (bytes[i] != 0) {
                    first12Zero = false;
                    break;
                }
            }
            if (first12Zero) {
                boolean isUnspecified = bytes[12] == 0 && bytes[13] == 0 && bytes[14] == 0 && bytes[15] == 0;
                boolean isLoopback = bytes[12] == 0 && bytes[13] == 0 && bytes[14] == 0 && (bytes[15] & 0xFF) == 1;
                if (isUnspecified) {
                    return true;
                }
                if (isLoopback) {
                    return !allowLoopback;
                }
                return checkEmbeddedIpv4(bytes, 12, allowLoopback);
            }
            boolean first10Zero = true;
            for (int i = 0; i < 10; i++) {
                if (bytes[i] != 0) {
                    first10Zero = false;
                    break;
                }
            }
            if (first10Zero && (bytes[10] & 0xFF) == 0xFF && (bytes[11] & 0xFF) == 0xFF) {
                return checkEmbeddedIpv4(bytes, 12, allowLoopback);
            }
        }
        return false;
    }

    private static boolean checkEmbeddedIpv4(byte[] bytes, int offset, boolean allowLoopback) {
        try {
            return isBlockedAddress(InetAddress.getByAddress(Arrays.copyOfRange(bytes, offset, offset + 4)), allowLoopback);
        } catch (UnknownHostException e) {
            return true;
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
     * Reaps sessions that have been idle longer than {@link RecorderProperties#idleTimeout()}, or
     * whose tab has been disconnected longer than {@link #DISCONNECT_GRACE}, returning their workers to the pool even if closing the browser context throws an exception.
     */
    public void reapIdle() {
        Instant now = clock.instant();
        Duration idleTimeout = properties.idleTimeout();
        List<UUID> toReap = new ArrayList<>();
        for (RecordingSession session : sessions.values()) {
            boolean idle = Duration.between(session.lastActivity(), now).compareTo(idleTimeout) >= 0;
            boolean abandoned = session.disconnectedAt()
                    .map(at -> Duration.between(at, now).compareTo(DISCONNECT_GRACE) >= 0)
                    .orElse(false);
            if (idle || abandoned) {
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
