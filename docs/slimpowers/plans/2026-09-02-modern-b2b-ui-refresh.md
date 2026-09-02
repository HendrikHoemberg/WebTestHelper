# Modern High-Velocity B2B UI Refresh — Implementation Plan

**Goal:** Transform WebTestHelper's visual interface into a modern, high-velocity B2B engineering console (Option 1: Slate + Luminous Status Signals, micro-elevation depth, refined sidebar with active pill indicators, tabular telemetry, vertical connected journey pipeline, and centered auth/error cards) while strictly preserving German copy, server-rendered HTMX architecture, and desktop-first ergonomics.

**Architecture:** UI and styling enhancement across `app.css` and Thymeleaf templates (`layout.html`, `journey/detail.html`, `journey/edit.html`, `anmelden.html`, `error.html`, `kacheln.html`). Zero domain or backend changes. All text assertions and Spring Security configurations remain 100% intact.

**Tech Stack:** Spring Boot modular monolith, Thymeleaf + HTMX + Alpine, Spring Security (`sec:`), `messages.properties` German copy, CSS custom properties in `src/main/resources/static/css/app.css`. View tests: `@WebMvcTest` + MockMvc.

**Spec:** Driven by the UI Evaluation Review and user-confirmed design direction (Option 1: Minimalist Slate Pill + Luminous Glowing Status Dots).

## Global Constraints

- German-only UI; message keys remain `ui.*`; no raw enums, `{0}` placeholders, or ISO instants in HTML.
- View tests assert text and markup semantics (`containsString`), not CSS rules.
- Desktop-only layout; no mobile breakpoints or collapsible sidebar.
- Verification command: `./mvnw test -Pfast`.
- Single test command: `./mvnw test -Dtest=<ClassName>`.

---

## Task 1: Design Tokens, Micro-Elevation & Option 1 Luminous Badges in `app.css`

Replace the flat pastel variables with Option 1 Slate Capsule badges and glowing status dots, and introduce layered micro-elevation shadows and smooth transitions.

**Files:**
- Modify: `src/main/resources/static/css/app.css`
- Test: `src/test/java/dev/hendrikhoemberg/webtesthelper/web/DashboardControllerTest.java`

**Interfaces:**
- Consumes: Existing CSS classes (`.status-badge`, `.status-dot-pulse`, `.badge-healthy`, `.badge-failing`, `.badge-warning`, `.card-box`, `.kachel`).
- Produces: Slate-100 capsule badge aesthetics (`#f1f5f9` base, `#cbd5e1` border, `#0f172a` text), vibrant glowing status dots with ambient pulse rings, multi-layer card elevation shadows, and 150ms hover interactions.

- [x] **Step 1: Write the test verifying badge markup and status signals**
  Verify `DashboardControllerTest` continues to render valid markup for active/warning/error states.

- [x] **Step 2: Run the test to establish baseline**
  `./mvnw test -Dtest=DashboardControllerTest`
  Expected: PASS.

- [x] **Step 3: Update `app.css` with Option 1 Tokens and Micro-Elevation**
  Update `:root` in `src/main/resources/static/css/app.css`:

  ```css
  :root {
      /* Canvas & Surfaces */
      --bg-canvas: #f8fafc;
      --bg-color: #f8fafc;
      --card-bg: #ffffff;
      --surface-card: #ffffff;
      --surface-elevated: #ffffff;
      --surface-subtle: #f8fafc;
      --surface-hover: #f1f5f9;

      /* High-Contrast Monochrome Chrome & Brand */
      --text-main: #09090b;
      --text-color: #09090b;
      --text-body: #334155;
      --text-muted: #64748b;
      --text-faint: #94a3b8;

      --border-subtle: #e2e8f0;
      --border-color: #e2e8f0;
      --border-strong: #cbd5e1;
      --border-dark: #1e293b;

      /* Layered Micro-Elevation */
      --shadow-subtle: 0 1px 2px rgba(15, 23, 42, 0.04);
      --shadow-card: 0 1px 0 rgba(255, 255, 255, 0.9) inset, 0 1px 3px rgba(15, 23, 42, 0.05), 0 4px 12px -2px rgba(15, 23, 42, 0.03);
      --shadow-elevated: 0 1px 0 rgba(255, 255, 255, 0.9) inset, 0 6px 16px -2px rgba(15, 23, 42, 0.08), 0 2px 6px -1px rgba(15, 23, 42, 0.04);

      /* Primary Actions */
      --primary-color: #09090b;
      --primary-hover: #18181b;

      /* Sidebar Shell */
      --sidebar-width: 250px;
      --sidebar-bg: #09090b;
      --sidebar-border: #1e293b;
      --sidebar-text: #94a3b8;
      --sidebar-hover-text: #ffffff;
      --sidebar-active-bg: rgba(255, 255, 255, 0.08);
      --sidebar-active-text: #ffffff;

      /* Option 1: Slate Capsule + Luminous Signal Dots */
      --status-badge-bg: #f1f5f9;
      --status-badge-border: #cbd5e1;
      --status-badge-text: #0f172a;

      --status-success-dot: #10b981;
      --status-error-dot: #f43f5e;
      --status-warning-dot: #f59e0b;
      --status-info-dot: #0284c7;
      --status-inactive-dot: #94a3b8;
  }
  ```

  Update `.status-badge` and `.status-dot-pulse`:

  ```css
  .status-badge {
      display: inline-flex;
      align-items: center;
      gap: 0.45rem;
      padding: 0.225rem 0.65rem;
      border-radius: 9999px;
      font-size: 0.75rem;
      font-weight: 600;
      letter-spacing: 0.01em;
      line-height: 1;
      background: var(--status-badge-bg);
      border: 1px solid var(--status-badge-border);
      color: var(--status-badge-text);
      box-shadow: 0 1px 2px rgba(0, 0, 0, 0.03);
  }

  .status-dot-pulse {
      width: 7px;
      height: 7px;
      border-radius: 50%;
      position: relative;
      flex-shrink: 0;
  }

  .badge-healthy .status-dot-pulse,
  .ampel-gruen .status-dot-pulse {
      background: var(--status-success-dot);
      box-shadow: 0 0 6px rgba(16, 185, 129, 0.7);
  }

  .badge-failing .status-dot-pulse,
  .ampel-rot .status-dot-pulse {
      background: var(--status-error-dot);
      box-shadow: 0 0 6px rgba(244, 63, 94, 0.7);
  }

  .badge-warning .status-dot-pulse,
  .ampel-gelb .status-dot-pulse,
  .schritt-drift .status-dot-pulse {
      background: var(--status-warning-dot);
      box-shadow: 0 0 6px rgba(245, 158, 11, 0.7);
  }

  .status-inaktiv .status-dot-pulse,
  .ampel-grau .status-dot-pulse {
      background: var(--status-inactive-dot);
  }

  .status-dot-pulse::after {
      content: '';
      position: absolute;
      top: -2px; left: -2px; right: -2px; bottom: -2px;
      border-radius: 50%;
      background: inherit;
      opacity: 0.45;
      animation: pulse-ring 2.2s infinite ease-out;
  }

  @keyframes pulse-ring {
      0% { transform: scale(0.9); opacity: 0.7; }
      100% { transform: scale(2.3); opacity: 0; }
  }
  ```

- [x] **Step 4: Run test to verify passes**
  `./mvnw test -Dtest=DashboardControllerTest`
  Expected: PASS.

- [x] **Step 5: Commit**
  `git commit -m "style(ui): introduce Option 1 slate badges, luminous signal dots and micro-elevation tokens"`

---

## Task 2: Refined Sidebar Navigation with Ambient Depth & Active Pill Indicators

Upgrade the sidebar with a deep charcoal background, clear active pill indicator bar (`border-left: 3px solid #ffffff`), refined icon contrasts, and a polished user profile footer.

**Files:**
- Modify: `src/main/resources/static/css/app.css`
- Modify: `src/main/resources/templates/layout.html`
- Test: `src/test/java/dev/hendrikhoemberg/webtesthelper/web/DashboardControllerTest.java`

**Interfaces:**
- Consumes: Thymeleaf layout fragment `layout.html`.
- Produces: Polished sidebar navigation with crisp active pill highlights, avatar border ring, and smooth transitions.

- [x] **Step 1: Verify current sidebar tests pass**
  `./mvnw test -Dtest=DashboardControllerTest`
  Expected: PASS.

- [x] **Step 2: Update sidebar styles in `app.css`**
  Add active indicator styling, ambient sidebar gradient, and profile ring:

  ```css
  .app-sidebar {
      width: var(--sidebar-width);
      background: linear-gradient(180deg, #09090b 0%, #0c0e12 100%);
      border-right: 1px solid var(--sidebar-border);
      display: flex;
      flex-direction: column;
      position: fixed;
      top: 0;
      bottom: 0;
      left: 0;
      z-index: 100;
  }

  .nav-item-btn {
      display: flex;
      align-items: center;
      gap: 0.65rem;
      padding: 0.55rem 0.85rem;
      border-radius: 7px;
      color: var(--sidebar-text);
      text-decoration: none;
      font-size: 0.875rem;
      font-weight: 500;
      transition: all 0.15s cubic-bezier(0.4, 0, 0.2, 1);
      border: 1px solid transparent;
      position: relative;
  }

  .nav-item-btn:hover {
      color: var(--sidebar-hover-text);
      background: rgba(255, 255, 255, 0.05);
  }

  .nav-item-btn.active {
      color: #ffffff;
      background: var(--sidebar-active-bg);
      font-weight: 600;
      border-color: rgba(255, 255, 255, 0.1);
      box-shadow: 0 1px 3px rgba(0, 0, 0, 0.2);
  }

  .nav-item-btn.active::before {
      content: '';
      position: absolute;
      left: -0.75rem;
      top: 0.35rem;
      bottom: 0.35rem;
      width: 3px;
      background: #ffffff;
      border-radius: 0 2px 2px 0;
  }

  .avatar-circle {
      width: 30px;
      height: 30px;
      border-radius: 50%;
      background: #18181b;
      color: #ffffff;
      display: flex;
      align-items: center;
      justify-content: center;
      font-size: 11px;
      font-weight: 700;
      border: 1px solid rgba(255, 255, 255, 0.15);
      box-shadow: 0 1px 2px rgba(0, 0, 0, 0.2);
  }
  ```

- [x] **Step 3: Run the test to verify passes**
  `./mvnw test -Dtest=DashboardControllerTest`
  Expected: PASS.

- [x] **Step 4: Commit**
  `git commit -m "style(ui): refine sidebar navigation with ambient gradient, active pill indicators and avatar styling"`

---

## Task 3: Tabular Telemetry, Metric Typography & Sparkline Micro-Pips

Add tabular numerics across all statistics, KPI cards, table row metrics, durations, and timestamps, and sharpen sparklines and capacity meters.

**Files:**
- Modify: `src/main/resources/static/css/app.css`
- Modify: `src/main/resources/templates/fragments/kacheln.html`
- Modify: `src/main/resources/templates/laeufe/detail.html`
- Test: `src/test/java/dev/hendrikhoemberg/webtesthelper/web/DashboardControllerTest.java`, `src/test/java/dev/hendrikhoemberg/webtesthelper/web/RunControllerTest.java`

**Interfaces:**
- Consumes: Target card metrics and run details.
- Produces: Bold tabular numerics (`tabular-nums`, `-0.035em` tracking), vibrant run pips with glowing micro-indicators, and smooth progress meters.

- [x] **Step 1: Verify current metric rendering in tests**
  `./mvnw test -Dtest=DashboardControllerTest,RunControllerTest`
  Expected: PASS.

- [x] **Step 2: Update typography and telemetry classes in `app.css`**
  Ensure tabular figures and crisp metrics styling:

  ```css
  .kpi-value,
  .m-value,
  .num-tabular,
  .zell-zeit,
  .zell-id {
      font-variant-numeric: tabular-nums;
      letter-spacing: -0.025em;
  }

  .kpi-value {
      font-size: 2rem;
      font-weight: 800;
      color: var(--text-main);
      letter-spacing: -0.04em;
  }

  .run-pip {
      width: 7px;
      height: 18px;
      border-radius: 3px;
      background: #e2e8f0;
      transition: all 0.15s cubic-bezier(0.4, 0, 0.2, 1);
      cursor: pointer;
  }

  .run-pip:hover {
      transform: scaleY(1.3);
  }

  .run-pip.pip-pass {
      background: var(--status-success-dot);
      box-shadow: 0 0 4px rgba(16, 185, 129, 0.5);
  }

  .run-pip.pip-fail {
      background: var(--status-error-dot);
      box-shadow: 0 0 4px rgba(244, 63, 94, 0.5);
  }

  .run-pip.pip-warn {
      background: var(--status-warning-dot);
      box-shadow: 0 0 4px rgba(245, 158, 11, 0.5);
  }
  ```

- [x] **Step 3: Run the tests to verify passes**
  `./mvnw test -Dtest=DashboardControllerTest,RunControllerTest`
  Expected: PASS.

- [x] **Step 4: Commit**
  `git commit -m "style(ui): enhance telemetry typography with tabular numerics and glowing sparkline pips"`

---

## Task 4: Connected Journey Flow: Vertical Node Pipeline in Journey Detail & Edit

Replace the plain HTML table in `journey/detail.html` with a vertical connected node pipeline and connect step cards in `journey/edit.html`.

**Files:**
- Modify: `src/main/resources/templates/journey/detail.html`
- Modify: `src/main/resources/templates/journey/edit.html`
- Modify: `src/main/resources/static/css/app.css`
- Test: `src/test/java/dev/hendrikhoemberg/webtesthelper/web/JourneyControllerTest.java`, `src/test/java/dev/hendrikhoemberg/webtesthelper/web/JourneyEditControllerTest.java`

**Interfaces:**
- Consumes: Journey step list (`journey.steps`) and step health data.
- Produces: Visual vertical pipeline with continuous connector rail, circular numbered node badges, action chips, strategy code pills, assertion badges, and drift indicators.

- [x] **Step 1: Verify current journey tests pass**
  `./mvnw test -Dtest=JourneyControllerTest,JourneyEditControllerTest`
  Expected: PASS.

- [x] **Step 2: Add journey pipeline CSS styles to `app.css`**
  ```css
  /* Connected Journey Node Pipeline */
  .journey-timeline-rail {
      position: relative;
      padding-left: 2rem;
      display: flex;
      flex-direction: column;
      gap: 1rem;
      margin: 1.5rem 0;
  }

  .journey-timeline-rail::before {
      content: '';
      position: absolute;
      left: 0.95rem;
      top: 1rem;
      bottom: 1rem;
      width: 2px;
      background: var(--border-subtle);
  }

  .journey-step-node {
      position: relative;
      background: var(--surface-card);
      border: 1px solid var(--border-subtle);
      border-radius: 10px;
      padding: 1rem 1.25rem;
      box-shadow: var(--shadow-card);
      transition: all 0.15s ease;
  }

  .journey-step-node:hover {
      border-color: var(--border-strong);
      box-shadow: var(--shadow-elevated);
  }

  .journey-node-bullet {
      position: absolute;
      left: -2rem;
      top: 1.15rem;
      width: 22px;
      height: 22px;
      border-radius: 50%;
      background: #09090b;
      color: #ffffff;
      display: flex;
      align-items: center;
      justify-content: center;
      font-size: 0.75rem;
      font-weight: 700;
      font-variant-numeric: tabular-nums;
      border: 2px solid #ffffff;
      box-shadow: 0 1px 3px rgba(0, 0, 0, 0.2);
      z-index: 2;
  }
  ```

- [x] **Step 3: Update `journey/detail.html` with the connected node pipeline**
  Replace the table block with the connected timeline rail while preserving all text assertions tested in `JourneyControllerTest`:
  - Step ordinal numbers (`#1`, `#2`, etc.)
  - Step action badges (`GOTO`, `FILL`, `CLICK`, `ASSERT`)
  - Locator candidates with strategy pill (`TEST_ID`, `LABEL`, `CSS`, `ROLE`, `TEXT`) and code value
  - Value / credential template expressions (`{{cred.login.username}}`)
  - Assertion type and expected value (`VISIBLE: text`)
  - Optional step badge (`Optional`)
  - Timeout duration (`3000 ms`)
  - Drift warning badge (`Abweichung`)

- [x] **Step 4: Update `journey/edit.html` with node connectors**
  Add timeline rail styling to `#schritte-liste` in `journey/edit.html`.

- [x] **Step 5: Run the journey tests — verify they PASS**
  `./mvnw test -Dtest=JourneyControllerTest,JourneyEditControllerTest`
  Expected: PASS.

- [x] **Step 6: Commit**
  `git commit -m "feat(journey): render journey steps as modern connected vertical node pipeline"`

---

## Task 5: Centered Auth & Error Presentation Cards

Center `/anmelden` and `/error` (404/500) pages dead center in the viewport with an elevated card surface and ambient radial canvas.

**Files:**
- Modify: `src/main/resources/static/css/app.css`
- Modify: `src/main/resources/templates/anmelden.html`
- Modify: `src/main/resources/templates/error.html`
- Test: `src/test/java/dev/hendrikhoemberg/webtesthelper/web/BarePath404Test.java`, `src/test/java/dev/hendrikhoemberg/webtesthelper/web/AdminBootstrapTest.java`

**Interfaces:**
- Consumes: Login and error view routes.
- Produces: Viewport-centered authentication and 404 presentation cards with high elevation and clear visual hierarchy.

- [x] **Step 1: Verify current auth/error tests pass**
  `./mvnw test -Dtest=BarePath404Test,AdminBootstrapTest`
  Expected: PASS.

- [x] **Step 2: Update standalone auth and error styling in `app.css`**
  ```css
  .anmelde-seite {
      min-height: 100vh;
      width: 100vw;
      display: flex;
      flex-direction: column;
      align-items: center;
      justify-content: center;
      padding: 2rem 1.5rem;
      background: radial-gradient(ellipse at 50% 30%, #ffffff 0%, #e2e8f0 100%);
  }

  .anmelde-karte {
      background-color: var(--surface-card);
      border: 1px solid var(--border-subtle);
      border-radius: 14px;
      padding: 2.5rem 2rem 2rem;
      box-shadow: 0 1px 0 rgba(255, 255, 255, 0.9) inset, 0 12px 32px -4px rgba(15, 23, 42, 0.12), 0 4px 12px -2px rgba(15, 23, 42, 0.06);
      width: 100%;
      max-width: 26rem;
  }
  ```

- [x] **Step 3: Update `anmelden.html` and `error.html` markup**
  Ensure clean layout structure and button alignments.

- [x] **Step 4: Run the test suite — verify PASS**
  `./mvnw test -Dtest=BarePath404Test,AdminBootstrapTest`
  Expected: PASS.

- [x] **Step 5: Commit**
  `git commit -m "style(ui): center auth and error screens with elevated presentation cards"`

---

## Task 6: Full Verification & Visual Consistency Check

Run the comprehensive fast test suite and full gate to ensure all 1460+ tests pass cleanly without regression.

**Files:**
- Test: All suites (`./mvnw test -Pfast`)

- [x] **Step 1: Run fast verification gate**
  `./mvnw test -Pfast`
  Expected: BUILD SUCCESS (all tests pass).

- [x] **Step 2: Verify git status and clean diffs**
  `git status`
  Ensure no unintended files or untracked changes.
