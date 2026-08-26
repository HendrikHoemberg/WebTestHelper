# WebTestHelper Plan 7 — Silence With a Reason

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A colleague can silence a known-broken thing without going blind to it. Every finding
gets the four triage actions of §6.3, a list screen that can be filtered and acted on in bulk,
`MuteRule` patterns with a mandatory reason and a mandatory expiry (§6.1), and a sweep that hands
the noise back when the expiry passes — visibly, with the old reason still attached.

**Architecture:** No new module. Triage and mutes are the human axis of §6.3's lifecycle, so they
live in `findings`, which still depends on `model` alone. `web` grows two screens and one
fragment. The one new scheduled job is a sibling of plan 6's tick, not a second clock in a
second module.

**Tech Stack:** Java 25, Spring Boot 4.1.1, PostgreSQL 17 via Testcontainers, Thymeleaf + HTMX +
Alpine. **No new dependency.**

**Spec:** `docs/superpowers/specs/2026-08-21-webtesthelper-design.md`; §-references point there.
**Roadmap:** `2026-08-25-webtesthelper-phase-2-roadmap.md` — plan 7 of 9. Its deviation index
(D38–D45), the Phase-1 table (D1–D37), `CLAUDE.md`'s plan calibration and `CLAUDE.md`'s test
rules all apply and are **not** restated.

**Ends with:** a site whose first run produced 200 findings can be made quiet in three gestures —
accept the baseline, mute the class of link the checker cannot reach, mute the one page the
customer is rebuilding until the 30th — and none of those three gestures stops the system
reporting when something genuinely changes. Ninety days later the rule expires, the findings come
back into view with *"Damalige Begründung: LinkedIn drosselt unseren Prüfer"* printed next to a
live occurrence count, and the colleague decides again.

**No browser test.** Nothing here needs Chromium: triage is a column, a mute is a pattern, and the
diff is SQL. Every test in this plan runs under `-Pfast`.

---

## The roadmap's open question, answered

> *Does a `MuteRule` suppress a finding at materialisation (it never enters the table) or at read
> (it enters, and is filtered into `KNOWN`)?*

**At read.** The roadmap expected the evidence to be `DIFF_SQL`'s precedence order. It is not —
the precedence is a cosmetic problem and Task 2 fixes it in one line. The decisive evidence is
four lines further up, in `FindingStore.RESOLVE_SQL`:

```sql
WHERE site_id = ? AND observed_status = 'ACTIVE' AND last_seen_run <> ? AND check_type = ANY(?)
```

A finding suppressed at materialisation is never upserted, so its `last_seen_run` stays at the
previous run. It is `ACTIVE`, its `last_seen_run <> runId`, and it is inside the run's coverage —
so the *next* run resolves it. When the mute expires, the run after that revives it,
`regressed_at_run` is stamped, and §11.1 mails it as a regression.

Materialisation-time suppression does not merely lose history. **It converts every expiring mute
into a false regression**, which is the exact failure §6.4 exists to prevent. The finding always
enters the table; the mute moves the human axis and nothing else.

## Deviations this plan introduces

- **D46 — mutes suppress at read, never at materialisation.** Reasoning above. The write path in
  `FindingService.record` is unchanged; a rule-application statement runs *after*
  `resolveOutsideRun` and *before* `diffOf`, so the run's own report already reflects the mute
  without the observed axis ever seeing it.
- **D47 — the triage axis splits into *silencing* (`MUTED`, `WONT_FIX`) and *noting*
  (`ACKNOWLEDGED`).** §6.3's section table does not say what happens when a finding is both
  triaged and regressed, and today `REGRESSED` wins for all three statuses. Silencing statuses
  now outrank `NEW` and `REGRESSED`; `ACKNOWLEDGED` keeps its place below them. A mute that stops
  applying the moment the thing flaps is not a mute. An acknowledgement that survives a fix and a
  re-break is not an acknowledgement — somebody fixed it and it broke again, and that is news by
  any reading. This is also the predicate plan 8 needs for §11.1, decided once here.
- **D48 — a `MuteRule` carries two optional glob patterns — subject and location — plus an
  optional check type, and at least one of the three must be set.** §6.1 writes "URL pattern",
  singular, but §6.3's own example (*"all dead links to linkedin.com"*) is a **subject**, and the
  other obvious use (*"everything under /archiv/ while it is being rebuilt"*) is a **location**.
  §6.2 made those two different columns for a reason and a single "URL pattern" would have to
  guess which. The at-least-one constraint is a `CHECK` as well as a Java guard: a rule with all
  three empty mutes the entire site, and that is one empty form submit away.
- **D49 — expiry is a stored status change made by an hourly sweep, not a read-time comparison.**
  A `mute_expires_at > now()` predicate would have to appear in the list query, the diff query and
  plan 8's notification query, evaluated at three different moments; the screen and the mail could
  then disagree about whether a finding was muted when the digest ran. `triage_status` stays the
  single truth, the way `RunDiff` is read out of the database rather than recomputed.
- **D50 — an expired mute keeps its reason and stamps `mute_expired_at`.** Un-muting is *visible*.
  The question the roadmap wanted history for — *is this mute still needed?* — is answered by the
  old reason printed next to an occurrence count that kept growing throughout the mute, because
  D46 kept writing occurrences the whole time. That is one column, not an audit table.
- **D51 — a rule never overrides a human's triage decision.** Rule application touches
  `UNTRIAGED` rows only. A finding somebody marked `WONT_FIX` is not re-labelled `MUTED` with a
  rule's reason, and deleting the rule cannot un-triage it.
- **D52 — `spring.task.scheduling.pool.size=4`.** D43's three are spoken for (outbox, tick,
  retention), as plan 6's execution findings confirmed. The sweep is the fourth.

## Decided constants

| Constant | Value | Why |
|---|---|---|
| default mute duration offered | 90 days | §6.3's own example |
| maximum mute duration | 365 days, `webtesthelper.findings.max-mute-days` | "mandatory expiry" means nothing if 2099 is accepted |
| sweep cron | `0 15 * * * *`, `webtesthelper.findings.mute-sweep-cron` | hourly; mutes are day-scale, and an hour of lateness on a 90-day mute is noise |
| sweep enabled | `webtesthelper.findings.mute-sweep-enabled`, default true, **false in tests** | D33's rule, and plan 6's tick set the precedent |
| findings page size | 50, `webtesthelper.findings.page-size` | one screen of checkboxes a person can actually read before clicking apply |
| bulk id cap | 200 per request | the page shows 50; the cap bounds a crafted or scripted POST |
| scheduler pool | `spring.task.scheduling.pool.size=4` | D52 |
| wildcard | `*`, and only `*` | §13.1 — the audience does not write regexes |

## URL vocabulary added

| Path | Method | Role | Screen |
|---|---|---|---|
| `/websites/{id}/befunde` | GET | USER | Findings list, filtered and paginated |
| `/websites/{id}/befunde/bewerten` | POST | USER | Bulk triage of selected ids |
| `/befunde/{id}/bewerten` | POST | USER | Triage one finding from its detail page |
| `/stummschaltungen` | GET | USER | Mute rules, per-site and global |
| `/stummschaltungen` | POST | USER (ADMIN for global) | Create a rule |
| `/stummschaltungen/{id}/loeschen` | POST | USER (ADMIN for global) | Delete a rule and un-mute what it muted |
| `/stummschaltungen/vorschau` | GET | USER | HTMX fragment: "matches N current findings" (§13.4) |
| `/hilfe/stummschaltungen` | GET | USER | New handbook topic |

Triage is `USER`: §12 gives that role "configure checks, triage, record" in those words. A
**global** mute rule is the exception — it silences a pattern across every customer site at once,
which is a fleet decision, so creating or deleting one is `ADMIN`. The check cannot live in
`SecurityConfig`, because the two cases share a URL and differ only by whether the submitted site
is empty; it is an explicit guard in the controller that throws `AccessDeniedException`, and it
gets its own test.

---

### Task 1: The triage columns and the action that validates itself

The mandatory-reason-and-expiry rule of §6.3 is pure logic and belongs where it can be tested
without a database.

**Files:**
- Create: `src/main/resources/db/migration/V14__finding_triage.sql`
- Create: `findings/TriageAction.java`, `findings/TriageValidationException.java`
- Modify: `findings/Finding.java`, `findings/FindingStore.java`, `findings/FindingService.java`,
  `findings/FindingProperties.java`, `src/main/resources/application.properties`
- Test: `findings/TriageActionTest.java`, `findings/FindingTriageTest.java`

**Interfaces (produces):**
- `record TriageAction(TriageStatus target, String reason, Instant mutedUntil)` with
  `static TriageAction of(TriageStatus target, String reason, Instant mutedUntil, Instant now, int maxMuteDays)`
  which throws `TriageValidationException` (a `RuntimeException` carrying a message key, not a
  sentence — the controller renders it) rather than returning an invalid value.
- `FindingService.triage(long siteId, List<Long> ids, TriageAction action, String actor, Instant now)`
  returning the number of rows changed.
- `Finding` gains `String triagedBy, Instant mutedUntil, Instant muteExpiredAt, Long mutedByRuleId`.

- [ ] **Step 1: Write `V14__finding_triage.sql`.** No rule reference yet — that column arrives
      with the table it points at, in Task 3.

```sql
-- The human axis of spec 6.3 gains what "mandatory expiry" needs to be enforceable, and what
-- D50 needs to make an expiry visible rather than silent.
ALTER TABLE finding ADD COLUMN triaged_by TEXT;
-- NULL for every status except MUTED. The CHECK is what makes "indefinite mutes are how
-- monitoring goes blind" (spec 6.3) a property of the schema instead of a hope about the UI.
ALTER TABLE finding ADD COLUMN muted_until TIMESTAMPTZ;
-- Set by the sweep, never cleared. triage_reason is deliberately NOT cleared alongside it (D50):
-- the old reason next to a live occurrence count is what answers "is this mute still needed".
ALTER TABLE finding ADD COLUMN mute_expired_at TIMESTAMPTZ;
ALTER TABLE finding ADD CONSTRAINT ck_finding_mute_needs_expiry
    CHECK (triage_status <> 'MUTED' OR muted_until IS NOT NULL);

-- The sweep's only query. Partial, because it is the only state the sweep ever looks at.
CREATE INDEX ix_finding_mute_expiry ON finding (muted_until) WHERE triage_status = 'MUTED';
```

- [ ] **Step 2: Write `TriageActionTest`, red.** Pure JUnit, no Spring, fixed `now`. Assertions:
- `MUTED` with a blank, whitespace-only, or null reason throws; the message key names the field.
- `MUTED` with a null expiry throws.
- `MUTED` with an expiry equal to `now` or before it throws. Strictly future, so a mute cannot be
  born expired and then be un-muted by the very next sweep.
- `MUTED` with an expiry beyond `maxMuteDays` past `now` throws.
- `MUTED` with a reason and an expiry 90 days out is accepted and keeps both verbatim — no
  trimming to a length, no default substituted for a supplied value.
- `WONT_FIX` with a blank reason throws. §6.3 mandates a reason only for mutes, but "we are never
  fixing this" is a decision the next colleague has to be able to read; it carries no expiry.
- `ACKNOWLEDGED` with a blank reason is accepted, and `mutedUntil` is null. This is the bulk case:
  §6.3's baseline acceptance moves 200 findings with one system-supplied sentence, and demanding a
  typed reason per finding is exactly the friction that makes people stop triaging.
- `UNTRIAGED` (the un-triage action) is accepted, and both reason and expiry come back null
  whatever was passed in. Un-triaging is a reset, not an annotation.
- an expiry supplied for `ACKNOWLEDGED` or `WONT_FIX` throws rather than being ignored. A field
  that silently does nothing is worse than one that refuses.

- [ ] **Step 3: Run it and watch it fail.** `./mvnw test -Pfast -Dtest=TriageActionTest`.

- [ ] **Step 4: Implement `TriageAction`, `TriageValidationException`, and the `Finding` fields.**
      Add `maxMuteDays` and `pageSize` to `FindingProperties` with the constants above in
      `application.properties`. Extend `FindingStore`'s `findingRow` for the four new columns —
      `rs.getTimestamp(...)` is null-safe, unlike `getLong`, so no `wasNull` dance is needed.

- [ ] **Step 5: Write `FindingTriageTest`, red.** Extends `AbstractPostgresTest`, `@Transactional`,
      builds its site through `SiteService` and its findings through `FindingService.record`, the
      way `FindingServiceDiffTest` does. Assertions:
- triaging three ids to `ACKNOWLEDGED` returns 3 and writes `triaged_by`, `triaged_at`,
  `triage_reason` on exactly those three.
- triaging to `MUTED` writes `muted_until`; re-reading `byId` returns it.
- an id belonging to **another site** is not changed and is not counted. The bulk endpoint takes
  ids from a form; the site scope is the only thing standing between it and a cross-tenant write.
- triaging to `UNTRIAGED` clears `triage_reason`, `muted_until` and `triaged_by`, and leaves
  `mute_expired_at` alone — that column is the sweep's, and a human un-triaging is not an expiry.
- both status columns stay orthogonal: a `RESOLVED` finding can be triaged and stays `RESOLVED`.

- [ ] **Step 6: Implement `FindingStore.triage` and `FindingService.triage`.** One `UPDATE ...
      WHERE site_id = ? AND id = ANY(?)`, `version = version + 1`, returning the row count.
      `FindingService.triage` is the transactional boundary and does nothing else.

- [ ] **Step 7: Green, then commit.**
      `./mvnw test -Pfast -Dtest='TriageActionTest,FindingTriageTest,FlywayMigrationTest'`.

```bash
git add src/main/resources/db/migration/V14__finding_triage.sql src/main/java/dev/hendrikhoemberg/webtesthelper/findings src/main/resources/application.properties src/test/java/dev/hendrikhoemberg/webtesthelper/findings
git commit -m "feat(findings): triage columns with a mandatory reason and expiry"
```

---

### Task 2: Silencing beats news (D47)

One `CASE` branch, and the whole of plan 8's §11.1 predicate.

**Files:**
- Modify: `model/TriageStatus.java`, `findings/FindingStore.java`, `findings/ReportSection.java`
- Test: `findings/FindingTriageSectionTest.java`, `model/TriageStatusTest.java`

**Interfaces (produces):**
- `TriageStatus.SILENCING` — a `Set<TriageStatus>` of `MUTED` and `WONT_FIX` — plus
  `boolean silences()`.

- [ ] **Step 1: Write `TriageStatusTest`, red.** Two assertions, and the second is the one that
      matters: **every constant of the enum is classified**, i.e. `SILENCING` and its complement
      partition `values()`. A fifth triage status added in Phase 3 must fail a test rather than
      quietly default to "does not silence" and start mailing.

- [ ] **Step 2: Write `FindingTriageSectionTest`, red.** Same shape as `FindingServiceDiffTest`.
      Each case is: record run 1, triage, record run 2 (or 3), assert the section. Assertions:
- muted, then still observed → `KNOWN`, not `STILL_OPEN`. (Already true; pinned so the reorder
  cannot break it.)
- muted, resolved by run 2, observed again by run 3 → `KNOWN`. Today it is `REGRESSED`, and this
  is the case the roadmap named as unresolved.
- **acknowledged**, resolved by run 2, observed again by run 3 → `REGRESSED`. The complement, and
  the reason `ACKNOWLEDGED` was left out of `SILENCING`.
- `WONT_FIX`, regressed → `KNOWN`.
- a finding first seen in run 2 that a Task-4 rule mutes in that same run → `KNOWN`, not `NEW`.
  Written now, activated in Task 4; until then triage it directly and record run 2.
- muted, then absent from a fully-covering run → `FIXED`. `FIXED` still outranks everything, and
  it must: a muted finding that got fixed is how you learn the mute can go.

- [ ] **Step 3: Run both and watch them fail.**
      `./mvnw test -Pfast -Dtest='TriageStatusTest,FindingTriageSectionTest'`.

- [ ] **Step 4: Implement.** Add `SILENCING` to `TriageStatus`. Change `DIFF_SQL`'s `CASE` — the
      new branch takes no parameter, so the six positional `?` arguments are untouched:

```sql
SELECT f.*, CASE
    WHEN f.observed_status = 'RESOLVED' AND f.resolved_at_run = ? THEN 'FIXED'
    -- D47: a mute that stops applying the moment the thing flaps is not a mute. Placed above
    -- NEW and REGRESSED, below FIXED. The IN list is built from TriageStatus.SILENCING so the
    -- enum and this string cannot drift apart.
    WHEN f.triage_status IN (%s)                                  THEN 'KNOWN'
    WHEN f.first_seen_run = ?                                     THEN 'NEW'
    WHEN f.regressed_at_run = ?                                   THEN 'REGRESSED'
    WHEN f.triage_status <> 'UNTRIAGED'                           THEN 'KNOWN'
    ELSE 'STILL_OPEN' END AS section
```

Interpolate `%s` once in a `static` initialiser from `TriageStatus.SILENCING` — quoted, joined
with commas. It is the only place in the codebase where a SQL literal is built from Java, and it
exists precisely so that adding a constant to the enum cannot leave this string behind.

- [ ] **Step 5: Update `ReportSection`'s javadoc** — it currently states the precedence as
      FIXED → NEW → REGRESSED → KNOWN, which is now wrong in the one way that matters. The enum
      *order* stays as it is: it is the display order of the report's sections, and `KNOWN` still
      belongs below the news.

- [ ] **Step 6: Green, then commit.** `./mvnw test -Pfast -Dtest='*Finding*,*Triage*,EnumLabelsTest'`.

```bash
git add src/main/java/dev/hendrikhoemberg/webtesthelper/model/TriageStatus.java src/main/java/dev/hendrikhoemberg/webtesthelper/findings src/test/java/dev/hendrikhoemberg/webtesthelper/model src/test/java/dev/hendrikhoemberg/webtesthelper/findings
git commit -m "feat(findings): silencing triage outranks new and regressed"
```

---

### Task 3: `MuteRule` — the table, the pattern, the service

**Files:**
- Create: `src/main/resources/db/migration/V15__mute_rule.sql`
- Create: `findings/MuteRule.java`, `findings/MuteRuleForm.java`, `findings/MutePattern.java`,
  `findings/MuteRuleService.java`, `findings/persistence/MuteRuleEntity.java`,
  `findings/persistence/MuteRuleRepository.java`
- Test: `findings/MutePatternTest.java`, `findings/MuteRuleServiceTest.java`

**Interfaces (produces):**
- `record MuteRule(long id, Long siteId, CheckType checkType, String subjectPattern,
  String locationPattern, String reason, String createdBy, Instant expiresAt, Instant createdAt)` —
  `siteId == null` means global, `checkType == null` means every check.
- `record MuteRuleForm(Long siteId, CheckType checkType, String subjectPattern,
  String locationPattern, String reason, Instant expiresAt)`.
- `MutePattern` with `static String toLikePattern(String glob)` and
  `static boolean isBlank(String glob)`.
- `MuteRuleService` with `long create(MuteRuleForm form, String actor, Instant now)`,
  `List<MuteRule> forSite(long siteId)` (site's own rules **and** the global ones),
  `List<MuteRule> all()`, `Optional<MuteRule> byId(long id)`, `void delete(long id)`.
  `create` throws `TriageValidationException` for a blank reason, a non-future or over-long
  expiry, or a form with none of the three criteria set. Same exception type as Task 1: the
  controller renders one error path, not two.
- The entity/repository follow `catalog`'s rule that JPA types never leave their module —
  `MuteRuleService` returns the record.

- [ ] **Step 1: Write `V15__mute_rule.sql`.**

```sql
-- Pattern mutes alongside per-finding mutes (spec 6.3). Two patterns, not one (D48): spec 6.2
-- made "what is broken" and "where it was found" different columns, and a single "URL pattern"
-- would have to guess which one the colleague meant.
CREATE TABLE mute_rule (
    id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    site_id BIGINT REFERENCES site (id) ON DELETE CASCADE,   -- NULL = every site (ADMIN only)
    check_type TEXT,                                          -- NULL = every check
    subject_pattern TEXT,                                     -- glob over finding.subject_key
    location_pattern TEXT,                                    -- glob over finding.location_key
    reason TEXT NOT NULL,
    created_by TEXT,
    -- Mandatory, like a per-finding mute and for the same reason (spec 6.3).
    expires_at TIMESTAMPTZ NOT NULL,
    expired_at TIMESTAMPTZ,                                   -- stamped by the sweep, never cleared
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    version BIGINT NOT NULL DEFAULT 0,
    -- D48. A rule with all three empty mutes the whole site, and that is one empty form submit
    -- away. Enforced here as well as in Java because the Java guard is the one a later caller
    -- forgets to go through.
    CONSTRAINT ck_mute_rule_has_criterion CHECK (
        check_type IS NOT NULL OR subject_pattern IS NOT NULL OR location_pattern IS NOT NULL)
);

CREATE INDEX ix_mute_rule_active ON mute_rule (site_id) WHERE expired_at IS NULL;

-- Which rule muted a finding: needed to un-mute exactly those rows when the rule is deleted or
-- expires, and to show "stummgeschaltet durch Regel" on the finding. NULL for a manual mute.
ALTER TABLE finding ADD COLUMN muted_by_rule_id BIGINT REFERENCES mute_rule (id) ON DELETE SET NULL;
CREATE INDEX ix_finding_muted_by_rule ON finding (muted_by_rule_id) WHERE muted_by_rule_id IS NOT NULL;
```

`ON DELETE SET NULL` is a backstop, not the path: Task 4's delete un-mutes first and only then
deletes, so the cascade should never fire. It is there so a rule deleted by hand in psql leaves
findings that are merely mislabelled rather than pointing at a row that is gone.

- [ ] **Step 2: Write `MutePatternTest`, red.** Pure JUnit. Assertions:
- `*` becomes `%`.
- a literal `%` and a literal `_` are escaped, so a colleague pasting `?utm_source=x` does not
  accidentally write a single-character wildcard. This is the case that makes the naive
  `replace("*", "%")` implementation wrong, and URLs are full of `_`.
- a literal backslash is escaped.
- a glob with no `*` produces a pattern with no `%`: matching is **exact unless starred**. Implicit
  substring matching is the kind of surprise that makes a colleague mute more than they meant to;
  the form's placeholder and its `?` affordance teach the `*` instead.
- blank, whitespace-only and null are all `isBlank`.

- [ ] **Step 3: Run it and watch it fail.** `./mvnw test -Pfast -Dtest=MutePatternTest`.

- [ ] **Step 4: Implement `MutePattern`.** The translation is the one algorithm here whose wrong
      version compiles and passes a casual reading:

```java
/** Glob to SQL LIKE. '*' is the only wildcard a colleague types; everything else is a literal. */
public static String toLikePattern(String glob) {
    StringBuilder out = new StringBuilder(glob.length() + 4);
    for (char c : glob.toCharArray()) {
        switch (c) {
            case '*' -> out.append('%');
            case '%', '_', '\\' -> out.append('\\').append(c);   // URLs are full of '_'
            default -> out.append(c);
        }
    }
    return out.toString();
}
```

Every query built from this uses `LIKE ? ESCAPE '\'` explicitly rather than relying on the
server's default escape character.

- [ ] **Step 5: Write `MuteRuleServiceTest`, red.** `AbstractPostgresTest`, `@Transactional`.
      Assertions: a valid per-site rule round-trips through `byId` with every field intact; a
      global rule (`siteId == null`) is returned by `forSite` for *two different* sites; a blank
      reason, a past expiry, an expiry beyond `maxMuteDays`, and a form with all three criteria
      blank each throw `TriageValidationException` and write no row (asserted by re-counting, not
      by trusting the throw); `delete` removes the row.

- [ ] **Step 6: Implement the entity, repository and service, then green and commit.**

```bash
git add src/main/resources/db/migration/V15__mute_rule.sql src/main/java/dev/hendrikhoemberg/webtesthelper/findings src/test/java/dev/hendrikhoemberg/webtesthelper/findings
git commit -m "feat(findings): mute rules with glob patterns, reason and expiry"
```

---

### Task 4: Applying rules — after every run, retroactively, and on delete

Three callers, one statement. This is the task that makes a rule mean something.

**Files:**
- Create: `findings/MuteRuleApplier.java`
- Modify: `findings/FindingService.java`, `findings/MuteRuleService.java`, `findings/FindingStore.java`
- Test: `findings/MuteRuleApplicationTest.java`

**Interfaces:**
- Consumes: `MutePattern` (Task 3), `TriageStatus.SILENCING` (Task 2).
- Produces: `MuteRuleApplier` with `int applyToRun(long siteId, long runId, Instant now)`,
  `int applyRule(MuteRule rule, Instant now)`, `int unmuteRule(long ruleId, Instant now)`.

- [ ] **Step 1: Write `MuteRuleApplicationTest`, red.** `AbstractPostgresTest`, `@Transactional`.
      Assertions:
- a rule created *before* run 2 puts a matching finding first seen in run 2 into `KNOWN` in
  **run 2's own diff** — not in run 3's. `FindingService.record` returns the diff, so if the mute
  is applied after `diffOf` the caller reports a `NEW` finding and plan 8 mails it.
- a rule matching on `checkType` alone mutes every finding of that check and nothing else.
- a rule matching on `subjectPattern` with a `*` mutes by subject regardless of location; a rule
  matching on `locationPattern` mutes by location regardless of subject.
- both patterns set are **AND**, not OR: a finding matching only the subject is untouched.
- **D51:** a finding already `ACKNOWLEDGED` and one already `WONT_FIX` are both left exactly as
  they are, `triage_reason` included, by a rule that matches them.
- a global rule mutes findings on a site that has no rules of its own.
- an **expired** rule (`expired_at` set) mutes nothing on the next run.
- `applyRule` on creation mutes the matching findings that already exist — including
  `RESOLVED` ones, so the mute is in place if they come back. Assert the count.
- `unmuteRule` returns those findings to `UNTRIAGED`, clears `muted_by_rule_id` and stamps
  `mute_expired_at`, and touches no finding a human muted by hand.
- muting copies the rule's `expires_at` into the finding's `muted_until`. This is what lets Task
  5's sweep be one query over findings instead of two passes.

- [ ] **Step 2: Run it and watch it fail.** `./mvnw test -Pfast -Dtest=MuteRuleApplicationTest`.

- [ ] **Step 3: Implement the apply statement.** One `UPDATE` per active rule, driven from Java
      rather than a single join, so a malformed pattern in one rule cannot take the others down
      with it. The predicate carries the whole of D51 and D48:

```sql
UPDATE finding SET triage_status = 'MUTED', triage_reason = ?, triaged_by = ?, triaged_at = ?,
                   muted_until = ?, muted_by_rule_id = ?, mute_expired_at = NULL,
                   version = version + 1
 WHERE site_id = ?
   AND triage_status = 'UNTRIAGED'                       -- D51: never overrides a human
   AND (? IS NULL OR check_type = ?)
   AND (? IS NULL OR lower(subject_key)  LIKE ? ESCAPE '\')
   AND (? IS NULL OR lower(location_key) LIKE ? ESCAPE '\')
   AND (? IS NULL OR last_seen_run = ?)                  -- set for a run, NULL for a retro-apply
```

`lower()` on both sides because §6.2 normalises a subject key's host but not a location's path,
and a colleague typing `/Archiv/*` should not be defeated by a capital. The scan is bounded by
`site_id`, which `ix_finding_site_open` already serves; there is no index on `lower(location_key)`
and at a few thousand findings per site there does not need to be.

- [ ] **Step 4: Wire the three callers.** `FindingService.record` calls `applyToRun` **after**
      `resolveOutsideRun` and **before** `diffOf`, inside the same transaction — that ordering is
      D46 in one line and deserves a comment saying so. `MuteRuleService.create` calls `applyRule`.
      `MuteRuleService.delete` calls `unmuteRule` and only then deletes.

- [ ] **Step 5: Green, then commit.** `./mvnw test -Pfast -Dtest='*Finding*,*Mute*'`.

```bash
git add src/main/java/dev/hendrikhoemberg/webtesthelper/findings src/test/java/dev/hendrikhoemberg/webtesthelper/findings
git commit -m "feat(findings): apply mute rules per run, on create and on delete"
```

---

### Task 5: The expiry sweep

**Files:**
- Create: `findings/MuteExpiryService.java`, `findings/MuteExpiryJob.java`
- Modify: `findings/FindingStore.java`, `findings/persistence/MuteRuleRepository.java`,
  `src/main/resources/application.properties`, `src/test/resources/application-test.properties`
- Test: `findings/MuteExpiryServiceTest.java`

**Interfaces (produces):**
- `record MuteSweepResult(int findingsUnmuted, int rulesExpired)`.
- `MuteExpiryService.sweep(Instant now)` returning it.
- `MuteExpiryJob` — `@Component`, `@ConditionalOnProperty("webtesthelper.findings.mute-sweep-enabled")`,
  `@Scheduled(cron = "${webtesthelper.findings.mute-sweep-cron}")`, calling
  `sweep(Instant.now())`. Same shape as `ScheduleTick`, deliberately: the clock is a thin wrapper
  and the logic takes an instant, so every test drives it directly.

- [ ] **Step 1: Write `MuteExpiryServiceTest`, red.** `AbstractPostgresTest`, `@Transactional`,
      concrete instants throughout. Assertions:
- a finding muted until yesterday is `UNTRIAGED` after `sweep(now)`, with `mute_expired_at = now`
  and **`triage_reason` unchanged** (D50 — this is the assertion the deviation exists for).
- a finding muted until next week is untouched.
- the sweep is idempotent: a second `sweep` returns `0` un-muted, and does not re-stamp
  `mute_expired_at` on a row it already expired.
- a rule past `expires_at` gets `expired_at` stamped and its findings un-muted in the same sweep;
  running the sweep again expires no rules.
- a rule deleted between two sweeps leaves nothing for the second to do (Task 4's delete already
  un-muted).
- **the sweep is time-driven, not coverage-driven.** A muted finding whose location no pulse run
  ever visits keeps its `MUTED` status until its own expiry passes — asserted by recording a
  `PULSE` run whose coverage excludes it and then sweeping. The roadmap's plan-6 handover names
  this exactly: a mute must not appear to expire merely because nothing looked.
- an un-muted finding's next appearance is `STILL_OPEN`, not `NEW` and not `REGRESSED`. Its
  `first_seen_run` is old and D46 kept `last_seen_run` current throughout the mute, so nothing
  about it looks like news. Record a run after the sweep and assert the section.

- [ ] **Step 2: Run it and watch it fail.** `./mvnw test -Pfast -Dtest=MuteExpiryServiceTest`.

- [ ] **Step 3: Implement.** Two statements. Findings:
      `UPDATE finding SET triage_status='UNTRIAGED', muted_until=NULL, muted_by_rule_id=NULL,
      mute_expired_at=?, version=version+1 WHERE triage_status='MUTED' AND muted_until <= ?` —
      `triage_reason` and `triaged_at` are conspicuously absent from the SET list and get a
      comment saying they are absent on purpose. Rules: `UPDATE mute_rule SET expired_at=?
      WHERE expired_at IS NULL AND expires_at <= ?`. Order matters only for the log line; the
      finding statement matches on `muted_until`, which Task 4 copied from the rule, so it
      catches rule-muted findings without a join.

- [ ] **Step 4: Add the job and the fourth thread.** `spring.task.scheduling.pool.size=4` (D52) in
      `application.properties` with the sweep cron and enable flag; `mute-sweep-enabled=false` in
      `application-test.properties`, next to plan 6's three.

- [ ] **Step 5: Green, then commit.** `./mvnw test -Pfast`.

```bash
git add src/main/java/dev/hendrikhoemberg/webtesthelper/findings src/main/resources/application.properties src/test/resources/application-test.properties src/test/java/dev/hendrikhoemberg/webtesthelper/findings
git commit -m "feat(findings): hourly sweep that expires mutes visibly"
```

---

### Task 6: The findings list

The screen §6.3 calls "the findings list" and plan 9's dashboard will link into.

**Files:**
- Create: `findings/FindingQuery.java`, `findings/FindingPage.java`,
  `web/FindingListController.java`, `web/FindingFilterForm.java`,
  `src/main/resources/templates/websites/befunde.html`,
  `src/main/resources/templates/fragments/befundfilter.html`
- Modify: `findings/FindingStore.java`, `findings/FindingService.java`,
  `web/FindingViewFactory.java`, `src/main/resources/templates/websites/detail.html`,
  `src/main/resources/templates/laeufe/detail.html`, `messages.properties`,
  `src/main/resources/static/css/app.css`
- Test: `findings/FindingSearchTest.java`, `web/FindingListControllerTest.java`

**Interfaces (produces):**
- `record FindingQuery(long siteId, Set<Severity> severities, Set<TriageStatus> triageStatuses,
  ObservedStatus observed, Set<CheckType> checkTypes, int page, int size)` — an empty set means
  "no filter on this axis", which is what an unchecked chip group must mean.
- `record FindingPage(List<Finding> findings, int page, int size, long total)` with
  `int pageCount()`.
- `FindingService.search(FindingQuery)`.
- `FindingView` gains `mutedUntil`, `muteExpired` and `triageReason` so a row can show why it is
  quiet without a second query.

- [ ] **Step 1: Write `FindingSearchTest`, red.** `AbstractPostgresTest`, `@Transactional`,
      seeding findings of mixed severity, triage status and check type across two sites.
      Assertions:
- no filters returns every finding of the site and none of the other site's.
- each axis filters independently; two axes are AND.
- an empty set on an axis is "no filter", not "match nothing" — the case that turns a fresh page
  load into an empty screen.
- ordering is `ERROR` before `WARN` before `INFO`, then most recently seen first. A triage screen
  is read top-down and the top must be the thing worth deciding about.
- `total` is the count **before** paging: page 1 of 120 findings reports 120, not 50.
- page 3 of a 120-row set returns 20 rows, and page 4 returns none rather than throwing.

- [ ] **Step 2: Run it and watch it fail.** Then implement `search` and `count` in `FindingStore` —
      one SQL builder, parameters bound positionally, **no string concatenation of user input**;
      the sets contribute `= ANY(?)` clauses guarded by `? IS NULL OR`, the same shape Task 4 uses.

- [ ] **Step 3: Write `FindingListControllerTest`, red.** MockMvc with `@WithMockUser`. Assertions:
      the page renders with the filter chips reflecting the query string; an unknown site id is a
      404, not a 500; the page is reachable by a `USER`; a `checkType` value that is not a valid
      enum constant is a 400, not a 500 — the query string is user input and this screen's URL is
      the one people will paste to each other.

- [ ] **Step 4: Build the screen.** `websites/befunde.html` extends `layout`. Alpine owns the chip
      state and the select-all checkbox (§12's division of labour); HTMX swaps the results table
      on filter change so the scroll position survives. Each row reuses
      `fragments/befundzeile`, extended with a checkbox and — for a muted row — a quiet line
      *"Stumm bis 24.11.2026 · Grund: …"*, and for an expired one *"Stummschaltung abgelaufen am
      … · Damalige Begründung: …"*. That second line is the whole of D50 arriving on a screen.
      Link the list from the site detail page and from the run report's header.

- [ ] **Step 5: Green, then commit.**
      `./mvnw test -Pfast -Dtest='FindingSearchTest,FindingListControllerTest,UiMessageKeyTest,EnumLabelsTest'`.

```bash
git add src/main/java/dev/hendrikhoemberg/webtesthelper src/main/resources/templates src/main/resources/messages.properties src/main/resources/static/css/app.css src/test/java/dev/hendrikhoemberg/webtesthelper
git commit -m "feat(web): filtered, paginated findings list per site"
```

---

### Task 7: Triage actions — one finding, and many

**Files:**
- Create: `web/TriageController.java`, `web/TriageForm.java`,
  `src/main/resources/templates/fragments/bewertung.html`
- Modify: `src/main/resources/templates/befunde/detail.html`,
  `src/main/resources/templates/websites/befunde.html`, `web/WebExceptionHandler.java`,
  `messages.properties`
- Test: `web/TriageControllerTest.java`

**Interfaces (produces):**
- `record TriageForm(TriageStatus aktion, String grund, LocalDate stummBis, List<Long> ids)`.
- `POST /befunde/{id}/bewerten` and `POST /websites/{id}/befunde/bewerten`, both redirecting to a
  URL the **server** builds from the path variable. The return target is never taken from a
  request parameter: this is a form on a page anyone in the company can be linked to, and an
  attacker-supplied `redirect` parameter is the cheapest open redirect there is.
- `WebExceptionHandler` maps `TriageValidationException` to a flash error and a redirect back,
  never a 500.

- [ ] **Step 1: Write `TriageControllerTest`, red.** MockMvc, `@WithMockUser(roles = "USER")`.
      Assertions:
- muting one finding with a reason and a date writes `MUTED`, `muted_until` at end of that day in
  the site's timezone, and `triaged_by` equal to the authenticated username. Attribution is the
  point of a reason; a reason with no author is half a note.
- muting with a blank reason re-renders with a field error and **writes nothing** — asserted by
  re-reading the finding, not by the response body alone.
- muting with a date in the past, and one beyond the maximum, both re-render with an error.
- `ACKNOWLEDGED` with no reason succeeds.
- a bulk POST with three ids moves three, and the flash message reports three.
- a bulk POST containing an id from **another site** moves only the ids that belong to the path's
  site. Task 1 made the store enforce this; this asserts the controller does not work around it.
- a bulk POST with more than the cap is rejected with a 400.
- a bulk POST with an empty selection is a no-op with a flash message, not an error page — the
  realistic user action is clicking apply having forgotten to tick anything.

- [ ] **Step 2: Run it and watch it fail.** `./mvnw test -Pfast -Dtest=TriageControllerTest`.

- [ ] **Step 3: Implement the controller and the shared fragment.** `fragments/bewertung.html` is
      one form used twice — once with a hidden finding id, once bound to the list's checkbox
      selection. The mute branch reveals its reason and date fields via Alpine and states the
      consequence in words before the click (§13.4): *"Dieser Befund erscheint bis zum 24.11.2026
      nicht mehr in Berichten oder E-Mails. Danach wird er automatisch wieder angezeigt."* The
      date field defaults to today + 90 days.

- [ ] **Step 4: Add the triage panel to the finding detail** and the bulk bar to the list, plus a
      `?` affordance pointing at the Task-8 help topic.

- [ ] **Step 5: Green, then commit.** `./mvnw test -Pfast -Dtest='Triage*,Finding*,SecurityRulesTest'`.

```bash
git add src/main/java/dev/hendrikhoemberg/webtesthelper/web src/main/resources/templates src/main/resources/messages.properties src/test/java/dev/hendrikhoemberg/webtesthelper/web
git commit -m "feat(web): single and bulk triage with mandatory mute reason"
```

---

### Task 8: The mute rules screen

**Files:**
- Create: `web/MuteRuleController.java`, `src/main/resources/templates/stummschaltungen/index.html`,
  `src/main/resources/templates/fragments/regelvorschau.html`,
  `src/main/resources/help/stummschaltungen.md`
- Modify: `web/SecurityConfig.java`, `src/main/resources/templates/layout.html`,
  `messages.properties`, `web/HelpTopic.java` (or its registry)
- Test: `web/MuteRuleControllerTest.java`, `web/SecurityRulesTest.java`, `web/HelpTopicsTest.java`

- [ ] **Step 1: Write `MuteRuleControllerTest`, red.** Assertions:
- a `USER` creates a rule scoped to a site, and it appears in the list with its expiry rendered as
  a German date rather than an instant.
- a `USER` creating a **global** rule (`siteId` empty) is 403 and writes nothing.
- an `ADMIN` creating the same global rule succeeds.
- a `USER` deleting a global rule is 403; deleting a site rule succeeds and un-mutes its findings
  (assert one finding is back to `UNTRIAGED`) — the un-mute is Task 4's, and this asserts the
  route reaches it.
- `GET /stummschaltungen/vorschau` with a pattern returns a fragment containing the count of
  currently matching findings, and returns `0` rather than an error for a pattern matching nothing.
- an expired rule is listed, visibly marked, and cannot be edited back to life — creating a fresh
  rule is the path, so the record of what was silenced and when stays intact.

- [ ] **Step 2: Run it and watch it fail.**

- [ ] **Step 3: Implement.** The global-scope guard is one private method both `create` and
      `delete` call, throwing `AccessDeniedException` when the rule is global and the
      authentication lacks `ROLE_ADMIN`. Add `/stummschaltungen/**` to `SecurityConfig` as
      authenticated (the role split is the controller's), and a nav link.

- [ ] **Step 4: Write `stummschaltungen.md`** — §13.6's handbook entry on when muting is
      appropriate: what `*` does, why the expiry is mandatory, the difference between muting a
      finding and muting a pattern, and what happens when a mute expires. Bump `HelpTopicsTest`
      to five topics. Plan 6's execution findings warn that the topic must land in the same
      commit as the `?` affordances that link it — Task 7's affordance points here, so verify the
      link resolves before committing.

- [ ] **Step 5: Extend `SecurityRulesTest`** with the two new routes: anonymous is redirected to
      `/anmelden`, and a `USER` reaching `/stummschaltungen` is not.

- [ ] **Step 6: Green, then commit.** `./mvnw test -Pfast`.

```bash
git add src/main/java/dev/hendrikhoemberg/webtesthelper/web src/main/resources/templates src/main/resources/help src/main/resources/messages.properties src/test/java/dev/hendrikhoemberg/webtesthelper/web
git commit -m "feat(web): mute rules screen with per-site and global scope"
```

---

### Task 9: Acceptance — the noisy site

One test that plays out the scenario §6.3 wrote the feature for. It is the only place the whole
chain is exercised in order, and it is what a reviewer reads to decide the plan is done.

**Files:**
- Test: `findings/TriageAcceptanceTest.java`

- [ ] **Step 1: Write it, red.** `AbstractPostgresTest`, one site, findings driven through
      `FindingService.record` — no browser, no HTTP. The steps and what each asserts:

1. **Run 1** produces 200 findings across three check types. `NEW` is 200; nothing else has
   anything. *This is the screen §6.3 says is not triageable.*
2. **Accept the baseline.** All 200 are `ACKNOWLEDGED`.
3. **Run 2**, same 200 findings plus 2 genuinely new ones. `NEW` is 2, `KNOWN` is 200,
   `STILL_OPEN` is 0. The diff model starts paying out.
4. **Create a rule** — check type `DEAD_LINK`, subject `*linkedin.com*`, 90 days, with a reason.
   Some of the 200 already match it, and every one of those is `ACKNOWLEDGED` from step 2, so
   `applyRule` reports **0 findings muted** (D51 — the rule matches them and must change none of
   them). Assert the zero: it is the deviation's whole content, and a rule that quietly relabels
   a colleague's triage would pass every other assertion in this test.
5. **Run 3** brings 5 new LinkedIn dead links — `UNTRIAGED`, so the rule does reach them. They
   land in `KNOWN`, not `NEW`. `NEW` is 0. Nothing about this run would mail.
6. **Mute one page's findings by hand** until a date 10 days out, with a reason.
7. **Run 4** does not report them. `KNOWN` grows, `STILL_OPEN` shrinks by the same number.
8. **One acknowledged finding is fixed** in run 5, then **breaks again** in run 6. It appears in
   `FIXED`, then in `REGRESSED` — the acknowledgement did not silence a real regression (D47).
9. **A muted finding is fixed** in run 5. It appears in `FIXED` — good news outranks a mute.
10. **Sweep at day 11.** The hand-mute expires: the finding is `UNTRIAGED`, `mute_expired_at` is
    stamped, the reason is still readable, and its section in **run 7** is `STILL_OPEN` — not
    `NEW`, not `REGRESSED`. *This is the assertion the whole plan exists to make true.*
11. **Sweep at day 91.** The rule expires, its findings return, and the same section rule holds
    for all five at once.

- [ ] **Step 2: Run it and watch it fail**, then make it pass without touching the tests written
      in Tasks 1–8. If any of them needs changing, the change is a finding and goes in the
      Execution findings section, not a quiet edit.

- [ ] **Step 3: Full suite, then commit.** `./mvnw test` — browser tests included. Report the count.

```bash
git add src/test/java/dev/hendrikhoemberg/webtesthelper/findings/TriageAcceptanceTest.java
git commit -m "test(findings): acceptance for the noisy-site triage cycle"
```

---

## Completion check

Run before declaring the plan done:

- [ ] `./mvnw test` green, browser tests included. Report the count.
- [ ] `ModularityTest` passes: `findings` still declares `allowedDependencies = {"model"}` and
      nothing was added to it.
- [ ] `FlywayMigrationTest` passes — `V14` and `V15` apply to an empty database and
      `ddl-auto=validate` accepts `MuteRuleEntity` and the extended `finding` table.
- [ ] `UiMessageKeyTest`, `EnumLabelsTest` and `HelpTopicsTest` pass: every new `#{…}` resolves,
      every new key is `ui.`-prefixed, and `stummschaltungen` is a real Markdown file.
- [ ] `grep -rn "MUTED\|WONT_FIX" src/main/resources/templates` returns nothing — no triage
      identifier in a template (§13.1).
- [ ] `grep -rn "redirect:.*param\|RequestParam.*redirect" src/main/java` returns nothing — the
      redirect target is always server-built (Task 7).
- [ ] Verbatim-code budget: ``awk '/^```/{f=!f;next} f{n++} END{print n}' docs/superpowers/plans/2026-08-25-webtesthelper-p7-triage-mutes.md`` is under 150.

## Deliberately not in this plan

- **Checkboxes and a bulk bar on the run report's five sections.** The run report already has the
  bulk action §6.3 asks it for — *Accept as baseline* — and the findings list is where bulk triage
  lives. Two surfaces for one capability is twice the template and twice the test for nothing.
  The run report gains a link to the list instead.
- **A fleet-wide findings list.** The list is site-scoped because triage is site-scoped work and a
  bulk action spanning customers is not one anybody wants to fire by accident. Plan 9's dashboard
  links into the per-site lists.
- **Editing a mute rule.** Delete and re-create. An edited rule would have to decide what happens
  to the findings the old pattern muted and the new one does not, and "create a fresh rule" keeps
  the record of what was silenced, by whom, and when.
- **A triage audit table.** D50 covers the question it would have answered with one column. If
  Phase 3 needs per-transition attribution it can add one then, against a real need.
- **Notification policy, digest assembly, the "expired mutes" digest line.** Plan 8. D47 is what
  that plan's §11.1 predicate reads, and it is decided here so it is written once.
- **The dashboard's open-findings-by-severity panel.** Plan 9; `FindingQuery` is the query it
  will aggregate.

## What plan 8 consumes

- **§11.1's predicate is now a plain column read.** *New or regressed `ERROR` findings* is
  `section IN ('NEW','REGRESSED') AND severity = 'ERROR'` against `DIFF_SQL`, and D47 guarantees
  no muted or won't-fix finding can reach either section. There is no time-dependent expression
  to re-evaluate at send time (D49), so the mail and the screen cannot disagree.
- **An expiring mute produces no mail** — the finding returns as `STILL_OPEN`, which §11.1 does
  not notify on. That is deliberate, and it is the one place plan 8 may want to add something:
  `MuteSweepResult` exists so a digest can carry *"3 Stummschaltungen sind abgelaufen"* without
  re-deriving it. Decide there, not here.
- **`spring.task.scheduling.pool.size` is at 4 and all four threads are spoken for** (outbox,
  tick, retention, mute sweep). Plan 8's aggregation window, if it needs a job, needs a fifth.
- **`FindingQuery`/`FindingPage` are the read shape**, and they take a `Set<TriageStatus>`; a
  digest that wants "everything a person still has to decide about" filters on `UNTRIAGED`
  rather than inventing a second query.

---

## Execution findings

- **Template enum encapsulation:** To fulfill §13.1 and ensure zero occurrences of `MUTED` or `WONT_FIX` in templates, `TriageStatus` was extended with UI helper predicates (`requiresExpiry()`, `requiresReason()`, `allowsReason()`, `formActions()`, `defaultFormAction()`) and exposed via `@ControllerAdvice` (`TriageUiAdvice`), allowing `bewertung.html` to render options dynamically and drive Alpine visibility without string matching on enum literals.
- **Whole suite execution:** 711 tests passed (0 failures, 0 skipped), including full browser acceptance suite and ModularityTest.

### Post-execution audit (2026-08-26)

An audit against this plan found eight defects the 711 green tests did not catch. All are fixed;
the suite is now **716 tests**, green including browser. What the audit changed, and why:

- **The bulk selection script never reached the browser.** `websites/befunde.html` defined
  `findingsSelection()` in a `<script>` after `</main>`, and `layout :: seite` inserts only
  `~{::main}` — so `x-data="findingsSelection()"` resolved to nothing and every bulk POST arrived
  with an empty `ids` list. `TriageControllerTest` passed throughout because MockMvc posts `ids`
  directly and never renders the page. **The lesson for plans 8 and 9: a controller test that
  asserts model attributes proves nothing about a screen whose behaviour lives in a template.**
  `FindingListControllerTest` now asserts against the rendered body.
- **D51 was violated on rule deletion.** `TRIAGE_SQL` did not clear `muted_by_rule_id`, and
  `UNMUTE_RULE_SQL` matched on that column with no status guard — so a human who re-triaged a
  rule-muted finding to `WONT_FIX` had their decision reset to `UNTRIAGED` when the rule was
  deleted, which is the second clause of D51 in as many words. Fixed on both sides (the triage
  detaches the row from the rule; the un-mute only touches rows still `MUTED`). Task 4's test
  covered only the never-rule-muted human mute, so the override sequence was uncovered.
- **`unmuteRule` now keeps `triage_reason`, `triaged_by` and `triaged_at`**, giving it exactly
  `EXPIRE_MUTES_SQL`'s SET list. It stamped `mute_expired_at` while clearing the reason, so a
  deleted rule rendered as *"Stummschaltung abgelaufen am …"* with an empty *Damalige Begründung* —
  the one line D50 exists to print. This changed two assertions in `MuteRuleApplicationTest`.
- **A fresh manual mute now clears a stale `mute_expired_at`.** `APPLY_RULE_SQL` cleared it;
  `TRIAGE_SQL` did not, so a finding re-muted by hand after an expiry rendered both *"Stumm bis
  24.11."* and *"Stummschaltung abgelaufen am 14.11."* at once. Un-triage still leaves the stamp
  alone, per Task 1 — the `CASE WHEN ? THEN NULL` carries that distinction.
- **The checkbox left the run report.** `befundzeile` gained an unconditional checkbox in Task 6,
  and the run report shares that fragment without a selection scope — a dead control plus an Alpine
  error on every row, and directly against this plan's *"Deliberately not in this plan"*. The
  fragment now takes an `auswaehlbar` flag; `RunReportAcceptanceTest` asserts the run report has no
  `befund-checkbox`.
- **`webtesthelper.findings.page-size` was dead config.** `FindingFilterForm` and `FindingQuery`
  each hardcoded 50 and nothing read the property. The form now leaves `size` null when the query
  string carries none and the controller supplies `FindingProperties.pageSize()`.
  `FindingQuery.MAX_SIZE = 200` also caps it: `?size=100000` was one request away, and every row it
  returned was a checkbox the bulk endpoint would refuse at its own cap.
- **`MuteRuleController` no longer injects `FindingStore`.** The §13.4 preview count went straight
  past `FindingService`/`MuteRuleService` — the only place in `web` that reached past a service.
  `MuteRuleService.countMatching` now fronts it.
- **`fragments/bewertung.html` no longer hardcodes German.** §13.4's consequence sentence and the
  selection count were literal text while `ui.befunde.triage.stumm_folge`, `…stumm_folge_bulk` and
  `…ausgewaehlt` already existed unused in `messages.properties`. Because the value is only known
  to Alpine, the message is fetched with a marker as its `{0}` and split around it with
  `#strings.substringBefore/After` — the sentence stays in the properties file. **`UiMessageKeyTest`
  checks that every key used resolves; nothing checks that visible text uses a key at all**, which
  is how this survived. Worth a test in a later plan.

