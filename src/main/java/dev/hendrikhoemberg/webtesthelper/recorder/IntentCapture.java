package dev.hendrikhoemberg.webtesthelper.recorder;

import com.microsoft.playwright.BrowserContext;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Installs intent capture bindings and init scripts onto a {@link BrowserContext} (§10.1).
 *
 * <p>Captures click, input, change, and submit events occurring inside pages within the context,
 * reporting them back to Java as {@link CapturedEvent} records.
 */
public final class IntentCapture {

    public static final String BINDING_NAME = "__wth_capture__";

    private static final String CAPTURE_SCRIPT;

    static {
        try {
            CAPTURE_SCRIPT = new ClassPathResource("recorder/capture.js")
                    .getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("recorder/capture.js fehlt im Klassenpfad", e);
        }
    }

    private final List<CapturedEvent> events = Collections.synchronizedList(new ArrayList<>());

    private IntentCapture() {
    }

    /**
     * Installs the capture binding and init script onto the given browser context.
     *
     * @param context the Playwright browser context
     * @return the {@link IntentCapture} instance collecting events
     */
    public static IntentCapture install(BrowserContext context) {
        Objects.requireNonNull(context, "context must not be null");
        IntentCapture capture = new IntentCapture();
        context.exposeBinding(BINDING_NAME, (source, args) -> {
            if (args != null && args.length > 0 && args[0] instanceof Map<?, ?> map) {
                capture.record(map);
            }
            return null;
        });
        context.addInitScript(CAPTURE_SCRIPT);
        return capture;
    }

    private void record(Map<?, ?> map) {
        String kindStr = Objects.toString(map.get("kind"), null);
        if (kindStr == null || kindStr.isBlank()) {
            return;
        }

        CapturedEvent.EventKind kind;
        try {
            kind = CapturedEvent.EventKind.valueOf(kindStr.trim());
        } catch (IllegalArgumentException e) {
            return;
        }

        String tagName = getString(map, "tagName");
        String id = getString(map, "id");
        String testId = getString(map, "testId");
        String role = getString(map, "role");
        String accessibleName = getString(map, "accessibleName");
        String labelText = getString(map, "labelText");
        String textContent = getString(map, "textContent");
        String value = getValue(map, "value");
        String cssPath = getString(map, "cssPath");

        CapturedEvent event = new CapturedEvent(
                kind, tagName, id, testId, role,
                accessibleName, labelText, textContent, value, cssPath);
        events.add(event);
    }

    private static String getString(Map<?, ?> map, String key) {
        Object val = map.get(key);
        if (val == null) {
            return null;
        }
        String s = val.toString().trim();
        return s.isEmpty() ? null : s;
    }

    private static String getValue(Map<?, ?> map, String key) {
        Object val = map.get(key);
        if (val == null) {
            return null;
        }
        return val.toString();
    }

    /**
     * Drains and returns all captured events since the last drain in arrival order.
     *
     * @return an unmodifiable list of captured events
     */
    public synchronized List<CapturedEvent> drain() {
        List<CapturedEvent> copy = new ArrayList<>(events);
        events.clear();
        return List.copyOf(copy);
    }

    /**
     * Waits up to {@code timeout} until at least {@code minCount} events have been captured, then
     * drains and returns them. Playwright delivers {@code exposeBinding} calls to this side
     * asynchronously, so acting on the page and immediately draining can race the delivery.
     *
     * @return an unmodifiable list of captured events (may hold fewer than {@code minCount} on timeout)
     */
    public List<CapturedEvent> awaitEvents(int minCount, Duration timeout) {
        long deadlineNanos = System.nanoTime() + timeout.toNanos();
        while (events.size() < minCount && System.nanoTime() < deadlineNanos) {
            sleepBriefly();
        }
        return drain();
    }

    /**
     * Waits up to {@code timeout} until an event of the given {@code kind} has been captured, then
     * drains and returns them. Useful when a single page action raises several events (a submit
     * click raises both a click and a submit) and the caller cares about one specific kind.
     *
     * @return an unmodifiable list of captured events (possibly empty on timeout)
     */
    public List<CapturedEvent> awaitEvent(CapturedEvent.EventKind kind, Duration timeout) {
        long deadlineNanos = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadlineNanos) {
            boolean found;
            synchronized (events) {
                found = events.stream().anyMatch(e -> e.kind() == kind);
            }
            if (found) {
                return drain();
            }
            sleepBriefly();
        }
        return drain();
    }

    private static void sleepBriefly() {
        try {
            Thread.sleep(5);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
