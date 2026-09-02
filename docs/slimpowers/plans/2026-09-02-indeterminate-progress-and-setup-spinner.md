# Indeterminate Progress Bar & Setup Spinner Implementation Plan

**Goal:** Fix the glitchy disappearing/pulsing loading bar on running test runs by replacing `pulse-ring` with a smooth indeterminate slide animation, and replace the confusing pulsing dot on `/einrichtung` with a clean, centered loading spinner.

**Architecture:** Update `app.css` to add smooth CSS keyframe animations for indeterminate progress bar sliding and circular spinning. Update Thymeleaf fragments `fortschritt.html` and `einrichtungsstand.html` to consume the new classes instead of reusing `status-dot-pulse` / `pulse-ring`.

**Tech Stack:** Spring Boot, Thymeleaf, HTML5, CSS3, JUnit 5, MockMvc

**Spec:** User request in chat session (2026-09-02)

## Global Constraints

- High-contrast monochrome carbon UI styling (`--text-main: #09090b`, `--border-subtle: #e2e8f0`).
- Desktop-first layout; German UI text.
- No raw CSS identifiers or glitches in rendered templates.
- Strict TDD: tests written and observed failing before minimal implementations.

---

### Task 1: Fix Run Progress Bar Animation (Smooth Indeterminate Slide)

**Files:**
- Modify: `src/main/resources/static/css/app.css` (add `.capacity-bar-indeterminate` and `@keyframes indeterminate-slide`)
- Modify: `src/main/resources/templates/fragments/fortschritt.html` (use `.capacity-bar-indeterminate` without inline `pulse-ring` animation)
- Test: `src/test/java/dev/hendrikhoemberg/webtesthelper/web/RunControllerTest.java`

**Interfaces:**
- Consumes: Run model `run.status` in `fortschritt.html`
- Produces: Smooth indeterminate sliding animation across `.capacity-bar-track`

- [x] **Step 1: Write the failing test**
  In `src/test/java/dev/hendrikhoemberg/webtesthelper/web/RunControllerTest.java`, add:
  ```java
  @Test
  @WithMockUser(roles = "USER")
  void fortschrittCarriesIndeterminateProgressBarAndNoPulseRing() throws Exception {
      Long runId = 42L;
      when(runOverviewService.getFortschritt(runId)).thenReturn(Optional.of(runningProgress(runId)));

      mvc.perform(get("/laeufe/" + runId + "/fortschritt"))
              .andExpect(status().isOk())
              .andExpect(content().string(containsString("capacity-bar-indeterminate")))
              .andExpect(content().string(not(containsString("pulse-ring"))));
  }
  ```

- [x] **Step 2: Run the single test — verify it FAILS**
  Command:
  `./mvnw test -Dtest=RunControllerTest#fortschrittCarriesIndeterminateProgressBarAndNoPulseRing`
  Expected: FAIL (assertion error: `capacity-bar-indeterminate` not found and `pulse-ring` found)

- [x] **Step 3: Write minimal implementation**
  In `src/main/resources/static/css/app.css`, add:
  ```css
  .capacity-bar-indeterminate {
      width: 35%;
      height: 100%;
      background: linear-gradient(90deg, #18181b 0%, #3f3f46 100%);
      border-radius: 9999px;
      animation: indeterminate-slide 1.6s cubic-bezier(0.4, 0, 0.2, 1) infinite;
  }

  @keyframes indeterminate-slide {
      0% {
          transform: translateX(-100%);
      }
      100% {
          transform: translateX(300%);
      }
  }
  ```
  In `src/main/resources/templates/fragments/fortschritt.html`, replace:
  ```html
  <div class="capacity-bar-track" style="margin-bottom: 0.75rem;">
      <div class="capacity-bar-fill" style="width: 60%; animation: pulse-ring 2s infinite;"></div>
  </div>
  ```
  with:
  ```html
  <div class="capacity-bar-track" style="margin-bottom: 0.75rem;">
      <div class="capacity-bar-indeterminate"></div>
  </div>
  ```

- [x] **Step 4: Run the single test — verify it PASSES**
  Command:
  `./mvnw test -Dtest=RunControllerTest#fortschrittCarriesIndeterminateProgressBarAndNoPulseRing`
  Expected: PASS

- [x] **Step 5: Commit**
  `git commit -m "fix(ui): use smooth indeterminate progress bar animation for active runs"`

---

### Task 2: Replace Pulsing Dot with Loading Spinner on /einrichtung

**Files:**
- Modify: `src/main/resources/static/css/app.css` (add `.loading-spinner` and `@keyframes wth-spin`)
- Modify: `src/main/resources/templates/fragments/einrichtungsstand.html` (replace `status-dot-pulse` with centered `.loading-spinner`)
- Test: `src/test/java/dev/hendrikhoemberg/webtesthelper/web/SetupControllerTest.java`

**Interfaces:**
- Consumes: `laeuft` boolean in `fragments/einrichtungsstand.html`
- Produces: Centered circular loading spinner indicating active site scan

- [x] **Step 1: Write the failing test**
  In `src/test/java/dev/hendrikhoemberg/webtesthelper/web/SetupControllerTest.java`, update `standWhileRunningCarriesTheTriggerAndTheWaitingSentence()`:
  ```java
  @Test
  @WithMockUser(roles = "USER")
  void standWhileRunningCarriesTheTriggerAndTheWaitingSentence() throws Exception {
      when(siteService.summary(SITE_ID)).thenReturn(summary());
      when(setupProbeService.stateOf(SITE_ID)).thenReturn(Optional.of(laeuft()));

      MvcResult result = mvc.perform(get("/websites/" + SITE_ID + "/einrichtung/stand"))
              .andExpect(status().isOk())
              .andExpect(view().name("fragments/einrichtungsstand :: stand"))
              .andExpect(content().string(org.hamcrest.Matchers.containsString("hx-trigger=\"every 2s\"")))
              .andExpect(content().string(org.hamcrest.Matchers.containsString("hx-get=\"/websites/" + SITE_ID + "/einrichtung/stand\"")))
              .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("@{/websites"))))
              .andExpect(content().string(org.hamcrest.Matchers.containsString("Wir untersuchen die Website")))
              .andExpect(content().string(org.hamcrest.Matchers.containsString("loading-spinner")))
              .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("status-dot-pulse"))))
              .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("<!DOCTYPE"))))
              .andReturn();

      String body = result.getResponse().getContentAsString();
      assertThat(body).doesNotContain("name=\"aktiv\"");
  }
  ```

- [x] **Step 2: Run the single test — verify it FAILS**
  Command:
  `./mvnw test -Dtest=SetupControllerTest#standWhileRunningCarriesTheTriggerAndTheWaitingSentence`
  Expected: FAIL (assertion error: `loading-spinner` not found and `status-dot-pulse` found)

- [x] **Step 3: Write minimal implementation**
  In `src/main/resources/static/css/app.css`, add:
  ```css
  .loading-spinner {
      width: 2rem;
      height: 2rem;
      margin: 0 auto 1rem;
      border: 2.5px solid var(--border-subtle);
      border-top-color: var(--text-main);
      border-radius: 9999px;
      animation: wth-spin 0.75s linear infinite;
  }

  @keyframes wth-spin {
      to {
          transform: rotate(360deg);
      }
  }
  ```
  In `src/main/resources/templates/fragments/einrichtungsstand.html`, update lines 10-13:
  ```html
  <div th:if="${laeuft}" class="einrichtungsstand-laeuft card-box" style="background: var(--surface-subtle); padding: 2rem 1.5rem; text-align: center; border: 1px solid var(--border-subtle);">
      <div class="loading-spinner" aria-hidden="true"></div>
      <p style="margin: 0; font-size: 0.95rem; font-weight: 500;" th:text="#{ui.einrichtung.laeuft}">Wir untersuchen die Website gerade — das kann einen Moment dauern.</p>
  </div>
  ```

- [x] **Step 4: Run the single test — verify it PASSES**
  Command:
  `./mvnw test -Dtest=SetupControllerTest#standWhileRunningCarriesTheTriggerAndTheWaitingSentence`
  Expected: PASS

- [x] **Step 5: Commit**
  `git commit -m "feat(ui): replace confusing pulsing dot with loading spinner on einrichtung page"`

---

### Task 3: Full Verification Suite
- [x] Run full test suite:
  `bash -c "set -o pipefail; ./mvnw test -B --no-transfer-progress | tail -n 60"`
  Expected: All tests pass.
