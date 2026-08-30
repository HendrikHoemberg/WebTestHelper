# Soft-404 Two-Anchor Detection Fix — Implementation Plan

**Goal:** Stop the soft-404 check from flagging real pages on sites that answer unknown paths with their shared shell (57 false findings on theis-feinwerktechnik.de), without losing detection on normal sites.

**Architecture:** The soft-404 check currently compares every page against one probe fingerprint (`{base}/{uuid}`) with a fixed 16-bit SimHash cutoff. On shell-heavy sites the probe page *is* the site shell, so every real page with little content of its own lands within the cutoff. Fix: capture a second anchor — the site's root page (known-real, `resolve(baseUrl, "/")`) — at crawl start and require `d(page, probe) < d(page, root)` in addition to the absolute cutoff. When the site answers everything with the same shell, `d(page, probe) == d(page, root)` for all pages, so the check degrades to silence (fail-safe) instead of 57 false positives. When root differs profoundly from the not-found page (normal sites), behavior is the previous one.

**Tech Stack:** Spring Boot, Playwright Chromium, JUnit 5, FixtureSite loopback server, Postgres + Flyway

**Spec:** `PageStatusCheck.java` javadoc (spec 7.1) — rewritten in Task 3.

**Measured geometry (project SimHash, real Chromium captures):**

| Site | d(probe, root) | d(probe, realPage) | result with fix |
|---|---|---|---|
| theis (shell site) | 0 (identical text) | 12 (brochure), 16 (karriere) | no finding |
| fixture (normal site) | 36 | 0 (clone), 27+ (real) | clone flagged, real not |

## Global Constraints

- German-only UI; no new message keys needed (reuses `finding.PAGE_STATUS.soft404`)
- Verify command: `./mvnw test -Pfast` (fast loop); full: `./mvnw test`
- Single test: `./mvnw test -Dtest=PageStatusCheckTest`
- Existing tests must survive unchanged where semantics are unchanged; the two unit tests that use the 3-arg probe constructor keep working via a legacy 3-arg constructor
- Worker pool sizes 0/2/4 untouched

---

### Task 1: `SoftNotFoundProbe` carries a root reference

**Files:**
- Modify: [`src/main/java/dev/hendrikhoemberg/webtesthelper/model/SoftNotFoundProbe.java`](src/main/java/dev/hendrikhoemberg/webtesthelper/model/SoftNotFoundProbe.java)

**Interfaces:**
- Consumes: nothing
- Produces: `SoftNotFoundProbe(httpStatus, simhash, textLength, referenceStatus, referenceSimhash, referenceTextLength)`; legacy 3-arg constructor → reference `0/0L/0` (`NONE`); `referenceUsable()`

- [ ] **Step 5 (verification after change):** the record stays a value carrier; no logic in it.

**Implementation:**

```java
package dev.hendrikhoemberg.webtesthelper.model;

/**
 * What the {base}/{uuid} probe learned about the site's not-found page (spec 7.1), plus the
 * fingerprint of the site's root page as a known-real anchor.
 *
 * <p>The probe page is a candidate "this is the site's not-found page" measurement; the root
 * reference is the counter-measurement. A real root page proves how distinguishable the
 * not-found fingerprint is from a real page of the same site. When the site answers every
 * path (including "/") with the same shell, both fingerprints are identical and the check
 * must stay silent rather than flagging every shell-like page.
 */
public record SoftNotFoundProbe(int httpStatus, long simhash, int textLength,
                                int referenceStatus, long referenceSimhash, int referenceTextLength) {

    public static final SoftNotFoundProbe NONE = new SoftNotFoundProbe(0, 0L, 0);

    /** Legacy view: a probe without a reference (unit tests, pre-reference runs). */
    public SoftNotFoundProbe(int httpStatus, long simhash, int textLength) {
        this(httpStatus, simhash, textLength, 0, 0L, 0);
    }

    public boolean usable() {
        return httpStatus == 200 && textLength > 0;
    }

    public boolean referenceUsable() {
        return referenceStatus == 200 && referenceTextLength > 0;
    }
}
```

- [ ] **Step 6:** commit

  ```bash
  git commit -m "feat: SoftNotFoundProbe carries a root-page reference"
  ```

---

### Task 2: Capture the root reference in `CrawlService`

**Files:**
- Modify: [`src/main/java/dev/hendrikhoemberg/webtesthelper/crawler/CrawlService.java`](src/main/java/dev/hendrikhoemberg/webtesthelper/crawler/CrawlService.java) — `probe(...)` also navigates `resolve(baseUrl, "/")` and packages both fingerprints

**Interfaces:**
- Consumes: `UrlNormalizer.resolve`, `PageNavigator.capture`, `deleteProbeScreenshot`
- Produces: `SoftNotFoundProbe` with a usable reference for runs whose root page answered 200 with text

- [ ] **Step 1: Write the failing test**

  In [`src/test/java/dev/hendrikhoemberg/webtesthelper/crawler/CrawlServiceFullCrawlTest.java`](src/test/java/dev/hendrikhoemberg/webtesthelper/crawler/CrawlServiceFullCrawlTest.java), extend `theSoftNotFoundProbeLearnsTheSitesNotFoundPage` (after line 124):

  ```java
  // The run's root page is the known-real anchor for the two-anchor soft-404 rule.
  assertThat(probe.referenceUsable()).isTrue();
  PageSnapshot home = result.snapshots().snapshots().stream()
          .filter(s -> s.url().value().equals(site.baseUrl()))
          .findFirst().orElseThrow();
  assertThat(probe.referenceSimhash()).isEqualTo(home.textSimhash());
  ```

  (`site` = the `FixtureSite` field; `someSnapshots()` returns `result.snapshots()` — keep the existing helper names, this test class exposes `result` and `site` as fields.)

- [ ] **Step 2: Run the single test — verify it FAILS**

  ```bash
  ./mvnw test -Dtest=CrawlServiceFullCrawlTest#theSoftNotFoundProbeLearnsTheSitesNotFoundPage
  ```

  Expected: FAIL — `probe.referenceSimhash()` is 0L (no reference captured yet), or `referenceUsable()` is false.

- [ ] **Step 3: Write minimal implementation**

  In `CrawlService.probe(...)`:

  ```java
  private SoftNotFoundProbe probe(CrawlRequest request, Path runArtifacts) {
      NormalizedUrl probeUrl = UrlNormalizer
              .resolve(request.site().baseUrl().value(), "/" + UUID.randomUUID()).orElseThrow();
      PageSnapshot snapshot = pool.submit(browser -> navigator.capture(browser,
              new CrawlTarget(-1L, probeUrl.value(), 0), request.site(), runArtifacts));
      deleteProbeScreenshot(runArtifacts, probeUrl);
      if (!snapshot.reachable() || snapshot.httpStatus() != 200
              || snapshot.textContent().isBlank()) {
          return new SoftNotFoundProbe(snapshot.httpStatus(), snapshot.textSimhash(),
                  snapshot.textContent().length());
      }
      NormalizedUrl rootUrl = UrlNormalizer
              .resolve(request.site().baseUrl().value(), "/").orElseThrow();
      PageSnapshot root = pool.submit(browser -> navigator.capture(browser,
              new CrawlTarget(-1L, rootUrl.value(), 0), request.site(), runArtifacts));
      deleteProbeScreenshot(runArtifacts, rootUrl);
      return new SoftNotFoundProbe(snapshot.httpStatus(), snapshot.textSimhash(),
              snapshot.textContent().length(),
              root.reachable() ? root.httpStatus() : 0,
              root.reachable() ? root.textSimhash() : 0L,
              root.reachable() ? root.textContent().length() : 0);
  }
  ```

  The old `deleteProbeScreenshot` now runs for both probe and root, so the root's own artifact later created by the crawl (or never, in PULSE runs) cannot double-count. Update the javadoc of `probe(...)` (currently mentions "Deviation D11") to describe the second capture.

- [ ] **Step 4: Run the single test — verify it PASSES**

  ```bash
  ./mvnw test -Dtest=CrawlServiceFullCrawlTest#theSoftNotFoundProbeLearnsTheSitesNotFoundPage
  ```

- [ ] **Step 5: commit**

  ```bash
  git commit -m "feat(crawler): capture root page as known-real soft-404 reference"
  ```

---

### Task 3: Two-anchor rule in `PageStatusCheck`

**Files:**
- Modify: [`src/main/java/dev/hendrikhoemberg/webtesthelper/checks/PageStatusCheck.java`](src/main/java/dev/hendrikhoemberg/webtesthelper/checks/PageStatusCheck.java) — `looksLikeNotFound(...)`

**Interfaces:**
- Consumes: `SoftNotFoundProbe.referenceUsable()`, `referenceSimhash()`
- Produces: pages are flagged only when within cutoff **and** closer to the probe than to the root

- [ ] **Step 1: Write the failing tests**

  Add to [`src/test/java/dev/hendrikhoemberg/webtesthelper/checks/PageStatusCheckTest.java`](src/test/java/dev/hendrikhoemberg/webtesthelper/checks/PageStatusCheckTest.java):

  ```java
  private static final String SHELL = """
          PERSÖNLICH. ZUVERLÄSSIG. MESSBAR GUT.
          VERMESSUNG
          FORMENBAU
          SONDERFERTIGUNG
          Impressum
          Datenschutz
          AGB
          Lieferantenkodex
          Lieferanteninformation
          © 2025 THEIS Feinwerktechnik · Alle Rechte vorbehalten.
          """;

  private static final String BROCHURE = SHELL + """
          Home
          News
          Unternehmen
          Karriere
          Kontakt
          VERMESSUNG
          PRODUKTE
          SERVICE
          DOWNLOAD
          HÄNDLER
          ANFRAGELISTE
          Theis VISION Agriculture Produktbroschüre
          Größe: 1982.25 KB
          Letzte Aktualisierung: 07.01.2020
          Download
          Broschüren
          Anleitungen
          Sonstiges
          """;

  @Test
  void aRealPageOnASiteWhoseShellAnswersEveryPathIsNotBlamedAsASoftNotFound() {
      // Measured on http://www.theis-feinwerktechnik.de (2026-08-30): the site answers the
      // probe URL and the root URL with the IDENTICAL 930-char shell. Real pages sit 12–16
      // bits from that shell — inside the 16-bit cutoff — so the old probe-only rule
      // reported 57 real pages as soft 404s.
      SoftNotFoundProbe probe = new SoftNotFoundProbe(200,
              SimHash.of(SHELL), SHELL.length(),
              200, SimHash.of(SHELL), SHELL.length());
      List<CheckFinding> findings = check.evaluate(
              Snapshots.page("https://example.com/broschuere").text(BROCHURE).build(),
              Snapshots.config(check, Snapshots.facts(probe)));

      assertThat(findings).isEmpty();
  }

  @Test
  void aGenuineNotFoundCloneIsStillReportedAgainstARootAnchor() {
      // Normal site (fixture geometry, measured): probe=not-found body, root=index;
      // d(probe, root) = 36, clone at d(probe)=0 — clearly closer to the probe.
      SoftNotFoundProbe probe = new SoftNotFoundProbe(200,
              SimHash.of(NOT_FOUND_TEXT), NOT_FOUND_TEXT.length(),
              200, SimHash.of(ROOT_TEXT), ROOT_TEXT.length());
      List<CheckFinding> findings = check.evaluate(
              Snapshots.page("https://example.com/verirrt").text(NOT_FOUND_TEXT).build(),
              Snapshots.config(check, Snapshots.facts(probe)));

      assertThat(findings).singleElement().satisfies(finding ->
              assertThat(finding.messageKey()).isEqualTo("finding.PAGE_STATUS.soft404"));
  }
  ```

  With:

  ```java
  private static final String ROOT_TEXT =
          "Startseite Leistungen Kontakt Medien Gemischte Inhalte Karte (grau) Karte (gesund) "
          + "Karte (spät) Karte (Ebenen) English Seite die es nicht mehr gibt Harte 404 "
          + "Weiterleitungskette Weiterleitungsschleife Handbuch (PDF) Preisliste (angeblich PDF) "
          + "Externer Partner Zeitweise gestörter Partner Externer toter Link Interner Bereich "
          + "Gesperrter Bereich Faule Bilder Langsames Bild Kontakt (Mantel) HEAD-Lügner Mehr erfahren";
  ```

- [ ] **Step 2: Run the single test — verify both FAIL**

  ```bash
  ./mvnw test -Dtest=PageStatusCheckTest
  ```

  Expected: `aRealPageOnASiteWhoseShellAnswersEveryPathIsNotBlamedAsASoftNotFound` FAILS today (d=12 ≤ 16 → finding). `aGenuineNotFoundCloneIsStillReportedAgainstARootAnchor` passes even today — its value is the regression guard.

- [ ] **Step 3: Write minimal implementation**

  In `looksLikeNotFound(...)`:

  ```java
  if (snapshot.httpStatus() != 200 || !probe.usable() || snapshot.textContent().isBlank()) {
      return false;
  }
  int distance = SimHash.hammingDistance(snapshot.textSimhash(), probe.simhash());
  if (distance > maxDistance) {
      return false;
  }
  // Two-anchor rule: a page may resemble the not-found shell and still be real (shell-heavy
  // sites). It is a soft 404 only if it is ALSO closer to the not-found fingerprint than to
  // the site's own root page. When the site answers every path — even "/" — with the same
  // shell (measured: d(probe, root) = 0), the distances are equal for every page and the
  // check stays silent: a check that eats real pages is worse than no check at all (spec 8).
  if (probe.referenceUsable()) {
      int rootDistance = SimHash.hammingDistance(
              snapshot.textSimhash(), probe.referenceSimhash());
      if (rootDistance <= distance) {
          return false;
      }
  }
  return true;
  ```

  Update the class javadoc: replace the "Note that the echo ceiling …" paragraph with the two-anchor narrative and the measured table (theis d(probe,root)=0, brochure 12, karriere 16; fixture gap 36, clones 0, real pages 27+).

- [ ] **Step 4: Run the single test — verify it PASSES**

  ```bash
  ./mvnw test -Dtest=PageStatusCheckTest
  ```

  All 10 tests green.

- [ ] **Step 5: Run the whole check package**

  ```bash
  ./mvnw test -Dtest='PageStatusCheckTest,SitemapConsistencyCheckTest,PageCheckAcceptanceTest'
  ```

  Expected: green. `aSitemapEntryThatIsASoftNotFoundReportsNothing` uses the legacy 3-arg probe → reference not usable → old rule → still green.

- [ ] **Step 6: commit**

  ```bash
  git commit -m "fix(checks): two-anchor soft-404 detection with root reference"
  ```

---

### Task 4: Persist the reference alongside the probe

**Files:**
- Create: [`src/main/resources/db/migration/V26__soft404_reference.sql`](src/main/resources/db/migration/V26__soft404_reference.sql)
- Modify: [`src/main/java/dev/hendrikhoemberg/webtesthelper/runner/persistence/RunResultJdbcRepository.java`](src/main/java/dev/hendrikhoemberg/webtesthelper/runner/persistence/RunResultJdbcRepository.java)
- Modify: [`src/main/java/dev/hendrikhoemberg/webtesthelper/runner/persistence/RunEntity.java`](src/main/java/dev/hendrikhoemberg/webtesthelper/runner/persistence/RunEntity.java)
- Test: [`src/test/java/dev/hendrikhoemberg/webtesthelper/runner/CrawlRunExecutorTest.java`](src/test/java/dev/hendrikhoemberg/webtesthelper/runner/CrawlRunExecutorTest.java) — extend `aManualRunCrawlsTheFixtureSiteEndToEnd` after line 136

**Interfaces:**
- Consumes: `SoftNotFoundProbe` record fields
- Produces: `run` exposes `soft404_reference_status/simhash/text_length`

- [ ] **Step 1: Write the migration**

  ```sql
  -- The known-real anchor for the two-anchor soft-404 rule (spec 7.1): the site's root page.
  ALTER TABLE run ADD COLUMN soft404_reference_status INTEGER;
  ALTER TABLE run ADD COLUMN soft404_reference_simhash BIGINT;
  ALTER TABLE run ADD COLUMN soft404_reference_text_length INTEGER;
  ```

  Filename: `V26__soft404_reference.sql`.

- [ ] **Step 2: Write the failing test**

  In `CrawlRunExecutorTest.aManualRunCrawlsTheFixtureSiteEndToEnd` after the `soft404_simhash` assertion:

  ```java
  assertThat(jdbc.queryForObject("SELECT soft404_reference_simhash FROM run WHERE id = ?",
          Long.class, runId2)).isNotZero();
  ```

- [ ] **Step 3: Run the single test — verify it FAILS**

  ```bash
  ./mvnw test -Dtest=CrawlRunExecutorTest#aManualRunCrawlsTheFixtureSiteEndToEnd
  ```

  Expected: FAIL — column does not exist (schema is migrated, value NULL).

- [ ] **Step 4: Write minimal implementation**

  `run` write SQL — in `RunResultJdbcRepository.OUTCOME_SQL`, after `soft404_text_length`:

  ```sql
  soft404_reference_status           = ?,
  soft404_reference_simhash          = ?,
  soft404_reference_text_length      = ?
  ```

  And in `saveCrawlOutcome(...)` (the 8-argument full form), after `probe.textLength()`:

  ```java
  probe.referenceStatus(),
  probe.referenceSimhash(),
  probe.referenceTextLength(),
  ```

  `RunEntity`: add the three fields (`Long referenceStatus?` — no, mirror existing style):

  ```java
  private Integer soft404ReferenceStatus;
  private Long soft404ReferenceSimhash;
  private Integer soft404ReferenceTextLength;
  ```

- [ ] **Step 5: Run the single test — verify it PASSES**

  ```bash
  ./mvnw test -Dtest=CrawlRunExecutorTest
  ```

- [ ] **Step 6: commit**

  ```bash
  git commit -m "feat: persist soft-404 root reference on the run row"
  ```

---

### Task 5: Full verification

- [ ] **Step 1: Fast suite**

  ```bash
  ./mvnw test -Pfast
  ```

  Expected: green.

- [ ] **Step 2: Full suite incl. browser acceptance**

  ```bash
  ./mvnw test
  ```

  Expected: green (~95 s). `PageCheckAcceptanceTest` fixture: probe=not-found body, root=index.html; gap measured 36 — clones still flagged, real pages not.

- [ ] **Step 3: Commit (if any reactor leftovers)**

  ```bash
  git log --oneline -8
  ```

---

## Self-Review

### Spec Coverage

| Requirement | Task |
|---|---|
| Capture a known-real anchor (root page) at crawl start | 2 |
| Flag only when within absolute cutoff | 3 |
| …and clearly closer to the probe than to the root | 3 |
| Degrade to silence when site answers everything with its shell | 3 (measured d(probe,root)=0) |
| Keep legacy probe-only behavior for reference-less probes | 1 (3-arg constructor) |
| Reference persisted for post-run inspection | 4 |
| Acceptance-level proof | 2 + 4 (browser crawl), 3 (unit) |

### Placeholder Scan

No TBDs/TODOs. Every step contains the actual code.

### Type Consistency

- `SoftNotFoundProbe` canonical 6-arg constructor matches the 3 columns; `referenceUsable()` uses `referenceStatus == 200 && referenceTextLength > 0`
- `RunResultJdbcRepository.saveCrawlOutcome` passes the three record accessors in the same order as the SQL placeholders
- `CrawlService.probe` guards the root capture with `root.reachable()` and stores `0/0L/0` otherwise — the `referenceUsable()` contract
- `PageStatusCheck.looksLikeNotFound` uses strict `rootDistance <= distance` → not a soft 404; equality case (shell site) stays silent
