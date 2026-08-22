# WebTestHelper Phase 1 — Plan Roadmap

**Date:** 2026-08-21
**Spec:** `docs/superpowers/specs/2026-08-21-webtesthelper-design.md` (§17 defines Phase 1)
**Status:** Plans 1, 2a, 2b and **3a** executed and reviewed against the spec; their commits are
on `main`. Plan 3 is split into 3a and 3b; **3b is written next**, from 3a's execution findings
below. Plans 4–5 are scoped below and written when their predecessor is done.

---

## Why this exists

The first attempt was a single plan covering all of Phase 1. It reached 10,983 lines — 78% of
them full Java source — before running out of budget mid-task, never reaching the run history
UI or the SMTP work at all. The superseded file is kept for reference at
`2026-08-21-webtesthelper-phase-1-superseded.md`; its Tasks 1–4 (foundation, catalog,
`UrlNormalizer`, lease queue) were sound and were carried over into Plan 1.

Phase 1 as scoped in the spec is a complete crawl-and-check product. A single plan cannot
hold it. It is therefore delivered as **five sequential plans**, each producing working,
testable software on its own and each reviewed before the next is written. Two of the five
turned out to be too large to execute in one sitting and were split in half — plan 2 at the
browser boundary, plan 3 at the network boundary — which is why the table below has seven rows.

## Calibration rules (apply to every plan)

Full inline code is reserved for the parts that are subtle and load-bearing; everything else
is specified at signature level with acceptance tests. Concretely:

- **Full code:** migrations, configuration, the test that pins a subtle contract, and the
  implementation of subtle algorithms (`UrlNormalizer`, lease SQL, thread confinement,
  fingerprinting, coverage-scoped diff).
- **Signatures + acceptance tests:** mechanical classes — JPA entities (exact field list and
  column mapping), simple services, controllers, templates.
- **Every step** still has exact file paths, exact commands, expected output, TDD order,
  and a commit. No placeholders, ever.
- Target: **each plan ≤ ~1,500 lines.** If a plan would exceed that, it is split again.

## The plans

| # | File | Goal | Ends with | Depends on |
|---|---|---|---|---|
| 1 | `2026-08-21-webtesthelper-p1-foundation.md` | Postgres + Flyway, Modulith skeleton, `model` types, site catalog, `UrlNormalizer`, leased run queue, worker loop | A booting app where runs are queued, claimed with `SKIP LOCKED`, heartbeated, and completed | — |
| 2a | `2026-08-21-webtesthelper-p2a-frontier.md` | Fixture site harness, `PageSnapshot` value family, batched crawl frontier | Every failure mode is served from loopback, snapshots are proven without a browser, the frontier is durable and concurrently claimable | 1 |
| 2b | `2026-08-21-webtesthelper-p2b-browser-crawl.md` | Admission rules (robots, patterns, sitemap), thread-confined browser pool, snapshot extraction, crawl pipeline + `RunExecutor` | A manual run crawls the fixture site end-to-end, snapshots captured, coverage recorded | 2a |
| 3a | `2026-08-21-webtesthelper-p3a-check-engine.md` | Check SPI + registry + §13.7 documentation gate, the eight page checks that need no network (incl. soft-404), the check pass in the run pipeline | A run crawls the fixture site and emits transient `CheckFinding`s for every failure mode a snapshot alone can see | 2b |
| 3b | `2026-08-21-webtesthelper-p3b-verification.md` | URL verification on virtual threads + external URL cache, `DEAD_LINK` and `FILE_DOWNLOAD`, the three site checks (TLS, hreflang, sitemap) | The full layer-1 catalog runs against real snapshots; external links cost one request across all sites | 3a |
| 4 | `2026-08-21-webtesthelper-p4-findings.md` | Fingerprinting + materialisation + site-wide promotion, coverage-scoped diff, pipeline assembly (crawl → verify → checks → re-verify → materialise → diff), baseline acceptance | Two runs against the fixture site produce a correct coverage-scoped diff; baseline works | 3b |
| 5 | `2026-08-21-webtesthelper-p5-web-smtp.md` | Security + German UI (run list, run detail, manual run, baseline button), `?` help affordances, SMTP settings + outbox + sender + test-mail | The usable product: schedule a manual run, read the diff, prove the mail relay | 4 |

## Deviations from the spec (carried over from the superseded plan)

| # | Deviation | Plan that applies it |
|---|---|---|
| D1 | A tenth `model` package holds shared value types; `checks`/`findings` depend only on it | 1 |
| D2 | Page checks run in one post-verification pass, not inline in the crawl loop | 3 |
| D3 | `CheckConfig` carries run-scoped facts (`RunFacts`), keeping the §7.3 signature | 3 |
| D4 | "target URL lowercased" = scheme + host only; path case preserved | 1 |
| D5 | Module direction is `runner → crawler`; `crawler` never imports `runner` | 2a |
| D6 | The fixture site is plain HTTP; `MIXED_CONTENT` is proven from a hand-built snapshot | 2a |
| D7 | Snapshots are memory-resident for a run, bounded by `CrawlBudget.maxPages` | 2a |
| D8 | Include/exclude pattern syntax: `*` any run, `?` one char, anchored, matched on `locationKey()` | 2b |
| D9 | robots.txt honours the `User-agent: *` group only | 2b |
| D10 | Non-HTML URLs never enter the frontier — they are assets, verified over HTTP in Plan 3 | 2b |
| D11 | The soft-404 probe navigates in a browser, so its fingerprint matches page text | 2b |
| D12 | The fixture's working media source is a generated WAV rather than a checked-in MP4 | 2a |
| D13 | Embedded help gets a minimal Phase 1 footprint (mechanism + 3 topics + test) | 5 |
| D14 | `CheckDescriptor` derives its three key names from the check type and adds `messageKeys()` | 3a |
| D15 | `CheckRegistry.standard()` is an explicit list, not component scanning; a build-failing coverage test replaces auto-registration | 3a |
| D16 | `CONSOLE_ERRORS` ignore patterns are case-insensitive substrings, not D8's URL globs | 3a |
| D17 | A cross-origin iframe is never reported empty — its text is unreadable, so "0 characters" says nothing | 3a |

D5–D12 were added when plan 2 was written; the help deviation moved from D5 to D13 so the
numbering stays chronological. Nothing referenced it yet. D14–D17 were added when plan 3a was
written.

## Execution

Each plan is executed with `superpowers:subagent-driven-development` (recommended) or
`executing-plans` immediately after it is written. The next plan is only written after the
previous one executes and its commits land — execution findings are fed back into the plan.

**On the line cap.** It is a guard against a plan running out of budget mid-execution, not an end
in itself. Plan 2 hit it and was split at the browser boundary: 2a needs no Chromium at all, 2b
needs it throughout. 2b still lands at ~2,200 lines because its acceptance tests are long, which
is the right place for length — each of its four tasks is ~500 lines and executes on its own.

Plan 3 is split the same way, at the **network** boundary. 3a's checks are pure functions over a
`PageSnapshot`: nothing they do reaches past the JVM, and every one of them is unit-tested from a
hand-built snapshot. 3b's checks cannot answer their question without fetching something — a
`HEAD` on a link target, a ranged `GET` for a PDF's magic bytes, a TLS handshake — which drags in
the virtual-thread pool, the per-host semaphore, the external URL cache and a migration. Splitting
there means 3a ships a complete, provably correct check engine before any of that lands.

Unlike 2a/2b, **3b is written only after 3a executes.** Plan 2 wrote both halves up front and
2b's verbatim code then needed four post-execution patches. 3a's execution findings feed 3b's
writer instead.

## Execution findings fed back from Plan 2a (for Plan 3's writer)

- **SimHash near bound is 12, not 6.** The plan-authored `<=6` threshold could not pass its own
  verbatim algorithm: the echo pair measures 8, longer echoes up to 12, unrelated pages >= 33.
  Plan 3 must set the soft-404 cutoff somewhere in the (12, 33) interval — re-measure against a
  page sharing the site's nav/footer with the 404 template before freezing it.
- **SimHash tokenization is ASCII-only** (`\W+` splits on umlauts: "gewünschte" -> `gew`+`nschte`).
  Fine today because thresholds are calibrated against this exact tokenizer; decide (fix or
  document as contract) before Plan 3 derives the cutoff.
- **Plan 2a's two verbatim-code typos were fixed during execution** (plan doc patched in follow-up
  commits): the PDF-trap content-type assertion (`contains` on an Optional is exact equality; the
  fixture serves `text/html; charset=utf-8`) and the SimHash threshold above.

## Execution findings fed back from Plan 2b (for Plan 3's writer)

- **The frontier claim now runs inside a CTE — keep it there.** The 2a shape
  `UPDATE … WHERE id IN (SELECT … LIMIT ? FOR UPDATE SKIP LOCKED)` is planner-unsafe: once the
  planner unnestes the subquery into a semi-join the LIMIT stops being applied (observed: a
  LIMIT-2 claim returned 4 rows mid-suite, breaking the maxPages budget test). The CTE keeps the
  LIMIT inside a locked, non-unnestable unit. Any future frontier SQL must not reintroduce the
  IN-subquery shape.
- **Sitemap entries are seeded at depth 1, not the plan-authored depth 0.** Depth 0 would let a
  `maxDepth=0` run crawl every sitemap page and fail the plan's own
  `maxDepthTruncates…` test once the fixture's real `sitemap.xml` is seeded.
- **The fixture's `/langsam` slot sleeps 20 s, not 5 s** (2a sized it for a 30 s timeout context;
  2b's tests use a 15 s navigation budget, and 5 s never triggered the timeout case).
- **`CrawlRunExecutorIT` is `CrawlRunExecutorTest`** — surefire's default includes match class
  names, and a `*IT` class is silently skipped by `./mvnw test`; the whole-plan acceptance test
  must run in the default suite. Any future `*IT` class needs an explicit surefire include.
- **Carry-overs for Plan 3:** `SiteResourceFetcher` hardcodes the User-Agent (wire the site's
  `effectiveUserAgent()` through when it grows into asset verification); `CrawlService.visit()`
  counts a page as visited *and* failed if the discovery enqueue throws (narrow, DB-failure-only);
  the soft-404 probe leaves one unreferenced screenshot per run in the run's artifact dir; the
  snapshot memory bound is soft in the all-unreachable corner (`room` counts reachable pages).
- **Soft-404 re-measure reminder (2a finding):** the 2b crawler tests still pass with the
  plan-authored `<=6` pairwise probe agreement because the fixture's 404 echo is an exact clone —
  this says nothing about the (12, 33) cutoff Plan 3 must pick.

## Post-review fixes to plan 2 (applied after the plan-2 review)

A review of plan 2 against the spec found two behavioural gaps and one documentation drift. All
three are fixed on `main`; the 2a and 2b plan files were patched so their verbatim code matches
the tree.

- **`reclaimStale` is now wired into the crawl.** 2a built and tested it; 2b's pipeline never
  called it. A run whose lease expires is re-queued and re-executed (§14), and the rows its dead
  worker held stayed `CLAIMED` forever — never visited, never pending, so `partialCoverage` came
  back `false` for a run that had silently missed pages, which is exactly the §6.4 failure the
  flag exists to prevent. `CrawlService` now reclaims before seeding, with a 10-minute stale
  timeout and 3 attempts before a URL is given up on.
- **Pinned key pages go through `UrlAdmission`.** The PULSE branch seeded them raw, so a pinned
  page bypassed robots, the exclude patterns and the asset guard — pinning is not the supported
  robots override, `respectRobots` is (§8). The base URL stays unfiltered on purpose: a site
  whose include patterns miss its own start page must still crawl something.
- **Per-page browser contexts are documented, not changed.** §5.4 says contexts are fresh per
  page *batch*; a batch fans out across every pool worker, so it cannot own one context, and
  per-page delivers what that paragraph asks for. `PageNavigator`'s javadoc now says so.
- **Still deferred to Plan 3, deliberately:** the four carry-overs listed above (hardcoded
  User-Agent, the visited-and-failed double count when discovery's enqueue throws, the probe's
  unreferenced screenshot, the soft snapshot-memory bound).

## Measurements taken while writing plan 3a

Plan 2a and 2b both left open questions that could only be answered by running Chromium against
the fixture site. They were measured before plan 3a was written, not guessed at, and the numbers
are now constants in that plan.

- **The soft-404 cutoff is 16**, closing the open item plan 2a raised. Measured against the
  fixture with a real browser: an exact clone of the not-found page sits at **0**, a not-found
  page that echoes the requested path at **8–12** (2a) to **20** (path spliced mid-sentence),
  and the closest *unrelated* real page — `/kontakt.html` — at **27**. `/leistungen.html` 31,
  `/` 33, `/medien.html` 35, `/en/index.html` 37. Sixteen sits inside 2a's required (12, 33)
  interval with margin on both sides, and is overridable per site.
- **A blocked iframe is not detectable from `FrameRef` alone.** The fixture's
  `X-Frame-Options: DENY` frame reports `loaded=true`, **`sameOrigin=false`**, `textLength=0` —
  indistinguishable from a healthy cross-origin embed. The usable signal is in
  `failedRequests`: a `document` request for the frame's URL failing with
  `net::ERR_BLOCKED_BY_RESPONSE`. This is why plan 3a reports emptiness only for same-origin
  frames (deviation D17); the naive rule would fire on every healthy YouTube and Maps embed on
  every page.
- **The Maps console error does reach the snapshot.** `ApiNotActivatedMapError` is logged by
  the iframe's own document and Playwright's page-level console event still delivers it, so
  `IFRAME_EMBED`'s Maps rule works from the snapshot as spec §7.1 describes.
- **A redirect loop arrives as an unreachable page**, not as a chain with a repeat:
  `/schleife/a` fails navigation with `net::ERR_TOO_MANY_REDIRECTS`. `REDIRECT_CHAIN` therefore
  owns that page and `PAGE_UNREACHABLE` steps aside, so one broken page yields one finding.
- **The fixture's redirect chain is exactly 3 hops** (`/weiter/1 → /weiter/2 → /weiter/3 →
  /ziel.html`, final status 200), which is also the default hop limit. The acceptance test
  drives it with a per-site `maxHops: 2` instead of lowering the default, which proves the
  per-site config path at the same time.

## Execution findings fed back from Plan 3a (for Plan 3b's writer)

3a executed with subagent-driven development: nine commits (four task, five review-fix), 208
browser-free / 251 total tests green, and the plan doc was patched so its verbatim code matches
the tree. The full list is in the p3a plan's "Execution findings" section; the headline items:

- **The blocked-iframe signal compares a failed document request's *final* URL against the
  frame's declared `src`.** A frame whose document redirects before being refused matches
  nothing. 3b/4 must record the frame's resolved URL or map failed requests back to the frame.
- **`IFRAME_EMBED` now requires `ERR_BLOCKED_BY_RESPONSE`** — a frame whose document 404s/500s
  is no longer reported as "blocked" and becomes `DEAD_LINK`'s job in 3b.
- **Coverage is now the three-way intersection** scope ∩ enabled ∩ implemented
  (`CheckEngine.coveredTypes()`). The run must never claim a check no implementation ran (§6.4);
  3b's site checks enter the column automatically once registered, and `saveCrawlOutcome`'s
  fifth `int` parameter stays the findings-count handoff.
- **hreflang input still does not exist** — `extract.js` collects nothing from `<head>`. 3b
  needs an `AlternateRef` value type, a `PageSnapshot` component, and `PageNavigator` mapping;
  budget a task.
- Decisions parked for 3b/4: maps-error attribution when several maps embeds share a page;
  source-less media findings merge to one fingerprint at materialisation; the loop finding's
  WARN severity vs the notification threshold; `CheckEvaluationException`'s embedded type name
  (§13.1 if surfaced in the UI).
- The Plan 2b carry-overs remain open (hardcoded User-Agent, visit double-count, orphan probe
  screenshot, soft memory bound).
