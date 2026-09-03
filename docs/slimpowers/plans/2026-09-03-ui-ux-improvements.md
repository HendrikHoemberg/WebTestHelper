# UI/UX Polish & Layout Alignment Implementation Plan

**Goal:** Resolve all 6 identified UI/UX alignment and labeling findings across help, user management, journeys, recorder, setup, and subpage headers.

**Architecture:** Refine Thymeleaf templates and `app.css` styles to enforce consistent flexbox/grid alignments, deduplicate redundant UI controls, clarify step metadata labels, and standardize heading structures without altering business logic.

**Tech Stack:** Java 21, Spring Boot 4, Thymeleaf, Spring Security, HTMX, Alpine.js, CSS custom properties.

**Spec:** Antigravity UI/UX Evaluation (Findings 1 through 6)

## Global Constraints
- German-only UI copy via message keys `ui.*`.
- View tests use `@WebMvcTest` and MockMvc with assertions on text/markup.
- Desktop-only layout conventions (no mobile collapsible sidebar).
- Test execution output piped with `set -o pipefail` and `tail` to maintain context hygiene.
- Verification command before completion: `./mvnw test -Pfast -B --no-transfer-progress`.

---

### Task 1: Help Index Card Equal Heights and Bottom-Aligned Buttons

**Files:**
- Modify: `src/main/resources/static/css/app.css`
- Modify: `src/main/resources/templates/hilfe/index.html`
- Test: `src/test/java/dev/hendrikhoemberg/webtesthelper/web/HelpControllerTest.java`

**Interfaces:**
- Consumes: `/hilfe` route and `HelpTopic` model list
- Produces: CSS grid layout `.hilfe-themen-liste` where each card `.hilfe-thema-karte` fills 100% height and has its `.hilfe-kachel-aktion` anchored to the bottom via `margin-top: auto`.

- [ ] **Step 1: Write the failing test**
  Add a test method `hilfeIndexRendersCardContainerAndStickyActions` to `src/test/java/dev/hendrikhoemberg/webtesthelper/web/HelpControllerTest.java`:
  ```java
  @Test
  @WithMockUser
  void hilfeIndexRendersCardContainerAndStickyActions() throws Exception {
      HelpTopic topic = new HelpTopic("test-topic", "Test Titel", "<p>HTML</p>", "<p>Teaser</p>");
      when(helpService.all()).thenReturn(List.of(topic));

      mvc.perform(get("/hilfe"))
              .andExpect(status().isOk())
              .andExpect(content().string(containsString("hilfe-themen-liste")))
              .andExpect(content().string(containsString("hilfe-kachel-aktion")));
  }
  ```
- [ ] **Step 2: Run the single test — verify it FAILS**
  Command: `bash -c "set -o pipefail; ./mvnw test -Dtest=HelpControllerTest#hilfeIndexRendersCardContainerAndStickyActions -B --no-transfer-progress | tail -n 25"`
  Expected: FAIL due to missing `hilfe-kachel-aktion` in template.
- [ ] **Step 3: Write minimal implementation**
  In `src/main/resources/templates/hilfe/index.html`, wrap the button in `<div class="hilfe-kachel-aktion">`.
  In `src/main/resources/static/css/app.css`, add `.hilfe-themen-liste` and `.hilfe-thema-karte` classes ensuring `display: flex; flex-direction: column; height: 100%; margin-bottom: 0;` and `.hilfe-kachel-aktion { margin-top: auto; padding-top: 1rem; }`.
- [ ] **Step 4: Run the single test — verify it PASSES**
  Command: `bash -c "set -o pipefail; ./mvnw test -Dtest=HelpControllerTest#hilfeIndexRendersCardContainerAndStickyActions -B --no-transfer-progress | tail -n 25"`
  Expected: PASS.
- [ ] **Step 5: Commit**
  `git commit -m "fix(ui): ensure equal card heights and sticky bottom buttons on help index"`

---

### Task 2: User Table Row Height and Horizontal Button Alignment

**Files:**
- Modify: `src/main/resources/templates/einstellungen/benutzer.html`
- Modify: `src/main/resources/static/css/app.css`
- Test: `src/test/java/dev/hendrikhoemberg/webtesthelper/web/UserControllerTest.java`

**Interfaces:**
- Consumes: `/einstellungen/benutzer` view model `benutzer`
- Produces: Action buttons grouped cleanly in horizontal flex layout (`benutzer-aktionen-gruppe`) so non-admin users with multiple controls do not double the table row height.

- [ ] **Step 1: Write the failing test**
  Add a test method in `src/test/java/dev/hendrikhoemberg/webtesthelper/web/UserControllerTest.java`:
  ```java
  @Test
  @WithMockUser(roles = "ADMIN")
  void regularUserActionsRendersHorizontalGroup() throws Exception {
      when(appUserService.list()).thenReturn(List.of(
              new AppUserSummary(2L, "bob", AppRole.USER, true, Instant.now())
      ));
      when(appUserService.enabledAdminCount()).thenReturn(2L);

      mvc.perform(get("/einstellungen/benutzer"))
              .andExpect(status().isOk())
              .andExpect(content().string(containsString("benutzer-aktionen-gruppe")));
  }
  ```
- [ ] **Step 2: Run the single test — verify it FAILS**
  Command: `bash -c "set -o pipefail; ./mvnw test -Dtest=UserControllerTest#regularUserActionsRendersHorizontalGroup -B --no-transfer-progress | tail -n 25"`
  Expected: FAIL due to missing class `benutzer-aktionen-gruppe`.
- [ ] **Step 3: Write minimal implementation**
  In `src/main/resources/templates/einstellungen/benutzer.html`, update the action container to use class `benutzer-aktionen-gruppe` and ensure the role/status forms wrapper uses `display: contents;` or inline-flex so each button sits in a single horizontal row.
  In `src/main/resources/static/css/app.css`, style `.benutzer-aktionen-gruppe` with `display: inline-flex; align-items: center; justify-content: flex-end; gap: 0.4rem; white-space: nowrap;`.
- [ ] **Step 4: Run the single test — verify it PASSES**
  Command: `bash -c "set -o pipefail; ./mvnw test -Dtest=UserControllerTest#regularUserActionsRendersHorizontalGroup -B --no-transfer-progress | tail -n 25"`
  Expected: PASS.
- [ ] **Step 5: Commit**
  `git commit -m "fix(ui): align user table actions horizontally to maintain uniform row height"`

---

### Task 3: Step Metadata Labeling in Journey Detail View

**Files:**
- Modify: `src/main/resources/messages.properties`
- Modify: `src/main/resources/templates/journey/detail.html`
- Test: `src/test/java/dev/hendrikhoemberg/webtesthelper/web/JourneyControllerTest.java`

**Interfaces:**
- Consumes: `JourneyStep.optional()` boolean
- Produces: Explicit German badge/label `Erforderlich` when false and `Optional` when true instead of unlabelled `Nein`.

- [ ] **Step 1: Write the failing test**
  Add a test method in `src/test/java/dev/hendrikhoemberg/webtesthelper/web/JourneyControllerTest.java`:
  ```java
  @Test
  @WithMockUser
  void journeyDetailShowsExplicitRequiredLabelInsteadOfNein() throws Exception {
      JourneyStep step = new JourneyStep(UUID.randomUUID(), 0, StepAction.GOTO, List.of(), "https://example.com", null, false, 5000);
      JourneyDefinition def = new JourneyDefinition(10L, 1L, "Test Journey", true, List.of(step));
      when(siteService.contextFor(1L)).thenReturn(siteContext);
      when(journeyService.findDefinition(10L)).thenReturn(Optional.of(def));

      mvc.perform(get("/websites/1/journeys/10"))
              .andExpect(status().isOk())
              .andExpect(content().string(containsString("Erforderlich")))
              .andExpect(content().string(not(containsString("schritte.nein"))));
  }
  ```
- [ ] **Step 2: Run the single test — verify it FAILS**
  Command: `bash -c "set -o pipefail; ./mvnw test -Dtest=JourneyControllerTest#journeyDetailShowsExplicitRequiredLabelInsteadOfNein -B --no-transfer-progress | tail -n 25"`
  Expected: FAIL because template currently renders "Nein".
- [ ] **Step 3: Write minimal implementation**
  In `src/main/resources/messages.properties`, add `ui.journey.detail.schritte.erforderlich=Erforderlich`.
  In `src/main/resources/templates/journey/detail.html`, replace `ui.journey.detail.schritte.nein` with `ui.journey.detail.schritte.erforderlich` in `.journey-meta-tags`.
- [ ] **Step 4: Run the single test — verify it PASSES**
  Command: `bash -c "set -o pipefail; ./mvnw test -Dtest=JourneyControllerTest#journeyDetailShowsExplicitRequiredLabelInsteadOfNein -B --no-transfer-progress | tail -n 25"`
  Expected: PASS.
- [ ] **Step 5: Commit**
  `git commit -m "fix(ux): clarify step requirement label in journey detail"`

---

### Task 4: Deduplicate Action Buttons in Screencast Recorder

**Files:**
- Modify: `src/main/resources/templates/journey/record.html`
- Test: `src/test/java/dev/hendrikhoemberg/webtesthelper/recorder/RecorderControllerTest.java`

**Interfaces:**
- Consumes: `/websites/{id}/aufzeichnen` view model
- Produces: Single centralized session termination button in the active canvas toolbar, leaving the top header dedicated solely to navigation.

- [ ] **Step 1: Write the failing test**
  Add a test method in `src/test/java/dev/hendrikhoemberg/webtesthelper/recorder/RecorderControllerTest.java` verifying that the header does not contain a duplicate "Aufzeichnung beenden" button when an active session is loaded:
  ```java
  @Test
  @WithMockUser
  void activeRecorderHeaderDoesNotContainDuplicateEndButton() throws Exception {
      when(siteService.contextFor(1L)).thenReturn(siteContext);
      when(sessionRegistry.allocate(eq(1L), any())).thenReturn(session);

      mvc.perform(get("/websites/1/aufzeichnen"))
              .andExpect(status().isOk())
              .andExpect(content().string(containsString("Ablauf speichern")))
              .andExpect(content().string(not(containsString("header-actions-cluster seiten-kopf-aktionen\">\n            <div th:unless=\"${capacityExceeded or startFailed}\">\n                <button type=\"button\" class=\"btn-ui btn-ui-secondary btn-danger-hover button sekundär\""))));
  }
  ```
- [ ] **Step 2: Run the single test — verify it FAILS**
  Command: `bash -c "set -o pipefail; ./mvnw test -Dtest=RecorderControllerTest#activeRecorderHeaderDoesNotContainDuplicateEndButton -B --no-transfer-progress | tail -n 25"`
  Expected: FAIL because duplicate button is present in header.
- [ ] **Step 3: Write minimal implementation**
  In `src/main/resources/templates/journey/record.html`, remove the redundant `button` inside `header-actions-cluster` so only `Zurück zur Website` / `Zurück zu den Abläufen` remains in the header.
- [ ] **Step 4: Run the single test — verify it PASSES**
  Command: `bash -c "set -o pipefail; ./mvnw test -Dtest=RecorderControllerTest#activeRecorderHeaderDoesNotContainDuplicateEndButton -B --no-transfer-progress | tail -n 25"`
  Expected: PASS.
- [ ] **Step 5: Commit**
  `git commit -m "fix(ux): remove duplicate end recording button from header"`

---

### Task 5: Standardize Help Icon Placement on Setup Page

**Files:**
- Modify: `src/main/resources/templates/einrichtung/index.html`
- Test: `src/test/java/dev/hendrikhoemberg/webtesthelper/web/SetupControllerTest.java`

**Interfaces:**
- Consumes: `/websites/{id}/einrichtung` view
- Produces: Help button placed directly next to `h1` in `abschnitt-ueberschrift-zeile`, rather than pushed to the far right margin.

- [ ] **Step 1: Write the failing test**
  Add a test method in `src/test/java/dev/hendrikhoemberg/webtesthelper/web/SetupControllerTest.java`:
  ```java
  @Test
  @WithMockUser
  void setupPagePlacesHelpIconAdjacentToTitle() throws Exception {
      when(siteService.contextFor(42L)).thenReturn(siteContext);
      when(setupProbeService.state(42L)).thenReturn(Optional.empty());

      mvc.perform(get("/websites/42/einrichtung"))
              .andExpect(status().isOk())
              .andExpect(content().string(containsString("abschnitt-ueberschrift-zeile")));
  }
  ```
- [ ] **Step 2: Run the single test — verify it FAILS**
  Command: `bash -c "set -o pipefail; ./mvnw test -Dtest=SetupControllerTest#setupPagePlacesHelpIconAdjacentToTitle -B --no-transfer-progress | tail -n 25"`
  Expected: FAIL due to missing `abschnitt-ueberschrift-zeile` in `einrichtung/index.html`.
- [ ] **Step 3: Write minimal implementation**
  In `src/main/resources/templates/einrichtung/index.html`, wrap `page-main-title` and `hinweis-schalter` together in `<div class="abschnitt-ueberschrift-zeile" style="border-bottom: none; margin-bottom: 0; padding-bottom: 0;">`, removing the help button from `seiten-kopf-aktionen`.
- [ ] **Step 4: Run the single test — verify it PASSES**
  Command: `bash -c "set -o pipefail; ./mvnw test -Dtest=SetupControllerTest#setupPagePlacesHelpIconAdjacentToTitle -B --no-transfer-progress | tail -n 25"`
  Expected: PASS.
- [ ] **Step 5: Commit**
  `git commit -m "fix(ui): place help trigger button directly adjacent to title on setup page"`

---

### Task 6: Remove Redundant Subtitles from Website Subpages

**Files:**
- Modify: `src/main/resources/templates/journey/list.html`
- Modify: `src/main/resources/templates/journey/detail.html`
- Modify: `src/main/resources/templates/journey/edit.html`
- Modify: `src/main/resources/templates/einrichtung/index.html`
- Test: `src/test/java/dev/hendrikhoemberg/webtesthelper/web/JourneyControllerTest.java`
- Test: `src/test/java/dev/hendrikhoemberg/webtesthelper/web/SetupControllerTest.java`

**Interfaces:**
- Consumes: Subpage header markup
- Produces: Clean header without orphaned site name text links beneath the main title.

- [ ] **Step 1: Write the failing test**
  Add assertions in `JourneyControllerTest.java` and `SetupControllerTest.java` ensuring that the orphaned subtitle div `basis-url-link` or redundant span is absent:
  ```java
  @Test
  @WithMockUser
  void journeyListHeaderDoesNotContainRedundantSiteSubtitle() throws Exception {
      when(siteService.contextFor(1L)).thenReturn(siteContext);
      when(journeyService.findBySite(1L)).thenReturn(List.of());

      mvc.perform(get("/websites/1/journeys"))
              .andExpect(status().isOk())
              .andExpect(content().string(not(containsString("class=\"basis-url-link\" style=\"font-weight: 600;\""))));
  }
  ```
- [ ] **Step 2: Run the single test — verify it FAILS**
  Command: `bash -c "set -o pipefail; ./mvnw test -Dtest=JourneyControllerTest#journeyListHeaderDoesNotContainRedundantSiteSubtitle -B --no-transfer-progress | tail -n 25"`
  Expected: FAIL because subtitle link exists.
- [ ] **Step 3: Write minimal implementation**
  Remove the redundant site link/span `<div style="margin-top: 0.25rem;">...</div>` from `journey/list.html`, `journey/detail.html`, `journey/edit.html`, and `einrichtung/index.html`.
- [ ] **Step 4: Run the single test — verify it PASSES**
  Command: `bash -c "set -o pipefail; ./mvnw test -Dtest=JourneyControllerTest#journeyListHeaderDoesNotContainRedundantSiteSubtitle -B --no-transfer-progress | tail -n 25"`
  Expected: PASS.
- [ ] **Step 5: Commit**
  `git commit -m "fix(ui): remove redundant site subtitles from website subpages"`

---

## Final Verification
Run default verification suite:
`bash -c "set -o pipefail; ./mvnw test -Pfast -B --no-transfer-progress | tail -n 50"`
