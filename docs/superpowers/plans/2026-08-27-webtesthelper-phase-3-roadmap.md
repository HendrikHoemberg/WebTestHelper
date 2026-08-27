# WebTestHelper Phase 3 — Plan Roadmap

**Date:** 2026-08-27
**Spec:** `docs/superpowers/specs/2026-08-21-webtesthelper-design.md` (§17 defines Phase 3)
**Predecessor:** `2026-08-25-webtesthelper-phase-2-roadmap.md` — Phase 2 complete, four plans
executed, **882 tests** green on `main` (`c532e17`).

**Phase 3 in one line (§17):** *the interactive 20%.*

Phases 1 and 2 built a system that reads. It navigates, it extracts, it reasons over what came
back, and it never touches anything. Every check so far is a pure function over a `PageSnapshot`
or over the whole crawled set — §5.2's *navigate once, check many*, which is what made ~15,000
page visits per sweep affordable and what let the entire catalog be developed against hand-built
records with no browser in the test.

Phase 3 breaks that property on purpose, for four checks and no more. A cookie banner cannot be
accepted from a snapshot. A contact form cannot be submitted from one. §5.2 already says so in as
many words — *"accepting a cookie banner or submitting a form cannot be done from a snapshot"* —
and the whole architectural point of naming `InteractionCheck` a **distinct SPI** is that this
property breaks in exactly one place, visibly, rather than seeping into the ten page checks that
are the bulk of the catalog.

So the phase has two halves, and the plan split follows them. First the machinery that drives a
live browser safely: a target set, a context lifecycle, a coverage model that does not lie about
what a sampled check saw. Then three more checks riding it.

---

## Scope

§17 lists five items. Two more sit directly in front of them, each named as a decision in an
earlier document.

| From | Item |
|---|---|
| §17 / §7.2 | `COOKIE_BANNER` — detect and accept; report if undismissable |
| §17 / §7.2 | `CONTACT_FORM` — three modes, field classification, IMAP verification |
| §17 / §7.2 | `LANGUAGE_SWITCHER` — URL **and** `<html lang>` **and** visible text |
| §17 / §7.2 | `BUTTON_REACHABILITY` — nav items and in-page buttons go somewhere |
| §17 / §10.4 | The credential store — AES-GCM, `{{cred.<name>.<field>}}`, never rendered back |
| §12, Phase-2 roadmap | **IMAP settings.** Deferred out of Phase 2 in as many words: *"they configure `SUBMIT_AND_VERIFY_MAIL`, which is Phase 3. Phase 2 configures what Phase 2 uses"* |
| §6.1, §7.2, §13.4 | **`Site.form_test_mode` reaching a screen.** The column has existed since `V2__site.sql` and `SiteEntity` since Phase 1. Nothing reads it, nothing writes it, `SiteContext` does not carry it. §13.4's worked example — *"Dies verschickt monatlich eine Testnachricht über das Kontaktformular an info@kunde-mueller.de"* — is about this field |

## The plans

Four plans, each producing working, testable software on its own, each reviewed before the next
is written.

| # | File | Goal | Ends with | Depends on |
|---|---|---|---|---|
| 10 | `…-p10-interaction-pass.md` | The `InteractionCheck` SPI, target selection, the context lifecycle, per-type coverage, and `COOKIE_BANNER` as its first tenant | A run drives a live browser after the crawl, dismisses banners, and reports the ones it cannot | Phase 2 |
| 11 | `…-p11-switcher-buttons.md` | `LANGUAGE_SWITCHER` and `BUTTON_REACHABILITY` — the two checks that navigate but never write | The two cheapest interaction checks, on the machinery plan 10 proved | 10 |
| 12 | `…-p12-contact-form.md` | `CONTACT_FORM`: field classification, the three modes, the FULL/DEEP submit gate, IMAP settings and the token poller, §13.4's consequence copy | *"Das Formular meldet Erfolg, die Nachricht kommt nicht an"* — the failure this product exists to catch | 11 |
| 13 | `…-p13-credentials.md` | The per-site credential store: AES-GCM via the existing `SecretBox`, `{{cred.…}}` resolution, redaction in logs and on screen | A password can be stored, referenced and never read back — the one thing Phase 4 cannot start without | 10 |

### Why the cookie banner ships with the machinery and not with its siblings

§7.2 does not list four peers. It says `COOKIE_BANNER` **runs first** and its accepted state is
reused by the others, *"since a banner overlay blocks everything behind it."* The banner is not
one of four checks; it is a precondition of the other three that happens to also emit a finding.
Building it in plan 11 would mean plan 10 shipping a context lifecycle with no consent step, and
plan 11 retrofitting one into machinery already under test — the precise shape of rework the
Phase-2 roadmap's "write the next plan only after the previous executes" rule exists to prevent.

### Why the contact form is last of the checks, and alone

It is the only check in the system with a **side effect on a customer**. It needs field
classification, three behaviour modes, a scope gate that forces the safest mode outside the
monthly deep run, an IMAP client, a settings screen, and §13.4's sentence stating in German what
pressing the button does to a client's inbox. Any two of those in one plan trips `CLAUDE.md`'s
~120-lines-per-task tripwire; all six in one plan with anything else would be two deliverables.

### Why credentials depend on plan 10 and not on plan 12

They depend on nothing in Phase 3 at all — no interaction check reads a credential. §17 places the
store here so that Phase 4's recorder, the component §10.5 names as *"the most likely to consume
disproportionate time"*, does not also have to build a secret store on its first day. Plan 13 is
listed after 10 only so it is not written against a moving `catalog` module; it could be executed
any time after plan 10 lands, including in parallel with 11 and 12 if that is ever useful.

## Deviations from the spec

The Phase-1 table (D1–D37), plan 6's (D38–D45), plan 7's (D46–D52), plan 8's (D53–D60) and plan
9's (D61–D71) apply unchanged and are **not** restated. Phase 3 continues the numbering from D72.
Each plan file carries the reasoning behind its own; this table is the index and holds only the
decisions that are phase-wide.

| # | Deviation | Plan |
|---|---|---|
| D72 | `checks` gains Playwright. §5.1's *"no Spring, no database, no browser"* is narrowed to the page and site halves of the module, which keep it | 10 |
| D73 | The interaction driver lives in `runner`, not `crawler` — no new module edge, and §5.1 assigns the browser pool to `runner` anyway | 10 |
| D74 | Coverage becomes **two** scopes, not one: interaction check types resolve only within the pages the run actually drove them on | 10 |
| D75 | Interaction findings are **never** promoted site-wide — a sampled observation set cannot disprove *"on 312 pages"* | 10 |
| D76 | One `BrowserContext` per target URL, one fresh `Page` per check inside it; consent is established once per context | 10 |
| D77 | `CheckType` grows **one plan at a time**. `CheckRegistryTest` fails the build on a type with no implementation, and `CheckDocumentationTest` on one with no German copy — so the enum constant, the class, the message keys and the help text land in a single commit or not at all | 10–12 |

### D72 in full, because it is the one that gives something up

§5.1 is unambiguous: *"`checks` and `findings` depend only on their own value types — no Spring,
no database, no browser. These are the two most logic-dense parts of the system and they must be
unit-testable in isolation."* §7.3 is equally unambiguous, and contradicts it:

```java
interface InteractionCheck extends CheckDescriptor {
    List<CheckFinding> evaluate(Page page, SiteContext site, CheckConfig config);
}
```

That `Page` is `com.microsoft.playwright.Page`. Both sentences are in the approved spec and only
one can hold. Three ways out were considered:

1. **A driver abstraction** — `checks` defines a narrow `PageDriver` port, `crawler` implements it
   over Playwright. Keeps the module pure and buys a mockable interaction check. Rejected: it is a
   second, weaker Playwright API maintained by hand, and §5.2's own table already answers the
   question it would be bought to solve — *"Interaction check … testable without a browser: no."*
   Paying an abstraction for testability the spec says is unattainable is paying twice.
2. **A fifth module.** `CheckRegistry` holds every descriptor so §13.7's documentation gate can
   walk them; `coveredTypes()` feeds §6.4's resolution scope; `CheckRegistryTest` fails the build
   when a `CheckType` has no implementation. Splitting the interaction checks out fragments all
   three, and the enforcement tests are the most valuable thing in the module.
3. **Take §7.3 at its word.** Chosen.

What is actually lost is smaller than §5.1's sentence suggests: four classes out of eighteen gain
a browser dependency. `CheckRegistry.standard()` still constructs without launching Chromium, so
`CheckRegistryTest`, `CheckDocumentationTest` and `ScopeCheckSetTest` stay under `-Pfast`, and the
ten page checks and three site checks keep their hand-built-snapshot tests unchanged. What is
gained is that the boundary is *visible*: a check that needs a browser must implement a different
interface, and `git grep InteractionCheck` is the complete list.

### D74 in full, because it is the correctness one

§6.4 makes coverage load-bearing, and today `RunCoverage` is a **cartesian product**:

```java
public record RunCoverage(Set<CheckType> checkTypes, Set<String> locationKeys, boolean wholeSite)
```

`FindingStore.RESOLVE_SQL` reads it as `check_type = ANY(types) AND location_key = ANY(locations)`.
For a page check that is exactly right: every check ran on every visited page. For an interaction
check it is a lie, and a damaging one. A FULL run crawls 300 pages and drives `COOKIE_BANNER` on
one of them. Its coverage would claim `COOKIE_BANNER × 300 URLs`, and last week's undismissable
banner on `/kontakt` — a page this run never drove — would be silently marked **fixed**. Next
week's run finds it again and reports it **regressed**. Every week, forever. That is §6.4's
motivating failure, reproduced by the very mechanism written to prevent it.

The fix is one extra set on the run and one extra `UPDATE` at materialisation, and it earns the
phase's only migration. Plan 10 owns it and gets §6.4's *"this gets an explicit test"* a second
time, for the sampled case.

## What Phase 3 does not ship

Named so a reviewer can tell a gap from a decision.

- **The recorder, journeys, `SELECTOR_DRIFT` and journey health.** Phase 4 (§10). Plan 13 builds
  the credential store they reference and nothing else from that section.
- **§16's application image and the second Compose service.** Unchanged from the Phase-2 roadmap.
  §16 still names two unconfirmed deployment inputs, and plan 12 answers exactly one of them —
  *which mailbox serves as the IMAP verification target* becomes a settings field with a test
  button, the same shape §11.4 gave SMTP. The relay question, and the image, stay open.
- **Re-verification of interaction findings.** §8 collects failures and re-verifies them "with
  fresh contexts". `FindingReverifier` re-probes dead-link subjects over HTTP and cannot re-drive
  a browser. Re-running `CONTACT_FORM` in `SUBMIT` mode to confirm a failure would send a second
  test mail to a customer — the one thing §8's politeness rules and §13.4's consequence copy exist
  to prevent. Plan 10 states the consequence instead of hiding it: an interaction check gets its
  retries **inside** the check, in the context it already holds, and the run's second pass leaves
  its findings alone.
- **The `IFRAME_EMBED` canvas-paint gap, blocked-iframe URL attribution, Maps-error attribution,
  and D23's re-verification scope.** Inherited open from the plan-3 review and both phase reviews,
  unchanged. Each needs a measurement against a hand-built fixture page, which belongs in front of
  a plan, not inside one.
- **A `PULSE` that crawls a site with no pinned key pages.** Plan 9's "Deliberately not" section
  names it and the repair — a dispatcher change with its own test. Still true, still out of scope.
- **A second locale.** §12 is German-only; every key added in this phase is German-only.

## Open questions to settle before the plan that needs them

- **Plan 11:** how does `LANGUAGE_SWITCHER` decide *"visible text actually differs"*? `SimHash` is
  already in `model` and `PageSnapshot` already carries `textSimhash`, so a Hamming distance is
  free — but the threshold is not derivable from first principles and the fixture's `/en/index.html`
  is a deliberately near-identical page. **Measure the distance between the fixture's German and
  English homepages before choosing a number**, and record it, the way plan 3a recorded the
  soft-404 cutoff of 16.
- **Plan 11:** what counts as *"a visible DOM change"* for a button that navigates nowhere? The
  fixture's `#tut-nichts` is the negative case and there is currently **no positive case** — no
  button that opens an accordion or reveals a panel. A check with only a failing fixture cannot
  demonstrate it does not fire on healthy markup, which is the false-positive question §8 makes
  the product's core promise. The fixture needs the healthy case first.
- **Plan 12:** does IMAP verification poll on the run's thread, or does the run finish and a later
  job resolve the finding? Polling blocks a browser worker's run for the mailbox round-trip;
  deferring means a `DEEP` run completes with a finding whose truth is not yet known, which the
  diff model has no state for. Decide against a measured GreenMail round-trip, not from taste.
- **Plan 12:** `spring-boot-starter-mail` is already on the classpath and Angus Mail ships the
  IMAP provider, so **no new dependency is expected** — confirm that before planning, because a
  new dependency changes the plan's shape and §12's vendored-assets rule shows how the project
  feels about them.
- **Plan 13:** does a credential belong to a site, or can it be global? §10.4 says *"an encrypted
  per-site store"* and §6.1 hangs `Credential` off `Site`. Confirm nothing in Phase 4's step model
  wants a shared login before committing the foreign key.

## Calibration and execution

**`CLAUDE.md`'s "Plan calibration" section is the authority** and is not restated here: 150
verbatim-code lines per plan, ~120 lines per task as the tripwire, no per-plan total, preambles
that point rather than copy, and no editing a plan after it has executed.

Each plan is executed with `superpowers:subagent-driven-development` (recommended) or
`executing-plans` immediately after it is written. **The next plan is only written after the
previous one executes and its commits land.** This roadmap therefore ships with plan 10 alone.

`./mvnw test` (882 tests, ~1m37s as of `c532e17`) is the pre-merge gate.

**This phase adds browser tests, and that is a change of habit worth stating.** `CLAUDE.md`
records that plan 5 added none and that all 122 of its tests ran under `-Pfast`. That was correct
there and is not available here: §5.2's table says an interaction check is not testable without a
browser, and §15 puts *"crawler and checks against the fixture site with real Chromium"* in the
integration layer for exactly this reason. Each plan should add **one** browser test class per
check — the class crawls or drives once and asserts many times, per `CLAUDE.md`'s rule that a
`@BeforeEach` which drives a browser costs one Chromium sweep per test method. Everything else —
target selection, coverage arithmetic, field classification, credential round-trips — is a pure
function over hand-built input and stays under `-Pfast`.

**The fixture site grows, and existing pages must not move.** §15 calls it the highest-value asset
in the project and it currently contains no cookie banner, no working language switcher, no button
that does anything, and no form endpoint that accepts a submission. All four are needed. But
`SetupProbeTest` asserts the probe's candidate set against `index.html`'s link order and cap of
eight, and `CrawlServiceFullCrawlTest` and `PageCheckAcceptanceTest` share one crawl across a
class. **New fixture pages are served but not linked from `index.html`**, so the crawl-shaped
assertions of Phases 1 and 2 do not move; the interaction tests navigate to their targets by
explicit URL, which is what an interaction check does anyway.
