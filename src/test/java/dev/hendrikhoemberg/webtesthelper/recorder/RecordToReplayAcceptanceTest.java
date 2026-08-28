package dev.hendrikhoemberg.webtesthelper.recorder;

import com.google.gson.JsonObject;
import dev.hendrikhoemberg.webtesthelper.catalog.JourneyService;
import dev.hendrikhoemberg.webtesthelper.catalog.SiteForm;
import dev.hendrikhoemberg.webtesthelper.catalog.SiteService;
import dev.hendrikhoemberg.webtesthelper.model.JourneyDefinition;
import dev.hendrikhoemberg.webtesthelper.model.JourneyReplayResult;
import dev.hendrikhoemberg.webtesthelper.model.JourneyStep;
import dev.hendrikhoemberg.webtesthelper.model.LocatorCandidate;
import dev.hendrikhoemberg.webtesthelper.model.ReplayStatus;
import dev.hendrikhoemberg.webtesthelper.model.StepAction;
import dev.hendrikhoemberg.webtesthelper.model.StepOutcome;
import dev.hendrikhoemberg.webtesthelper.model.StepStatus;
import dev.hendrikhoemberg.webtesthelper.runner.JourneyReplayer;
import dev.hendrikhoemberg.webtesthelper.support.AbstractPostgresTest;
import dev.hendrikhoemberg.webtesthelper.support.FixtureSite;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.LockSupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Closing acceptance test for the journey recorder (§10.1, §10.2, §10.4, Task 6).
 *
 * <p>Proves end-to-end that an interactive recording session driven via CDP input events
 * produces an executable, robust {@link JourneyDefinition} with multi-candidate locators
 * and no keystroke explosion, that replays green through {@link JourneyReplayer}, and
 * that typed passwords never appear in step objects or persisted JSON.
 */
@Tag("browser")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RecordToReplayAcceptanceTest extends AbstractPostgresTest {

    @Autowired
    private SiteService siteService;

    @Autowired
    private JourneyService journeyService;

    @Autowired
    private RecordingSessionRegistry sessionRegistry;

    @Autowired
    private ScreencastBridge screencastBridge;

    @Autowired
    private RecorderSocketHandler socketHandler;

    @Autowired
    private JourneyReplayer journeyReplayer;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private static final Duration CAPTURE_BARRIER_DEADLINE = Duration.ofSeconds(5);
    private static final long POLL_PARK_NANOS = Duration.ofMillis(10).toNanos();

    private FixtureSite fixtureSite;
    private long siteId;

    /**
     * No table clearing: this class is non-{@code @Transactional} and {@code PER_CLASS}, but it
     * never reads by name — every row it touches (the site and the journeys) is created here in
     * this same {@code @BeforeAll} and read back by id. Surefire runs classes sequentially in one
     * JVM, so the rows it leaves behind can interleave with no other class; a wholesale
     * {@code DELETE FROM site} would be worse than the rows it leaves, because other classes'
     * journeys reference their sites and would trip FK violations.
     */
    @BeforeAll
    void startFixture() {
        fixtureSite = FixtureSite.start();
        siteId = siteService.create(new SiteForm(
                "Fixture Site",
                fixtureSite.url("reise/start.html"),
                100,
                3,
                Duration.ofMinutes(10),
                List.of(),
                List.of(),
                true,
                null,
                true
        ));
    }

    @AfterAll
    void stopFixture() {
        if (fixtureSite != null) {
            fixtureSite.close();
        }
    }

    @Test
    void recordedJourneyReplaysGreenWithMultipleLocatorCandidatesAndNoKeystrokeExplosion() throws Exception {
        String startUrl = fixtureSite.url("reise/wacklig.html");
        RecordingSession session = sessionRegistry.open(siteId, startUrl, "alice");
        WebSocketSession wsSession = createMockWebSocketSession("ws-acceptance-session", session);
        socketHandler.afterConnectionEstablished(wsSession);

        try {
            // 1. Click start link on the wobbly fixture page (no id — CSS path is structural)
            clickElement(session, wsSession, "[data-testid=reise-start]");

            // Wait for navigation to schritt2.html
            session.worker().submit(browser -> {
                session.page().waitForURL("**/reise/schritt2.html");
                return null;
            });

            // 2. Fill name field
            clickElement(session, wsSession, "#reise-name");
            typeString(wsSession, "Erika Mustermann");

            // 3. Select destination option ("paris")
            clickElement(session, wsSession, "#reise-ziel");
            typeKey(wsSession, "ArrowDown", 40);

            // 4. Fill required email field
            clickElement(session, wsSession, "input[type=email]");
            typeString(wsSession, "erika@example.com");

            // 5. Click submit button
            clickElement(session, wsSession, "#reise-submit");

            // Wait for navigation to ziel.html
            session.worker().submit(browser -> {
                session.page().waitForURL("**/reise/ziel.html*");
                return null;
            });

            // Drain captured events and build steps
            List<CapturedEvent> events = session.intentCapture().drain();
            List<JourneyStep> steps = StepBuilder.build(events, startUrl);

            // Assert (a): Exactly one GOTO (step 0) and one step per user action, no keystroke steps
            assertThat(steps).as("Journey steps built from recording").hasSize(6);
            assertThat(steps.get(0).action()).isEqualTo(StepAction.GOTO);
            assertThat(steps.get(0).value()).isEqualTo(startUrl);

            assertThat(steps.get(1).action()).isEqualTo(StepAction.CLICK);
            assertThat(steps.get(2).action()).isEqualTo(StepAction.FILL);
            assertThat(steps.get(2).value()).isEqualTo("Erika Mustermann");
            assertThat(steps.get(3).action()).isEqualTo(StepAction.SELECT);
            assertThat(steps.get(3).value()).isEqualTo("paris");
            assertThat(steps.get(4).action()).isEqualTo(StepAction.FILL);
            assertThat(steps.get(4).value()).isEqualTo("erika@example.com");
            assertThat(steps.get(5).action()).isEqualTo(StepAction.CLICK);

            // Dense sequential ordinals
            assertThat(steps.stream().map(JourneyStep::ordinal).toList())
                    .containsExactly(0, 1, 2, 3, 4, 5);

            // Assert (b): Each non-GOTO step carries at least two locator candidates
            for (int i = 1; i < steps.size(); i++) {
                JourneyStep step = steps.get(i);
                assertThat(step.locatorCandidates())
                        .as("Step %d (%s) must carry at least 2 candidates", step.ordinal(), step.action())
                        .hasSizeGreaterThanOrEqualTo(2);
            }

            // Save session as journey in catalog
            long journeyId = journeyService.create(siteId, "Aufgenommene Reise", steps);
            JourneyDefinition definition = journeyService.findDefinition(journeyId).orElseThrow();

            // Assert (c): JourneyReplayer.replay on the saved journey returns PASSED
            JourneyReplayResult replayResult = journeyReplayer.replay(definition, siteService.contextFor(siteId), null);

            assertThat(replayResult.status()).isEqualTo(ReplayStatus.PASSED);
            assertThat(replayResult.driftCount()).isZero();
            assertThat(replayResult.outcomes()).hasSize(6);

            for (StepOutcome outcome : replayResult.outcomes()) {
                assertThat(outcome.status()).isEqualTo(StepStatus.PASSED);
                assertThat(outcome.drifted()).isFalse();
                assertThat(outcome.failureMessageKey()).isNull();
            }
        } finally {
            socketHandler.afterConnectionClosed(wsSession, CloseStatus.NORMAL);
            sessionRegistry.close(session.sessionId());
        }
    }

    @Test
    void typedPasswordAppearsNowhereInBuiltStepsOrPersistedJson() throws Exception {
        String formUrl = fixtureSite.url("interaktiv/formular-viele.html");
        RecordingSession session = sessionRegistry.open(siteId, formUrl, "alice");
        WebSocketSession wsSession = createMockWebSocketSession("ws-password-session", session);
        socketHandler.afterConnectionEstablished(wsSession);

        String plaintextPassword = "SuperSecretPassword!2026";

        try {
            // Click into password input
            clickElement(session, wsSession, "#password");

            // Type secret password
            typeString(wsSession, plaintextPassword);

            // Deterministic barrier: the keystroke dispatch runs synchronously on the worker, but
            // the captured events only reach Java via Playwright's exposeBinding callback on its
            // reader thread. Unlike the journey test there is no submit/`waitForURL` to join on, so
            // drain until capture has actually observed an INPUT (or fail loudly after the deadline
            // rather than silently build an empty journey).
            List<CapturedEvent> events = drainUntilInputObserved(session.intentCapture());
            List<JourneyStep> steps = StepBuilder.build(events, formUrl);

            // Anti-vacuity: the typing must have produced exactly one (redacted) FILL step, so a
            // drain-before-arrival break or a redaction break both fail this test.
            List<JourneyStep> fillSteps = steps.stream()
                    .filter(step -> step.action() == StepAction.FILL)
                    .toList();
            assertThat(fillSteps)
                    .as("Password typing must produce exactly one FILL step, but produced %d", fillSteps.size())
                    .hasSize(1);
            assertThat(fillSteps.get(0).value())
                    .as("Password step value must be redacted to empty")
                    .isEmpty();

            // Assert: Plaintext password appears nowhere in step objects
            assertThat(steps).isNotEmpty();
            for (JourneyStep step : steps) {
                assertThat(step.value()).doesNotContain(plaintextPassword);
                assertThat(step.toString()).doesNotContain(plaintextPassword);
                for (LocatorCandidate candidate : step.locatorCandidates()) {
                    assertThat(candidate.value()).doesNotContain(plaintextPassword);
                }
            }

            // Persist the journey to database
            long pwdJourneyId = journeyService.create(siteId, "Passwort Test", steps);

            // Assert: Plaintext password appears nowhere in the persisted JSON row
            String rawJson = jdbcTemplate.queryForObject(
                    "SELECT steps FROM journey WHERE id = ?", String.class, pwdJourneyId);
            assertThat(rawJson).isNotNull();
            assertThat(rawJson).doesNotContain(plaintextPassword);
        } finally {
            socketHandler.afterConnectionClosed(wsSession, CloseStatus.NORMAL);
            sessionRegistry.close(session.sessionId());
        }
    }

    /**
     * Drains captured events until capture has reported an {@code INPUT}, then returns everything
     * accumulated so far in arrival order.
     *
     * <p>{@link IntentCapture#drain()} is synchronized and clears the list it returns, so the only
     * safe way to wait for the reader-thread callback to deliver an event is to keep re-draining
     * into one accumulation list. Deadline-based with a short, bounded pause; fails rather than
     * returning an empty capture if the typing was never observed.
     */
    private List<CapturedEvent> drainUntilInputObserved(IntentCapture capture) {
        long deadline = System.nanoTime() + CAPTURE_BARRIER_DEADLINE.toNanos();
        List<CapturedEvent> accumulated = new ArrayList<>();
        while (System.nanoTime() < deadline) {
            accumulated.addAll(capture.drain());
            if (accumulated.stream().anyMatch(event -> event.kind() == CapturedEvent.EventKind.INPUT)) {
                return accumulated;
            }
            LockSupport.parkNanos(POLL_PARK_NANOS);
        }
        throw new AssertionError("Aufnahme hat die Eingabe nie beobachtet: kein INPUT-Event innerhalb "
                + "von " + CAPTURE_BARRIER_DEADLINE.toSeconds() + " s — typing was not captured");
    }

    private void clickElement(RecordingSession session, WebSocketSession wsSession, String selector) throws Exception {
        var box = session.worker().submit(browser -> {
            var locator = session.page().locator(selector);
            locator.scrollIntoViewIfNeeded();
            return locator.boundingBox();
        });
        assertThat(box).as("Bounding box for %s", selector).isNotNull();

        CanvasGeometry geometry = new CanvasGeometry(1280, 720, 1280, 720, 1.0, 0.0);
        JsonObject clickMsg = new JsonObject();
        clickMsg.addProperty("type", "click");
        clickMsg.addProperty("canvasX", box.x + box.width / 2.0);
        clickMsg.addProperty("canvasY", box.y + box.height / 2.0);
        JsonObject g = new JsonObject();
        g.addProperty("canvasWidth", geometry.canvasWidth());
        g.addProperty("canvasHeight", geometry.canvasHeight());
        g.addProperty("frameWidth", geometry.frameWidth());
        g.addProperty("frameHeight", geometry.frameHeight());
        g.addProperty("pageScaleFactor", geometry.pageScaleFactor());
        g.addProperty("offsetTop", geometry.offsetTop());
        clickMsg.add("geometry", g);

        socketHandler.handleTextMessage(wsSession, new TextMessage(clickMsg.toString()));
    }

    private void typeString(WebSocketSession wsSession, String text) throws Exception {
        for (char ch : text.toCharArray()) {
            JsonObject keyMsg = new JsonObject();
            keyMsg.addProperty("type", "key");
            keyMsg.addProperty("key", String.valueOf(ch));
            keyMsg.addProperty("text", String.valueOf(ch));
            socketHandler.handleTextMessage(wsSession, new TextMessage(keyMsg.toString()));
        }
    }

    private void typeKey(WebSocketSession wsSession, String key, int windowsVirtualKeyCode) throws Exception {
        JsonObject keyMsg = new JsonObject();
        keyMsg.addProperty("type", "key");
        keyMsg.addProperty("key", key);
        keyMsg.addProperty("code", key);
        if (windowsVirtualKeyCode > 0) {
            keyMsg.addProperty("windowsVirtualKeyCode", windowsVirtualKeyCode);
        }
        socketHandler.handleTextMessage(wsSession, new TextMessage(keyMsg.toString()));
    }

    private WebSocketSession createMockWebSocketSession(String id, RecordingSession session) {
        WebSocketSession wsSession = mock(WebSocketSession.class);
        when(wsSession.getId()).thenReturn(id);
        when(wsSession.isOpen()).thenReturn(true);
        Map<String, Object> attrs = new HashMap<>();
        attrs.put(RecorderHandshakeInterceptor.RECORDING_SESSION_ATTR, session);
        when(wsSession.getAttributes()).thenReturn(attrs);
        return wsSession;
    }
}
