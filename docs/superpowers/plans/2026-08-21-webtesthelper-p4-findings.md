# WebTestHelper Plan 4 — Fingerprints, Materialisation and the Coverage-Scoped Diff

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Turn the transient `CheckFinding`s the check engine already produces into persistent
findings with identity, history and a lifecycle — fingerprinted, promoted to site-wide where a
subject is everywhere, re-verified so transient failures never become findings at all, and
diffed **only within what the run actually covered**. After this plan the product answers the
question it exists to answer: *what changed since last time?*

**Architecture:** A new `findings` module, depending on `model` alone (deviation D1), holds
fingerprinting, materialisation and the diff. It is reached only from `runner`, which grows one
pipeline stage: crawl → verify → page checks → site checks → **re-verify → materialise → diff**
(§5.3). Materialisation is a pure function over `List<CheckFinding>`; persistence is a
`JdbcTemplate` batch (§6.5). The network half of re-verification lives in `crawler`, where the
sockets already are, and consumes `model` types only.

**Tech Stack:** Java 25, Spring Boot 4.1.1, PostgreSQL 17 via Testcontainers, Jackson 3
(`tools.jackson`) for the two `jsonb` columns. **No new dependency.** Playwright/Chromium in
one test class only.

**Spec:** `docs/superpowers/specs/2026-08-21-webtesthelper-design.md`; §-references point there.
**Roadmap:** `…-phase-1-roadmap.md` — plan 4 of 5. **Predecessors:** p1, p2a, p2b, p3a, p3b, all
executed and on `main`; written from 3b's execution findings, under `CLAUDE.md`'s plan
calibration rules, which override `superpowers:writing-plans`' "No Placeholders" section:
signatures, paths and acceptance *assertions* are exact, obvious bodies are not written out.

**Ends with:** two consecutive runs against the fixture site producing identical fingerprints and
an empty *New* section — nothing flickers — a budget-capped third run resolving nothing it did
not reach, and a baseline acceptance that turns a first run's whole finding list into one
acknowledged block.

---

## Deviations and constraints

The roadmap's deviation table (D1–D22) and everything p1–p3b established apply unchanged and are
**not** restated here. `CLAUDE.md` holds the test rules. Five deviations are new; the roadmap
table carries their one-line form, and this is the reasoning:

- **D23 — re-verification is an HTTP re-check, and only of subjects the crawl never navigated.**
  §8 says failures are re-verified "with fresh contexts"; a second browser pass is a second crawl
  and Phase 1 does not pay for one. So a *browser* verdict is never overturned by an *HTTP*
  answer: the fixture's `/langsam` answers a `HEAD` in milliseconds and still exceeds Chromium's
  navigation budget, so an HTTP re-check would delete a true `PAGE_UNREACHABLE`. Re-verification
  therefore selects findings whose subject (a) verified `DEAD` in the first pass and (b) is not
  the URL of any snapshot. `PAGE_STATUS`, `PAGE_UNREACHABLE` and `REDIRECT_CHAIN` are out of
  scope by construction, which is the honest statement of what Phase 1 can prove. A finding is
  dropped only when the fresh status is `OK`.
- **D24 — `external_url_check.dependent_site_ids` does not fan findings out across sites in
  Phase 1** (3b's first open question). A finding needs a run, a location and coverage; one
  written for site B outside any run of B would sit outside every future coverage and could never
  be resolved (§6.4) — a permanently un-closable row. Site B's next run reads the cached `DEAD`
  and reports it there, with a location and inside coverage, which is §8.1's outcome one run
  later. The column stays for the Phase-2 sweep that shortens the delay.
- **D25 — a run's coverage is the frontier's `DONE` URLs *plus* every snapshot's final URL.**
  `covered_urls` records the URL that was *requested*; a finding's `locationKey` comes from the
  URL that *answered*. `/kontakt` → `/kontakt.html` is one page under two names (the same
  mismatch that killed a run before the plan-3 review), and without the union that page's
  findings would sit outside coverage forever and never resolve.
- **D26 — `occurrence_count` is recomputed from the occurrence rows, never accumulated.**
  `reclaimStale` re-executes a run under its original id (§14), so materialisation must be
  idempotent per `runId`. An accumulating `+=` would double every count on a reclaimed run.
- **D27 — the site-wide threshold is global configuration in Phase 1**, not a per-site column.
  §6.2 gives it as a default of 5; no site has needed a different one, and a column that nothing
  writes is a migration to undo.

New constraints this plan introduces:

- **`findings` may not import `crawler`, `checks`, `catalog` or `runner`.** It takes
  `List<CheckFinding>` and a `RunCoverage` and returns rows. `ModularityTest` enforces it.
- **Materialisation is a pure function.** `FindingMaterializer` has no clock, no database and no
  Spring; the observation instant is a parameter. This is what makes promotion testable at the
  threshold boundary without a crawl.
- **Every write of one run's findings is one transaction.** A half-materialised run would make
  the next run's diff wrong in both directions.
- **No new message keys.** This plan renders nothing; `CheckDocumentationTest` must stay green
  without additions, and the baseline's triage reason is stored as the German sentence it is,
  not as a key — the column also holds free text a human typed, and a renderer cannot tell the
  two apart.

## Decided constants

Rules from §6.2, §6.3 and §8. None needed a browser to establish; the one number the spec fixes
is the threshold, and its boundary semantics are the part that is easy to get wrong.

| Constant | Value | Why |
|---|---|---|
| `SITE_WIDE_THRESHOLD` | 5, `webtesthelper.findings.site-wide-threshold` | §6.2 default. **"More than"** — a subject on exactly 5 pages stays per-page, 6 promotes |
| fingerprint | `sha256` hex, 64 chars, fields joined by `\0` | §6.2. A separator no URL can contain; see Task 1 |
| site-scoped `locationKey` | `"*"` | already what `CheckFinding.locationKey()` returns for `observedOn == null` |
| occurrence `page_url` | `NULL` for a site-scoped finding | with `UNIQUE NULLS NOT DISTINCT`, so the dedupe still bites |
| section precedence | FIXED, NEW, REGRESSED, KNOWN, STILL_OPEN | first match wins; a regression is news even when the finding is acknowledged |
| site-wide resolution | only on a run with complete coverage | a partial crawl cannot disprove "on 312 pages" |
| re-verify attempts / delay | 2 attempts, 2 s doubling (100 ms in tests) | §8 "backoff"; the first pass was the zeroth attempt |
| re-verify drop rule | fresh status `OK` only | a flip to `UNVERIFIABLE` is not evidence the link works |
| `findings_total` | materialised findings observed in this run | was the raw pre-promotion count; the row now means something a user can count |

---

### Task 1: The schema and the shared vocabulary

**Files:**
- Create: `src/main/resources/db/migration/V9__finding.sql`
- Create: `model/ObservedStatus.java`, `model/TriageStatus.java`, `model/RunCoverage.java`
- Create: `findings/package-info.java`, `findings/Fingerprint.java`, `findings/FindingProperties.java`
- Modify: `runner/persistence/RunEntity.java`, `runner/RunSummary.java`, `runner/RunService.java`
  (`toSummary`), `runner/package-info.java`, `src/main/resources/application.properties`
- Test: `findings/FingerprintTest.java`, `model/RunCoverageTest.java` (both browser-free)

**Interfaces (produces):**
- `enum ObservedStatus { ACTIVE, RESOLVED }`, `enum TriageStatus { UNTRIAGED, ACKNOWLEDGED,
  MUTED, WONT_FIX }` — the two orthogonal axes of §6.3, in `model` beside `RunStatus` because
  `findings` writes them and Plan 5's web layer reads them.
- `record RunCoverage(Set<CheckType> checkTypes, Set<String> locationKeys, boolean complete)`
  with `static RunCoverage of(Collection<String> checkTypeNames, Collection<String> coveredUrls,
  Collection<String> snapshotUrls, boolean partialCoverage)` — the factory maps every URL through
  `UrlNormalizer.locationKeyOf` and unions the two sources (D25).
- `final class Fingerprint` — `static String of(long siteId, CheckType type, String subjectKey,
  String locationKey)`.
- `record FindingProperties(int siteWideThreshold)` bound to `webtesthelper.findings`
  (the existing `@ConfigurationPropertiesScan` picks it up).
- `RunEntity` gains `findingsNew` and `findingsResolved` (`int`); `RunSummary` gains the same two
  `int` components after `findingsTotal`.

- [ ] **Step 1: Write `V9__finding.sql`**

```sql
-- Findings are the product (spec 6.2). Fingerprint is the identity; the two status columns are
-- the orthogonal axes of spec 6.3 — collapsing them is how acknowledging a finding erases the
-- fact that it is still broken.
CREATE TABLE finding (
    id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    site_id BIGINT NOT NULL REFERENCES site (id) ON DELETE CASCADE,
    fingerprint TEXT NOT NULL,
    check_type TEXT NOT NULL,
    subject_key TEXT NOT NULL,
    location_key TEXT NOT NULL,          -- '*' when site-wide (spec 6.2's two-tier resolution)
    severity TEXT NOT NULL,
    message_key TEXT NOT NULL,
    message_args JSONB NOT NULL DEFAULT '[]'::jsonb,
    evidence JSONB,
    observed_status TEXT NOT NULL DEFAULT 'ACTIVE'
        CHECK (observed_status IN ('ACTIVE','RESOLVED')),
    triage_status TEXT NOT NULL DEFAULT 'UNTRIAGED'
        CHECK (triage_status IN ('UNTRIAGED','ACKNOWLEDGED','MUTED','WONT_FIX')),
    triage_reason TEXT,
    triaged_at TIMESTAMPTZ,
    -- Run ids are history, not live references: findings are kept indefinitely (spec 6.5) and
    -- must survive any later pruning of run rows, so these three carry no foreign key.
    first_seen_run BIGINT NOT NULL,
    last_seen_run BIGINT NOT NULL,
    resolved_at_run BIGINT,              -- kept after a regression: it is what makes one visible
    occurrence_count INTEGER NOT NULL DEFAULT 0,
    page_count INTEGER NOT NULL DEFAULT 0,   -- pages in the most recent run: "on 312 pages"
    first_seen_at TIMESTAMPTZ NOT NULL,
    last_seen_at TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL DEFAULT 0
);

CREATE UNIQUE INDEX ux_finding_fingerprint ON finding (fingerprint);
CREATE INDEX ix_finding_site_open ON finding (site_id, observed_status, triage_status);
CREATE INDEX ix_finding_last_seen ON finding (last_seen_run);
CREATE INDEX ix_finding_resolved_at ON finding (resolved_at_run);

-- Occurrences keep every exact page, so promotion to '*' never loses detail (spec 6.2).
CREATE TABLE finding_occurrence (
    id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    finding_id BIGINT NOT NULL REFERENCES finding (id) ON DELETE CASCADE,
    run_id BIGINT NOT NULL,
    page_url TEXT,                       -- NULL for a site-scoped finding: there is no one page
    severity TEXT NOT NULL,
    message_key TEXT NOT NULL,
    message_args JSONB NOT NULL DEFAULT '[]'::jsonb,
    evidence JSONB,
    observed_at TIMESTAMPTZ NOT NULL
);

-- One row per finding per page per run. NULLS NOT DISTINCT so the site-scoped case dedupes too,
-- and so re-materialising a reclaimed run (spec 14) is an update rather than a duplicate.
CREATE UNIQUE INDEX ux_finding_occurrence ON finding_occurrence (finding_id, run_id, page_url)
    NULLS NOT DISTINCT;
CREATE INDEX ix_finding_occurrence_run ON finding_occurrence (run_id);

ALTER TABLE run ADD COLUMN findings_new INTEGER NOT NULL DEFAULT 0;
ALTER TABLE run ADD COLUMN findings_resolved INTEGER NOT NULL DEFAULT 0;
```

- [ ] **Step 2: Add the two enums and `RunCoverage`** (signatures above). `RunCoverageTest`,
      browser-free, red first:
- `/kontakt.html` in `coveredUrls` and `/kontakt` in `snapshotUrls` both appear as location keys
  (D25 — the redirect case).
- a URL with a query keeps it (`/suche?q=x`), matching `NormalizedUrl.locationKey()`.
- `partialCoverage = true` gives `complete() == false`.
- an unparseable entry does not throw and does not appear.
- a check-type name that is not a `CheckType` is ignored rather than throwing — the column is
  data, and a renamed enum constant must not make every old run unreadable.

- [ ] **Step 3: `FingerprintTest`, red.** Assertions:
- stable: two calls with equal input give equal output; 64 lowercase hex characters.
- the same subject on two sites fingerprints differently.
- `("a\0b", "c")` and `("a", "b\0c")` differ — the separator is not splice-able. A URL
  cannot contain a NUL, which is *why* it is the separator; the test pins the property anyway.
- `locationKey = "*"` differs from `locationKey = "/"`.
- changing only the `CheckType` changes the fingerprint.

- [ ] **Step 4: Implement `Fingerprint`.** The joining rule is the whole algorithm and the part a
      future reader would otherwise re-invent differently:

```java
public static String of(long siteId, CheckType type, String subjectKey, String locationKey) {
    String joined = siteId + "\0" + type.name() + "\0" + subjectKey + "\0" + locationKey;
    byte[] digest = MessageDigest.getInstance("SHA-256")   // wrap the checked exception
            .digest(joined.getBytes(StandardCharsets.UTF_8));
    return HexFormat.of().formatHex(digest);
}
```

- [ ] **Step 5: The module and the run columns.** `findings/package-info.java` declares
      `@ApplicationModule(displayName = "Findings", allowedDependencies = {"model"})`;
      `runner/package-info.java` adds `"findings"` to its list. `RunEntity` and `RunSummary` gain
      the two counters, `RunService.toSummary` passes them through. `application.properties`:
      `webtesthelper.findings.site-wide-threshold=5`.

- [ ] **Step 6: Run and commit** — `./mvnw test -Pfast`. `FlywayMigrationTest` and
      `ddl-auto=validate` are what prove V9 and `RunEntity` agree; `ModularityTest` proves the
      new module's boundary.

---

### Task 2: Materialisation — fingerprints, promotion, occurrences

**Files:**
- Create: `findings/FindingOccurrence.java`, `findings/MaterialisedFinding.java`,
  `findings/FindingMaterializer.java`
- Test: `findings/FindingMaterializerTest.java` (browser-free, no Spring, no database)

**Interfaces (produces):**
- `record FindingOccurrence(String pageUrl, Severity severity, String messageKey,
  List<String> messageArgs, Evidence evidence)` — `pageUrl` null for a site-scoped finding.
- `record MaterialisedFinding(String fingerprint, CheckType type, Severity severity,
  String subjectKey, String locationKey, String messageKey, List<String> messageArgs,
  Evidence evidence, List<FindingOccurrence> occurrences)` with `int pageCount()`
  (`occurrences.size()`, which *is* a page count because occurrences are deduped by page).
- `final class FindingMaterializer` — `static List<MaterialisedFinding> materialise(long siteId,
  List<CheckFinding> findings, int siteWideThreshold)`.

The algorithm in words, because it is three obvious grouping passes and one non-obvious rule:
group by `(type, subjectKey)`; count the distinct page location keys in each group; if that count
is **greater than** the threshold the whole group becomes one finding at `locationKey = "*"`,
otherwise the group splits into one finding per location key. Then, inside each resulting
finding, occurrences are deduped by page and the finding's own severity, message and evidence are
taken from its representative occurrence.

- [ ] **Step 1: `FindingMaterializerTest`, red.** Hand-built `CheckFinding`s — one static helper
      building a `CheckFinding` from `(type, subject, pagePath, severity, messageKey)` keeps the
      class readable. Assertions:
- one subject on **4** pages, threshold 5 → 4 findings, each `locationKey` its own page path,
  each with one occurrence.
- the same on **5** pages → still 5 findings. **The boundary assertion this test exists for:**
  §6.2 says *more than* the threshold.
- the same on **6** pages → 1 finding, `locationKey` `"*"`, 6 occurrences, `pageCount() == 6`,
  and its fingerprint equals `Fingerprint.of(siteId, type, subject, "*")`.
- promotion is per subject: a second subject on 2 pages in the same input stays per-page.
- a site-scoped `CheckFinding` (`observedOn == null`) yields `locationKey` `"*"` and one
  occurrence whose `pageUrl` is null — with no promotion arithmetic involved.
- two `MEDIA_PLAYABLE` findings for a source-less element on **one** page (identical type,
  subject, location and message key) collapse to one finding with **one** occurrence. This closes
  the decision 3a parked for this plan: they are one defect, and two rows would be two identical
  lines in the report.
- occurrences that disagree on severity: the finding carries `Severity.max` of them, each
  occurrence keeps its own.
- the representative message is the **highest-severity** occurrence's, ties broken by the lowest
  `pageUrl` — so the headline never contradicts the severity beside it, and the choice is
  deterministic.
- materialising the same input twice yields equal lists in equal order (the diff depends on it).
- an empty input yields an empty list, not a crash.

- [ ] **Step 2: Implement the three types.** `LinkedHashMap` throughout, so order follows input
      order and the determinism assertion holds without sorting the world.

- [ ] **Step 3: Run and commit** — `./mvnw test -Pfast`.

---

### Task 3: The store, the coverage-scoped diff and the report sections

**Files:**
- Create: `findings/Finding.java`, `findings/ReportSection.java`, `findings/RunDiff.java`,
  `findings/FindingStore.java`, `findings/FindingService.java`
- Test: `findings/FindingStoreTest.java`, `findings/FindingServiceDiffTest.java`
  (both `AbstractPostgresTest`, browser-free)

**Interfaces (produces):**
- `record Finding(long id, long siteId, String fingerprint, CheckType type, String subjectKey,
  String locationKey, Severity severity, String messageKey, List<String> messageArgs,
  Evidence evidence, ObservedStatus observed, TriageStatus triage, String triageReason,
  long firstSeenRun, long lastSeenRun, Long resolvedAtRun, int occurrenceCount, int pageCount,
  Instant firstSeenAt, Instant lastSeenAt)` — one row, read side only.
- `enum ReportSection { FIXED, NEW, REGRESSED, KNOWN, STILL_OPEN }` — declared in precedence
  order, which is also the order the SQL evaluates.
- `record RunDiff(long runId, Map<ReportSection, List<Finding>> bySection)` with
  `List<Finding> of(ReportSection)`, `int count(ReportSection)` and `int observedTotal()`
  (everything but `FIXED`).
- `FindingStore` (`@Repository`): `List<Long> upsertAll(long siteId, long runId,
  List<MaterialisedFinding> findings, Instant observedAt)` returning the finding ids in input
  order, `void insertOccurrences(List<Long> findingIds, long runId, List<MaterialisedFinding>
  findings, Instant observedAt)`, `void recountOccurrences(List<Long> findingIds)`,
  `int resolveOutsideRun(long siteId, long runId, RunCoverage coverage)`,
  `RunDiff diffOf(long siteId, long runId)`.
- `FindingService` (`@Service`): `@Transactional RunDiff record(long runId, long siteId,
  List<CheckFinding> findings, RunCoverage coverage, Instant observedAt)` and
  `RunDiff diffOf(long siteId, long runId)`.

`record` is four statements in one transaction — upsert, occurrences, recount, resolve — and then
it *reads the diff back out of the database* rather than computing it in memory. One derivation,
so the number in the report and the number in the table can never disagree.

- [ ] **Step 1: The upsert.** The `DO UPDATE` set-list is the lifecycle of §6.3 written down —
      what a re-observation changes and, more importantly, what it must not:

```sql
INSERT INTO finding (site_id, fingerprint, check_type, subject_key, location_key, severity,
                     message_key, message_args, evidence, observed_status, triage_status,
                     first_seen_run, last_seen_run, page_count, first_seen_at, last_seen_at)
VALUES (?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, 'ACTIVE', 'UNTRIAGED', ?, ?, ?, ?, ?)
ON CONFLICT (fingerprint) DO UPDATE SET
    observed_status = 'ACTIVE',          -- a re-observation revives a RESOLVED finding …
    severity        = excluded.severity, -- … but resolved_at_run stays: it is the regression flag
    message_key     = excluded.message_key,
    message_args    = excluded.message_args,
    evidence        = excluded.evidence,
    last_seen_run   = excluded.last_seen_run,
    last_seen_at    = excluded.last_seen_at,
    page_count      = excluded.page_count,
    version         = finding.version + 1
RETURNING id
```

`triage_status`, `triage_reason`, `triaged_at`, `first_seen_run` and `first_seen_at` are absent
from the set-list on purpose: the triage axis is human-owned and a run may not touch it.
`RETURNING id` makes the ids available without a second lookup — issue it per row with
`jdbc.queryForObject(..., Long.class)` rather than `batchUpdate`, which cannot return keys. A
run's finding count is in the hundreds; the occurrence insert, which is the row count that grows
with site size, stays a batch.

- [ ] **Step 2: Occurrences, and the recount.** `insertOccurrences` is a `batchUpdate` with
      `ON CONFLICT (finding_id, run_id, page_url) DO UPDATE SET severity = excluded.severity,
      message_key = excluded.message_key, message_args = excluded.message_args,
      evidence = excluded.evidence, observed_at = excluded.observed_at`. Then D26's recount,
      which is why nothing accumulates:

```sql
UPDATE finding f SET occurrence_count = o.n
  FROM (SELECT finding_id, count(*) AS n FROM finding_occurrence
         WHERE finding_id = ANY(?) GROUP BY finding_id) o
 WHERE f.id = o.finding_id
```

- [ ] **Step 3: The coverage-scoped resolution.** §6.4 is one statement, and every clause in it
      is load-bearing:

```sql
UPDATE finding
   SET observed_status = 'RESOLVED', resolved_at_run = ?, version = version + 1
 WHERE site_id = ?
   AND observed_status = 'ACTIVE'
   AND last_seen_run <> ?                -- not re-observed by this run
   AND check_type = ANY(?)               -- coverage: only checks this run actually ran
   AND (location_key = ANY(?)            -- coverage: only pages this run actually visited
        OR (location_key = '*' AND ?))   -- site-wide: only on a run with complete coverage
```

- [ ] **Step 4: The sections.** One query, one `CASE`, and the branch order *is* the precedence
      table:

```sql
SELECT f.*, CASE
    WHEN f.observed_status = 'RESOLVED' AND f.resolved_at_run = ? THEN 'FIXED'
    WHEN f.first_seen_run = ?                                     THEN 'NEW'
    WHEN f.resolved_at_run IS NOT NULL                            THEN 'REGRESSED'
    WHEN f.triage_status <> 'UNTRIAGED'                           THEN 'KNOWN'
    ELSE 'STILL_OPEN' END AS section
  FROM finding f
 WHERE f.site_id = ?
   AND (f.last_seen_run = ? OR (f.observed_status = 'RESOLVED' AND f.resolved_at_run = ?))
 ORDER BY CASE f.severity WHEN 'ERROR' THEN 0 WHEN 'WARN' THEN 1 ELSE 2 END,
          f.check_type, f.location_key, f.subject_key
```

The `WHERE` is what keeps §6.4 honest on the read side too: a finding the run did not cover is
neither resolved nor listed. It is simply not this run's business.

- [ ] **Step 5: `FindingStoreTest`, red then green.** One site created through `SiteService`;
      run ids are plain numbers, since the two tables carry no foreign key to `run` — which keeps
      every diff test free of crawl machinery. Assertions:
- upsert of two findings returns two ids; re-upserting one of them returns the same id and leaves
  `first_seen_run` and `first_seen_at` at their original values.
- an upsert over a finding whose `triage_status` is `ACKNOWLEDGED` leaves it `ACKNOWLEDGED` —
  **the assertion §6.3 exists for**: acknowledging must not be erased by the finding still being
  broken.
- `evidence` and `message_args` survive a round trip through `jsonb` (a screenshot path, an HTTP
  status and a two-element console excerpt come back equal).
- `recountOccurrences` sets `occurrence_count` to the number of rows, and running the whole
  sequence twice for the same `runId` leaves both `occurrence_count` and the row counts unchanged
  (D26's idempotency).
- `resolveOutsideRun` resolves a finding on a covered page whose check type is covered; leaves
  one whose type is not in `checkTypes`; leaves one whose location is not in `locationKeys`;
  leaves a `"*"` finding when `complete` is false and resolves it when true.

- [ ] **Step 6: `FindingServiceDiffTest`, red then green** — the diff over a sequence of
      synthetic runs, still no browser. Assertions:
- run 1 with three findings: all three in `NEW`, none in `STILL_OPEN`.
- run 2 with the same three: all three in `STILL_OPEN`, `NEW` empty, `FIXED` empty, and the
  fingerprints unchanged. **The stability assertion** — a diff that reports its own input as new
  every time is worse than no diff.
- run 3 dropping one of them, full coverage: that one in `FIXED` with `resolved_at_run = 3`, the
  other two in `STILL_OPEN`.
- run 4 seeing it again: `REGRESSED`, not `NEW`, and `first_seen_run` is still 1.
- a finding acknowledged between runs appears in `KNOWN`, not `STILL_OPEN`; the same finding
  after a resolve-then-return appears in `REGRESSED`, not `KNOWN` (precedence).
- **§6.4's explicit test, named `aPulseRunDoesNotResolveWhatAFullCrawlFound`:** the next run has
  a `RunCoverage` holding one page and `RunScope.PULSE.checkTypes()`; a `FILE_DOWNLOAD` finding
  on another page is untouched — still `ACTIVE`, `last_seen_run` unchanged — and appears in
  **no** section of that run's diff. The spec calls this out as the failure mode that makes a
  tool report the same regression every week forever.
- the promotion boundary crossing: a run seeing the subject on 6 pages (one `"*"` finding)
  followed by one seeing it on 3 gives 3 `NEW` + 1 `FIXED`. Not a bug; a documented consequence
  of the two-tier rule, pinned here so nobody "fixes" it by accident.

- [ ] **Step 7: Run and commit** — `./mvnw test -Pfast`.

---

### Task 4: Baseline acceptance

**Files:**
- Modify: `findings/FindingService.java`, `findings/FindingStore.java`, `runner/RunService.java`
- Test: `findings/BaselineAcceptanceTest.java`, `runner/RunServiceTest.java` (extend)

**Interfaces (produces):**
- `FindingService`: `@Transactional int acceptBaseline(long siteId, long runId)` — moves every
  `UNTRIAGED` finding **with an occurrence in that run** to `ACKNOWLEDGED`, stamping
  `triage_reason` with the constant German sentence and `triaged_at` with now. Returns how many
  it moved.
- `RunService`: `int acceptBaseline(long runId)` — resolves the run, calls the above, sets
  `baseline_accepted_at`, returns the count. Plan 5's button calls this one.

- [ ] **Step 1: `BaselineAcceptanceTest`, red.** Assertions:
- three `UNTRIAGED` findings in the run → all three `ACKNOWLEDGED`, `triage_reason` non-blank and
  containing no internal identifier (§13.1), `triaged_at` set.
- a finding already `WONT_FIX` keeps its status and its own reason.
- a finding of the same site with **no occurrence in that run** is untouched — accepting a
  baseline is a statement about what that run saw.
- calling it twice returns 0 the second time and changes nothing.
- the *next* run's diff lists them under `KNOWN` instead of `STILL_OPEN`, which is the whole
  point: run two shows only genuine change (§6.3).

- [ ] **Step 2: Implement both methods.** The `UPDATE … WHERE id IN (SELECT finding_id FROM
      finding_occurrence WHERE run_id = ?)` shape is obvious enough not to write out; the
      `AND triage_status = 'UNTRIAGED'` guard is what makes the second call a no-op.

- [ ] **Step 3: `RunServiceTest` gains** `acceptBaseline` setting `baseline_accepted_at`,
      `RunSummary.baselineAccepted()` flipping to true, and an unknown run id raising
      `IllegalArgumentException` like the other lookups on that service.

Baseline acceptance is proven here, at the service level against real Postgres, rather than in
the browser suite: it mutates triage state, and a mutating test inside the shared-crawl class of
Task 6 would silently change what the other tests in that class observe, depending on JUnit's
method order. The browser suite stays read-only for that reason.

- [ ] **Step 4: Run and commit** — `./mvnw test -Pfast`.

---

### Task 5: End-of-run re-verification

**Files:**
- Create: `crawler/FindingReverifier.java`, `crawler/ReverificationOutcome.java`
- Modify: `crawler/VerifierProperties.java`, `src/test/java/…/support/FixtureSite.java`,
  `src/test/resources/fixture-site/index.html`, `src/main/resources/application.properties`,
  `src/test/resources/application-test.properties`
- Test: `crawler/FindingReverifierTest.java`, `support/FixtureSiteTest.java` (extend)
  — both browser-free

**Interfaces (produces):**
- `record ReverificationOutcome(List<CheckFinding> surviving, Set<String> recoveredSubjects,
  int rechecked)`.
- `FindingReverifier` (`@Component`, constructor takes `UrlVerifier`,
  `ExternalUrlCacheJdbcRepository`, `VerifierProperties`):
  `ReverificationOutcome reverify(SiteContext site, RunSnapshots snapshots,
  UrlVerifications firstPass, List<CheckFinding> findings)`.
- `VerifierProperties` gains `int reverifyAttempts` and `Duration reverifyDelay`.

- [ ] **Step 1: Give the fixture a failure that heals.** A new slot `/extern/flatterhaft`:
      503 `text/html` on the **first** request, 200 on every later one, off the existing
      `requestCounts` map. Link it from `index.html` as
      `<li><a href="http://localhost:{{PORT}}/extern/flatterhaft">Zeitweise gestörter Partner</a></li>`
      — on the `localhost` alias, so it is an *external* candidate and the cache write-back is
      observable. `FixtureSiteTest` pins both statuses, in order.

- [ ] **Step 2: `FindingReverifierTest`, red.** One `FixtureSite` for the class, the real
      `UrlVerifier` and cache (`AbstractPostgresTest`, no browser), `reverifyDelay` 100 ms.
      Assertions:
- a `DEAD_LINK.dead` finding on `/extern/flatterhaft` is **dropped**, and the
  `external_url_check` row for it now reads `OK` — §8's "only survivors become findings", and 3b's
  handoff that the corrected result must reach the cache or a transient failure sits there for
  its full TTL.
- a finding on `http://localhost:9/tot` survives, and its cache row stays `DEAD` with a fresher
  `checked_at`.
- **two** findings sharing the recovered subject (the same dead link on two pages) are dropped
  together — recovery is a property of the subject, not of the finding.
- a finding whose subject is the URL of a snapshot is never re-fetched: assert
  `site.requestCount(path)` is unchanged across the call. **The assertion D23 exists for.**
- a finding whose first-pass verification was `OK` (a `FILE_DOWNLOAD.wrongType`) is not re-fetched
  and survives.
- a subject that comes back `UNVERIFIABLE` keeps its finding.
- `rechecked` counts subjects, not findings; an empty finding list does no I/O at all.

- [ ] **Step 3: Implement `FindingReverifier`.** The selection rule is the deviation, so it is
      the one part worth writing out; the rest is a `verifyAll` over the suspects, a `cache.store`
      of the external results and a `removeIf` on the finding list:

```java
Set<String> visited = snapshots.visitedUrls();                    // browser verdicts stay (D23)
Set<String> suspects = findings.stream().map(CheckFinding::subjectKey).distinct()
        .filter(subject -> !visited.contains(subject))
        .filter(subject -> firstPass.byUrl().get(subject) != null
                && firstPass.byUrl().get(subject).status() == UrlStatus.DEAD)
        .collect(Collectors.toSet());
```

Between attempts sleep `reverifyDelay`, doubling — on a virtual thread the wait costs nothing but
wall clock. Only a fresh `OK` clears a subject; anything else leaves it in the failure set and its
findings survive.

- [ ] **Step 4: Configuration.** `application.properties`:
      `webtesthelper.verifier.reverify-attempts=2`, `webtesthelper.verifier.reverify-delay=2s`.
      Test profile: `reverify-delay=100ms`.

- [ ] **Step 5: Run and commit** — `./mvnw test -Pfast`, then `./mvnw test` once.

---

### Task 6: The pipeline, the memory bound, and the two-run acceptance

**Files:**
- Modify: `runner/CrawlRunExecutor.java`, `runner/persistence/RunResultJdbcRepository.java`,
  `crawler/CrawlService.java`
- Test: `runner/CrawlRunExecutorTest.java` (extend), `crawler/CrawlServiceScopeAndBudgetTest.java`
  (extend)

**Interfaces (changes):**
- `RunResultJdbcRepository.saveCrawlOutcome(long runId, CrawlResult result,
  List<String> coveredCheckTypes, SoftNotFoundProbe probe, int findingsTotal, int findingsNew,
  int findingsResolved)` — two more `int`s, the same handoff shape 3a established.

- [ ] **Step 1: Close the snapshot memory bound** (p2b's last open carry-over). `visited` counts
      only reachable pages, so a run against a host where every page fails keeps accumulating
      snapshots past `maxPages`. Bound the *list*, not just the budget: the loop guard becomes
      `visited.get() >= maxPages || snapshots.size() >= maxPages`, and `room` is
      `Math.min(batchSize, maxPages - Math.max(visited.get(), snapshots.size()))`. In the
      all-reachable case the two are identical, so no existing budget test changes.
      `CrawlServiceScopeAndBudgetTest` gains: a PULSE run pinned to three unreachable URLs with
      `maxPages = 2` produces at most 2 snapshots.

- [ ] **Step 2: Wire the pipeline in `CrawlRunExecutor`.** After the two check passes and before
      the run row is written: `reverifier.reverify(...)`, then
      `RunCoverage.of(coveredCheckTypes, result.coveredUrls(), result.snapshots().visitedUrls(),
      result.partialCoverage())` (D25), then `findings.record(...)` with `startedAt` as the
      observation instant. The log line becomes the diff — new, fixed, still open — because "42
      Befunde" says nothing and "3 neu, 2 behoben" is the product.

- [ ] **Step 3: The run row.** `findings_total` is now `diff.observedTotal()`, plus
      `diff.count(NEW)` and `diff.count(FIXED)` into the two new columns. Update the javadoc on
      `CrawlRunExecutor` — it currently promises exactly this plan.

- [ ] **Step 4: Extend `CrawlRunExecutorTest` with the acceptance runs.** `@BeforeAll` runs the
      fixture **three** times against one site — the class already crawls once, so this is three
      Chromium sweeps in one class rather than a second browser class (3b's guidance): `runId1`
      and `runId2` both `FULL`, then `sites.update(...)` with `maxPages = 2` and `runId3`. The
      existing assertions move to `runId2` unchanged. New assertions:
- after run 1: `finding` rows exist, every one `ACTIVE`/`UNTRIAGED`, `first_seen_run = runId1`,
  and `findings_new` on the run row equals the row count.
- **run 2 changes nothing.** The set of fingerprints is identical to run 1's, `findings_new` is
  **0**, `findings_resolved` is **0**, and every finding's `first_seen_run` is still `runId1`.
  This is the plan's headline assertion: fingerprint instability would surface here as a run-two
  report full of "new" findings, and nowhere else in the suite.
- run 2's occurrence rows exist for `runId2` as well as `runId1` — history accumulates while
  identity does not move.
- **run 3 resolves nothing outside its coverage.** Derive the location keys run 3 covered from
  its `covered_urls`, take the findings whose `location_key` is not among them, and assert they
  are all still `ACTIVE` with `last_seen_run = runId2`. Derived, not hardcoded, so a change in
  crawl order cannot make it lie. `partial_coverage` on run 3 is true.
- no finding of run 1 is a duplicate: `count(*) = count(DISTINCT fingerprint)`.
- the flaky external link produced **no** surviving finding in run 1 — re-verification ran inside
  the pipeline, which no unit test can prove.

- [ ] **Step 5: Run the full suite and commit.** `./mvnw test`, browser tests included. Expect
      the browser suite to grow by roughly two fixture crawls.

---

## Plan 4 completion check

- [ ] `./mvnw test` passes, browser tests included, and `-Pfast` passes too — Tasks 1–5 are
      browser-free, which is what keeps the diff cheap to reason about
- [ ] Six task commits landed, plus whatever the reviews add
- [ ] `ModularityTest` proves `findings → {model}`, and
      `grep -rn "webtesthelper.crawler\|webtesthelper.checks\|webtesthelper.catalog\|webtesthelper.runner" src/main/java/…/findings/`
      returns nothing
- [ ] `V9` is the only new migration, and `ddl-auto=validate` still starts the app
- [ ] `CheckDocumentationTest` is untouched and still green — this plan added no message key
- [ ] The §6.4 explicit test exists by name (`aPulseRunDoesNotResolveWhatAFullCrawlFound`) and the
      run-two stability assertion exists in the browser suite
- [ ] p2b's snapshot memory bound is closed; the four `truncate` copies are still four (this plan
      adds no fifth, so 3b's "centralise if a fifth appears" stays parked)
- [ ] **Write `2026-08-21-webtesthelper-p5-web-smtp.md` next**, from the section below

## Execution findings fed back to Plan 5's writer

*(Filled in during execution — measured truth, not plan text. Under `CLAUDE.md` this section is
never compressed to fit a length target: it is the only content in the plan that cannot be
regenerated by reading the code.)*

## What Plan 5 consumes from this plan

- `FindingService.diffOf(siteId, runId)` → `RunDiff` — the run detail page *is* this object,
  rendered section by section in the order `ReportSection` declares.
- `RunService.acceptBaseline(runId)` — the "Als Ausgangsbestand übernehmen" button, and §13.4's
  consequence-stated-before-the-click applies to it: it acknowledges every untriaged finding of
  that run, which the dialog has to say in words.
- `Finding.messageKey()` + `messageArgs()` + `evidence()` — the renderer's input. **The open item
  the plan-3 review recorded lands here:** `PAGE_UNREACHABLE.navigation` carries Chromium's
  `net::ERR_…`, `DEAD_LINK.dead` and `TLS_CERT.handshakeFailed` carry a Java exception string.
  `CheckDocumentationTest` cannot see them because it scans only the static German. §13.1 says no
  internal identifier reaches the screen, so Plan 5 translates them or demotes them to evidence.
- `Finding.locationKey().equals("*")` plus `pageCount()` — the site-wide form reads *"logo-x.png
  liefert 404 — auf 312 Seiten"*, and the occurrence rows behind it are the "show me where" list.
- `RunSummary.findingsNew()` / `findingsResolved()` — the run list's two columns, already on the
  row so listing twenty runs stays one query.
- Still open, deliberately, and inherited unchanged: `UNVERIFIABLE` at `INFO` versus the
  notification threshold (§11.1, Phase 2's problem); the blocked-iframe signal comparing a failed
  request's final URL against the frame's declared `src` (3a); Maps-error attribution when
  several embeds share a page (3a); and the `IFRAME_EMBED` canvas-paint gap, which the plan-3
  review left open because proving a map painted needs a hand-built grey-map fixture and a
  measurement, not a plan.
- Retention is unbounded on purpose in Phase 1: findings are kept indefinitely (§6.5) and so are
  their occurrences. A site-wide finding on 300 pages costs 300 rows per run; if that becomes a
  problem it is an occurrence-pruning policy, not a finding-pruning one.
