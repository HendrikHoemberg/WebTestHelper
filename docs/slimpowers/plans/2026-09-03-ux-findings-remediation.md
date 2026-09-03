# UX Findings & Architecture Remediation Implementation Plan

**Goal:** Secure the outbox endpoints against unauthorized access, optimize Playwright PDF generation through BrowserPool integration with Docker sandboxing support, enrich Slack/Teams webhook notifications with full Block Kit digest metadata, and decouple external webhook HTTP calls from database transactions.

**Architecture:** Spring Boot modular monolith adhering to Clean Architecture principles. Security rules enforced via `SecurityConfig`. PDF generation delegated to thread-confined `BrowserPool` Chromium workers. Asynchronous Slack Block Kit webhook notifications using non-blocking Java 11+ `HttpClient`.

**Tech Stack:** Java 25, Spring Boot 4.1.1, Spring Security, Thymeleaf, Playwright 1.62.0, PostgreSQL/Flyway.

**Spec:** [`docs/slimpowers/specs/2026-09-03-ux-and-features-design.md`](file:///home/hendrik/Documents/Coding/WebTestHelper/docs/slimpowers/specs/2026-09-03-ux-and-features-design.md)

## Global Constraints
- German-only UI; message keys `ui.*`; no internal identifiers or raw enum names in rendered HTML.
- View tests: `@WebMvcTest` + MockMvc; assertions on text/markup, not on CSS.
- Recorder worker pool sizes (0/2/4) untouched.
- Desktop-only UI; no mobile breakpoints.
- Context hygiene: always use `-B --no-transfer-progress` and pipe with `tail` on test runs.

---

### Task 1: Outbox Security Rules & I18n Messages

**Files:**
- Modify: `src/main/java/dev/hendrikhoemberg/webtesthelper/web/SecurityConfig.java`
- Modify: `src/main/java/dev/hendrikhoemberg/webtesthelper/web/OutboxController.java`
- Modify: `src/main/resources/messages.properties`
- Test: `src/test/java/dev/hendrikhoemberg/webtesthelper/web/SecurityRulesTest.java`
- Test: `src/test/java/dev/hendrikhoemberg/webtesthelper/web/OutboxControllerTest.java`

**Interfaces:**
- Consumes: Outbox action POST requests and GET `/postausgang/{id}/details`.
- Produces: Strict `ROLE_ADMIN` authorization for all `/postausgang/**` routes; localized flash messages via `MessageSource`.

- [x] **Step 1: Write the failing test**
  In `SecurityRulesTest.java`, add tests verifying that a user with `ROLE_USER` receives HTTP 403 Forbidden when calling `POST /postausgang/1/wiederholen`, `POST /postausgang/alle-wiederholen`, `POST /postausgang/1/loeschen`, `POST /postausgang/alle-loeschen`, and `GET /postausgang/1/details`.
- [x] **Step 2: Run the single test — verify it FAILS**
  `bash -c "set -o pipefail; ./mvnw test -Dtest=SecurityRulesTest -Pfast -B --no-transfer-progress | tail -n 30"`
- [x] **Step 3: Write minimal implementation**
  In `SecurityConfig.java`, update the admin matcher to include `/postausgang/**`. In `OutboxController.java`, inject `MessageSource` and replace hardcoded flash strings with messages resolved from `messages.properties` (`ui.postausgang.erfolg.wiederholen`, `ui.postausgang.erfolg.alle_wiederholen`, `ui.postausgang.erfolg.loeschen`, `ui.postausgang.erfolg.alle_loeschen`).
- [x] **Step 4: Run the single test — verify it PASSES**
  `bash -c "set -o pipefail; ./mvnw test -Dtest=SecurityRulesTest,OutboxControllerTest -Pfast -B --no-transfer-progress | tail -n 30"`
- [x] **Step 5: Commit**
  `git commit -m "fix(security): enforce admin role on all outbox subpaths and localize flash messages"`

---

### Task 2: Playwright PDF Generation via BrowserPool & Sandboxing

**Files:**
- Modify: `src/main/java/dev/hendrikhoemberg/webtesthelper/reporting/PdfReportService.java`
- Test: `src/test/java/dev/hendrikhoemberg/webtesthelper/reporting/PdfReportServiceTest.java`
- Test: `src/test/java/dev/hendrikhoemberg/webtesthelper/web/RunControllerTest.java`

**Interfaces:**
- Consumes: Rendered Thymeleaf report HTML string.
- Produces: `byte[]` A4 PDF generated via `browserPool.submit(...)` leveraging pre-warmed Chromium instances with container sandbox settings.

- [x] **Step 1: Write the failing test**
  In `PdfReportServiceTest.java`, test that `PdfReportService` delegates HTML rendering to `BrowserPool.submit(...)` instead of spawning ad-hoc `Playwright.create()` instances.
- [x] **Step 2: Run the single test — verify it FAILS**
  `bash -c "set -o pipefail; ./mvnw test -Dtest=PdfReportServiceTest -Pfast -B --no-transfer-progress | tail -n 30"`
- [x] **Step 3: Write minimal implementation**
  Refactor `PdfReportService.java` to inject `BrowserPool`. Replace `Playwright.create()` and `chromium().launch(...)` in `renderHtmlToPdf(String html)` with `browserPool.submit(browser -> { try (BrowserContext ctx = browser.newContext(); Page page = ctx.newPage()) { page.setContent(html, new Page.SetContentOptions().setWaitUntil(WaitUntilState.LOAD)); return page.pdf(...); } })`.
- [x] **Step 4: Run the single test — verify it PASSES**
  `bash -c "set -o pipefail; ./mvnw test -Dtest=PdfReportServiceTest,RunControllerTest -Pfast -B --no-transfer-progress | tail -n 30"`
- [x] **Step 5: Commit**
  `git commit -m "perf(report): reuse browserpool workers for direct pdf rendering with container sandboxing"`

---

### Task 3: Slack/Teams Block Kit Digest Payload, Async Dispatch & Settings

**Files:**
- Modify: `src/main/java/dev/hendrikhoemberg/webtesthelper/catalog/AppSettings.java`
- Modify: `src/main/java/dev/hendrikhoemberg/webtesthelper/web/SettingsForm.java`
- Modify: `src/main/java/dev/hendrikhoemberg/webtesthelper/web/SettingsController.java`
- Modify: `src/main/resources/templates/einstellungen/index.html`
- Modify: `src/main/resources/messages.properties`
- Modify: `src/main/java/dev/hendrikhoemberg/webtesthelper/reporting/WebhookNotifier.java`
- Modify: `src/main/java/dev/hendrikhoemberg/webtesthelper/reporting/DigestService.java`
- Test: `src/test/java/dev/hendrikhoemberg/webtesthelper/catalog/AppSettingsTest.java`
- Test: `src/test/java/dev/hendrikhoemberg/webtesthelper/reporting/WebhookNotifierTest.java`
- Test: `src/test/java/dev/hendrikhoemberg/webtesthelper/reporting/DigestServiceTest.java`
- Test: `src/test/java/dev/hendrikhoemberg/webtesthelper/web/SettingsControllerTest.java`

**Interfaces:**
- Consumes: `Digest` containing `SiteDigest` findings; `AppSettings.webhookOnlyCritical()` toggle.
- Produces: Asynchronous, non-blocking Slack Block Kit JSON dispatch containing header, total errors, loud sites breakdown, and dashboard link; UI toggle in `/einstellungen`.

- [x] **Step 1: Write the failing test**
  In `WebhookNotifierTest.java`, assert that `sendDigestNotification` produces valid Block Kit JSON with header, findings summary, affected site names, and link button. In `AppSettingsTest.java` and `SettingsControllerTest.java`, test saving and retrieving `webhookOnlyCritical`. In `DigestServiceTest.java`, assert that webhooks are not called if `onlyCritical` is enabled and no critical findings exist, and that calls are non-blocking.
- [x] **Step 2: Run the single test — verify it FAILS**
  `bash -c "set -o pipefail; ./mvnw test -Dtest=WebhookNotifierTest,AppSettingsTest,DigestServiceTest -Pfast -B --no-transfer-progress | tail -n 30"`
- [x] **Step 3: Write minimal implementation**
  1. In `AppSettings.java`, add `webhookOnlyCritical()` and `saveWebhookOnlyCritical(boolean)`.
  2. In `SettingsForm.java`, `SettingsController.java`, and `einstellungen/index.html`, add the toggle `webhookOnlyCritical`.
  3. In `WebhookNotifier.java`, implement `buildDigestBlockKitPayload(Digest digest, String baseUrl)` formatting `header`, `section` with stats and sites, and `actions` button. Provide asynchronous dispatch `sendDigestNotificationAsync(...)` via `httpClient.sendAsync(...)`.
  4. In `DigestService.java`, check `appSettings.webhookOnlyCritical()` and trigger `webhookNotifier.sendDigestNotificationAsync(...)` non-blockingly without delaying transaction completion.
- [x] **Step 4: Run the single test — verify it PASSES**
  `bash -c "set -o pipefail; ./mvnw test -Dtest=WebhookNotifierTest,AppSettingsTest,DigestServiceTest,SettingsControllerTest -Pfast -B --no-transfer-progress | tail -n 30"`
- [x] **Step 5: Commit**
  `git commit -m "feat(webhooks): enrich digest payload with slack block kit and decouple async dispatch"`

---

### Task 4: Full Suite Verification

**Files:**
- None (verification only).

- [x] **Step 1: Run default fast verify suite**
  `bash -c "set -o pipefail; ./mvnw test -Pfast -B --no-transfer-progress | tail -n 50"`
- [x] **Step 2: Run full verify suite including browser tests**
  `bash -c "set -o pipefail; ./mvnw test -B --no-transfer-progress | tail -n 60"`
- [x] **Step 3: Verification-before-completion check**
  Confirm 0 failures, 0 errors, 100% passing tests (1766 tests, 0 failures, 0 errors).
