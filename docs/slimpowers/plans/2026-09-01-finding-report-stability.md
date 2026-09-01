# Finding Report Stability Implementation Plan

**Goal:** Stop unverifiable external-link notices from appearing as repaired defects and preserve each completed run's report classification after later runs execute.

**Architecture:** Keep unverifiable findings as resolved lifecycle records so their detail URLs and evidence remain available, but classify their resolution as an internal expiry bucket that is omitted from user-facing report sections and counts. Persist the calculated report section for every completed finding record in a small Flyway-managed table; report views prefer that snapshot and fall back to the existing live diff for legacy runs.

**Tech Stack:** Spring Boot, Thymeleaf, JdbcTemplate, PostgreSQL, Flyway, JUnit 5, AssertJ, MockMvc, Testcontainers PostgreSQL.

**Spec:** Existing finding lifecycle and reporting rules in `FindingStore`, `FindingService`, and `RunDiff`; no separate product specification file is present.

## Global Constraints

- Preserve German-only rendered UI copy and existing `ui.*` message conventions.
- Keep finding history and `/befunde/{id}` links intact; do not delete expired findings.
- Use PostgreSQL migrations under `src/main/resources/db/migration/`.
- Use real PostgreSQL persistence tests through `AbstractPostgresTest`.
- Do not change crawl coverage or worker-pool behavior.

---

### Task 1: Expire Unverifiable Link Notices Quietly

**Files:**
- Modify: [`src/main/java/dev/hendrikhoemberg/webtesthelper/findings/ReportSection.java` — add an internal `EXPIRED` classification.]
- Modify: [`src/main/java/dev/hendrikhoemberg/webtesthelper/findings/FindingStore.java` — classify resolved unverifiable/technical-failure link notices as `EXPIRED` and omit that bucket from returned diffs.]
- Test: [`src/test/java/dev/hendrikhoemberg/webtesthelper/findings/FindingServiceDiffTest.java` — verify expiry is not reported as `FIXED`, while ordinary dead links remain `FIXED`.]

**Interfaces:**
- Consumes: existing `FindingService.record(...)` lifecycle.
- Produces: `RunDiff` values that never expose resolved `finding.DEAD_LINK.unverifiable` or `finding.DEAD_LINK.technicalFailure` entries as `FIXED`.

- [x] **Step 1: Write the failing test**
  Add a `FindingServiceDiffTest` case that records an INFO finding with message key `finding.DEAD_LINK.unverifiable`, records an empty fully covered next run, and asserts:
  ```java
  assertThat(run2.count(ReportSection.FIXED)).isZero();
  assertThat(noSectionContains(run2, fingerprint)).isTrue();
  assertThat(observedStatus(fingerprint)).isEqualTo(ObservedStatus.RESOLVED);
  ```
  Add the same assertion for `finding.DEAD_LINK.technicalFailure`, and retain an assertion that an ordinary `finding.DEAD_LINK.dead` still appears as `FIXED`.
- [x] **Step 2: Run the single test — verify it FAILS**
  Run `./mvnw test -Pfast -Dtest=FindingServiceDiffTest`; expected failure: the unverifiable finding is currently counted under `FIXED`.
- [x] **Step 3: Write minimal implementation**
  Add `EXPIRED` to `ReportSection`. In `FindingStore`'s SQL section `CASE`, place this branch before `FIXED`:
  ```sql
  WHEN f.observed_status = 'RESOLVED'
       AND f.resolved_at_run = ?
       AND f.message_key IN ('finding.DEAD_LINK.unverifiable',
                             'finding.DEAD_LINK.technicalFailure')
  THEN 'EXPIRED'
  ```
  Update the parameter bindings for the additional run-id placeholder. After `diffOf` groups rows, remove `ReportSection.EXPIRED` from the returned map. This keeps the database lifecycle record and detail URL while excluding expiry from UI sections, baseline counts, digest counts, and resolved counters.
- [x] **Step 4: Run the single test — verify it PASSES**
  Run `./mvnw test -Pfast -Dtest=FindingServiceDiffTest`; expected result: expiry tests pass and existing fixed-link tests remain green.

### Task 2: Persist Report Section Snapshots

**Files:**
- Create: [`src/main/resources/db/migration/V27__run_finding_section.sql` — store the section assigned to each finding for a run.]
- Modify: [`src/main/java/dev/hendrikhoemberg/webtesthelper/findings/FindingStore.java` — write snapshots inside the finding transaction and read them when available.]
- Modify: [`src/main/java/dev/hendrikhoemberg/webtesthelper/findings/FindingService.java` — snapshot the post-triage diff and expose a report-oriented read method.]
- Test: [`src/test/java/dev/hendrikhoemberg/webtesthelper/findings/RunDiffSnapshotTest.java` — verify snapshot preference, fixed classifications, and legacy fallback.]

**Interfaces:**
- Consumes: `FindingStore.diffOf(long siteId, long runId)` and the existing transactional `FindingService.record(...)` method.
- Produces: `FindingStore.snapshotDiff(long siteId, long runId)`, `FindingStore.snapshotOf(long siteId, long runId)`, and `FindingService.diffForReport(long siteId, long runId)`.

- [x] **Step 1: Write the failing test**
  Create `RunDiffSnapshotTest extends AbstractPostgresTest`, annotate it `@Transactional`, create a site in `@BeforeEach`, and cover these cases:
  ```java
  service.record(1, siteId, threeFindings(), fullCoverage(allPages), observedAt);
  service.record(2, siteId, twoFindings(), fullCoverage(allPages), observedAt);

  assertThat(service.diffOf(siteId, 1).count(ReportSection.NEW)).isEqualTo(1);
  assertThat(service.diffForReport(siteId, 1).count(ReportSection.NEW)).isEqualTo(3);
  ```
  The first assertion documents the current live-diff mutation; the second is the required frozen result. Also assert that a run resolving findings has the expected `FIXED` entries in its snapshot, and that deleting the snapshot row makes `diffForReport` fall back to `diffOf`.
- [x] **Step 2: Run the single test — verify it FAILS**
  Run `./mvnw test -Pfast -Dtest=RunDiffSnapshotTest`; expected failure: the new service method does not exist and no snapshot table is available.
- [x] **Step 3: Write minimal implementation**
  Add migration `V27__run_finding_section.sql`:
  ```sql
  CREATE TABLE run_finding_section (
      run_id BIGINT NOT NULL REFERENCES run (id) ON DELETE CASCADE,
      finding_id BIGINT NOT NULL REFERENCES finding (id) ON DELETE CASCADE,
      section TEXT NOT NULL,
      PRIMARY KEY (run_id, finding_id)
  );
  ```
  In `FindingStore`, add methods that delete and repopulate a run's rows using the same SQL section `CASE` and coverage of the current `diffOf` query, and a snapshot read query ordered exactly like the live diff. Return an empty/absent snapshot when no rows exist so legacy runs can fall back. Keep `EXPIRED` rows out of the returned `RunDiff` just as `diffOf` does.
  In `FindingService.record(...)`, call `store.snapshotDiff(siteId, runId)` after `resolveOutsideRun` and `muteRuleApplier.applyToRun(...)`, then return the resulting diff. Add:
  ```java
  public RunDiff diffForReport(long siteId, long runId) {
      return store.snapshotOf(siteId, runId).orElseGet(() -> store.diffOf(siteId, runId));
  }
  ```
  Use an `Optional<RunDiff>` (or an equivalent absent-value contract) so an empty snapshot for a genuinely empty run is distinguishable from a legacy run with no snapshot rows. Do not backfill old runs because their original section state cannot be reconstructed after later runs mutated lifecycle pointers.
- [x] **Step 4: Run the single test — verify it PASSES**
  Run `./mvnw test -Pfast -Dtest=RunDiffSnapshotTest`; expected result: completed runs retain their original section membership, resolved findings appear in the resolving run's snapshot, and legacy rows use the live fallback.

### Task 3: Use Frozen Reports in the Run View

**Files:**
- Modify: [`src/main/java/dev/hendrikhoemberg/webtesthelper/web/RunController.java` — use `diffForReport` for terminal run pages.]
- Modify: [`src/test/java/dev/hendrikhoemberg/webtesthelper/web/RunControllerTest.java` — stub the report-specific service method and verify it is used.]
- Modify: [`src/test/java/dev/hendrikhoemberg/webtesthelper/web/RunReportAcceptanceTest.java` — reset the snapshot table and cover quiet expiry/history preservation through MockMvc.]

**Interfaces:**
- Consumes: `FindingService.diffForReport(long siteId, long runId)`.
- Produces: `/laeufe/{id}` pages that preserve the section membership assigned when the run completed.

- [x] **Step 1: Write the failing test**
  Update the controller test setup to stub `findingService.diffForReport(siteId, runId)` and add verification that a completed run calls `diffForReport` and does not call `diffOf`. Extend the acceptance flow so a later run does not remove the earlier run's original NEW entries, and an unverifiable INFO finding that disappears does not produce a `Behoben` heading.
- [x] **Step 2: Run the single test — verify it FAILS**
  Run `./mvnw test -Pfast -Dtest=RunControllerTest,RunReportAcceptanceTest`; expected failure: the controller still calls `diffOf`, and the acceptance test still observes live report mutation.
- [x] **Step 3: Write minimal implementation**
  Replace the terminal branch in `RunController.detail(...)` with `findingService.diffForReport(run.siteId(), id)`. Replace all run-detail stubs in `RunControllerTest` accordingly. Add `DELETE FROM run_finding_section` to `RunReportAcceptanceTest.resetTables()` before deleting findings/runs; existing foreign-key cascades remain enabled.
- [x] **Step 4: Run the single test — verify it PASSES**
  Run `./mvnw test -Pfast -Dtest=RunControllerTest,RunReportAcceptanceTest`; expected result: controller/view tests render the frozen section map and the acceptance flow confirms both behaviors.

### Task 4: Full Verification

**Files:**
- Modify: any files required only if targeted tests expose integration regressions.

- [x] **Step 1: Run the fast suite**
  Run `./mvnw test -Pfast`; expected result: all non-browser tests pass.
- [x] **Step 2: Run the complete project verification**
  Run `./mvnw test`; expected result: the full suite, including browser-tagged acceptance tests, passes.
- [x] **Step 3: Review the final diff**
  Inspect `git status`, `git diff`, and the migration/test changes. Confirm no generated files, runtime data, screenshots, or secrets were modified.

## Self-Review

- **Spec coverage:** unverifiable resolution classification, section-count suppression, transactional snapshot creation, legacy fallback, controller integration, and full verification each have an explicit task.
- **Historical limitation:** existing runs are not backfilled because their original section membership cannot be recovered from the current mutable lifecycle columns; new completed runs are frozen from deployment onward.
- **No UI copy change:** `EXPIRED` is an internal bucket removed before view-model construction, so no new rendered identifier or message key is exposed.
