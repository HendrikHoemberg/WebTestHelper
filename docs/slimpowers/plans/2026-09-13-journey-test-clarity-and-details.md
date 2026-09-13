# Journey Test Clarity & Detailed Results Implementation Plan

**Goal:** Fix the journey test button loading indicator state, clarify selector drift messaging for non-technical users, and provide an in-place expandable step breakdown of test execution results.

**Architecture:** Update JourneyController to project replay results into a user-friendly view model, enhance `journey/ergebnis.html` with plain-German copy and an Alpine.js collapsible step outcome list, and fix the HTMX button indicator CSS and markup.

**Tech Stack:** Spring Boot 3, Thymeleaf, HTMX, Alpine.js, JUnit 5, MockMvc

**Spec:** In-chat approved design (Variante A: Inline expandable step results + CSS button fix + plain German drift explanation)

## Global Constraints
- Desktop-only UI (no mobile breakpoints or collapsible sidebar)
- German-only UI; message keys ui.*
- No internal identifiers (enum names, raw ISO instants, {0} placeholders) in rendered HTML
- View tests use @WebMvcTest + MockMvc; assertions on text/markup, not CSS

---

### Task 1: Button Loading State & HTMX Indicator Fix

**Files:**
- Modify: `src/main/resources/static/css/app.css` (add `!important` to `.htmx-request .htmx-indicator-hide`, add `.btn-spinner` and flex styling for `.htmx-request .htmx-indicator`)
- Modify: `src/main/resources/templates/journey/list.html` (use CSS classes for indicator hide/show, add `data-hx-disabled-elt="this"`)
- Modify: `src/main/resources/templates/journey/detail.html` (use CSS classes for indicator hide/show, add `data-hx-disabled-elt="this"`)
- Test: `src/test/java/dev/hendrikhoemberg/webtesthelper/web/JourneyControllerTest.java`

**Interfaces:**
- Produces: Correct button toggle behavior during HTMX requests so "Jetzt testen" is replaced cleanly by spinner + "Wird geprüft …", and the button is disabled while the test is running.

- [ ] **Step 1: Write the failing test**
  In `JourneyControllerTest.java`, add assertions verifying that the run button on both the journey list and journey detail views includes `data-hx-disabled-elt="this"` and does not mix inline styles with indicator classes.

```java
    @Test
    @WithMockUser(roles = "USER")
    void listJourneys_runButtonHasDisabledEltAndIndicatorClasses() throws Exception {
        JourneyStep step = new JourneyStep(UUID.randomUUID(), 0, StepAction.GOTO, List.of(), "https://example.com", null, false, 5000);
        JourneyDefinition journey = new JourneyDefinition(42L, 1L, "Warenkorb", true, List.of(step));
        when(journeyService.findBySiteId(1L)).thenReturn(List.of(journey));

        mvc.perform(get("/websites/1/journeys"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("data-hx-disabled-elt=\"this\"")));
    }
```

- [ ] **Step 2: Run the single test — verify it FAILS**
  Command: `./mvnw test -Dtest=JourneyControllerTest#listJourneys_runButtonHasDisabledEltAndIndicatorClasses`
  Expected: FAIL because `data-hx-disabled-elt="this"` is not present yet.

- [ ] **Step 3: Write minimal implementation**
  1. In `app.css`:
     - Change `.htmx-request .htmx-indicator-hide { display: none !important; }`
     - Update `.htmx-indicator` and `.htmx-request .htmx-indicator` so it displays as `inline-flex` with spinner when active.
     - Add `.btn-spinner` style.
  2. In `journey/list.html` & `journey/detail.html`:
     - Add `data-hx-disabled-elt="this"` to the test button.
     - Update button indicator markup to include a subtle spinner and clean classes.

- [ ] **Step 4: Run the single test — verify it PASSES**
  Command: `./mvnw test -Dtest=JourneyControllerTest#listJourneys_runButtonHasDisabledEltAndIndicatorClasses`
  Expected: PASS

- [ ] **Step 5: Commit**
  `git commit -m "fix(journey): button loading indicator and double-click prevention"`

---

### Task 2: Step Execution View Model & German Messages

**Files:**
- Create: `src/main/java/dev/hendrikhoemberg/webtesthelper/web/JourneyStepResultView.java`
- Modify: `src/main/java/dev/hendrikhoemberg/webtesthelper/web/JourneyController.java`
- Modify: `src/main/resources/messages.properties`
- Test: `src/test/java/dev/hendrikhoemberg/webtesthelper/web/JourneyControllerTest.java`

**Interfaces:**
- Produces: `JourneyStepResultView` record pairing each `JourneyStep` with its corresponding `StepOutcome`, formatted target summary, winner explanation in plain German (e.g. `Ausweich-Erkennung verwendet: Text („In den Warenkorb“)`), and human-friendly status label.
- Exposed to model as `stepResults` in `runNow`.

- [ ] **Step 1: Write the failing test**
  In `JourneyControllerTest.java`, add a test for a `DRIFTED` replay outcome verifying that `stepResults` model attribute is populated and plain-language winner details are rendered.

```java
    @Test
    @WithMockUser(roles = "ADMIN")
    void runNow_whenDrifted_rendersFriendlyBannerAndStepDetails() throws Exception {
        UUID stepId = UUID.randomUUID();
        LocatorCandidate primary = new LocatorCandidate(LocatorStrategy.TEST_ID, "btn-submit", 0);
        LocatorCandidate fallback = new LocatorCandidate(LocatorStrategy.TEXT, "Jetzt kaufen", 1);
        JourneyStep step = new JourneyStep(stepId, 0, StepAction.CLICK, List.of(primary, fallback), null, null, false, 5000);
        JourneyDefinition journey = new JourneyDefinition(10L, 1L, "Kaufabschluss", true, List.of(step));
        when(journeyService.findDefinition(10L)).thenReturn(Optional.of(journey));

        StepOutcome driftedOutcome = StepOutcome.drifted(stepId, fallback);
        JourneyReplayResult result = new JourneyReplayResult(10L, "Kaufabschluss", ReplayStatus.DRIFTED, List.of(driftedOutcome), 1, Optional.empty(), Optional.empty());
        when(journeyReplayer.replay(eq(journey), eq(testSite), isNull())).thenReturn(result);
        when(journeyHealthService.record(10L, result)).thenReturn(new JourneyHealth(Instant.now(), 0, 1));

        mvc.perform(post("/websites/1/journeys/10/jetzt-ausfuehren").with(csrf()))
                .andExpect(status().isOk())
                .andExpect(model().attributeExists("stepResults"))
                .andExpect(content().string(containsString("automatische Anpassung")))
                .andExpect(content().string(containsString("Jetzt kaufen")));
    }
```

- [ ] **Step 2: Run the single test — verify it FAILS**
  Command: `./mvnw test -Dtest=JourneyControllerTest#runNow_whenDrifted_rendersFriendlyBannerAndStepDetails`
  Expected: FAIL (`stepResults` not present / string not found)

- [ ] **Step 3: Write minimal implementation**
  1. Create `JourneyStepResultView(int ordinal, String actionLabel, String targetSummary, String statusBadgeClass, String statusLabel, boolean drifted, String winnerDetails, String failureMessage)`.
  2. In `JourneyController.java`, implement `buildStepResults(JourneyDefinition journey, JourneyReplayResult result, Locale locale)`:
     - Maps each step by UUID to its outcome.
     - Resolves strategy label via `messageSource.getMessage("ui.journey.strategy." + outcome.winner().strategy().name(), null, locale)`.
     - Builds human-friendly `winnerDetails` in German: e.g. `Ausweich-Erkennung verwendet: Text („In den Warenkorb“)`.
  3. In `messages.properties`, define keys:
     - `ui.journey.test.drift.titel=Ablauf erfolgreich durchgeführt (mit automatischer Anpassung)`
     - `ui.journey.test.drift.hinweis=Alle Schritte wurden erfolgreich ausgeführt. Auf der Website haben sich einzelne Elemente verändert – WebTestHelper konnte sie über alternative Erkennungsmerkmale trotzdem finden. Es besteht aktuell kein Handlungsbedarf.`
     - `ui.journey.test.erfolgreich.hinweis=Alle Schritte wurden fehlerfrei und ohne Abweichungen ausgeführt.`
     - `ui.journey.test.schritte_anzeigen=Schritt-Ergebnisse anzeigen`
     - `ui.journey.test.schritte_verbergen=Schritt-Ergebnisse verbergen`
     - `ui.journey.test.schritt.status.erfolgreich=Erfolgreich`
     - `ui.journey.test.schritt.status.angepasst=Angepasst`
     - `ui.journey.test.schritt.status.fehlgeschlagen=Fehlgeschlagen`
     - `ui.journey.test.schritt.status.uebersprungen=Übersprungen`
     - `ui.journey.test.schritt.ausweich_verwendet=Ausweich-Erkennung verwendet: {0} („{1}“)`

- [ ] **Step 4: Run the single test — verify it PASSES**
  Command: `./mvnw test -Dtest=JourneyControllerTest#runNow_whenDrifted_rendersFriendlyBannerAndStepDetails`
  Expected: PASS

- [ ] **Step 5: Commit**
  `git commit -m "feat(journey): add step results view model and friendly German messaging"`

---

### Task 3: Expandable Step Results UI in Result Fragment

**Files:**
- Modify: `src/main/resources/templates/journey/ergebnis.html`
- Test: `src/test/java/dev/hendrikhoemberg/webtesthelper/web/JourneyControllerTest.java`

**Interfaces:**
- Produces: Rich, accessible HTML fragment rendered upon test execution containing:
  - Clear status title and non-technical explanation
  - Alpine.js expandable disclosure (`x-data="{ offen: false }"`)
  - Formatted step-by-step table showing ordinal, action, target summary, status badge, and winner/failure annotations
  - Dismiss ("Schließen") button

- [ ] **Step 1: Write the failing test**
  In `JourneyControllerTest.java`, assert that `ergebnis` includes the step table markup and toggle button.

```java
    @Test
    @WithMockUser(roles = "ADMIN")
    void runNow_rendersExpandableStepTableMarkup() throws Exception {
        JourneyStep step1 = new JourneyStep(UUID.randomUUID(), 0, StepAction.GOTO, List.of(), "https://acme.example.com", null, false, 5000);
        JourneyDefinition journey = new JourneyDefinition(10L, 1L, "Start", true, List.of(step1));
        when(journeyService.findDefinition(10L)).thenReturn(Optional.of(journey));

        JourneyReplayResult result = new JourneyReplayResult(10L, "Start", ReplayStatus.PASSED, List.of(StepOutcome.passed(step1.id(), null)), 0, Optional.empty(), Optional.empty());
        when(journeyReplayer.replay(eq(journey), eq(testSite), isNull())).thenReturn(result);
        when(journeyHealthService.record(10L, result)).thenReturn(new JourneyHealth(Instant.now(), 0, 0));

        mvc.perform(post("/websites/1/journeys/10/jetzt-ausfuehren").with(csrf()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("journey-test-ergebnis-schritte")))
                .andExpect(content().string(containsString("Schritt-Ergebnisse anzeigen")));
    }
```

- [ ] **Step 2: Run the single test — verify it FAILS**
  Command: `./mvnw test -Dtest=JourneyControllerTest#runNow_rendersExpandableStepTableMarkup`
  Expected: FAIL (missing markup)

- [ ] **Step 3: Write minimal implementation**
  Update `src/main/resources/templates/journey/ergebnis.html`:
  - Wrap the result card in an Alpine component `x-data="{ detailsOffen: false, sichtbar: true }" x-show="sichtbar"`.
  - Render a dismiss button (`@click="sichtbar = false"`).
  - Render title & explanation according to status (`PASSED`, `DRIFTED`, `FAILED`).
  - Render toggle button for step details: `@click="detailsOffen = !detailsOffen"`.
  - In `journey-test-ergebnis-schritte`, render each step with:
    - Step number and action tag
    - Target summary
    - Status badge (badge-healthy / badge-warning / badge-error)
    - If drifted: annotation showing fallback locator strategy & value
    - If failed: failure explanation message

- [ ] **Step 4: Run the single test — verify it PASSES**
  Command: `./mvnw test -Dtest=JourneyControllerTest#runNow_rendersExpandableStepTableMarkup`
  Expected: PASS

- [ ] **Step 5: Commit**
  `git commit -m "feat(journey): render expandable step details in test result fragment"`

---

### Task 4: Full Suite Verification

- [ ] **Step 1: Run default test suite**
  Command: `bash -c "set -o pipefail; ./mvnw test -Pfast -B --no-transfer-progress | tail -n 50"`
  Expected: PASS with 0 failures
- [ ] **Step 2: Run verification before completion**
  Check git status and diff.
