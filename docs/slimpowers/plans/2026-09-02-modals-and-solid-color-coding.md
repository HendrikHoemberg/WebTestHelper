# Modals & Solid Color Coding Implementation Plan

**Goal:** Eliminate all washed-out pastel colors across the app in favor of the neutral Zinc/Carbon design, introduce solid red (`#dc2626` / `#b91c1c`) for destructive/abort actions, and replace inline confirmations and native `confirm()` popups with accessible modals styled after the abort run modal.

**Architecture:** 
1. **Design System & Palette:** Define a crisp, non-pastel danger color token (`--danger-solid: #dc2626`, `--danger-solid-hover: #b91c1c`, white text) for primary destructive actions (`.btn-ui-danger`). In table action lists, delete triggers become clean neutral buttons (`.btn-ui-secondary` with danger hover) that open the modal. Replace remaining pastel badges (`.badge-prioritaet-*`, warning boxes) with neutral zinc capsules + colored dots/indicators.
2. **Reusable Modal Architecture:** Standardize Alpine.js modal overlays using `.wth-modal` markup. Provide clean modal dialogs with backdrop blur, keyboard trap / ESC close, and clear button hierarchy (solid red primary for confirm, neutral secondary for abort).
3. **Template Rollout:**
   - **Phase 1:** CSS Palette & Color Tokens (kill pastel colors, style solid danger buttons and table action triggers).
   - **Phase 2:** Website deletion modals (`websites/liste.html` and `fragments/site-kopf.html`).
   - **Phase 3:** User management modals (`einstellungen/benutzer.html`: password reset & delete confirmation with "LÖSCHEN").
   - **Phase 4:** Stummschaltung deletion modal (`stummschaltungen/index.html`).
   - **Phase 5:** Credentials & Recipient modals (`fragments/zugangsdaten.html` & `fragments/empfaenger.html`).
   - **Phase 6:** Journey recorder modals (`journey/record.html`: save journey dialog & discard recording confirmation).
   - **Phase 7:** Baseline modal (`laeufe/detail.html`: replace inline accordion with modal).

**Tech Stack:** Spring Boot, Thymeleaf, Alpine.js, HTMX, CSS Custom Properties.

---

### Task 1: Eliminate Pastel Colors & Introduce Solid High-Contrast Danger Coding in `app.css`

**Files:**
- Modify: `src/main/resources/static/css/app.css`

**Details:**
- Remove pastel `#ffe4e6`, `#fda4af`, `#fee2e2`, `#fef3c7`.
- Define:
  - `--danger-solid: #dc2626;`
  - `--danger-solid-hover: #b91c1c;`
- Update `.btn-ui-danger`:
  - When used as a primary destructive action in modals (`.btn-ui-danger`, `.btn-ui-danger-solid`): solid red background `#dc2626`, crisp white text `#ffffff`, border `#dc2626`, hover `#b91c1c`.
  - When used as a table row trigger (`.btn-ui-danger-subtle` / `.btn-ui-secondary.btn-danger-hover`): neutral surface card background, normal or subtle text, hover transitions to crisp red border & text (no pastel pink background fill!).
- Modernize priority badges (`.badge-prioritaet-dringend`, `.badge-prioritaet-empfohlen`) to use neutral zinc capsules with signal colored indicators instead of flat pastel backgrounds.

---

### Task 2: Website Deletion Modals

**Files:**
- Modify: `src/main/resources/templates/websites/liste.html`
- Modify: `src/main/resources/templates/fragments/site-kopf.html`
- Test: `src/test/java/dev/hendrikhoemberg/webtesthelper/web/SiteControllerTest.java`

**Details:**
- Replace `onclick="return confirm(...)"` with Alpine-driven `.wth-modal` dialogs.
- Clear modal warning about cascade deletion (removes journeys, schedules, credentials, run history).
- Confirm button: Solid red `Jetzt löschen`, cancel: `Abbrechen`.

---

### Task 3: User Management Modals (Password Reset & Deletion with "LÖSCHEN")

**Files:**
- Modify: `src/main/resources/templates/einstellungen/benutzer.html`
- Test: `src/test/java/dev/hendrikhoemberg/webtesthelper/web/UserControllerTest.java`

**Details:**
- Remove the inline password input from the table row; replace with a "Passwort ändern" button opening a focused modal.
- Remove the inline red expanding box from the table row; replace with a "Löschen" button opening a modal containing the required "LÖSCHEN" confirmation input.
- Table rows become compact and vertically aligned.

---

### Task 4: Stummschaltung Deletion Modal

**Files:**
- Modify: `src/main/resources/templates/stummschaltungen/index.html`
- Test: `src/test/java/dev/hendrikhoemberg/webtesthelper/web/MuteRuleControllerTest.java`

**Details:**
- Replace `onclick="return confirm(...)"` with `.wth-modal`.
- Informs the user that findings will be reported again on future runs.
- Solid red confirmation button.

---

### Task 5: Credentials & Recipient Confirmation & Action Modals

**Files:**
- Modify: `src/main/resources/templates/fragments/zugangsdaten.html`
- Modify: `src/main/resources/templates/fragments/empfaenger.html`
- Test: `src/test/java/dev/hendrikhoemberg/webtesthelper/web/CredentialControllerTest.java`
- Test: `src/test/java/dev/hendrikhoemberg/webtesthelper/web/RecipientControllerTest.java`

**Details:**
- Delete actions for credentials and recipients currently submit with zero confirmation. Add confirmation modals.
- Clean up the credential edit form into a modal dialog.

---

### Task 6: Journey Recorder Confirmation & Save Modals

**Files:**
- Modify: `src/main/resources/templates/journey/record.html`

**Details:**
- "Aufzeichnung beenden": Confirmation modal warning that recorded steps will be lost.
- "Ablauf speichern": Modal for entering journey name and saving, de-cluttering the canvas header.

---

### Task 7: Baseline Acceptance Modal

**Files:**
- Modify: `src/main/resources/templates/laeufe/detail.html`
- Test: `src/test/java/dev/hendrikhoemberg/webtesthelper/web/RunControllerTest.java`

**Details:**
- Convert the inline accordion (`ausgangsbestand-panel`) into a confirmation modal styled consistently with the abort run modal.
