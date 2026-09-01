# WebTestHelper UI Overhaul Implementation Plan

**Goal:** Transform WebTestHelper into a high-craft, professional QA tool with a Pure High-Contrast Monochrome Carbon aesthetic, a fixed Left Sidebar Navigation layout, reusable SVG icon fragments, run history sparklines, and polished data density across all templates.

**Architecture:** Server-rendered Spring Boot monolithic frontend using Thymeleaf + HTMX + Alpine.js. Styling is centralized in `src/main/resources/static/css/app.css` using CSS custom properties. SVG icons are encapsulated in `src/main/resources/templates/fragments/icons.html`. All German copy resides in `src/main/resources/messages.properties`.

**Tech Stack:** Java 25, Spring Boot 4.1.1, Thymeleaf, HTMX 2.x, Alpine.js 3.x, Maven, JUnit 5 + MockMvc.

**Spec:** `docs/slimpowers/specs/2026-09-01-ui-overhaul-design.md`

## Global Constraints

- **Verify (everything)**: `./mvnw test` (full suite incl. `@Tag("browser")` acceptance tests).
- **Fast loop**: `./mvnw test -Pfast` (skips browser group only).
- **Single test**: `./mvnw test -Dtest=<TestName>`
- **No external CDNs**: Strictly enforced by `VendoredAssetsTest`.
- **German-only copy**: All text via `messages.properties` (`ui.*` and `check.*` keys), enforced by `UiMessageKeyTest`.
- **No raw internal enums or ISO timestamps**: Enforced by `EnumLabelsTest` and view tests.
- **Maintain existing HTMX attributes & Alpine component bindings** across all refactored templates.

---

### Task 1: Core Design System Tokens & Base Styles (`app.css`)

**Files:**
- Modify: `src/main/resources/static/css/app.css`
- Test: `src/test/java/dev/hendrikhoemberg/webtesthelper/web/VendoredAssetsTest.java`

**Interfaces:**
- Produces: CSS custom properties for canvas, surface, borders, sidebar, buttons (`.btn-ui`), form inputs, cards, data tables, status badges (`.status-badge`), pulse animations, sparkline pips (`.run-pip`), capacity progress tracks, and responsive sidebar workspace wrappers.

- [ ] **Step 1: Write the failing / regression check test**
  Ensure asset structure and non-CDN constraints remain valid.
- [ ] **Step 2: Run test — verify baseline**
  `./mvnw test -Dtest=VendoredAssetsTest`
- [ ] **Step 3: Update `app.css` with complete design system rules**
  Write comprehensive Monochrome Carbon styles covering sidebar layout, main workspace wrapper, buttons, cards, tables, status signals, inputs, sparklines, and code pills.
- [ ] **Step 4: Run single test — verify it PASSES**
  `./mvnw test -Dtest=VendoredAssetsTest`
- [ ] **Step 5: Commit**
  `git commit -m "feat(ui): update app.css with monochrome carbon design tokens and layout styles"`

---

### Task 2: SVG Icon Fragments & App Shell (`icons.html`, `layout.html`, `anmelden.html`)

**Files:**
- Create: `src/main/resources/templates/fragments/icons.html`
- Modify: `src/main/resources/templates/layout.html`
- Modify: `src/main/resources/templates/anmelden.html`
- Test: `src/test/java/dev/hendrikhoemberg/webtesthelper/web/DashboardControllerTest.java`

**Interfaces:**
- Produces: `fragments/icons.html` defining reusable inline SVG fragments (`dashboard`, `globe`, `mute`, `help`, `settings`, `outbox`, `play`, `refresh`, `plus`, `trash`, `check`, `alert`, `x`, `chevron_up`, `chevron_down`, `copy`, `external_link`).
- Produces: New sidebar layout shell in `layout.html` with grouped navigation, bottom user card, global alert banners, and fluid workspace container.

- [ ] **Step 1: Write / verify view assertions for layout and navigation**
- [ ] **Step 2: Run test — verify baseline**
  `./mvnw test -Dtest=DashboardControllerTest`
- [ ] **Step 3: Create `fragments/icons.html` and update `layout.html` + `anmelden.html`**
- [ ] **Step 4: Run test — verify it PASSES**
  `./mvnw test -Dtest=DashboardControllerTest`
- [ ] **Step 5: Commit**
  `git commit -m "feat(ui): add svg icon fragments and overhaul layout shell with left sidebar"`

---

### Task 3: Dashboard & Target Tiles with Run Sparklines (`uebersicht/index.html`, `fragments/kacheln.html`)

**Files:**
- Modify: `src/main/resources/templates/uebersicht/index.html`
- Modify: `src/main/resources/templates/fragments/kacheln.html`
- Test: `src/test/java/dev/hendrikhoemberg/webtesthelper/web/DashboardControllerTest.java`

**Interfaces:**
- Produces: High-craft dashboard with 4 KPI cards (Erfolgsquote, Offene Befunde, Geprüfte Seiten, Worker-Kapazität progress track) and target cards with live status badges, run history sparklines, and tabular metrics.

- [ ] **Step 1: Verify view tests cover polling interval and tile output**
- [ ] **Step 2: Run test**
  `./mvnw test -Dtest=DashboardControllerTest`
- [ ] **Step 3: Update `uebersicht/index.html` and `fragments/kacheln.html`**
- [ ] **Step 4: Run test — verify PASS**
  `./mvnw test -Dtest=DashboardControllerTest`
- [ ] **Step 5: Commit**
  `git commit -m "feat(ui): overhaul dashboard and status tiles with sparklines and kpi cards"`

---

### Task 4: Websites Catalog, Details & Forms (`websites/liste.html`, `detail.html`, `formular.html`, `fragments/zeitplaene.html`, `fragments/zugangsdaten.html`, `fragments/empfaenger.html`)

**Files:**
- Modify: `src/main/resources/templates/websites/liste.html`
- Modify: `src/main/resources/templates/websites/detail.html`
- Modify: `src/main/resources/templates/websites/formular.html`
- Modify: `src/main/resources/templates/fragments/zeitplaene.html`
- Modify: `src/main/resources/templates/fragments/zugangsdaten.html`
- Modify: `src/main/resources/templates/fragments/empfaenger.html`
- Test: `src/test/java/dev/hendrikhoemberg/webtesthelper/web/SiteControllerTest.java`
- Test: `src/test/java/dev/hendrikhoemberg/webtesthelper/web/SiteDetailControllerTest.java`

**Interfaces:**
- Produces: Polished target listing, structured site configuration views (crawl budget, path patterns, schedule tiers, recipients, credentials, active checks, run history table), and clean form controls.

- [ ] **Step 1: Run baseline site tests**
  `./mvnw test -Dtest=SiteControllerTest,SiteDetailControllerTest`
- [ ] **Step 2: Update `websites/liste.html`, `detail.html`, `formular.html`, and related fragments**
- [ ] **Step 3: Run site tests — verify PASS**
  `./mvnw test -Dtest=SiteControllerTest,SiteDetailControllerTest`
- [ ] **Step 4: Commit**
  `git commit -m "feat(ui): overhaul websites catalog, detail views, and configuration forms"`

---

### Task 5: Findings & Triage Inspector (`websites/befunde.html`, `befunde/detail.html`, `fragments/befundfilter.html`, `fragments/befundzeile.html`, `fragments/bewertung.html`)

**Files:**
- Modify: `src/main/resources/templates/websites/befunde.html`
- Modify: `src/main/resources/templates/befunde/detail.html`
- Modify: `src/main/resources/templates/fragments/befundfilter.html`
- Modify: `src/main/resources/templates/fragments/befundzeile.html`
- Modify: `src/main/resources/templates/fragments/bewertung.html`
- Test: `src/test/java/dev/hendrikhoemberg/webtesthelper/web/FindingListControllerTest.java`
- Test: `src/test/java/dev/hendrikhoemberg/webtesthelper/web/FindingControllerTest.java`

**Interfaces:**
- Produces: Polished findings list with filter chips, select-all bulk triage bar, URL copy triggers, structured 3-part finding detail, and syntax-highlighted HTTP evidence blocks.

- [ ] **Step 1: Run baseline finding tests**
  `./mvnw test -Dtest=FindingListControllerTest,FindingControllerTest`
- [ ] **Step 2: Update `websites/befunde.html`, `befunde/detail.html`, and triage fragments**
- [ ] **Step 3: Run finding tests — verify PASS**
  `./mvnw test -Dtest=FindingListControllerTest,FindingControllerTest`
- [ ] **Step 4: Commit**
  `git commit -m "feat(ui): overhaul findings list, filter bar, and 3-section finding detail view"`

---

### Task 6: Run Reports & Live Progress (`laeufe/detail.html`, `fragments/fortschritt.html`)

**Files:**
- Modify: `src/main/resources/templates/laeufe/detail.html`
- Modify: `src/main/resources/templates/fragments/fortschritt.html`
- Test: `src/test/java/dev/hendrikhoemberg/webtesthelper/web/RunControllerTest.java`

**Interfaces:**
- Produces: Run report view with live animated progress bar, scope summary, baseline acceptance card, and color-coded diff sections (`FIXED`, `NEW`, `REGRESSED`, `KNOWN`, `STILL_OPEN`).

- [ ] **Step 1: Run baseline run report tests**
  `./mvnw test -Dtest=RunControllerTest`
- [ ] **Step 2: Update `laeufe/detail.html` and `fragments/fortschritt.html`**
- [ ] **Step 3: Run run report tests — verify PASS**
  `./mvnw test -Dtest=RunControllerTest`
- [ ] **Step 4: Commit**
  `git commit -m "feat(ui): overhaul run report and live progress polling fragment"`

---

### Task 7: Journey Studio & Live Recorder (`journey/list.html`, `detail.html`, `edit.html`, `record.html`)

**Files:**
- Modify: `src/main/resources/templates/journey/list.html`
- Modify: `src/main/resources/templates/journey/detail.html`
- Modify: `src/main/resources/templates/journey/edit.html`
- Modify: `src/main/resources/templates/journey/record.html`
- Test: `src/test/java/dev/hendrikhoemberg/webtesthelper/web/JourneyControllerTest.java`
- Test: `src/test/java/dev/hendrikhoemberg/webtesthelper/web/JourneyEditControllerTest.java`

**Interfaces:**
- Produces: Journey overview with health badges, step table with locator previews and drift indicators, step editor with vector reorder controls, and screencast studio canvas.

- [ ] **Step 1: Run baseline journey tests**
  `./mvnw test -Dtest=JourneyControllerTest,JourneyEditControllerTest`
- [ ] **Step 2: Update journey templates**
- [ ] **Step 3: Run journey tests — verify PASS**
  `./mvnw test -Dtest=JourneyControllerTest,JourneyEditControllerTest`
- [ ] **Step 4: Commit**
  `git commit -m "feat(ui): overhaul journey studio, step editor, and live recorder canvas"`

---

### Task 8: Mute Rules, Settings, User Admin, Help & Setup (`stummschaltungen/index.html`, `fragments/regelvorschau.html`, `einstellungen/index.html`, `einstellungen/benutzer.html`, `fragments/systemlast.html`, `postausgang/index.html`, `hilfe/index.html`, `hilfe/thema.html`, `fragments/hinweis.html`, `einrichtung/index.html`, `fragments/einrichtungsstand.html`)

**Files:**
- Modify: `src/main/resources/templates/stummschaltungen/index.html`
- Modify: `src/main/resources/templates/fragments/regelvorschau.html`
- Modify: `src/main/resources/templates/einstellungen/index.html`
- Modify: `src/main/resources/templates/einstellungen/benutzer.html`
- Modify: `src/main/resources/templates/fragments/systemlast.html`
- Modify: `src/main/resources/templates/postausgang/index.html`
- Modify: `src/main/resources/templates/hilfe/index.html`
- Modify: `src/main/resources/templates/hilfe/thema.html`
- Modify: `src/main/resources/templates/fragments/hinweis.html`
- Modify: `src/main/resources/templates/einrichtung/index.html`
- Modify: `src/main/resources/templates/fragments/einrichtungsstand.html`
- Test: `src/test/java/dev/hendrikhoemberg/webtesthelper/web/MuteRuleControllerTest.java`
- Test: `src/test/java/dev/hendrikhoemberg/webtesthelper/web/SettingsControllerTest.java`
- Test: `src/test/java/dev/hendrikhoemberg/webtesthelper/web/UserControllerTest.java`
- Test: `src/test/java/dev/hendrikhoemberg/webtesthelper/web/HelpControllerTest.java`

**Interfaces:**
- Produces: Overhauled mute rules matching preview, system settings, user admin modal, outbox logs, setup discovery progress, and manual reader.

- [ ] **Step 1: Run baseline admin & help tests**
  `./mvnw test -Dtest=MuteRuleControllerTest,SettingsControllerTest,UserControllerTest,HelpControllerTest`
- [ ] **Step 2: Update admin, help, and setup templates and fragments**
- [ ] **Step 3: Run admin & help tests — verify PASS**
  `./mvnw test -Dtest=MuteRuleControllerTest,SettingsControllerTest,UserControllerTest,HelpControllerTest`
- [ ] **Step 4: Commit**
  `git commit -m "feat(ui): overhaul mute rules, settings, user admin, outbox, and help views"`

---

### Task 9: Full Suite Verification & Message Key Consistency

**Files:**
- Test: `src/test/java/dev/hendrikhoemberg/webtesthelper/web/UiMessageKeyTest.java`
- Test: `src/test/java/dev/hendrikhoemberg/webtesthelper/web/EnumLabelsTest.java`
- Test: `src/test/java/dev/hendrikhoemberg/webtesthelper/web/VendoredAssetsTest.java`

- [ ] **Step 1: Run structural UI validation tests**
  `./mvnw test -Dtest=UiMessageKeyTest,EnumLabelsTest,VendoredAssetsTest`
- [ ] **Step 2: Run full fast test suite**
  `./mvnw test -Pfast`
- [ ] **Step 3: Run full verification suite (including browser acceptance tests)**
  `./mvnw test`
- [ ] **Step 4: Verify git status is clean**
