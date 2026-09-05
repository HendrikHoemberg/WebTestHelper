# Assessment Follow-up Remediation Plan

**Goal:** Close the remaining gaps found while verifying ASSESSMENT_REPORT.md: one security hole (SEC-04 `jetzt-ausfuehren`), one template bug (CONV-02 sub-minute overdue), one flaky full-suite test (PdfRenderer close), interrupt propagation in HostThrottle (C5), the duplicate `contextFor` query (ARCH-04), per-request DB reads in the two controller advices (DB-04), and one dead exception class (E1 leftover).

**Architecture:** Spring Boot modular monolith (web, catalog, scheduling, runner, crawler, checks, findings, reporting, recorder + new auth). UI is Thymeleaf + HTMX + Alpine, German-only copy in `messages.properties`.

**Tech Stack:** Java 21+, Spring Boot, Spring Modulith, Caffeine, Playwright, PostgreSQL + Flyway, JUnit 5, AssertJ, Mockito.

**Spec:** ASSESSMENT_REPORT.md findings SEC-04, CONV-02, C5, ARCH-04, DB-04 + observed flaky `PdfRendererLifecycleStressTest`.

## Global Constraints
- Zero failures in the fast gate (`bash -c "set -o pipefail; ./mvnw test -Pfast -B --no-transfer-progress | tail -n 50"`); full gate (incl. `@Tag("browser")`) afterwards because security/templates are touched.
- German-only UI copy via `messages.properties`; no identifiers in rendered HTML.
- View tests: `@WebMvcTest` + MockMvc, assertions on text/markup.
- Do not change 0/2/4 worker pool sizes.

---

### Task 1: SEC-04 — Restrict `jetzt-ausfuehren` to ADMIN

**Files:**
- Modify: `src/main/java/dev/hendrikhoemberg/webtesthelper/web/SecurityConfig.java` — add `/websites/*/journeys/*/jetzt-ausfuehren` to the POST-admin matcher list.
- Test: `src/test/java/dev/hendrikhoemberg/webtesthelper/web/SecurityRulesTest.java` — new test: `@WithMockUser(roles = "USER")` POST with CSRF → 403 Forbidden.

**Interfaces:**
- Consumes: existing matcher list in `filterChain`.
- Produces: non-admin users can no longer trigger live journey replays.

- [ ] **Step 1:** Add failing test `userCannotTriggerImmediateJourneyReplay` (expect 403).
- [ ] **Step 2:** Run `./mvnw test -Dtest=SecurityRulesTest -B --no-transfer-progress` — FAIL (404/200 today, falls through to authenticated).
- [ ] **Step 3:** Add matcher line after `/websites/*/journeys/*/loeschen`.
- [ ] **Step 4:** Re-run — PASS.

### Task 2: CONV-02 — REVERTED (deliberate 60s clock-skew tolerance)

- Initial attempt made any negative duration render "überfällig", but `Milestone3UiConventionsAdversarialTest` (`CONV-02: Relativzeit Negative Duration Boundary Verification`) deliberately locks in the boundary: overdue ≥60s → "überfällig", <60s → "in Kürze" (treated as clock skew between the scheduler and the UI). The template change and the new `DashboardControllerTest` test were reverted to respect the project's own adversarial gate. The user-visible bug from the report (hours-overdue runs shown as "in Kürze") was already fixed by that boundary.

### Task 3: PdfRenderer — deterministic executor termination on close

**Files:**
- Modify: `src/main/java/dev/hendrikhoemberg/webtesthelper/reporting/PdfRenderer.java` — in `close(timeout, unit)`, after `executor.shutdownNow()`, await termination with the remaining deadline budget (`awaitTermination`), restoring interrupt on InterruptedException.
- Test: `src/test/java/dev/hendrikhoemberg/webtesthelper/challenger/PdfRendererLifecycleStressTest.java` — existing `cleanLifecycle_noProcessLeak` is the red test (failed once in full gate under load).

**Interfaces:**
- Consumes: `close(long, TimeUnit)`.
- Produces: `close()` returns only after the worker thread has actually terminated (or the bounded budget elapsed), making `isTerminated()` deterministic.

- [ ] **Step 1:** Fix production code.
- [ ] **Step 2:** Run `PdfRendererLifecycleStressTest` repeatedly + full gate — must pass.

### Task 4: C5 — Interrupted politeness wait aborts instead of continuing

**Files:**
- Modify: `src/main/java/dev/hendrikhoemberg/webtesthelper/crawler/HostThrottle.java` — on InterruptedException, restore the flag and throw `CrawlCancelledException` (existing cancellation protocol; `CrawlService.visit` already rethrows it, `SetupProbe` propagates).
- Test: `src/test/java/dev/hendrikhoemberg/webtesthelper/crawler/HostThrottleTest.java` — new test interrupting a thread inside `await`; assert `CrawlCancelledException` and thread termination.

**Interfaces:**
- Consumes: `CrawlCancelledException` (crawler package).
- Produces: callers abort immediately on interruption instead of proceeding to navigation.

- [ ] **Step 1:** Add failing test.
- [ ] **Step 2:** Run `HostThrottleTest` — FAIL (currently swallows).
- [ ] **Step 3:** Implement rethrow; update javadoc.
- [ ] **Step 4:** Re-run — PASS. Also run `CrawlServiceEnqueueTest`, `SetupProbeTest` if present.

### Task 5: ARCH-04 — `populateConfig` loads the site context once

**Files:**
- Modify: `src/main/java/dev/hendrikhoemberg/webtesthelper/web/SiteDetailModel.java` — extract `populate(SiteContext site, Model model)`; `populate(siteId, model)` delegates with one `contextFor`; `populateConfig` loads the context once and passes it to both `populate(...)` and `trafficLight(site)`.
- Test: Create `src/test/java/dev/hendrikhoemberg/webtesthelper/web/SiteDetailModelTest.java` — Mockito unit test: `populateConfig` calls `siteService.contextFor` exactly once and puts `site`, `checkCategories`, `trafficLight` attributes.

**Interfaces:**
- Consumes: existing constructor (9 deps).
- Produces: identical model attributes, one fewer DB context load per config render.

- [ ] **Step 1:** Add failing test (expects `times(1)`).
- [ ] **Step 2:** Run — FAIL (`times(2)`).
- [ ] **Step 3:** Refactor.
- [ ] **Step 4:** Re-run — PASS. Also run `SiteControllerTest`, `SiteDetailControllerTest`, `CheckSettingsControllerTest`, `CredentialControllerTest`, `RecipientControllerTest`, `ScheduleControllerTest`.

### Task 6: DB-04a — Tutorial status cached in the HTTP session

**Files:**
- Modify: `src/main/java/dev/hendrikhoemberg/webtesthelper/web/TutorialAdvice.java` — accept `HttpSession`; store/read `tutorialOffen` under a constant session key (one `AppUserService` lookup per session instead of per request). `tour=start` still forces open.
- Modify: `src/main/java/dev/hendrikhoemberg/webtesthelper/web/TutorialController.java` — on `abschliessen`/`neustarten`, update the session attribute.
- Test: `src/test/java/dev/hendrikhoemberg/webtesthelper/web/TutorialAdviceTest.java` — new tests with `MockHttpSession`: second call without session reuse does not re-query the service; a fresh session re-queries; existing tests keep passing (null session → direct query).
- Test: `src/test/java/dev/hendrikhoemberg/webtesthelper/web/TutorialControllerTest.java` — verify session attribute updated on dismissal.

**Interfaces:**
- Consumes: `AppUserService.isTutorialAbgeschlossen`, `setTutorialAbgeschlossen`.
- Produces: per-session caching with explicit invalidation on dismiss/restart.

- [ ] **Step 1:** Add failing tests.
- [ ] **Step 2:** Run — FAIL.
- [ ] **Step 3:** Implement.
- [ ] **Step 4:** Re-run — PASS.

### Task 7: DB-04b — `schedulingPaused` cached in AppSettings

**Files:**
- Modify: `src/main/java/dev/hendrikhoemberg/webtesthelper/catalog/AppSettings.java` — `private volatile Boolean schedulingPausedCache`; `schedulingPaused()` returns the cache or reads + caches; `saveSchedulingPaused` updates the cache; package-private `invalidateSchedulingPausedCache()`.
- Test: `src/test/java/dev/hendrikhoemberg/webtesthelper/catalog/AppSettingsTest.java` — new test: save → read reflects without repository row (proves cache); call `invalidateSchedulingPausedCache()` in `cleanUp()` to prevent cross-test staleness after `deleteAll`.
- Check: `ScheduleKillSwitchTest` only writes via `saveSchedulingPaused` (verified) → auto-invalidation keeps it consistent.

**Interfaces:**
- Consumes: `getSetting(KEY_SCHEDULING_PAUSED)`, `saveSetting`.
- Produces: one PK read per process (not per request) for the pause flag, invalidated on every write.

- [ ] **Step 1:** Add test.
- [ ] **Step 2:** Implement.
- [ ] **Step 3:** Run `AppSettingsTest`, `ScheduleKillSwitchTest`, `SettingsControllerTest` — PASS.

### Task 8: E1 leftover — delete dead `CheckEvaluationException`

**Files:**
- Delete: `src/main/java/dev/hendrikhoemberg/webtesthelper/checks/CheckEvaluationException.java` (nothing throws it since E1 containment).
- Modify: `src/main/java/dev/hendrikhoemberg/webtesthelper/checks/CheckAbstainedException.java` — reword the javadoc paragraph that links the deleted class.

- [ ] **Step 1:** Delete + reword.
- [ ] **Step 2:** Run `CheckEngineTest` + fast gate — PASS.

### Task 9: Final Verification Gate

- [ ] **Step 1:** Fast gate (`bash -c "set -o pipefail; ./mvnw test -Pfast -B --no-transfer-progress | tail -n 50"`).
- [ ] **Step 2:** Full gate (`bash -c "set -o pipefail; ./mvnw test -B --no-transfer-progress | tail -n 60"`) — changes touch security/templates.
- [ ] **Step 3:** Repeat `PdfRendererLifecycleStressTest` a few times to confirm the flake is gone.
