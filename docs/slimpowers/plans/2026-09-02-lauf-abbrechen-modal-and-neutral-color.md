# Lauf abbrechen Modal & Neutral Zinc Color Replacement Plan

**Goal:** Transform the inline "Lauf abbrechen" confirmation prompt into a modal matching the existing help modals, and replace the cool blue Slate color tokens (used by the modal close button, help buttons, borders, and surfaces) with true neutral Zinc monochrome colors across the app.

**Architecture:** Thymeleaf + HTMX + Alpine. The prompt in `laeufe/detail.html` is wrapped in a standard `.wth-modal` overlay triggered via the existing `@abbrechen-offen.window` Alpine event. Generic modal CSS classes are extracted in `app.css`. The design tokens in `app.css` are aligned to neutral Zinc (`#fafafa`, `#e4e4e7`, `#d4d4d8`, `#71717a`), eliminating the blue tint of `.wth-hilfe-modal-schliessen` and corresponding elements app-wide.

**Tech Stack:** Spring Boot, Thymeleaf, Alpine.js, HTMX, CSS Custom Properties. Fast verification via `./mvnw test -Pfast`.

**Spec:** Driven by user request and `docs/slimpowers/specs/2026-09-01-ui-overhaul-design.md` (Pure High-Contrast Monochrome Carbon Palette).

## Global Constraints

- German-only UI; message keys remain `ui.*`; no raw enums or unformatted strings in HTML.
- View tests assert text, markup, and accessibility semantics (`containsString`), not CSS rules.
- Desktop-first layout; keep existing worker pool sizes and non-visual architecture intact.
- Verification command: `./mvnw test -Pfast -B --no-transfer-progress`.

---

## Investigation Findings: Where the "Slightly Blue" Color is Used

The modal's close button (`.wth-hilfe-modal-schliessen`) uses:
- `background: var(--surface-subtle);` -> `#f8fafc` (Tailwind Slate-50, RGB 248, 250, 252 - blue dominant)
- `border: 1px solid var(--border-subtle);` -> `#e2e8f0` (Tailwind Slate-200, RGB 226, 232, 240 - blue dominant)
- `color: var(--text-muted);` -> `#64748b` (Tailwind Slate-500, RGB 100, 116, 139 - ~215° hue, distinct cool slate blue)
- hover: `border-color: var(--border-strong);` -> `#cbd5e1` (Tailwind Slate-300, RGB 203, 213, 225)

These Slate colors are also used throughout the app:
1. **`--surface-subtle` (`#f8fafc`)**:
   - `.wth-hilfe-modal-schliessen` (modal close button background)
   - `.hinweis-schalter` (all `?` help buttons on all 14 pages)
   - `.btn-ui-secondary:hover`, `.button.sekundär:hover`
   - `.site-tabs a:hover`
   - `.sparkline-history-strip`
2. **`--border-subtle` (`#e2e8f0`)**:
   - Modal dialog border & header divider
   - All `.card-box` card borders, table borders, form input borders, tabs border
3. **`--text-muted` (`#64748b`)**:
   - Modal close button icon (`×`)
   - `?` button icon
   - Subtitles, breadcrumb separators, timestamps, and muted labels
4. **Hardcoded instances**:
   - `capacity-bar-track` & `.run-pip` (`#e2e8f0`)
   - Email digest template `mail/digest.html` (`#f8fafc`, `#e2e8f0`, `#64748b`, `#f1f5f9`, `#334155`)

**Proposed Solution:**
Switch the palette variables in `app.css` from cool **Slate** to true neutral **Zinc** (as originally specified in `docs/slimpowers/specs/2026-09-01-ui-overhaul-design.md`):
- `--surface-subtle`: `#f8fafc` -> `#fafafa`
- `--surface-hover`: `#f1f5f9` -> `#f4f4f5`
- `--border-subtle`: `#e2e8f0` -> `#e4e4e7`
- `--border-strong`: `#cbd5e1` -> `#d4d4d8`
- `--border-dark`: `#1e293b` -> `#27272a`
- `--text-muted`: `#64748b` -> `#71717a`
- `--text-faint`: `#94a3b8` -> `#a1a1aa`
- `--text-body`: `#334155` -> `#27272a`
- `--status-badge-bg` & `--status-*-bg`: `#f1f5f9` -> `#f4f4f5`
- `--status-badge-border` & `--status-*-border`: `#cbd5e1` -> `#d4d4d8`
- `.capacity-bar-track` & `.run-pip`: `#e2e8f0` -> `var(--border-subtle)` / `#e4e4e7`

This eliminates all blue undertones from the close button, the help buttons, cards, and chrome, giving a crisp, neutral monochrome aesthetic.

---

### Task 1: Neutral Zinc Palette & Generic Modal CSS in `app.css`

**Files:**
- Modify: `src/main/resources/static/css/app.css`

**Interfaces:**
- Produces: Generic `.wth-modal*` classes shared between help and action dialogs. True neutral Zinc tokens eliminating blue hue.

- [x] **Step 1: Replace Slate color values with Zinc in `:root` and hardcoded classes**
  In `src/main/resources/static/css/app.css`:
  - Replace `#f8fafc` with `#fafafa` for `--surface-subtle`, `#f4f4f5` for `--bg-canvas` / `--bg-color`
  - Replace `#f1f5f9` with `#f4f4f5` for `--surface-hover`, `--status-badge-bg`, `--status-*-bg`
  - Replace `#e2e8f0` with `#e4e4e7` for `--border-subtle`, `--border-color`, `.capacity-bar-track`, `.run-pip`
  - Replace `#cbd5e1` with `#d4d4d8` for `--border-strong`, `--status-badge-border`, `--status-*-border`
  - Replace `#64748b` with `#71717a` for `--text-muted`
  - Replace `#94a3b8` with `#a1a1aa` for `--text-faint`, `--status-inactive-dot`
  - Replace `#334155` with `#27272a` for `--text-body`
  - Replace `#1e293b` with `#27272a` for `--border-dark`, `--sidebar-border`
  - Replace `#0f172a` with `#18181b` for `--status-badge-text`, `--status-*-text`, `--success-color`, `--danger-color`, `--warning-color`, `--info-color`

- [x] **Step 2: Add `.wth-modal*` classes aliasing and unifying `.wth-hilfe-modal*`**
  Unify `.wth-modal`, `.wth-modal-backdrop`, `.wth-modal-dialog`, `.wth-modal-kopf`, `.wth-modal-titel`, `.wth-modal-schliessen`, `.wth-modal-inhalt` alongside `.wth-hilfe-modal*`.
  Ensure `.wth-modal-schliessen` and `.wth-hilfe-modal-schliessen` use the clean neutral Zinc styling.

- [x] **Step 3: Run existing UI tests to verify no regressions**
  `./mvnw test -Dtest=DashboardControllerTest`

---

### Task 2: Implement "Lauf abbrechen" Modal & German Copy

**Files:**
- Modify: `src/main/resources/messages.properties`
- Modify: `src/main/resources/templates/laeufe/detail.html`
- Modify: `src/main/resources/templates/mail/digest.html` (update hardcoded inline colors to neutral zinc)

**Interfaces:**
- Consumes: `@abbrechen-offen.window` dispatched by `fortschritt.html` button.
- Produces: Accessible modal dialog with backdrop blur, keyboard ESC handling, outside-click dismissal, header with title "Prüflauf abbrechen" and close button, and confirmation form.

- [x] **Step 1: Add new message keys in `messages.properties`**
  ```properties
  ui.lauf.abbrechen.dialog.titel=Prüflauf abbrechen
  ui.lauf.abbrechen.schliessen=Dialog schließen
  ```
  *(Note: keeping title as "Prüflauf abbrechen" ensures the assertion `occurrencesOf(html, "Lauf abbrechen").isEqualTo(1)` remains intact while giving a clear modal title).*

- [x] **Step 2: Replace inline confirmation panel with `.wth-modal` dialog in `laeufe/detail.html`**

- [x] **Step 3: Update `mail/digest.html` inline styles to neutral zinc**
  Replace `#f8fafc` -> `#fafafa`, `#e2e8f0` -> `#e4e4e7`, `#64748b` -> `#71717a`, `#f1f5f9` -> `#f4f4f5`, `#334155` -> `#27272a`.

---

### Task 3: Test Coverage in `RunControllerTest`

**Files:**
- Modify: `src/test/java/dev/hendrikhoemberg/webtesthelper/web/RunControllerTest.java`

- [x] **Step 1: Update and extend `queuedRunDetailRendersTheCancelPanel()`**

- [x] **Step 2: Run `RunControllerTest`**
  `./mvnw test -Dtest=RunControllerTest`
  Expected: PASS.

---

### Task 4: Fast Verification

- [x] Run full fast verification:
  `bash -c "set -o pipefail; ./mvnw test -Pfast -B --no-transfer-progress | tail -n 50"`
  Expected: BUILD SUCCESS.
