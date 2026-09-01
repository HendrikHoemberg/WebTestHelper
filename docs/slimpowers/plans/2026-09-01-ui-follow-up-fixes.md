# UI Follow-Up Fixes Implementation Plan

**Goal:** Fix the two functional/UX defects found in the UI review (run-report 500, raw
ISO timestamps) and the three layout/polish issues (full-width content, floating "?" help
button, login vertical alignment) in one focused pass.

**Architecture:** The critical defect is a schema/version gap — `FindingStore.snapshotOf()`
reads a `run_report_snapshot` header table that an existing install does not have, so the
Fallthrough `diffForReport` never runs and the view throws. That is fixed with a forward Flyway
migration. The remaining items are isolated template/CSS changes; the CSS ones are unguarded by
tests (per project convention view tests assert text, not CSS) so they are verified visually.

**Tech Stack:** Spring Boot 4.1 + Thymeleaf + HTMX/Alpine, Postgres + Flyway, Maven (`./mvnw`).

**Spec:** `/home/hendrik/Documents/Coding/WebTestHelper/docs/slimpowers/plans/2026-09-01-ui-follow-up-fixes.md` (this file). Findings source: `docs/slimpowers/plans/2026-09-01-ui-overhaul.md` + live review.

## Global Constraints

- German-only UI via `ui.*` message keys; **no internal identifiers, `{0}` placeholders, or raw ISO instants in rendered HTML** (AGENTS.md).
- View tests: `@WebMvcTest` + MockMvc; assert on text/markup, **never on CSS**.
- Do not edit `data/`, `target/`, `.env`, `compose.yaml`.
- Do not change the Flyway checksum of already-applied migrations (`V1`–`V27`) — add a new forward migration instead.
- Verify (everything): `./mvnw test -Pfast`. Single test: `./mvnw test -Dtest=<TestClass>`.

---

### Task 1: Add the missing `run_report_snapshot` migration (fixes the run-report 500)

**Files:**
- Create: [`src/main/resources/db/migration/V28__run_report_snapshot.sql`](/home/hendrik/Documents/Coding/WebTestHelper/src/main/resources/db/migration/V28__run_report_snapshot.sql) — forward migration creating the missing header table.

**Interfaces:**
- Consumes: nothing (schema-level).
- Produces: a `run_report_snapshot(run_id BIGINT PRIMARY KEY)` table on existing installs so
  `FindingStore.snapshotOf()` (which runs `SELECT EXISTS (SELECT FROM run_report_snapshot WHERE run_id = ?)`)
  stops throwing `PSQLException: relation "run_report_snapshot" does not exist`.

**Step 1: Write the migration**

```sql
-- Existing installs were migrated before this header table was present in V27. The run-report
-- classification logic needs it to exist (FindingStore.snapshotOf). IF NOT EXISTS keeps this a
-- no-op on fresh installs where V27 already created it.
CREATE TABLE IF NOT EXISTS run_report_snapshot (
    run_id BIGINT PRIMARY KEY
);
```

**Step 2: Verify fresh-install path (must still pass)**

- Command: `./mvnw test -Dtest=RunReportAcceptanceTest -Pfast`
- Expected: PASS (Testcontainers migrates `V1`→`V28`; `CREATE TABLE IF NOT EXISTS` conflicts with nothing).

**Step 3: Verify upgrade path on the real database**

- Command: start a throwaway instance on port 9091 (scheduling/poller disabled) so Flyway applies `V28` to the deployed DB, then confirm the run page renders:
  ```bash
  WTH_ADMIN_PASSWORD=evalpass123 WTH_BASE_URL=http://localhost:9091 ./mvnw spring-boot:run \
    -Dspring-boot.run.arguments="--server.port=9091 --webtesthelper.scheduling.tick-enabled=false --webtesthelper.runner.poller-enabled=false"
  # then (after startup) with curl+CSRF login: GET /laeufe/20 -> 200, not 500
  ```
- Expected: `/laeufe/20` and `/laeufe/37` return **200** (no Whitelabel error). The `09*` screenshots regenerate as real run reports.
- Cleanup: kill the 9091 process. Note: the user's `:9090` instance picks up `run_report_snapshot` on its next restart (Flyway re-runs `V28`; `IF NOT EXISTS` makes the second apply a no-op).

**Step 4: Verify everything still passes**

- Command: `./mvnw test -Pfast`
- Expected: PASS.

- [x] Step 1: migration written
- [x] Step 2: `RunReportAcceptanceTest` passes (fresh DB)
- [x] Step 3: `/laeufe/20` returns 200 after `V28` applied on real DB
- [x] Step 4: `./mvnw test -Pfast` passes
- [x] Commit: `git commit -m "fix(db): add run_report_snapshot migration so run reports render on existing installs"`

---

### Task 2: Format the raw ISO timestamps in the run-history table

**Files:**
- Modify: [`src/main/resources/templates/websites/detail.html:153`](/home/hendrik/Documents/Coding/WebTestHelper/src/main/resources/templates/websites/detail.html) — render `queuedAt` as a localized date, not a raw `Instant`.
- Test: [`src/test/java/dev/hendrikhoemberg/webtesthelper/web/SiteDetailControllerTest.java`](/home/hendrik/Documents/Coding/WebTestHelper/src/test/java/dev/hendrikhoemberg/webtesthelper/web/SiteDetailControllerTest.java) — assert formatted date present and raw ISO absent (mirrors the existing `...WithoutRawCronInProse` test).

**Interfaces:**
- Consumes: `RunSummary.queuedAt` (`Instant`) already provided by `runService.recentForSite()`.
- Produces: the "Verlauf der Prüfläufe" `Zeitpunkt` cell shows `25.08.2026 10:00` style text.

- [x] **Step 1: Write the failing test**
  In `getSiteDetailRenders...` (the existing test that stubs `runSummary` with `Instant.parse("2026-08-25T10:00:00Z")`), add to the existing `mvc.perform(...).andExpect(...)` chain:
  ```java
  .andExpect(content().string(containsString("25.08.2026 10:00")))
  .andExpect(content().string(not(containsString("2026-08-25T10:00:00Z"))))
  ```
  Add `import static org.hamcrest.Matchers.not;` if not already present.
- [x] **Step 2: Run the single test — verify it FAILS**
  `./mvnw test -Dtest=SiteDetailControllerTest` → expected: FAIL (`"25.08.2026 10:00"` not found; raw ISO still present).
- [x] **Step 3: Write minimal implementation**
  Change line 153 from:
  ```html
  <td style="font-variant-numeric: tabular-nums;" th:text="${run.queuedAt}">2026-08-25T10:00:00Z</td>
  ```
  to:
  ```html
  <td style="font-variant-numeric: tabular-nums;" th:text="${#temporals.format(run.queuedAt, 'dd.MM.yyyy HH:mm')}">25.08.2026 10:00</td>
  ```
- [x] **Step 4: Run the single test — verify it PASSES**
  `./mvnw test -Dtest=SiteDetailControllerTest` → expected: PASS.
- [x] **Step 5: Commit**
  `git commit -m "fix(ui): format run-history timestamps instead of rendering raw ISO instants"`

---

### Task 3: Constrain the shared content width (fix the full-width look)

**Files:**
- Modify: [`src/main/resources/static/css/app.css:276`](/home/hendrik/Documents/Coding/WebTestHelper/src/main/resources/static/css/app.css) — one shared max-width for the content column.

**Interfaces:**
- Consumes: `--sidebar-width: 250px` (fixed) + `.app-main-wrapper { margin-left }`.
- Produces: every page's `.workspace-content` is capped and centered on wide displays; inner
  auto-fit grids (KPI, help) still fill.

- [x] **Step 1: Write the change (no automated test; CSS change)**
  Lower `.workspace-content` from `max-width: 1400px` to `max-width: 1200px`; keep `width: 100%; margin: 0 auto;` and the existing padding. This keeps a single, centered, readable column on large windows while letting KPI/help grids fill the width.
- [x] **Step 2: Verify visually**
  Screenshot the dashboard, site detail, findings list, help, and settings at a wide viewport (e.g. 1920×1080) and confirm cards no longer stretch edge-to-edge. No test update is required — project convention forbids CSS assertions in view tests.
- [x] **Step 3: Commit**
  `git commit -m "style(ui): constrain shared content width to a centered readable column"`

---

### Task 4: Fix the floating "?" help toggle alignment

**Files:**
- Modify: [`src/main/resources/static/css/app.css`](/home/hendrik/Documents/Coding/WebTestHelper/src/main/resources/static/css/app.css) — add the missing `.abschnitt-ueberschrift-zeile` and `.hinweis-schalter` rules (both currently have **no** CSS rule; the dashboard header inline style omits `display:flex`, so the button stacks under the title).

**Interfaces:**
- Consumes: `.abschnitt-ueberschrift-zeile` (used by dashboard, laufe, journeys, stummschaltungen, einstellungen, einrichtung headers) and `.hinweis-schalter` (the "?" buttons).
- Produces: "?" help toggles sit flush-right of section titles instead of floating detached.

- [x] **Step 1: Write the change** — append to the header/helpers area of `app.css`:
  ```css
  .abschnitt-ueberschrift-zeile {
      display: flex;
      align-items: center;
      justify-content: space-between;
      gap: 0.75rem;
  }
  .hinweis-schalter {
      flex-shrink: 0;
      width: 1.5rem;
      height: 1.5rem;
      border: 1px solid var(--border-subtle);
      border-radius: 9999px;
      background: var(--surface-subtle);
      color: var(--text-muted);
      font-size: 0.85rem;
      font-weight: 700;
      display: inline-flex;
      align-items: center;
      justify-content: center;
      cursor: pointer;
  }
  .hinweis-schalter:hover { border-color: var(--border-strong); color: var(--text-main); }
  ```
  Keep any inline `style="display:flex; ..."` on individual rows (same value — harmless).
- [x] **Step 2: Verify visually**
  Screenshot dashboard, einrichtung, journeys, stummschaltungen, settings — the "?" must align with the section title on the right edge.
- [x] **Step 3: Commit**
  `git commit -m "style(ui): align help toggles with section titles via missing header/button rules"`

---

### Task 5: Vertically center the login screen

**Files:**
- Modify: [`src/main/resources/static/css/app.css:1053`](/home/hendrik/Documents/Coding/WebTestHelper/src/main/resources/static/css/app.css) — `.anmelde-seite` / `.anmelde-karte` / `.anmelde-marke`.

**Interfaces:**
- Consumes: standalone `anmelden.html` (`<main class="anmelde-seite">` containing `.anmelde-marke` + `.anmelde-karte`).
- Produces: centered login card on any viewport height.

- [x] **Step 1: Write the change**
  Replace `.anmelde-seite`:
  ```css
  .anmelde-seite {
      min-height: 100vh;
      display: flex;
      flex-direction: column;
      align-items: center;
      justify-content: center;
      padding: 1.5rem;
  }
  ```
  Add `width: 100%;` and `max-width: 26rem;` to `.anmelde-marke` (so the brand line and card share the same column) and keep `.anmelde-karte { width: 100%; max-width: 26rem; }`. Remove the now-obsolete `margin: 6rem auto 0`.
- [x] **Step 2: Verify visually**
  Screenshot `/anmelden` at 1440×900 and at a tall viewport — brand + card centered both vertically and horizontally.
- [x] **Step 3: Commit**
  `git commit -m "style(ui): vertically center the standalone login screen"`

---

## Self-Review

- **Spec coverage**
  - Run-report 500 → Task 1 (migration).
  - Raw ISO timestamps → Task 2 (template + test).
  - Full-width layout → Task 3 (CSS max-width).
  - Floating "?" button → Task 4 (CSS header/button rules).
  - Login vertical centering → Task 5 (CSS).
  All five review findings have a task. CSS tasks (3, 4, 5) are intentionally unguarded by automated tests per the "no CSS assertions" convention and are verified via screenshots.
- **Placeholder scan** — none; every task has concrete file paths and code.
- **Type consistency** — `RunSummary.queuedAt` is an `Instant`; `#temporals.format(...)` accepts it. `recentRuns` is `List<RunSummary>`; `run.queuedAt` resolves. `V28` uses `IF NOT EXISTS` so it is idempotent across fresh and upgraded DBs.

**Execution note:** Tasks 1, 2 are testable via Maven; Tasks 3, 4, 5 are CSS and verified by re-screenshotting. After all tasks, run `verification-before-completion`: full `./mvnw test -Pfast` (must pass), plus one live screenshot pass over the affected pages on `:9090`.
