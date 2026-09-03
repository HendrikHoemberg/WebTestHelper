# UI & UX Design Fixes Implementation Plan

**Goal:** Remediate all issues identified in the UI/UX review: make the website form context-aware (create vs. edit), fix KPI subtitle dead ternary logic, ensure accessible card navigation affordance, eliminate empty card boxes in run detail filtering, and replace inline styling with clean CSS classes using design tokens.

**Architecture:** Presentation-tier refactoring in Thymeleaf templates, CSS in `app.css`, and Alpine.js reactivity. German-only UI (`ui.*`), standard desktop layout, strictly adhering to WCAG and project conventions in `AGENTS.md`.

**Tech Stack:** Spring Boot 3 / Thymeleaf, Alpine.js, `app.css`, JUnit 5 / MockMvc (`@WebMvcTest`).

**Spec:** Review findings from `docs/slimpowers/plans/2026-09-03-ui-ux-design-optimizations.md`.

## Global Constraints

- German-only UI; message keys `ui.*`; no technical abbreviations, raw enums, or internal identifiers.
- View tests: `@WebMvcTest` + MockMvc; assertions on text/markup.
- Desktop-only layout: no mobile breakpoints or collapsible hamburger menus.
- Clean context hygiene: `-B --no-transfer-progress`, `set -o pipefail`, and `tail` on test commands.

---

### Task 1: Context-Aware Website Formular & CSS Extraction

**Files:**
- Modify: `src/main/resources/templates/websites/formular.html`
- Modify: `src/main/resources/messages.properties`
- Modify: `src/main/resources/static/css/app.css`
- Test: `src/test/java/dev/hendrikhoemberg/webtesthelper/web/SiteControllerTest.java`

**Changes:**
- In `formular.html`, display the left intro column context-sensitively:
  - If `siteId == null` (new website): show onboarding info (*"Automatische Erstprüfung"*).
  - If `siteId != null` (edit website): show edit guidance (*"Einstellungen anpassen: Name, Basis-URL und Crawling-Grenzwerte konfigurieren"*).
- Extract inline CSS from `formular.html` into `.form-layout-2col` and `.form-intro-col` in `app.css`.
- Add test in `SiteControllerTest.java` verifying that editing a website does NOT display *"Automatische Erstprüfung"* and that creating a new website does.

**Verification:**
`./mvnw test -Dtest=SiteControllerTest -B --no-transfer-progress`

---

### Task 2: Dashboard KPI Subtitles & Accessible Card Affordance

**Files:**
- Modify: `src/main/resources/templates/uebersicht/index.html`
- Modify: `src/main/resources/templates/fragments/kacheln.html`
- Modify: `src/main/resources/messages.properties`
- Modify: `src/main/resources/static/css/app.css`
- Test: `src/test/java/dev/hendrikhoemberg/webtesthelper/web/UebersichtControllerTest.java`

**Changes:**
- In `messages.properties`, introduce a dedicated `ui.uebersicht.kpi.ueber_alle_websites=über alle Websites`.
- In `uebersicht/index.html`, remove dead ternary expressions for warning/info subtitles and reference `ui.uebersicht.kpi.ueber_alle_websites` directly.
- In `fragments/kacheln.html`:
  - Make cards accessible for keyboard navigation (`tabindex="0"`, `role="link"`, `onkeydown` for Enter).
  - Support middle-click and Cmd/Ctrl-click for opening in a new tab without interfering with inner action links/buttons.
- Add test in `UebersichtControllerTest.java` verifying the rendered KPI subtitle text.

**Verification:**
`./mvnw test -Dtest=UebersichtControllerTest -B --no-transfer-progress`

---

### Task 3: Finding Detail Unified Card & Browser Mockup CSS Extraction

**Files:**
- Modify: `src/main/resources/templates/befunde/detail.html`
- Modify: `src/main/resources/static/css/app.css`
- Test: `src/test/java/dev/hendrikhoemberg/webtesthelper/web/FindingControllerTest.java`

**Changes:**
- Move inline styles from `befunde/detail.html` into `app.css`:
  - `.browser-mockup-frame`, `.browser-mockup-bar`, `.browser-mockup-dots`, `.browser-mockup-dot`.
  - Section classes using existing CSS variables (`var(--surface-subtle)`, `var(--border-subtle)`, `var(--border-strong)`).
  - Ensure dark remediation callout maintains proper contrast.

**Verification:**
`./mvnw test -Dtest=FindingControllerTest -B --no-transfer-progress`

---

### Task 4: Run Detail Filter UX & Filter Pill Bar CSS

**Files:**
- Modify: `src/main/resources/templates/laeufe/detail.html`
- Modify: `src/main/resources/static/css/app.css`
- Test: `src/test/java/dev/hendrikhoemberg/webtesthelper/web/RunControllerTest.java`

**Changes:**
- In `laeufe/detail.html`:
  - Enhance Alpine component on the section container so sections with zero matching findings for the selected filter severity are hidden, preventing empty card boxes.
  - Move `.filter-pill-bar` inline styling into `app.css`.
  - Harmonize action buttons (keep `Bericht drucken` as `btn-ui-secondary button sekundär` to match standard toolbar hierarchy).
- Add test in `RunControllerTest.java` verifying filter attributes and section rendering.

**Verification:**
`./mvnw test -Dtest=RunControllerTest -B --no-transfer-progress`

---

### Task 5: Full Verification & Sanity Check

- Run standard fast suite: `bash -c "set -o pipefail; ./mvnw test -Pfast -B --no-transfer-progress | tail -n 50"`
- Run browser acceptance test: `./mvnw test -Dtest=LoginFlowBrowserAcceptanceTest -B --no-transfer-progress`
- Confirm `git status` and clean working tree.
