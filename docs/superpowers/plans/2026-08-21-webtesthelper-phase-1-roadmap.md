# WebTestHelper Phase 1 — Plan Roadmap

**Date:** 2026-08-21
**Spec:** `docs/superpowers/specs/2026-08-21-webtesthelper-design.md` (§17 defines Phase 1)
**Status:** Plan 1 written and ready for execution; Plans 2–5 are scoped below and written when their predecessor is done.

---

## Why this exists

The first attempt was a single plan covering all of Phase 1. It reached 10,983 lines — 78% of
them full Java source — before running out of budget mid-task, never reaching the run history
UI or the SMTP work at all. The superseded file is kept for reference at
`2026-08-21-webtesthelper-phase-1-superseded.md`; its Tasks 1–4 (foundation, catalog,
`UrlNormalizer`, lease queue) were sound and were carried over into Plan 1.

Phase 1 as scoped in the spec is a complete crawl-and-check product. A single plan cannot
hold it. It is therefore delivered as **five sequential plans**, each producing working,
testable software on its own and each reviewed before the next is written.

## Calibration rules (apply to all five plans)

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

## The five plans

| # | File | Goal | Ends with | Depends on |
|---|---|---|---|---|
| 1 | `2026-08-21-webtesthelper-p1-foundation.md` | Postgres + Flyway, Modulith skeleton, `model` types, site catalog, `UrlNormalizer`, leased run queue, worker loop | A booting app where runs are queued, claimed with `SKIP LOCKED`, heartbeated, and completed | — |
| 2 | `2026-08-21-webtesthelper-p2-crawler.md` | Fixture site harness, `PageSnapshot` value types, thread-confined browser pool, batched crawl frontier, snapshot extraction | A manual run crawls the fixture site end-to-end, snapshots captured, frontier durable | 1 |
| 3 | `2026-08-21-webtesthelper-p3-checks.md` | Check SPI + registry + doc-enforcement test, all layer-1 page checks (incl. soft-404), asset verification on virtual threads, external URL cache, site checks | A run evaluates every layer-1 check against real snapshots and emits transient `CheckFinding`s | 2 |
| 4 | `2026-08-21-webtesthelper-p4-findings.md` | Fingerprinting + materialisation + site-wide promotion, coverage-scoped diff, pipeline assembly (crawl → verify → checks → re-verify → materialise → diff), baseline acceptance | Two runs against the fixture site produce a correct coverage-scoped diff; baseline works | 3 |
| 5 | `2026-08-21-webtesthelper-p5-web-smtp.md` | Security + German UI (run list, run detail, manual run, baseline button), `?` help affordances, SMTP settings + outbox + sender + test-mail | The usable product: schedule a manual run, read the diff, prove the mail relay | 4 |

## Deviations from the spec (carried over from the superseded plan)

| # | Deviation | Plan that applies it |
|---|---|---|
| D1 | A tenth `model` package holds shared value types; `checks`/`findings` depend only on it | 1 |
| D2 | Page checks run in one post-verification pass, not inline in the crawl loop | 3 |
| D3 | `CheckConfig` carries run-scoped facts (`RunFacts`), keeping the §7.3 signature | 3 |
| D4 | "target URL lowercased" = scheme + host only; path case preserved | 1 |
| D5 | Embedded help gets a minimal Phase 1 footprint (mechanism + 3 topics + test) | 5 |

## Execution

Each plan is executed with `superpowers:subagent-driven-development` (recommended) or
`executing-plans` immediately after it is written. The next plan is only written after the
previous one executes and its commits land — execution findings are fed back into the plan.
