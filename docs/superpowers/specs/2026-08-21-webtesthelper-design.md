# WebTestHelper — Architecture Design

**Date:** 2026-08-21
**Status:** Approved
**Scope:** Architecture for all four delivery phases. Phase 1 gets its own implementation plan; later phases get their own plans against this document.

---

## 1. Purpose

The company hosts 20–50 customer websites. Each is checked manually every month for dead links, broken images, non-playing videos, failing contact forms, unreachable PDF downloads, broken language switching, and misconfigured Google Maps embeds. The work is tedious and unreliable because humans do it.

WebTestHelper is a self-hosted web application that lets non-technical employees configure these checks per site, runs them on a schedule, and reports what changed.

**The design constraint that shapes everything:** employees do not want to author tests. They want to be told what is broken. So the system pushes as much as possible into zero-configuration checking and falls back to authored tests only for the genuinely site-specific remainder.

## 2. Goals

- Replace the manual monthly checklist with scheduled automated runs.
- Configuration by non-technical staff, without selectors, scripts, or jargon.
- Reports that surface **what changed**, not what is merely still true.
- Low false-positive rate — trust is the product.
- Self-hosted, single container, no external services required.

## 3. Non-goals

- Visual / layout regression testing (screenshot diffing). Large subsystem, poor noise profile, nothing on the checklist needs it. Screenshots are captured as evidence only.
- Customer-facing reports. Internal audience only.
- Uptime monitoring at minute granularity. Different problem, different tool.
- Webhook-triggered runs. Explicitly out of scope; `Run.trigger` remains an enum so it can be added later.
- Load, performance, accessibility, or SEO auditing.

## 4. Decisions

| Decision | Choice | Rationale |
|---|---|---|
| Configuration model | Three layers: crawl-based passive checks, heuristic interaction checks, authored journeys | Maximises zero-config coverage; authoring is the fallback, not the default |
| Scale target | 20–50 sites, up to several hundred pages each | Single host, bounded worker pools |
| Finding model | Stateful findings with run-to-run diff | A run that finds nothing new must cost zero attention |
| Contact form testing | Configurable per site: no-submit / submit / submit + IMAP verification | Not every client will permit a BCC; default to the safest mode |
| Report audience | Internal only | No second presentation layer, no branding, no sanitisation |
| Journey recording | Server-side remote browser streamed into the webapp | Nothing to install; records in the same clean browser that replays |
| Process topology | Modular monolith, Spring Boot, Playwright for Java | Right size for 50 sites; runner sits behind an interface so extraction stays a refactor |
| Deployment | Docker on Linux, local user accounts | Browser binaries and system libraries stay in sync |
| Schedule shape | Tiered: daily pulse / weekly full / monthly deep | Different checks have different costs and different side effects |
| Triggers | Cron plus manual "Run now" | Webhook explicitly deferred |
| Storage | SQLite (WAL) via Spring Data JPA | Sufficient at this scale; Postgres is a driver and dialect swap |
| UI | Thymeleaf + HTMX, SSE for progress | No SPA build chain; must still build in three years |
| Locale | German only | The audience is internal colleagues |

## 5. Architecture

### 5.1 Modules

Nine packages with enforced dependency direction. Spring Modulith fails the build when a boundary is crossed.

```
web          Thymeleaf + HTMX controllers, SSE, recorder WebSocket
catalog      sites, check profiles, per-site settings, credentials
scheduling   cron per site, tier scopes, run creation
runner       job queue, lease management, worker pool, browser pool
crawler      URL frontier, robots.txt, dedupe, budgets
checks       check implementations (SPI)
findings     fingerprinting, lifecycle, run-to-run diff, coverage
reporting    digest assembly, outbox, mail sender, dashboard queries
recorder     CDP screencast, input forwarding, step capture
```

`checks` and `findings` depend only on their own value types — no Spring, no database, no browser. These are the two most logic-dense parts of the system and they must be unit-testable in isolation.

### 5.2 Central design decision: navigate once, check many

A page-scoped check never drives the browser. The crawler visits each URL exactly once and produces an immutable `PageSnapshot`:

- final URL after redirects, HTTP status, response headers, timing
- all links (internal and external, with their anchor context)
- all images: `<img>`, `srcset`, CSS `background-image`
- media elements and their sources
- iframes and their computed state
- forms and their fields
- console messages, failed network requests
- screenshot path

Every page check is then a pure function: `PageSnapshot -> List<Finding>`.

Consequences:

- ~15,000 page visits per full sweep becomes affordable — one navigation, many checks.
- Adding a check costs one class and a unit test built from a hand-constructed snapshot. No browser in the test.
- Checks cannot interfere with each other or with crawl ordering.

**Three kinds of check exist**, and only the first gets this treatment:

| Kind | Input | Runs | Testable without a browser |
|---|---|---|---|
| **Page check** | `PageSnapshot` | per page, during crawl | yes — pure function |
| **Site check** | all snapshots from the run + site config | once, after crawl | yes — pure function |
| **Interaction check** | a live `Page` in a fresh context | once per target, after crawl | no — needs the fixture site |

Site checks need cross-page knowledge (hreflang reciprocity, sitemap consistency, TLS). Interaction checks necessarily drive the browser — accepting a cookie banner or submitting a form cannot be done from a snapshot. Keeping them as distinct SPIs is what stops interaction concerns from leaking into the pure page-check path, which is the bulk of the catalog.

### 5.3 Execution pipeline

```
schedule fires (or "Run now")
  └─ Run(QUEUED) → worker acquires lease
       ├─ seed frontier: start URLs + sitemap.xml
       ├─ soft-404 probe: fetch {baseUrl}/{random-uuid}, retain fingerprint
       ├─ crawl loop  ── browser pool (N contexts)
       │    navigate → PageSnapshot → page-scoped checks
       │    ├─ new internal URLs → frontier
       │    └─ external and asset URLs → dedup'd set
       ├─ asset verification ── HTTP pool (M workers)
       │    HEAD, fall back to ranged GET
       ├─ site-scoped checks (language coverage, sitemap consistency, TLS)
       ├─ journeys, each in a fresh context
       ├─ end-of-run re-verification of every failure
       └─ materialise: fingerprint → diff against previous run → report
```

Two pools with opposite cost profiles. A browser context is expensive and slow; an HTTP HEAD is neither. Verifying 4,000 external links through a browser takes hours; through an HTTP pool it takes minutes. Both are rate-limited per host.

**One run at a time per site**, so results stay coherent and crawls stay polite.

### 5.4 Concurrency and pools

| Pool | Default size | Purpose |
|---|---|---|
| Browser contexts (runner) | 4 | Page navigation and snapshot capture |
| HTTP asset checkers | 16 | Link, image, and file verification |
| Browser contexts (recorder) | 2, hard cap | Interactive recording sessions |

The recorder pool is **separate from the runner pool**. An idle recording session holds a context for minutes; sharing the runner's pool would let two people recording halve crawl throughput.

Contexts are recycled every N pages to bound memory. Each context starts with fresh cookies so runs are reproducible.

## 6. Domain model

### 6.1 Entities

```
Site ──┬── SiteCheckSetting   (check type, enabled, config, severity override)
       ├── Schedule           (cron, timezone, scope, enabled)
       ├── Journey ── JourneyStep
       ├── Credential         (encrypted)
       ├── NotificationRecipient
       └── Run ──┬── CrawlQueueItem
                 └── FindingOccurrence ── Finding

MuteRule       (site or global, check type, URL pattern, reason, expiry)
Notification   (outbox row)
Setting        (SMTP, base URL, IMAP, concurrency, redirect-all-mail)
User
```

`Site` carries: base URL, crawl budget (max pages, max depth, max duration), include/exclude patterns, robots policy, form-test mode, User-Agent override.

`Run` carries: trigger (`SCHEDULED` | `MANUAL`), scope, status (`QUEUED` | `RUNNING` | `COMPLETED` | `FAILED` | `CANCELLED`), timing, statistics, error, **and coverage** (see 6.4).

### 6.2 Finding identity

```
fingerprint = sha256(siteId, checkType, subjectKey, locationKey)
```

**`subjectKey`** is the broken thing, aggressively normalised. For a dead link: target URL lowercased, default port dropped, fragment dropped, query parameters sorted, tracking parameters (`utm_*` and similar) stripped. Without this normalisation the same dead link fingerprints differently when found on two pages and the diff is worthless.

**`locationKey`** is where it was found, and it is the part that is easy to get wrong.

- Naive choice (exact page URL): a broken footer image becomes 312 separate findings and muting is useless.
- Omitting it: the finding cannot say where.

**Two-tier resolution.** If a subject appears on more than `SITE_WIDE_THRESHOLD` pages (default 5), the finding is site-wide and `locationKey = "*"`. It reads: *"logo-x.png returns 404 — on 312 pages."* Otherwise `locationKey` is the normalised page path.

Occurrences always record every exact page, so detail is never lost.

This is why fingerprinting is a **post-crawl materialisation step**: you cannot know a subject is site-wide until the crawl is complete.

For journeys: `locationKey` is the journey, `subjectKey` is the step's stable UUID, assigned at record time and preserved through edits and re-recording — so re-recording does not orphan triage history.

### 6.3 Lifecycle: two orthogonal axes

```
observed (system-owned)   ACTIVE | RESOLVED
triage   (human-owned)    UNTRIAGED | ACKNOWLEDGED | MUTED | WONT_FIX
```

Collapsing these into a single enum causes the classic failure where acknowledging a finding erases the fact that it is still broken.

Findings additionally carry `firstSeenRun`, `lastSeenRun`, `resolvedAtRun`, `occurrenceCount`.

Report sections derive from the combination:

| Section | Derivation |
|---|---|
| **New** | ACTIVE, first seen this run |
| **Regressed** | ACTIVE, was RESOLVED in an earlier run |
| **Fixed** | flipped to RESOLVED this run |
| **Still open** | ACTIVE, UNTRIAGED, older than this run |
| **Known** | ACTIVE, triaged to ACKNOWLEDGED / MUTED / WONT_FIX |

**Muting requires a reason and an expiry.** Both mandatory. Indefinite mutes are how monitoring goes blind.

`MuteRule` handles patterns alongside per-finding mutes — *"all dead links to linkedin.com, 90 days, reason: rate-limits our checker"*.

### 6.4 Coverage — load-bearing

A run records what it actually covered: the set of check types it ran and the set of URLs it visited.

**Resolution applies only within coverage.** A finding outside a run's coverage is left untouched.

Without this rule, a daily pulse visiting 10 pages marks the broken image on `/leistungen` as fixed; the weekly full crawl then reports it as regressed. Every week, forever.

The same rule protects budget-capped runs: a run that hits its page or duration budget completes with **partial coverage** and resolves nothing it did not reach.

This gets an explicit test.

### 6.5 Storage

SQLite in WAL mode with `busy_timeout` set. SQLite permits one writer, so workers accumulate a run's results in memory and persist once at materialisation.

Repositories stay vanilla Spring Data — Postgres is a driver and dialect change, not a rewrite.

Artifacts (screenshots, HTML, HAR) go to disk under `/data/artifacts/{runId}/`, pruned after the last 12 runs per site. Findings are small and kept indefinitely.

## 7. Check catalog

### 7.1 Layer 1 — passive

| Check | Kind | Verifies |
|---|---|---|
| `PAGE_STATUS` | page | 2xx, plus soft-404 detection |
| `DEAD_LINK` | page | internal and external targets resolve |
| `REDIRECT_CHAIN` | page | no loops, no long hop chains |
| `IMAGE_BROKEN` | page | `<img>`, `srcset`, CSS `background-image` render (`naturalWidth > 0`, not merely status 200) |
| `FILE_DOWNLOAD` | page | status 200 **and** content-type matches **and** non-trivial size **and** `%PDF` magic bytes |
| `MEDIA_PLAYABLE` | page | `<video>` / `<audio>` sources resolve, metadata loads (`readyState >= 1`, `duration > 0`) |
| `IFRAME_EMBED` | page | not blocked by X-Frame-Options / CSP, renders non-empty content |
| `MIXED_CONTENT` | page | no http subresources on an https page |
| `CONSOLE_ERRORS` | page | no uncaught JS errors |
| `TLS_CERT` | site | valid, not expiring within N days |
| `HREFLANG` | site | language alternates resolve and reciprocate across the crawled set |
| `SITEMAP_CONSISTENCY` | site | sitemap entries resolve, and crawled pages are not missing from it |

Three implementations where the obvious approach is the useless one:

**Google Maps.** The real-world failure is billing or API key: a grey map with a *"For development purposes only"* watermark and an `ApiNotActivatedMapError` console entry. "The iframe loaded" passes that. `IFRAME_EMBED` special-cases Maps — it asserts the map canvas painted and scans the console for the provider's error codes.

**PDF downloads.** A link returning 200 `text/html` is a login wall or an error page, not a PDF. Hence content-type plus magic bytes.

**Soft 404s.** At crawl start the runner fetches `{baseUrl}/{random-uuid}` to learn what the site's not-found page looks like. Any 200 response closely resembling that probe is a soft 404.

### 7.2 Layer 2 — heuristic interaction

All four are `InteractionCheck`s: they drive a live `Page` in a fresh context after the crawl, using the crawl's snapshots to decide which pages to target.

| Check | Behaviour |
|---|---|
| `COOKIE_BANNER` | detect and accept — it gates everything else; report if undismissable |
| `CONTACT_FORM` | locate form, classify each field from type / name / label / autocomplete, fill plausibly, then act per the site's configured mode |
| `LANGUAGE_SWITCHER` | for each locale: URL changed **and** `<html lang>` changed **and** visible text actually differs |
| `BUTTON_REACHABILITY` | every nav item and in-page button navigates somewhere valid or produces a visible DOM change |

`COOKIE_BANNER` runs first and its accepted state is reused by the others in the same context, since a banner overlay blocks everything behind it.

`LANGUAGE_SWITCHER` asserts all three conditions because the real-world failure is a switcher that changes the URL and serves German anyway.

**Contact form modes**, configured per site, defaulting to the safest:

1. `NO_SUBMIT` — fill, trigger validation, verify valid input is accepted and invalid input rejected, stop before submitting. Zero side effects. *(default for new sites)*
2. `SUBMIT` — submit with a clearly marked test message, assert a success indicator.
3. `SUBMIT_AND_VERIFY_MAIL` — submit with a unique token in the message body, then poll a configured IMAP mailbox for that token. The only mode that catches the most common real failure: the form shows "Danke" and delivers nothing.

### 7.3 Check SPI

A common descriptor, three evaluation contracts (see §5.2):

```java
interface CheckDescriptor {
    CheckType type();
    String titleKey();
    String descriptionKey();
    String remediationKey();
    Severity defaultSeverity();
}

interface PageCheck extends CheckDescriptor {
    List<Finding> evaluate(PageSnapshot snapshot, CheckConfig config);
}

interface SiteCheck extends CheckDescriptor {
    List<Finding> evaluate(RunSnapshots snapshots, SiteContext site, CheckConfig config);
}

interface InteractionCheck extends CheckDescriptor {
    List<Finding> evaluate(Page page, SiteContext site, CheckConfig config);
}
```

Implementations are registered automatically by their interface. Adding the twelfth check must not require touching the runner.

The shared `CheckDescriptor` is what the documentation enforcement test in §13.7 walks — every check of every kind must carry its three message keys.

## 8. False-positive control

Trust is the product. Four mechanisms:

**End-of-run re-verification.** Failures are not findings. They are collected, then re-verified in a second pass at the end of the run with fresh contexts and backoff. Only survivors become findings. This eliminates transient 5xx noise without waiting a month for a second run to confirm.

**`UNVERIFIABLE` is a distinct outcome from `DEAD`.** A 403, 429, or 999 from LinkedIn, Instagram, or a Cloudflare-fronted host means *they blocked our checker*, not *your link is broken*. Reported separately, `INFO` severity by default.

**Evidence on every finding** — screenshot, exact request and response, console excerpt. An employee must be able to judge a finding in five seconds rather than re-checking it by hand, which is the tedium being eliminated.

**Per-check severity with per-site override.** `ERROR` / `WARN` / `INFO`. Only `ERROR` triggers notification by default.

**Politeness:** robots.txt respected by default with a per-site override (the company hosts these sites), per-host concurrency and delay caps, and an identifying User-Agent so the company's own access logs stay greppable.

## 9. Scheduling

`Schedule` is a set per site. Each entry carries a cron expression, a timezone, and a **scope** determining which checks run and how deep the crawl goes.

| Tier | Default cron | Scope | Cost per site |
|---|---|---|---|
| **Pulse** | daily 03:00 | the site's pinned key-page set, page checks only, no submits | ~60–90 s |
| **Full** | weekly, Sun 03:00 | full crawl, all page, site, and interaction checks, no submits | ~15 min |
| **Deep** | monthly, 1st 03:00 | everything: form submission, IMAP verification, journeys | ~20 min |

**The pulse key-page set is pinned per site, not recomputed each run.** It is stored as an explicit URL list on the `Site`, auto-populated after the first full crawl from the most-linked navigation targets (default 10, plus the homepage) and editable by hand.

Pinning it is not a convenience — it is required for correctness. Coverage-based resolution (§6.4) compares the URLs a run visited against a finding's location, so a pulse set that drifted from run to run would make findings flicker between resolved and regressed for reasons no one could explain.

Capacity across 50 sites with 4 browser workers: pulse ≈ 20 minutes, weekly full ≈ 3 hours in a Sunday-night window, monthly deep somewhat more. Comfortable on one host.

Defaults are applied when a site is created; every tier is overridable per site, and any tier can be disabled.

**Why not everything daily:** side effects (daily form submission is ~30 test mails per month into a client inbox), load on company-owned hosting, and third-party rate limits that convert real link checks into `UNVERIFIABLE` noise.

**Notification frequency is decoupled from run frequency** (see §11).

## 10. Recorder and journeys

### 10.1 Recording

The employee opens "Record" and the server allocates a `RecordingSession`: a Chromium context from the recorder pool, 15-minute idle timeout, two concurrent sessions maximum.

**Live view:** a CDP session with `Page.startScreencast` emits JPEG frames; the server pushes them over a WebSocket to a `<canvas>`. Each frame is acknowledged with `Page.screencastFrameAck`.

**Input:** canvas clicks and keystrokes travel back over the same WebSocket and are dispatched via `Input.dispatchMouseEvent` / `Input.dispatchKeyEvent`. Coordinates scale between canvas and viewport.

**Intent capture:** raw input events do not convey intent, so a capture script is injected via `addInitScript`. It listens for click / input / change / submit in the capture phase and reports the touched element back through an exposed binding.

### 10.2 Selectors — ranked candidates, not one selector

Recorded tests get abandoned because they break silently on the next redesign. Each captured element stores **multiple ranked locator candidates**:

```
1. data-testid / data-test / data-cy        most stable
2. role + accessible name                    getByRole('button', name='Absenden')
3. associated label text                     getByLabel('E-Mail')
4. id — only if it looks authored; reject :r1:, ember123, long hex, digit-heavy
5. text content
6. scoped CSS path                           last resort
```

At replay, candidates are tried in order. **If the primary fails but a fallback succeeds, the step passes and the run emits a `SELECTOR_DRIFT` warning.** The team learns the site was redesigned before the journey hard-fails, instead of after.

### 10.3 Step model

```
JourneyStep {
  id                 UUID, stable across edits and re-recording
  ordinal            int
  action             GOTO | CLICK | FILL | SELECT | PRESS | HOVER | WAIT_FOR | ASSERT
  locatorCandidates  [{ strategy, value, rank }]
  value              String, may reference {{cred.<name>.<field>}}
  assertion          { type, expected }   // text-contains | visible | url-matches | count
  optional           boolean
  timeoutMs          int
}
```

**`optional` matters.** The cookie banner is the motivating case: it may not appear on a second visit or in an A/B variant, and a journey failing because a banner *was not* there is worse than no journey.

### 10.4 Editing, credentials, health

After recording, the employee sees the step list and can delete junk steps (recorders always capture some), reorder, edit values, mark steps optional, and add assertions by picking an element and choosing an assertion type. Step UUIDs survive all of this.

**Credentials** live in an encrypted per-site store (AES-GCM, key from `/data/keyfile`), referenced as `{{cred.login.password}}`. Never in the step JSON, never rendered back to the UI, redacted from logs.

**Journey health** is tracked explicitly: last success, consecutive failures, drift count. A journey failing repeatedly with drift is flagged *"needs re-recording"* in the UI rather than generating the same finding every night.

**Replay** runs each journey in a fresh context with Playwright's auto-waiting, a per-step timeout, and a screenshot plus trace on failure.

### 10.5 v1 scope boundary

Screencast and input forwarding are fiddly — DPI scaling, key modifiers, scrolling, popups, file inputs. For v1 the recorder handles **a single tab, no file uploads, no downloads, no drag-and-drop**, and it ships last. If it slips, layers 1 and 2 still deliver most of the checklist.

## 11. Reporting and mail delivery

### 11.1 Notification policy

- Mail is sent when a run produces **new or regressed `ERROR` findings**.
- An **all-clear** is sent on the monthly deep run even when nothing is wrong. Silence is ambiguous; people need periodic proof the system is alive.
- **Run failures are themselves notifiable.** A checker that silently stopped running is worse than any broken link it would have found.
- A nightly pulse with no changes sends nothing.

### 11.2 Aggregation

**One mail per window, not one per site.** Twelve sites finishing in the same nightly window produce one digest. Aggregation is per schedule tier. Volume is the fastest way to make people filter reports into a folder they never open.

Recipients are per site with a global fallback.

### 11.3 Outbox

Notifications are persisted (`recipient`, `subject`, `html`, `text`, `state`, `attempts`, `lastError`) and dispatched by a separate scheduled sender with retry and backoff.

- A run must never fail because the mail relay is down.
- A failed mail must be **visible in the UI**, not lost in a log. *"Did the August report go out?"* is a question that will be asked.
- Retry makes transient relay hiccups a non-event.

### 11.4 Configuration

SMTP settings live in the database and are editable in Settings — host, port, TLS mode, credentials, from-address — bootstrapped from environment variables on first start. Changing the relay must not require a container redeploy. The password is encrypted with the same AES-GCM key as journey credentials.

Two settings that look minor and are not:

- **"Send test mail" button.** Immediate, unambiguous confirmation the relay works. Without it, the first sign of a bad SMTP config is a report that never arrives.
- **Global redirect-all-mail address.** A staging instance must not mail real colleagues.

**Base URL is a required setting.** Every mail deep-links to a finding, and the app cannot reliably infer its external URL behind a reverse proxy. Getting this wrong ships reports full of `http://localhost:8080` links.

### 11.5 Rendering and failure

Multipart HTML plus plain text, both rendered by Thymeleaf. Corporate mail clients strip HTML more often than expected, and the text part improves deliverability.

When SMTP itself is broken the system cannot email about it, so failed sends raise a persistent health banner on the dashboard, and the outbox screen shows the queue with its errors.

Delivery is a `Notifier` interface with `EmailNotifier` as its only implementation. A chat or webhook channel later is one class; the abstraction goes no further today.

## 12. Web UI

Thymeleaf server-rendered with HTMX fragments. No SPA build chain. Live run progress via SSE; the recorder's WebSocket is the one bidirectional exception.

**Screens:**

| Screen | Contents |
|---|---|
| Dashboard | traffic-light grid across all sites, open findings by severity, next scheduled run, system health banner |
| Site detail | schedules, check settings, journeys, run history |
| Run detail | findings grouped New / Regressed / Still open / Fixed / Known, coverage, live progress |
| Finding detail | plain-language explanation, screenshot, request/response, console excerpt, every page it occurs on, triage actions |
| Journey editor | recorder, step list, assertions |
| Mute rules | per-site and global patterns with reasons and expiries |
| Settings | SMTP, IMAP test mailbox, base URL, users, concurrency, redirect-all-mail |

**Auth:** Spring Security, local accounts, BCrypt. Two roles — `ADMIN` (users, sites, settings) and `USER` (configure checks, triage, record).

**Locale:** Spring message bundles from day one, German as the default and only locale.

## 13. Self-explanatory UI and embedded documentation

**Principle: the interface explains itself; documentation is the fallback, not the plan.** Every place a document is needed is first treated as a UI problem.

### 13.1 Plain language, enforced at build time

No internal identifier reaches the screen — "Tote Links", never `DEAD_LINK`. Each check declares `titleKey`, `descriptionKey`, and `remediationKey` in its SPI contract.

A startup test walks every registered check and **fails the build if any key is missing in any supported locale.** Adding a check without its explanation becomes impossible. This is the only reliable defence against documentation rot.

### 13.2 Findings explain themselves

This is where a non-technical employee makes a judgment call. Every finding renders three things above the technical evidence:

1. **What we checked** — plain language
2. **What we found** — plain language, not a stack trace
3. **What to do about it** — the `remediationKey`

The third turns a technical finding into an assignable task. *"Der Google-Maps-API-Schlüssel wird abgelehnt; Abrechnung in Google Cloud prüfen"* beats `ApiNotActivatedMapError` for every reader the system actually has.

### 13.3 Guided setup instead of a blank form

When a site is added, a one-off probe detects what it contains — forms, multiple languages, videos, Maps embeds, PDFs — and pre-selects the relevant checks **with the reason shown**: *"Kontaktformular auf /kontakt gefunden — Formular-Prüfung vorgeschlagen."*

The employee **confirms a proposal rather than authoring a configuration.** This pattern does more for reliable steering than any amount of prose.

### 13.4 Consequences stated before the click

Anything with a side effect explains itself in words at the point of decision:

> *"Dies verschickt monatlich eine Testnachricht über das Kontaktformular an info@kunde-mueller.de."*

No employee should have to read a manual to discover what a setting does to a customer.

### 13.5 Inline help

Small `?` affordances expand to two or three sentences plus an example, in place, via an HTMX fragment. Never a link that navigates away and loses the user's context.

### 13.6 Handbook bundled in the app

A `/hilfe` section rendered from Markdown in `src/main/resources/help/`, covering long-form material: what the three tiers mean, how to triage, when muting is appropriate, how to record a journey, how to read a report.

Bundled rather than an external wiki, deliberately: it ships with the version it documents, works on an isolated internal host, is reviewed in the same commit as the change that made it wrong, and is deep-linkable from every `?` affordance. An external wiki is accurate the day it is written and misleading six months later.

### 13.7 Enforcement

Two build-failing tests carry this section:

1. Every registered check resolves `titleKey`, `descriptionKey`, and `remediationKey` in every supported locale.
2. Every help topic id referenced by a `?` affordance resolves to a bundled Markdown file.

Documentation that cannot rot is worth more than documentation that is merely thorough.

## 14. Failure handling and operations

**Crawl frontier is a database table**, not an in-memory queue. Costs a little write traffic; buys live progress, resumability after a container restart, and orphaned-run recovery.

**Lease expiry.** Runs are leased by workers. On startup and on a timer, expired leases are reclaimed and their runs resumed or failed.

**A page that times out or crashes the tab** becomes a `PAGE_UNREACHABLE` candidate and the crawl continues. One bad page must never kill a run.

**Browser process death:** contexts are recycled; if the `Browser` dies, it is restarted and the run resumes from the persisted frontier.

**Budget guards** (max pages, max depth, max duration) end a run cleanly with partial coverage, which per §6.4 resolves nothing it did not reach.

**Kill switches:** global pause, per-site disable.

**Run-level failures** set `Run.FAILED` with the error and trigger a notification.

## 15. Testing strategy

**The fixture site is the highest-value asset in the project.** A small static site served by the test harness, deliberately containing one of every failure mode:

- an image returning 404
- a soft 404 (200 with "Seite nicht gefunden" content)
- a link to a PDF that returns HTML
- a valid PDF
- a Maps iframe throwing `ApiNotActivatedMapError`
- a video with a broken source and one with a working source
- a contact form
- a language switcher that changes the URL but not the content
- a button that does nothing
- a redirect chain and a redirect loop
- mixed content

Every check is developed and regression-tested against it, against a real Chromium. It makes false-positive work tractable: a bad finding is reproducible in seconds instead of requiring a hunt for a customer site that exhibits it.

**Nothing in CI ever touches a real customer site.**

**Layered above that:**

| Layer | Coverage |
|---|---|
| Unit | Checks, fingerprinting, diff engine — pure functions over hand-built snapshots |
| Unit | Explicit test: a pulse run does not resolve full-crawl findings (§6.4) |
| Unit | Selector candidate generation and ranking, including rejection of generated ids |
| Integration | Crawler and checks against the fixture site with real Chromium |
| Integration | Journey record-and-replay against the fixture site |
| Integration | Outbox and mail rendering against a test SMTP server (GreenMail) |
| Repository | Spring Data against SQLite in a temp directory |
| Architecture | Spring Modulith boundary verification; message-key and help-topic completeness (§13.7) |

## 16. Deployment

Single Docker image: JRE 25, the application, and Chromium with its system libraries baked in. Browser version is pinned to the Playwright version.

```
/data
  ├── webtesthelper.db      SQLite (WAL)
  ├── keyfile               AES-GCM key for credentials and SMTP password
  └── artifacts/{runId}/    screenshots, HTML, HAR
```

`docker compose up -d`, port 8080, one persistent volume at `/data`. Reverse proxy terminates TLS; base URL configured in Settings.

Environment variables bootstrap first start: admin credentials, SMTP settings, base URL.

**Deployment inputs still to confirm:** which SMTP relay (company relay or external provider — decides the from-address and whether SPF/DKIM alignment is needed), and which mailbox serves as the IMAP verification target for `SUBMIT_AND_VERIFY_MAIL`.

## 17. Delivery phases

Each phase gets its own implementation plan written against this document.

| Phase | Contents | Value delivered |
|---|---|---|
| **1** | Domain model, persistence, job queue, browser pool, crawler, `PageSnapshot`, all layer-1 checks, fingerprint + diff + coverage, run history UI, manual run, fixture site, **SMTP settings + outbox + sender + test-mail button** | Most of the monthly checklist, run on demand |
| **2** | Tiered schedules, digest content and notification policy, triage UI, mute rules, dashboard, guided site setup | It runs itself and tells you |
| **3** | Cookie banner, contact form (three modes + IMAP), language switcher, button reachability, credential store | The interactive 20% |
| **4** | Recorder, journeys, selector drift, journey health | The long tail |

**Why SMTP plumbing sits in Phase 1** while digest content sits in Phase 2: it is small, independent of the crawler, and de-risks a fiddly integration early. The relay is proven working weeks before a report depends on it.

**Why the recorder ships last:** it is the component most likely to consume disproportionate time, and phases 1–3 already cover the large majority of the manual checklist. If phase 4 slips, the system is still worth running.
