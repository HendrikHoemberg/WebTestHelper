# Button Icons Enhancement Implementation Plan

**Goal:** Provide a coherent, accessible, and polished user experience across the application by enhancing high-priority/contextual buttons with icons and replacing repetitive/unicode-based buttons with compact SVG icon-only buttons.

**Architecture:** Extend the central Thymeleaf icon library (`fragments/icons.html`) with missing Lucide SVG icons (`more_horizontal`, `pencil`, `arrow_left`, `printer`, `download`). Update templates to use `th:replace="~{fragments/icons :: ...}"` within `.btn-ui` and icon-only button wrappers, ensuring strict accessibility (`aria-label`, `title`) and CSS support (`.btn-icon-only`, `.hinweis-schalter`, `.wth-modal-schliessen`).

**Tech Stack:** Thymeleaf, Alpine.js, HTMX, Spring Boot MVC, CSS, JUnit 5 MockMvc.

**Spec:** In-session UX analysis and recommendations confirmed by user.

## Global Constraints
- German-only UI (no English copy in rendered HTML; message keys `ui.*`).
- Accessible icon-only buttons MUST always include `aria-label` and `title`.
- Do not introduce external icon font dependencies; all icons are native inline SVG fragments matching existing Lucide design (`viewBox="0 0 24 24"`, `stroke="currentColor"`, `stroke-width="2"`, `class="svg-icon"`).
- Test execution: `-B --no-transfer-progress` and `set -o pipefail`.

---

### Task 1: Icon Library Extension & CSS Utility Styling

**Files:**
- Modify: `src/main/resources/templates/fragments/icons.html` (add `more_horizontal`, `pencil`, `arrow_left`, `printer`, `download`)
- Modify: `src/main/resources/static/css/app.css` (add `.btn-icon-only` styling, refine `.hinweis-schalter` and `.wth-modal-schliessen` SVG sizing/alignment)
- Test: `src/test/java/dev/hendrikhoemberg/webtesthelper/web/SiteDetailControllerTest.java` (verify site header renders without errors)

**Interfaces:**
- Produces: Thymeleaf icon fragments: `more_horizontal`, `pencil`, `arrow_left`, `printer`, `download`.
- Produces: `.btn-icon-only` CSS class for 32x32 / 28x28 square/circular action buttons.

- [x] **Step 1: Write test or verify baseline**
  Run: `./mvnw test -Dtest=SiteDetailControllerTest` -> verify baseline passes.
- [x] **Step 2: Add SVG fragments to `fragments/icons.html`**
  Add Lucide-style SVG fragments:
  - `more_horizontal`: `<circle cx="12" cy="12" r="1"></circle><circle cx="19" cy="12" r="1"></circle><circle cx="5" cy="12" r="1"></circle>`
  - `pencil`: `<path d="M17 3a2.85 2.83 0 1 1 4 4L7.5 20.5 2 22l1.5-5.5Z"></path><path d="m15 5 4 4"></path>`
  - `arrow_left`: `<path d="m12 19-7-7 7-7"></path><path d="M19 12H5"></path>`
  - `printer`: `<polyline points="6 9 6 2 18 2 18 9"></polyline><path d="M6 18H4a2 2 0 0 1-2-2v-5a2 2 0 0 1 2-2h16a2 2 0 0 1 2 2v5a2 2 0 0 1-2 2h-2"></path><rect width="12" height="8" x="6" y="14"></rect>`
  - `download`: `<path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4"></path><polyline points="7 10 12 15 17 10"></polyline><line x1="12" x2="12" y1="15" y2="3"></line>`
- [x] **Step 3: Add CSS for `.btn-icon-only` in `app.css`**
  Ensure `.btn-icon-only` has proper padding (`padding: 0.4rem;`), flex centering, square aspect ratio, and inherits `.btn-ui` properties.
- [x] **Step 4: Verify single test passes**
  Run: `./mvnw test -Dtest=SiteDetailControllerTest`

---

### Task 2: Replace Text / Unicode with Icon-Only Buttons

**Files:**
- Modify: `src/main/resources/templates/journey/edit.html` (replace `▲`, `▼`, `✕` with `chevron_up`, `chevron_down`, `trash` SVG fragments; update JS template `addNewStep()`)
- Modify: `src/main/resources/templates/postausgang/index.html` (replace `↻` and `✕` with `refresh` and `trash` SVG fragments)
- Modify: `src/main/resources/templates/fragments/site-kopf.html` (replace `⋯` with `more_horizontal` SVG, modal close `×` with `x` SVG)
- Modify: `src/main/resources/templates/fragments/hinweis.html` and callers of `.hinweis-schalter` (replace raw `?` character with `help` SVG)
- Modify: `src/main/resources/templates/befunde/detail.html` & `src/main/resources/templates/fragments/befundzeile.html` (replace text „Kopieren“ on `.kopie-button` with `copy` SVG icon + tooltip)
- Modify: `src/main/resources/templates/fragments/zugangsdaten.html` & `src/main/resources/templates/fragments/empfaenger.html` (replace row „Löschen“ with compact danger `trash` icon button)
- Modify: All remaining modal templates containing `×` close buttons (`websites/liste.html`, `journey/list.html`, `journey/edit.html`, `journey/detail.html`, `laeufe/detail.html`, `einstellungen/benutzer.html`, `stummschaltungen/index.html`, etc.)
- Test: `src/test/java/dev/hendrikhoemberg/webtesthelper/web/JourneyEditControllerTest.java`
- Test: `src/test/java/dev/hendrikhoemberg/webtesthelper/web/OutboxControllerTest.java`
- Test: `src/test/java/dev/hendrikhoemberg/webtesthelper/web/FindingListControllerTest.java`

**Interfaces:**
- Produces: Clean, accessible icon-only buttons with `aria-label` and `title`.

- [x] **Step 1: Run baseline tests**
  Run: `./mvnw test -Dtest=JourneyEditControllerTest,OutboxControllerTest,FindingListControllerTest`
- [x] **Step 2: Update `journey/edit.html` step control buttons**
  Replace raw `▲`, `▼`, `✕` with `chevron_up`, `chevron_down`, `trash` icons and update `addNewStep()` JS template.
- [x] **Step 3: Update `postausgang/index.html` row actions**
  Replace `↻` and `✕` with `refresh` and `trash` SVGs within `.btn-icon-only`.
- [x] **Step 4: Update `.hinweis-schalter` and modal close buttons (`wth-modal-schliessen`)**
  Replace raw `?` with `<span th:replace="~{fragments/icons :: help}"></span>`.
  Replace raw `×` with `<span th:replace="~{fragments/icons :: x}"></span>`.
- [x] **Step 5: Update `.kopie-button` in `befundzeile.html` & `befunde/detail.html`**
  Replace button text „Kopieren“ with `<span th:replace="~{fragments/icons :: copy}"></span>` + `.sr-only` accessibility text.
- [x] **Step 6: Update row delete buttons in `zugangsdaten.html` and `empfaenger.html`**
  Change row deletion trigger to `.btn-icon-only` with `trash` icon and `title="#{...loeschen}"`.
- [x] **Step 7: Run tests to verify**
  Run: `./mvnw test -Dtest=JourneyEditControllerTest,OutboxControllerTest,FindingListControllerTest`

---

### Task 3: Enhance High-Priority & Menu Buttons with Icons (Icon + Text)

**Files:**
- Modify: `src/main/resources/templates/journey/list.html` („Jetzt testen“ button with `play`)
- Modify: `src/main/resources/templates/fragments/site-kopf.html` (dropdown items: `refresh` for Erkennung wiederholen, `pencil` for Bearbeiten, `trash` for Löschen)
- Modify: `src/main/resources/templates/laeufe/detail.html` & `src/main/resources/templates/laeufe/druck.html` („PDF herunterladen“ with `download`, „Bericht drucken“ with `printer`, „Zurück...“ with `arrow_left`)
- Modify: `src/main/resources/templates/journey/edit.html` („Weiteren Schritt hinzufügen“ with `plus`, „Änderungen speichern“ with `check`)
- Modify: `src/main/resources/templates/fragments/zugangsdaten.html` & `src/main/resources/templates/fragments/empfaenger.html` („...hinzufügen“ with `plus`)
- Modify: `src/main/resources/templates/einstellungen/benutzer.html` („Neuen Benutzer anlegen“ with `plus`)
- Modify: `src/main/resources/templates/einstellungen/index.html` („Test-E-Mail senden“ with `play`, „Postfach prüfen“ with `play`, „Webhook testen“ with `play`, „Einstellungen speichern“ with `check`)
- Test: `src/test/java/dev/hendrikhoemberg/webtesthelper/web/JourneyControllerTest.java`
- Test: `src/test/java/dev/hendrikhoemberg/webtesthelper/web/RunControllerTest.java`
- Test: `src/test/java/dev/hendrikhoemberg/webtesthelper/web/SettingsControllerTest.java`

- [x] **Step 1: Update `journey/list.html`**
  Add `play` icon to „Jetzt testen“ button (`<span th:replace="~{fragments/icons :: play}"></span>`).
- [x] **Step 2: Update `site-kopf.html` menu items**
  Add `refresh`, `pencil`, and `trash` icons to the more-menu dropdown items.
- [x] **Step 3: Update `laeufe/detail.html` & `laeufe/druck.html` report buttons**
  Add `download`, `printer`, and `arrow_left` icons.
- [x] **Step 4: Update Add/Save CTAs across forms**
  Add `plus` icon to „Weiteren Schritt hinzufügen“, „Zugangsdaten hinzufügen“, „Empfänger hinzufügen“, „Neuen Benutzer anlegen“. Add `check` to save buttons.
- [x] **Step 5: Update `einstellungen/index.html` test buttons**
  Add `play` to „Test-E-Mail senden“, `play` to „Postfach prüfen“, `play` to „Webhook testen“.
- [x] **Step 6: Run tests to verify**
  Run: `./mvnw test -Dtest=JourneyControllerTest,RunControllerTest,SettingsControllerTest`

---

### Task 4: Full Suite Verification & Regression Check

**Files:**
- None (verification phase)

- [x] **Step 1: Run default fast verification suite**
  `bash -c "set -o pipefail; ./mvnw test -Pfast -B --no-transfer-progress | tail -n 50"`
- [x] **Step 2: Run full verification suite (including acceptance tests touching templates)**
  `bash -c "set -o pipefail; ./mvnw test -B --no-transfer-progress | tail -n 60"`
- [x] **Step 3: Confirm all tests pass with zero failures or regressions**
