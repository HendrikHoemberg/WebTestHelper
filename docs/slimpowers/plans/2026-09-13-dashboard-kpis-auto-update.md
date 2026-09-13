# Dashboard KPIs Auto-Update Implementation Plan

**Goal:** Ensure the dashboard's top-level KPIs (errors, warnings, infos, active runs) and system capacity card automatically update during periodic HTMX polling alongside the website tiles.

**Architecture:** Move the KPI hero strip and system capacity card from static placement in `uebersicht/index.html` into the live polling fragment `fragments/kacheln.html` under a root container (`#dashboard-live`). `GET /uebersicht/kacheln` already computes and provides the full `DashboardView`, so swapping the fragment automatically updates both metrics and tiles without backend changes.

**Tech Stack:** Spring Boot 4, Thymeleaf, HTMX, MockMvc.

**Spec:** Dashboard live view / spec 14.

## Global Constraints

- German-only UI; message keys `ui.*`; no internal identifiers rendered.
- Desktop-only UI: no responsive changes or mobile styling.
- View tests: `@WebMvcTest` + MockMvc; assertions on text/markup, not CSS.
- Context hygiene: always use `-B --no-transfer-progress` and pipefail + tail for test runs.

---

### Task 1: Integrate KPI Strip and Capacity Card into the Polled Dashboard Fragment

**Files:**
- Modify: `src/main/resources/templates/fragments/kacheln.html` (wrap KPIs, capacity, and tiles grid in the polling fragment root)
- Modify: `src/main/resources/templates/uebersicht/index.html` (delegate dynamic dashboard section to the polled fragment)
- Test: `src/test/java/dev/hendrikhoemberg/webtesthelper/web/DashboardControllerTest.java`

**Interfaces:**
- Consumes: `DashboardView` via `Model` attribute `"uebersicht"`, `pollSekunden` long.
- Produces: `fragments/kacheln :: kacheln` containing `#dashboard-live` with KPI hero metrics, system capacity card, and `#dashboard-kacheln` tiles grid.

- [x] **Step 1: Write the failing test**
  Add a test in `DashboardControllerTest.java` asserting that `GET /uebersicht/kacheln` includes the KPI metrics and capacity indicator:

  ```java
  @Test
  @WithMockUser(roles = "USER")
  void dashboardKachelnFragmentRendersKpisAndSystemCapacity() throws Exception {
      when(dashboardService.overview()).thenReturn(sampleView());

      mvc.perform(get("/uebersicht/kacheln"))
              .andExpect(status().isOk())
              .andExpect(content().string(containsString("dashboard-live")))
              .andExpect(content().string(containsString("über alle Websites")))
              .andExpect(content().string(containsString("Systemkapazität")))
              .andExpect(content().string(containsString("dashboard-kacheln")));
  }
  ```

- [x] **Step 2: Run the single test — verify it FAILS**
  Command:
  `bash -c "set -o pipefail; ./mvnw test -Dtest=DashboardControllerTest#dashboardKachelnFragmentRendersKpisAndSystemCapacity -B --no-transfer-progress | tail -n 25"`
  Expected: FAIL with `Response content does not contain "dashboard-live"`.

- [x] **Step 3: Write minimal implementation**
  In `src/main/resources/templates/fragments/kacheln.html`:
  Define the fragment `kacheln` on an outer container `#dashboard-live` with HTMX polling attributes (`hx-get="/uebersicht/kacheln"`, `hx-trigger="every ${pollSekunden}s"`, `hx-swap="outerHTML"`).
  Inside this container, include:
  1. The KPI Hero Metrics Strip (`<section class="kpi-hero-grid uebersicht-kennzahlen">...`)
  2. The System Capacity card (`<div class="card-box" ...>...`)
  3. The Target Tiles Grid (`<div id="dashboard-kacheln" class="targets-layout-grid dashboard-raster">...`)

  In `src/main/resources/templates/uebersicht/index.html`:
  Replace the static KPI section, capacity box, and tiles fragment include with:
  `<div th:replace="~{fragments/kacheln :: kacheln}"></div>`.

- [x] **Step 4: Run the single test — verify it PASSES**
  Command:
  `bash -c "set -o pipefail; ./mvnw test -Dtest=DashboardControllerTest -B --no-transfer-progress | tail -n 25"`
  Expected: PASS (all 13 tests in DashboardControllerTest pass).

- [x] **Step 5: Run full verification suite**
  Command:
  `bash -c "set -o pipefail; ./mvnw test -B --no-transfer-progress | tail -n 60"`
  Expected: BUILD SUCCESS.
