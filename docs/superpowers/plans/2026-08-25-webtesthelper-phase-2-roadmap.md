# WebTestHelper Phase 2 — Plan Roadmap

**Date:** 2026-08-25
**Spec:** `docs/superpowers/specs/2026-08-21-webtesthelper-design.md` (§17 defines Phase 2)
**Predecessor:** `2026-08-21-webtesthelper-phase-1-roadmap.md` — Phase 1 complete, seven plans
executed, 558 tests green on `main`.

**Phase 2 in one line (§17):** *it runs itself and tells you.*

Phase 1 built a product a person operates: you press *Jetzt prüfen* and read the diff. Phase 2
removes the person from the loop — the runs fire on a clock, the noise can be silenced with a
reason and an expiry, the mail arrives on its own, and one screen answers "is anything wrong".

---

## Scope

§17 lists six items. Phase 1 parked four more directly in front of them, each named as a decision
in plan 5's "Deliberately not in this plan":

| From | Item |
|---|---|
| §17 | Tiered schedules |
| §17 | Digest content and notification policy |
| §17 | Triage UI with bulk actions |
| §17 | Mute rules |
| §17 | Dashboard |
| §17 | Guided site setup |
| §14 | Kill switches — global pause, per-site disable. Both mean "do not *schedule* this", and Phase 1 had no scheduler |
| §6.5 | Artifact pruning after the last 12 runs per site — a scheduled job, and Phase 2 owns the scheduler |
| §9 | Pinned key pages, auto-populated after the first full crawl. Required for correctness, not convenience — coverage-scoped resolution compares a run's visited URLs against a finding's location, so a pulse set that drifts makes findings flicker |
| §12 | The rest of the Settings screen: user management, per-site notification recipients, concurrency |

## The plans

Four plans, each producing working, testable software on its own, each reviewed before the next
is written.

| # | File | Goal | Ends with | Depends on |
|---|---|---|---|---|
| 6 | `…-p6-scheduling.md` | `scheduling` module, cron per site, the three tiers, kill switches, pinned key pages, artifact retention | The clock fires runs; an administrator can stop it; a pulse set exists to fire against | Phase 1 |
| 7 | `…-p7-triage-mutes.md` | Triage actions, bulk triage, the findings list, `MuteRule` with mandatory reason and expiry, expiry sweep | A colleague can silence a known-broken thing without going blind to it | 6 (the expiry sweep needs the scheduler) |
| 8 | `…-p8-digest.md` | Notification policy (§11.1), digest assembly and aggregation (§11.2), per-site recipients with global fallback, run-failure mail, monthly all-clear | Mail arrives on its own, one per window, and says what changed | 7 |
| 9 | `…-p9-dashboard-setup.md` | Dashboard (§12), guided site setup (§13.3), users and concurrency in Settings | The screen that answers "is anything wrong", and a site you configure by confirming a proposal | 6, 7 |

### Why triage sits before the digest

§17 lists the digest second and triage third. The order is reversed here for one reason: **the
digest's central question is answered by the triage model.** §11.1 mails on "new or regressed
`ERROR` findings", and `FindingStore.DIFF_SQL` evaluates `regressed_at_run = ?` *before*
`triage_status <> 'UNTRIAGED'` — so today a finding that is muted and then regresses lands in
`REGRESSED` and would be mailed. Whether that is correct is a mute-semantics decision, not a mail
decision. Writing plan 8 first means writing the notification predicate twice: once against a
triage model that does not exist, and again when it does.

Value is not lost by the swap. Plan 7 alone makes the Phase-1 report usable on a real site with
200 pre-existing findings; plan 8 turns the same predicate into mail.

### Why the dashboard sits last

It reads from all three: next scheduled run (6), open findings by severity and triage (7), and
the mail-health banner it takes over from the shared layout (8, D35). Built first it would be
three placeholder panels.

## Deviations from the spec

The Phase-1 table (D1–D37) applies unchanged and is **not** restated. Phase 2's deviations
continue the numbering. Each plan file carries the reasoning behind its own; this table is the
index.

| # | Deviation | Plan |
|---|---|---|
| D38 | `scheduling` depends on `runner`; a schedule is exactly one row per (site, scope), seeded lazily by the dispatcher and **never deleted, only disabled** | 6 |
| D39 | `next_fire_at` is a stored column computed in Java from the cron, not derived per tick | 6 |
| D40 | A schedule missed while the container was down fires **once**, then advances past *now*. No backlog replay | 6 |
| D41 | The kill switches gate scheduling only — a manual run on a paused or disabled site still runs | 6 |
| D42 | Artifact pruning computes the **delete** set from run rows; a directory with no run row is left alone | 6 |
| D43 | `spring.task.scheduling.pool.size=3` — three `@Scheduled` jobs now share Spring's scheduler, whose default pool is one thread | 6 |
| D44 | The dashboard keeps D29's HTMX poll; D29's "SSE arrives with the Phase-2 dashboard" is superseded | 9 |
| D45 | Pinned key pages are ranked by **distinct source pages**, not raw link count, and only pages the run fetched successfully are eligible | 6 |

D44 is decided here rather than in plan 9 because D29 made a promise this roadmap is the right
place to withdraw. §12 asks for SSE via HTMX's SSE extension, and D29 deferred it on the grounds
that a single live region did not justify an emitter registry fed from browser-worker threads and
torn down on every terminal path including the ones that throw. The dashboard does not change
that arithmetic: its live regions are a traffic-light grid and a run counter, both of which are
one indexed query, both of which tolerate a three-second lag, and neither of which can leak a
server-side resource when a viewer closes the tab. A poll that cannot leak beats an emitter that
can. Revisit if a screen ever needs sub-second latency; none does.

## What Phase 2 does not ship

Named so a reviewer can tell a gap from a decision.

- **§16's application image and the second compose service.** §16 still lists two deployment
  inputs as unconfirmed — which SMTP relay, and which mailbox serves IMAP verification — and the
  image pins the Chromium build to the Playwright version. That is a packaging task whose
  verification is a container build, unlike every task in this phase. It stays the first thing to
  build once the relay question is answered.
- **IMAP settings** (§12's Settings row) — they configure `SUBMIT_AND_VERIFY_MAIL`, which is
  Phase 3. Phase 2 configures what Phase 2 uses.
- **Everything in Phase 3 and 4** — the four interaction checks, the credential store, the
  recorder and journeys.
- **The `IFRAME_EMBED` canvas-paint gap, blocked-iframe URL attribution, Maps-error
  attribution, and D23's re-verification scope.** Inherited open from the plan-3 review and the
  Phase-1 review, unchanged. Each needs a measurement against a hand-built fixture page, which
  belongs in front of a plan, not inside one. D23 in particular is a behaviour change to the
  false-positive engine: §8 collects failures and re-verifies them "with fresh contexts", D23
  narrows that to an HTTP re-check of subjects the crawl never navigated, and
  `PAGE_UNREACHABLE`'s subject is always in `visitedUrls()` — so a page that timed out once gets
  no second chance, on a host where §16 names the OOM killer terminating Chromium mid-run as a
  real event.

## Calibration and execution

**`CLAUDE.md`'s "Plan calibration" section is the authority** and is not restated here: 150
verbatim-code lines per plan, ~120 lines per task as the tripwire, no per-plan total, preambles
that point rather than copy, and no editing a plan after it has executed.

Each plan is executed with `superpowers:subagent-driven-development` (recommended) or
`executing-plans` immediately after it is written. **The next plan is only written after the
previous one executes and its commits land** — execution findings feed the next writer. This is
the rule that stopped p2b needing four post-execution patches, and it is why this roadmap ships
with plan 6 alone.

`./mvnw test` (558 tests, ~1m25s) is the pre-merge gate. Plans 6–8 should add no browser test:
nothing in scheduling, triage, mutes or mail needs Chromium, and plan 6's one crawl-side
assertion extends `CrawlRunExecutorTest`'s existing `@BeforeAll` crawl rather than adding a class.
Plan 9's guided-setup probe is the one place a browser may be unavoidable — decide it there,
against a measurement, not now.

## Open questions to settle before the plan that needs them

- **Plan 7:** does a `MuteRule` suppress a finding at materialisation (it never enters the table)
  or at read (it enters, and is filtered into `KNOWN`)? Suppressing at materialisation loses the
  history that would prove the mute is still needed when it expires. Decide in plan 7; the
  evidence is in `FindingStore.DIFF_SQL`'s precedence order.
- **Plan 8:** what is the aggregation *window*? §11.2 says "one mail per window, per schedule
  tier". A window keyed on the tier plus the calendar day is the obvious reading; a window that
  waits for every site's run to finish is the correct one and needs a completion signal that does
  not exist yet.
- **Plan 9:** does the guided-setup probe need a browser? It detects forms, languages, videos,
  Maps embeds and PDFs on one page — `PageNavigator` already extracts all five into a
  `PageSnapshot`, so a single-page crawl may be the whole probe. Measure before planning.
