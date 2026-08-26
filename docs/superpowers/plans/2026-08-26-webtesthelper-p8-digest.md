# WebTestHelper Plan 8 — One Mail, When It Matters

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Mail arrives on its own, one per window, and says what changed. §11.1's policy decides
*whether* to send, §11.2's aggregation decides *when* and *to whom*, and the outbox plan 5 already
built decides *how*.

**Architecture:** No new module. The digest is assembled by a poller over the `run` table in
`reporting`, which grows the three dependencies it needs to read a finished run
(`checks`, `findings`, `runner`). Nothing in the run's terminal path learns that mail exists.
`web` grows one panel, one Settings field and one handbook topic.

**Tech Stack:** Java 25, Spring Boot 4.1.1, PostgreSQL 17 via Testcontainers, Thymeleaf,
GreenMail (already a test dependency). **No new dependency.**

**Spec:** `docs/superpowers/specs/2026-08-21-webtesthelper-design.md`; §-references point there.
**Roadmap:** `2026-08-25-webtesthelper-phase-2-roadmap.md` — plan 8 of 9. Its deviation index
(D38–D45), plan 7's (D46–D52), the Phase-1 table (D1–D37), `CLAUDE.md`'s plan calibration and
`CLAUDE.md`'s test rules all apply and are **not** restated.

**Ends with:** twelve sites finish their nightly pulse between 03:00 and 03:25. At 03:30 one mail
lands in each recipient's inbox naming the two sites that changed, with a link that opens the
finding. The ten quiet sites are a count. Nobody's phone goes off for the WARN that was already
there yesterday, and the month's deep run mails even when everything is fine, because silence is
ambiguous.

**No browser test.** A digest is a query, a predicate and a template. Every test in this plan runs
under `-Pfast`.

---

## The roadmap's open question, answered

> *What is the aggregation window? A window keyed on the tier plus the calendar day is the obvious
> reading; a window that waits for every site's run to finish is the correct one and needs a
> completion signal that does not exist yet.*

**It waits — and the completion signal already exists.** It is the `run` table. A window for tier
*T* is the set of terminal runs of scope *T* that no digest has claimed yet, and it closes when
**no run of scope *T* is `QUEUED` or `RUNNING`** and the newest finish has settled. That is not a
new signal; it is the same table `RunPoller` claims from, read with the opposite predicate.

Two consequences make this cheaper than the calendar reading rather than more expensive:

- **A run that overruns is not orphaned.** Under a fixed 05:00 cutoff, the site that finished at
  05:02 is reported the following night with its findings already aged into `STILL_OPEN` — which
  §11.1 does not mail. That is news deleted by a clock.
- **A tier whose sites sit in different timezones splits itself correctly.** Site cron is
  evaluated per site timezone (D39), so a site an hour behind opens its own window instead of
  being cut in half by a shared cutoff.

The escape hatches are two constants, not a mechanism: a **settle** delay so the gap between two
sites' runs cannot close the window early, and a **max wait** so a pathologically stuck run cannot
hold the mail forever. The stale-lease sweep already requeues or supersedes a `RUNNING` run whose
lease expired, so max wait is a backstop for a case the runner is supposed to make impossible —
which is exactly what a backstop is for.

## Deviations this plan introduces

- **D53 — the digest is assembled by a poller over the `run` table, never by a call at the end of
  a run.** §11.3's *"a run must never fail because the mail relay is down"* becomes structural
  instead of a `try/catch` somebody can delete: the run's terminal paths do not import
  `reporting`, and `runner` gains no dependency on it. It also means all three terminal paths are
  covered by one mechanism — `RunWorker`'s `COMPLETED`, `RunWorker`'s `FAILED`, and the lease
  sweep's requeue/supersede — where an end-of-run call would have to be installed in each and
  would still miss the run that died with its JVM.
- **D54 — a window closes on quiet, not on a clock.** Reasoning above. `settle` and `max wait` are
  constants, and the window's membership is a column on `run`, not a table.
- **D55 — a manual run is digested, in its scope's window.** Skipping manual runs looks quieter
  and is a hole: a manual run materialises findings like any other, so a `NEW` `ERROR` it
  discovers is `STILL_OPEN` by the next scheduled run, and §11.1 never mails `STILL_OPEN`. The
  news would be swallowed permanently by the person who pressed the button. The cost — a small
  off-hours digest after someone presses *Jetzt prüfen* — is a cost the outbox screen makes
  visible.
- **D56 — "one mail per window" means one mail per *recipient* per window.** §11.2's rule exists
  to stop twelve sites producing twelve mails; it cannot mean one mail globally, because
  recipients are per site (§11.2's own next sentence). The window's digest is assembled once,
  then **restricted to each recipient's sites**, and §11.1's predicate is evaluated on the
  restriction. A recipient of one quiet site gets nothing from a window in which another
  customer's site broke.
- **D57 — the finding renderer moves from `web` to `reporting`.** A finding's German sentence is
  built from `messageKey` + humanised args + the check's title and remediation (§13.1). The mail
  and the screen must produce the same sentence, and two renderers are two §13.1 implementations
  that will drift the first time an arg needs softening. `reporting` gains `checks` and `findings`
  (this task) and `runner` (Task 3) as allowed dependencies; `web` already depends on `reporting`,
  so nothing else moves.
- **D58 — §11.1's predicate is `ERROR`-only; the digest's *content* is not.** The mail lists `NEW`
  and `REGRESSED` findings at `ERROR` **and** `WARN`, capped per site; `FIXED`, `STILL_OPEN` and
  `KNOWN` are counts with a link. Once the decision to mail is taken, hiding the WARN that
  appeared in the very same run costs a second round-trip to the screen for no benefit. `INFO`
  stays a count in every case: §8 makes `UNVERIFIABLE` `INFO` by default and it is the noisiest
  class the system produces.
- **D59 — per-site recipients live in `catalog`, on the Site aggregate.** §6.1 hangs
  `NotificationRecipient` off `Site`, deleting a site must take its recipients with it, and
  `reporting` may read `catalog` already. Putting the table in `reporting` would invert that and
  give `web` a second place to edit site configuration.
- **D60 — `spring.task.scheduling.pool.size=5`.** D52's four are spoken for (outbox, tick,
  retention, mute sweep). The digest cycle is the fifth.

## Decided constants

| Constant | Value | Why |
|---|---|---|
| settle delay | 5 min, `webtesthelper.reporting.digest-settle` | wider than any gap between two sites' runs of one tier, narrower than anyone's patience |
| max wait | 6 h, `webtesthelper.reporting.digest-max-wait` | a nightly window must never leak into the next night's |
| cycle interval | 2 min, `webtesthelper.reporting.digest-interval` | the window is minutes wide; polling faster buys nothing |
| cycle enabled | `webtesthelper.reporting.digest-enabled`, default true, **false in tests** | D33's rule; the mute sweep and the tick set the precedent |
| findings listed per site | 10, `webtesthelper.reporting.digest-max-findings` | D58. An unaccepted baseline run has 200; the mail says ten and links to the rest |
| scheduler pool | `spring.task.scheduling.pool.size=5` | D60 |

## URL vocabulary added

| Path | Method | Role | Screen |
|---|---|---|---|
| `/websites/{id}/empfaenger` | POST | ADMIN | Add a recipient to a site |
| `/websites/{id}/empfaenger/{rid}/loeschen` | POST | ADMIN | Remove one |
| `/hilfe/benachrichtigungen` | GET | USER | New handbook topic |

Recipients are `ADMIN`, matching `/websites/*/zeitplaene`: deciding who is mailed about a
customer's site is administration, not triage. The global fallback address lives on
`/einstellungen`, which is already `ADMIN`. Neither path is matched by the existing
`POST /websites/*` rule, so `SecurityConfig` gains an explicit matcher and `SecurityRulesTest`
gains a case.

---

### Task 1: Move the finding renderer into `reporting`

A pure move. No behaviour changes, no test assertions change — only packages and imports. It goes
first so every later task can call one renderer (D57).

**Files:**
- Move (with `git mv`, packages rewritten): `web/FindingViewFactory.java`, `web/FindingView.java`,
  `web/FindingDetailView.java`, `web/TechnicalText.java` → `reporting/`
- Move: `web/FindingViewFactoryTest.java`, `web/TechnicalTextTest.java` → `reporting/`
- Modify: `reporting/package-info.java`, `web/FindingController.java`,
  `web/FindingListController.java`, `web/RunController.java`,
  `web/FindingControllerTest.java`, `web/FindingListControllerTest.java`, `web/RunControllerTest.java`
  (imports only)

**Interfaces (produces):**
- `reporting.FindingViewFactory.of(Finding, Locale) → FindingView` and
  `detailOf(Finding, List<FindingOccurrence>, Locale) → FindingDetailView`, unchanged signatures
  in a new package.

- [ ] **Step 1: Move the four classes and two tests**, rewriting `package` and the imports in the
      three controllers and three controller tests. Templates are untouched: no property name
      changes.

- [ ] **Step 2: Run `./mvnw test -Pfast` and watch `ModularityTest` fail.** Expected: a violation
      naming `reporting` → `checks` (`CheckRegistry`, `CheckDescriptor`) and `reporting` →
      `findings` (`Finding`, `ReportSection`, `RunDiff`). This is the failing test for this task —
      the boundary is the deliverable, and Modulith is what checks it.

- [ ] **Step 3: Widen `reporting`'s `allowedDependencies` to `{"model", "catalog", "checks",
      "findings"}`** and re-run. Expected: green, with the same test count as before the move.
      Do **not** add `runner` here; Task 3 adds it in the task that needs it.

- [ ] **Step 4: Commit.**

```bash
git commit -am "refactor(reporting): one finding renderer for the screen and the mail"
```

---

### Task 2: Recipients on the Site aggregate, with a global fallback

§11.2's last line in one table and one setting. Nothing here knows what a digest is.

**Files:**
- Create: `src/main/resources/db/migration/V16__notification_recipient.sql`
- Create: `catalog/persistence/NotificationRecipientEntity.java`,
  `catalog/persistence/NotificationRecipientRepository.java`, `catalog/Recipient.java`,
  `catalog/RecipientService.java`
- Modify: `catalog/AppSettings.java`
- Test: `catalog/RecipientServiceTest.java` (extends `AbstractPostgresTest`),
  `catalog/AppSettingsTest.java`

**Interfaces (produces):**
- `record Recipient(long id, long siteId, String email)`
- `RecipientService.list(long siteId) → List<Recipient>` — the site's own rows, ordered by email.
- `RecipientService.add(long siteId, String email) → long`, throwing
  `IllegalArgumentException` with a message key for a blank or malformed address.
- `RecipientService.remove(long siteId, long recipientId)` — the site id is a guard, not a lookup
  key: a recipient id from another site must not delete.
- `RecipientService.effectiveFor(Collection<Long> siteIds) → Map<Long, List<String>>` — per-site
  addresses where a site has any, the global fallback where it has none, an **empty list** where
  neither exists.
- `AppSettings.fallbackRecipients() → List<String>` and `saveFallbackRecipients(String raw)`,
  keyed `mail.fallback-recipients`, stored unencrypted.

- [ ] **Step 1: Write `V16__notification_recipient.sql`.** One table, cascading from `site`, and a
      case-insensitive uniqueness rule — the same address entered twice with different capitals is
      one recipient, and two rows would mean two mails.

```sql
CREATE TABLE notification_recipient (
    id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    site_id BIGINT NOT NULL REFERENCES site (id) ON DELETE CASCADE,
    email TEXT NOT NULL CHECK (email <> ''),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    version BIGINT NOT NULL DEFAULT 0
);
CREATE UNIQUE INDEX ux_notification_recipient_site_email
    ON notification_recipient (site_id, lower(email));
```

- [ ] **Step 2: Write the failing tests** in `RecipientServiceTest`, against a seeded site:
      a site with two recipients returns both; a site with none falls back to the configured
      global list; a site with none and no global setting returns an empty list; `add` of the same
      address in different case is rejected; `add` of `"nicht-mal-eine-adresse"` is rejected;
      `remove` with another site's recipient id changes nothing; deleting the site removes its
      rows. In `AppSettingsTest`: `saveFallbackRecipients("A@x.test; b@x.test , b@X.test")`
      round-trips to exactly `[a@x.test, b@x.test]`.

- [ ] **Step 3: Run them and watch them fail** — `./mvnw test -Pfast -Dtest=RecipientServiceTest`.

- [ ] **Step 4: Implement the entity, repository and service.** Addresses are stripped and
      lowercased on the way in; splitting the fallback setting accepts comma, semicolon and
      whitespace because a person pasting three addresses will use all three. Validation is one
      shape check (`something@something.tld`, no spaces) and not an RFC parser — the failure mode
      of a too-strict check here is a colleague who cannot be mailed at all.

- [ ] **Step 5: Run the suite** — `./mvnw test -Pfast`, plus `FlywayMigrationTest` green so
      `ddl-auto=validate` accepts the new entity.

- [ ] **Step 6: Commit.**

```bash
git commit -am "feat(catalog): per-site notification recipients with a global fallback"
```

---

### Task 3: When a window closes

The run table read with the opposite predicate to `RunPoller`'s, plus the pure rule that decides
whether the window is done (D54).

**Files:**
- Create: `src/main/resources/db/migration/V17__run_digest.sql`, `reporting/DigestWindow.java`
- Modify: `runner/RunService.java`, `runner/persistence/RunRepository.java`,
  `reporting/package-info.java` (add `runner`), `reporting/ReportingProperties.java`,
  `src/main/resources/application.properties`
- Test: `runner/RunDigestQueryTest.java` (extends `AbstractPostgresTest`),
  `reporting/DigestWindowTest.java` (pure, no Spring)

**Interfaces (produces):**
- `RunService.undigested(RunScope scope) → List<RunSummary>` — `COMPLETED` and `FAILED` runs of
  that scope with `digest_sent_at IS NULL`, oldest finish first.
- `RunService.hasRunsInFlight(RunScope scope) → boolean` — any `QUEUED` or `RUNNING` run of that
  scope, any site.
- `RunService.markDigested(List<Long> runIds, Instant at) → int` — `@Transactional`, `@Modifying`,
  stamps only rows still `NULL`.
- `record DigestWindow(RunScope scope, List<RunSummary> runs, Instant closedAt)` with
  `static Optional<DigestWindow> close(RunScope scope, List<RunSummary> undigested,
  boolean inFlight, Instant now, Duration settle, Duration maxWait)` and `List<Long> runIds()`,
  which Task 7 stamps with.
- `ReportingProperties` gains `Duration digestSettle`, `Duration digestMaxWait`,
  `Duration digestInterval`, `boolean digestEnabled`, `int digestMaxFindings`, each with the
  defaults from **Decided constants** applied in the compact constructor, as the existing fields do.

- [ ] **Step 1: Write `V17__run_digest.sql`.** One nullable column and the partial index the
      claim query needs. `CANCELLED` is deliberately outside the index: a superseded stale run
      has no report to make and must never hold a window open.

```sql
ALTER TABLE run ADD COLUMN digest_sent_at TIMESTAMPTZ;
CREATE INDEX ix_run_undigested ON run (scope, finished_at)
    WHERE digest_sent_at IS NULL AND status IN ('COMPLETED', 'FAILED');
```

- [ ] **Step 2: Write the failing `DigestWindowTest`** — pure, no database, five cases:
      no undigested runs → empty; a run in flight → empty; newest finish is 1 minute old against a
      5-minute settle → empty; nothing in flight and the newest finish is 6 minutes old → a window
      holding every undigested run; **a run in flight but the oldest undigested finish is 7 hours
      old → a window anyway**, and it contains only the finished runs. Assert on `runs` contents,
      not just presence.

- [ ] **Step 3: Run it and watch it fail.**

- [ ] **Step 4: Implement `DigestWindow.close`.** The whole rule, and the only algorithm in this
      plan a reader would otherwise re-invent differently:

```java
if (undigested.isEmpty()) return Optional.empty();
Instant newest = undigested.stream().map(RunSummary::finishedAt).max(naturalOrder()).orElseThrow();
Instant oldest = undigested.stream().map(RunSummary::finishedAt).min(naturalOrder()).orElseThrow();
boolean quiet   = !inFlight && !newest.isAfter(now.minus(settle));
boolean overdue = oldest.isBefore(now.minus(maxWait));
return (quiet || overdue) ? Optional.of(new DigestWindow(scope, undigested, now)) : Optional.empty();
```

- [ ] **Step 5: Write the failing `RunDigestQueryTest`**, seeding runs through `RunRepository`:
      a `COMPLETED` and a `FAILED` `PULSE` run appear in `undigested(PULSE)`; a `CANCELLED` one
      does not; a `FULL` one does not; `hasRunsInFlight(PULSE)` is true with a `QUEUED` row and
      false without; `markDigested` returns 2 then 0 for the same ids, and the runs leave
      `undigested`.

- [ ] **Step 6: Implement the repository methods and service delegates**, add `runner` to
      `reporting`'s `allowedDependencies`, add the five properties with their defaults.

- [ ] **Step 7: `./mvnw test -Pfast`** green, `FlywayMigrationTest` included. **Commit.**

```bash
git commit -am "feat(runner): digest window claim over the run table"
```

---

### Task 4: The digest model and §11.1's predicate

Pure value types and the one predicate the whole plan turns on. No database, no Spring — the same
reason `checks` and `findings` have none (§5.1).

**Files:**
- Create: `reporting/DigestSection.java`, `reporting/SiteDigest.java`, `reporting/Digest.java`
- Test: `reporting/DigestPolicyTest.java`

**Interfaces (produces):**
- `record DigestSection(List<FindingView> shown, int total)` with `int omitted()`.
- `record SiteDigest(long siteId, String siteName, long runId, RunStatus status, Instant finishedAt,
  String errorMessage, boolean partialCoverage, DigestSection news, DigestSection regressions,
  int errorCount, int fixedCount, int stillOpenCount, int knownCount)`, where `errorCount` is the
  number of `NEW` + `REGRESSED` findings at `ERROR` severity — §11.1's predicate, counted once at
  assembly. Carries `boolean failed()` and `boolean notable()` (`errorCount > 0 || failed()`).
- `record Digest(RunScope scope, Instant closedAt, List<SiteDigest> sites)` with
  `Digest restrictedTo(Set<Long> siteIds)`, `boolean notifiable()`, `boolean allClear()`,
  `int errorTotal()` (the §11.1 count across the restriction) and `int failedRuns()`.

- [ ] **Step 1: Write the failing `DigestPolicyTest`.** The assertions, one per §11.1 bullet:
      a `PULSE` digest whose sites have `errorCount == 0` and no failure is **not** notifiable
      (*"a nightly pulse with no changes sends nothing"*); one `ERROR` on one site makes it
      notifiable; a `FAILED` run alone makes it notifiable with `errorTotal() == 0`; a `DEEP`
      digest with nothing wrong **is** notifiable and reports `allClear()`; a `PULSE` digest that
      is `allClear()` is not notifiable. Plus the D56 case, which is the one a reviewer should
      look hardest at: a two-site digest where only site B is notable, `restrictedTo(Set.of(A))`
      is **not** notifiable, and `restrictedTo(Set.of(B))` is.

- [ ] **Step 2: Run it and watch it fail.**

- [ ] **Step 3: Implement the three records.** `notifiable()` is
      `scope == DEEP || sites.stream().anyMatch(SiteDigest::notable)` and nothing else — no
      time-dependent expression, per D49's reasoning applied to mail: the predicate must give the
      same answer at assembly, at render and at any later reading of the outbox row.

- [ ] **Step 4: `./mvnw test -Pfast`** green. **Commit.**

```bash
git commit -am "feat(reporting): digest model and the 11.1 notification predicate"
```

---

### Task 5: Assembling a window from the database

**Files:**
- Create: `reporting/DigestAssembler.java`
- Test: `reporting/DigestAssemblerTest.java` (extends `AbstractPostgresTest`)

**Interfaces (consumes):** `DigestWindow` (Task 3), `Digest`/`SiteDigest`/`DigestSection` (Task 4),
`FindingViewFactory` (Task 1), `FindingService.diffOf(siteId, runId)`, `SiteService.summary(id)`.

**Interfaces (produces):**
- `DigestAssembler.assemble(DigestWindow window, Locale locale) → Digest`. The per-site cap is
  read from the injected `ReportingProperties.digestMaxFindings()`, not passed by the caller —
  the cycle has no business knowing it.

- [ ] **Step 1: Write the failing `DigestAssemblerTest`.** Seed one site, one `COMPLETED` run and
      findings through `FindingService.record` so the sections come from `DIFF_SQL` rather than
      from hand-set columns — the section a finding lands in is exactly what D47 decided and must
      not be re-derived here. Assertions: a `NEW` `ERROR` and a `NEW` `WARN` both appear in
      `news.shown`, an `INFO` does not but is not lost from `news.total`; `errorCount` counts the
      `ERROR` only; a `MUTED` finding appears in neither section and lands in `knownCount`
      (the D47 guarantee, asserted here because this is where a regression would show up as mail);
      twelve `NEW` findings against `digestMaxFindings = 10` give `shown.size() == 10`,
      `total == 12`, `omitted() == 2`, and the ten shown are the highest severity first, because
      `DIFF_SQL` already orders that way and the cap must not resort them; a `FAILED` run yields a
      `SiteDigest` with `failed()`, its `errorMessage`, and empty sections without ever querying
      the diff.

- [ ] **Step 2: Run it and watch it fail.**

- [ ] **Step 3: Implement `DigestAssembler`.** One `diffOf` per completed run, one
      `SiteService.summary` per site, the renderer from Task 1 for each listed finding. A `FAILED`
      run skips the diff entirely: it has no materialisation, and calling `diffOf` on it would
      report the *previous* run's open findings as though this one had observed them.

- [ ] **Step 4: `./mvnw test -Pfast`** green. **Commit.**

```bash
git commit -am "feat(reporting): assemble a window into a per-site digest"
```

---

### Task 6: Rendering the mail

Multipart HTML plus plain text, both Thymeleaf, German from message keys (§11.5, §13.1).

**Files:**
- Create: `reporting/DigestMailRenderer.java`,
  `src/main/resources/templates/mail/digest.html`, `src/main/resources/templates/mail/digest.txt`
- Modify: `src/main/resources/messages.properties`
- Test: `reporting/DigestMailRendererTest.java`

**Interfaces (produces):**
- `DigestMailRenderer.render(String recipient, Digest digest, String baseUrl, Locale locale) → OutboundMail`.

The subject is assembled from a tier label and up to two fragments so German plurals stay in the
properties file rather than in a format string:

```properties
ui.mail.digest.betreff=WebTestHelper – {0}: {1}
ui.mail.digest.betreff.fehler_einzahl=1 neuer oder wiederkehrender Fehler
ui.mail.digest.betreff.fehler={0} neue oder wiederkehrende Fehler
ui.mail.digest.betreff.fehlgeschlagen_einzahl=1 Prüflauf fehlgeschlagen
ui.mail.digest.betreff.fehlgeschlagen={0} Prüfläufe fehlgeschlagen
ui.mail.digest.betreff.alles_gut=alles in Ordnung
ui.mail.digest.titel=Prüfbericht
ui.mail.digest.einleitung=Diese Sammelmail fasst {0} geprüfte Websites zusammen.
ui.mail.digest.alles_gut=Auf allen geprüften Websites ist alles in Ordnung.
ui.mail.digest.neu=Neu
ui.mail.digest.wiederkehrend=Wieder aufgetreten
ui.mail.digest.weitere=und {0} weitere
ui.mail.digest.behoben={0} behoben
ui.mail.digest.offen={0} weiterhin offen
ui.mail.digest.bekannt={0} bereits bewertet
ui.mail.digest.lauf_fehlgeschlagen=Der Prüflauf ist fehlgeschlagen und hat nichts geprüft.
ui.mail.digest.teilweise=Der Lauf hat sein Budget erreicht und nicht die ganze Website geprüft.
ui.mail.digest.lauf_oeffnen=Lauf ansehen
ui.mail.digest.befund_oeffnen=Befund ansehen
```

- [ ] **Step 1: Write the failing `DigestMailRendererTest`** against a hand-built `Digest` (no
      database — Task 4's records are plain values). Assertions: the subject of a two-error
      `PULSE` digest reads `WebTestHelper – Puls-Prüfung: 2 neue oder wiederkehrende Fehler`; a
      one-error digest uses the singular key; a `DEEP` all-clear subject ends in `alles in
      Ordnung`; the HTML contains the site name, the finding's rendered German message, and the
      **absolute** links `https://wth.example/laeufe/{runId}` and
      `https://wth.example/befunde/{id}`; the text part contains the same message and is not
      empty; a capped section renders *und 2 weitere*; a failed run renders its error message; and
      the rendered HTML contains no `??` (Thymeleaf's unresolved-key marker) and no
      `NEW`/`REGRESSED`/`ERROR` identifier (§13.1).

- [ ] **Step 2: Run it and watch it fail.**

- [ ] **Step 3: Write the two templates and the renderer.** Links are built as
      `baseUrl + "/befunde/" + id` in the template from the `baseUrl` variable — §11.4 makes base
      URL a required setting precisely so this is not `th:href="@{...}"`, which would render a
      relative path in a mail client. Severity is a label from `ui.severity.*`, never the enum
      name. Inline CSS only, no external stylesheet.

- [ ] **Step 4: `./mvnw test -Pfast`** green, `UiMessageKeyTest` included — it scans
      `templates/**`, so the two new templates are covered automatically. **Commit.**

```bash
git commit -am "feat(reporting): digest mail rendering, HTML and text"
```

---

### Task 7: The cycle — one mail per recipient, then stamp

Where D53, D54 and D56 meet the outbox plan 5 built.

**Files:**
- Create: `reporting/DigestService.java`, `reporting/DigestScheduledJob.java`
- Modify: `src/main/resources/application.properties` (pool size 5, the five digest properties),
  `src/test/resources/application.properties` (`digest-enabled=false`)
- Test: `reporting/DigestServiceTest.java` (extends `AbstractPostgresTest`)

**Interfaces (produces):**
- `DigestService.runCycle(Instant now) → int` — the number of notifications enqueued across all
  three scopes. `@Transactional`: the enqueue and the stamp are one decision, and splitting them
  either mails twice or never.
- `DigestScheduledJob.cycle()` — `@Scheduled(fixedDelayString = "${webtesthelper.reporting.digest-interval:2m}")`,
  `@ConditionalOnProperty("webtesthelper.reporting.digest-enabled")`, passing `Instant.now()`.
  Same shape as `MuteExpiryJob`; the `now` parameter is what makes the service testable without
  waiting five minutes.

Per scope, the cycle is: close the window (Task 3) → assemble (Task 5) → resolve recipients for
the window's sites (Task 2) → invert to recipient → sites → for each, restrict, test §11.1, render
and enqueue → stamp **every** run in the window, mailed or not.

```java
for (var e : bySiteInverted.entrySet()) {                 // recipient -> their sites in this window
    Digest theirs = digest.restrictedTo(e.getValue());    // D56: the predicate sees only their sites
    if (!theirs.notifiable()) continue;                   // 11.1: a quiet pulse sends nothing
    outbox.enqueue(renderer.render(e.getKey(), theirs, appSettings.baseUrl(), Locale.GERMAN));
}
runs.markDigested(window.runIds(), now);                  // unconditional: an unmailed window must not reopen
```

- [ ] **Step 1: Write the failing `DigestServiceTest`.** Seed two sites in one `PULSE` window —
      site A with recipient `a@example.test` and a `NEW` `ERROR`, site B with no recipient of its
      own, a configured global fallback `team@example.test`, and a `FAILED` run. Assertions:
      a cycle run **before** the settle elapses enqueues nothing and stamps nothing; a cycle at
      settle + 1 min enqueues exactly two notifications; `a@example.test`'s HTML names site A and
      **not** site B; `team@example.test`'s names site B's failure; both runs now carry
      `digest_sent_at`; a second cycle enqueues nothing. Then the quiet case: a fresh `PULSE`
      window with no errors and no failures enqueues **nothing** but still stamps its run — assert
      the stamp, because an unstamped quiet window is re-assembled every two minutes forever. Then
      a site with neither a recipient nor a fallback: nothing enqueued, run stamped, and a WARN
      logged rather than an exception.

- [ ] **Step 2: Run it and watch it fail.**

- [ ] **Step 3: Implement `DigestService` and the job**, raise the pool to 5 with a comment naming
      the five jobs (D60), and set `digest-enabled=false` in the test properties.

- [ ] **Step 4: `./mvnw test -Pfast`** green. **Commit.**

```bash
git commit -am "feat(reporting): digest cycle, one mail per recipient per window"
```

---

### Task 8: The screens

Recipients on the site detail, the fallback in Settings, and the topic that explains when mail
arrives. §13.4: the consequence is stated before the click — a site with no recipients of its own
must **say** which address will actually be mailed instead.

**Files:**
- Create: `src/main/resources/templates/fragments/empfaenger.html`,
  `web/RecipientController.java`, `src/main/resources/help/benachrichtigungen.md`
- Modify: `src/main/resources/templates/websites/detail.html`,
  `src/main/resources/templates/einstellungen/index.html`, `web/SiteController.java`,
  `web/SettingsController.java`, `web/SettingsForm.java`, `web/SecurityConfig.java`,
  `src/main/resources/messages.properties`
- Test: `web/RecipientControllerTest.java`, `web/SecurityRulesTest.java`,
  `web/SettingsControllerTest.java`, `web/HelpTopicsTest.java`

- [ ] **Step 1: Write the failing `RecipientControllerTest`.** Following plan 7's execution
      finding — *a controller test that asserts model attributes proves nothing about a screen
      whose behaviour lives in a template* — every assertion here is against the **rendered body**:
      the site detail of a site with two recipients renders both addresses; a site with none
      renders the fallback address and the §13.4 sentence naming it; a site with none and no
      fallback renders the warning that **no mail will be sent about this site**; POSTing a
      malformed address re-renders the panel with the field error and adds nothing; POSTing a valid
      one redirects to `/websites/{id}` and the address is on the page. In `SecurityRulesTest`: a
      `USER` POSTing to `/websites/1/empfaenger` gets 403.

- [ ] **Step 2: Run them and watch them fail.**

- [ ] **Step 3: Build the fragment, the controller and the two Settings fields**, plus the
      `?` affordance linking `/hilfe/benachrichtigungen`. `SettingsForm` gains one textarea for the
      fallback list, validated the same way `RecipientService.add` validates.

- [ ] **Step 4: Write `help/benachrichtigungen.md`** — what triggers a mail (§11.1 in four
      sentences), that one mail covers every site in a window and not one per site, that a muted
      finding is not mailed even if it comes back (D47, the question a colleague will actually
      ask), and that a failed relay shows up in the Postausgang rather than in silence.
      `HelpTopicsTest` moves from five topics to six.

- [ ] **Step 5: `./mvnw test -Pfast`** green — `UiMessageKeyTest`, `EnumLabelsTest` and
      `HelpTopicsTest` included. **Commit.**

```bash
git commit -am "feat(web): recipients per site, fallback address, notification handbook"
```

---

### Task 9: The acceptance test

One test that proves the sentence at the top of this plan, end to end, through a real SMTP server
and no browser.

**Files:**
- Test: `reporting/DigestAcceptanceTest.java` (extends `AbstractPostgresTest`, `GreenMailExtension`
  registered as in `MailRelayAcceptanceTest`)

- [ ] **Step 1: Write the test.** Three sites in one nightly `PULSE` window: *Kundenseite A* with
      recipient `betreuer-a@example.test` and one new `ERROR` plus one new `WARN`; *Kundenseite B*
      with the same recipient and nothing changed; *Kundenseite C* with recipient
      `betreuer-c@example.test` and a `FAILED` run. Point `AppSettings` at GreenMail and set the
      base URL to `https://wth.example`. Then, in order:

      1. Cycle at settle + 1 min, then run the outbox dispatcher.
      2. GreenMail received **two** messages, not three and not one per site.
      3. `betreuer-a@example.test`'s message is multipart, its subject names one new error
         (the `WARN` is listed but not counted by §11.1's predicate), its HTML names both A and B
         with B as a quiet count, and it carries `https://wth.example/befunde/`.
      4. `betreuer-c@example.test`'s message names the failed run and neither A nor B.
      5. A second cycle plus dispatch adds no message.
      6. A `DEEP` run for site A with nothing changed, one more cycle and dispatch: one further
         message to `betreuer-a@example.test` whose subject ends *alles in Ordnung* — §11.1's
         periodic proof the system is alive.
      7. The same `DEEP`-less `PULSE` case for a quiet window sends nothing, and the run is
         stamped all the same.

- [ ] **Step 2: Run it and watch it fail**, then make it pass. If a step needs production code
      that Tasks 1–8 did not produce, that is a finding for the Execution findings section, not a
      quiet edit.

- [ ] **Step 3: Full suite, then commit.** `./mvnw test` — browser tests included. Report the count.

```bash
git commit -am "test(reporting): acceptance for the nightly digest"
```

---

## Completion check

Run before declaring the plan done:

- [ ] `./mvnw test` green, browser tests included. Report the count.
- [ ] `ModularityTest` passes and `reporting` declares exactly
      `{"model", "catalog", "checks", "findings", "runner"}` — nothing more.
      `runner/package-info.java` is **unchanged**: if `runner` gained a dependency on `reporting`,
      D53 was violated and a run can now fail because of mail.
- [ ] `grep -rn "reporting" src/main/java/dev/hendrikhoemberg/webtesthelper/runner` returns
      nothing.
- [ ] `FlywayMigrationTest` passes — `V16` and `V17` apply to an empty database and
      `ddl-auto=validate` accepts `NotificationRecipientEntity` and the extended `run` table.
- [ ] `UiMessageKeyTest`, `EnumLabelsTest` and `HelpTopicsTest` pass: every new `#{…}` resolves,
      every new key is `ui.`-prefixed, and `benachrichtigungen` is a real Markdown file.
- [ ] `grep -rn "NEW\|REGRESSED\|ERROR" src/main/resources/templates/mail` returns nothing — no
      section or severity identifier in a mail template (§13.1).
- [ ] `webtesthelper.reporting.digest-enabled=false` is present in
      `src/test/resources/application.properties` (D33), and `spring.task.scheduling.pool.size=5`
      names its five jobs in a comment.
- [ ] Verbatim-code budget: ``awk '/^```/{f=!f;next} f{n++} END{print n}' docs/superpowers/plans/2026-08-26-webtesthelper-p8-digest.md`` is under 150.

## Deliberately not in this plan

- **Expired mutes in the digest.** Plan 7 left the decision here; the answer is no. The sweep is
  hourly and fleet-wide while a digest is per tier, so a mute expiring on Tuesday would be
  reported in Wednesday's pulse digest **and** again in Sunday's full digest. Re-reporting is free
  and correct on a screen and is noise in mail. `MuteSweepResult` stays as plan 7 left it, and
  plan 9's dashboard gets the panel.
- **A per-recipient digest frequency or an unsubscribe.** §11.2 aggregates per tier, and a
  recipient who wants less mail is asking for a tier to be disabled on their sites — which §9
  already allows, on the schedule where the cost actually is.
- **Retrying an assembly.** A window is stamped when it is dispatched, mailed or not. If rendering
  throws, the transaction rolls back and the next cycle two minutes later tries the same window
  again; there is nothing to retry by hand.
- **A digest for `CANCELLED` runs.** A superseded stale run has no findings and no failure worth
  mailing; the run it was superseded by reports for it.
- **The mail-health banner.** D35 shipped it in plan 5 and `HealthBannerAdvice` already reads
  `failedCount()`. Digest mails are outbox rows like any other and light it up for free.
- **SSE, the dashboard's mail panel, users and concurrency.** Plan 9, per D44 and the roadmap.

## What plan 9 consumes

- **`Digest`/`SiteDigest` are already the dashboard's per-site shape** — traffic light, error
  count, last run status — assembled from the same `diffOf` the report uses. The dashboard should
  query, not re-derive: `errorCount` here and the panel's number must agree.
- **`RecipientService.effectiveFor` answers "who hears about this site"**, which the dashboard's
  health row will want next to "when does it next run".
- **`spring.task.scheduling.pool.size` is at 5 and all five threads are spoken for** (outbox,
  tick, retention, mute sweep, digest). The dashboard's HTMX poll (D44) needs no job, which is one
  more reason D44 withdrew the SSE promise.
- **The renderer lives in `reporting` now (D57).** Plan 9's dashboard should use
  `FindingViewFactory` from there rather than re-importing anything from `web`.

---

## Execution findings

*(Filled in after the plan executes. Do not edit anything above this line once execution has
started — the code is the truth.)*
