# Lauf abbrechen Implementation Plan

**Goal:** Give users a way to stop queued and running runs from the UI.

**Architecture:** Cooperative cancellation. A cancel endpoint conditionally flips a run row to
`CANCELLED` (with `finished_at`, lease cleared); a cancelled QUEUED run is never claimed
(`CLAIM_SQL` only matches QUEUED/RUNNING), and a cancelled RUNNING run is discovered by the
worker because its next heartbeat returns 0 rows — the heartbeat already requires
`status = 'RUNNING'`. The executor converts a failed heartbeat into a `RunCancelledException`
at well-defined checkpoints, the worker catches it and finishes the run as `CANCELLED` instead
of `FAILED`. `FINISH_SQL` gains a `status = 'RUNNING'` guard so a late cancel is never
overwritten by `COMPLETED` and a requeued run (lease-sweep edge case) is never stomped.

**Tech Stack:** Spring Boot modular monolith, Postgres + raw JDBC for lease/run-state SQL,
Thymeleaf + HTMX + Alpine, German UI via `messages.properties`.

**Spec:** design approved in chat (2026-09-01): cancel on run detail page + progress fragment,
Alpine confirmation panel like the Ausgangsbestand block.

## Global Constraints

- German-only UI copy in `messages.properties`; new keys must start with `ui.`.
- All template message keys must resolve in `messages.properties` (`UiMessageKeyTest` scans
  templates).
- Raw SQL for status transitions (mirror `RunLeaseJdbcRepository` style).
- Worker pool sizes (0/2/4) untouched; no new Flyway migration needed (CANCELLED + columns exist).
- Verify with `./mvnw test` before claiming done; single tests via `-Dtest=...`.

---

### Task 1: Cancel SQL + FINISH_SQL guard in `RunLeaseJdbcRepository`

**Files:**
- Modify: [`src/main/java/dev/hendrikhoemberg/webtesthelper/runner/persistence/RunLeaseJdbcRepository.java`]
  — add `CANCEL_SQL` + `cancel(long runId)`; add `AND status = 'RUNNING'` to `FINISH_SQL`.
- Test: [`src/test/java/dev/hendrikhoemberg/webtesthelper/runner/RunLeaseJdbcRepositoryTest.java`]
  — new tests for cancel + guarded finish.

**Interfaces:**
- Produces: `public boolean cancel(long runId)` — true iff exactly one QUEUED/RUNNING row was
  cancelled; `finish(...)` now returns false when the row is no longer RUNNING.

- [ ] **Step 1: Write the failing tests**

  ```java
  @Test
  void cancelMarksAQueuedRunCancelledAndFinished() {
      long runId = queueRun(siteA);

      assertThat(leases.cancel(runId)).isTrue();

      assertThat(jdbc.queryForObject("SELECT status FROM run WHERE id = ?", String.class, runId))
              .isEqualTo("CANCELLED");
      assertThat(jdbc.queryForObject("SELECT finished_at IS NOT NULL FROM run WHERE id = ?",
              Boolean.class, runId)).isTrue();
      assertThat(jdbc.queryForObject("SELECT lease_owner FROM run WHERE id = ?", String.class, runId))
              .isNull();
      // A cancelled run is never claimed (spec 14's queue is the only path to RUNNING).
      assertThat(leases.claimNext("worker-1", Duration.ofMinutes(5))).isEmpty();
  }

  @Test
  void cancelMarksARunningRunCancelledAndClearsTheLease() {
      long runId = queueRun(siteA);
      leases.claimNext("worker-1", Duration.ofMinutes(5)).orElseThrow();

      assertThat(leases.cancel(runId)).isTrue();

      assertThat(jdbc.queryForObject("SELECT status FROM run WHERE id = ?", String.class, runId))
              .isEqualTo("CANCELLED");
      assertThat(jdbc.queryForObject("SELECT lease_owner FROM run WHERE id = ?", String.class, runId))
              .isNull();
      assertThat(jdbc.queryForObject("SELECT lease_expires_at FROM run WHERE id = ?",
              java.sql.Timestamp.class, runId)).isNull();
  }

  @Test
  void cancelOfATerminalRunIsANoOp() {
      long runId = queueRun(siteA);
      leases.claimNext("worker-1", Duration.ofMinutes(5)).orElseThrow();
      leases.finish(runId, "worker-1", RunStatus.COMPLETED, null);

      assertThat(leases.cancel(runId)).isFalse();
      assertThat(jdbc.queryForObject("SELECT status FROM run WHERE id = ?", String.class, runId))
              .isEqualTo("COMPLETED");
  }

  @Test
  void cancelOfAnUnknownRunIsANoOp() {
      assertThat(leases.cancel(9_999_999L)).isFalse();
  }

  @Test
  void finishDoesNotOverwriteACancelledRun() {
      long runId = queueRun(siteA);
      leases.claimNext("worker-1", Duration.ofMinutes(5)).orElseThrow();
      leases.cancel(runId);

      assertThat(leases.finish(runId, "worker-1", RunStatus.COMPLETED, null)).isFalse();
      assertThat(jdbc.queryForObject("SELECT status FROM run WHERE id = ?", String.class, runId))
              .isEqualTo("CANCELLED");
  }
  ```

- [ ] **Step 2: Run the single test — verify it FAILS**
  `./mvnw test -Dtest=RunLeaseJdbcRepositoryTest` → expected: FAIL (no `cancel` method).

- [ ] **Step 3: Write minimal implementation**

  ```java
  private static final String CANCEL_SQL = """
          UPDATE run
             SET status           = 'CANCELLED',
                 finished_at      = now(),
                 lease_owner      = NULL,
                 lease_expires_at = NULL,
                 error_message    = NULL
           WHERE id = ? AND status IN ('QUEUED', 'RUNNING')
          """;
  ```
  `FINISH_SQL` becomes:
  ```java
  private static final String FINISH_SQL = """
          UPDATE run
             SET status           = ?,
                 finished_at      = now(),
                 lease_owner      = NULL,
                 lease_expires_at = NULL,
                 error_message    = ?
           WHERE id = ? AND lease_owner = ? AND status = 'RUNNING'
          """;
  ```
  ```java
  /** Cancels a QUEUED or RUNNING run; false when the run does not exist or already ended. */
  public boolean cancel(long runId) {
      return jdbc.update(CANCEL_SQL, runId) == 1;
  }
  ```

- [ ] **Step 4: Run the single test — verify it PASSES**
  `./mvnw test -Dtest=RunLeaseJdbcRepositoryTest` → expected: PASS.

- [ ] **Step 5: Commit**
  `git commit -m "feat: cancel a queued or running run row, guard finish against cancelled runs"`

---

### Task 2: `RunCancelledException` + worker stops at the next checkpoint

**Files:**
- Create: [`src/main/java/dev/hendrikhoemberg/webtesthelper/runner/RunCancelledException.java`]
- Modify: [`src/main/java/dev/hendrikhoemberg/webtesthelper/runner/RunWorker.java`]
  — catch `RunCancelledException` before the generic `Exception` handler; log and do not call
  `finish` (the cancel endpoint already stamped the row; the guarded FINISH_SQL would refuse
  anyway — and in the rare requeue-race case the run must stay QUEUED, not become CANCELLED).
- Test: [`src/test/java/dev/hendrikhoemberg/webtesthelper/runner/RunWorkerTest.java`]

**Interfaces:**
- Produces: `RunCancelledException extends RuntimeException`; worker turns it into a cancelled
  run (status stays CANCELLED, never FAILED).

- [ ] **Step 1: Write the failing test**

  ```java
  @Test
  void aCancelledExecutionLeavesTheRunCancelledNotFailed() {
      long runId = runs.enqueue(siteId, RunTrigger.MANUAL, RunScope.FULL);
      worker.withExecutorForTest(lease -> { throw new RunCancelledException("Lauf abgebrochen"); });
      jdbc.update("UPDATE run SET status = 'CANCELLED', finished_at = now() WHERE id = ?", runId);

      assertThat(worker.workOnce()).isTrue();

      assertThat(jdbc.queryForObject("SELECT status FROM run WHERE id = ?", String.class, runId))
              .isEqualTo("CANCELLED");
      assertThat(jdbc.queryForObject("SELECT error_message FROM run WHERE id = ?", String.class, runId))
              .isNull();
  }
  ```
  (The `UPDATE` mirrors what the cancel endpoint commits before the worker notices; the
  assertion that matters is: no `FAILED`, no error message.)

- [ ] **Step 2: Run the single test — verify it FAILS**
  `./mvnw test -Dtest=RunWorkerTest` → expected: FAIL (RunCancelledException does not exist;
  once it does without the catch, the run would be marked FAILED).

- [ ] **Step 3: Write minimal implementation**

  `RunCancelledException.java`:
  ```java
  package dev.hendrikhoemberg.webtesthelper.runner;

  /**
   * Raised by the executor when the run's lease is no longer RUNNING — a user cancelled it,
   * or (rarely) the sweep requeued it. The worker must not finish such a run as COMPLETED or
   * FAILED: the run row already carries the truth.
   */
  public final class RunCancelledException extends RuntimeException {

      public RunCancelledException(String message) {
          super(message);
      }
  }
  ```
  In `RunWorker.executeLeased`, before the generic catch:
  ```java
  } catch (RunCancelledException cancelled) {
      // The cancel endpoint (or the lease sweep) already moved the row out of RUNNING; the
      // guarded FINISH_SQL would refuse a finish here, and that is correct — a requeued run
      // must stay QUEUED for its next worker, a cancelled one stays CANCELLED.
      log.info("Run {} abgebrochen", lease.runId());
  } catch (Exception e) {
  ```

- [ ] **Step 4: Run the single test — verify it PASSES**
  `./mvnw test -Dtest=RunWorkerTest` → expected: PASS.

- [ ] **Step 5: Commit**
  `git commit -m "feat: worker stops a run at the next checkpoint without marking it failed"`

---

### Task 3: `CrawlRunExecutor` heartbeats become cancellation checkpoints

**Files:**
- Modify: [`src/main/java/dev/hendrikhoemberg/webtesthelper/runner/CrawlRunExecutor.java`]
  — replace bare `leases.heartbeat(...)` calls with a `heartbeatOrThrow(lease)` helper that
  throws `RunCancelledException` when the heartbeat updates 0 rows; call it at run start, in
  the crawl progress callback, after the crawl, before the interaction pass, before the
  journey pass and before re-verification.
- Test: [`src/test/java/dev/hendrikhoemberg/webtesthelper/runner/RunWorkerTest.java`]
  — end-to-end through the real executor would need Chromium; a unit-level checkpoint test
  lives in `CrawlRunExecutorTest` only if cheap. Instead the worker test from Task 2 covers
  the propagation; add a focused test with a mocked executor bean here:

- [ ] **Step 1: Write the failing test** — add to `RunWorkerTest`:

  ```java
  @Test
  void aRunCancelledMidExecutionIsFinishedAsCancelled() {
      long runId = runs.enqueue(siteId, RunTrigger.MANUAL, RunScope.FULL);
      worker.withExecutorForTest(lease -> {
          // The cancel lands while the run executes.
          jdbc.update("UPDATE run SET status = 'CANCELLED', finished_at = now(), "
                  + "lease_owner = NULL, lease_expires_at = NULL WHERE id = ?", lease.runId());
          throw new RunCancelledException("Lauf " + lease.runId() + " wurde abgebrochen");
      });

      assertThat(worker.workOnce()).isTrue();

      assertThat(jdbc.queryForObject("SELECT status FROM run WHERE id = ?", String.class, runId))
              .isEqualTo("CANCELLED");
  }
  ```

  And add to `CrawlRunExecutorTest` (runs in the fast profile too? — it is `@Tag("browser")`,
  so it must stay browser-only; the real-executor checkpoint assertion is the `@Tag("browser")`
  test below):

  ```java
  @Test
  void aCancelledRunStopsBeforeTheCrawl() throws Exception {
      // Cancel the run between claim and execute: the executor's first checkpoint must throw
      // before any page is fetched, and the worker must leave the run CANCELLED.
      long runId = runs.enqueue(siteId, RunTrigger.MANUAL, RunScope.FULL);
      worker.withExecutorForTest(lease -> {
          leases.cancel(lease.runId());
          defaultExecutor.execute(lease);
      });

      assertThat(worker.workOnce()).isTrue();

      assertThat(jdbc.queryForObject("SELECT status FROM run WHERE id = ?", String.class, runId))
              .isEqualTo("CANCELLED");
      assertThat(jdbc.queryForObject(
              "SELECT count(*) FROM crawl_queue_item WHERE run_id = ? AND status = 'DONE'",
              Integer.class, runId)).isZero();
  }
  ```
  (This replaces the default-executor wire-up for one test; `leases` and `defaultExecutor`
  must be autowired fields — add `@Autowired RunLeaseJdbcRepository leases;` and
  `@Autowired RunExecutor defaultExecutor;` to `CrawlRunExecutorTest`.)

- [ ] **Step 2: Run the single tests — verify they FAIL**
  `./mvnw test -Dtest=RunWorkerTest` → expected: PASS (Task 2 already covers it — this step
  doubles as regression). `./mvnw test -Dtest=CrawlRunExecutorTest` → expected: FAIL
  (executor ignores cancellation: the run ends COMPLETED because the real crawl runs).

- [ ] **Step 3: Write minimal implementation**

  In `CrawlRunExecutor`:
  ```java
  /** One cancellation checkpoint: the heartbeat requires status = 'RUNNING', so a cancelled
   *  run (or a reclaimed lease) makes it return false — which is the executor's stop signal. */
  private void heartbeatOrThrow(RunLease lease) {
      if (!leases.heartbeat(lease.runId(), identity.name(), LEASE_EXTENSION)) {
          throw new RunCancelledException("Lauf " + lease.runId() + " wurde abgebrochen");
      }
  }
  ```
  Replace the four bare calls:
  - first line of `execute`: `heartbeatOrThrow(lease);` before `SiteContext site = ...`
  - progress callback: `(visited, failed) -> { heartbeatOrThrow(lease); results.updateProgress(lease.runId(), visited, failed); }`
  - after the crawl: `heartbeatOrThrow(lease);` (replaces the existing `leases.heartbeat(...)`)
  - before the interaction pass: `heartbeatOrThrow(lease);`
  - before the journey pass: `heartbeatOrThrow(lease);`
  - before `reverifier.reverify(...)`: `heartbeatOrThrow(lease);`

- [ ] **Step 4: Run the single tests — verify they PASS**
  `./mvnw test -Dtest=RunWorkerTest && ./mvnw test -Dtest=CrawlRunExecutorTest` → expected: PASS.

- [ ] **Step 5: Commit**
  `git commit -m "feat: executor stops at phase boundaries when the run was cancelled"`

---

### Task 4: `RunService.cancel` + controller endpoint + flash messages

**Files:**
- Modify: [`src/main/java/dev/hendrikhoemberg/webtesthelper/runner/RunService.java`]
  — inject `RunLeaseJdbcRepository`, add `public boolean cancel(long runId)` delegating to it.
- Modify: [`src/main/java/dev/hendrikhoemberg/webtesthelper/web/RunController.java`]
  — add `@PostMapping("/{id}/abbrechen")` → `redirect:/laeufe/{id}` with flash message.
- Modify: [`src/main/resources/messages.properties`] — add keys (Task 5's template needs them):
  ```
  ui.lauf.abbrechen.titel=Lauf abbrechen
  ui.lauf.abbrechen.button=Lauf abbrechen
  ui.lauf.abbrechen.folge=Der Prüflauf wird gestoppt, sobald der laufende Schritt beendet ist. Bereits geprüfte Seiten bleiben erhalten, Feststellungen dieses Laufs werden nicht gespeichert.
  ui.lauf.abbrechen.bestaetigen=Jetzt abbrechen
  ui.lauf.abbrechen.erfolg=Der Prüflauf wurde abgebrochen.
  ui.lauf.abbrechen.bereits_beendet=Der Prüflauf war bereits beendet und wurde nicht abgebrochen.
  ```
- Test: [`src/test/java/dev/hendrikhoemberg/webtesthelper/runner/RunServiceTest.java`]
  + [`src/test/java/dev/hendrikhoemberg/webtesthelper/web/RunControllerTest.java`]

**Interfaces:**
- Produces: `POST /laeufe/{id}/abbrechen` (CSRF-protected like the baseline POST);
  `RunService.cancel(long) → boolean`.

- [ ] **Step 1: Write the failing tests**

  `RunServiceTest`:
  ```java
  @Test
  void cancelReturnsTrueForAQueuedRunAndFalseForATerminalOne() {
      long queued = runs.enqueue(siteId, RunTrigger.MANUAL, RunScope.FULL);

      assertThat(runs.cancel(queued)).isTrue();
      assertThat(runs.cancel(queued)).isFalse();   // idempotent: already terminal
      assertThat(runs.cancel(9_999_999L)).isFalse();
  }
  ```
  `RunControllerTest`:
  ```java
  @Test
  @WithMockUser(roles = "USER")
  void postAbbrechenWithoutCsrfIsForbidden() throws Exception {
      mvc.perform(post("/laeufe/101/abbrechen"))
              .andExpect(status().isForbidden());
  }

  @Test
  @WithMockUser(roles = "USER")
  void postAbbrechenCancelsAndRedirectsWithFlashMessage() throws Exception {
      long runId = 101L;
      when(runService.cancel(runId)).thenReturn(true);

      mvc.perform(post("/laeufe/" + runId + "/abbrechen").with(csrf()))
              .andExpect(status().is3xxRedirection())
              .andExpect(redirectedUrl("/laeufe/" + runId))
              .andExpect(flash().attributeExists("flashMessage"));

      verify(runService).cancel(runId);
  }

  @Test
  @WithMockUser(roles = "USER")
  void postAbbrechenOfAnAlreadyFinishedRunStillRedirects() throws Exception {
      long runId = 102L;
      when(runService.cancel(runId)).thenReturn(false);

      mvc.perform(post("/laeufe/" + runId + "/abbrechen").with(csrf()))
              .andExpect(status().is3xxRedirection())
              .andExpect(redirectedUrl("/laeufe/" + runId))
              .andExpect(flash().attributeExists("flashMessage"));
  }
  ```

- [ ] **Step 2: Run the single tests — verify they FAIL**
  `./mvnw test -Dtest=RunServiceTest,RunControllerTest` → expected: FAIL (no `cancel` method,
  no mapping).

- [ ] **Step 3: Write minimal implementation**

  `RunService` — add field + constructor param `RunLeaseJdbcRepository leases` and:
  ```java
  /**
   * Cancels a QUEUED or RUNNING run. True when the run was cancelled, false when it does not
   * exist or already ended — a terminal run is deliberately left untouched.
   */
  public boolean cancel(long runId) {
      return leases.cancel(runId);
  }
  ```
  `RunController`:
  ```java
  @PostMapping("/{id}/abbrechen")
  public String abbrechen(@PathVariable("id") long id, RedirectAttributes redirectAttributes, Locale locale) {
      boolean cancelled = runService.cancel(id);
      String msg = messageSource.getMessage(
              cancelled ? "ui.lauf.abbrechen.erfolg" : "ui.lauf.abbrechen.bereits_beendet",
              null, locale);
      redirectAttributes.addFlashAttribute("flashMessage", msg);
      return "redirect:/laeufe/" + id;
  }
  ```

- [ ] **Step 4: Run the single tests — verify they PASS**
  `./mvnw test -Dtest=RunServiceTest,RunControllerTest` → expected: PASS.

- [ ] **Step 5: Commit**
  `git commit -m "feat: cancel endpoint and service method for queued or running runs"`

---

### Task 5: Cancel UI on the run detail page and in the progress fragment

**Files:**
- Modify: [`src/main/resources/templates/laeufe/detail.html`]
  — Alpine confirmation panel (`x-data="{ offen: false }"`, `@abbrechen-offen.window="offen = true"`)
  directly below the live-progress block, shown only for QUEUED/RUNNING; form POSTs to
  `@{/laeufe/{id}/abbrechen(id=${run.id})}` like the Ausgangsbestand form.
- Modify: [`src/main/resources/templates/fragments/fortschritt.html`]
  — a button in the fragment that dispatches `@click="$dispatch('abbrechen-offen')"` so the
  page-level panel opens (dispatched events survive the 3s fragment swap, panel state does not).
- Test: [`src/test/java/dev/hendrikhoemberg/webtesthelper/web/RunControllerTest.java`]

**Interfaces:**
- Consumes: `POST /laeufe/{id}/abbrechen` (Task 4), message keys from Task 4.

- [ ] **Step 1: Write the failing tests** — add to `RunControllerTest`:

  ```java
  @Test
  @WithMockUser(roles = "USER")
  void queuedRunDetailRendersTheCancelPanel() throws Exception {
      long runId = 110L;
      long siteId = 42L;
      when(runService.summary(runId)).thenReturn(sampleSummary(runId, siteId, RunStatus.QUEUED, false, false, null));
      when(siteService.contextFor(siteId)).thenReturn(sampleSite(siteId));

      mvc.perform(get("/laeufe/" + runId))
              .andExpect(status().isOk())
              .andExpect(content().string(containsString("Lauf abbrechen")))
              .andExpect(content().string(containsString("Jetzt abbrechen")))
              .andExpect(content().string(containsString("x-data=\"{ offen: false }\"")));
  }

  @Test
  @WithMockUser(roles = "USER")
  void completedRunDetailDoesNotRenderTheCancelPanel() throws Exception {
      long runId = 111L;
      long siteId = 42L;
      when(runService.summary(runId)).thenReturn(sampleSummary(runId, siteId, RunStatus.COMPLETED, false, false, null));
      when(siteService.contextFor(siteId)).thenReturn(sampleSite(siteId));
      when(findingService.diffOf(siteId, runId)).thenReturn(new RunDiff(runId, Map.of()));
      when(findingViewFactory.of(eq(new RunDiff(runId, Map.of())), any(Locale.class))).thenReturn(Map.of());

      mvc.perform(get("/laeufe/" + runId))
              .andExpect(status().isOk())
              .andExpect(content().string(not(containsString("Lauf abbrechen"))))
              .andExpect(content().string(not(containsString("Jetzt abbrechen"))));
  }

  @Test
  @WithMockUser(roles = "USER")
  void fortschrittForRunningRunOffersTheCancelDispatch() throws Exception {
      long runId = 112L;
      RunSummary summary = sampleSummary(runId, 42L, RunStatus.RUNNING, false, false, null);

      when(runService.summary(runId)).thenReturn(summary);

      mvc.perform(get("/laeufe/" + runId + "/fortschritt"))
              .andExpect(status().isOk())
              .andExpect(content().string(containsString("$dispatch('abbrechen-offen')")));
  }
  ```

- [ ] **Step 2: Run the single test — verify it FAILS**
  `./mvnw test -Dtest=RunControllerTest` → expected: FAIL (markup missing).

- [ ] **Step 3: Write minimal implementation**

  In `laeufe/detail.html`, directly after the live-progress `<th:block>` (line 28) add:
  ```html
  <!-- Abbrechen (nur bei laufenden Läufen) -->
  <section th:if="${run.status.name() == 'QUEUED' or run.status.name() == 'RUNNING'}"
           class="detail-bereich" x-data="{ offen: false }" @abbrechen-offen.window="offen = true">
      <h2 th:text="#{ui.lauf.abbrechen.titel}">Lauf abbrechen</h2>
      <div class="ausgangsbestand-zeile">
          <button type="button" @click="offen = !offen" class="button sekundär"
                  th:text="#{ui.lauf.abbrechen.button}">Lauf abbrechen</button>
      </div>
      <div x-show="offen" class="ausgangsbestand-panel" style="display: none; margin-top: 1rem;">
          <p th:text="#{ui.lauf.abbrechen.folge}">
              Der Prüflauf wird gestoppt, sobald der laufende Schritt beendet ist...
          </p>
          <form th:action="@{/laeufe/{id}/abbrechen(id=${run.id})}" method="post" class="inline-form">
              <button type="submit" class="button primär" th:text="#{ui.lauf.abbrechen.bestaetigen}">Jetzt abbrechen</button>
              <button type="button" @click="offen = false" class="button sekundär"
                      th:text="#{ui.lauf.ausgangsbestand.abbrechen}">Abbrechen</button>
          </form>
      </div>
  </section>
  ```
  In `fragments/fortschritt.html`, after the `<p>` lines, inside the `fortschritt` div:
  ```html
  <button type="button" class="button sekundär" @click="$dispatch('abbrechen-offen')"
          th:text="#{ui.lauf.abbrechen.button}">Lauf abbrechen</button>
  ```
  (The button also works on the standalone fragment: the dispatch is a no-op when no listener
  exists, the panel lives on the detail page it came from.)

- [ ] **Step 4: Run the single test — verify it PASSES**
  `./mvnw test -Dtest=RunControllerTest` → expected: PASS. Also
  `./mvnw test -Dtest=UiMessageKeyTest` → PASS (new keys resolve).

- [ ] **Step 5: Commit**
  `git commit -m "feat: cancel confirmation panel on the run detail page and progress fragment"`

---

## Self-Review

- **Spec coverage:** stop QUEUED (Task 1 cancel SQL + never claimed), stop RUNNING (Tasks 1–3
  checkpoints + worker), endpoint (Task 4), UI with confirmation (Task 5). ✓
- **Placeholder scan:** none — every task carries concrete SQL/markup/test code. ✓
- **Type consistency:** `RunLeaseJdbcRepository.cancel(long)` ↔ `RunService.cancel(long)`
  ↔ `POST /laeufe/{id}/abbrechen`; `RunCancelledException` caught only in `RunWorker`
  (generic `Exception` catch is later in the chain). ✓

**Final verification:** `./mvnw test` (full suite incl. browser acceptance) after all tasks.
