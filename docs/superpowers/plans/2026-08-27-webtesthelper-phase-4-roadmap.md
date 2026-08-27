# WebTestHelper Phase 4 — Plan Roadmap

**Date:** 2026-08-27
**Spec:** `docs/superpowers/specs/2026-08-21-webtesthelper-design.md` (§17 defines Phase 4; §10 is
the whole of it)
**Predecessor:** `2026-08-27-webtesthelper-phase-3-roadmap.md` — Phase 3 complete, four plans
executed, **1204 tests** green on `main` (`e5795db`).

**Phase 4 in one line (§17):** *the long tail.*

Phases 1–3 built a system that visits pages and judges what it finds there. Every finding so far
is about **a page**: a URL was fetched or driven, a check looked at it, and the finding's
`locationKey` is where it was seen. Phase 4 introduces the first subject in the system that is not
a page — a **journey**, an ordered sequence of steps that only means anything end to end. A journey
does not fail *on a URL*; it fails *at step 4 of 9*. §6.2 already anticipated this and says so in
one sentence: *"For journeys: `locationKey` is the journey, `subjectKey` is the step's stable
UUID."*

That sentence is the whole architectural content of the phase, and it is why the phase is not
simply "four more checks". Every mechanism built in Phases 1–3 that keys on a page — coverage
resolution, fingerprinting, site-wide promotion, re-verification — has to learn that a second kind
of subject exists.

The phase has two halves, and the plan split follows them: **replaying** a journey, and
**recording** one.

---

## Scope

§17 lists four items. §10 expands them into seven deliverables.

| From | Item |
|---|---|
| §17 / §10.3 | The step model — `JourneyStep`, eight actions, ranked locator candidates, `optional`, per-step timeout |
| §17 / §10.2 | Selector candidates: six ranked strategies, and the rejection rule for generated ids |
| §17 / §10.4 | **Replay** — fresh context, auto-waiting, per-step timeout, screenshot plus trace on failure |
| §17 / §10.2 | `SELECTOR_DRIFT` — primary candidate fails, fallback succeeds, step passes, warning emitted |
| §17 / §10.4 | **Journey health** — last success, consecutive failures, drift count, *"needs re-recording"* |
| §17 / §10.1 | **The recorder** — `RecordingSession`, recorder pool, 15-minute idle timeout, two concurrent sessions, CDP screencast over a WebSocket, input forwarding, intent capture |
| §17 / §10.4 | The step **editor** — delete, reorder, edit values, mark optional, add assertions; UUIDs survive all of it |
| §10.4, §6.1 | `{{cred.<name>.<field>}}` resolved **at replay**. Plan 13 built the store; nothing resolves one into a running browser yet |

## The plans

Four plans, each producing working, testable software on its own.

| # | File | Goal | Ends with | Depends on |
|---|---|---|---|---|
| 14 | `…-p14-journey-replay.md` | The journey and step model, its migration, credential resolution at replay, and the replay engine with ranked-candidate fallback and `SELECTOR_DRIFT` | A journey authored as JSON replays against the fixture, and a renamed `data-testid` produces a drift warning instead of a failure | Phase 3 |
| 15 | `…-p15-journey-runs.md` | Journeys as run participants: dispatch, journey-scoped coverage, finding identity and materialisation, health tracking, the journey list and detail screens | A scheduled run replays a site's journeys and a failing step becomes a triageable finding that resolves when it passes | 14 |
| 16 | `…-p16-recorder-session.md` | `RecordingSession`, the recorder pool, the CDP screencast bridge over a WebSocket, and input forwarding | An employee opens Record, sees the live page in a `<canvas>`, and clicks it | 15 |
| 17 | `…-p17-capture-editor.md` | Intent capture via `addInitScript`, candidate generation and ranking, and the step editor | A recorded session becomes a journey that plan 14's engine replays | 16 |

### Why replay ships before the recorder, and why that is not the obvious order

§10 describes recording first and replay second, and building in that order would be a mistake.

**The value is in replay.** §10.5 states plainly that the recorder may slip — *"it ships last. If
it slips, layers 1 and 2 still deliver most of the checklist."* A journey that runs nightly and
reports a broken checkout is worth having whether a human recorded it in a browser or typed it as
JSON. The recorder is a **convenience for authoring**; the replay engine is the product. Building
the convenience first means the product is the thing at risk.

**Replay can be proven without the recorder; the recorder cannot be proven without replay.** A
hand-authored journey against the fixture site exercises the step model, every candidate strategy,
the fallback ladder, drift, health and finding identity — with no CDP, no WebSocket and no
screencast. Whereas the only way to know a recorder produced a *good* journey is to replay it. Build
recording first and its acceptance test is "some JSON was written"; build it second and the
acceptance test is "the recorded journey replays green", which is the thing anyone actually wants.

**And it puts the schema risk first.** If the step model is wrong, plan 14 discovers it against a
hand-written fixture in an afternoon. Discovered in plan 17, it invalidates the recorder's capture
output and plan 14's engine at once.

## The spike, and what it measured

§10.1 is the least verifiable part of the spec — CDP screencast, frame acks, input dispatch and
coordinate scaling, none of which is exercised anywhere in the tree. It was spiked before this
roadmap was written, against the real fixture and Playwright 1.62.0, and the spike was then deleted.
Six results, recorded here because they are not recoverable by reading the code:

| Question | Measured answer |
|---|---|
| Does `Page.startScreencast` work under Playwright 1.62's **default** headless? | **Yes.** No `setChannel("chromium")` needed — the default headless and the chromium channel produced a byte-identical first frame. §10.1 costs no launch-option change |
| Frame size and format | 32,684 base64 characters (~24 KB) per JPEG frame at `quality: 60`, 1280×720 |
| Frame cadence | **Change-driven, not periodic.** A static page emits exactly **one** frame — the initial paint — no matter how long the screencast runs. Ten forced repaints produced eleven frames |
| Does `ctx.newCDPSession(page)` coexist with the normal Playwright API on the same page? | **Yes.** `locator().boundingBox()` and `Input.dispatchMouseEvent` were used against one page in one test |
| Does CDP input reach the page and fire a capture listener? | **Yes** — with `buttons` and `clickCount` alongside `type`/`x`/`y`/`button`. Omitting `buttons` silently delivers nothing |
| Does `exposeBinding` + `addInitScript` capture intent? | **Yes**, capture-phase listeners fire and the binding survives navigation |

**The cadence result is the one that changes a design.** A live view that assumes frames arrive
steadily will show a viewer a blank canvas on any idle page, which is most pages most of the time —
and it will look exactly like a broken WebSocket. Plan 16 therefore owes a `Page.captureScreenshot`
on attach, and its test must assert the idle case, not just the animated one.

**The trap, which cost the spike a run:** `addInitScript` **does not apply to a page populated with
`page.setContent()`**. The init script never executes and the binding never fires. `setContent` is
the natural instinct for a three-line DOM fixture, and it fails silently — no error, no capture,
nothing to debug. Plan 17's tests navigate to a `data:` URL or a fixture page instead. This is
recorded because the failure mode is indistinguishable from "the capture script is wrong".

The one new dependency the phase needs is **`spring-boot-starter-websocket`**, confirmed to resolve
at Boot 4.1.1. Nothing else — Playwright already exposes CDP, and `CredentialService` already
resolves `{{cred.…}}`.

## Deviations from the spec

Phases 1–3 established D1–D105 and they are **not** restated. Phase 4 continues from **D106**. Each
plan carries the reasoning behind its own; this table holds only the phase-wide ones.

| # | Deviation | Plan |
|---|---|---|
| D106 | A journey subject is **not** a page. `CheckFinding.observedOn` is a `NormalizedUrl` and its `locationKey()` derives from one, so journeys emit their own value type rather than overloading it — §6.2's *"`locationKey` is the journey"* cannot be expressed by the existing record | 14 |
| D107 | **Coverage gains a third scope.** D74 split it once for interaction checks; journeys split it again. A run that replayed 3 of 5 journeys must not resolve the other two's findings | 15 |
| D108 | Journey check types are registered as a **third kind**, alongside `CheckType.interaction()` — exempt from `CheckRegistry` (they have no `evaluate`) but **not** exempt from `CheckDocumentationTest` | 15 |
| D109 | The recorder gets its **own pool**, not `BrowserPool`. A 15-minute interactive session holding one of four thread-confined crawl workers would starve the crawler for a quarter of an hour | 16 |
| D110 | The live view is **change-driven**, per the spike: a frame on attach via `Page.captureScreenshot`, then screencast frames as they come | 16 |

### D107 in full, because it is the correctness one, and it is D74's third outing

`RunCoverage` already carries the scar of getting this wrong once. It began as a cartesian product
of `checkTypes × locationKeys`; D74 split off `interactionCheckTypes` and
`interactionLocationKeys` when a `COOKIE_BANNER` driven on one page would have claimed coverage of
300 and silently resolved a banner finding on a page it never visited. Its own Javadoc now spells
out why the interaction half is a **map** and not a flat set — *"a flat set is a cartesian product
again at smaller scale."*

Journeys are the same failure a third time, and worse, because the ratio is more extreme. A site has
300 pages and perhaps three journeys. A `FULL` run that replays the checkout journey and skips the
login journey — because it is disabled, or its credential will not decrypt, or the recorder never
finished it — must resolve nothing belonging to the login journey. Under any page-keyed coverage it
would resolve everything, because the run's `locationKeys` contains every URL it crawled and a
journey's location key is not a URL at all.

The fix has the same shape as D74's and should reuse it deliberately: journey findings resolve only
within the set of journeys the run actually **completed a replay of** — not the set it intended to
replay, and not the set it started. Plan 15 owns it, and it earns §6.4's *"this gets an explicit
test"* for the third time.

### D108, because it decides whether journeys are checks

`CheckRegistryTest` fails the build when a `CheckType` has no implementation; `ScopeCheckSetTest`
asserts the tier split against `CheckRegistry`; `RunScope.checkTypes()` returns
`EnumSet.allOf(CheckType.class)` for `FULL` and `DEEP`. So adding `SELECTOR_DRIFT` as a plain
`CheckType` breaks two build gates immediately, because no `CheckDescriptor` implements it.

But the fingerprint of §6.2 is `sha256(siteId, checkType, subjectKey, locationKey)`, and triage,
muting and the digest all filter on check type. A journey failure that is not a `CheckType` cannot
be fingerprinted, triaged or muted — it would need a parallel copy of Phases 1–3's entire finding
machinery.

So journey types **are** `CheckType` constants and are **not** registry entries. `CheckType` already
models exactly this distinction once: `interaction()` marks the types that behave differently, its
Javadoc explains that `CheckRegistry` is the truth and the enum holds a copy, and
`CheckRegistryTest` fails the build when the two disagree. A `journey()` predicate alongside it is
the same move a second time, which is the argument for it — a reviewer who understands
`interaction()` understands this for free.

## What Phase 4 does not ship

Named so a reviewer can tell a gap from a decision.

- **Everything §10.5 excludes.** Multiple tabs, file uploads, downloads, drag-and-drop. The spec
  draws this boundary itself; the plans do not reopen it.
- **Re-verification of journey findings.** §8 re-probes failures with fresh contexts, and
  `FindingReverifier` does it over HTTP. D105 already established that an interaction finding is
  never re-probed that way; a journey is the same argument with more force, since replaying a
  journey to confirm a failure may submit a form twice. A journey's retries live **inside** the
  replay, in the context it already holds.
- **`SELECTOR_DRIFT` as a mute-rule pattern subject.** Muting works per finding via the existing
  machinery; a pattern rule over selector strings is a Phase 5 question if it is ever a question.
- **Journeys in `PULSE`.** §9 gives `PULSE` page checks only and no submits. A journey is by
  definition a submit-shaped thing. `FULL` and `DEEP` replay journeys; `PULSE` does not.
- **The four inherited gaps.** The `IFRAME_EMBED` canvas-paint gap, blocked-iframe URL attribution,
  Maps-error attribution and D23's re-verification scope. Open since the plan-3 review and unchanged
  — each needs a measurement in front of a plan, not inside one.
- **§16's application image and the second Compose service**, and the SMTP relay question. Open
  since the Phase-2 roadmap.
- **A second locale.** §12 is German-only; every key added in this phase is German-only.

## Open questions to settle before the plan that needs them

- **Plan 14:** does a journey belong to a site, or can it be global? Plan 13 asked the mirror of this
  question about credentials and answered *per-site*. §6.1 hangs `Journey` off `Site`, and a journey
  referencing `{{cred.login.password}}` can only resolve it within one site's store. Confirm the two
  answers agree before committing the foreign key.
- **Plan 14:** what is the drift *threshold*? A journey where every step falls back to a rank-6 CSS
  path is not healthy, but §10.2 emits one warning per drifted step. Decide whether drift is per step
  (many findings) or per journey (one finding naming the count) — §6.2's site-wide promotion at
  `SITE_WIDE_THRESHOLD = 5` is the precedent to argue from.
- **Plan 15:** does a journey replay run inside the existing `CrawlRunExecutor` after the interaction
  pass, or as its own executor? The interaction pass already borrows a `BrowserPool` worker per
  target after the crawl; a journey is the same shape. Reuse is likely right, but it decides whether
  a journey can run without a crawl, which is what makes a journey-only run possible.
- **Plan 16:** what is the WebSocket authentication story? Every screen so far is behind
  `SecurityConfig`; a raw `WebSocketHandler` is not automatically. The screencast streams a live view
  of a customer's site inside an authenticated session, and the recorder can type a credential into
  it. This must be settled *before* the endpoint exists, not after.
- **Plan 16:** the spike measured ~24 KB per frame. At a plausible interaction rate that is a real
  byte budget over a WebSocket. Measure frames-per-second during actual typing against the fixture
  before choosing `everyNthFrame` and `quality`, and record the number the way plan 3a recorded the
  soft-404 cutoff of 16.
- **Plan 17:** how does candidate generation decide an id *"looks authored"*? §10.2 gives examples to
  reject — `:r1:`, `ember123`, long hex, digit-heavy — but not a rule. This is a pure function over a
  string and therefore the one part of the recorder that is cheaply and exhaustively testable; give
  it a table-driven test with every example from §10.2 plus the fixture's real ids.

## Calibration and execution

**`CLAUDE.md`'s "Plan calibration" section is the authority** and is not restated here: 150
verbatim-code lines per plan, ~120 lines per task as the tripwire, no per-plan total, preambles that
point rather than copy, and no editing a plan after it has executed.

**These four plans are written ahead of execution, and that is a deliberate departure** from the
Phase-2 and Phase-3 roadmaps' *"the next plan is only written after the previous one executes."*
The rule exists so that a plan is not written against a guessed data shape. Three things make the
risk acceptable here and they should be checked rather than assumed:

1. §10.3 pins `JourneyStep` field by field and §10.2 pins the six candidate strategies, so the
   schema the later plans consume comes from the **spec**, not from plan 14's execution.
2. The recorder's genuine unknowns are CDP unknowns, and executing plans 14 and 15 teaches nothing
   about CDP. They were retired by the spike instead — which is what the spike was for.
3. `CLAUDE.md` forbids editing a plan **after it has executed**. A plan written today and executed
   later may be amended freely in between, and plans 15–17 should be re-read against the tree
   immediately before they run.

The cost is real and is stated so it is not discovered: plans 15–17 carry no execution findings from
their predecessors. Where plan 14 learns something that changes them, **amend them before executing
them** — that is legal, and it is the mechanism this departure depends on.

`./mvnw test` is the pre-merge gate. Each plan should add **one** browser test class per
deliverable, per `CLAUDE.md`'s rule that a `@BeforeEach` which drives a browser costs one Chromium
sweep per test method. Plan 14's replay engine, plan 16's screencast bridge and plan 17's capture
script each need one. Everything else — the step model, candidate ranking, coverage arithmetic,
health arithmetic, id rejection — is a pure function over hand-built input and stays under `-Pfast`.

**The fixture site grows again, under Phase 3's rule.** It has no multi-step flow to record: the
contact form is one page, and a journey needs at least a form that leads somewhere. New fixture
pages are **served but not linked from `index.html`**, so `SetupProbeTest`'s candidate-set
assertions and the crawl-shaped assertions of Phases 1–3 do not move.
