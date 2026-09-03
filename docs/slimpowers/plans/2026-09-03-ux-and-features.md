# UX Improvements & Feature Expansion Implementation Plan

**Goal:** Eliminate technical jargon across journeys and baseline workflows, provide live recorder step feedback and manual step creation, fix outbox permission and workflow dead-ends, visually document journey failures with screenshots, provide push-button PDF report downloads, and integrate centralized Slack/Teams webhooks with complete help documentation.

**Architecture:** Spring Boot modular monolith adhering to Clean Architecture principles. UI using Thymeleaf + Alpine.js + CSS variables. Playwright headless Chromium for live recording and server-side PDF generation. Java HTTP client for Slack Block Kit webhook dispatching.

**Tech Stack:** Java 25, Spring Boot 4.1.1, Thymeleaf, Spring Security, Playwright 1.62.0, Alpine.js, PostgreSQL/Flyway.

**Spec:** [`docs/slimpowers/specs/2026-09-03-ux-and-features-design.md`](file:///home/hendrik/Documents/Coding/WebTestHelper/docs/slimpowers/specs/2026-09-03-ux-and-features-design.md)

## Global Constraints
- German-only UI; message keys `ui.*`; no internal identifiers or raw enum names in rendered HTML.
- View tests: `@WebMvcTest` + MockMvc; assertions on text/markup, not on CSS.
- Recorder worker pool sizes (0/2/4) untouched.
- Desktop-only UI; no mobile breakpoints.
- Context hygiene: always use `-B --no-transfer-progress` and pipe with `tail` on test runs.

---

### Task 1: Jargon & Terminology Cleanup (Section 2.A)

**Files:**
- Modify: `src/main/resources/messages.properties`
- Modify: `src/main/resources/templates/journey/detail.html`
- Modify: `src/main/resources/templates/journey/edit.html`
- Modify: `src/main/resources/templates/laeufe/detail.html`
- Modify: `src/main/resources/templates/fragments/kacheln.html`
- Modify: `src/main/resources/templates/websites/detail.html`
- Test: `src/test/java/dev/hendrikhoemberg/webtesthelper/web/JourneyControllerTest.java`
- Test: `src/test/java/dev/hendrikhoemberg/webtesthelper/web/RunControllerTest.java`

**Interfaces:**
- Consumes: Existing journey and run view models.
- Produces: Clear, human-readable German labels for step actions, timeouts in seconds, rephrased "Ausgangsbestand" as "Aktuelle Mängel als bekannt markieren", and unified 3-tier run scope taxonomy.

- [ ] **Step 1: Write the failing test**
  In `JourneyControllerTest.java`, assert that journey detail HTML renders friendly German step action names (e.g. "Seite aufrufen", "Element anklicken") instead of raw `GOTO` or `CLICK`, formats timeout as seconds, and renders friendly drift explanation. In `RunControllerTest.java`, assert baseline button renders "Aktuelle Mängel als bekannt markieren".
- [ ] **Step 2: Run the single test — verify it FAILS**
  `bash -c "set -o pipefail; ./mvnw test -Dtest=JourneyControllerTest -Pfast -B --no-transfer-progress | tail -n 30"`
- [ ] **Step 3: Write minimal implementation**
  Update `messages.properties` with friendly action labels (`ui.journey.action.GOTO=Seite aufrufen`, `ui.journey.action.CLICK=Element anklicken`, `ui.journey.action.FILL=Text eingeben`, etc.), timeout format, baseline keys (`ui.lauf.ausgangsbestand.button=Aktuelle Mängel als bekannt markieren`, `ui.uebersicht.altlasten={0} als bekannt markiert`), and scope keys. Update `journey/detail.html`, `journey/edit.html`, `laeufe/detail.html`, and `fragments/kacheln.html`.
- [ ] **Step 4: Run the single test — verify it PASSES**
  `bash -c "set -o pipefail; ./mvnw test -Dtest=JourneyControllerTest,RunControllerTest -Pfast -B --no-transfer-progress | tail -n 30"`
- [ ] **Step 5: Commit**
  `git commit -m "feat(ui): eliminate technical jargon in journeys, baseline, and run scopes"`

---

### Task 2: Manual Step Addition in Journey Editor (Section 2.B)

**Files:**
- Modify: `src/main/java/dev/hendrikhoemberg/webtesthelper/web/JourneyEditController.java`
- Modify: `src/main/resources/templates/journey/edit.html`
- Modify: `src/main/resources/messages.properties`
- Test: `src/test/java/dev/hendrikhoemberg/webtesthelper/web/JourneyEditControllerTest.java`

**Interfaces:**
- Consumes: `JourneyEditForm` with dynamically submitted new step items without IDs.
- Produces: `JourneyStep` creation with assigned `UUID.randomUUID()` and locator candidate for new steps; client-side "+ Schritt hinzufügen" button.

- [ ] **Step 1: Write the failing test**
  In `JourneyEditControllerTest.java`, add a test `submittingNewStepWithoutId_createsAndPersistsStepWithGeneratedUuid()` submitting a form where a `StepEditItem` has `id = null`, `action = StepAction.CLICK`, and a locator candidate or value. Verify it is saved with a newly generated UUID and dense ordinals.
- [ ] **Step 2: Run the single test — verify it FAILS**
  `bash -c "set -o pipefail; ./mvnw test -Dtest=JourneyEditControllerTest#submittingNewStepWithoutId_createsAndPersistsStepWithGeneratedUuid -Pfast -B --no-transfer-progress | tail -n 30"`
- [ ] **Step 3: Write minimal implementation**
  Update `JourneyEditController.java` to generate a `UUID.randomUUID()` when `item.getId() == null`. Support locator candidate input from form. Add template HTML and JavaScript `addStep()` function in `journey/edit.html` with button `+ Weiteren Schritt hinzufügen`.
- [ ] **Step 4: Run the single test — verify it PASSES**
  `bash -c "set -o pipefail; ./mvnw test -Dtest=JourneyEditControllerTest -Pfast -B --no-transfer-progress | tail -n 30"`
- [ ] **Step 5: Commit**
  `git commit -m "feat(journey): allow manual step addition in journey editor"`

---

### Task 3: Live Step Feedback in Journey Recorder (Section 2.B)

**Files:**
- Modify: `src/main/java/dev/hendrikhoemberg/webtesthelper/recorder/IntentCapture.java`
- Modify: `src/main/java/dev/hendrikhoemberg/webtesthelper/recorder/RecorderSocketHandler.java`
- Modify: `src/main/resources/templates/journey/record.html`
- Modify: `src/main/resources/messages.properties`
- Test: `src/test/java/dev/hendrikhoemberg/webtesthelper/recorder/IntentCaptureTest.java`
- Test: `src/test/java/dev/hendrikhoemberg/webtesthelper/recorder/RecorderInputTest.java`

**Interfaces:**
- Consumes: `CapturedEvent` from browser execution.
- Produces: WebSocket message `{"type":"step_captured", ...}` streamed to browser; live step sidebar in `record.html`.

- [ ] **Step 1: Write the failing test**
  Add test in `IntentCaptureTest.java` / `RecorderInputTest.java` verifying that an event listener callback is invoked when an event is recorded by `IntentCapture`.
- [ ] **Step 2: Run the single test — verify it FAILS**
  `bash -c "set -o pipefail; ./mvnw test -Dtest=IntentCaptureTest -Pfast -B --no-transfer-progress | tail -n 30"`
- [ ] **Step 3: Write minimal implementation**
  Add listener registration `IntentCapture.onEvent(Consumer<CapturedEvent>)`. In `RecorderSocketHandler.java`, subscribe when establishing WebSocket connection and broadcast JSON message `{"type":"step_captured", "action":"...", "summary":"..."}`. In `record.html`, add sidebar with step list and append step on WebSocket message.
- [ ] **Step 4: Run the single test — verify it PASSES**
  `bash -c "set -o pipefail; ./mvnw test -Dtest=IntentCaptureTest,RecorderInputTest -Pfast -B --no-transfer-progress | tail -n 30"`
- [ ] **Step 5: Commit**
  `git commit -m "feat(recorder): stream live step confirmation to recorder UI via websocket"`

---

### Task 4: Permission Guard & Interactive Outbox Management (Section 2.C)

**Files:**
- Modify: `src/main/resources/templates/layout.html`
- Modify: `src/main/java/dev/hendrikhoemberg/webtesthelper/reporting/OutboxService.java`
- Modify: `src/main/java/dev/hendrikhoemberg/webtesthelper/web/OutboxController.java`
- Modify: `src/main/resources/templates/postausgang/index.html`
- Modify: `src/main/resources/messages.properties`
- Test: `src/test/java/dev/hendrikhoemberg/webtesthelper/web/OutboxControllerTest.java`
- Test: `src/test/java/dev/hendrikhoemberg/webtesthelper/reporting/OutboxServiceTest.java`

**Interfaces:**
- Consumes: Outbox persistence entities.
- Produces: `retry(id)`, `retryAllFailed()`, `delete(id)`, `deleteAllFailed()`, detail inspection, and admin-only banner rendering in `layout.html`.

- [ ] **Step 1: Write the failing test**
  In `OutboxControllerTest.java`, add tests for `POST /postausgang/{id}/wiederholen`, `POST /postausgang/alle-wiederholen`, `POST /postausgang/{id}/loeschen`, and `POST /postausgang/alle-loeschen`. Add test verifying non-admin users do not see mail failure banner in layout.
- [ ] **Step 2: Run the single test — verify it FAILS**
  `bash -c "set -o pipefail; ./mvnw test -Dtest=OutboxControllerTest -Pfast -B --no-transfer-progress | tail -n 30"`
- [ ] **Step 3: Write minimal implementation**
  Add `sec:authorize="hasRole('ADMIN')"` to mail failure banner in `layout.html`. In `OutboxService.java`, add `retry(id)`, `retryAllFailed()`, `delete(id)`, `deleteAllFailed()`, and `findDetail(id)`. Implement endpoints in `OutboxController.java`. Update `postausgang/index.html` with retry/delete buttons, batch buttons, and Alpine.js detail modal.
- [ ] **Step 4: Run the single test — verify it PASSES**
  `bash -c "set -o pipefail; ./mvnw test -Dtest=OutboxControllerTest,OutboxServiceTest -Pfast -B --no-transfer-progress | tail -n 30"`
- [ ] **Step 5: Commit**
  `git commit -m "feat(outbox): add retry, dismiss actions, detail inspection, and admin permission guard"`

---

### Task 5: Journey Failure Screenshot Documentation (Section 3.5)

**Files:**
- Modify: `src/main/resources/templates/laeufe/detail.html`
- Modify: `src/main/resources/templates/befunde/detail.html`
- Modify: `src/main/resources/templates/fragments/befundzeile.html`
- Modify: `src/main/resources/messages.properties`
- Test: `src/test/java/dev/hendrikhoemberg/webtesthelper/reporting/FindingViewFactoryTest.java`
- Test: `src/test/java/dev/hendrikhoemberg/webtesthelper/web/RunControllerTest.java`

**Interfaces:**
- Consumes: `MaterialisedFinding` with `CheckType.JOURNEY_STEP_FAILED` and `Evidence.screenshotPath()`.
- Produces: Prominent visual screenshot card in finding details and run details when a journey step fails.

- [ ] **Step 1: Write the failing test**
  In `FindingViewFactoryTest.java` and `RunControllerTest.java`, assert that `JOURNEY_STEP_FAILED` findings provide screenshot path and human-readable journey and step failure context.
- [ ] **Step 2: Run the single test — verify it FAILS**
  `bash -c "set -o pipefail; ./mvnw test -Dtest=FindingViewFactoryTest -Pfast -B --no-transfer-progress | tail -n 30"`
- [ ] **Step 3: Write minimal implementation**
  In `befunde/detail.html`, render journey failure screenshot prominently with title „Zustand der Website beim Abbruch von Schritt X“. In `fragments/befundzeile.html`, add screenshot indicator thumbnail/badge when `befund.screenshotUrl != null`. In `laeufe/detail.html`, highlight journey failures with visual evidence.
- [ ] **Step 4: Run the single test — verify it PASSES**
  `bash -c "set -o pipefail; ./mvnw test -Dtest=FindingViewFactoryTest,RunControllerTest -Pfast -B --no-transfer-progress | tail -n 30"`
- [ ] **Step 5: Commit**
  `git commit -m "feat(findings): document failed journey steps with prominent failure screenshots"`

---

### Task 6: Direct PDF Report Export (Section 3.6)

**Files:**
- Create: `src/main/java/dev/hendrikhoemberg/webtesthelper/reporting/PdfReportService.java`
- Modify: `src/main/java/dev/hendrikhoemberg/webtesthelper/web/RunController.java`
- Modify: `src/main/resources/templates/laeufe/detail.html`
- Modify: `src/main/resources/templates/laeufe/druck.html`
- Modify: `src/main/resources/messages.properties`
- Test: `src/test/java/dev/hendrikhoemberg/webtesthelper/web/RunControllerTest.java`
- Test: `src/test/java/dev/hendrikhoemberg/webtesthelper/reporting/PdfReportServiceTest.java`

**Interfaces:**
- Consumes: Rendered report HTML from Thymeleaf template `laeufe/druck.html`.
- Produces: `byte[]` A4 PDF via Playwright Chromium `page.pdf()`; `GET /laeufe/{id}/bericht/pdf` endpoint returning `application/pdf`.

- [ ] **Step 1: Write the failing test**
  Add unit/web test in `RunControllerTest.java` for `GET /laeufe/{id}/bericht/pdf` expecting status 200, content-type `application/pdf`, and attachment header `pruefbericht-lauf-{id}.pdf`.
- [ ] **Step 2: Run the single test — verify it FAILS**
  `bash -c "set -o pipefail; ./mvnw test -Dtest=RunControllerTest#berichtPdf -Pfast -B --no-transfer-progress | tail -n 30"`
- [ ] **Step 3: Write minimal implementation**
  Create `PdfReportService.java` wrapping Playwright headless page creation, `page.setContent(html)`, and `page.pdf(...)`. Add `GET /laeufe/{id}/bericht/pdf` endpoint in `RunController.java`. Add „PDF herunterladen“ buttons in `laeufe/detail.html` and `laeufe/druck.html`.
- [ ] **Step 4: Run the single test — verify it PASSES**
  `bash -c "set -o pipefail; ./mvnw test -Dtest=RunControllerTest -Pfast -B --no-transfer-progress | tail -n 30"`
- [ ] **Step 5: Commit**
  `git commit -m "feat(reporting): provide direct A4 PDF report export via playwright"`

---

### Task 7: Centralized Email, Slack/Teams Webhooks & Help Documentation (Section 3.6)

**Files:**
- Create: `src/main/java/dev/hendrikhoemberg/webtesthelper/reporting/WebhookNotifier.java`
- Create: `src/main/java/dev/hendrikhoemberg/webtesthelper/reporting/WebhookPayload.java`
- Create: `src/main/resources/help/webhooks.md`
- Modify: `src/main/java/dev/hendrikhoemberg/webtesthelper/catalog/AppSettings.java`
- Modify: `src/main/java/dev/hendrikhoemberg/webtesthelper/web/SettingsController.java`
- Modify: `src/main/java/dev/hendrikhoemberg/webtesthelper/reporting/DigestScheduledService.java`
- Modify: `src/main/resources/templates/einstellungen/index.html`
- Modify: `src/main/resources/templates/fragments/empfaenger.html`
- Modify: `src/main/resources/messages.properties`
- Test: `src/test/java/dev/hendrikhoemberg/webtesthelper/reporting/WebhookNotifierTest.java`
- Test: `src/test/java/dev/hendrikhoemberg/webtesthelper/web/SettingsControllerTest.java`
- Test: `src/test/java/dev/hendrikhoemberg/webtesthelper/web/HelpTopicsTest.java`

**Interfaces:**
- Consumes: Run results with critical errors; AppSettings webhook configuration.
- Produces: Slack Block Kit formatted HTTP POST dispatch to configured Webhook URL; centralized email & webhook UI in `/einstellungen`; help article `webhooks.md`.

- [ ] **Step 1: Write the failing test**
  In `WebhookNotifierTest.java`, test payload formatting with Slack Block Kit and HTTP POST dispatch with MockWebServer or mock HTTP client. In `HelpTopicsTest.java`, verify `webhooks.md` exists and is covered by `HelpService`.
- [ ] **Step 2: Run the single test — verify it FAILS**
  `bash -c "set -o pipefail; ./mvnw test -Dtest=WebhookNotifierTest -Pfast -B --no-transfer-progress | tail -n 30"`
- [ ] **Step 3: Write minimal implementation**
  Create `WebhookNotifier.java` and `WebhookPayload.java`. Add webhook properties in `AppSettings.java`. In `SettingsController.java`, add form fields and `POST /einstellungen/webhook-test`. In `DigestScheduledService.java` / run completion, invoke `WebhookNotifier` when critical findings occur. Create `help/webhooks.md` explaining Slack Incoming Webhooks setup.
- [ ] **Step 4: Run the single test — verify it PASSES**
  `bash -c "set -o pipefail; ./mvnw test -Dtest=WebhookNotifierTest,SettingsControllerTest,HelpTopicsTest -Pfast -B --no-transfer-progress | tail -n 30"`
- [ ] **Step 5: Commit**
  `git commit -m "feat(notifications): add centralized slack/teams webhook alerts and help documentation"`

---

### Task 8: Full Verification & Integration Polish

**Files:**
- Modify: Any files needing final polish based on test suite execution.
- Test: Complete verification suite.

- [ ] **Step 1: Run default fast verify suite**
  `bash -c "set -o pipefail; ./mvnw test -Pfast -B --no-transfer-progress | tail -n 50"`
- [ ] **Step 2: Run full verify suite including browser tests**
  `bash -c "set -o pipefail; ./mvnw test -B --no-transfer-progress | tail -n 60"`
- [ ] **Step 3: Verification-before-completion check**
  Confirm 0 failures, 0 errors, 100% passing tests.
