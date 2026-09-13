# Failed Run Retry Implementation Plan

**Goal:** Allow users to directly restart/retry a failed test run from both the run detail page and the website's runs history table, preserving the original run scope.

**Architecture:** A new POST endpoint `/laeufe/{id}/wiederholen` in `RunController` inspects the run's `siteId` and `scope`, enqueues a new manual run via `RunService`, and redirects directly to the newly created run. In the UI, a primary CTA button is added to `laeufe/detail.html` (in the header actions cluster and alongside technical failure details), and a row action button is added to `websites/laeufe.html` for runs with status `FAILED`.

**Tech Stack:** Spring Boot (MVC, Security), Thymeleaf, Alpine.js, JUnit 5 + MockMvc.

**Spec:** User request to implement contextual retry for failed runs.

## Global Constraints
- German-only UI copy via `src/main/resources/messages.properties`, key prefix `ui.*`.
- View assertions tested with MockMvc on text and markup, not on CSS styling.
- All post forms must include CSRF tokens (standard Thymeleaf `th:action`).
- Test output must be bundled with `-B --no-transfer-progress` and piped through `tail`.

---

### Task 1: Controller Endpoint for Run Retry

**Files:**
- Modify: `src/main/java/dev/hendrikhoemberg/webtesthelper/web/RunController.java`
- Test: `src/test/java/dev/hendrikhoemberg/webtesthelper/web/RunControllerTest.java`

**Interfaces:**
- Consumes: `POST /laeufe/{id}/wiederholen`
- Produces: Queries `runService.summary(id)`, enqueues `runService.enqueue(summary.siteId(), RunTrigger.MANUAL, summary.scope())`, redirects to `/laeufe/{newRunId}`.

- [ ] **Step 1: Write the failing test**
  Add to `RunControllerTest.java`:
  ```java
  @Test
  @WithMockUser(roles = "USER")
  void wiederholenEnqueuesRunWithSameScopeAndRedirectsToNewRun() throws Exception {
      long failedRunId = 101L;
      long siteId = 42L;
      long newRunId = 102L;
      RunSummary failedRun = sampleSummary(failedRunId, siteId, RunStatus.FAILED, false, false, "Konnte Host nicht auflösen");
      when(runService.summary(failedRunId)).thenReturn(failedRun);
      when(runService.enqueue(siteId, RunTrigger.MANUAL, RunScope.FULL)).thenReturn(newRunId);

      mvc.perform(post("/laeufe/" + failedRunId + "/wiederholen").with(csrf()))
              .andExpect(status().is3xxRedirection())
              .andExpect(redirectedUrl("/laeufe/" + newRunId));

      verify(runService).enqueue(siteId, RunTrigger.MANUAL, RunScope.FULL);
  }
  ```
- [ ] **Step 2: Run the single test — verify it FAILS**
  Command: `./mvnw test -Dtest=RunControllerTest#wiederholenEnqueuesRunWithSameScopeAndRedirectsToNewRun -B --no-transfer-progress`
  Expected: FAIL with status 404 (endpoint does not exist).
- [ ] **Step 3: Write minimal implementation**
  In `RunController.java`:
  ```java
  @PostMapping("/{id}/wiederholen")
  public String wiederholen(@PathVariable("id") long id) {
      RunSummary run = runService.summary(id);
      long newRunId = runService.enqueue(run.siteId(), RunTrigger.MANUAL, run.scope());
      return "redirect:/laeufe/" + newRunId;
  }
  ```
- [ ] **Step 4: Run the single test — verify it PASSES**
  Command: `./mvnw test -Dtest=RunControllerTest#wiederholenEnqueuesRunWithSameScopeAndRedirectsToNewRun -B --no-transfer-progress`
  Expected: PASS.
- [ ] **Step 5: Commit**
  `git commit -m "feat(web): add endpoint to retry run preserving scope"`

---

### Task 2: Retry Action on Run Detail View

**Files:**
- Modify: `src/main/resources/messages.properties`
- Modify: `src/main/resources/templates/laeufe/detail.html`
- Test: `src/test/java/dev/hendrikhoemberg/webtesthelper/web/RunControllerTest.java`

**Interfaces:**
- Consumes: `RunSummary` with status `FAILED` in model.
- Produces: Renders `<form th:action="@{/laeufe/{id}/wiederholen(id=${run.id})}" method="post">` button in header actions and beside technical details.

- [ ] **Step 1: Write the failing test**
  Add to `RunControllerTest.java`:
  ```java
  @Test
  @WithMockUser(roles = "USER")
  void failedRunDetailRendersRetryButtons() throws Exception {
      long runId = 101L;
      long siteId = 42L;
      RunSummary summary = sampleSummary(runId, siteId, RunStatus.FAILED, false, false, "Verbindungsfehler");
      SiteContext site = sampleSite(siteId);

      when(runService.summary(runId)).thenReturn(summary);
      when(siteService.contextFor(siteId)).thenReturn(site);
      when(findingService.diffForReport(siteId, runId)).thenReturn(new RunDiff(runId, Map.of()));
      when(findingViewFactory.of(any(), any())).thenReturn(Map.of());

      mvc.perform(get("/laeufe/" + runId))
              .andExpect(status().isOk())
              .andExpect(content().string(containsString("/laeufe/" + runId + "/wiederholen")))
              .andExpect(content().string(containsString("Prüflauf wiederholen")));
  }

  @Test
  @WithMockUser(roles = "USER")
  void completedRunDetailDoesNotRenderRetryButton() throws Exception {
      long runId = 101L;
      long siteId = 42L;
      RunSummary summary = sampleSummary(runId, siteId, RunStatus.COMPLETED, false, false, null);
      SiteContext site = sampleSite(siteId);

      when(runService.summary(runId)).thenReturn(summary);
      when(siteService.contextFor(siteId)).thenReturn(site);
      when(findingService.diffForReport(siteId, runId)).thenReturn(new RunDiff(runId, Map.of()));
      when(findingViewFactory.of(any(), any())).thenReturn(Map.of());

      mvc.perform(get("/laeufe/" + runId))
              .andExpect(status().isOk())
              .andExpect(content().string(not(containsString("/laeufe/" + runId + "/wiederholen"))));
  }
  ```
- [ ] **Step 2: Run the tests — verify it FAILS**
  Command: `./mvnw test -Dtest=RunControllerTest#failedRunDetailRendersRetryButtons -B --no-transfer-progress`
  Expected: FAIL with missing retry button markup.
- [ ] **Step 3: Write minimal implementation**
  1. In `src/main/resources/messages.properties`:
     ```properties
     ui.lauf.aktion.wiederholen=Prüflauf wiederholen
     ui.lauf.aktion.wiederholen_kurz=Wiederholen
     ```
  2. In `src/main/resources/templates/laeufe/detail.html`:
     In header actions:
     ```html
     <form th:if="${run.status.name() == 'FAILED'}" th:action="@{/laeufe/{id}/wiederholen(id=${run.id})}" method="post" class="inline-form">
         <button type="submit" class="btn-ui btn-ui-primary button primär">
             <span th:replace="~{fragments/icons :: refresh}"></span>
             <span th:text="#{ui.lauf.aktion.wiederholen}">Prüflauf wiederholen</span>
         </button>
     </form>
     ```
     In "Technische Details" section:
     ```html
     <section th:if="${run.errorMessage != null and not #strings.isEmpty(run.errorMessage)}" class="card-box detail-bereich">
         <div style="display: flex; align-items: center; justify-content: space-between; margin-bottom: 1rem;">
             <h2 style="margin: 0; border: none; padding: 0;" th:text="#{ui.lauf.technische_details.titel}">Technische Details</h2>
             <form th:action="@{/laeufe/{id}/wiederholen(id=${run.id})}" method="post" class="inline-form">
                 <button type="submit" class="btn-ui btn-ui-primary button primär btn-ui-sm">
                     <span th:replace="~{fragments/icons :: refresh}"></span>
                     <span th:text="#{ui.lauf.aktion.wiederholen}">Prüflauf wiederholen</span>
                 </button>
             </form>
         </div>
         <pre class="technischer-block" style="background: #09090b; color: #f4f4f5; padding: 1rem; border-radius: 8px; font-family: var(--font-mono); font-size: 0.8rem; overflow-x: auto;" th:text="${run.errorMessage}">Fehlermeldung...</pre>
     </section>
     ```
- [ ] **Step 4: Run the tests — verify they PASS**
  Command: `./mvnw test -Dtest=RunControllerTest#failedRunDetailRendersRetryButtons,RunControllerTest#completedRunDetailDoesNotRenderRetryButton -B --no-transfer-progress`
  Expected: PASS.
- [ ] **Step 5: Commit**
  `git commit -m "feat(web): add retry CTA to run detail view for failed runs"`

---

### Task 3: Retry Action on Website Runs Table View

**Files:**
- Modify: `src/main/resources/templates/websites/laeufe.html`
- Test: `src/test/java/dev/hendrikhoemberg/webtesthelper/web/SiteDetailControllerTest.java`

**Interfaces:**
- Consumes: List of `RunSummary` in `websites/laeufe.html`.
- Produces: Renders retry button alongside `Ansehen` for rows where `run.status == FAILED`.

- [ ] **Step 1: Write the failing test**
  Add to `SiteDetailControllerTest.java`:
  ```java
  @Test
  @WithMockUser(roles = "USER")
  void runsTableRendersRetryButtonForFailedRun() throws Exception {
      long siteId = 42L;
      SiteContext site = sampleSite(siteId);
      RunSummary failedRun = new RunSummary(
              101L, siteId, RunStatus.FAILED, RunTrigger.MANUAL, RunScope.FULL,
              Instant.now(), Instant.now(), Instant.now(), 10, 2, 0, false, false, "Konnte Host nicht auflösen"
      );

      when(siteService.contextFor(siteId)).thenReturn(site);
      when(runService.recentForSite(eq(siteId), any(Integer.class))).thenReturn(List.of(failedRun));
      when(findingService.openCounts(siteId)).thenReturn(new OpenFindingCounts(0, 0, 0, 0));

      mvc.perform(get("/websites/" + siteId + "/laeufe"))
              .andExpect(status().isOk())
              .andExpect(content().string(containsString("/laeufe/101/wiederholen")))
              .andExpect(content().string(containsString("Wiederholen")));
  }
  ```
- [ ] **Step 2: Run the test — verify it FAILS**
  Command: `./mvnw test -Dtest=SiteDetailControllerTest#runsTableRendersRetryButtonForFailedRun -B --no-transfer-progress`
  Expected: FAIL.
- [ ] **Step 3: Write minimal implementation**
  In `src/main/resources/templates/websites/laeufe.html`:
  Update `td.zell-aktion`:
  ```html
  <td class="text-rechts zell-aktion">
      <div style="display: flex; align-items: center; justify-content: flex-end; gap: 0.5rem;">
          <form th:if="${run.status.name() == 'FAILED'}" th:action="@{/laeufe/{id}/wiederholen(id=${run.id})}" method="post" class="inline-form">
              <button type="submit" class="btn-ui btn-ui-secondary btn-ui-sm" th:title="#{ui.lauf.aktion.wiederholen}">
                  <span th:replace="~{fragments/icons :: refresh}"></span>
                  <span th:text="#{ui.lauf.aktion.wiederholen_kurz}">Wiederholen</span>
              </button>
          </form>
          <a th:href="@{/laeufe/{id}(id=${run.id})}" class="btn-ui btn-ui-secondary btn-ui-sm" th:text="#{ui.websites.detail.laeufe.ansehen}">Ansehen</a>
      </div>
  </td>
  ```
- [ ] **Step 4: Run the test — verify it PASSES**
  Command: `./mvnw test -Dtest=SiteDetailControllerTest#runsTableRendersRetryButtonForFailedRun -B --no-transfer-progress`
  Expected: PASS.
- [ ] **Step 5: Commit**
  `git commit -m "feat(web): add retry action to runs history table for failed runs"`

---

### Task 4: Verification

- [ ] **Step 1: Run Fast Test Suite**
  `bash -c "set -o pipefail; ./mvnw test -Pfast -B --no-transfer-progress | tail -n 50"`
- [ ] **Step 2: Run Full Suite if necessary**
  `bash -c "set -o pipefail; ./mvnw test -B --no-transfer-progress | tail -n 60"`
