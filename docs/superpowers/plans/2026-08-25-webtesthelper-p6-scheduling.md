# WebTestHelper Plan 6 — The Clock

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Nobody presses the button any more. Three tiers fire per site on a cron — pulse daily,
full weekly, deep monthly (§9) — an administrator can stop the whole fleet or one site with one
switch (§14), the pulse tier finally has the pinned key-page set that makes coverage-scoped
resolution stable (§9), and screenshots stop accumulating forever (§6.5).

**Architecture:** One new module, `scheduling`: §5.1's "cron per site, tier scopes, run creation".
It owns a `schedule` table, a tick that claims due rows and calls `RunService.enqueue`, and
nothing else — it has no opinion about what a run does. Two Phase-1 modules grow a job each:
`runner` learns to select and pin key pages after a full crawl, and to prune artifacts;
`crawler` learns to delete an artifact directory, because it is the module that created it.

**Tech Stack:** Java 25, Spring Boot 4.1.1, Spring's `CronExpression` (no Quartz — the schedules
are per-site rows, not a job store), PostgreSQL 17 via Testcontainers, Thymeleaf + HTMX + Alpine.
**No new dependency.**

**Spec:** `docs/superpowers/specs/2026-08-21-webtesthelper-design.md`; §-references point there.
**Roadmap:** `2026-08-25-webtesthelper-phase-2-roadmap.md` — plan 6 of 9, first of Phase 2.
Its deviation index (D38–D45), the Phase-1 table (D1–D37), `CLAUDE.md`'s plan calibration and
`CLAUDE.md`'s test rules all apply and are **not** restated.

**Ends with:** a site created this morning has three schedules by tonight, one of them fires at
03:00 in the site's own timezone, the run that comes out is `SCHEDULED` rather than `MANUAL`, a
pause switch stops all of it without consuming the occurrences it skipped, and a colleague who
opens the site sees "Täglich um 03:00 Uhr — nächster Lauf: Mi, 26.08.2026" rather than
`0 0 3 * * *`.

**No browser test.** Nothing here needs Chromium. The one crawl-side assertion (Task 5) extends
`CrawlRunExecutorTest`'s existing `@BeforeAll` crawl rather than adding a class, per `CLAUDE.md`.

---

## Deviations this plan introduces

Full text in the roadmap's index; the reasoning is here, because this is the plan that applies them.

- **D38 — `scheduling` depends on `runner`; one schedule row per (site, scope), seeded lazily and
  never deleted.** §6.1 draws `Schedule` as a plain child collection of `Site`, which allows two
  pulse crons on one site racing each other into the same 03:00 window. A unique index on
  `(site_id, scope)` makes "the pulse schedule" an addressable thing the UI can edit and the
  dashboard can read. *Lazily seeded* solves the backfill: sites already exist on `main` with no
  schedule rows, and a Flyway backfill cannot compute `next_fire_at` from a cron in SQL — setting
  it to `now()` would stampede the whole fleet into one crawl on the upgrade boot. *Never
  deleted* is what makes lazy seeding safe: a deleted row would reappear on the next tick, so
  §9's "any tier can be disabled" is implemented as `enabled = false`, and the UI offers no
  delete.
- **D39 — `next_fire_at` is stored, not derived.** The alternative parses every site's three cron
  expressions on every tick to find out whether any is due. Stored, the due query is one partial
  index scan, and plan 9's "next scheduled run" panel is a `min()` over the same index.
- **D40 — a missed schedule fires once, then advances past *now*.** A container down for two days
  has three pulse occurrences outstanding per site. Replaying them queues three identical crawls
  back to back, which tells nobody anything the first one did not and lands 150 crawls on the
  fleet at boot. Firing once answers the only question anyone has after downtime — *is it still
  fine?* — and the arithmetic is a single line: the next fire is computed from `now`, never from
  the occurrence that was missed.
- **D41 — the kill switches gate scheduling only.** Both switches mean "do not *schedule* this".
  A person pressing *Jetzt prüfen* on a paused site is making an explicit decision, and blocking
  it would make the switch a foot-gun during exactly the incident it was flipped for.
- **D42 — pruning computes the delete set, not the keep set.** "Delete every directory whose id
  is not in the keep set" is one failed query away from deleting every artifact the system has.
  Ranking run rows and deleting the tail cannot do that: a query that returns nothing deletes
  nothing. The cost is that a directory whose run row is gone is never cleaned up — accepted, and
  nothing deletes run rows today.
- **D43 — `spring.task.scheduling.pool.size=3`.** Spring's scheduler defaults to one thread and
  Phase 1 put the outbox dispatcher on it. This plan adds the schedule tick and the retention
  sweep; on one thread, a retention sweep over a large `/data` directory blocks mail.
- **D45 — pinned key pages rank by distinct source pages.** Raw link count is the wrong metric:
  one page with a 60-item sitemap-style link list would outrank the header nav that appears on
  every page. What "most-linked navigation target" means (§9) is *linked from many places*, which
  is distinct sources. Only pages the crawl actually fetched with a 2xx are eligible — pinning a
  404 into the pulse set would make every pulse run report the same finding forever.

## Decided constants

| Constant | Value | Why |
|---|---|---|
| default pulse cron | `0 0 3 * * *` | §9 daily 03:00 |
| default full cron | `0 0 3 * * SUN` | §9 weekly, Sunday 03:00 |
| default deep cron | `0 0 3 1 * *` | §9 monthly, 1st, 03:00 |
| default timezone | `Europe/Berlin` | the audience is internal colleagues (§4, locale German) |
| tick interval | 30 s, `webtesthelper.scheduling.tick-interval` | §9's crons are minute-granular; 30 s bounds the lateness at half a minute |
| tick enabled | `webtesthelper.scheduling.tick-enabled`, default true, **false in tests** | D33's rule: a tick that queues a run during a repository test is a debugging session nobody enjoys |
| due batch size | 50 per tick, `webtesthelper.scheduling.batch-size` | 50 sites × 3 tiers is the whole fleet (§4); the cap exists so a clock skew cannot make one tick unbounded |
| artifact retention | 12 runs per site, `webtesthelper.runner.artifact-retention-runs` | §6.5 |
| retention cron | `0 30 4 * * *`, `webtesthelper.runner.retention-cron` | after §9's 03:00 window, before office hours |
| retention enabled | `webtesthelper.runner.retention-enabled`, default true, **false in tests** | same reason as the tick |
| key pages pinned | 10 plus the base URL, `webtesthelper.runner.key-pages` | §9 |
| scheduler pool | `spring.task.scheduling.pool.size=3` | D43 |

## URL vocabulary added

| Path | Method | Role | Screen |
|---|---|---|---|
| `/websites/{id}/zeitplaene` | POST | ADMIN | Save all three tiers of one site |
| `/einstellungen` | POST | ADMIN | grows the global pause switch (existing route) |
| `/hilfe/zeitplaene` | GET | USER | New handbook topic |

Schedules are ADMIN, not USER. §12 gives `USER` "configure checks, triage, record" and reserves
sites for `ADMIN`; a schedule is what puts load on a customer's hosting and, at the deep tier,
mail into a customer's inbox (§9). That is a site-level decision.

---

### Task 1: `CronSchedule` — cron, timezone, and the catch-up rule

The whole of D40 lives in one method, and it is pure. No database, no Spring, no clock.

**Files:**
- Create: `scheduling/CronSchedule.java`, `scheduling/package-info.java`
- Modify: `model/RunScope.java`
- Test: `scheduling/CronScheduleTest.java`

**Interfaces (produces):**
- `record CronSchedule(CronExpression expression, ZoneId zone)` with
  `static Optional<CronSchedule> parse(String cron, String timezone)` and
  `Instant nextAfter(Instant instant)`.
- `RunScope.defaultCron()` returning the three constants above.

- [ ] **Step 1: Write `CronScheduleTest`, red.** Pure JUnit, no Spring context. Assertions, all
      with concrete instants so a timezone bug cannot hide behind a relative comparison:
- `0 0 3 * * *` / `Europe/Berlin` from `2026-08-25T12:00:00Z` → `2026-08-26T01:00:00Z`
  (03:00 CEST is 01:00 UTC).
- the same expression from `2026-10-24T12:00:00Z` → `2026-10-25T01:00:00Z`, and from
  `2026-10-26T12:00:00Z` → `2026-10-27T02:00:00Z`. **This is why the timezone is a column.**
  Berlin leaves CEST on 25 October 2026, so "03:00 local" is two different UTC instants either
  side of it, and a schedule stored as a UTC hour would drift an hour twice a year.
- `0 0 3 * * SUN` from a Wednesday → the coming Sunday 03:00 local.
- `0 0 3 1 * *` from mid-month → the 1st of the next month, 03:00 local.
- `parse("keine ahnung", "Europe/Berlin")` and `parse("0 0 3 * * *", "Mars/Olympus")` both
  return `Optional.empty()` — **they must not throw.** A bad row must not stop the tick for
  every other site, and Task 3 relies on this.
- `nextAfter` applied to an instant two days stale returns the occurrence after *that instant*.
  The no-backlog property itself is a dispatcher property and is asserted in Task 3; what this
  test pins is that `nextAfter` has no memory of its own.

- [ ] **Step 2: Run it and watch it fail.** `./mvnw test -Pfast -Dtest=CronScheduleTest`.
      Expected: compilation failure, `CronSchedule` does not exist.

- [ ] **Step 3: Implement `CronSchedule`.** `parse` wraps `CronExpression.parse` and
      `ZoneId.of` in a try/catch returning `Optional.empty()`. The one method worth writing out
      is the advance, because D40 *is* this line and the wrong version is the one that looks
      more careful:

```java
/** The next occurrence strictly after {@code instant}, in this schedule's own zone. */
Instant nextAfter(Instant instant) {
    ZonedDateTime next = expression.next(instant.atZone(zone));
    // next() returns null when the expression has no further occurrence (a fixed date in the
    // past). Treat that as "never again" rather than as an error: the row stays put and the
    // partial index stops matching it.
    return next == null ? null : next.toInstant();
}
```

D40 is then the *caller's* rule — Task 3 always passes `now`, never the occurrence it missed —
and it is stated there rather than baked in here, so this type stays a pure cron wrapper.

- [ ] **Step 4: Add `RunScope.defaultCron()`** returning `0 0 3 * * *` / `0 0 3 * * SUN` /
      `0 0 3 1 * *`. It belongs on `RunScope` because that enum already carries the other two
      tier facts (`checkTypes()`, `crawlsWholeSite()`), and a fourth place that knows what a
      tier means is a fourth place to forget.

- [ ] **Step 5: Declare the module.** `scheduling/package-info.java`:
      `@ApplicationModule(displayName = "Scheduling", allowedDependencies = {"model", "catalog", "runner"})`.
      `catalog` for the site's enabled flag and the pause setting, `runner` to enqueue. Nothing
      depends on `scheduling` yet; `web` is added in Task 7.

- [ ] **Step 6: Green, then commit.** `./mvnw test -Pfast -Dtest='CronScheduleTest,ModularityTest'`.

```bash
git add src/main/java/dev/hendrikhoemberg/webtesthelper/scheduling src/main/java/dev/hendrikhoemberg/webtesthelper/model/RunScope.java src/test/java/dev/hendrikhoemberg/webtesthelper/scheduling
git commit -m "feat(scheduling): cron parsing with per-schedule timezone"
```

---

### Task 2: The `schedule` table, entity and service

**Files:**
- Create: `src/main/resources/db/migration/V13__schedule.sql`
- Create: `scheduling/Schedule.java`, `scheduling/ScheduleService.java`,
  `scheduling/persistence/ScheduleEntity.java`, `scheduling/persistence/ScheduleRepository.java`
- Test: `scheduling/ScheduleServiceTest.java`

**Interfaces:**
- Consumes: `CronSchedule.parse`, `RunScope.defaultCron()` (Task 1).
- Produces:
  - `record Schedule(long id, long siteId, RunScope scope, String cron, String timezone,
    boolean enabled, Instant lastFiredAt, Instant nextFireAt)`
  - `ScheduleService` with `List<Schedule> forSite(long siteId)`,
    `List<Schedule> due(Instant now, int limit)`, `int seedMissingDefaults(Instant now)`,
    `void seedDefaults(long siteId, Instant now)`,
    `void update(long scheduleId, String cron, String timezone, boolean enabled, Instant now)`.
    **Every method returns the `Schedule` record, never the entity** — `catalog` set that rule in
    plan 1 (§5.1: the JPA entities never leave their module) and `scheduling` follows it, which
    is also why Task 3's dispatcher reads `row.nextFireAt()` and not a Lombok getter.
  - `ScheduleEntity` (Lombok `@Getter @Setter @NoArgsConstructor`, JPA, table `schedule`), mirror
    of the columns, `scope` as `@Enumerated(STRING)`, `long version` as `@Version`.
  - `ScheduleRepository extends JpaRepository<ScheduleEntity, Long>` with
    `List<ScheduleEntity> findBySiteIdOrderByScope(long siteId)` and
    `List<ScheduleEntity> findDue(Instant now, Limit limit)` — enabled rows whose `nextFireAt`
    has passed, ordered ascending. **The site's own enabled flag is Task 4's**; here the query
    knows only about the schedule. `ScheduleService.due` wraps it, turning the `int limit` into
    `Limit.of(limit)`.

- [ ] **Step 1: Write `V13__schedule.sql`.** The partial index matches the due query exactly:
      every read of this table filters on `enabled`.

```sql
-- Tiered schedules (spec 9). One row per (site, tier): the tier IS the identity, which is what
-- lets the UI edit "the pulse schedule" and stops two pulse crons racing into one 03:00 window.
-- Rows are disabled, never deleted (D38) — the dispatcher seeds missing ones on every tick, so a
-- deleted row would come back.
CREATE TABLE schedule (
    id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    site_id BIGINT NOT NULL REFERENCES site (id) ON DELETE CASCADE,
    scope TEXT NOT NULL,
    cron TEXT NOT NULL,
    -- Per schedule, not global: "03:00 local" is two different UTC instants either side of a
    -- daylight-saving change, and a stored UTC hour drifts an hour twice a year.
    timezone TEXT NOT NULL DEFAULT 'Europe/Berlin',
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    last_fired_at TIMESTAMPTZ,
    -- Stored rather than derived (D39): the tick is then one index scan instead of parsing every
    -- site's three cron expressions to find out that none of them is due.
    next_fire_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    version BIGINT NOT NULL DEFAULT 0
);

CREATE UNIQUE INDEX ux_schedule_site_scope ON schedule (site_id, scope);
CREATE INDEX ix_schedule_due ON schedule (next_fire_at) WHERE enabled;
```

- [ ] **Step 2: Write `ScheduleServiceTest`, red.** Extends `AbstractPostgresTest`,
      `@Transactional` so it rolls back. It creates its site through `SiteService`. Assertions:
- `seedDefaults` on a fresh site creates exactly three rows — one per `RunScope` — with the
  §9 crons, `Europe/Berlin`, all enabled, `lastFiredAt` null.
- every seeded `nextFireAt` is **strictly in the future**. A site created at 02:59 must not
  trigger an immediate crawl of a site nobody has finished configuring.
- calling `seedDefaults` twice leaves three rows, unchanged. It is idempotent **per scope**, not
  per site: delete a site's `DEEP` row by hand and it comes back alone. D38 means nothing
  deletes rows today, so the case that will actually exercise this is a fourth `RunScope`
  constant arriving in a later phase and needing a schedule on every existing site.
- `seedMissingDefaults` seeds every site that has no rows at all and returns how many it
  touched; a second call returns 0.
- `update` with a cron `CronSchedule.parse` rejects throws `IllegalArgumentException` and
  leaves the row byte-for-byte unchanged — asserted by re-reading, not by trusting the throw.
- `update` recomputes `nextFireAt` from `now`, not from the stored value: change the cron of a
  schedule whose `nextFireAt` is next Sunday to a daily cron and the new value is tomorrow.
- `update(…, enabled = false)` leaves the row present. There is no delete method to call.

- [ ] **Step 3: Run it and watch it fail.**
      `./mvnw test -Pfast -Dtest=ScheduleServiceTest`. Expected: compilation failure.

- [ ] **Step 4: Implement the entity, repository and service.** `seedDefaults` inserts one row
      per missing scope with `nextFireAt = CronSchedule.parse(scope.defaultCron(), zone)
      .nextAfter(now)`, and catches `DataIntegrityViolationException` on `ux_schedule_site_scope`
      as a lost race — the winner's row is what everybody wanted. This is the same
      swallow-the-duplicate shape `RunService.enqueue` already uses and for the same reason.
      `seedMissingDefaults` finds sites with no rows via a repository `@Query` with
      `NOT EXISTS`, and seeds each.

- [ ] **Step 5: Green.** `./mvnw test -Pfast -Dtest='ScheduleServiceTest,FlywayMigrationTest'`.
      `FlywayMigrationTest` is the one that proves the entity matches the migration —
      `ddl-auto=validate` fails startup on a mismatch, so a wrong column type surfaces here and
      not in production.

- [ ] **Step 6: Commit.**

```bash
git add src/main/resources/db/migration/V13__schedule.sql src/main/java/dev/hendrikhoemberg/webtesthelper/scheduling src/test/java/dev/hendrikhoemberg/webtesthelper/scheduling
git commit -m "feat(scheduling): schedule table, entity and defaults per tier"
```

---

### Task 3: The dispatcher — due detection, durable claim, enqueue

The tick is the whole feature. Everything before it was preparation and everything after it is a
screen.

**Files:**
- Create: `scheduling/ScheduleDispatcher.java`, `scheduling/ScheduleTick.java`,
  `scheduling/SchedulingProperties.java`,
  `scheduling/persistence/ScheduleClaimJdbcRepository.java`
- Modify: `src/main/resources/application.properties`,
  `src/test/resources/application-test.properties`
- Test: `scheduling/ScheduleDispatcherTest.java`

`SchedulingProperties` needs no registration: `WebtesthelperApplication` carries
`@ConfigurationPropertiesScan`, which is how `RunnerProperties` and `ReportingProperties` are
already picked up.

**Interfaces:**
- Consumes: `ScheduleService` (Task 2), `RunService.enqueue(long, RunTrigger, RunScope)`.
- Produces:
  - `ScheduleDispatcher` with `int tick(Instant now)` — returns how many runs it queued.
  - `ScheduleClaimJdbcRepository` with `boolean claim(long scheduleId, Instant expectedNextFire,
    Instant newNextFire, Instant firedAt)`.
  - `record SchedulingProperties(Duration tickInterval, boolean tickEnabled, int batchSize)`
    bound to `webtesthelper.scheduling`.

- [ ] **Step 1: Write `ScheduleDispatcherTest`, red.** Extends `AbstractPostgresTest`, **not**
      `@Transactional` — the dispatcher commits per row, so it clears `run` and `schedule` in
      `@BeforeEach` instead. It calls `tick(now)` directly; the `@Scheduled` trigger is off in
      tests. Assertions:
- a schedule backdated one minute queues exactly one run, `status = QUEUED`,
  `trigger = SCHEDULED`, `scope` equal to the schedule's, and `tick` returns 1.
- after that tick the row's `lastFiredAt` is set and `nextFireAt` is in the future.
- ticking again immediately queues nothing and returns 0.
- **D40:** a pulse schedule backdated **two days** queues exactly one run, and its new
  `nextFireAt` is the next 03:00 *after now* — not yesterday's, not the day before's. Assert
  the value, not just "in the future": the failing implementation advances by one occurrence
  at a time and would pass a mere futurity check on the third tick.
- a schedule with `enabled = false` never fires however far it is backdated.
- **a broken row does not poison the tick:** give a site's `PULSE` row the cron `nicht-ein-cron`
  and an older `nextFireAt` than a healthy `FULL` row, tick once, and assert the `FULL` run was
  queued and that the broken row's `nextFireAt` is untouched. It is the ordering that makes
  this test mean something — the broken row must be *processed first*.
- **the claim is exclusive:** call `claim(id, oldNextFire, …)` twice with the same
  `oldNextFire`; the first returns true, the second false. This is the compare-and-set that
  makes two application instances safe, and it is asserted at the repository rather than with
  threads because a two-thread test of this proves timing, not exclusivity.
- a site with a run of the same scope already `QUEUED` gets no second one — `RunService.enqueue`
  already dedupes on `ux_run_single_queued_per_site_scope`, and the tick must not defeat it.
  `tick` still returns 1: it did fire, and the queue collapsed it.
- `seedMissingDefaults` runs first: a site with no schedule rows has three after one tick, and
  none of them fired.

- [ ] **Step 2: Run it and watch it fail.** `./mvnw test -Pfast -Dtest=ScheduleDispatcherTest`.

- [ ] **Step 3: Write the claim.** It is a lease, in the same family as
      `RunLeaseJdbcRepository`'s and `OutboxClaimJdbcRepository`'s, and it is written out because
      the compare-and-set predicate is the entire concurrency argument:

```sql
UPDATE schedule
   SET next_fire_at = ?, last_fired_at = ?, updated_at = now(), version = version + 1
 WHERE id = ? AND next_fire_at = ?
```

`rowsUpdated == 1` means this tick owns the occurrence. Zero means another instance — or this
instance's previous tick, still in flight — already advanced the row, and the caller must skip
it without enqueueing. Nothing here needs `SELECT … FOR UPDATE`: the predicate *is* the lock,
and it is the value being replaced, so the update is idempotent under retry.

- [ ] **Step 4: Implement `ScheduleDispatcher.tick`.** In order:
  1. `scheduleService.seedMissingDefaults(now)` — D38's lazy backfill.
  2. `scheduleService.due(now, properties.batchSize())`, ordered by `next_fire_at` ascending so
     the fleet drains oldest-first and a clock skew cannot starve one site.
  3. For each row: `CronSchedule.parse(row)` — on `Optional.empty()`, log **once per row per
     tick at WARN with the site and the cron text** and `continue`, leaving `next_fire_at`
     alone so the row stays visibly stuck rather than silently skipping forward; then
     `claim(id, row.nextFireAt(), cron.nextAfter(now), now)` — **`now`, not `row.nextFireAt()`,
     and that argument is D40**; on `false`, `continue`; then `runService.enqueue(siteId,
     SCHEDULED, scope)`.
  4. Count the enqueues and return the count.

  **Claim before enqueue, and a failed enqueue is a missed run, logged at ERROR.** The
  alternative — one transaction around both, rolled back on failure — retries every 30 s
  forever. The realistic failure is `IllegalArgumentException` from a site deleted between the
  query and the enqueue, which no amount of retrying fixes; a database outage never gets the
  claim committed in the first place. Missing one occurrence and saying so in the log is the
  better trade.

- [ ] **Step 5: Add `ScheduleTick`** — `@Component`, `@ConditionalOnProperty(name =
      "webtesthelper.scheduling.tick-enabled", matchIfMissing = true)`, one
      `@Scheduled(fixedDelayString = "${webtesthelper.scheduling.tick-interval:30s}")` method
      calling `tick(Instant.now())` and swallowing nothing — Spring logs a thrown scheduled task
      and keeps the schedule alive, which is the behaviour wanted.

- [ ] **Step 6: Properties.** `application.properties` gains
      `webtesthelper.scheduling.tick-interval=30s`, `.tick-enabled=true`, `.batch-size=50`, and
      **`spring.task.scheduling.pool.size=3` (D43)** — Spring's scheduler is one thread by
      default, the outbox dispatcher is already on it, and Task 6 adds a third job.
      `application-test.properties` gains `webtesthelper.scheduling.tick-enabled=false`.

- [ ] **Step 7: Green, then commit.**
      `./mvnw test -Pfast -Dtest='ScheduleDispatcherTest,ScheduleServiceTest,ModularityTest'`.

```bash
git add src/main/java/dev/hendrikhoemberg/webtesthelper/scheduling src/main/resources/application.properties src/test/resources/application-test.properties src/test/java/dev/hendrikhoemberg/webtesthelper/scheduling
git commit -m "feat(scheduling): tick claims due schedules and queues runs"
```

---

### Task 4: The kill switches

§14 names two: global pause and per-site disable. `site.enabled` has existed as an unread column
since plan 1 and now gets something to stop. A switch nobody can flip is not a switch, so the
screen for both ships in this task rather than in Task 7.

**Files:**
- Modify: `catalog/AppSettings.java`, `catalog/SiteService.java`, `catalog/SiteForm.java`,
  `scheduling/ScheduleDispatcher.java`,
  `scheduling/persistence/ScheduleRepository.java` (the `findDue` join),
  `web/SettingsController.java`, `web/SettingsForm.java`, `web/SiteFormModel.java`,
  `web/SiteController.java`, `src/main/resources/templates/einstellungen/index.html`,
  `src/main/resources/templates/websites/formular.html`,
  `src/main/resources/templates/layout.html`, `src/main/resources/messages.properties`
- Test: `scheduling/ScheduleKillSwitchTest.java`, and additions to `web/SettingsControllerTest.java`
  and `web/SiteControllerTest.java`

**Interfaces (produces):**
- `AppSettings.KEY_SCHEDULING_PAUSED = "scheduling.paused"`, `boolean schedulingPaused()`,
  `void saveSchedulingPaused(boolean paused)` — same `getSetting`/`saveSetting` pair as
  `redirectAllMailTo`, unencrypted.
- `SiteForm` gains `boolean enabled` as its last component; `SiteService.applyForm` applies it.
- `SiteFormModel` gains `Boolean enabled` (**`Boolean`, not `boolean`** — plan 5 measured this:
  an unchecked checkbox binds null) and
  `static SiteFormModel of(SiteContext context, boolean enabled)`. `SiteContext` is **not**
  given an `enabled` component: the runner has no business knowing, and `SiteSummary.enabled()`
  already carries it for the one caller that does.

- [ ] **Step 1: Write `ScheduleKillSwitchTest`, red.** Same shape as `ScheduleDispatcherTest`.
      The assertions are all about what must *not* happen, and one of them is the whole point:
- with `schedulingPaused = true`, a schedule backdated a day queues nothing, and **its
  `nextFireAt` and `lastFiredAt` are unchanged.** A pause that consumed the occurrences it
  skipped would silently swallow two days of runs, which is indistinguishable from the outage
  the pause was flipped for. Assert the exact stored values before and after.
- unpausing and ticking then queues the run.
- with the site `enabled = false`, the same: nothing queued, nothing advanced, and the site's
  schedules are still seeded and listed (a disabled site is paused, not forgotten).
- re-enabling the site and ticking queues the run.
- **D41:** `RunService.enqueue(siteId, MANUAL, FULL)` succeeds while the site is disabled and
  while the pause is on. The kill switches gate the clock, not the person.

- [ ] **Step 2: Run it and watch it fail.**

- [ ] **Step 3: Implement the global pause.** `ScheduleDispatcher.tick` returns 0 before the
      seed step when `appSettings.schedulingPaused()`. Log at **DEBUG**, not INFO: a paused
      instance ticks 2,880 times a day and an INFO line each time buries everything else.

- [ ] **Step 4: Implement the per-site switch in SQL, not Java.** `findDue`'s `@Query` joins
      `site` and requires `site.enabled`. Filtering in Java after the query would let a fleet
      of disabled sites consume the whole `batchSize` and starve the enabled ones — the same
      class of bug as claiming a batch and then discarding most of it.

- [ ] **Step 5: Wire `enabled` through the site form.** `SiteFormModel` gains the field and the
      `of(context, enabled)` overload; `SiteController.bearbeiten` passes
      `siteService.summary(id).enabled()`; `SiteForm` and `SiteService.applyForm` carry it to
      the entity. `formular.html` gets a checkbox with `#{ui.websites.formular.enabled}` and,
      under §13.4, a sentence stating the consequence: *"Deaktivierte Websites werden nicht mehr
      automatisch geprüft. Ein Lauf über „Jetzt prüfen" ist weiterhin möglich."*

- [ ] **Step 6: Wire the pause switch and its banner.** `SettingsForm` gains
      `Boolean schedulingPaused`; the settings template gets a checkbox with the §13.4 sentence
      *"Solange die Planung angehalten ist, startet kein Prüflauf von selbst — auf keiner
      Website."*; `layout.html` gets a second banner beside the mail-health one, rendered when
      the pause is on, linking to `/einstellungen`. It is an **info** banner, not a warning: a
      pause is a deliberate state, but an invisible one is how monitoring goes quiet without
      anyone noticing (§11.5's argument, applied to the clock).

      The banner needs the flag on every page, so extend the existing `HealthBannerAdvice`
      rather than adding a second `@ControllerAdvice` — plan 5's review already established
      that each advice attribute is a query on every request, the 3 s progress poll included, so
      this one must be a single cheap read and must not grow a second.

- [ ] **Step 7: Controller tests.** `SettingsControllerTest`: posting the form with the box
      ticked persists `scheduling.paused = true` and un-ticked persists false (the null-binding
      case again); a `USER` gets 403; a tokenless POST is 403. `SiteControllerTest`: posting the
      site form with the box un-ticked disables the site. Plus a rendering assertion that the
      banner text appears when paused and does not when it is not.

- [ ] **Step 8: Green, then commit.**
      `./mvnw test -Pfast -Dtest='ScheduleKillSwitchTest,SettingsControllerTest,SiteControllerTest,SiteServiceTest,UiMessageKeyTest'`.

```bash
git add -A src/main src/test
git commit -m "feat(scheduling,web): global pause and per-site disable"
```

---

### Task 5: Pinned key pages, populated from the first full crawl

§9 calls this required for correctness, not a convenience: coverage-scoped resolution (§6.4)
compares a run's visited URLs against a finding's location, so a pulse set recomputed each run
would make findings flicker between resolved and regressed for reasons nobody could explain.
Phase 1 shipped the column, the `CrawlService` branch that reads it, and nothing that fills it.

**Files:**
- Create: `runner/KeyPageSelector.java`
- Modify: `runner/CrawlRunExecutor.java`, `runner/RunnerProperties.java`,
  `catalog/SiteService.java`, `web/SiteFormModel.java`,
  `src/main/resources/templates/websites/formular.html`,
  `src/main/resources/templates/websites/detail.html`,
  `src/main/resources/messages.properties`, `src/main/resources/application.properties`
- Test: `runner/KeyPageSelectorTest.java`, additions to `runner/CrawlRunExecutorTest.java`

**Interfaces (produces):**
- `KeyPageSelector.select(RunSnapshots snapshots, NormalizedUrl baseUrl, int limit)`
  → `List<String>` of absolute normalised URLs, base URL first.
- `SiteService.pinKeyPages(long siteId, List<String> pages)` — a plain setter. The
  *only-if-empty* rule lives in the executor, which is the only caller that needs it; the form
  overwrites deliberately.
- `RunnerProperties` gains `int keyPages`.

- [ ] **Step 1: Write `KeyPageSelectorTest`, red.** Pure, no Spring, built with the existing
      `support/Snapshots` helper. Assertions:
- a target linked from five of five pages outranks one linked five times from a single page.
  **This is D45 and it is the test that matters** — the naive implementation counts links.
- a target with no successful snapshot in the run is excluded, whether it 404s, is
  unreachable, or was simply never crawled. Pinning a broken page would make every pulse run
  report the same finding for ever.
- the base URL is always present and always first, even with zero inbound links.
- `limit` is respected and counts the base URL as one of the entries beyond it — the result is
  `limit + 1` at most, matching §9's "default 10, plus the homepage".
- ties are broken by URL ascending, asserted with two targets on equal counts. A
  non-deterministic pulse set is the exact drift §9 forbids.
- empty snapshots yield the base URL alone, not an empty list.

- [ ] **Step 2: Run it and watch it fail.**

- [ ] **Step 3: Implement `KeyPageSelector`.** The counting and ranking are written out because
      the metric is the decision and the wrong one type-checks identically:

```java
Map<String, Long> inboundSources = snapshots.snapshots().stream()
        .flatMap(page -> page.internalLinks().stream()
                .map(link -> link.target().value())
                .distinct()                        // D45: one page linking a target twice is one
                .map(target -> Map.entry(target, page.url().value())))
        .filter(e -> !e.getKey().equals(e.getValue()))          // a page linking to itself is not a source
        .collect(Collectors.groupingBy(Map.Entry::getKey, Collectors.counting()));

Map<String, PageSnapshot> crawled = snapshots.byUrlIndex();
List<String> ranked = inboundSources.entrySet().stream()
        .filter(e -> healthy(crawled.get(e.getKey())))          // reachable and 2xx
        .sorted(Comparator.<Map.Entry<String, Long>>comparingLong(Map.Entry::getValue).reversed()
                .thenComparing(Map.Entry::getKey))              // deterministic, or the set drifts
        .map(Map.Entry::getKey)
        .filter(url -> !url.equals(baseUrl.value()))
        .limit(limit)
        .toList();
```

`healthy` is a private predicate on the class: a non-null snapshot that is `reachable()` with an
`httpStatus()` in 200–299. The result is `baseUrl.value()` prepended to `ranked`. Absolute
normalised URLs are stored, not paths: `CrawlService` resolves the stored values against the base URL, which accepts both, and a
bare path is ambiguous on a site served from a sub-path.

- [ ] **Step 4: Wire it into `CrawlRunExecutor`.** After the run's findings are materialised,
      when **all** of these hold: the scope is `FULL`, the site's `pinnedKeyPages()` is empty,
      and the crawl was **not** partial. The last condition is the one worth defending: a
      budget-capped crawl saw an arbitrary slice of the site, and freezing that slice into the
      pulse set is precisely the drift §9 exists to prevent. A site whose first full crawl hits
      its budget gets no pinned set and no pulse coverage until its budget is raised, which is
      visible on the site screen and is the honest state.

      Failures here must not fail the run: wrap in a try/catch that logs at WARN, in the same
      spirit as `CrawlService.enqueueDiscovered`.

- [ ] **Step 5: Add the assertions to `CrawlRunExecutorTest`.** That class already crawls the
      fixture once in `@BeforeAll`; this costs no browser time. After that crawl the fixture
      site's `pinnedKeyPages` is non-empty, starts with the base URL, contains no URL the crawl
      failed on, and is at most `keyPages + 1` long. Then a second, non-browser assertion in the
      same class or in `SiteServiceTest`: with pins already present, `pinKeyPages` is not called
      again — hand-edited pins survive the next full run.

- [ ] **Step 6: Expose the pins in the UI.** A textarea in `formular.html`
      (`#{ui.websites.formular.pinnedKeyPages}`, one URL per line, reusing `SiteFormModel`'s
      existing `splitPatterns` splitter) and a read-only list on the site detail page with the
      §13.5 `?` affordance pointing at the new `zeitplaene` topic from Task 7 — the pulse set
      only makes sense next to an explanation of what a pulse run is.

- [ ] **Step 7: Green, then commit.** `./mvnw test -Pfast -Dtest='KeyPageSelectorTest,SiteServiceTest,SiteControllerTest,UiMessageKeyTest'`
      then the browser class: `./mvnw test -Dtest=CrawlRunExecutorTest`.

```bash
git add -A src/main src/test
git commit -m "feat(runner,catalog): pin key pages from the first full crawl"
```

---

### Task 6: Artifact retention

§6.5: artifacts are pruned after the last 12 runs per site; findings are small and kept
indefinitely. Nothing in Phase 1 deleted a screenshot, and the fixture-site suite alone writes
one per page per run.

**Files:**
- Create: `crawler/ArtifactStore.java`, `runner/ArtifactRetentionService.java`,
  `runner/ArtifactRetentionScheduler.java`
- Modify: `runner/RunnerProperties.java`, `runner/persistence/RunResultJdbcRepository.java`
  (or a new query method on it), `src/main/resources/application.properties`,
  `src/test/resources/application-test.properties`
- Test: `crawler/ArtifactStoreTest.java`, `runner/ArtifactRetentionServiceTest.java`

**Interfaces (produces):**
- `ArtifactStore` (in `crawler`, which owns `CrawlerProperties.artifactDir`) with
  `int deleteRunArtifacts(Collection<Long> runIds)` returning the number of directories removed.
- `ArtifactRetentionService` (in `runner`, which knows what a run is) with `int prune()`.
- `RunnerProperties` gains `int artifactRetentionRuns` and `boolean retentionEnabled`.

The split is what the module directions force and it is also the right one: `runner` may depend
on `crawler`, never the reverse, so the module that knows *which* runs to prune calls the module
that knows *where* the files are. Neither needs a new property naming the other's directory.

- [ ] **Step 1: Write `ArtifactStoreTest`, red.** No database, a `@TempDir` as the artifact
      directory. Assertions:
- deleting an existing run directory removes it and its contents recursively and returns 1.
- a run id with no directory is not an error and does not count.
- directories not named in the call are untouched — including a directory whose name is not a
  number at all, which the store must ignore rather than interpret.
- the store never resolves outside its artifact directory. The ids are `long`s, so traversal is
  not expressible, but assert it anyway: this is the one method in the system that deletes
  files, and the guarantee should be a test rather than a comment.

- [ ] **Step 2: Write `ArtifactRetentionServiceTest`, red.** Extends `AbstractPostgresTest`,
      artifact dir from the test properties, clears `run` in `@BeforeEach`, creates directories
      by hand. Assertions:
- a site with 15 terminal runs keeps the 12 newest directories by `queued_at` and deletes the
  3 oldest; `prune()` returns 3.
- **a `RUNNING` run's directory is never deleted, even when it is the oldest.** A sweep that
  deletes the screenshots of a run currently writing them is a corrupted report, and the
  `QUEUED`/`RUNNING` exclusion is in the SQL, not in Java, so no caller can forget it.
- two sites are ranked independently: site A's 15 runs do not shorten site B's 3.
- **D42:** a directory named for a run id that has no row is left alone.
- running `prune()` twice deletes nothing the second time.

- [ ] **Step 3: Run both and watch them fail.**

- [ ] **Step 4: Implement the delete-set query.** The safety property is in this statement and
      that is why it is written out rather than described:

```sql
SELECT id FROM (
    SELECT id, row_number() OVER (PARTITION BY site_id ORDER BY queued_at DESC) AS rn
      FROM run
     WHERE status IN ('COMPLETED', 'FAILED', 'CANCELLED')
) ranked
 WHERE rn > ?
```

Only terminal runs are ranked, so a `QUEUED` or `RUNNING` run is not a candidate at any depth.
And it returns the runs to **delete**, never the runs to keep (D42): a query that comes back
empty then deletes nothing, where the keep-set shape would delete everything.

- [ ] **Step 5: Implement `ArtifactStore.deleteRunArtifacts` and
      `ArtifactRetentionService.prune`.** `prune` runs the query, hands the ids to the store, and
      logs one INFO line with the count — this is a destructive job and its only trace should not
      be an absence of files.

- [ ] **Step 6: Add `ArtifactRetentionScheduler`** — `@ConditionalOnProperty(name =
      "webtesthelper.runner.retention-enabled", matchIfMissing = true)`, one
      `@Scheduled(cron = "${webtesthelper.runner.retention-cron:0 30 4 * * *}")` method. 04:30 is
      after §9's 03:00 window and before anyone is at a desk. Properties:
      `webtesthelper.runner.artifact-retention-runs=12`, `.retention-enabled=true`,
      `.retention-cron=0 30 4 * * *`; `retention-enabled=false` in the test properties.
      `spring.task.scheduling.pool.size=3` is already set by Task 3 and this is the third job it
      was set for.

- [ ] **Step 7: Green, then commit.**
      `./mvnw test -Pfast -Dtest='ArtifactStoreTest,ArtifactRetentionServiceTest'`.

```bash
git add -A src/main src/test
git commit -m "feat(runner,crawler): prune artifacts beyond the last twelve runs per site"
```

---

### Task 7: The schedules screen

The screen a non-technical colleague reads. §13.1 forbids internal identifiers reaching it, and
`0 0 3 * * SUN` is one — so the common case is a time of day, and the cron survives as an
escape hatch behind a disclosure.

**Files:**
- Create: `scheduling/TierCron.java`, `web/ScheduleController.java`, `web/ScheduleFormModel.java`,
  `web/ScheduleView.java`, `src/main/resources/templates/fragments/zeitplaene.html`,
  `src/main/resources/help/zeitplaene.md`
- Modify: `web/package-info.java` (add `"scheduling"`), `web/SiteController.java`,
  `src/main/resources/templates/websites/detail.html`,
  `src/main/resources/messages.properties`
- Test: `scheduling/TierCronTest.java`, `web/ScheduleControllerTest.java`

**Interfaces:**
- Consumes: `ScheduleService.forSite`, `.update` (Task 2); `CronSchedule` (Task 1).
- Produces:
  - `TierCron` with `static String compose(RunScope scope, LocalTime time)` and
    `static Optional<LocalTime> timeOfDay(RunScope scope, String cron)`.
  - `record ScheduleView(RunScope scope, String cron, String timezone, boolean enabled,
    LocalTime timeOfDay, Instant lastFiredAt, Instant nextFireAt)` — `timeOfDay` null when the
    stored cron does not fit the tier's shape.
  - `record ScheduleFormModel(List<Row> zeitplaene)` with
    `record Row(RunScope scope, String zeit, String cron, String timezone, Boolean enabled)`.

- [ ] **Step 1: Write `TierCronTest`, red.** Pure. The tier fixes the day part, the form supplies
      the time, so the two together determine the expression. Assertions:
- `compose(PULSE, 03:00)` → `0 0 3 * * *`; `compose(FULL, 03:00)` → `0 0 3 * * SUN`;
  `compose(DEEP, 03:00)` → `0 0 3 1 * *` — the three defaults are exactly what the form
  produces at its default time, so a site edited and saved unchanged keeps its default cron.
- `compose(PULSE, 22:45)` → `0 45 22 * * *`, and `timeOfDay(PULSE, "0 45 22 * * *")` →
  `22:45`. Round-trip on all three tiers.
- `timeOfDay(PULSE, "0 0 3 * * MON,THU")` → empty. A hand-written cron that does not fit the
  shape must be *reported* as not fitting, so the screen can show the advanced field expanded
  rather than silently rewriting somebody's expression on the next save.

- [ ] **Step 2: Write `ScheduleControllerTest`, red.** `@WebMvcTest(ScheduleController.class)`
      with `@MockitoBean ScheduleService` and `SiteService`. Assertions:
- `GET /websites/1` as `USER` renders three tier rows with their German tier names and no raw
  cron string in the visible text (the advanced input's `value` may carry it; the rendered
  prose must not).
- `POST /websites/1/zeitplaene` as `ADMIN` with three valid times calls `update` three times
  with the composed crons, and redirects to `/websites/1`.
- the same POST as `USER` is 403; without a CSRF token, 403.
- a row with `zeit = "25:00"` re-renders the form with a field error and calls `update`
  **zero** times — a partial save across three tiers is the worst outcome available here.
- a row whose advanced cron field is filled takes the cron verbatim and ignores `zeit`, and an
  unparseable one is a field error on that row, not an exception.

- [ ] **Step 3: Run both and watch them fail.**

- [ ] **Step 4: Implement `TierCron` and `ScheduleController`.** One route, one POST, all three
      tiers at once — a per-tier route would mean three navigations to change an 03:00 that is
      wrong for the same reason on all three. Validation happens for every row before any
      `update` call.

- [ ] **Step 5: Build the fragment.** `fragments/zeitplaene.html`, included from
      `websites/detail.html`. Per tier: the German tier name (`#{ui.runscope.PULSE}`, which
      already exists), a plain-German sentence for when it runs
      (`ui.zeitplan.beschreibung.PULSE` = *"Täglich um {0} Uhr"*, `.FULL` = *"Jeden Sonntag um
      {0} Uhr"*, `.DEEP` = *"Am 1. jedes Monats um {0} Uhr"*), the next and last fire formatted
      in the schedule's own timezone, an enable checkbox, a time input, and an *Erweitert*
      disclosure holding the raw cron and timezone. The disclosure is Alpine local state — it
      never touches the server, which is §12's division of labour exactly.

      **§13.4 at the point of decision:** the `DEEP` row states what it will do —
      *"Die Tiefenprüfung verschickt Formular-Testnachrichten an die Website. Sie ist erst ab
      Ausbaustufe 3 wirksam."* The second sentence is not padding: the tier is schedulable now
      and its side effects arrive in Phase 3, and a colleague who enables it today deserves to
      know which of those is true.

- [ ] **Step 6: Write `help/zeitplaene.md`** — what the three tiers are, why the pulse set is
      pinned, what pausing does, and why the deep tier is monthly rather than daily (§9's three
      reasons: side effects, load on company hosting, third-party rate limits). Wire a
      `hinweis-schalter` affordance to it from the schedules fragment and from the pinned-pages
      block Task 5 added. `HelpTopicsTest` then has a fourth topic and its three-distinct-topics
      assertion keeps holding.

- [ ] **Step 7: Add `"scheduling"` to `web/package-info.java`'s allowed dependencies.**

- [ ] **Step 8: Green, then commit.**
      `./mvnw test -Pfast -Dtest='TierCronTest,ScheduleControllerTest,SiteDetailControllerTest,UiMessageKeyTest,HelpTopicsTest,ModularityTest'`.

```bash
git add -A src/main src/test
git commit -m "feat(web,scheduling): schedules screen with plain-German tiers"
```

---

### Task 8: Acceptance — a full tier cycle

One test that reads like the feature. Browser-free, database-backed, tick called by hand.

**Files:**
- Create: `scheduling/ScheduleAcceptanceTest.java`

- [ ] **Step 1: Write it.** Extends `AbstractPostgresTest`, not `@Transactional`, clears `run`,
      `schedule`, `site` and the pause setting in `@BeforeEach`. The steps are the assertions:

1. Create a site through `SiteService`. Tick once. → three schedules exist with the §9 crons,
   `Europe/Berlin`, all enabled; **no run is queued**, because every default fire is in the future.
2. Backdate the `PULSE` row by one minute. Tick. → exactly one run: `QUEUED`, `SCHEDULED`,
   `PULSE`. Its schedule's `lastFiredAt` is set and `nextFireAt` is tomorrow's 03:00 in Berlin,
   asserted as an instant.
3. Tick again. → still one run in the table.
4. Backdate `PULSE` by two days. Tick. → one *more* run, and `nextFireAt` is the next 03:00
   after now. **The two intervening occurrences are not replayed** (D40).
5. Pause globally. Backdate `FULL` by a day. Tick. → no new run, and `FULL`'s `nextFireAt` and
   `lastFiredAt` are exactly what they were.
6. Unpause. Disable the site. Tick. → still nothing, still unchanged.
7. Re-enable the site. Tick. → the `FULL` run is queued.
8. Disable the `DEEP` schedule and backdate it a month. Tick. → nothing. §9's "any tier can be
   disabled", proven at the tier rather than at the site.
9. `RunService.enqueue(site, MANUAL, FULL)` while paused → succeeds (D41).

- [ ] **Step 2: Run the whole suite.** `./mvnw test` — all of it, browser included. The count
      should be Phase 1's 558 plus this plan's; record the number in the execution findings.

- [ ] **Step 3: Commit.**

```bash
git add src/test/java/dev/hendrikhoemberg/webtesthelper/scheduling/ScheduleAcceptanceTest.java
git commit -m "test(scheduling): acceptance for the full tier cycle"
```

---

## Completion check

Run before declaring the plan done:

- [ ] `./mvnw test` green, browser tests included. Report the count.
- [ ] `ModularityTest` passes with `scheduling` present and nothing depending on `web`.
- [ ] `FlywayMigrationTest` passes — `V13` applies to an empty database and `ddl-auto=validate`
      accepts `ScheduleEntity`.
- [ ] `UiMessageKeyTest` and `HelpTopicsTest` pass: every new `#{…}` resolves, every new key is
      `ui.`-prefixed, and `zeitplaene` is a real Markdown file.
- [ ] `grep -rn "0 0 3" src/main/resources/templates` returns nothing — no cron literal in a
      template.
- [ ] Verbatim-code budget: ``awk '/^```/{f=!f;next} f{n++} END{print n}' docs/superpowers/plans/2026-08-25-webtesthelper-p6-scheduling.md`` is under 150.

## Deliberately not in this plan

- **Triage, mutes, the digest, the dashboard, guided setup** — plans 7–9. This plan makes the
  runs happen; making them *tell* you is the next three.
- **A "next scheduled run" panel.** Plan 9's dashboard owns it; `next_fire_at` (D39) is stored
  so that panel is a `min()` over an existing index.
- **Per-site concurrency limits.** §12 lists concurrency under Settings; it is a global
  worker-pool number (§5.4), not a schedule property, and it lands in plan 9 with the rest of
  the Settings screen.
- **Retrying a schedule whose enqueue failed.** Named in Task 3 with its reasoning: the
  realistic failure is a deleted site, which no retry fixes.
- **§16's container image.** Roadmap decision, unchanged.

## What plan 7 consumes

- `RunTrigger.SCHEDULED` runs now exist, so the findings list must not assume every run had a
  person behind it.
- The tick is the mechanism plan 7's **mute-expiry sweep** should use — a second `@Scheduled`
  in `scheduling` would be a third clock. Prefer a method on the existing tick or a sibling job
  in the module that owns the expiry, and remember D43's pool of three is now fully spoken for:
  a fourth job needs a fourth thread.
- `AppSettings` now carries a boolean setting (`scheduling.paused`) with a getter/saver pair;
  copy that shape rather than inventing a second one for any global flag plan 7 needs.
- Pinned key pages exist, so a `PULSE` run finally covers a stable URL set — which is the
  precondition for §6.4's coverage-scoped resolution to behave across tiers. Plan 7's mute
  semantics interact with it: a mute scoped to a URL outside the pulse set must not appear to
  "expire" merely because no pulse run ever visits it.

---

## Execution findings

Plan 6 executed 2026-08-25 with subagent-driven development: eight task commits plus four
review-fix commits (`8b17779`…`72a9a25`, 65 files, +3,125 −42, measured with
`git diff --shortstat`). **628 full tests green**
(Phase 1's 558 + 70 new), all but two browser-free. The plan's "no browser test" claim held:
Task 5's crawl-side assertions extended `CrawlRunExecutorTest`'s existing `@BeforeAll` crawl
rather than adding a class.

**Measured corrections to plan constants, decided by runtime evidence:**

- **The DST assertion on 2026-10-24 → 01:00Z is wrong; the runtime says 02:00Z.** Berlin's
  fall-back on 25 Oct 2026 happens *at* 03:00, so 03:00 local that day is CET (02:00Z) — a
  CEST 03:00 never exists. Spring 7.0.9's `CronExpression.next` resolves it to `03:00+01:00`.
  The test asserts 02:00Z, and the DST-drift argument survives via the summer (Aug 26 → 01:00Z)
  vs winter (Oct 27 → 02:00Z) pair. Same family of finding as plan 4's `Fingerprint` join:
  a concrete instant in prose is measured, not trusted.
- **`next_fire_at TIMESTAMPTZ NOT NULL` contradicted Task 1's own "never again" contract**
  (`nextAfter` returns null for a cron with no future occurrence, and the row is then meant to
  "stay put and stop matching"). V13's NOT NULL was dropped in a review-fix commit; the column
  is nullable and `findDue`'s `<= :now` predicate excludes NULL rows, which is what the
  Task-1 comment meant by "the partial index stops matching it". The dispatcher guards the null
  path with a WARN-and-continue, tested with `0 0 3 31 2 *` (parses, never fires — verified
  against the actual Spring version).

**Review-driven fixes worth knowing about:**

- **Task 7's all-or-nothing save had a hole: a blank timezone.** `validate` accepted it, then
  `update` threw mid-loop *after* earlier tiers had already committed per-row (the service is
  deliberately non-@Transactional) — the exact "worst outcome" the plan forbids, delivered as
  a 500. Fixed by rejecting a blank zone as a field error before any write.
- **The plan's own D45 example does not discriminate.** "Five of five pages" vs "five times
  from one page" both score 5 under raw counting *and* under distinct sources — a tie the URL
  tie-break resolves the same way, so a naive link-counting implementation passes the test.
  The committed test uses six links from one page so the two metrics disagree on *order*.
- **Task 8's step 4 ("one more run") is literally false against the queue dedupe.** A second
  backdated occurrence of the same scope collapses into the still-`QUEUED` first run via
  `ux_run_single_queued_per_site_scope`. The acceptance test completes the first run before
  re-backdating — the minimal stitch that keeps "one more run" meaningful, and the same stitch
  step 9 needs for the D41 manual run.
- **`zeitplaene.md` landed in Task 5, not Task 7.** The pinned-pages `?` affordance needs the
  topic to exist in the same commit as the link; a real stub shipped with Task 5 and Task 7
  expanded it. HelpTopicsTest moved to 4 topics at Task 5, not Task 7.

**Post-review fixes, 2026-08-25 (after the plan was declared done). 632 full tests green.**

- **`sec:authorize` had never worked, anywhere in the app.**
  `thymeleaf-extras-springsecurity6` was not on the classpath, so Thymeleaf did not recognise the
  `sec:` namespace and copied those attributes into the HTML verbatim — the admin nav links,
  `detail.html`'s Bearbeiten button and this plan's schedule form all rendered for every `USER`,
  and `sec:authentication="name"` showed the placeholder instead of the username. Nothing failed,
  because no test had ever asserted that a gate *hides* anything. Predates plan 6; found while
  gating the schedules form. The routes were never exposed — `SecurityConfig` and
  `SecurityRulesTest` were correct throughout — so this was an affordance leak, not an
  authorization hole. Fixed by adding the (Boot-managed) dependency, which supersedes the
  preamble's "No new dependency". The regression test asserts a rendered page contains no literal
  `sec:` string: a dropped jar is invisible in behaviour but obvious in the markup.
- **The `partialCoverage` guard on key-page pinning had no assertion.** `CrawlRunExecutorTest`'s
  `@BeforeAll` already built the case — run 3 is budget-capped and the pins were cleared before it
  — and a comment claimed the outcome, but nothing checked it. A comment is not a test. Now
  `aPartialFullCrawlPinsNothing`.
- **`ScheduleRepository`'s `@Query` reaches into `catalog.persistence.SiteEntity`, and
  `ModularityTest` cannot see it.** JPQL names entities as text, so the reference compiles to no
  bytecode and ArchUnit passes on it rather than approving it. The dependency is intended (D41's
  site-enabled predicate belongs in the query, not in Java) but it is on `catalog`'s internals,
  which modulith would reject if it could see it. Documented on the interface; the unused import is
  kept deliberately so the coupling is greppable. Hibernate's startup JPQL validation is what
  actually guards a rename.
- **This section's diffstat was wrong** — it read "63 files, +6,164 −172" against an actual
  65 / +3,125 / −42. Numbers in findings are load-bearing precisely because they cannot be
  regenerated; this one was never measured.

**What the plan got right and is worth reusing:** specifying *assertions* rather than test
bodies again produced tests that match the plan nearly line for line, and the "one method per
module concern" architecture held under review with zero module-boundary violations — the
runner→crawler retention split and the scheduling→catalog→runner triangle passed
`ModularityTest` untouched. The claim-repository CAS shape (copied from the two Phase-1
leases) needed no revision; two reviews checked its concurrency argument and found it sound.
The one thing to watch: with the D43 pool of three fully spoken for (outbox, tick, retention),
plan 7's mute-expiry sweep genuinely needs a fourth thread or a shared job — the roadmap's
note is confirmed, not hypothetical.
