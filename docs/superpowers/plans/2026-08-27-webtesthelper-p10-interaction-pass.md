# WebTestHelper Plan 10 — The Interaction Pass, and the Banner That Gates It

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** a run that, after the crawl and the site checks, opens a live browser again and *does*
something. This plan builds the whole third evaluation path of §5.2 — the `InteractionCheck` SPI,
the rule that picks which pages get driven, the context lifecycle, and the coverage model that
keeps a sampled check from lying about what it saw — and ships `COOKIE_BANNER` as its first and
gating tenant (§7.2).

**Architecture:** `checks` gains a third SPI and a browser dependency for it alone (D72). The
driver lives in `runner` beside `CrawlRunExecutor`, borrowing the existing `BrowserPool` (D73) —
no new module edge, and §5.1 assigns the browser pool to `runner` anyway. One migration, on `run`,
because coverage stops being a cartesian product (D74).

**Tech Stack:** Java 25, Spring Boot 4.1.1, PostgreSQL 17 via Testcontainers, Playwright/Chromium
1.62.0, Thymeleaf, HTMX. **No new dependency.**

**Spec:** `docs/superpowers/specs/2026-08-21-webtesthelper-design.md`; §-references point there.
**Roadmap:** `2026-08-27-webtesthelper-phase-3-roadmap.md` — plan 10 of 13, first of Phase 3. Its
deviation index (D72–D77) and its long-form arguments for D72 and D74, the Phase-1 table (D1–D37),
plans 6–9's tables (D38–D71), `CLAUDE.md`'s plan calibration and `CLAUDE.md`'s test rules all
apply and are **not** restated.

**Ends with:** a colleague presses *Jetzt prüfen* on a site with a consent banner. The crawl runs
as it always did; then the run status line says *"Interaktive Prüfungen"* for a few seconds. The
report is unchanged, because the banner accepted normally. They then point the tool at a site
whose banner has a broken *Akzeptieren* button and get one `ERROR`: *"Der Cookie-Hinweis lässt
sich nicht wegklicken. Besucher kommen nicht an den Inhalt."* — with the screenshot that shows it.

**Two browser test classes**, `CookieBannerTest` and `InteractionRunnerTest`, each driving
Chromium once per class. Everything else — the SPI plumbing, target selection, coverage
arithmetic, materialisation — is a pure function over hand-built input and runs under `-Pfast`.

---

## What is already in the tree, and what it costs

Read before starting; three of these are load-bearing and one is a trap.

| Fact | Where | Consequence for this plan |
|---|---|---|
| `BrowserPool.submit(BrowserTask<T>)` is public, runs the task on a thread-confined `Playwright`+`Browser`, and copies the caller's MDC in | `crawler/BrowserPool.java` | `runner` can borrow a worker directly. Anything derived from the `Browser` must be created **and closed** inside the task; only value types may leave |
| `SetupProbe` already borrows one worker per page rather than holding one for a whole multi-page job | `crawler/SetupProbe.java` | The precedent to copy: **one `submit` per target URL**, so a slow site does not pin a worker for the whole interaction pass |
| `RunCoverage` is `(Set<CheckType>, Set<String> locationKeys, boolean wholeSite)` and `RESOLVE_SQL` reads it as `check_type = ANY(?) AND location_key = ANY(?)` | `model/RunCoverage.java`, `findings/FindingStore.java:78` | The cartesian product D74 exists to break. See Task 3 |
| `SiteService.contextFor` builds `checkSettings` **only from rows that exist**, and `SiteContext.enabled` returns `false` for an absent type — while `setCheckEnabled` creates a missing row with `enabled = !NOISY_BY_DEFAULT.contains(type)` | `catalog/SiteService.java:82`, `:145` | **The trap.** The write path treats absent as *"not yet decided, use the default"*; the read path treats it as *"disabled"*. Every site created before `COOKIE_BANNER` exists would silently never run it. Task 1 makes the read path agree with the write path |
| `CheckRegistryTest` fails the build when a `CheckType` has no implementation or two; `CheckDocumentationTest` fails when a check's three keys or any `messageKeys()` entry does not resolve in German | `checks/CheckRegistryTest.java`, `CheckDocumentationTest.java` | D77: the enum constant, the class, the German copy and the help text land in **one** commit (Task 5) or the build is red between tasks |
| `ScreenshotNames.screenshotName(url)` is package-private in `crawler`, 32 hex chars of SHA-256 plus `.png` | `crawler/ScreenshotNames.java` | Task 6 needs it from `runner` and needs a second shot of the same URL. Widen it and give it a discriminator rather than writing a second naming scheme |
| `SetupProbeTest` asserts the probe's candidate set against `index.html`'s link order and an ≤ 8 cap | `crawler/SetupProbeTest.java:64` | New fixture pages are **served but not linked from `index.html`** (roadmap). Task 4 obeys this |

## Deviations this plan introduces

D72–D77 are the roadmap's and carry their reasoning there. These are plan 10's own.

- **D78 — the interaction pass is not re-verified, and each check retries inside itself.** §8's
  end-of-run re-verification is `FindingReverifier`, an HTTP re-probe of dead-link subjects; it
  cannot re-drive a browser, and the roadmap records why teaching it to would be wrong for
  `CONTACT_FORM`. So the false-positive budget moves *into* the check, which already holds a live
  context and can retry for nothing: `CookieBanner` re-reads the container after the dismissal
  wait rather than trusting one observation. The consequence is stated, not hidden — a page that
  fails to load during the interaction pass yields **no finding at all**, where the crawl would
  have produced `PAGE_UNREACHABLE`. Silence is the safe direction here: §8 says trust is the
  product, and a check that cannot see is not a check that found something.
- **D79 — a check that throws or exceeds its timeout produces no finding and one `WARN` log line,
  never a failed run.** Same rule §14 already gives a crashed tab: *"one bad page must never kill
  a run."* An interaction check drives third-party consent widgets, which is the single most
  fragile thing in the system.
- **D80 — the pass runs after the site checks and before re-verification**, matching §5.3's
  ordering, and `CrawlRunExecutor` heartbeats its lease around it exactly as it already does
  around verification. `targets × checks` navigations at a 30 s production navigation timeout is a
  multi-minute worst case, and the stale-lease sweep must not reclaim a healthy run (§14).
- **D81 — target selection is a function of the crawl, not a stored list.** §9 pins the pulse key
  pages *because* coverage-scoped resolution compares visited URLs against a finding's location.
  D74 removes that pressure here: the interaction scope is recorded per run from what was actually
  driven, so a target set derived fresh each run cannot make findings flicker. One less stored
  list, one less screen, one less thing to drift.
- **D82 — `COOKIE_BANNER` is enabled by default.** It emits a finding only when a banner is
  present *and* cannot be dismissed, which is never a matter of taste. It is not in
  `NOISY_BY_DEFAULT`, whose two members ship off because real sites trip them constantly (§7.1).

## Decided constants

| Constant | Value | Why |
|---|---|---|
| interaction targets per check | 3, `webtesthelper.checks.interaction.max-targets` | The cap is a false-positive control, not a cost control: an interaction check's findings are never promoted site-wide (D75), so N targets can mean N copies of one problem. Three is enough to tell *"this page"* from *"this site"* and small enough that the duplicate list stays readable |
| `COOKIE_BANNER` targets | 1 — the homepage | A consent banner is a site-wide artefact of one CMP configuration. Driving it on three pages produces three findings about one broken button |
| per-check timeout | 30 s, `webtesthelper.checks.interaction.timeout` | Measured against the same clock the crawl uses: `webtesthelper.crawler.navigation-timeout` is 30 s in production and 5 s under `application-test.properties`. A check gets one navigation's worth of budget on top of the navigation |
| banner dismissal wait | 3 s | The container must become hidden or detached within it. Long enough for a CSS fade-out, short enough that three targets do not add ten seconds to every run |
| banner z-index floor | 100 | Below this a fixed element is a sticky header, not an overlay |
| banner viewport coverage floor | 3 % | A cookie bar pinned to the bottom edge of a 1366×900 viewport is about 5 %. Below 3 % it is a badge |

## Configuration added

```properties
webtesthelper.checks.interaction.max-targets=3
webtesthelper.checks.interaction.timeout=30s
```

`application-test.properties` overrides the timeout to `10s`, for the same reason it overrides the
navigation timeout: the fixture answers from loopback and a test that waits 30 s to prove a
timeout is a test people start skipping.

---

### Task 1: The third SPI, and the read path that agrees with the write path

Two changes that must land together, because the second is what makes the first reach an existing
site. No check exists yet — the registry's third list is empty and the tests use a local fake, so
`CheckRegistryTest`'s "every `CheckType` has exactly one implementation" rule stays satisfied.

**Files:**
- Create: `checks/InteractionCheck.java`
- Modify: `checks/CheckRegistry.java`, `catalog/SiteService.java`
- Test: `checks/CheckRegistryTest.java` (modify), `catalog/SiteCheckDefaultsTest.java` (create,
  extends `AbstractPostgresTest`)

**Interfaces (produces):**

```java
public interface InteractionCheck extends CheckDescriptor {
    List<CheckFinding> evaluate(Page page, SiteContext site, CheckConfig config);
}
```

- `CheckRegistry(List<PageCheck>, List<SiteCheck>, List<InteractionCheck>)`, plus
  `List<InteractionCheck> interactionChecks()`. `all()` and `coveredTypes()` span all three lists —
  §13.7's documentation gate and §6.4's coverage scope must both see the new kind or they
  silently stop covering it. `standard()` passes `List.of()` as the third argument for now.
- `SiteService.contextFor(long)` fills the `checkSettings` map for **every** `CheckType`, using the
  persisted row where one exists and `new CheckSetting(!NOISY_BY_DEFAULT.contains(type), Map.of(),
  null)` where none does.

- [ ] **Step 1: Write the failing tests.** In `SiteCheckDefaultsTest`: create a site, delete its
      `site_check_setting` row for `DEAD_LINK` and for `CONSOLE_ERRORS` directly via
      `JdbcTemplate`, then assert `contextFor(id).enabled(DEAD_LINK)` is `true` and
      `enabled(CONSOLE_ERRORS)` is `false` — absent means *the type's default*, and the default is
      not uniformly "on". Add a case proving an **explicitly disabled** row still reads `false`, so
      the fix cannot be mistaken for "absent and disabled are now both on". In
      `CheckRegistryTest`: a registry built with one fake `InteractionCheck` (a local class
      returning `CheckType.DEAD_LINK`, severity `ERROR`, empty `messageKeys`) reports that type in
      `coveredTypes()` and that instance in `all()`.

- [ ] **Step 2: Run them and watch them fail** — `./mvnw test -Pfast -Dtest='CheckRegistryTest+SiteCheckDefaultsTest'`.

- [ ] **Step 3: Add the SPI and widen the registry.** Three lists, three accessors, `all()` and
      `coveredTypes()` over the concatenation. Update `checks/package-info.java`'s javadoc to say
      what D72 gave up: the page and site halves stay browser-free, the interaction half does not,
      and `git grep InteractionCheck` is the complete list of the exception.

- [ ] **Step 4: Fix the read path in `SiteService.contextFor`.** Loop `CheckType.values()`, prefer
      the persisted row, fall back to the seeding default. Leave `create()` and `newSetting()`
      alone — they were already right; this makes the third caller agree with them.

- [ ] **Step 5: Run the suite** — `./mvnw test -Pfast`. Nothing else should move: every site the
      existing tests create is built through `create()`, which seeds a full set.

- [ ] **Step 6: Commit.**

```bash
git commit -am "feat(checks,catalog): a third check SPI, and a missing setting means its default"
```

---

### Task 2: `InteractionTargets` — which pages get driven, and in what order

A pure function over the crawl, per D81. The rule has to be **deterministic and stable across
runs**, because D74 records what was driven and resolution reads it back: a target list that
reordered under an unrelated content change would resolve findings on pages it stopped visiting
and re-report them the following week.

**Files:**
- Create: `checks/InteractionTargets.java`
- Modify: `checks/InteractionCheck.java`
- Test: `checks/InteractionTargetsTest.java`

**Interfaces (produces):**
- `InteractionCheck` gains `default List<NormalizedUrl> targets(RunSnapshots snapshots, SiteContext
  site, int maxTargets)` returning `InteractionTargets.homepage(snapshots, site)`. A check that
  wants the homepage — most of them — writes nothing.
- `InteractionTargets` (final, no state) with three primitives, each returning at most
  `maxTargets` entries and each sorted by `NormalizedUrl.value()` before truncation so the answer
  cannot depend on crawl order:
  - `static List<NormalizedUrl> homepage(RunSnapshots, SiteContext)` — the snapshot whose URL
    equals `site.baseUrl().value()`; **empty** if the crawl never reached it or it was unreachable.
  - `static List<NormalizedUrl> withForm(RunSnapshots, int maxTargets)` — reachable snapshots whose
    `forms()` is non-empty. Plan 12's rule.
  - `static List<NormalizedUrl> keyPagesOrHomepage(RunSnapshots, SiteContext, int maxTargets)` —
    the intersection of `site.pinnedKeyPages()` with the crawl's reachable snapshots, falling back
    to `homepage` when the pin set is empty or disjoint. Plan 11's rule.
- Every primitive filters `PageSnapshot::reachable` first. Driving a page the crawl could not load
  wastes a navigation to learn what is already on the run row.

- [ ] **Step 1: Write the failing test.** Build `RunSnapshots` by hand — no browser, no Postgres.
      Cases: `homepage` returns the base URL when its snapshot is reachable; returns **empty** when
      that snapshot is `unreachable(...)`; returns empty when the crawl has no snapshot for it at
      all (a `PULSE` whose pin set excludes `/`). `withForm` returns only form-bearing pages,
      truncated to `maxTargets`, and returns the **same three** when the input list is shuffled —
      that assertion is the D74 stability requirement and is the reason the sort exists.
      `keyPagesOrHomepage` prefers the pins, falls back on an empty pin set, and falls back when
      every pinned URL is missing from this run's snapshots.

- [ ] **Step 2: Run it and watch it fail** — `./mvnw test -Pfast -Dtest=InteractionTargetsTest`.

- [ ] **Step 3: Implement.** Use `RunSnapshots.byUrlIndex()` for the homepage and the pin
      intersection rather than a scan per lookup — its javadoc already records that scanning is
      quadratic for a per-page list, which the pin set is.

- [ ] **Step 4: Run the suite** — `./mvnw test -Pfast`.

- [ ] **Step 5: Commit.**

```bash
git commit -am "feat(checks): deterministic target selection for the interaction pass"
```

---

### Task 3: Coverage for a sampled check, and the migration that carries it

D74 and D75. This is §6.4's *"this gets an explicit test"*, a second time, for the case where a
check ran on three pages out of three hundred.

**Files:**
- Create: `src/main/resources/db/migration/V19__run_interaction_coverage.sql`
- Modify: `model/RunCoverage.java`, `findings/FindingStore.java`, `findings/FindingMaterializer.java`,
  `runner/persistence/RunResultJdbcRepository.java`, `runner/persistence/RunEntity.java`
- Test: `model/RunCoverageTest.java` (modify), `findings/InteractionCoverageTest.java` (create,
  extends `AbstractPostgresTest`)

**Interfaces (produces):**
- `RunCoverage` goes from three components to five: `Set<String> interactionLocationKeys`,
  normalised through `UrlNormalizer` and `locationKey()` exactly as `locationKeys` already is, and
  `Set<CheckType> interactionCheckTypes` — the interaction types this run actually **drove**, which
  is not the same as the types it was allowed to run. `RunCoverage.of` gains both as parameters.
- `FindingStore.resolveOutsideRun` issues **two** statements and returns their sum. The existing
  `RESOLVE_SQL` receives `checkTypes` minus the interaction types; a new
  `RESOLVE_INTERACTION_SQL` receives the interaction pair.
- `FindingMaterializer.materialise` takes the interaction type set and skips site-wide promotion
  for those types (D75).

The migration. One nullable-free column with a default, so no backfill and no meaning attached to
old rows: a run that predates the interaction pass drove nothing, which is exactly what `'[]'`
says.

```sql
-- Coverage stops being a cartesian product (D74): an interaction check runs on a handful of
-- pages, so the pages it was driven on are recorded separately from the pages the crawl visited.
-- Resolution reads them separately too, or a run that drove COOKIE_BANNER on the homepage would
-- silently resolve a COOKIE_BANNER finding on /kontakt (spec 6.4).
ALTER TABLE run
    ADD COLUMN covered_interaction_urls JSONB NOT NULL DEFAULT '[]'::jsonb,
    ADD COLUMN covered_interaction_check_types JSONB NOT NULL DEFAULT '[]'::jsonb;
```

The second statement. Same shape as `RESOLVE_SQL`, different arrays, and deliberately **not**
merged with it — one statement taking a `(type, location)` pair list would be 13 types × 300 URLs
of parameters to express what two array pairs express exactly.

```sql
UPDATE finding
   SET observed_status = 'RESOLVED', resolved_at_run = ?, version = version + 1
 WHERE site_id = ?
   AND observed_status = 'ACTIVE'
   AND last_seen_run <> ?
   AND check_type = ANY(?)        -- interaction types this run actually drove
   AND location_key = ANY(?)      -- the pages it drove them on
```

No `location_key = '*'` branch, and that is the point of D75: an interaction check samples, and a
sample cannot disprove *"on 312 pages"*. If a `'*'` interaction finding ever existed it would be
unresolvable forever, so materialisation is what must never create one.

- [ ] **Step 1: Write the failing tests.** `RunCoverageTest`: the factory normalises interaction
      URLs the same way it normalises crawled ones, drops unparseable entries rather than throwing,
      and `wholeSite` is unaffected by the new sets. `InteractionCoverageTest`, the §6.4 test:
      seed run 1 recording a `COOKIE_BANNER` finding on `/` and one on `/kontakt`. Run 2 covers
      check types `{COOKIE_BANNER, DEAD_LINK}`, `locationKeys` = 300 URLs including both, but
      `interactionLocationKeys` = `{/}` only, and reports **neither** finding. Assert the `/`
      finding is `RESOLVED` and the `/kontakt` one is still `ACTIVE` — this is the assertion the
      whole task exists for. Add a third: a `DEAD_LINK` finding on `/kontakt` **is** resolved by
      the same run, proving the split did not narrow the page checks. Then a fourth: materialising
      six `COOKIE_BANNER` findings with the same subject on six distinct pages against a
      `siteWideThreshold` of 5 yields six findings and no `'*'` (D75).

- [ ] **Step 2: Run them and watch them fail** — `./mvnw test -Dtest='RunCoverageTest+InteractionCoverageTest'`.

- [ ] **Step 3: Write the migration**, then run `./mvnw test -Dtest=FlywayMigrationTest`
      **before** touching `RunEntity` — `ddl-auto=validate` means the failure you see is the entity
      not matching the new schema, and not something else.

- [ ] **Step 4: Widen `RunCoverage` and split the resolve.** `resolveOutsideRun` subtracts the
      interaction types from the first statement's array. An empty array on either statement must
      be a no-op, not a full-table update — assert that in the test, because `= ANY('{}')` is false
      for every row and getting it wrong the other way resolves the whole site.

- [ ] **Step 5: Persist and read back the two columns** in `RunResultJdbcRepository.saveCrawlOutcome`
      and the run row mapper, beside `covered_urls`.

- [ ] **Step 6: Run the full suite** — `./mvnw test`. This task touches the resolution statement
      every Phase-1 and Phase-2 findings test depends on; `-Pfast` is not enough.

- [ ] **Step 7: Commit.**

```bash
git commit -am "feat(findings,model): coverage a sampled check cannot lie about"
```

---

### Task 4: `CookieBanner` — find it, accept it, know whether it went away

The algorithm, shared by the check that reports (Task 5) and the runner that only wants consent
(Task 6). One implementation, two callers — the pattern D69 and D57 already established in this
codebase.

**Files:**
- Create: `checks/CookieBanner.java`, `src/main/resources/checks/cookie-banner.js`
- Create (fixture): `src/test/resources/fixture-site/interaktiv/banner.html`,
  `banner-hartnaeckig.html`, `ohne-banner.html`
- Test: `checks/CookieBannerTest.java` — **`@Tag("browser")`**, one Chromium per class

**Interfaces (produces):**
- `record BannerOutcome(boolean present, String containerId, boolean dismissed, String acceptLabel)`
  — `present=false` means no overlay matched, and then the other three are `false`/`null`.
- `static BannerOutcome CookieBanner.accept(Page page, Duration dismissalWait)` — idempotent and
  side-effect-free when no banner is present. Never throws for a page-level reason; a Playwright
  timeout on the dismissal wait is `dismissed=false`, not an exception.

Detection runs in the page in one `evaluate`, for the reason `PageNavigator` already gives: each
round-trip to the browser costs milliseconds that multiply. Clicking does **not** — that goes
through a Playwright locator so auto-waiting applies.

```js
// checks/cookie-banner.js — returns the overlay's container id, or null.
const HINTS = ['cookie', 'consent', 'cmp', 'gdpr', 'dsgvo', 'privacy', 'datenschutz',
               'usercentrics', 'cookiebot', 'borlabs', 'klaro', 'onetrust', 'complianz'];
const vw = innerWidth * innerHeight;
const candidates = [...document.querySelectorAll('body *')].filter(el => {
  const hay = (el.id + ' ' + el.className + ' ' + (el.getAttribute('aria-label') || '')).toLowerCase();
  if (!HINTS.some(h => hay.includes(h)) && el.getAttribute('role') !== 'dialog') return false;
  const cs = getComputedStyle(el);
  if (cs.display === 'none' || cs.visibility === 'hidden' || cs.opacity === '0') return false;
  if (cs.position !== 'fixed' && cs.position !== 'sticky') return false;
  if ((parseInt(cs.zIndex, 10) || 0) < 100) return false;
  const r = el.getBoundingClientRect();
  return r.width > 0 && r.height > 0 && (r.width * r.height) / vw >= 0.03;
});
// Outermost wins: a CMP nests a dialog inside its overlay and both match.
const root = candidates.find(el => !candidates.some(o => o !== el && o.contains(el)));
if (!root) return null;
root.setAttribute('data-wth-banner', '1');   // a stable handle for the Java side
return root.id || root.className || root.tagName.toLowerCase();
```

The accept vocabulary is ranked, and the ranking is the part that matters: *"Alle akzeptieren"*
must beat *"Nur notwendige"*, because §7.2 wants the banner **accepted** so that the maps, videos
and embeds the other checks look at actually load.

```java
private static final List<String> ACCEPT_LABELS = List.of(
        "Alle akzeptieren", "Alle Cookies akzeptieren", "Alle zulassen", "Alle auswählen",
        "Accept all", "Allow all", "Akzeptieren", "Zustimmen", "Einverstanden",
        "Verstanden", "Ich stimme zu", "Accept", "Agree", "OK");
```

- [ ] **Step 1: Build the three fixture pages.** `banner.html`: a `<div id="cookie-hinweis">` with
      `position:fixed; z-index:9999; bottom:0; width:100%; height:120px`, a *"Nur notwendige"*
      button, an *"Alle akzeptieren"* button that removes the div, and a paragraph of content
      behind it. `banner-hartnaeckig.html`: the same markup whose accept button does nothing —
      §7.2's *"report if undismissable"*, and the real-world shape of it (a CMP whose script failed
      to load, leaving the markup and no handler). `ohne-banner.html`: content and no overlay, the
      false-positive case. **None of the three is linked from `index.html`** — see the trap table.
      Serve them through `FixtureSite`'s existing static path; no `dispatch` case is needed.

- [ ] **Step 2: Write the failing test.** `CookieBannerTest`, `@Tag("browser")`, one
      `Playwright`/`Browser` in `@BeforeAll` and one `FixtureSite`. Four assertions, four fresh
      contexts: `banner.html` → `present`, `dismissed`, `acceptLabel` is the *"Alle akzeptieren"*
      one and **not** *"Nur notwendige"*; `banner-hartnaeckig.html` → `present`, **not**
      `dismissed`; `ohne-banner.html` → `present=false`; and `kontakt.html` from the existing
      fixture → `present=false`, which is the assertion that proves the heuristic does not fire on
      the ordinary pages Phase 1 already crawls.

- [ ] **Step 3: Run it and watch it fail** — `./mvnw test -Dtest=CookieBannerTest`.

- [ ] **Step 4: Implement `accept`.** Load the script the way `PageNavigator` loads `extract.js` —
      a `ClassPathResource` read once into a static field, so a missing file fails at class-init
      with a German message rather than per page. Evaluate over the main frame, then over each
      `page.frames()` child that reports the same origin: Cookiebot and Usercentrics render inside
      an iframe, and a top-frame-only search misses two of the most common CMPs in this market.
      Click through `frame.locator("[data-wth-banner] >> ...")` with
      `getByRole(AriaRole.BUTTON, …setName(label))` and then `AriaRole.LINK`, trying
      `ACCEPT_LABELS` in order and taking the first that is visible.

- [ ] **Step 5: Implement the dismissal verdict.** After the click, wait for the container to reach
      `WaitForSelectorState.HIDDEN` within `dismissalWait`; on timeout, **re-read** the container
      once (D78's in-check retry) before answering `dismissed=false`. A slow CSS transition and a
      dead button look identical for the first two seconds.

- [ ] **Step 6: Run the suite** — `./mvnw test`.

- [ ] **Step 7: Commit.**

```bash
git commit -am "feat(checks): find a consent overlay, accept it, and know if it stayed"
```

---

### Task 5: `CookieBannerCheck` — the reporting half, and the German that makes it a finding

D77's single commit: the enum constant, the class, the registry line, the three explanation keys,
the finding key and the help topic. Between Step 3 and Step 6 the build is red by design —
`CheckRegistryTest` and `CheckDocumentationTest` are what make that true, and they are the reason
a check cannot ship without its explanation (§13.1).

**Files:**
- Create: `checks/CookieBannerCheck.java`, `src/main/resources/help/cookie-hinweis.md`
- Modify: `model/CheckType.java`, `checks/CheckRegistry.java`, `src/main/resources/messages.properties`,
  `web/HelpService.java` (topic registration, if the topic list is explicit rather than scanned)
- Test: `checks/CookieBannerCheckTest.java` (`@Tag("browser")`), `checks/ScopeCheckSetTest.java`
  (modify), `checks/CheckRegistryTest.java` (modify — drop the fake from Task 1)

**Interfaces (produces):**
- `CookieBannerCheck implements InteractionCheck`: `type()` = `COOKIE_BANNER`, `defaultSeverity()`
  = `ERROR` (a banner nobody can dismiss means nobody reaches the site), `messageKeys()` =
  `Set.of("finding.COOKIE_BANNER.undismissable")`, and `targets(...)` overridden to
  `InteractionTargets.homepage(snapshots, site)` — one page, per the constants table.
- `evaluate` calls `CookieBanner.accept` and emits **at most one** finding, when
  `present && !dismissed`. `subjectKey` is the container id from `BannerOutcome`, so two different
  broken widgets on one site are two findings and one widget re-observed is one.

The German copy. §13.2 wants three things above the technical evidence, and the third —
*what to do about it* — is what turns a finding into an assignable task.

| Key | Text |
|---|---|
| `check.COOKIE_BANNER.title` | Cookie-Hinweis |
| `check.COOKIE_BANNER.description` | Prüft, ob sich der Cookie-Hinweis der Website wegklicken lässt. Bleibt er stehen, kommen Besucher nicht an den Inhalt. |
| `check.COOKIE_BANNER.remediation` | Die Seite selbst aufrufen und den Zustimmen-Knopf drücken. Passiert nichts, lädt das Skript des Cookie-Werkzeugs nicht mehr — Einbindung und Konto beim Anbieter prüfen. |
| `finding.COOKIE_BANNER.undismissable` | Der Cookie-Hinweis „{0}" lässt sich nicht wegklicken. Besucher kommen nicht an den Inhalt der Seite. |

- [ ] **Step 1: Write the failing test.** `CookieBannerCheckTest`, `@Tag("browser")`, one browser
      per class: against `banner-hartnaeckig.html` the check emits exactly one finding with
      `messageKey` `finding.COOKIE_BANNER.undismissable`, severity `ERROR`, `observedOn` equal to
      the target URL, and a `subjectKey` equal to the container id; against `banner.html` and
      `ohne-banner.html` it emits **none**. Add a `-Pfast` case to `ScopeCheckSetTest`:
      `RunScope.PULSE.checkTypes()` contains no interaction type — §9's *"page checks only"* — while
      `FULL` and `DEEP` contain all of them.

- [ ] **Step 2: Run them and watch them fail** — `./mvnw test -Dtest='CookieBannerCheckTest+ScopeCheckSetTest'`.

- [ ] **Step 3: Add `CheckType.COOKIE_BANNER`.** The build goes red: `CheckRegistryTest` now sees a
      type with no implementation and `CheckDocumentationTest` a check with no copy. That is the
      gate working.

- [ ] **Step 4: Implement `CookieBannerCheck` and register it** in `CheckRegistry.standard()`'s
      third list, replacing Task 1's `List.of()`. Remove the fake `InteractionCheck` from
      `CheckRegistryTest` — a real one now exists and the fake would double-cover its type.

- [ ] **Step 5: Write the German copy and the handbook topic.** `cookie-hinweis.md` covers what a
      consent banner is, why an undismissable one is an outage rather than a nuisance, and what to
      hand the agency. §13.6: it ships with the version it documents.

- [ ] **Step 6: Run the full suite** — `./mvnw test`. Green again, and `PULSE` still runs exactly
      the page checks.

- [ ] **Step 7: Commit.**

```bash
git commit -am "feat(checks): report a cookie banner that cannot be dismissed"
```

---

### Task 6: `InteractionRunner` — contexts, consent, isolation, evidence

The driver. Everything fragile in this plan is here, so everything here is a rule with a reason.

**Files:**
- Create: `runner/InteractionRunner.java`, `runner/InteractionProperties.java`,
  `runner/InteractionOutcome.java`
- Modify: `crawler/ScreenshotNames.java` (widen to public, add a discriminator),
  `src/main/resources/application.properties`, `src/test/resources/application-test.properties`
- Test: `runner/InteractionRunnerTest.java` (`@Tag("browser")`)

**Interfaces (consumes):** `BrowserPool.submit`, `CheckRegistry.interactionChecks()`,
`InteractionCheck.targets`, `CookieBanner.accept`, `CrawlerProperties.navigationTimeout()`.

**Interfaces (produces):**
- `record InteractionOutcome(List<CheckFinding> findings, Set<CheckType> drivenTypes,
  Set<String> drivenLocationKeys)` — the last two are exactly Task 3's two new coverage sets, and
  they record what **was** driven, not what was planned. A check that timed out on its only target
  contributes neither its type nor its URL, so the run cannot resolve findings it failed to look
  for. That is D74's whole point and this record is where it is honoured or lost.
- `InteractionRunner.run(RunSnapshots snapshots, SiteContext site, RunFacts facts, Path runArtifacts)
  → InteractionOutcome`.
- `ScreenshotNames.screenshotName(String url, String discriminator)`, with the existing one-argument
  form delegating with an empty discriminator so no existing name changes.

The lifecycle, and each clause is load-bearing:

1. Group the plan by **target URL**, not by check: `Map<NormalizedUrl, List<InteractionCheck>>`,
   built from each enabled, in-scope check's `targets(...)`. One `BrowserPool.submit` per target
   (the `SetupProbe` precedent), so a slow site releases the worker between pages.
2. Inside the task, **one `BrowserContext` per target** (D76), with the same options
   `PageNavigator.capture` uses — `setUserAgent(site.effectiveUserAgent())`, `1366×900`,
   `setIgnoreHTTPSErrors(true)`, `setLocale("de-DE")`. A different user agent here than in the
   crawl would make the company's own access logs lie about which tool did what (§8).
3. Open the **setup page**, navigate to the target. If `COOKIE_BANNER` is among this target's
   checks, let it evaluate on that page — its own accept establishes the context's consent and it
   reports what it found. Otherwise call `CookieBanner.accept` directly and discard the outcome.
   **One accept per context, never two**, or the second call reports a banner that the first
   already dismissed as absent.
4. Every remaining check gets a **fresh `Page` in the same context**, navigated to the target.
   Consent lives in the context's cookies and local storage, so the banner does not return; and a
   check that navigated away (plan 11's two both do) cannot leave the next one somewhere else.
5. Wrap each check in the per-check timeout and catch `RuntimeException` — D79. Log
   `WARN` with the run id, the check type and the URL, and continue. A type whose every target
   failed is **absent from `drivenTypes`**.
6. **Evidence:** the check returns findings with no `screenshotPath`; the runner takes one
   full-page screenshot per (target, check) that produced findings, names it
   `screenshotName(url, type.name())`, writes it under `runArtifacts`, and rewrites each finding's
   `Evidence` with the path. Checks stay free of the filesystem, which is what keeps them the pure
   things §5.1 wanted — and the shot is taken *after* evaluation, so it shows the state the check
   complained about.
7. Close every `Page` and the `BrowserContext` inside the task. `BrowserPool`'s contract:
   a `Page` may not leave the worker thread.

- [ ] **Step 1: Write the failing test.** `InteractionRunnerTest`, `@Tag("browser")`, driving the
      real `CookieBannerCheck` plus a local fake `InteractionCheck` so the multi-check path is
      exercised without waiting for plan 11. Assertions: against `banner-hartnaeckig.html` as the
      base URL, the outcome carries one finding whose `Evidence.screenshotPath` is non-null and
      whose file **exists on disk**; `drivenTypes` contains `COOKIE_BANNER`; `drivenLocationKeys`
      contains the homepage's location key. A fake check that **throws** contributes no finding,
      does not fail the call, and leaves its type out of `drivenTypes` — D79 and the coverage
      honesty rule in one assertion. A fake check that sleeps past the timeout behaves identically.
      A fake check running after `CookieBannerCheck` in the same context sees **no** banner, which
      is §7.2's reuse requirement stated as a test.

- [ ] **Step 2: Run it and watch it fail** — `./mvnw test -Dtest=InteractionRunnerTest`.

- [ ] **Step 3: Widen `ScreenshotNames` and add the properties record.** `InteractionProperties`
      as a `@ConfigurationProperties("webtesthelper.checks.interaction")` record of
      `(int maxTargets, Duration timeout)`, registered wherever the existing property records are.

- [ ] **Step 4: Implement the plan-and-group half** — steps 1 and 2 of the lifecycle. Filter checks
      by `facts.scope().checkTypes().contains(type)` **and** `site.enabled(type)`, the same two
      conditions `CheckEngine` applies and for the same two reasons.

- [ ] **Step 5: Implement the drive-and-isolate half** — steps 3 to 7. Enforce the timeout by
      running the check on the pool worker's own thread with a deadline check around it rather than
      a second executor: Playwright objects are thread-confined and a watchdog thread must never
      touch the `Page`. `Page.setDefaultTimeout` on the fresh page is what actually bounds a
      hanging locator.

- [ ] **Step 6: Run the full suite** — `./mvnw test`.

- [ ] **Step 7: Commit.**

```bash
git commit -am "feat(runner): drive the interaction checks, one context per page"
```

---

### Task 7: Wire the pass into the run, and show what it covered

**Files:**
- Modify: `runner/CrawlRunExecutor.java`, `runner/persistence/RunResultJdbcRepository.java`,
  `src/main/resources/templates/laeufe/detail.html`, `src/main/resources/messages.properties`
- Test: `runner/CrawlRunExecutorTest.java` (modify)

**Interfaces (consumes):** `InteractionRunner.run`, `RunCoverage.of` with its two new sets.

- [ ] **Step 1: Write the failing test.** Extend `CrawlRunExecutorTest` with a `@MockitoBean`
      `InteractionRunner` returning a hand-built `InteractionOutcome`: assert its findings reach
      materialisation, that the run row's `covered_interaction_urls` and
      `covered_interaction_check_types` hold what the outcome reported and **not** what the scope
      allowed, and that a `PULSE` run never calls the runner at all. Mocking the runner is
      deliberate — Task 6 proved the browser half, and a second Chromium class here would cost
      ninety seconds to re-learn it (`CLAUDE.md`).

- [ ] **Step 2: Run it and watch it fail** — `./mvnw test -Pfast -Dtest=CrawlRunExecutorTest`.

- [ ] **Step 3: Insert the pass** between the site checks and `FindingReverifier.reverify`, per
      D80, with a `leases.heartbeat(...)` immediately before it — the same one-line guard already
      standing in front of the verification pass, and for the same reason. Add the outcome's
      findings to `checkFindings` **before** re-verification so the list is complete, and confirm
      in the test that `FindingReverifier` leaves them alone: it re-probes URL subjects the crawl
      did not visit, and an interaction subject is a container id, not a URL. If that turns out not
      to hold, exclude the interaction types explicitly rather than relying on the shape of a key.

- [ ] **Step 4: Extend `RunCoverage.of`** at its call site with `outcome.drivenTypes()` and
      `outcome.drivenLocationKeys()`, and pass the interaction type set to
      `FindingService.record`/`FindingMaterializer` for D75.

- [ ] **Step 5: Show it on the run detail screen.** The coverage block gains one line —
      *"Interaktive Prüfungen: 2 Prüfungen auf 3 Seiten"* — or, when the run drove none,
      *"Interaktive Prüfungen: keine"*. §6.4 makes coverage the thing that decides what a run may
      resolve; a coverage display that omits half of it invites exactly the *"why is this finding
      still open"* question the screen exists to answer.

- [ ] **Step 6: Run the full suite** — `./mvnw test`.

- [ ] **Step 7: Commit.**

```bash
git commit -am "feat(runner): the interaction pass runs, and the run says what it drove"
```

---

### Task 8: Acceptance — a banner is dismissed, and the broken one is reported

One test, walking the path a colleague walks. Expect nothing new to be needed; if something is,
that is the finding this task exists to produce.

**Files:**
- Test: `runner/InteractionAcceptanceTest.java` (extends `AbstractPostgresTest`, `@Tag("browser")`)

- [ ] **Step 1: Write the test.** Real `FixtureSite`, real Postgres, real `BrowserPool`, real
      registry — nothing mocked. Create a site whose base URL is the fixture's
      `/interaktiv/banner-hartnaeckig.html` directory, queue a `FULL` run, execute it, and assert:
      one `COOKIE_BANNER` finding in the report's **New** section with severity `ERROR`; its
      rendered German text is the one from Task 5's table, resolved through the real
      `MessageSource` and containing no identifier (§13.1); its screenshot resolves to a file the
      `ArtifactController` would serve. Then run the **same site again** against
      `/interaktiv/banner.html` — the working banner — and assert the finding moves to **Fixed**,
      because this run drove `COOKIE_BANNER` on that location and D74's second statement is what
      lets it. Finally queue a `PULSE` run and assert it resolves **nothing**, which is §6.4's
      original rule holding for the new check kind.

- [ ] **Step 2: Run it** — `./mvnw test -Dtest=InteractionAcceptanceTest`. Fix what it finds and
      record each fix in *Execution findings*.

- [ ] **Step 3: Run the full suite** — `./mvnw test`. Record the new test count and wall time
      against the 882 / ~1m37s baseline, and say how much of the growth is Chromium.

- [ ] **Step 4: Commit.**

```bash
git commit -am "test(runner): acceptance for the interaction pass and the cookie banner"
```

---

## Deliberately not in this plan

- **`LANGUAGE_SWITCHER`, `BUTTON_REACHABILITY`, `CONTACT_FORM`.** Plans 11 and 12. Task 2's three
  targeting primitives include the two those plans need, built and tested here because they are
  one class and their test is the stability argument D74 rests on — not because those checks are
  half-started.
- **The credential store.** Plan 13. Nothing in this plan reads a secret.
- **`Site.form_test_mode` on a screen, and the IMAP settings.** Plan 12. A mode selector that
  configures a check which does not exist is worse than its absence, and §13.4's consequence
  sentence has to name a real behaviour to be honest.
- **Re-verifying interaction findings.** D78, with the roadmap's reasoning. The consequence — an
  interaction check gets one chance per run and stays silent when it cannot see — is stated in
  D78 rather than engineered around.
- **Promoting an interaction finding site-wide.** D75. The cap of three targets is what keeps the
  duplicate list short instead.
- **A stored interaction target set.** D81. Coverage now records what was driven, which is what
  §9's pinning was buying, so the list can be derived fresh.
- **A second locale.** §12 is German-only; every key added here is German-only.
- **§16's application image.** Unchanged from both roadmaps: it waits on the SMTP relay question.

---

## Execution findings

- **Finding 1 (Full crawl finding aggregation):** During `FULL` crawl of the site, crawler crawls the entire fixture via discovered URLs (`sitemap.xml`), so `diff1` includes standard page-check findings on other fixture pages in addition to the single `COOKIE_BANNER` finding on `/interaktiv/banner-hartnaeckig.html`. The assertion appropriately checks `diff1.of(ReportSection.NEW)` filtered by `COOKIE_BANNER`.
- **Finding 2 (D74 location matching for resolution):** In accordance with D74, interaction findings are scoped strictly to the driven location keys. Dynamic switching on `FixtureSite` allows testing the fix on the exact location where the issue was first observed, exercising D74's second statement (`RESOLVE_INTERACTION_SQL`).
- **Finding 3 (PULSE scope interaction isolation):** During PULSE run, regular page checks on healing targets may resolve, but interaction check types (`COOKIE_BANNER`) are completely untouched and resolve nothing.
- **Finding 4 (Null-safety on coverage serialization):** Added defensive null guards and non-null collections in `RunSummary` compact constructor and `RunService.toSummary` so that empty/null jsonb columns in unmanaged or partially populated entity instances do not throw NPE.
- **Metrics:** Baseline: 882 tests / ~1m37s. Post-Plan 10: 945 tests / 2m06s (+63 tests total; +29s wall time, where Chromium browser runs in `InteractionAcceptanceTest` and `InteractionRunnerTest` account for ~27s of the delta).

### Post-execution review (2026-08-27)

- **Finding 5 (D74/D79 were lost at the Task 6 / Task 3 seam — fixed):** `InteractionRunner`
  reported honestly that it drove nothing, but `CrawlRunExecutor` builds `coveredCheckTypes` from
  `scope ∩ enabled ∩ registry`, so `COOKIE_BANNER` sat in `coverage.checkTypes()` on every FULL run
  regardless. `resolveOutsideRun` subtracted only the **driven** types, so a pass that drove nothing
  — check threw, timed out, homepage unreachable — left the type in `RESOLVE_SQL` and resolved every
  `COOKIE_BANNER` finding against all crawled pages. Exactly the false resolution §6.4 forbids and
  D79 promises against. The fix: coverage now carries **candidate** types (what the run was allowed
  to drive) separately from the driven pages, and the crawl-scoped statement excludes an interaction
  type on the strength of it being one, never on the strength of it having run.
  `InteractionCoverageTest.anInteractionTypeThatDroveNothingResolvesNothing` and
  `CrawlRunExecutorTest.anInteractionPassThatDroveNothingResolvesNothing` both fail without the
  guard. **Neither existed before:** `emptyInteractionArraysAreNoOp` called `resolveOutsideRun`,
  assigned the result to a variable it never asserted on, and its comment described this bug in
  passing. It is now `emptyDrivenPageSetIsANoOp` and asserts.
- **Finding 6 (the flat coverage sets were a cartesian product again — fixed):** the plan specified
  `InteractionOutcome` as two flat sets, which reads as `types × pages`. With one check that is
  harmless; with plan 11's two it means a check driven on `/` resolves its own finding on `/kontakt`
  because a *different* check was driven there. `RunCoverage.interactionLocationKeys` is now
  `Map<CheckType, Set<String>>` and `RESOLVE_INTERACTION_SQL` runs once per type. Fixed now rather
  than after plans 11–12 create the data that makes it visible.
- **Finding 7 (`CookieBannerCheck` could emit an unresolvable finding — fixed):** `observedOn` came
  from `UrlNormalizer.normalize(page.url()).orElse(null)`, and `CheckFinding.locationKey()` maps
  null to `"*"`. `RESOLVE_INTERACTION_SQL` has no `'*'` branch **by design** (D75), so such a finding
  would never resolve. Materialisation was guarded; the check itself was not. It now falls back to
  the site's base URL.
- **Finding 8 (the dismissal wait had two values — fixed):** the constants table says 3 s, the
  runner's consent-only path used 3 s, and `CookieBannerCheck` hardcoded 2 s — the same banner could
  be called dismissable by one path and undismissable by the other. Now one
  `CookieBanner.DISMISSAL_WAIT`, which is also the null default inside `accept`.
- **Finding 9 (`covered_interaction_urls` held location keys — fixed):** the runner returned
  location keys, so `RunCoverage.parseLocationKeysInto` had grown an unrecorded
  `else if (url.startsWith("/"))` passthrough — which also silently widened the *crawl* coverage
  path, where an unparseable URL had always been dropped. The interaction side now carries real URLs
  and is normalised like every other coverage URL; the passthrough is gone.
- **Note (not changed):** the per-check timeout is measured after the fact rather than enforced —
  `executeCheck` runs the check to completion and discards its findings if it overran. This matches
  the plan's Step 5 ("a deadline check around it rather than a second executor", with
  `Page.setDefaultTimeout` bounding the locator waits) and is the right call while Playwright objects
  are thread-confined, but a check that hangs on non-Playwright work is still unbounded.
- **Metrics after the review:** 949 tests / 2m13s (+4 tests over the 945 recorded above; all four are
  `-Pfast` coverage tests, no new Chromium).
