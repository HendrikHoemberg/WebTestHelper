# Plan 17 — Intent capture, ranked candidates, and the step editor

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development
> (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use
> checkbox (`- [ ]`) syntax for tracking.

**Goal:** Clicking and typing in a recording session produces a journey whose steps carry six ranked
locator candidates each, the employee edits it into shape, and plan 14's engine replays it green.

**Architecture:** A capture script injected into the recorded page reports *which element* was
touched and *what was done to it*; the server turns each report into ranked candidates and a step.
Raw CDP input tells you a click happened at (x, y) and nothing about intent — §10.1 — so the DOM has
to be asked.

**Spec:** `docs/superpowers/specs/2026-08-21-webtesthelper-design.md` — §10.1 (intent capture),
§10.2 (candidates), §10.4 (editing).
**Roadmap:** `2026-08-27-webtesthelper-phase-4-roadmap.md`. **Read its spike section before Task 1**
— it names a silent failure mode that will otherwise look exactly like a broken capture script.

**Depends on:** plan 16. **No new dependency.** This plan closes the loop: its acceptance test is
plan 14's replay engine running a journey nobody typed.

> ⚠ **Written before plans 14–16 executed.** Re-read against the tree and reconcile with their
> Execution findings — especially plan 14's `LocatorCandidate` and `JourneyStep` signatures, which
> this plan produces values for, and plan 16's session API.

**§10.5 still bounds this:** one tab, no uploads, no downloads, no drag-and-drop.

---

## The trap, stated once, because it costs a session

**`addInitScript` does not run on a page populated with `page.setContent()`.** The script never
executes, the binding never fires, nothing is captured, and no error is raised anywhere. `setContent`
is the natural way to write a three-line DOM fixture, and every test in this plan that reaches for it
will fail in a way indistinguishable from a bug in the capture script. **Navigate to a `data:` URL or
a fixture page instead.** Measured in the roadmap's spike; not guessed.

## File structure

| File | Responsibility |
|---|---|
| `recorder/capture.js` (resource) | Injected listener; reports touched elements |
| `recorder/CapturedEvent.java` | One report: kind, tag, attributes, role, name, label, id, text |
| `recorder/IntentCapture.java` | Installs the script and the binding; collects events |
| `model/AuthoredId.java` | §10.2's "does this id look authored?" rule |
| `recorder/CandidateBuilder.java` | `CapturedEvent` → ranked `List<LocatorCandidate>` |
| `recorder/StepBuilder.java` | `List<CapturedEvent>` → `List<JourneyStep>` |
| `recorder/RecorderController.java` | Save session as a journey |
| `web/JourneyEditController.java` + templates | §10.4's editor |

---

### Task 1: Asking the page what was touched

**Files:** Create `recorder/capture.js`, `recorder/CapturedEvent.java`, `recorder/IntentCapture.java`.
Test: `…/recorder/IntentCaptureTest.java` — `@Tag("browser")`, one context for the class.

**Produces:** `CapturedEvent(EventKind kind, String tagName, String id, String testId, String role,
String accessibleName, String labelText, String textContent, String value, String cssPath)` with
`EventKind { CLICK, INPUT, CHANGE, SUBMIT }`; `IntentCapture.install(BrowserContext)` and
`drain()` → `List<CapturedEvent>`.

- [ ] **Step 1: Write the failing tests** — against a `data:` URL or the fixture, **never
  `setContent`** (see above). Clicking a button reports one `CLICK` naming its tag, id, test id,
  role and accessible name. Typing into an input reports `INPUT` with the value. Selecting an option
  reports `CHANGE`. Submitting reports `SUBMIT`. The listener is **capture-phase**, so a click on a
  child of a button reports the element the page would act on, not the text node. The binding
  survives a navigation — click on page one, navigate, click on page two, drain reports both.

- [ ] **Step 2: Run and watch them fail.**

- [ ] **Step 3: Implement.** `exposeBinding` for the callback, `addInitScript` for the listener,
  installed on the **context** so it applies to every page. The script reads attributes and computes
  a scoped CSS path; it makes no decisions — ranking is Java's job, where it is testable without a
  browser.

- [ ] **Step 4: Run to green.**

- [ ] **Step 5: Commit.** `feat(recorder): ask the page which element the click was meant for`

**Why the value is captured but must never be stored as typed.** An `INPUT` on a password field
carries a real password. `StepBuilder` (Task 4) writes `{{cred.…}}` or an empty value for password
inputs and never the captured text; §10.4 is explicit that credentials are *"never in the step
JSON"*. Task 4 owns that rule and tests it.

---

### Task 2: Which ids can be trusted

**Files:** Create `model/AuthoredId.java`. Test: `…/model/AuthoredIdTest.java` (`-Pfast`).

**Produces:** `AuthoredId.looksAuthored(String id)` → `boolean`.

§10.2 gives examples to reject — `:r1:`, `ember123`, long hex, digit-heavy — but no rule. This is a
pure function over a string and therefore the one part of the recorder that is exhaustively testable
for nothing, which is the argument for giving it its own task and its own file.

```
reject when any holds:
  blank, or longer than 64 chars
  contains a character outside [A-Za-z0-9_-]      // React's :r1:, Vue's data-v- suffixes
  matches ^[0-9a-f]{8,}$ (case-insensitive)       // long hex
  digits are more than half the characters        // ember123, id-4815162342
  matches a known generator prefix: ember, react-, ng-, mui-, radix-, headlessui-, svelte-
otherwise authored
```

- [ ] **Step 1: Write the failing tests.** Table-driven, and the table must contain **every example
  §10.2 names** plus every real id in the fixture site, asserted in the direction each belongs. Add
  `kontakt-formular` and `absenden` as accepted, `:r7:` (planted in plan 14's fixture) rejected,
  `a3f9b2c81d` rejected, `x1` accepted — short and digit-bearing but not generated, the case a
  digit-ratio rule alone gets wrong.

- [ ] **Step 2: Run and watch them fail.**

- [ ] **Step 3: Implement.**

- [ ] **Step 4: Run to green.**

- [ ] **Step 5: Commit.** `feat(model): an id a framework invented is not an id worth remembering`

**Why in `model` and not `recorder`.** It is a fact about ids, it needs nothing but a string, and
plan 14's `LocatorResolver` may later want to distrust a stored `ID` candidate the same way. `model`
is the module every other one may see (§5.1).

---

### Task 3: Six candidates, ranked

**Files:** Create `recorder/CandidateBuilder.java`. Test: `…/recorder/CandidateBuilderTest.java`
(`-Pfast` — a `CapturedEvent` is a record and needs no browser).

**Consumes:** Task 1's `CapturedEvent`, Task 2's `AuthoredId`. **Produces:**
`CandidateBuilder.build(CapturedEvent event)` → `List<LocatorCandidate>`, sorted, never empty.

- [ ] **Step 1: Write the failing tests.** An event with a test id, a role, a label and an authored
  id yields four candidates in §10.2's order with `TEST_ID` first. An event whose id fails
  `AuthoredId` yields **no `ID` candidate at all** — not a low-ranked one; a candidate that is known
  to break is worse than one fewer candidate. An event with only a CSS path yields exactly one
  candidate. Text longer than a sane bound does not become a `TEXT` candidate — a paragraph is not a
  selector. The list is never empty: a `CSS` candidate is always available, which is what makes it
  §10.2's "last resort".

- [ ] **Step 2: Run and watch them fail.**

- [ ] **Step 3: Implement.** Strategy order comes from `LocatorStrategy`'s declaration order (plan 14,
  Task 1); do not re-encode it here.

- [ ] **Step 4: Run to green.**

- [ ] **Step 5: Commit.** `feat(recorder): remember six ways to find the thing, best first`

---

### Task 4: Events become steps

**Files:** Create `recorder/StepBuilder.java`. Test: `…/recorder/StepBuilderTest.java` (`-Pfast`).

**Produces:** `StepBuilder.build(List<CapturedEvent> events, String startUrl)` →
`List<JourneyStep>`.

- [ ] **Step 1: Write the failing tests.** A click becomes `CLICK`; typing becomes `FILL` carrying
  the value; a select becomes `SELECT`; the list starts with a `GOTO` for `startUrl`. Then the
  editorial rules, which are the actual content of this task:
  - **Consecutive `INPUT` events on the same element collapse into one `FILL`** with the final
    value. Keystroke-per-step is the classic recorder failure and §10.4's *"recorders always capture
    some junk"* is the spec acknowledging it.
  - **A `SUBMIT` following a click on that form's submit button does not add a second step** — one
    user action, one step.
  - **A password input never yields a step carrying the typed text.** Assert the built step's value
    is empty or a `{{cred.…}}` template, and assert the plaintext appears **nowhere** in the built
    list. §10.4: never in the step JSON.
  - Every step gets a fresh `UUID` and a dense ordinal.

- [ ] **Step 2: Run and watch them fail.**

- [ ] **Step 3: Implement.**

- [ ] **Step 4: Run to green.**

- [ ] **Step 5: Commit.** `feat(recorder): one thing the person did is one step`

---

### Task 5: The editor

**Files:** Create `web/JourneyEditController.java`, `templates/journey/edit.html`. Modify
`recorder/RecorderController.java` (save session → journey), `messages.properties`, `help/reisen.md`.
Test: `…/web/JourneyEditControllerTest.java` (`-Pfast`).

§10.4's list: delete junk steps, reorder, edit values, mark optional, add assertions by picking an
element and choosing an assertion type. **Step UUIDs survive all of it.**

- [ ] **Step 1: Write the failing tests.** Deleting step 3 of 5 leaves four steps with dense ordinals
  and **the other four UUIDs unchanged** — plan 15's finding identity is built on that and it is the
  property a re-numbering implementation quietly breaks. Reordering changes ordinals, not UUIDs.
  Editing a value persists it. Marking a step optional persists it. Adding an assertion of each of
  the four `AssertionType`s persists it. A value edited to `{{cred.login.password}}` is stored as
  the template and the edit screen **renders the template back**, never a resolved secret — the same
  assertion plan 14 Task 7 makes for the read-only screen, made again here because this screen has a
  form and forms round-trip values.

- [ ] **Step 2: Run and watch them fail.**

- [ ] **Step 3: Implement.** Reuse `JourneyService.update`'s renumbering from plan 14 Task 2 rather
  than renumbering in the controller.

- [ ] **Step 4: Run to green,** then full `./mvnw test` for §13.7's gates.

- [ ] **Step 5: Commit.** `feat(web): fix up a recording before trusting it`

---

### Task 6: Record it, then replay it

**Files:** Create `…/recorder/RecordToReplayAcceptanceTest.java` — `@Tag("browser")`, one session for
the class.

This is the phase's closing test and the only one that proves the recorder produced something
*good*: a journey nobody typed, replayed by plan 14's engine.

- [ ] **Step 1: Write the failing acceptance test.** Open a recording session on the fixture's
  `reise/start.html`. Drive it **through plan 16's input path**, not through Playwright's
  `page.click` — the point is that CDP input reaches the capture script. Click the link, fill the
  name field, select an option, submit. Save the session as a journey. Then assert:
  (a) the saved journey has one `GOTO` and one step per user action, with no keystroke steps;
  (b) each step carries at least two candidates, because plan 14's fixture gives every element two
  handles;
  (c) **`JourneyReplayer.replay` on the saved journey returns `PASSED`.**
  Assertion (c) is the deliverable. (a) and (b) are diagnostics that tell you *why* when it fails.

- [ ] **Step 2: Run and watch it fail.**

- [ ] **Step 3: Implement whatever it exposes.**

- [ ] **Step 4: Run to green,** then full `./mvnw test`.

- [ ] **Step 5: Commit.** `test(recorder): a journey nobody typed replays green`

---

## Self-review checklist for the executing agent

- `./mvnw test` green.
- `git grep -n 'setContent' -- 'src/test/java/**/recorder'` returns nothing. See the trap.
- **A typed password appears nowhere in a saved journey's JSON.** Grep the persisted row in Task 6's
  test, not just the built list — the two can differ if serialisation adds a field.
- `AuthoredIdTest`'s table contains every example §10.2 names.
- Task 6 (c) genuinely fails if `CandidateBuilder` is made to emit only the `CSS` candidate. If it
  still passes, the fixture's CSS paths are too stable to prove anything and the test needs a
  redesign, not a pass.

## Execution findings

*(Filled in during execution. Record any §10.2 candidate strategy that turned out not to be
derivable from a `CapturedEvent`, and the id-rejection rule as finally shipped. Do not edit the tasks
above once they have run.)*
