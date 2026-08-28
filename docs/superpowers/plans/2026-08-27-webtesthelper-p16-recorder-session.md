# Plan 16 — The recorder session and its live view

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development
> (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use
> checkbox (`- [ ]`) syntax for tracking.

**Goal:** An employee opens "Aufzeichnen", gets a live picture of a real Chromium session in a
`<canvas>`, and can click and type into it. No steps are captured yet — that is plan 17.

**Architecture:** A `RecordingSession` owns one Chromium context on its own single-thread executor,
separate from the crawl's `BrowserPool` (D109). A CDP session streams JPEG frames out over a
WebSocket and mouse and key events come back over the same socket. The session is a
**server-held resource with an idle timeout**, not a request.

**Spec:** `docs/superpowers/specs/2026-08-21-webtesthelper-design.md` — §10.1, §10.5.
**Roadmap:** `2026-08-27-webtesthelper-phase-4-roadmap.md` — **read its spike section before Step 1
of Task 4.** It contains six measured facts about CDP screencast in this exact Playwright version,
including the one that decides this plan's design (frames are change-driven) and one silent failure
mode that will otherwise cost a debugging session. Deviations D109 and D110 are argued there.

**Depends on:** plan 15. **New dependency:** `spring-boot-starter-websocket` — the only one in
Phase 4, confirmed to resolve at Boot 4.1.1.

> ⚠ **Written before plans 14 and 15 executed.** Re-read against the tree and reconcile with their
> Execution findings before starting.

**§10.5 bounds this plan and it is not reopened:** a single tab, no file uploads, no downloads, no
drag-and-drop.

---

## File structure

| File | Responsibility |
|---|---|
| `pom.xml` | +`spring-boot-starter-websocket` |
| `recorder/RecorderPool.java` | Two single-thread Chromium workers, independent of `BrowserPool` |
| `recorder/RecorderWorker.java` | One such worker: its thread, its `Playwright`, its `Browser` |
| `recorder/RecordingSession.java` | One session: context, page, CDP session, last-activity clock |
| `recorder/RecordingSessionRegistry.java` | Allocation, the two-session cap, idle reaping |
| `recorder/RecorderProperties.java` | Idle timeout, max sessions, frame quality, viewport |
| `recorder/ScreencastBridge.java` | CDP frames → socket; the on-attach frame (D110) |
| `recorder/InputTranslator.java` | Canvas coordinates → viewport coordinates |
| `recorder/RecorderSocketHandler.java` | The WebSocket endpoint |
| `recorder/WebSocketConfig.java` | Registration |
| `web/SecurityConfig.java` | The socket path is authenticated |
| `templates/journey/record.html` | The canvas screen |

A **new module**, `recorder`, rather than more classes in `runner`. It owns a browser, a socket and
a session clock, none of which any other module has; and §5.1's module graph is verified by Spring
Modulith, so the boundary is enforced rather than intended. Add its `package-info.java` allowing
`model` and `catalog` only.

---

### Task 1: A browser the crawler cannot lose

**Files:** Modify `pom.xml`. Create `recorder/package-info.java`, `RecorderPool.java`,
`RecorderProperties.java`. Test: `…/recorder/RecorderPoolTest.java` — `@Tag("browser")`.

**Produces:** `RecorderPool.allocate()` → `Optional<RecorderWorker>`; `RecorderWorker.submit(…)`
with the same thread-confinement contract as `BrowserPool` — a `Page` may not leave the task.

- [ ] **Step 1: Write the failing tests.** Two allocations succeed; the third returns
  `Optional.empty()` rather than blocking — §10.1's *"two concurrent sessions maximum"* is a cap the
  user is told about, not a queue they wait in. Releasing one makes a third succeed. Every Playwright
  object is created and used on the worker's own thread.

- [ ] **Step 2: Run and watch them fail.**

- [ ] **Step 3: Implement.** Copy `BrowserPool`'s structure — platform threads, one `Playwright` per
  worker, created on the worker thread. **Do not extend or reuse `BrowserPool`:** D109. Its
  `submit` blocks on an `ArrayBlockingQueue` until a worker frees up, which is right for a crawl
  task measured in seconds and catastrophic for a session measured in fifteen minutes. A recorder
  session holding a crawl worker would stall every run on the box.

- [ ] **Step 4: Run to green,** then full `./mvnw test` (Modulith boundary verification runs there).

- [ ] **Step 5: Commit.** `feat(recorder): the recorder gets its own browsers, not the crawler's`

---

### Task 2: A session that ends by itself

**Files:** Create `recorder/RecordingSession.java`, `RecordingSessionRegistry.java`. Test:
`…/recorder/RecordingSessionRegistryTest.java` (`-Pfast`, with a stub pool and an injected clock).

**Produces:** `RecordingSessionRegistry.open(long siteId, String startUrl, String username)` →
`RecordingSession`; `find(UUID sessionId, String username)` → `Optional<RecordingSession>`;
`close(UUID)`; `reapIdle()`.

- [ ] **Step 1: Write the failing tests.** A session is addressed by a **random `UUID`**, never a
  sequential id. `find` with the wrong username returns empty — ownership is checked at lookup, not
  at the socket, so there is one place to get it right. A session idle past
  `RecorderProperties.idleTimeout` (default **15 minutes**, §10.1) is reaped by `reapIdle()` and its
  worker returned to the pool; one with recent activity is not. Reaping a session **releases its
  worker even if closing its Chromium context throws** — otherwise one crashed session permanently
  costs half the recorder's capacity.

- [ ] **Step 2: Run and watch them fail.**

- [ ] **Step 3: Implement.** Activity is stamped on every input and on every frame ack. Drive
  `reapIdle` from a scheduled job, following `MuteExpiryJob`.

- [ ] **Step 4: Run to green.**

- [ ] **Step 5: Commit.** `feat(recorder): a forgotten recording session lets go of its browser`

---

### Task 3: The socket, and who is allowed on it

**Files:** Create `recorder/RecorderSocketHandler.java`, `WebSocketConfig.java`. Modify
`web/SecurityConfig.java`. Test: `…/recorder/RecorderSocketSecurityTest.java` (`-Pfast`).

**This task settles the roadmap's open question and must be done before any frame is sent.** The
socket carries a live view of a customer's site inside an authenticated session, and plan 17 will
let a user type a password into it.

- [ ] **Step 1: Write the failing tests.** An **unauthenticated** handshake is rejected at the
  handshake, not after the first frame. An authenticated user who does **not** own the session id is
  rejected — reuse Task 2's `find(UUID, String)` so there is one ownership rule. A valid handshake
  for an existing owned session is accepted. A handshake naming an unknown session id is rejected
  without disclosing whether it ever existed.

- [ ] **Step 2: Run and watch them fail.**

- [ ] **Step 3: Implement.** A plain `WebSocketHandler` — no STOMP, no SockJS, no message broker;
  this is one socket carrying frames one way and input the other. Register its path under the same
  `authenticated()` rule as the rest of the app in `SecurityConfig`; a `WebSocketHandler` is **not**
  covered by the existing filter chain by default, which is precisely the mistake this task exists
  to not make.

- [ ] **Step 4: Run to green.**

- [ ] **Step 5: Commit.** `feat(recorder): nobody watches a recording session but the person who
  started it`

---

### Task 4: Frames

**Files:** Create `recorder/ScreencastBridge.java`. Test: `…/recorder/ScreencastBridgeTest.java` —
`@Tag("browser")`, one session for the class.

**Read the roadmap's spike table first.** Screencast is change-driven: a static page emits exactly
one frame, forever. Frames are ~24 KB of base64 JPEG at `quality: 60`.

**Produces:** `ScreencastBridge.attach(RecordingSession, FrameSink)`, `detach(…)`; `FrameSink` is a
one-method interface so the test can collect frames without a socket.

- [ ] **Step 1: Write the failing tests.**
  (a) Attaching to a session on a **static** page delivers **at least one frame within a second** —
  the on-attach `Page.captureScreenshot` of D110. Without it a viewer of an idle page sees nothing
  and cannot tell the feature from a broken socket. This is the assertion the spike bought.
  (b) A visual change on the page delivers further frames.
  (c) Every delivered frame is acknowledged with `Page.screencastFrameAck` carrying that frame's
  `sessionId` — unacknowledged frames stop the stream, so assert the ack count matches.
  (d) `detach` stops the screencast and delivers no further frames.

- [ ] **Step 2: Run and watch them fail.**

- [ ] **Step 3: Implement.** `format: "jpeg"`, quality and max dimensions from `RecorderProperties`.
  All CDP calls go through the session's worker thread — Playwright's thread confinement covers
  `CDPSession` too.

- [ ] **Step 4: Run to green.**

- [ ] **Step 5: Commit.** `feat(recorder): show the page as it is now, then as it changes`

**Measure before choosing the defaults.** The roadmap leaves `everyNthFrame` and `quality` open with
a reason: 24 KB per frame is a real byte budget once a user is typing. During Step 4, drive the
fixture flow for ten seconds of realistic interaction, count frames, and record frames-per-second
and total bytes in Execution findings. Choose the constants from that measurement and say what it
was — like the soft-404 cutoff of 16, this number cannot be recovered by reading the code.

---

### Task 5: Clicks that land where they were aimed

**Files:** Create `recorder/InputTranslator.java`. Modify `RecorderSocketHandler`. Test:
`…/recorder/InputTranslatorTest.java` (`-Pfast`) and `…/recorder/RecorderInputTest.java`
(`@Tag("browser")`).

**Produces:** `InputTranslator.toViewport(double canvasX, double canvasY, CanvasGeometry geometry)`
→ `ViewportPoint`; `CanvasGeometry(int canvasWidth, int canvasHeight, int frameWidth, int
frameHeight, double pageScaleFactor, double offsetTop, double scrollOffsetX, double scrollOffsetY)`.

Coordinate translation is the one algorithm in this plan a competent developer would not write the
same way twice, so it is written out. The frame the user clicked is not the viewport: it has its own
pixel size, the canvas displays it at a third size, and the screencast metadata carries a page scale
and scroll offset that a naive `x * width / canvasWidth` ignores.

```
scale   = frameWidth / canvasWidth          // canvas is displayed at an arbitrary CSS size
frameX  = canvasX * scale
frameY  = canvasY * (frameHeight / canvasHeight)
// screencastFrame metadata is in CSS pixels of the page, already scrolled:
viewportX = frameX / pageScaleFactor + scrollOffsetX
viewportY = (frameY - offsetTop) / pageScaleFactor + scrollOffsetY
```

- [ ] **Step 1: Write the failing tests.** Pure arithmetic first: a click at the canvas centre maps
  to the viewport centre at scale 1; a canvas displayed at half the frame's size doubles the
  coordinate; a non-zero `scrollOffsetY` shifts the result by exactly that much; `offsetTop` is
  subtracted **before** the scale divide, not after — the two orders differ whenever both are
  non-zero, which is the bug this test exists to catch. Then the browser test: a click dispatched at
  a computed point lands on the fixture's button, proved by the resulting navigation.

- [ ] **Step 2: Run and watch them fail.**

- [ ] **Step 3: Implement.** `Input.dispatchMouseEvent` needs `type`, `x`, `y`, `button`,
  **`buttons`** and `clickCount`; the spike found that omitting `buttons` delivers nothing and
  raises no error. Keys go through `Input.dispatchKeyEvent`. Per §10.5, no drag: `mousePressed` and
  `mouseReleased` at one point.

- [ ] **Step 4: Run to green.**

- [ ] **Step 5: Commit.** `feat(recorder): a click on the canvas is a click on the page`

---

### Task 6: The screen

**Files:** Create `templates/journey/record.html`, `recorder/RecorderController.java`. Modify
`messages.properties`, `help/reisen.md`. Test: `…/recorder/RecorderControllerTest.java` (`-Pfast`).

- [ ] **Step 1: Write the failing tests.** Starting a recording for a site opens a session and
  renders the canvas screen. When two sessions are already open, the screen says so in German and
  offers no third — §13.4: the consequence is stated, not discovered. Leaving the screen closes the
  session rather than waiting fifteen minutes for the reaper. Unauthenticated access redirects.

- [ ] **Step 2: Run and watch them fail.**

- [ ] **Step 3: Implement.** Vendored JS only, per §12 — no CDN. The client draws each frame to the
  canvas and posts mouse and key events back with the canvas geometry Task 5 needs.

- [ ] **Step 4: Run to green,** then full `./mvnw test`.

- [ ] **Step 5: Commit.** `feat(recorder): open a page and watch it in the browser you are already
  in`

---

### Task 7: One session, end to end

**Files:** Create `…/recorder/RecordingSessionAcceptanceTest.java` — `@Tag("browser")`, one session
for the class.

- [ ] **Step 1: Write the failing acceptance test.** Open a session on the fixture's `reise/start.html`,
  attach a frame sink, receive the on-attach frame, dispatch a click on the link, observe the page
  navigate to `schritt2.html`, and close the session with the worker returned to the pool. Then: a
  session left idle past a shortened timeout is reaped and its worker returned.

- [ ] **Step 2: Run and watch it fail.**

- [ ] **Step 3: Implement whatever it exposes.**

- [ ] **Step 4: Run to green,** then full `./mvnw test`.

- [ ] **Step 5: Commit.** `test(recorder): a recording session shows a page, takes a click, and
  goes away`

---

## Self-review checklist for the executing agent

- `./mvnw test` green.
- **`RecordingSessionRegistryTest` fails if the idle reaper is disabled**, and Task 1's cap test
  fails if the third allocation is allowed to block. Both are resource leaks that never surface as a
  test failure on their own.
- No recorder class touches `BrowserPool`: `git grep BrowserPool -- src/main/java/…/recorder`
  returns nothing.
- Task 4's measured frame rate and byte total are in Execution findings, with the chosen `quality`
  and `everyNthFrame`.

## Execution findings

### Task 4: frame rate and bandwidth

**These numbers replace a first set that measured the test harness.** The original measurement
polled a queue with a 200 ms timeout inside its own drive loop, so it could not observe more than
five frames a second whatever the browser did; the "~5.10 fps / ~150 KB/s" it recorded was that
poll, not the screencast. `CLAUDE.md` calls the measured constants the one thing a plan cannot
regenerate by reading the code, which is exactly why a wrong one is worse than none. Re-measured
with a counting sink against `reise/lang.html`, 10 s of continuous typing and scrolling:

| `pumpInterval` | frames/s | KB/s | idle page |
|---|---|---|---|
| 25 ms | 26.99 | 286 | 0 frames in 3 s |
| **100 ms (default)** | **9.77** | **103** | 0 frames in 3 s |
| 400 ms | 2.45 | 26 | 0 frames in 3 s |

- **The frame rate is exactly `1 / pumpInterval`, and that is the finding.** An unacknowledged
  frame stops the stream, and the ack is sent from inside the CDP dispatch — which only runs
  during a pump — so each pump releases exactly one frame. `pumpInterval` is therefore the frame
  rate and the byte budget in one knob. `everyNthFrame` is not: it drops change-driven frames
  rather than pacing them, and stays at 1.
- **Average frame: 10.6 KB base64** on this fixture, against the roadmap spike's ~24 KB on a
  richer page. Frame size is content-dependent; budget against the spike's number, not this one.
  At 24 KB a real site costs ~240 KB/s at the default while someone is actively typing.
- **Idle costs nothing.** Change-driven screencast plus D110's on-attach screenshot means an
  untouched page emits zero frames and still renders immediately.
- **Chosen defaults:** `pumpInterval` 100 ms (≈10 fps, ~100–240 KB/s busy, ≤100 ms input latency),
  `quality` 60, `everyNthFrame` 1, `maxWidth` 1280, `maxHeight` 720, `format` "jpeg".

### The live view needed a pump, and no test caught that it did not have one

playwright-java has no dispatcher thread: `Connection.processOneMessage()` runs only while the
owning thread is inside a Playwright call. A CDP listener on an idle worker therefore never fires.
Measured against a page repainting five times a second: **zero frames in three seconds** of an idle
worker, and a single API call releasing **exactly one**.

Shipped, that meant the canvas repainted once per user input, showing the page as it was *before*
that input, and never showed anything asynchronous — the "is the socket broken?" experience D110
exists to prevent, moved one step downstream. Every frame test was green because each was bracketed
by a pumping call on the worker. A test that expects a frame must first prove nothing on the Java
side is causing it.

### `Input.dispatchMouseEvent` takes viewport coordinates, so scroll offset must not be added

The plan's translation formula added `scrollOffsetX/Y`. Measured against `reise/lang.html` scrolled
to y=1500: dispatching at the link's viewport position hits it, dispatching at its document
position hits nothing and raises no error. A screencast frame shows the visual viewport and the
dispatch consumes viewport CSS pixels — the same space. `CanvasGeometry` lost both fields.

This survived review because every test ran at scroll 0, and it stayed hidden one round longer
because the on-attach frame fabricated its metadata as zeroes instead of reading
`Page.getLayoutMetrics`. Two defects agreeing with each other looked like one passing test.

`offsetTop` and `pageScaleFactor` are kept and unverified: they are mobile-emulation chrome and
pinch zoom, neither of which §10.5's single-tab desktop recorder can produce.

### Capacity and ownership

- `open()` wrapped a failed Chromium start in the same `IllegalStateException` as a full pool, so
  the screen advised waiting for a colleague to finish — advice that never comes true. Capacity is
  now its own exception type carrying the configured limit.
- Closing a session bypassed `find(UUID, String)` entirely when `siteId` was supplied, which the
  template always did. Task 3's "one place to get ownership right" only holds if every path goes
  through it; a second path had been added underneath it.
