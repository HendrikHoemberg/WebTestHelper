# Plan 14 — Journey model and replay

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development
> (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use
> checkbox (`- [ ]`) syntax for tracking.

**Goal:** A journey stored as ordered steps replays against a live site, falls back through ranked
locator candidates when the primary selector breaks, and reports the fallback as drift rather than
as a failure.

**Architecture:** The step model is pure value types in `model`; persistence and CRUD sit in
`catalog` beside `Credential`, which journeys reference; the replay engine sits in `runner` beside
`InteractionRunner`, whose context lifecycle it copies. No recorder — journeys in this plan are
authored as JSON.

**Spec:** `docs/superpowers/specs/2026-08-21-webtesthelper-design.md` — §10.2, §10.3, §10.4.
**Roadmap:** `2026-08-27-webtesthelper-phase-4-roadmap.md`. Its Scope, Deviations (D106–D110),
"What Phase 4 does not ship", spike results and calibration rules govern this plan and are **not
restated**. Read it first.

**This plan was written ahead of execution** (roadmap, "Calibration and execution"). It is the first
Phase 4 plan, so nothing precedes it; but plans 15–17 were written against *its stated interfaces*.
Where execution changes a signature named in "Produces", say so in Execution findings — plans 15–17
are amended from that section.

**New deviations:** D106 (below, Task 6). No new dependency.

---

## What exists already

- `CredentialService.resolve(long siteId, String template)` → `SecretText`, which carries the
  resolved value behind `expose()` and returns the **template** from `toString()` (D100). Plan 13
  built it; nothing calls it from a browser yet.
- `InteractionRunner` — the model for borrowing a `BrowserPool` worker, opening one
  `BrowserContext`, establishing consent via `CookieBanner.accept`, and closing everything inside
  the worker task. Journey replay is the same lifecycle with an ordered step list in place of a
  check.
- `ScreenshotNames.screenshotName(String, String)` and the artifact store, for failure evidence.
- `@JdbcTypeCode(SqlTypes.JSON)` on a `List<…>` field is this codebase's jsonb mapping — see
  `SiteEntity.includePatterns`.

## File structure

| File | Responsibility |
|---|---|
| `model/JourneyStep.java` | One step: id, ordinal, action, candidates, value, assertion, optional, timeout |
| `model/StepAction.java` | `GOTO CLICK FILL SELECT PRESS HOVER WAIT_FOR ASSERT` |
| `model/LocatorCandidate.java` | `{ strategy, value, rank }` |
| `model/LocatorStrategy.java` | `TEST_ID ROLE LABEL ID TEXT CSS` — declaration order **is** rank order |
| `model/StepAssertion.java` | `{ type, expected }` |
| `model/AssertionType.java` | `TEXT_CONTAINS VISIBLE URL_MATCHES COUNT` |
| `model/JourneyDefinition.java` | A journey as replayed: id, name, steps. No JPA, no Spring |
| `db/migration/V21__journey.sql` | `journey` table; steps as jsonb |
| `catalog/persistence/JourneyEntity.java`, `JourneyRepository.java` | Storage |
| `catalog/JourneyService.java` | CRUD, ordinal renumbering, `JourneyDefinition` projection |
| `runner/LocatorResolver.java` | Candidate ladder → a `Locator`, plus which rank won |
| `runner/StepExecutor.java` | One step against one `Page` |
| `runner/StepOutcome.java`, `runner/JourneyReplayResult.java` | Results |
| `runner/JourneyReplayer.java` | Context lifecycle, ordered execution, failure artifacts |
| `web/JourneyController.java` + templates | Read-only list and detail |

---

### Task 1: The step model

**Files:** Create the seven `model/` types above. Test:
`src/test/java/…/model/JourneyStepTest.java`, `…/model/LocatorCandidateTest.java`.

**Produces:** the record signatures below; every later task and plans 15–17 consume them.

```
JourneyStep(UUID id, int ordinal, StepAction action, List<LocatorCandidate> locatorCandidates,
            String value, StepAssertion assertion, boolean optional, int timeoutMs)
LocatorCandidate(LocatorStrategy strategy, String value, int rank)
StepAssertion(AssertionType type, String expected)
JourneyDefinition(Long id, Long siteId, String name, boolean enabled, List<JourneyStep> steps)
```

- [ ] **Step 1: Write the failing tests.** Assert, without writing implementations first:
  a step with a null `id` is rejected; `locatorCandidates` is copied defensively and returned
  sorted by `rank` ascending regardless of insertion order; a `GOTO` step is valid with **no**
  candidates and a non-blank `value`; a `CLICK` step with no candidates is rejected; `timeoutMs`
  defaults to `JourneyStep.DEFAULT_TIMEOUT_MS` when given as 0; `LocatorStrategy.values()` is in
  rank order with `TEST_ID` first and `CSS` last.

- [ ] **Step 2: Run and watch them fail.** `./mvnw test -Pfast -Dtest='Journey*Test,Locator*Test'`

- [ ] **Step 3: Implement the records.** Compact constructors validate and copy; no framework
  annotations — `model` sees nothing but its own types (§5.1).

- [ ] **Step 4: Run to green.**

- [ ] **Step 5: Commit.** `feat(model): a journey step is an action, a way to find the thing, and a
  fallback plan`

**Why the strategy enum's declaration order is the ranking.** §10.2 gives six strategies in a fixed
preference order. Storing a separate rank *and* an enum invites the two to disagree — a candidate
persisted with `strategy=TEST_ID, rank=6` is unanswerable. `rank` exists because a recorder may
generate two candidates of the same strategy (two matching labels) and their order matters; it is
ordering **within** a strategy, and `LocatorResolver` sorts by `(strategy.ordinal(), rank)`.

---

### Task 2: Journey persistence

**Files:** Create `V21__journey.sql`, `JourneyEntity`, `JourneyRepository`, `JourneyService`. Test:
`src/test/java/…/catalog/JourneyServiceTest.java` (Testcontainers, `-Pfast`).

**Consumes:** Task 1's records. **Produces:** `JourneyService.create(long siteId, String name,
List<JourneyStep> steps)` → `long`; `findDefinition(long journeyId)` → `Optional<JourneyDefinition>`;
`findEnabledBySite(long siteId)` → `List<JourneyDefinition>` (plan 15 dispatches from this).

```sql
-- Journeys belong to a site: a journey resolves {{cred.…}} against one site's store (spec 10.4),
-- and there is no cross-site credential. Steps are jsonb rather than a child table because they
-- are only ever read and written as a whole ordered list, never queried into.
CREATE TABLE journey (
    id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    site_id BIGINT NOT NULL REFERENCES site (id) ON DELETE CASCADE,
    name TEXT NOT NULL CHECK (name <> ''),
    enabled BOOLEAN NOT NULL DEFAULT true,
    steps JSONB NOT NULL DEFAULT '[]'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    version BIGINT NOT NULL DEFAULT 0
);
CREATE UNIQUE INDEX ux_journey_site_name ON journey (site_id, lower(name));
```

- [ ] **Step 1: Write the failing tests.** A journey round-trips through jsonb with every step field
  intact — assert on a step carrying an assertion, `optional=true` and three candidates, because a
  Jackson mapping that drops a nested record fails silently otherwise. A duplicate name within one
  site is rejected; the same name under a different site is accepted. Deleting a site cascades.
  **Step UUIDs survive an update that reorders steps** — §10.3's "stable across edits" is the
  property plan 17's editor depends on and it is cheapest to lock down here.

- [ ] **Step 2: Run and watch them fail.**

- [ ] **Step 3: Implement.** Follow `CredentialEntity` for the entity shape and `CredentialService`
  for validation style — German message keys, `IllegalArgumentException`. `JourneyService.update`
  renumbers `ordinal` to a dense 0..n-1 from list position; it never renumbers `id`.

- [ ] **Step 4: Run to green,** and `./mvnw test -Pfast` for `ddl-auto=validate`.

- [ ] **Step 5: Commit.** `feat(catalog): a journey is a named, ordered list of steps on a site`

---

### Task 3: The candidate ladder, and a fixture flow to try it on

**Files:** Create `runner/LocatorResolver.java`, `runner/LocatorMatch.java`. Create fixture pages
under `src/test/resources/fixture-site/reise/` (see below). Test:
`src/test/java/…/runner/LocatorResolverTest.java` — `@Tag("browser")`, one page load for the class.

**Produces:** `LocatorResolver.resolve(Page page, JourneyStep step)` → `Optional<LocatorMatch>`;
`LocatorMatch(Locator locator, LocatorCandidate winner, boolean drifted)`, where `drifted` is true
when the winner is **not** the highest-ranked candidate on the step.

**The fixture flow** — three pages, served but **not linked from `index.html`** (roadmap; Phase 3's
rule protects `SetupProbeTest`):

| Page | Contains |
|---|---|
| `reise/start.html` | `<a data-testid="reise-start">`, leads to `schritt2.html` |
| `reise/schritt2.html` | A form: `<input data-testid="reise-name">`, `<select>`, a labelled e-mail field with **no** test id, and a submit button with an accessible name |
| `reise/ziel.html` | A confirmation with `<h1>Buchung bestätigt</h1>` and a `data-testid="bestaetigung"` |

Every element carries **at least two** independently usable handles — a test id *and* a role+name,
or a label *and* an id. A fixture where each element is findable exactly one way cannot demonstrate
a fallback, which is the entire subject of this task.

- [ ] **Step 1: Build the fixture pages.** No JavaScript beyond a form that navigates to
  `ziel.html` on submit. Add the deliberately generated-looking id `id=":r7:"` to one element —
  Task 3's negative case and plan 17's rejection rule both need it to exist.

- [ ] **Step 2: Write the failing tests.** A step whose only candidate is a matching `TEST_ID`
  resolves and reports `drifted=false`. A step whose `TEST_ID` candidate matches **nothing** but
  whose `ROLE` candidate matches resolves and reports `drifted=true` — this is §10.2's whole
  promise and it is the assertion that must not be weakened. A step where no candidate matches
  returns `Optional.empty()`. A candidate matching **two** elements is treated as not matching:
  ambiguity is not a resolution, and picking the first is how a replay silently clicks the wrong
  thing.

- [ ] **Step 3: Run and watch them fail.** `./mvnw test -Dtest=LocatorResolverTest`

- [ ] **Step 4: Implement.** Try candidates in `(strategy.ordinal(), rank)` order. Each strategy maps
  to the Playwright locator it names — `getByTestId`, `getByRole`, `getByLabel`, `#id`,
  `getByText`, raw CSS. Resolution means **exactly one** match, tested with `count() == 1`, not by
  catching a timeout: a `Locator` is lazy, so a wrong candidate costs nothing until it is used, and
  `count()` is the cheap way to ask.

- [ ] **Step 5: Run to green.**

- [ ] **Step 6: Commit.** `feat(runner): find the element six ways and say which one worked`

---

### Task 4: Executing one step

**Files:** Create `runner/StepExecutor.java`, `runner/StepOutcome.java`. Test:
`…/runner/StepExecutorTest.java` — `@Tag("browser")`, one context for the class.

**Consumes:** `LocatorResolver`. **Produces:** `StepExecutor.execute(Page page, JourneyStep step,
String resolvedValue)` → `StepOutcome`; `StepOutcome(UUID stepId, StepStatus status,
LocatorCandidate winner, boolean drifted, String failureMessageKey, List<String> failureArgs)` with
`StepStatus { PASSED, DRIFTED, FAILED, SKIPPED }`.

`resolvedValue` is passed in already resolved rather than read from `step.value()` — Task 5 explains
why, and the signature exists in this shape from the start so Task 5 changes no callers.

- [ ] **Step 1: Write the failing tests.** One per action against the fixture flow: `GOTO`
  navigates; `CLICK` follows the link; `FILL` types into the input; `SELECT` picks an option;
  `PRESS` sends a key; `HOVER` resolves without navigating; `WAIT_FOR` succeeds on a present element
  and fails on an absent one within `timeoutMs`; `ASSERT` with each of the four `AssertionType`s,
  passing and failing. Then the two rules that are not per-action: an **optional** step whose
  locator resolves to nothing returns `SKIPPED`, not `FAILED`; a **non-optional** one returns
  `FAILED`. And a step that resolves via a fallback returns `DRIFTED` with `winner` naming the
  strategy that worked — `DRIFTED` is a **passing** status.

- [ ] **Step 2: Run and watch them fail.**

- [ ] **Step 3: Implement.** Per-step timeout from `step.timeoutMs()` applied to the Playwright call,
  not to the whole method. Failures produce a German `messageKey` and args, never a formatted string
  — §13.1, and `CheckDocumentationTest` will want the keys.

- [ ] **Step 4: Run to green.**

- [ ] **Step 5: Commit.** `feat(runner): one step, eight actions, and an optional step that is
  allowed to be missing`

**Why `optional` is a status and not a swallowed error.** §10.3's motivating case is the cookie
banner that may not appear. If an optional step's failure were simply discarded, a journey whose
every step went missing after a redesign would replay green. `SKIPPED` is recorded, counted and
shown; plan 15's health model treats an all-skipped journey as a health problem.

---

### Task 5: Credentials reach the browser

**Files:** Modify `runner/JourneyReplayer` inputs — create
`runner/JourneyValueResolver.java`. Test: `…/runner/JourneyValueResolverTest.java` (`-Pfast`).

**Consumes:** `CredentialService.resolve`. **Produces:**
`JourneyValueResolver.resolve(long siteId, JourneyStep step)` → `SecretText`.

- [ ] **Step 1: Write the failing tests.** A step value with no `{{cred.…}}` returns
  `SecretText.plain` and is **not** sensitive. A value of `{{cred.login.password}}` returns the
  stored secret behind `expose()` while `toString()` returns the template verbatim — the D100
  property, asserted from both sides. A reference to a credential that does not exist fails with the
  step's German key, not with a null. A value mixing literal text and a reference resolves the
  reference and keeps the literal.

- [ ] **Step 2: Run and watch them fail.**

- [ ] **Step 3: Implement.** Delegate to `CredentialService.resolve`; this class exists to give the
  replayer a single call and to keep `runner` from reaching into `catalog`'s internals.

- [ ] **Step 4: Run to green.**

- [ ] **Step 5: Commit.** `feat(runner): a step may carry a password without ever holding one`

**Why `StepExecutor` takes a resolved `String` and not the `SecretText`.** The executor must not be
able to log its input. Handing it the already-exposed value at the single call site in
`JourneyReplayer` means exactly one line in the system holds plaintext, and it is a line with no
logger on it. `JourneyReplayer` logs `SecretText.toString()` — the template — when it logs at all.

---

### Task 6: The replay engine

**Files:** Create `runner/JourneyReplayer.java`, `runner/JourneyReplayResult.java`. Test:
`…/runner/JourneyReplayAcceptanceTest.java` — `@Tag("browser")`, **one replay per test method is
acceptable here** because the deliverable is the replay itself; keep it to four methods.

**Consumes:** everything above, `BrowserPool`, `CookieBanner.accept`, `ScreenshotNames`.
**Produces:** `JourneyReplayer.replay(JourneyDefinition journey, SiteContext site, Path artifacts)`
→ `JourneyReplayResult(long journeyId, String journeyName, ReplayStatus status, List<StepOutcome>
outcomes, int driftCount, Optional<String> screenshotName, Optional<String> traceName)` with
`ReplayStatus { PASSED, DRIFTED, FAILED }`.

- [ ] **Step 1: Write the failing acceptance tests.**
  (a) The fixture journey — start, click, fill, submit, assert the confirmation — replays `PASSED`
  with every outcome `PASSED` and `driftCount == 0`.
  (b) The same journey with the first step's `TEST_ID` candidate changed to a value present on no
  page replays **`DRIFTED`, not `FAILED`**, every step still passes, `driftCount == 1`, and the
  drifted outcome names `ROLE` as the winner. *This is §10.2's promise and the reason the phase
  exists; if only one assertion in this plan survives review, it is this one.*
  (c) A journey whose third step targets an element on no page replays `FAILED`, stops at that step,
  and the outcomes after it are absent — a journey is ordered and a later step cannot be judged
  after an earlier one failed.
  (d) The failed replay wrote **a screenshot and a trace** into the artifact directory, named by
  `ScreenshotNames`.

- [ ] **Step 2: Run and watch them fail.**

- [ ] **Step 3: Implement.** One `BrowserPool.submit` per journey; one fresh `BrowserContext` inside
  it with the crawl's viewport and user agent; consent established once via `CookieBanner.accept`
  before step 1; tracing started at context creation and **discarded unless the replay fails**, per
  §10.4. Everything closed inside the worker task — a `Page` may not leave (see `BrowserPool`'s
  contract).

- [ ] **Step 4: Run to green.** `./mvnw test -Dtest=JourneyReplayAcceptanceTest`

- [ ] **Step 5: Commit.** `feat(runner): replay a journey, and let a redesign warn instead of break`

**D106 — a journey result is not a `CheckFinding`, and this task is where that becomes visible.**
`CheckFinding` takes `NormalizedUrl observedOn` and derives `locationKey()` from it; §6.2 requires a
journey finding's `locationKey` to be *the journey* and its `subjectKey` to be *the step's UUID*.
Neither is a URL. `JourneyReplayResult` is therefore its own type and plan 15 maps it at
materialisation — the same place page findings acquire their fingerprints, and the only place that
has ever known about identity (§7.3: a check "needs no knowledge of identity or lifecycle, and
cannot accidentally acquire any"). Extending `CheckFinding` with a nullable location override would
put a journey concept into the record every one of the seventeen page and interaction checks
returns, to serve none of them.

---

### Task 7: Seeing a journey

**Files:** Create `web/JourneyController.java`, templates
`src/main/resources/templates/journey/{list,detail}.html`, help topic
`src/main/resources/help/reisen.md`. Modify `messages.properties`, and the site detail template to
link the list. Test: `…/web/JourneyControllerTest.java` (`-Pfast`, `@WebMvcTest`).

**Consumes:** `JourneyService`. Read-only: creation is JSON-authored in this plan and becomes the
recorder's job in plan 17.

- [ ] **Step 1: Write the failing tests.** The list shows a site's journeys with step counts and
  enabled state. The detail shows steps in ordinal order with action, the winning-candidate strategy
  names and the assertion. **A step whose value contains `{{cred.…}}` renders the template, never a
  secret** — assert on the rendered body, because this is the screen where a leak would be visible
  to a user. An unauthenticated request is redirected (`SecurityConfig`).

- [ ] **Step 2: Run and watch them fail.**

- [ ] **Step 3: Implement.** Follow `CredentialController` for the controller and template shape,
  including its redaction advice.

- [ ] **Step 4: Run to green,** then the full `./mvnw test` — `CheckDocumentationTest` and the
  message-key completeness gate (§13.7) run there and will reject a missing German key.

- [ ] **Step 5: Commit.** `feat(web): see what a journey does before it does it`

---

## Self-review checklist for the executing agent

- `./mvnw test` green, and the count has grown by the number of tests this plan adds.
- `git grep '{{cred' -- 'src/main/resources/templates'` returns nothing that renders a resolved
  value.
- The step JSON written by Task 2's test is byte-identical after a save/load/save cycle.
- **`LocatorResolverTest` and `JourneyReplayAcceptanceTest` genuinely fail when `LocatorResolver` is
  made to return only the first candidate.** Drift is the deliverable; a green suite that would stay
  green without the fallback ladder has not tested it.

## Execution findings

*(Filled in during execution. Record measured constants and anything that contradicts this plan —
plans 15–17 were written ahead of execution and are amended from this section. Do not edit the tasks
above once they have run.)*
