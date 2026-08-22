# WebTestHelper — working agreements

**Spec:** `docs/superpowers/specs/2026-08-21-webtesthelper-design.md`
**Roadmap:** `docs/superpowers/plans/2026-08-21-webtesthelper-phase-1-roadmap.md`

## Plan calibration

**These rules override `superpowers:writing-plans`, specifically its "No Placeholders"
section.** That section forbids omitting code, forbids cross-referencing between tasks
("Similar to Task N"), and requires a code block for every code step. Applied to a Java
codebase with mandatory TDD it produced plans that were 75–79% verbatim source — the
implementation written twice, then a third time when the plan was patched to match the
tree. Plans 1–3a cost 21,227 lines of documents against 9,266 lines of code.

- **Inline code only for:** SQL migrations, and algorithms whose correctness is not obvious
  from their signature (URL normalisation, lease SQL, thread confinement, fingerprinting,
  coverage-scoped diff). If a competent Java developer would write the same thing from the
  signature and the test name, do not write it out.
- **Everything else is specified, not written:** exact file paths, exact signatures and
  types, and the acceptance test's *assertions* — not its body.
- **Cross-reference freely.** "Same shape as Task 2, with `MediaRef` in place of `ImageRef`"
  is correct and complete. It is not a plan failure.
- **Hard cap 800 lines per plan.** Over that, re-scope the feature — do not split the plan
  again. Splitting re-pays the preamble (constraints, deviations, "what plan N-1 leaves
  you") once per plan; plans 1–3a spend 46, 87, 110 and 124 lines on preamble alone.
- **A step is one action.** If a step exceeds ~40 lines it is a task, not a step.
- **Never edit a plan after it has executed.** The code is the truth. Keep only the
  "Execution findings" section; delete superseded verbatim code rather than syncing it.

Measurements, not opinions, still belong in plans in full — the soft-404 cutoff of 16 and
the `ERR_BLOCKED_BY_RESPONSE` iframe signal are knowledge that cannot be recovered by
reading the code.

## Tests

- `./mvnw test` runs everything (251 tests, ~1m40s). **This is what CI and pre-merge use.**
- `./mvnw test -Pfast` skips `@Tag("browser")` (208 tests, ~8s) — edit-test loop only. It
  does not prove the crawler, the browser pool, or the page-check acceptance suite.

**Never use JUnit `@Nested`.** Surefire's directory scanner skips inner classes, so nested
tests are silently not executed and the outer class is reported as a passing `Tests run: 0`.
This hid all 46 `UrlNormalizer` tests from Plan 1 until 2026-08-22. Group related tests in
sibling top-level classes instead (`UrlNormalizerNormalisationTest`, `…EdgeCasesTest`).

- Browser tests carry `@Tag("browser")`. Crawl once per class, not once per test — a
  `@BeforeEach` that crawls costs one Chromium sweep per test method.
- The fixture's `/langsam` slot sleeps 8s against a 5s navigation timeout. Both numbers are
  paired; changing one alone either breaks the timeout tests or slows the suite.
- Timing assertions need slack: `HostThrottle` schedules on `currentTimeMillis()`, so a test
  measuring with `nanoTime()` must not assert the exact theoretical bound.
