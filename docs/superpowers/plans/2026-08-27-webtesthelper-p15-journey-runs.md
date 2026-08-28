# Plan 15 — Journeys as run participants

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development
> (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use
> checkbox (`- [ ]`) syntax for tracking.

**Goal:** A scheduled run replays a site's journeys, a failing step becomes a triageable finding
that names the journey and the step, drift becomes a warning finding, and a run that replayed some
journeys resolves nothing belonging to the ones it skipped.

**Architecture:** No new machinery — this plan teaches three existing mechanisms that a second kind
of subject exists. `CheckType` learns a third kind (D108), `RunCoverage` learns a third scope
(D107), and `FindingMaterializer` learns to build identity from a journey and a step UUID instead of
a URL (§6.2).

**Spec:** `docs/superpowers/specs/2026-08-21-webtesthelper-design.md` — §6.2, §6.4, §9, §10.4.
**Roadmap:** `2026-08-27-webtesthelper-phase-4-roadmap.md` — its Deviations (D106–D110), the D107
and D108 arguments in full, "What Phase 4 does not ship" and the calibration rules govern this plan
and are **not restated**.

**Depends on:** plan 14. **New deviations:** D107, D108 (argued in the roadmap; this plan implements
them). No new dependency.

> ⚠ **This plan was written before plan 14 executed.** Re-read it against the tree before starting,
> and reconcile it with plan 14's "Execution findings" — in particular the exact shape of
> `JourneyReplayResult` and `StepOutcome`, which every task here consumes. Amending this plan now is
> correct; amending it after it has run is not.

---

## What exists already

- `FindingStore` holds **two** resolve statements: `RESOLVE_SQL`, scoped by
  `check_type = ANY(?) AND location_key = ANY(?)`, and `RESOLVE_INTERACTION_SQL`, run once per
  interaction type against that type's own page set. Its comment at the loop over
  `interactionLocationKeys` is the precedent this plan follows exactly.
- `RunCoverage` already carries `interactionCheckTypes` and `interactionLocationKeys`, and its
  Javadoc already explains why the second is a map. Read it before writing Task 2.
- `CheckType.interaction()` — the enum holds a copy of a `CheckRegistry` fact, and
  `CheckRegistryTest` fails the build when the two disagree. D108 is this move a second time.
- `FindingMaterializer.materialise(long siteId, List<CheckFinding> findings, …)` and
  `Fingerprint` — the only place identity is computed.
- `CrawlRunExecutor` runs the crawl, then the interaction pass, then materialisation.

## File structure

| File | Responsibility |
|---|---|
| `model/CheckType.java` | +`JOURNEY_STEP_FAILED`, `SELECTOR_DRIFT`, +`journey()` |
| `model/RunCoverage.java` | +`journeyIds`, and the accessor resolution reads |
| `db/migration/V22__run_journey_coverage.sql` | Coverage column; journey health columns |
| `findings/JourneyFindingMapper.java` | `JourneyReplayResult` → `MaterialisedFinding` |
| `findings/FindingStore.java` | +`RESOLVE_JOURNEY_SQL` |
| `runner/JourneyPass.java` | Replays a site's enabled journeys during a run |
| `runner/CrawlRunExecutor.java` | Calls it; feeds coverage |
| `catalog/JourneyHealth.java`, `JourneyHealthService.java` | §10.4's four numbers |
| `web/JourneyController.java` + templates | Health on screen |

---

### Task 1: Two new check types that are not checks

**Files:** Modify `model/CheckType.java`, `messages.properties`, `help/reisen.md`. Modify
`checks/CheckRegistry.java` only if its `standard()` set is enumerated by hand. Test: modify
`…/checks/CheckRegistryTest.java`, `…/checks/ScopeCheckSetTest.java`; create
`…/model/CheckTypeJourneyTest.java` (`-Pfast`).

**Produces:** `CheckType.JOURNEY_STEP_FAILED`, `CheckType.SELECTOR_DRIFT`, `CheckType.journey()`.

- [ ] **Step 1: Write the failing tests.** `journey()` is true for exactly the two new constants and
  false for all seventeen others; `interaction()` is false for both — a type is at most one kind.
  Then the two build gates, which must be **taught**, not weakened: `CheckRegistryTest` currently
  fails the build for a `CheckType` with no implementation, and must now exempt journey types while
  still failing for a non-journey type with no implementation — assert **both** halves, because an
  exemption that swallows the original rule is how the gate silently dies. `ScopeCheckSetTest`
  asserts journey types are in `FULL` and `DEEP` and **absent from `PULSE`** (roadmap: §9 gives
  `PULSE` no submits).

- [ ] **Step 2: Run and watch them fail.** `./mvnw test -Pfast -Dtest='CheckType*,CheckRegistryTest,ScopeCheckSetTest'`

- [ ] **Step 3: Implement.** `RunScope.checkTypes()` returns `EnumSet.allOf` for `FULL`/`DEEP`, so
  those need no change; `PULSE` enumerates its set by hand and stays correct by omission — add an
  assertion rather than an edit. Add German message keys and extend `help/reisen.md`; §13.7's
  documentation gate rejects a type with no copy.

- [ ] **Step 4: Run to green,** then full `./mvnw test`.

- [ ] **Step 5: Commit.** `feat(model): a journey failure is a finding, but a journey is not a check`

---

### Task 2: Coverage learns a third scope

**Files:** Create `V22__run_journey_coverage.sql`. Modify `model/RunCoverage.java`,
`runner/persistence/RunEntity.java`, `findings/FindingStore.java`. Test: modify
`…/model/RunCoverageTest.java`; create `…/findings/FindingStoreJourneyResolutionTest.java`
(Testcontainers, `-Pfast`).

**Produces:** `RunCoverage.journeyIds()` → `Set<Long>`; a fourth `RunCoverage.of(…)` overload taking
it.

```sql
-- D107. Journey findings resolve only within the journeys a run actually finished replaying —
-- the third scope, after the crawl's pages (spec 6.4) and the interaction pass's per-type page
-- sets (D74). A run that replayed 3 of 5 journeys must leave the other two alone.
ALTER TABLE run ADD COLUMN covered_journey_ids JSONB NOT NULL DEFAULT '[]'::jsonb;
```

- [ ] **Step 1: Write the failing tests.** In `RunCoverageTest`: `journeyIds` is copied defensively;
  a run with no journeys yields an empty set, not null. In the store test — and this is the task's
  real deliverable — seed two journeys with an open finding each, resolve against a coverage naming
  **only the first**, and assert the second journey's finding is **still ACTIVE**. Then the
  symmetric leak: a journey finding must **not** be resolved by `RESOLVE_SQL`'s crawl-scoped
  statement even though the run crawled 300 URLs. Assert both; the first alone passes trivially if
  journey findings are simply never resolved at all.

- [ ] **Step 2: Run and watch them fail.**

- [ ] **Step 3: Implement.** `RESOLVE_JOURNEY_SQL` mirrors `RESOLVE_INTERACTION_SQL`, scoped by
  `check_type = ANY(?) AND location_key = ANY(?)` where the location keys are the covered journeys'
  keys. Journey types must also be **excluded from the crawl-scoped statement**, the way
  interaction types already are at `FindingStore:500` — that filter is the leak the second assertion
  catches.

- [ ] **Step 4: Run to green.**

- [ ] **Step 5: Commit.** `fix(findings): a run that skipped a journey has not proved it works`

**Why the covered set is journeys *completed*, not journeys *attempted*.** A journey that threw
partway leaves its later steps unjudged. If it counted as covered, its step-4 finding would resolve
because step 4 never reported this run — the run did not see it work, it merely stopped looking.
`JourneyPass` adds a journey to coverage only after `replay` returns a result.

---

### Task 3: A journey failure becomes a finding

**Files:** Create `findings/JourneyFindingMapper.java`. Test:
`…/findings/JourneyFindingMapperTest.java` (`-Pfast`).

**Consumes:** `JourneyReplayResult`, `StepOutcome` (plan 14). **Produces:**
`JourneyFindingMapper.map(JourneyDefinition journey, JourneyReplayResult result)` →
`List<MaterialisedFinding>`. The site id comes from `JourneyDefinition.siteId()`; do not pass it
separately, or the two can disagree.

**Identity, from §6.2 verbatim:** `locationKey` is the journey; `subjectKey` is the step's stable
UUID.

- [ ] **Step 1: Write the failing tests.** A `FAILED` outcome produces one `JOURNEY_STEP_FAILED`
  finding whose `subjectKey` is the step UUID and whose `locationKey` identifies the journey. A
  `DRIFTED` outcome produces one `SELECTOR_DRIFT` finding at a lower severity. A `PASSED` replay
  produces none. Then the property the whole design rests on: **re-recording a journey and
  reordering its steps does not change the fingerprint of a finding about a step that kept its
  UUID** — build the same step at ordinal 2 and at ordinal 7 and assert one fingerprint. §6.2's
  reason for the stable UUID is *"so re-recording does not orphan triage history"*; without this
  test that sentence is unenforced.
  Also: a journey renamed keeps its findings — so `locationKey` is derived from the journey **id**,
  not its name. Assert it, because the name is the tempting choice and it is wrong.

- [ ] **Step 2: Run and watch them fail.**

- [ ] **Step 3: Implement.** Reuse `Fingerprint`; site-wide promotion does **not** apply — a journey
  is already a single location and there is no threshold at which it becomes "on 312 pages".

- [ ] **Step 4: Run to green.**

- [ ] **Step 5: Commit.** `feat(findings): a broken step keeps its name across a re-recording`

---

### Task 4: Journeys run inside a run

**Files:** Create `runner/JourneyPass.java`. Modify `runner/CrawlRunExecutor.java`. Test:
`…/runner/JourneyPassTest.java` (`-Pfast`, with a stub replayer).

**Produces:** `JourneyPass.run(SiteContext site, RunScope scope, Path artifacts)` →
`JourneyPassResult(List<MaterialisedFinding> findings, Set<Long> completedJourneyIds)`.

- [ ] **Step 1: Write the failing tests.** A `FULL` run replays a site's **enabled** journeys and
  skips disabled ones — a disabled journey is absent from `completedJourneyIds`, so its findings
  resolve neither way. A `PULSE` run replays none. A journey whose replay **throws** is caught, is
  absent from `completedJourneyIds`, and does not abort the pass — one broken journey must not cost
  the run its other journeys or its crawl findings. Findings from all replays are returned together.

- [ ] **Step 2: Run and watch them fail.**

- [ ] **Step 3: Implement.** Follow `InteractionRunner`'s per-target error containment (D79, D86).
  Call it from `CrawlRunExecutor` after the interaction pass, and feed `completedJourneyIds` into
  the run's coverage.

- [ ] **Step 4: Run to green.**

- [ ] **Step 5: Commit.** `feat(runner): a run replays the site's journeys after it has looked at
  its pages`

---

### Task 5: Journey health

**Files:** Modify `V22__run_journey_coverage.sql` **only if Task 2 has not yet been committed** —
otherwise create `V23__journey_health.sql`. Create `catalog/JourneyHealth.java`,
`catalog/JourneyHealthService.java`. Modify `JourneyEntity`. Test:
`…/catalog/JourneyHealthServiceTest.java` (Testcontainers, `-Pfast`).

**Produces:** `JourneyHealth(Instant lastSuccessAt, int consecutiveFailures, int driftCount, boolean
needsRerecording)`; `JourneyHealthService.record(long journeyId, JourneyReplayResult result)`.

Columns on `journey`: `last_success_at TIMESTAMPTZ`, `consecutive_failures INT NOT NULL DEFAULT 0`,
`drift_count INT NOT NULL DEFAULT 0`.

- [ ] **Step 1: Write the failing tests.** A passing replay sets `lastSuccessAt` and zeroes
  `consecutiveFailures`. Three failing replays leave it at 3. A **drifted** replay counts as a
  success for `consecutiveFailures` — it passed — while incrementing `driftCount`; this is the
  distinction §10.2 exists to draw and the easy thing to get backwards. `needsRerecording` is
  derived, not stored, and is true on the threshold decided below.

- [ ] **Step 2: Run and watch them fail.**

- [ ] **Step 3: Implement.**

- [ ] **Step 4: Run to green.**

- [ ] **Step 5: Commit.** `feat(catalog): a journey remembers how it has been doing`

**The threshold is a decision this task must record, not inherit.** §10.4 says a journey *"failing
repeatedly with drift"* is flagged. Both conditions matter: repeated failure alone means the site is
broken, which is a finding and already reported; drift alone means one selector moved. The
combination means the *recording* is stale. Start at `consecutiveFailures >= 3 && driftCount > 0`
and **record the number and the reasoning here**, the way plan 3a recorded the soft-404 cutoff of 16.
It is not derivable from first principles and a later reader will want to know it was chosen rather
than found.

---

### Task 6: Health on screen

**Files:** Modify `web/JourneyController.java`, `templates/journey/{list,detail}.html`,
`messages.properties`, `help/reisen.md`. Test: modify `…/web/JourneyControllerTest.java`.

- [ ] **Step 1: Write the failing tests.** The list shows each journey's last success and failure
  streak. A journey meeting Task 5's threshold shows the *"needs re-recording"* state; one below it
  does not. The detail shows `driftCount` and which steps drifted on the last replay.

- [ ] **Step 2: Run and watch them fail.**

- [ ] **Step 3: Implement.** §13.2: the flag explains itself on the screen — what it means and what
  to do — not a bare badge. Follow `TriageUiAdvice` for the copy pattern.

- [ ] **Step 4: Run to green,** then full `./mvnw test` for the §13.7 gates.

- [ ] **Step 5: Commit.** `feat(web): say when a journey needs recording again instead of failing
  every night`

**Why this is a screen state and not a finding.** §10.4 is explicit — flagged *"rather than
generating the same finding every night."* A `NEEDS_RERECORDING` finding would be ACTIVE
indefinitely, appear in every digest, and be indistinguishable from the site actually being broken,
which is the noise §8 exists to prevent.

---

### Task 7: End to end

**Files:** Create `…/runner/JourneyRunAcceptanceTest.java` — `@Tag("browser")`, **one crawl for the
class** (`CLAUDE.md`).

- [ ] **Step 1: Write the failing acceptance test.** Against the fixture site and plan 14's
  `reise/` flow, in one class sharing one setup:
  (a) A `FULL` run with a healthy journey produces **no** journey findings and sets `lastSuccessAt`.
  (b) A run with a journey whose step 3 targets a missing element produces exactly one
  `JOURNEY_STEP_FAILED` finding, named for the journey and the step.
  (c) Repairing the journey and running again flips that finding to **RESOLVED** — the diff model
  working end to end for a subject that is not a page.
  (d) A second site's journey findings are untouched by the first site's run.

- [ ] **Step 2: Run and watch them fail.**

- [ ] **Step 3: Implement whatever the test exposes.** If (c) fails, the fault is in Task 2's
  coverage wiring, not in the test.

- [ ] **Step 4: Run to green,** then full `./mvnw test`.

- [ ] **Step 5: Commit.** `test(runner): a journey that breaks and then works again is reported as
  fixed`

---

## Self-review checklist for the executing agent

- `./mvnw test` green.
- **Delete `RESOLVE_JOURNEY_SQL`'s scope filter and confirm Task 2's second assertion fails.** D107
  is the correctness deliverable of this plan; a suite that stays green without the scope has not
  proved it.
- No journey type appears in `PULSE`'s set.
- Task 5's threshold and its reasoning are written into this file's Execution findings.

## Execution findings

### Task 5: Journey Health Threshold Rationale (§10.4, D106)
- **Threshold for `needsRerecording`:** `consecutiveFailures >= 3 && driftCount > 0`
- **Reasoning:**
  - Repeated failure alone (`consecutiveFailures >= 3 && driftCount == 0`) means the target website itself is broken or throwing errors. This is an active finding and is already reported as a triageable defect (`JOURNEY_STEP_FAILED`).
  - Selector drift alone (`driftCount > 0 && consecutiveFailures == 0`) means one or more locators had to fall back to secondary candidates, but the replay ultimately passed.
  - The combination of repeated failure *and* recorded selector drift (`consecutiveFailures >= 3 && driftCount > 0`) indicates that the journey definition's recorded locator candidates are degraded/stale and the test can no longer navigate reliably, requiring the user to re-record the journey rather than treating it as a regression on the site.
  - The value of 3 consecutive failures prevents a single flaky run from triggering a re-recording prompt while ensuring persistent locator decay is promptly surfaced to the user.

### Post-execution review (2026-08-28)

**The self-review item about `RESOLVE_JOURNEY_SQL` names the wrong assertion.** Deleting that
statement's `location_key = ANY(?)` scope fails Task 2's *first* assertion
(`resolvesOnlyWithinCoveredJourneys`), not its second. Verified by mutation: the scope is genuinely
load-bearing, so D107's correctness deliverable is proved — but by the other test.

**The second assertion was passing for the wrong reason, and now does not.** Task 2 also asks that
journey types be excluded from the crawl-scoped statement (`.filter(t -> !t.journey())` in
`FindingStore.resolveOutsideRun`). `crawlScopedStatementDoesNotResolveJourneyFindings` cannot see
that filter work: a journey's `location_key` is a numeric id and the crawl's keys are normalised URL
paths, so the location scoping alone already spares the finding. Deleting the filter left the suite
green. Two backstop tests now pin it by removing that second line of defence — one gives the journey
finding a location key the run crawled, one gives it `'*'` against a whole-site run. Both fail with
the filter deleted.

Worth generalising: *a scope test whose subject could never have been in scope anyway proves
nothing.* Both of Task 2's assertions were written the same way and only one happened to bite. When
a test exists to pin a filter, construct the case where that filter is the **only** thing standing
between the subject and the wrong outcome — even when that case cannot arise in production today.
The filter is defence against a future change to the mapper's key format, and that is exactly what
the test has to encode.

**Task 6 shipped only half of "the detail shows `driftCount` and which steps drifted".** The
cumulative count was on screen; the per-step marking was not, and nothing persisted it — `V23` has
no column for it and `JourneyHealth` had no field. Delivered afterwards as
`V24__journey_last_drifted_steps.sql` (`last_drifted_step_ids JSONB`), `JourneyHealth
.lastDriftedStepIds`, and an „Abweichung" badge per step row in `journey/detail.html`.

The column is **overwritten** by every completed replay while `drift_count` accumulates, because the
two answer different questions: "has this recording been decaying?" (the threshold above) versus
"where did the site move, last night?". A `FAILED` replay records its drifted steps too — a step can
drift *and* fail (`StepOutcome.failed(id, winner, drifted, …)`), and that combination is precisely
the stale-recording case §10.4 wants on screen.
