# WebTestHelper Plan 9 — Is Anything Wrong, and What Does This Site Contain

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** two screens and one Settings section. The **dashboard** (§12) answers *is anything
wrong* in one look across every site. **Guided setup** (§13.3) turns adding a site from filling in
a blank form into confirming a proposal. **Settings** grows the users and concurrency rows §12
lists and Phase 1 skipped.

**Architecture:** No new module and **no migration**. The dashboard is three grouped queries —
one per source module — assembled in `reporting`, which §5.1 already names as the owner of
dashboard queries. Guided setup splits in two: the browser work is a `crawler` component beside
`CrawlService`, the background execution and the proposal mapping are a `runner` service beside
`ArtifactRetentionService`. `web` renders both and owns the user screen, where `AppUserService`
already lives.

**Tech Stack:** Java 25, Spring Boot 4.1.1, PostgreSQL 17 via Testcontainers, Thymeleaf, HTMX,
Alpine, Playwright/Chromium. **No new dependency.**

**Spec:** `docs/superpowers/specs/2026-08-21-webtesthelper-design.md`; §-references point there.
**Roadmap:** `2026-08-25-webtesthelper-phase-2-roadmap.md` — plan 9 of 9, the last of Phase 2.
Its deviation index (D38–D45), plan 7's (D46–D52), plan 8's (D53–D60), the Phase-1 table
(D1–D37), `CLAUDE.md`'s plan calibration and `CLAUDE.md`'s test rules all apply and are **not**
restated.

**Ends with:** a colleague opens the app and sees twelve tiles. Ten are green, one is amber
because last night's crawl stopped on its page budget, one is red with *3 Fehler*. They click the
red one. Later an administrator adds a thirteenth site, waits about twenty seconds, and is shown
*"Kontaktformular auf /kontakt gefunden"*, *"Video auf /medien gefunden"*, *"sitemap.xml
gefunden"* — with the matching checks already ticked — and presses **Übernehmen**.

**One browser test**, and the roadmap said to decide it against a measurement. The measurement is
below; it is the only task in this plan that needs Chromium.

---

## The roadmap's open question, answered

> *Does the guided-setup probe need a browser? It detects forms, languages, videos, Maps embeds
> and PDFs on one page — `PageNavigator` already extracts all five into a `PageSnapshot`, so a
> single-page crawl may be the whole probe. Measure before planning.*

**A browser, yes. One page, no.** Two measurements, both against the fixture site, which §15 calls
the highest-value asset in the project precisely so questions like this are answerable:

1. **The five signals are not on the homepage.** `src/test/resources/fixture-site/index.html`
   carries two `<link rel="alternate" hreflang>` and two PDF links — languages and documents. The
   contact form is on `/kontakt.html`, the Maps iframe is on `/kontakt.html`, the `<video>` and
   `<audio>` are on `/medien.html`. A one-page probe cannot produce §13.3's *own worked example*,
   *"Kontaktformular auf /kontakt gefunden"*, on the site the project uses to prove itself.
2. **A browser-free probe cannot see them either.** There is no HTML parser on the classpath
   (no jsoup), and the extraction that finds all five in one pass already exists as
   `crawler/extract.js`, run by `PageNavigator.capture` inside one `page.evaluate`. Re-deriving it
   over raw HTTP would be a second, weaker implementation of the project's most-exercised code,
   and would miss every form and map a site renders with JavaScript.

So the probe is **the homepage plus up to seven admitted internal links, at depth 0, through the
existing `BrowserPool` and `PageNavigator`** (D66). On the fixture that is the homepage, then
`/leistungen.html`, `/kontakt.html`, `/medien.html`, `/mixed-content.html`, `/en/index.html` —
form and media reached at links two and three.

The cost is the reason the probe is asynchronous rather than a request-scoped call:
`webtesthelper.crawler.navigation-timeout` is **30 s** in production (5 s only under
`application-test.properties`), so eight pages is a 240 s worst case. No HTTP request waits that
long. §13.3's probe is a background job with a polled screen (D67).

## Deviations this plan introduces

- **D61 — the dashboard read model lives in `reporting`, which gains `scheduling`.** §5.1 assigns
  "dashboard queries" to `reporting` in as many words. It already reads `catalog`, `findings` and
  `runner`; the next scheduled run is the fourth input and it is in `scheduling`. The alternative,
  assembling in `web`, puts a four-module join in the layer that is supposed to render — and p8's
  findings record what that costs: a `@Component` model helper in `web` is invisible to a
  `@WebMvcTest` slice and needed `@Import` in three tests. No cycle: `scheduling` depends on
  `{model, catalog, runner}` and never on `reporting`.
- **D62 — the traffic light counts everything `TriageStatus.SILENCING` does not silence.**
  `ACKNOWLEDGED` is *"I have seen this and it is still broken"*, not *"this is fine"*, so a site
  whose only `ERROR` is acknowledged is red. The escape hatch is the one plan 7 built: mute it, or
  mark it won't-fix, with a reason and an expiry. The dashboard therefore reuses D47's
  `SILENCING_IN_CLAUSE` rather than inventing a second definition of "quiet" — the same argument
  D57 made about the finding renderer, one layer up.
- **D63 — `/` is the dashboard; the site list moves to `GET /websites`.** §12 lists the dashboard
  first for a reason: the site list answers *what do we watch*, which nobody asks twice a day.
- **D64 — the dashboard polls every 30 s**, not the run detail's 3 s. D44 chose a poll over SSE
  because the dashboard's live regions tolerate lag; 3 s would run a full-table aggregate twenty
  times a minute per viewer to watch numbers that change when a crawl finishes, which is minutes.
- **D65 — concurrency is displayed, not edited.** `BrowserPool`'s constructor launches one
  Playwright and one Chromium process per worker and nothing can resize it: shrinking the pool
  mid-crawl would have to kill a process holding an open `Page`. Settings shows the configured
  values, the live saturation beside them, and the environment variable that changes each — with
  §13.4's sentence saying a restart is required. An editable field that silently does nothing
  until the next restart is worse than a read-only one that says so.
- **D66 — the probe crawls the homepage plus up to seven admitted internal links.** Measured
  above. Candidates pass through the existing `UrlAdmission` at **depth 0**, exactly as
  `CrawlService.seedFrontier` admits the pinned pulse set: they are entry points, and a site whose
  `maxDepth` is 0 must still be probeable.
- **D67 — probe state is in-memory, per-process, with no table and no column.** A probe is a
  proposal that lives for the twenty seconds between pressing *Anlegen* and pressing *Übernehmen*;
  losing it to a restart costs one button press. p8's findings make the alternative explicit:
  *"a nullable column that means 'not yet done' is a backlog for every row that predates it"* — a
  `site.setup_completed_at` would render every existing site as un-set-up and would need a
  backfill migration to say something nobody asked for.
- **D68 — guided setup does not pin key pages.** `CrawlRunExecutor.pinKeyPagesAfterFullCrawl`
  fires only when `site.pinnedKeyPages().isEmpty()`, so any set the probe wrote would
  **permanently** pre-empt the first full crawl's — an eight-page ranking beating a several-
  hundred-page one, forever, with no screen that explains why. The pulse set stays plan 6's.
- **D69 — the maps-embed predicate moves to `model` as `FrameRef.isMapsEmbed()`.** The probe is in
  `crawler`, which may not depend on `checks`, and `IframeEmbedCheck.isMapsEmbed` is the rule that
  already knows a Maps embed is a `/maps/embed` path *or* a Google host with `/maps` — the fixture
  serves its Maps frame from its own host, so a host-only copy would silently fail on the very
  fixture that proves the check. One rule, on the value type both callers already hold.
- **D70 — guided setup is `USER`, not `ADMIN`.** §12 splits the roles as `ADMIN` (users, sites,
  settings) and `USER` (configure checks, triage, record). Guided setup *configures checks*.
  Creating the site that leads to it stays `ADMIN`.
- **D71 — the last enabled `ADMIN` cannot be disabled, demoted or deleted.** There is no password
  reset flow and no second channel; `AdminBootstrap` only seeds an admin when the table is
  **empty**, so an app with one disabled admin and three users is locked out permanently and the
  repair is a manual `UPDATE` against production.

## Decided constants

| Constant | Value | Why |
|---|---|---|
| dashboard poll | 30 s, `webtesthelper.dashboard.poll-interval` | D64 |
| probe pages | 8, `webtesthelper.setup.probe-pages` | homepage + 7. A primary nav is typically 5–9 items and *Kontakt* is usually last in it |
| probe budget | 120 s, `webtesthelper.setup.probe-timeout` | checked **between** pages, from the first navigation. Waiting for a busy `BrowserPool` is not counted: a probe that failed during every nightly crawl window would be useless exactly when someone is onboarding a site |
| probe result TTL | 1 h | D67's map is swept on every `start`; nobody returns to a proposal an hour later |
| open-count severities | `ERROR`, `WARN`, `INFO` separately | the tile shows errors and warnings; `INFO` is a total only, per D58's reasoning about `UNVERIFIABLE` |

## URL vocabulary added

| Path | Method | Role | Screen |
|---|---|---|---|
| `/` | GET | USER | Dashboard (was: site list) |
| `/uebersicht/kacheln` | GET | USER | The polled tile grid fragment |
| `/websites` | GET | USER | Site list (D63) |
| `/websites/{id}/einrichtung` | GET | USER | Guided setup wizard |
| `/websites/{id}/einrichtung/stand` | GET | USER | Polled probe-status fragment |
| `/websites/{id}/einrichtung` | POST | USER | Apply the confirmed proposal |
| `/websites/{id}/einrichtung/neu` | POST | USER | Re-run the probe |
| `/einstellungen/benutzer` | GET | ADMIN | User list and create form |
| `/einstellungen/benutzer` | POST | ADMIN | Create a user |
| `/einstellungen/benutzer/{id}` | POST | ADMIN | Change role, enable/disable, set password |
| `/einstellungen/benutzer/{id}/loeschen` | POST | ADMIN | Delete a user |
| `/hilfe/uebersicht`, `/hilfe/einrichtung` | GET | USER | Two new handbook topics |

Ant `*` does not cross `/`, so the existing `POST /websites/*` ADMIN matcher does **not** catch
`/websites/*/einrichtung`; D70's rule needs its own matcher, added before `anyRequest()`.

---

### Task 1: Open findings per site, in one query

The grid's first input. One statement for the whole table, because fifty sites polling every 30 s
must not be fifty statements.

**Files:**
- Create: `findings/OpenFindingCounts.java`
- Modify: `findings/FindingStore.java`, `findings/FindingService.java`
- Test: `findings/OpenFindingCountsTest.java` (extends `AbstractPostgresTest`)

**Interfaces (produces):**
- `record OpenFindingCounts(int errors, int warnings, int infos, int untriaged)` with
  `static OpenFindingCounts none()` and `int total()`.
- `FindingStore.openCountsBySite() → Map<Long, OpenFindingCounts>` — sites with no open findings
  are **absent from the map**, not present with zeros; the caller has the site list and the
  dashboard has to render a tile for a site that has never run at all.
- `FindingService.openCountsBySite()` delegating, `@Transactional(readOnly = true)`.

- [ ] **Step 1: Write the failing test.** Seed two sites through `FindingService.record` and
      triage: site A gets one `ERROR` left `UNTRIAGED`, one `WARN` `ACKNOWLEDGED`, one `ERROR`
      `MUTED`, one `INFO`; site B gets one resolved `ERROR` and nothing else. Assert A maps to
      `errors=1, warnings=1, infos=1, untriaged=2` — the muted `ERROR` is **not** counted (D62)
      and the acknowledged `WARN` **is**; assert B is absent from the map entirely; assert a site
      id with no findings is absent. Add one case proving the untriaged count is a subset, not a
      separate axis: `untriaged <= errors + warnings + infos` on both.

- [ ] **Step 2: Run it and watch it fail** — `./mvnw test -Pfast -Dtest=OpenFindingCountsTest`.

- [ ] **Step 3: Implement the query.** One statement, aggregating on the axis
      `ix_finding_site_open (site_id, observed_status, triage_status)` already indexes. The
      `FILTER` clause is what keeps the untriaged count from being a second round-trip, and the
      silencing list is interpolated from the enum for the same reason D47 gave.

```sql
SELECT site_id, severity,
       count(*)                                              AS open_count,
       count(*) FILTER (WHERE triage_status = 'UNTRIAGED')    AS untriaged_count
  FROM finding
 WHERE observed_status = 'ACTIVE'
   AND triage_status NOT IN (%s)   -- SILENCING_IN_CLAUSE, shared with DIFF_SQL (D62)
 GROUP BY site_id, severity
```

- [ ] **Step 4: Run the suite** — `./mvnw test -Pfast`.

- [ ] **Step 5: Commit.**

```bash
git commit -am "feat(findings): open findings per site in one grouped query"
```

---

### Task 2: The last run and the next run, per site

The grid's other two inputs. Both are *"one row per site, the interesting one"*, and both are a
Postgres `DISTINCT ON` chosen so the existing indexes serve them and this plan adds no migration.

**Files:**
- Create: `runner/LastRun.java`, `runner/persistence/RunDashboardJdbcRepository.java`
- Modify: `runner/RunService.java`, `scheduling/ScheduleService.java`,
  `scheduling/persistence/ScheduleRepository.java`
- Test: `runner/LastRunPerSiteTest.java`, `scheduling/NextFirePerSiteTest.java` (both extend
  `AbstractPostgresTest`)

**Interfaces (produces):**
- `record LastRun(long siteId, long runId, RunStatus status, Instant finishedAt, boolean partialCoverage)`
- `RunService.lastTerminalPerSite() → Map<Long, LastRun>` — only `COMPLETED` and `FAILED`; a site
  whose only run is `QUEUED` is absent, because "nothing has finished here yet" and "it is green"
  are different answers.
- `RunService.runsInFlight() → int` — `QUEUED` plus `RUNNING`, all scopes. One `count(*)`.
- `ScheduleService.nextFirePerSite() → Map<Long, Schedule>` — the earliest future occurrence per
  site across the three tiers, `enabled` rows with a non-null `next_fire_at` only, and only for
  sites that are themselves enabled (D41's predicate, which `ScheduleRepository.findDue` already
  carries and which must not be re-derived in Java here).

- [ ] **Step 1: Write the two failing tests.** `LastRunPerSiteTest`: a site with a `COMPLETED` run
      then a newer `FAILED` one maps to the `FAILED`; a site whose newest run is `RUNNING` maps to
      the older terminal one; a site with only a `QUEUED` run is absent; `partialCoverage` is
      carried through; `runsInFlight` counts a `QUEUED` and a `RUNNING` across two sites as 2.
      `NextFirePerSiteTest`: a site with `PULSE` tomorrow 03:00 and `FULL` on Sunday maps to the
      `PULSE` row; disabling the `PULSE` tier moves it to the `FULL` row; disabling the **site**
      removes it from the map; a row whose `next_fire_at` is null (plan 6's "never fires again"
      cron) is skipped rather than sorting first.

- [ ] **Step 2: Run them and watch them fail.**

- [ ] **Step 3: Implement the run projection.** `ORDER BY queued_at DESC` and not `finished_at`,
      deliberately: that is the exact shape of `ix_run_site_recent (site_id, queued_at DESC)`, so
      the statement is an index scan and the plan needs no new index and no migration.

```sql
SELECT DISTINCT ON (site_id)
       site_id, id, status, finished_at, partial_coverage
  FROM run
 WHERE status IN ('COMPLETED', 'FAILED')
 ORDER BY site_id, queued_at DESC, id DESC
```

- [ ] **Step 4: Implement the schedule projection** as a `@Query` on `ScheduleRepository` joining
      `SiteEntity` for the enabled predicate — the same JPQL cross-module reference plan 6's
      findings document on `findDue`, kept greppable the same way. Order by
      `(site_id, next_fire_at)` and take the first per site in Java **only if** a `DISTINCT ON`
      cannot be expressed in JPQL; a native query returning `(site_id, scope, next_fire_at)` is
      preferred and stays inside the module.

- [ ] **Step 5: Run the suite** — `./mvnw test -Pfast`.

- [ ] **Step 6: Commit.**

```bash
git commit -am "feat(runner,scheduling): last run and next run, one row per site"
```

---

### Task 3: `DashboardService`, the traffic light, and system capacity

The assembly and the only judgement call on the screen: what makes a site red.

**Files:**
- Create: `reporting/DashboardService.java`, `reporting/DashboardView.java`,
  `reporting/SiteTile.java`, `reporting/TrafficLight.java`, `runner/SystemCapacity.java`,
  `runner/CapacityService.java`
- Modify: `reporting/package-info.java` (add `scheduling`, D61)
- Test: `reporting/DashboardServiceTest.java`, `reporting/TrafficLightTest.java`

**Interfaces (produces):**
- `enum TrafficLight { GRUEN, GELB, ROT, GRAU }` with
  `static TrafficLight of(boolean siteEnabled, LastRun lastRun, OpenFindingCounts counts)` — a
  pure function, which is what makes `TrafficLightTest` a table of cases with no Spring in it.
- `record SiteTile(long siteId, String name, String baseUrl, boolean enabled, TrafficLight light,
  LastRun lastRun, OpenFindingCounts counts, Schedule nextRun)` — `lastRun` and `nextRun` nullable.
- `record DashboardView(List<SiteTile> tiles, OpenFindingCounts totals, int runsInFlight,
  Instant nextFireAt, boolean schedulingPaused, SystemCapacity capacity)`
- `DashboardService.overview() → DashboardView`, `@Transactional(readOnly = true)`, **five**
  queries in total regardless of site count: sites, open counts, last runs, next fires, in-flight.
- `record SystemCapacity(int browserWorkersTotal, int browserWorkersBusy, int queuedRuns,
  int failedMails, Duration pollInterval, int schedulerThreads)`
- `CapacityService.current() → SystemCapacity` in `runner`, which is where the queue lives and the
  only module allowed to hold `BrowserPool` and be read by both `web` and `reporting`.
  `failedMails` is passed in by the caller, not read here: `runner` must not learn that mail exists
  (D53).

The light, in precedence order — each row is one test in `TrafficLightTest`:

| Light | When | Why not something else |
|---|---|---|
| `GRAU` | the site is disabled (§14's per-site kill switch) | evaluated first and alone: nothing is watching this site, so green would be a lie and red would be noise |
| `ROT` | the last terminal run is `FAILED`, **or** `counts.errors() > 0` | a failed run is worse than any finding it would have produced (§11.1) |
| `GELB` | `counts.warnings() > 0`, **or** the last run had `partialCoverage`, **or** there is no terminal run yet | partial coverage means *"we did not look everywhere"*, which is not green; a site that has never finished a run has nothing to be green about |
| `GRUEN` | otherwise | including a site whose only open findings are `INFO` |

- [ ] **Step 1: Write `TrafficLightTest`** as a table over the four rows plus the three
      precedence collisions that actually decide the rule: a disabled site with 5 errors is
      `GRAU`; a `FAILED` last run with zero findings is `ROT`; a `COMPLETED` partial run with one
      error is `ROT`, not `GELB`. Add the D62 case: `counts` built with a silenced error excluded
      upstream renders `GRUEN`, proving the light never re-decides what Task 1 already filtered.

- [ ] **Step 2: Write `DashboardServiceTest`** with the four collaborators mocked: three sites,
      one absent from every map, assert the tile for it exists with `light == GELB`,
      `lastRun == null`, `counts == OpenFindingCounts.none()`. Assert `totals` is the sum over
      **enabled** sites only — a disabled site's stale findings must not inflate the header count
      that decides whether anyone opens the app. Assert `nextFireAt` is the earliest across sites
      and is `null` when `AppSettings.schedulingPaused()` (D41: paused means nothing fires, and a
      countdown to a run that will not start is the worst kind of wrong).

- [ ] **Step 3: Run them and watch them fail.**

- [ ] **Step 4: Implement**, widening `reporting`'s `allowedDependencies` with `"scheduling"`.
      Expect `ModularityTest` to have been the failing test for that line.

- [ ] **Step 5: Run the suite** — `./mvnw test -Pfast`, `ModularityTest` included.

- [ ] **Step 6: Commit.**

```bash
git commit -am "feat(reporting): dashboard read model and the traffic light"
```

---

### Task 4: The dashboard screen

`/` becomes the dashboard, the site list gets its own route, and the grid polls.

**Files:**
- Create: `web/DashboardController.java`, `src/main/resources/templates/uebersicht/index.html`,
  `src/main/resources/templates/fragments/kacheln.html`,
  `src/main/resources/help/uebersicht.md`
- Modify: `web/SiteController.java`, `web/SecurityConfig.java`,
  `src/main/resources/templates/layout.html`, `src/main/resources/messages.properties`,
  `src/main/resources/static/css/app.css`, `src/main/resources/application.properties`
- Test: `web/DashboardControllerTest.java`, `web/EnumLabelsTest.java` (add `TrafficLight`),
  `web/SiteControllerTest.java`, `web/SecurityRulesTest.java`,
  `web/RunReportAcceptanceTest.java` (the three places that assert `get("/")` renders the list)

**Interfaces (produces):**
- `GET /` → `uebersicht/index`, model attribute `uebersicht` (a `DashboardView`).
- `GET /uebersicht/kacheln` → `fragments/kacheln :: kacheln`, the same attribute, for the poll.
- `GET /websites` → `websites/liste`, the list `SiteController.index` renders today.

- [ ] **Step 1: Write `DashboardControllerTest`** against the **rendered body**, not the model —
      p7's findings record a bulk-selection script that never reached the browser while the
      controller test stayed green, and the lesson written down there was that a controller test
      asserting model attributes proves nothing about a screen. Assert: a red tile renders the
      site name and `3 Fehler`; a `GRAU` tile renders neither a finding count nor a next run; the
      grid carries `hx-get="/uebersicht/kacheln"` and `hx-trigger="every 30s"`; the fragment
      response does **not** contain `<nav` (it is a fragment, not a page); no raw enum constant
      (`ROT`, `COMPLETED`, `PULSE`) appears in the body — §13.1, and the reason `TrafficLight`
      joins `EnumLabelsTest` in this task.

- [ ] **Step 2: Run it and watch it fail.**

- [ ] **Step 3: Move the list to `/websites` and add the dashboard controller.** Update the three
      existing tests that `get("/")` — they become `get("/websites")` — and add one asserting `/`
      is now the dashboard. `SecurityRulesTest` keeps its anonymous-redirect case on `/`.

- [ ] **Step 4: Build the two templates.** One tile per site in a CSS grid: the light as a
      coloured status badge, name, the error/warning counts as links into
      `/websites/{id}/befunde` with the severity filter pre-applied, the last run as a link to
      `/laeufe/{id}`, the next run as a relative German time. The header carries the totals, the
      in-flight count, and the capacity line from `SystemCapacity`. A `?` affordance points at the
      new `uebersicht` topic, which explains what each colour means and that `GRAU` is a switch
      somebody threw, not a failure.

- [ ] **Step 5: Add the nav entry** — *Übersicht* before *Websites* — and the message keys. The
      `TrafficLight` labels are `ui.trafficlight.*` per `EnumLabelsTest`'s naming rule.

- [ ] **Step 6: Run the suite** — `./mvnw test -Pfast`, plus `UiMessageKeyTest`, `HelpTopicsTest`
      and `EnumLabelsTest` green.

- [ ] **Step 7: Commit.**

```bash
git commit -am "feat(web): the dashboard answers whether anything is wrong"
```

---

### Task 5: Users — the service and the lockout guard

The rule that matters is D71, and it is a service rule so no screen can route around it.

**Files:**
- Modify: `web/AppUserService.java`, `web/persistence/AppUserRepository.java`
- Create: `web/AppUserSummary.java`, `web/UserValidationException.java`
- Test: `web/AppUserServiceTest.java` (exists; extend), `web/AppUserGuardTest.java`

**Interfaces (produces):**
- `record AppUserSummary(long id, String username, AppRole role, boolean enabled, Instant createdAt)`
- `AppUserService.list() → List<AppUserSummary>`, ordered by username.
- `AppUserService.create(String username, String rawPassword, AppRole role) → long` — existing
  signature, now rejecting a blank username, a duplicate (case-insensitively, over
  `ux_app_user_username`) and a password under 8 characters, each with a message key.
- `AppUserService.setRole(long id, AppRole)`, `setEnabled(long id, boolean)`,
  `setPassword(long id, String rawPassword)`, `delete(long id)`.
- `AppUserService.enabledAdminCount() → long` — the guard's single source of truth.
- `UserValidationException(String messageKey, Object... args)`, handled by the existing
  `WebExceptionHandler` pattern.

- [ ] **Step 1: Write `AppUserGuardTest`.** With exactly one enabled `ADMIN` present:
      `setEnabled(admin, false)` throws; `setRole(admin, USER)` throws; `delete(admin)` throws;
      `setPassword(admin, …)` **succeeds** — changing your own password is not a lockout. Then with
      two enabled admins, all four succeed, and after disabling one the guard protects the other.
      Add the case the constraint would otherwise cover silently: a second enabled admin that is
      `enabled = false` does not count, so one enabled and one disabled admin is still locked.

- [ ] **Step 2: Extend `AppUserServiceTest`** with the validation cases: blank username, duplicate
      username differing only in case, seven-character password, and `list()` ordering.

- [ ] **Step 3: Run them and watch them fail.**

- [ ] **Step 4: Implement.** The guard reads `enabledAdminCount()` inside the same `@Transactional`
      method that writes, so two concurrent demotions cannot both see two admins — the count is
      the read half of a check-then-act and the transaction is what makes it one.

- [ ] **Step 5: Run the suite** — `./mvnw test -Pfast`.

- [ ] **Step 6: Commit.**

```bash
git commit -am "feat(web): user administration with a last-admin guard"
```

---

### Task 6: Users and capacity on the Settings screen

§12's two remaining Settings rows. Capacity is read-only and says so (D65).

**Files:**
- Create: `web/UserController.java`, `web/UserFormModel.java`,
  `src/main/resources/templates/einstellungen/benutzer.html`,
  `src/main/resources/templates/fragments/systemlast.html`
- Modify: `web/SettingsController.java`, `web/SecurityConfig.java`,
  `src/main/resources/templates/einstellungen/index.html`,
  `src/main/resources/messages.properties`
- Test: `web/UserControllerTest.java`, `web/SettingsControllerTest.java`,
  `web/SecurityRulesTest.java`, `web/EnumLabelsTest.java` (add `AppRole`)

**Interfaces (produces):**
- `GET /einstellungen/benutzer` → list plus create form, `ADMIN`.
- `POST /einstellungen/benutzer` → create, then redirect with a flash.
- `POST /einstellungen/benutzer/{id}` → one endpoint taking `aktion` ∈ `{rolle, aktiv, passwort}`,
  because three near-identical routes over one row buy nothing a reviewer would separate.
- `POST /einstellungen/benutzer/{id}/loeschen` → delete.
- `SettingsController.index` gains a `systemlast` attribute (`SystemCapacity`).

- [ ] **Step 1: Write `UserControllerTest`.** Rendered-body assertions again: the list shows the
      German role label and never `ADMIN`; the row for the last enabled admin renders its
      disable, demote and delete controls **absent**, not merely disabled — a control that exists
      and 500s is a worse answer than a control that is not there; a create POST with a duplicate
      username re-renders the form with the field error and the list intact. In
      `SecurityRulesTest`: a `USER` gets 403 on all four paths.

- [ ] **Step 2: Write the `SettingsControllerTest` case** for the capacity panel: it renders the
      configured worker count, the live busy count, and the literal environment-variable name
      `WTH_BROWSER_WORKERS`, plus the §13.4 sentence naming the restart. Assert the panel contains
      no `<input` and no `<form` — D65 in a test, so a later well-meaning change to make it
      editable fails here rather than in production.

- [ ] **Step 3: Run them and watch them fail.**

- [ ] **Step 4: Implement the controller and the two templates.** The delete and disable buttons
      carry §13.4 consequence text inline (*"Diese Person kann sich danach nicht mehr anmelden."*)
      and the delete uses a plain POST with a typed confirmation in Alpine — **never** a JavaScript
      `confirm()`, which blocks the browser automation this project's own tests depend on.

- [ ] **Step 5: Run the suite** — `./mvnw test -Pfast`.

- [ ] **Step 6: Commit.**

```bash
git commit -am "feat(web): users and system capacity in Settings"
```

---

### Task 7: `SetupProbe` — the browser half of guided setup

**The one browser test in this plan.** Everything above and below runs under `-Pfast`.

**Files:**
- Create: `crawler/SetupProbe.java`, `crawler/ProbeEvidence.java`, `crawler/SetupProbeProperties.java`
- Modify: `model/FrameRef.java` (D69), `checks/IframeEmbedCheck.java` (delegate to it),
  `src/main/resources/application.properties`
- Test: `crawler/SetupProbeTest.java` (`@Tag("browser")`, against `FixtureSite`),
  `model/FrameRefTest.java`, `checks/IframeEmbedCheckTest.java` (unchanged assertions)

**Interfaces (produces):**
- `record ProbeEvidence(boolean reachable, String unreachableReason, List<String> pagesVisited,
  List<String> formPages, List<String> mediaPages, List<String> mapPages, Set<String> languages,
  List<String> documentLinks, boolean sitemapFound, boolean secure)` — every list holds
  **normalised absolute URLs**, so the screen can link to what it claims to have found.
- `SetupProbe.probe(SiteContext site) → ProbeEvidence`, blocking, one `BrowserPool.submit` per
  page so a probe never holds a worker across the politeness delay.
- `FrameRef.isMapsEmbed() → boolean`, moved verbatim from `IframeEmbedCheck` (D69).

- [ ] **Step 1: Move the maps predicate** to `FrameRef`, delegate from `IframeEmbedCheck`, and
      run `IframeEmbedCheckTest` — its assertions must not change. `FrameRefTest` adds the two
      cases the move exists to protect: a `/maps/embed/v1/place` path on a non-Google host is a
      maps embed (the fixture's own case), and `https://google.com/search` is not.

- [ ] **Step 2: Write `SetupProbeTest`.** One `@BeforeAll` probe of the fixture — `CLAUDE.md`'s
      rule is crawl once per class — then assert against its single `ProbeEvidence`:
      `formPages` contains `/kontakt.html`; `mediaPages` contains `/medien.html`;
      `mapPages` contains `/kontakt.html`; `languages` contains `de` and `en`;
      `documentLinks` contains `/dateien/handbuch.pdf`; `sitemapFound` is true;
      `pagesVisited` never contains `/geheim/intern.html` (robots, §8) and never contains a `.pdf`
      URL (`UrlAdmission`'s `NOT_NAVIGABLE`); `pagesVisited` has at most `probe-pages` entries and
      starts with the base URL. Add the unreachable case as a second, separate probe against a
      dead port: `reachable` false, `unreachableReason` non-blank, every list empty.

- [ ] **Step 3: Run it and watch it fail** — `./mvnw test -Dtest=SetupProbeTest` (no `-Pfast`;
      this one needs Chromium).

- [ ] **Step 4: Implement.** Candidate selection is the part a reader would otherwise reinvent
      differently — document order is what makes the primary nav come first, and admission at
      depth 0 is what makes a `maxDepth=0` site probeable (D66):

```java
List<String> candidates = home.internalLinks().stream()
        .map(link -> link.target().value())
        .distinct()                                   // document order survives; the nav comes first
        .filter(url -> !url.equals(base.value()))
        .map(UrlNormalizer::normalize).flatMap(Optional::stream)
        .filter(url -> admission.admit(url, 0).admitted())   // depth 0: probe pages are entry points
        .map(NormalizedUrl::value)
        .limit(properties.probePages() - 1)
        .toList();
```

      `sitemapFound` reuses `SiteResourceFetcher.fetchText` over `robots.txt` and
      `/sitemap.xml` — no browser, and it is the signal that flips `SITEMAP_CONSISTENCY`, which
      `SiteService.NOISY_BY_DEFAULT` ships **off**. The deadline is checked between pages, never
      inside a navigation: `PageNavigator` owns its own timeout and interrupting it would leave a
      `BrowserContext` open on a pool thread.

- [ ] **Step 5: Run the full suite** — `./mvnw test`. Record the new total and the added wall time.

- [ ] **Step 6: Commit.**

```bash
git commit -am "feat(crawler): a setup probe that reads what a site contains"
```

---

### Task 8: `SetupProbeService` — background execution and the proposal

The half that turns evidence into something a colleague can tick.

**Files:**
- Create: `runner/SetupProbeService.java`, `runner/ProbeState.java`, `runner/SetupProposal.java`,
  `runner/CheckProposal.java`
- Test: `runner/SetupProposalTest.java`, `runner/SetupProbeServiceTest.java`

**Interfaces (produces):**
- `enum ProbeStatus { LAEUFT, FERTIG, FEHLGESCHLAGEN }`
- `record ProbeState(ProbeStatus status, Instant startedAt, SetupProposal proposal, String error)`
- `record CheckProposal(CheckType type, boolean suggested, String reasonKey, List<String> reasonArgs)`
  — `reasonKey` is a `ui.einrichtung.grund.*` message key and `reasonArgs` its arguments, so
  §13.3's *"Kontaktformular auf /kontakt gefunden"* is one sentence in the properties file and not
  string concatenation in a template (§13.1).
- `record SetupProposal(ProbeEvidence evidence, List<CheckProposal> checks)`
- `SetupProbeService.start(long siteId)` — idempotent while one is `LAEUFT` for that site;
  sweeps entries older than the TTL on every call.
- `SetupProbeService.stateOf(long siteId) → Optional<ProbeState>`
- `SetupProbeService.clear(long siteId)`
- `SetupProposals.of(ProbeEvidence) → List<CheckProposal>` — a pure static, one entry per
  `CheckType`, so the screen renders the full catalog and the probe only decides `suggested` and
  the reason.

The mapping, one row per test in `SetupProposalTest`:

| Evidence | Suggests | Reason shown |
|---|---|---|
| any `formPages` | (none yet — Phase 3 owns form checks) | *Kontaktformular auf {0} gefunden* — shown as information, with no checkbox to tick |
| any `mediaPages` | `MEDIA_PLAYABLE` | *Video oder Audio auf {0} gefunden* |
| any `mapPages` | `IFRAME_EMBED` | *Karten-Einbettung auf {0} gefunden* |
| `languages.size() > 1` | `HREFLANG` | *{0} Sprachfassungen gefunden* |
| any `documentLinks` | `FILE_DOWNLOAD` | *Dokument zum Herunterladen gefunden: {0}* |
| `sitemapFound` | `SITEMAP_CONSISTENCY` | *sitemap.xml gefunden* — the one that flips a `NOISY_BY_DEFAULT` check **on** |
| `secure` | `TLS_CERT`, `MIXED_CONTENT` | *Website wird über HTTPS ausgeliefert* |
| always | `PAGE_STATUS`, `PAGE_UNREACHABLE`, `DEAD_LINK`, `REDIRECT_CHAIN`, `IMAGE_BROKEN` | *Grundprüfung, immer sinnvoll* |
| never suggested | `CONSOLE_ERRORS` | left off with its reason stated: it is the other `NOISY_BY_DEFAULT` check and no probe signal justifies it |

- [ ] **Step 1: Write `SetupProposalTest`** as a table over those rows against hand-built
      `ProbeEvidence` values — no Spring, no browser. Assert every `CheckType` appears exactly
      once in the output, so adding a check to the enum without a proposal rule fails here; assert
      an unreachable evidence proposes the always-on baseline and nothing else, because a probe
      that saw nothing must not silently turn features off.

- [ ] **Step 2: Write `SetupProbeServiceTest`** with `SetupProbe` mocked: `start` twice while the
      first is `LAEUFT` runs the probe once; a probe that throws lands in `FEHLGESCHLAGEN` with
      the message and never in `LAEUFT` forever; `stateOf` on an unknown site is empty; an entry
      older than the TTL is gone after the next `start`; `clear` removes it. Await with a
      bounded poll, never a bare `Thread.sleep` assertion.

- [ ] **Step 3: Run them and watch them fail.**

- [ ] **Step 4: Implement.** One daemon single-thread executor owned by the service and shut down
      in `@PreDestroy`; probes are rare and each one competes for the same four browser workers a
      crawl uses, so a second probe thread would buy contention rather than throughput.

- [ ] **Step 5: Run the suite** — `./mvnw test -Pfast`.

- [ ] **Step 6: Commit.**

```bash
git commit -am "feat(runner): run the setup probe in the background and propose checks"
```

---

### Task 9: The guided setup screen

§13.3's actual deliverable: confirming a proposal instead of authoring a configuration.

**Files:**
- Create: `web/SetupController.java`, `web/SetupForm.java`,
  `src/main/resources/templates/einrichtung/index.html`,
  `src/main/resources/templates/fragments/einrichtungsstand.html`,
  `src/main/resources/help/einrichtung.md`
- Modify: `web/SiteController.java` (create redirects to the wizard), `web/SecurityConfig.java`,
  `src/main/resources/templates/websites/detail.html` (a *Einrichtung erneut vorschlagen* link),
  `src/main/resources/messages.properties`
- Test: `web/SetupControllerTest.java`, `web/SecurityRulesTest.java`

**Interfaces (produces):**
- `GET /websites/{id}/einrichtung` → starts a probe if none is held, renders the shell.
- `GET /websites/{id}/einrichtung/stand` → `fragments/einrichtungsstand :: stand`; while `LAEUFT`
  it carries `hx-trigger="every 2s"`, and when `FERTIG` or `FEHLGESCHLAGEN` **it does not** — the
  poll stops by swapping in markup with no trigger, which is the only way an HTMX poll ends
  without client-side state.
- `POST /websites/{id}/einrichtung` with `SetupForm(List<CheckType> aktiv)` → one
  `SiteService.setCheckEnabled` per `CheckType` in the catalog (enabled if present in `aktiv`,
  disabled if not), `SetupProbeService.clear`, redirect to `/websites/{id}`.
- `POST /websites/{id}/einrichtung/neu` → `clear` then `start`, redirect back.

- [ ] **Step 1: Write `SetupControllerTest`**, rendered-body throughout. While `LAEUFT`: the
      fragment contains `hx-trigger` and the German waiting sentence. When `FERTIG`: it contains
      one checkbox per `CheckType`, the suggested ones checked, each with its reason sentence
      rendered from the message bundle and **no** raw `CheckType` constant anywhere (§13.1); the
      form-found line appears as text with no checkbox beside it. When `FEHLGESCHLAGEN`: the error
      and a *Erneut versuchen* button, and the **Übernehmen** button still present, because a
      colleague whose site was briefly down must still be able to accept the defaults and move on.
      Assert the POST with two types ticked leaves exactly those two enabled and every other
      `CheckType` disabled — including one that was enabled by `SiteService.create`'s seeding, so
      the test proves the form is authoritative rather than additive.

- [ ] **Step 2: Write the `SecurityRulesTest` cases** for D70: a `USER` reaches all four setup
      paths; an anonymous request is redirected to `/anmelden`.

- [ ] **Step 3: Run them and watch them fail.**

- [ ] **Step 4: Implement the controller and both templates.** The shell explains what the probe
      is doing in one sentence and names the pages it is visiting as they arrive. A `?` affordance
      points at the new `einrichtung` topic, which says what the probe does **not** do — it does
      not submit forms, it does not change the pulse set (D68), and it visits at most eight pages.

- [ ] **Step 5: Point site creation at it** — `SiteController.create` redirects to
      `/websites/{id}/einrichtung` instead of `/websites/{id}`, and `SiteControllerTest`'s
      redirect assertion moves with it.

- [ ] **Step 6: Run the suite** — `./mvnw test -Pfast`, plus `UiMessageKeyTest` and
      `HelpTopicsTest`.

- [ ] **Step 7: Commit.**

```bash
git commit -am "feat(web): guided setup proposes a configuration to confirm"
```

---

### Task 10: Acceptance — a site is added, and the dashboard says so

One test that walks the whole plan, and the only place the two features meet.

**Files:**
- Create: `web/DashboardAcceptanceTest.java` (extends `AbstractPostgresTest`, `-Pfast`,
  `SetupProbe` stubbed with a `@MockitoBean` returning a hand-built `ProbeEvidence`)
- Modify: `docs/superpowers/plans/2026-08-26-webtesthelper-p9-dashboard-setup.md`
  (the **Execution findings** section only)

**Why the probe is stubbed here:** Task 7 already proves the probe against real Chromium and the
fixture. Repeating it inside an acceptance test would buy a second ninety-second Chromium sweep to
re-learn a fact one test already knows — the same arithmetic plan 5's findings used to justify
adding no browser test at all.

- [ ] **Step 1: Write the acceptance test** as one method with numbered steps, asserting on the
      rendered bodies:
      1. `POST /websites` as `ADMIN` redirects to `/websites/{id}/einrichtung`.
      2. `GET …/einrichtung/stand` eventually renders the proposal, with
         `SITEMAP_CONSISTENCY` ticked — the check `SiteService.create` seeded **off**, which is the
         single clearest proof that the probe changed the configuration rather than echoing it.
      3. `POST …/einrichtung` with the proposal's suggestions redirects to the site, and
         `SiteService.contextFor` reports exactly those checks enabled.
      4. `GET /` shows the new site's tile as `GELB` — no run has finished — with no finding
         counts.
      5. Seed a `COMPLETED` run and one `UNTRIAGED` `ERROR` finding; `GET /uebersicht/kacheln`
         now shows the tile as `ROT` with `1 Fehler`.
      6. Mute that finding with a reason and an expiry; the tile is `GRUEN` — D62's rule end to
         end, and the one assertion that ties plan 7's mute model to plan 9's screen.
      7. Disable the site; the tile is `GRAU` and carries no counts at all.

- [ ] **Step 2: Run it and watch it fail** — `./mvnw test -Pfast -Dtest=DashboardAcceptanceTest`.

- [ ] **Step 3: Make it pass.** Expect nothing new to be needed; a change required here is a gap
      in an earlier task and belongs in that task's file, not in a patch beside the test.

- [ ] **Step 4: Run the full suite** — `./mvnw test`, browser tests included. Record the total and
      the wall time against plan 8's **782 tests / ~1m27s**.

- [ ] **Step 5: Write the Execution findings section** at the bottom of this file. Everything above
      the section header stays untouched, per `CLAUDE.md`. Record at minimum: the measured test
      count and wall time; whether the probe's page budget of 8 held against a real site if one was
      tried; what the dashboard's five queries actually cost at the seeded site count; and any
      constant in the table above that the runtime contradicted.

- [ ] **Step 6: Commit.**

```bash
git commit -am "test(web): acceptance for guided setup and the dashboard"
```

---

## Deliberately not in this plan

- **Pinning key pages from the probe.** D68. The eight-page ranking would beat the full crawl's
  several-hundred-page one permanently, because `pinKeyPagesAfterFullCrawl` only fires on an empty
  set. The consequence is stated rather than fixed: **a brand-new site's nightly `PULSE` crawls
  nothing until its first `FULL` run pins a set.** That is plan 6's behaviour, unchanged by this
  plan, and the honest repair is a `PULSE` that falls back to the base URL when the pin set is
  empty — a change to plan 6's dispatcher, with its own test, in front of a plan and not inside
  one.
- **Editable concurrency.** D65. Resizing `BrowserPool` at runtime means terminating Chromium
  processes that may hold an open `Page` on a worker thread; the screen shows the values and the
  variable that changes them.
- **SSE anywhere.** D44 withdrew D29's promise for the whole phase and this plan is what it was
  withdrawn for. Both live regions are one indexed query at 30 s.
- **A form-submission check proposed by the probe.** The probe reports the form it found; §7.2's
  interaction checks are Phase 3, and a checkbox that enables nothing is worse than a sentence.
- **Persisted probe results and a "setup complete" flag.** D67.
- **A second locale.** §12 is German-only and every key added here is German-only.
- **`§16`'s application image.** Unchanged from the roadmap: it waits on the SMTP relay question.
