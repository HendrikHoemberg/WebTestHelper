# Recorder Session Recovery Implementation Plan

**Goal:** Provide an explicit recovery mechanism ("Eigene Aufzeichnungen beenden" / "Alle Aufzeichnungen zurücksetzen") on the capacity-exceeded screen, fix silent `beforeunload` CSRF session drops, and prevent multi-click hangs on the recording start button.

**Architecture:** Extend `RecordingSessionRegistry` with `closeAllForUser` and `closeAll`. Expose POST endpoints in `RecorderController` (`/recorder/meine-sitzungen-beenden` and `/recorder/alle-beenden` for admins). Render action buttons inside the capacity-exceeded warning card on `record.html`. Fix `navigator.sendBeacon` encoding in `record.html` so Spring Security's `CsrfFilter` accepts unloads. Add button loading feedback in `journey/list.html` to prevent accidental multi-clicks.

**Tech Stack:** Spring Boot 4, Spring Security, Thymeleaf, Alpine.js, Playwright, JUnit 5 / AssertJ / MockMvc.

**Spec:** Issue report from user session (capacity limit hit after unhandled click delay; abandoned sessions locking worker pool).

## Global Constraints

- German-only UI; message keys `ui.recorder.*`; no internal technical identifiers in HTML.
- View tests use `@WebMvcTest` + MockMvc; assert on text and markup, not on CSS.
- Worker pool capacity untouched (2 recorder workers).
- Follow TDD: write failing test, verify failure, write minimal code, verify pass.

---

### Task 1: Registry Session Termination Methods

**Files:**
- Modify: `src/main/java/dev/hendrikhoemberg/webtesthelper/recorder/RecordingSessionRegistry.java`
- Test: `src/test/java/dev/hendrikhoemberg/webtesthelper/recorder/RecordingSessionRegistryTest.java`

**Interfaces:**
- Produces:
  - `int closeAllForUser(String username)`: closes all active sessions owned by `username` and releases their workers to the pool; returns count.
  - `int closeAll()`: closes all active sessions across all users and releases their workers to the pool; returns count.
  - `int activeSessionsForUser(String username)`: returns count of active sessions for a user.

- [x] **Step 1: Write the failing test**
  Add unit tests in `RecordingSessionRegistryTest.java`:
  - `closeAllForUserClosesOnlyMatchingSessionsAndReleasesWorkers`
  - `closeAllClosesEverySessionAndReleasesWorkers`
  - `activeSessionsForUserCountsOnlyMatchingOwner`

- [x] **Step 2: Run the single test — verify it FAILS**
  `./mvnw test -Dtest=RecordingSessionRegistryTest -B --no-transfer-progress` (fails on missing methods)

- [x] **Step 3: Write minimal implementation**
  Implement `closeAllForUser`, `closeAll`, and `activeSessionsForUser` in `RecordingSessionRegistry.java`.

- [x] **Step 4: Run the single test — verify it PASSES**
  `./mvnw test -Dtest=RecordingSessionRegistryTest -B --no-transfer-progress`

---

### Task 2: Controller Recovery Endpoints & Model Attributes

**Files:**
- Modify: `src/main/java/dev/hendrikhoemberg/webtesthelper/recorder/RecorderController.java`
- Test: `src/test/java/dev/hendrikhoemberg/webtesthelper/recorder/RecorderControllerTest.java`

**Interfaces:**
- Produces:
  - `POST /recorder/meine-sitzungen-beenden` (authenticated): calls `sessionRegistry.closeAllForUser(username)`, redirects to `/websites/{siteId}/journeys` (or `/websites/{siteId}/aufzeichnen` if requested) with a flash message.
  - `POST /recorder/alle-beenden` (requires `ROLE_ADMIN`): calls `sessionRegistry.closeAll()`, redirects with a flash message.
  - In `record(...)`: when `capacityExceeded` is true, pass `userActiveSessions` and `siteId` into the model.

- [x] **Step 1: Write the failing test**
  In `RecorderControllerTest.java`:
  - `closeMySessions_asAuthenticatedUser_closesOnlyUserSessionsAndRedirects`
  - `closeAllSessions_asAdmin_closesAllSessionsAndRedirects`
  - `closeAllSessions_asRegularUser_isForbidden`
  - `record_whenCapacityExceeded_exposesUserActiveSessionsInModel`

- [x] **Step 2: Run the single test — verify it FAILS**
  `./mvnw test -Dtest=RecorderControllerTest -B --no-transfer-progress`

- [x] **Step 3: Write minimal implementation**
  Implement the endpoints and model attributes in `RecorderController.java` and register `/recorder/alle-beenden` with admin check in `SecurityConfig.java` (or method security).

- [x] **Step 4: Run the single test — verify it PASSES**
  `./mvnw test -Dtest=RecorderControllerTest -B --no-transfer-progress`

---

### Task 3: Capacity Exceeded UI Actions & German Messages

**Files:**
- Modify: `src/main/resources/messages.properties`
- Modify: `src/main/resources/templates/journey/record.html`
- Test: `src/test/java/dev/hendrikhoemberg/webtesthelper/recorder/RecorderControllerTest.java`

**Interfaces:**
- In `record.html` within `div th:if="${capacityExceeded}"`:
  - Show recovery form `POST /recorder/meine-sitzungen-beenden` with button `ui.recorder.kapazitaet.eigene_beenden` ("Eigene Aufzeichnungen beenden").
  - Show recovery form `POST /recorder/alle-beenden` with button `ui.recorder.kapazitaet.alle_beenden` ("Alle Aufzeichnungen zurücksetzen") for administrators via `sec:authorize="hasRole('ADMIN')"`.

- [x] **Step 1: Write the failing test**
  In `RecorderControllerTest.java`:
  - Assert that when `capacityExceeded` is rendered, the HTML contains the form action `/recorder/meine-sitzungen-beenden` and message text.
  - Assert that as admin, `/recorder/alle-beenden` is present in HTML; as normal user, it is not present.

- [x] **Step 2: Run the single test — verify it FAILS**
  `./mvnw test -Dtest=RecorderControllerTest -B --no-transfer-progress`

- [x] **Step 3: Write minimal implementation**
  - Add message keys to `messages.properties`:
    - `ui.recorder.kapazitaet.eigene_beenden=Eigene Aufzeichnungen beenden`
    - `ui.recorder.kapazitaet.alle_beenden=Alle Aufzeichnungen zurücksetzen`
    - `ui.recorder.kapazitaet.erfolg_eigene=Ihre laufenden Aufzeichnungssitzungen wurden beendet.`
    - `ui.recorder.kapazitaet.erfolg_alle=Alle Aufzeichnungssitzungen wurden zurückgesetzt.`
  - Update `templates/journey/record.html` with the action buttons in the warning card.

- [x] **Step 4: Run the single test — verify it PASSES**
  `./mvnw test -Dtest=RecorderControllerTest -B --no-transfer-progress`

---

### Task 4: Fix `beforeunload` CSRF Encoding in `record.html`

**Files:**
- Modify: `src/main/resources/templates/journey/record.html`
- Test: Verify in `RecorderControllerTest.java` and manual check with `application/x-www-form-urlencoded` payload.

**Interfaces:**
- Modify client-side JavaScript in `record.html`:
  - When `navigator.sendBeacon` is called, construct a `Blob` with `type: 'application/x-www-form-urlencoded'` instead of `FormData`.
  - Spring Security's `CsrfFilter` will now parse the `_csrf` parameter properly and not reject unloads with 403.

- [x] **Step 1: Update JavaScript in `record.html`**
- [x] **Step 2: Verify `RecorderControllerTest` and run verification**

---

### Task 5: Prevent Double Clicks & Add Loading State to Start Button

**Files:**
- Modify: `src/main/resources/templates/journey/list.html`
- Modify: `src/main/resources/messages.properties`

**Interfaces:**
- In `journey/list.html`:
  - Add Alpine.js state or click handler to the "Ablauf aufzeichnen" button:
    Upon click, disable pointer events and display `ui.journey.liste.wird_vorbereitet` ("Browser wird gestartet...") to provide instant visual feedback and block duplicate in-flight requests.

- [x] **Step 1: Add message key in `messages.properties`**
- [x] **Step 2: Update `journey/list.html` button with Alpine click state**
- [x] **Step 3: Verify with fast test suite**

---

### Task 6: Full Verification

- [x] Run fast verification: `bash -c "set -o pipefail; ./mvnw test -Pfast -B --no-transfer-progress | tail -n 50"`
- [x] Run recorder tests specifically: `./mvnw test -Dtest=*Recorder* -B --no-transfer-progress`
- [x] Document verified output before completion.
