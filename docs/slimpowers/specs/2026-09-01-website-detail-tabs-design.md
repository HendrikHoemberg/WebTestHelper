# Website-Detailseite: Registerkarten-Umbau — Design Specification

**Date:** 2026-09-01
**Status:** Approved
**Author:** opencode & Hendrik
**Depends on:** `2026-09-01-ui-overhaul-design.md` (existing shell/layout)

---

## 1. Context & Motivation

A third-party UX review of WebTestHelper found a single structural weakness: the
Website detail page mixes **monitoring results** and **administrative configuration**
on one long, linear page. Symptoms (verbatim from the review):

1. Wrong mental model — a user landing on a website sees crawler parameters
   ("Crawl-Budget & Limits", "Maximale Tiefe", robots.txt, User-Agent) first, instead of
   site state (health, open findings, last/next run).
2. Six competing header actions in one cluster (Feststellungen, Abläufe, Jetzt prüfen,
   Einrichtung erneut vorschlagen, Bearbeiten, Löschen).
3. Four independent forms on one screen (Zeitpläne, Empfänger, Zugangsdaten, Prüfungen)
   → unclear whether changes save together or separately.
4. Visual overload: ~17–19 check tiles each with a checkbox + severity dropdown, plus a
   long pill-list of Key Pages for the Pulse tier.
5. Jargon for laypeople ("Pulse-Prüfung", "Tiefenprüfung (Ausbaustufe 3)", "Crawl-Budget").
6. Bare `/befunde` and `/laeufe` URLs throw a 404 rather than a friendly page.

**Goal:** split the detail page into **readable tabs** (Übersicht & Feststellungen /
Prüfläufe / Konfiguration), restore the monitoring-first mental model, declutter the
header, group the check options, use layperson wording, and fix the bare-URL 404.

---

## 2. Goals / Non-Goals

**Goals**
- Monitoring-first overview tab: health Ampel, 3 status cards, top-5 findings preview.
- Real, deep-linkable routes per tab (no client-side-only switching).
- One consistent page header (breadcrumbs + title + Ampel + URL + `Jetzt prüfen` + `⋯`).
- Check options grouped by category (Inhalt / Technik / Rechtliches) in collapsible sections.
- Layperson terminology, consistent across detail page, schedule cards, and run table.
- Bare `/befunde` and `/laeufe` render a friendly 404 page.

**Non-Goals / Out of scope**
- No change to `websites/befunde.html` (the findings list) or `befunde/detail.html`.
- No change to journey studio, mute rules, settings, outbox, help, or login pages.
- No mobile/responsive work (desktop-only per `AGENTS.md`).
- No performance work on the crawler/runner itself.
- URL **slugs** `/befunde`, `/laeufe`, `/befund*` stay as-is (internal tokens); only
  user-visible copy is normalized. (Survey confirmed rendered text is already uniformly
  **„Feststellungen"** — the review's "Befunde/Feststellungen" inconsistency is visible only
  in HTML comments and slugs, so no copy change is required.)

---

## 3. Decisions (confirmed with stakeholder)

1. **Scope:** full review — tabs + header declutter + terminology + check grouping + 404 fix.
2. **Tab mechanism:** real routes per tab (`/websites/{id}`, `/websites/{id}/laeufe`,
   `/websites/{id}/konfiguration`).
3. **Check grouping:** introduce a new `CheckCategory` enum (Inhalt / Technik / Rechtliches),
   mapped centrally in `CheckRegistry`; render as collapsible accordions.
4. **Terminology:** global rename of the three `ui.runscope.*` display labels + remove
   "Ausbaustufe 3" phrasing; rename the budget/depth headings on the detail page. (This also
   changes mail-digest subject lines — accepted for consistency; the digest tests are updated
   accordingly.)

---

## 4. Routes & Templates

| Route | Handler | Template | Contents |
|---|---|---|---|
| `GET /websites/{id}` | `SiteController.uebersicht` | `websites/uebersicht` | Header + Ampel, 3 status cards, top-5 findings |
| `GET /websites/{id}/laeufe` | `SiteController.laeufe` | `websites/laeufe` | Header + run history table (moved from old detail) |
| `GET /websites/{id}/konfiguration` | `SiteController.konfiguration` | `websites/konfiguration` | Header + checks grouped, Zeitpläne, Empfänger, Zugangsdaten, Budget, Pfadmuster, Schlüsselseiten |
| `GET /websites/{id}/befunde` | `FindingListController` (unchanged) | `websites/befunde` | full findings list |

The old `websites/detail.html` is **removed**; its pieces are redistributed.

**Shared fragments (new):**
- `fragments/site-kopf :: kopf(site, trafficlight)` — breadcrumbs, title, Ampel badge, URL,
  `[▶ Jetzt prüfen]`, `[⋯]` dropdown.
- `fragments/site-tabs :: tabs(site, aktiv)` — tab bar with active state, links the 3 routes.

The `⋯` dropdown (<details> or Alpine) holds: *Benutzerabläufe* (`/sites/{id}/journeys`),
*Einrichtung erneut vorschlagen* (POST), *Bearbeiten* (ADMIN), *Löschen* (ADMIN).

---

## 5. Module A — Health wiring into the detail page

`SiteDetailModel.populate()` is split into three methods (it keeps its shared context
`site` availability via `contextFor`):

- `populateOverview(long siteId, Model m)`:
  - `site` (`SiteContext`)
  - `trafficLight` (`TrafficLight`) — from `reporting.TrafficLight.of(site.enabled(), lastRun, counts)`
  - `lastRun` (`RunSummary` or null) — from `runService.recentForSite(siteId, 1)` → first element
  - `openCounts` (`OpenFindingCounts`) — from `findingService.openCountsBySite().get(siteId)`
  - `nextRun` (`Schedule` or null) — earliest `enabled` schedule `nextFireAt` from `scheduleService.forSite(siteId)`
  - `topFindings` (`List<FindingView>`) — `findingService.search(FindingQuery.forSite(siteId).withSize(5))`, already severity-then-recency ordered → mapped via `findingViewFactory`
- `populateRuns(long siteId, Model m)`: `site`, `recentRuns`
- `populateConfig(long siteId, Model m)`: `site`, `checkRows`, `zeitplaene`, `zeitplaeneDetail`,
  `recipients`, `fallbackRecipients`, `credentials`, plus `checkCategories` (see Module B)

New dependencies added to `SiteDetailModel`: `FindingService`, `FindingViewFactory`
(and the `reporting`/`findings` model records already resolvable).

> Note: `LastRun` (reporting) lacks queuedAt/pagesVisited/duration, so the "Letzte Prüfung"
> card uses `RunSummary` from `recentForSite(siteId, 1)` instead.

---

## 6. Module B — Check categories (domain)

New enum `model.CheckCategory { INHALT, TECHNIK, RECHT }` with a centrally maintained
mapping. Because the reviewer-specified taxonomy ("Inhalt/Technik/Rechtliches") does not
exist in the code, the mapping is a product decision captured here for review:

| Category | Check types |
|---|---|
| **INHALT** | PAGE_STATUS, PAGE_UNREACHABLE, DEAD_LINK, IMAGE_BROKEN, MEDIA_PLAYABLE, FILE_DOWNLOAD, REDIRECT_CHAIN, SITEMAP_CONSISTENCY, HREFLANG |
| **TECHNIK** | TLS_CERT, MIXED_CONTENT, CONSOLE_ERRORS, IFRAME_EMBED |
| **RECHT** | COOKIE_BANNER, CONTACT_FORM, LANGUAGE_SWITCHER, BUTTON_REACHABILITY |

`COOKIE_BANNER` (Cookie-Hinweis = consent requirement) is assigned to **RECHT**.

Implementation:
- Add `category(CheckType)` to `CheckRegistry` (or a static `EnumMap<CheckType, CheckCategory>`
  with a defensive fallback for unknown types → TECHNIK).
- `checkRows` optionally augmented to carry `category`, or rendered grouped by iterating
  categories and filtering `checkRows`.
- Message keys `ui.checkcat.INHALT` / `ui.checkcat.TECHNIK` / `ui.checkcat.RECHT` for headings.

**Frontend:** collapsible `<details>` sections per category, each containing the existing
per-check tile markup (checkbox `name="aktiv"` + select `name="schweregrad[TYPE]"`), so the
existing POST `/websites/{id}/pruefungen` binding is untouched.

---

## 7. Module C — Templates & fragments

1. `websites/uebersicht.html`:
   - `kopf` fragment + `tabs(site, 'uebersicht')`.
   - Ampel chips (`ui.trafficlight.*`) + 3 status cards:
     - Offene Feststellungen (errors/warnings/infos, link `Zu allen Feststellungen →` to `/websites/{id}/befunde`)
     - Letzte Prüfung (`ui.runscope.KURZ`, formatted time, duration, pagesVisited)
     - Nächste Prüfung (time + scope) or "Keine geplant"
   - Top-5 preview: severity pill + title + occurrence URL + `[Details →]` → `/befunde/{id}`.
2. `websites/laeufe.html`: `kopf` + `tabs(site, 'laeufe')` + the existing run-history table
   (unchanged list accessor `recentRuns`).
3. `websites/konfiguration.html`: `kopf` + `tabs(site, 'konfiguration')` + grouped check
   accordions + the existing `zeitplaene`/`empfaenger`/`zugangsdaten` fragments + budget /
   Pfadmuster / Schlüsselseiten sections moved from old `detail.html`.
4. Delete `websites/detail.html`.

---

## 8. Module D — Controllers & redirects

- `SiteController`: replace single `detail` GET with the three GET mappings above; the other
  POSTs (`/pruefen`, `/bearbeiten`, `/loeschen`, `/einrichtung/neu`) unchanged.
- `ScheduleController.save` (line 90), `RecipientController.add`/`remove`,
  `CredentialController.update`/`create`/`delete`, `CheckSettingsController.save`:
  - success `redirect:/websites/{id}` → **`redirect:/websites/{id}/konfiguration`**
  - validation-error re-render `websites/detail` → **`websites/konfiguration`** and call
    `siteDetailModel.populateConfig(...)` (replaces `populate(...)`).
  - `SetupController` line 81 `redirect:/websites/{id}` — decide: keep overview or go config.
    Propose keep overview (setup completion lands on the monitoring view).
  - `RecipientController`/`CredentialController`/`ScheduleController` error paths currently call
    `siteDetailModel.populate(siteId, model)` → must call `populateConfig`.
  - `CheckSettingsControllerTest`/`RecipientControllerTest`/`CredentialControllerTest`/
    `ScheduleControllerTest` assert `view().name("websites/detail")` and specific content —
    updated to `websites/konfiguration`.

---

## 9. Module E — Terminology & message keys

| Key | Old | New |
|---|---|---|
| `ui.runscope.PULSE` | Puls-Prüfung | Schnell-Check (wichtigste Seiten) |
| `ui.runscope.FULL` | Vollständige Prüfung | Vollständiger Wochen-Check |
| `ui.runscope.DEEP` | Tiefenprüfung | Vollständiger Monats-Check |
| `ui.runscope.PULSE.kurz` | Schneller täglicher Check der wichtigsten Seiten | Täglicher Schnell-Check der wichtigsten Seiten |
| `ui.runscope.FULL.kurz` | Wöchentlicher Check der gesamten Website | Wöchentlicher Check der gesamten Website |
| `ui.runscope.DEEP.kurz` | Monatlicher Volltest inkl. Kontaktformular-Prüfung | Monatlicher Volltest inkl. Kontaktformular-Prüfung |
| `ui.zeitplan.deep.hinweis` | … erst ab Ausbaustufe 3 wirksam | drop "Ausbaustufe 3" clause |
| `ui.websites.detail.budget.titel` | Crawl-Budget & Limits | Prüfumfang & Grenzen |
| `ui.websites.detail.budget.maxDepth` | Maximale Tiefe | Wie tief verlinkte Seiten prüfen |
| `ui.websites.formular.maxDepth` | Maximale Tiefe | *(optionally mirror)* |
| new `ui.checkcat.*` | — | Inhalt / Technik / Rechtliches |

**Test impact (must be updated):** `DigestMailRendererTest`, `DigestServiceTest`,
`DigestAcceptanceTest` (mail subjects), `SiteDetailControllerTest` (page content assertions on
the old runscope labels). Any remaining honest literal usage in `help/*.md` is out of scope
(help docs), but the `ui.zeitplan.deep.hinweis` key change is in scope.

---

## 10. Module F — bare-URL 404 fix

- Reproduce `/befunde` and `/laeufe` (no id) via `@WebMvcTest`/MockMvc: a bare prefix currently
  returns 404 with an empty body (the friendly `error.html` is not rendered in the test path).
- Fix chosen: add a class-level `@GetMapping` `root()` in `FindingController` and `RunController`
  that **redirects** to `/websites` (the "Weiterleitung zu den Websites" option). The regression
  test asserts a 3xx redirect to `/websites` for both bare prefixes.

---

## 11. Testing strategy

- `@WebMvcTest` for the three new tab routes (`SiteController`): assert view name, model
  attributes, markup (text/`data-*`/HTMX attributes, no raw cron, no internal enum names,
  `sec:*` never emitted for USER).
- Update `SiteDetailControllerTest`, `CheckSettingsControllerTest`, `RecipientControllerTest`,
  `CredentialControllerTest`, `ScheduleControllerTest` for the new template name / redirects.
- New `CheckCategory` unit test: every `CheckType` in `CheckRegistry.all()` maps to a
  non-null category; the enum is stable.
- New regression test for `/befunde` and `/laeufe` → friendly 404.
- Email-digest tests updated for the new `ui.runscope.*` labels.
- Gate: run `./mvnw test -Pfast` for web/domain changes (templates, messages, controllers).
  The crawler/runner/checks module touches the `resource` check files only for the category
  map, so `-Pfast` remains the gate; `./mvnw test` full is warranted if any resource/browser
  path changes (run history table markup is on the laeufe tab — pure template).

---

## 12. Risks & open points

1. **Check-category assignment** — the Inhalt/Technik/Rechtliches mapping in §6 was confirmed
   as proposed (COOKIE_BANNER → RECHT).
2. **Global runscope rename changes mail digest subjects** — confirmed; the §9 labels are the
   locked wording.
3. **Redirect target of `SetupController` line 81** (`/websites/{id}`) — lands on the overview
   tab (monitoring-first), confirmed.
4. **17 vs 19 check tiles** — the registry currently yields the checks listed in §6; the
   count is data-driven, so no hardcoding.
5. **Top-5 findings query** — `FindingQuery` has no fluent `withSize`; use the constructor
   directly: `new FindingQuery(siteId, Set.of(), Set.of(), null, Set.of(), 1, 5)`.
