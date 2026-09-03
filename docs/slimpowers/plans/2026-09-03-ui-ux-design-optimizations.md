# UI & UX Design Optimizations Implementation Plan

**Goal:** Implement the 8 evaluated design optimizations across WebTestHelper to resolve excessive whitespace, improve visual hierarchy, clean up cramped forms, unify fragmented cards, and introduce interactive filtering and click affordances.

**Architecture:** Thymeleaf templates + Alpine.js + CSS modernizations in `app.css`. No architectural or domain entity changes; purely presentation-tier enhancements adhering strictly to German-only copy, WCAG contrast, and desktop-first UX conventions.

**Tech Stack:** Spring Boot 3 / Thymeleaf, Alpine.js, custom CSS (`app.css`), JUnit 5 / MockMvc (`@WebMvcTest`).

**Spec:** Evaluated design recommendations from session review based on `2026-08-21-webtesthelper-design.md`.

## Global Constraints

- German-only UI; message keys `ui.*`; no technical abbreviations, raw enums, or internal identifiers.
- View tests: `@WebMvcTest` + MockMvc; assertions on text/markup, preserved compatibility with existing tests.
- Desktop-only layout: no mobile breakpoints or collapsible hamburger menus.
- Clean context hygiene: `-B --no-transfer-progress`, `set -o pipefail`, and `tail` on test commands.

---

### Task 1: Two-Column Form Layout on Desktop for Website Registration & Edit
- **Files:**
  - Modify: `src/main/resources/templates/websites/formular.html`
  - Modify: `src/main/resources/messages.properties`
  - Modify: `src/main/resources/static/css/app.css`
  - Test: `src/test/java/dev/hendrikhoemberg/webtesthelper/web/SiteControllerTest.java`
- **Changes:**
  - Replace floating, single-column 760px card with a structured 2-column layout.
  - Left column: Contextual introduction explaining automatic initial crawl and reassurance.
  - Right column: Clean form card with existing input fields.
- **Verification:** `./mvnw test -Dtest=SiteControllerTest`

---

### Task 2: Dashboard KPI Cards Labeling & Card Click Affordance
- **Files:**
  - Modify: `src/main/resources/templates/uebersicht/index.html`
  - Modify: `src/main/resources/templates/fragments/kacheln.html`
  - Modify: `src/main/resources/messages.properties`
  - Modify: `src/main/resources/static/css/app.css`
  - Test: `src/test/java/dev/hendrikhoemberg/webtesthelper/web/UebersichtControllerTest.java`
- **Changes:**
  - Eliminate redundant double labeling (`FEHLER / 14 / Fehler` -> `FEHLER / 14 / über alle Websites`).
  - Add interactive card affordance: entire `.target-card-box` is hoverable, clickable to navigate to site overview.
- **Verification:** `./mvnw test -Dtest=UebersichtControllerTest`

---

### Task 3: Stummschaltungsformular 2x2 Grid
- **Files:**
  - Modify: `src/main/resources/templates/stummschaltungen/index.html`
  - Test: `src/test/java/dev/hendrikhoemberg/webtesthelper/web/MuteRuleControllerTest.java`
- **Changes:**
  - Change cramped 4-column row into balanced 2x2 grid (Row 1: Website + Prüfungsart; Row 2: Betreff-Muster + Fundort-Muster).
  - Quick-chips and pattern builder receive ample horizontal space without wrapping.
- **Verification:** `./mvnw test -Dtest=MuteRuleControllerTest`

---

### Task 4: Finding Detail Unified Card & Browser Window Mockup Frame
- **Files:**
  - Modify: `src/main/resources/templates/befunde/detail.html`
  - Modify: `src/main/resources/static/css/app.css`
  - Test: `src/test/java/dev/hendrikhoemberg/webtesthelper/web/FindingControllerTest.java`
- **Changes:**
  - Consolidate 3 fragmented micro-cards (*Was wir geprüft haben*, *Was wir gefunden haben*, *Was zu tun ist*) into 1 unified, beautifully structured card with clear typography and dark left accent on remediation.
  - Replace harsh pitch-black screenshot background with a modern browser window mockup frame (neutral chrome header with 3 window dots and subtle border).
- **Verification:** `./mvnw test -Dtest=FindingControllerTest`

---

### Task 5: Run Detail Action Hierarchy & Client-Side Finding Filter Bar
- **Files:**
  - Modify: `src/main/resources/templates/laeufe/detail.html`
  - Modify: `src/main/resources/messages.properties`
  - Modify: `src/main/resources/static/css/app.css`
  - Test: `src/test/java/dev/hendrikhoemberg/webtesthelper/web/RunControllerTest.java`
- **Changes:**
  - Highlight primary action `Bericht drucken` as solid black button.
  - Add instant client-side Alpine.js filter bar: `[Alle]`, `[Nur Fehler]`, `[Nur Warnungen]`, `[Nur Hinweise]`.
- **Verification:** `./mvnw test -Dtest=RunControllerTest`

---

### Task 6: User Management Action Button Layout Styling
- **Files:**
  - Modify: `src/main/resources/templates/einstellungen/benutzer.html`
  - Modify: `src/main/resources/static/css/app.css`
  - Test: `src/test/java/dev/hendrikhoemberg/webtesthelper/web/UserControllerTest.java`
- **Changes:**
  - Refine `.benutzer-aktionen-gruppe` to prevent 4 wide buttons from stretching and dominating the table width.
- **Verification:** `./mvnw test -Dtest=UserControllerTest`

---

### Task 7: Full Verification & Visual Confirmation
- Run `./mvnw test -Pfast -B --no-transfer-progress` (all 1,521 tests).
- Generate live screenshots to visually confirm polished layouts.
