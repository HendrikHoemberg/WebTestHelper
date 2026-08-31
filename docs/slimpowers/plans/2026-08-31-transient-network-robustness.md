# Transient Network Robustness Implementation Plan

**Goal:** Stop transient transport failures (network changes, timeouts, resets) from producing false dead-link findings, via one navigation retry, UNVERIFIABLE classification of transport errors, and a proper German explanation for `ERR_NETWORK_CHANGED`.

**Architecture:** A tiny string classifier (`model.TransientFailure`) decides what a transient transport failure is. It feeds three places: the crawl (retry a navigation once when its failure is transient), the verification model (`UrlVerification.ofSnapshot` and `UrlVerifier` classify transport failures as UNVERIFIABLE instead of DEAD), and the hreflang check (a transiently unreachable alternate is not dead). `DeadLinkCheck` gets a dedicated message key for transport failures, and `TechnicalText` learns to map `ERR_NETWORK_CHANGED`.

**Tech Stack:** Spring Boot, Playwright Chromium, JUnit 5, FixtureSite loopback server, Postgres + Flyway

**Spec:** Spec 8 (noise avoidance) — measured incident: run 10 on theis-feinwerktechnik.de (2026-08-30), 4 navigations failed with `net::ERR_NETWORK_CHANGED` inside a 14-second window (18:17:29–18:17:43) while pages crawled before and after loaded fine; no retry (`attempts=1`) and DEAD classification amplified the 4 failed pages into 16 findings (4 PAGE_UNREACHABLE + 10 DEAD_LINK + 2 HREFLANG, 263 occurrences).

## Global Constraints

- German-only UI; message keys `ui.*` / `finding.*`; no internal identifiers in rendered HTML
- Verify command: `./mvnw test -Pfast` (fast loop); full: `./mvnw test`
- Single test: `./mvnw test -Dtest=<TestClass>`
- Worker pool sizes 0/2/4 untouched; no new configuration properties — retry uses constants
- Existing tests change only where semantics intentionally change

---

### Task 1: `TransientFailure` classifier

**Files:**
- Create: [`src/main/java/dev/hendrikhoemberg/webtesthelper/model/TransientFailure.java`](src/main/java/dev/hendrikhoemberg/webtesthelper/model/TransientFailure.java)
- Test: [`src/test/java/dev/hendrikhoemberg/webtesthelper/model/TransientFailureTest.java`](src/test/java/dev/hendrikhoemberg/webtesthelper/model/TransientFailureTest.java)

**Interfaces:**
- Consumes: nothing
- Produces: `TransientFailure.isTransient(String reason)` — true only for known transport failures

- [ ] **Step 1: Write the failing test**

  ```java
  package dev.hendrikhoemberg.webtesthelper.model;

  import org.junit.jupiter.api.Test;

  import static org.assertj.core.api.Assertions.assertThat;

  class TransientFailureTest {

      @Test
      void transportErrorsAreTransient() {
          for (String reason : new String[]{
                  "net::ERR_NETWORK_CHANGED at https://kunde.de/x",
                  "net::ERR_CONNECTION_TIMED_OUT",
                  "net::ERR_CONNECTION_REFUSED",
                  "net::ERR_CONNECTION_RESET",
                  "net::ERR_ABORTED",
                  "net::ERR_NAME_NOT_RESOLVED",
                  "net::ERR_ADDRESS_UNREACHABLE",
                  "net::ERR_HTTP2_PROTOCOL_ERROR",
                  "net::ERR_SSL_PROTOCOL_ERROR",
                  "SSLHandshakeException: handshake failure",
                  "Timeout 5000ms exceeded",
                  "net::ERR_TIMED_OUT"}) {
              assertThat(TransientFailure.isTransient(reason))
                      .as("'%s' must be transient", reason).isTrue();
          }
      }

      @Test
      void pageLevelFailuresAndBlanksAreNotTransient() {
          for (String reason : new String[]{
                  "net::ERR_TOO_MANY_REDIRECTS",
                  "net::ERR_BLOCKED_BY_RESPONSE",
                  "net::ERR_CERT_DATE_INVALID",
                  "Nicht als URL interpretierbar",
                  "",
                  null}) {
              assertThat(TransientFailure.isTransient(reason))
                      .as("'%s' must not be transient", reason).isFalse();
          }
      }
  }
  ```

- [ ] **Step 2: Run the single test — verify it FAILS**

  ```bash
  ./mvnw test -Dtest=TransientFailureTest
  ```

  Expected: FAIL — the class does not exist.

- [ ] **Step 3: Write minimal implementation**

  ```java
  package dev.hendrikhoemberg.webtesthelper.model;

  import java.util.List;

  /**
   * Decides whether a navigation failure is the kind that comes and goes — a network change,
   * a DNS hiccup, a reset, a timeout — rather than a property of the page (a redirect loop, a
   * blocking response). Transient failures are worth one retry and never warrant a dead verdict
   * (spec 8): a check that reports a healthy site as broken is worse than no check at all.
   */
  public final class TransientFailure {

      private static final List<String> TRANSIENT_MARKERS = List.of(
              "ERR_NETWORK_CHANGED",
              "ERR_CONNECTION_TIMED_OUT",
              "ERR_TIMED_OUT",
              "ERR_CONNECTION_REFUSED",
              "ERR_CONNECTION_RESET",
              "ERR_ABORTED",
              "ERR_NAME_NOT_RESOLVED",
              "ERR_ADDRESS_UNREACHABLE",
              "ERR_HTTP2_PROTOCOL_ERROR",
              "ERR_SSL_PROTOCOL_ERROR",
              "SSLHandshakeException",
              "Timeout");

      private TransientFailure() {
      }

      public static boolean isTransient(String reason) {
          if (reason == null || reason.isBlank()) {
              return false;
          }
          return TRANSIENT_MARKERS.stream().anyMatch(reason::contains);
      }
  }
  ```

- [ ] **Step 4: Run the single test — verify it PASSES**

  ```bash
  ./mvnw test -Dtest=TransientFailureTest
  ```

- [ ] **Step 5: commit**

  ```bash
  git commit -m "feat(model): TransientFailure classifier for transport errors"
  ```

---

### Task 2: `ERR_NETWORK_CHANGED` in `TechnicalText`

**Files:**
- Modify: [`src/main/java/dev/hendrikhoemberg/webtesthelper/reporting/TechnicalText.java`](src/main/java/dev/hendrikhoemberg/webtesthelper/reporting/TechnicalText.java)
- Modify: [`src/main/resources/messages.properties`](src/main/resources/messages.properties)
- Test: [`src/test/java/dev/hendrikhoemberg/webtesthelper/reporting/TechnicalTextTest.java`](src/test/java/dev/hendrikhoemberg/webtesthelper/reporting/TechnicalTextTest.java)

**Interfaces:**
- Consumes: nothing
- Produces: `humanise("...ERR_NETWORK_CHANGED...")` returns the new German sentence instead of the generic one

- [ ] **Step 1: Write the failing test**

  In `TechnicalTextTest`, add to the `chromiumErrors` list in `chromiumNetworkErrorsHumaniseToGermanSentenceWithoutNetOrErr`:

  ```java
  "net::ERR_NETWORK_CHANGED",
  ```

  Add to the `keys` list in `everyTechnicalMessageKeyResolvesInGermanBundle`:

  ```java
  "ui.technisch.network_changed",
  ```

  Add the new test:

  ```java
  @Test
  void aNetworkChangeHumanisesToItsOwnSentenceNotTheGenericOne() {
      String raw = "Error {\n  message='net::ERR_NETWORK_CHANGED at https://kunde.de/x";
      String humanised = TechnicalText.humanise(raw, messageSource, Locale.GERMAN);
      String expected = messageSource.getMessage("ui.technisch.network_changed", null, Locale.GERMAN);

      assertThat(humanised).isEqualTo(expected);
      assertThat(humanised).doesNotContain("net::").doesNotContain("ERR_NETWORK_CHANGED");
  }
  ```

- [ ] **Step 2: Run the single test — verify it FAILS**

  ```bash
  ./mvnw test -Dtest=TechnicalTextTest
  ```

  Expected: FAIL — the key `ui.technisch.network_changed` does not exist in the bundle.

- [ ] **Step 3: Write minimal implementation**

  In `messages.properties`, after `ui.technisch.aborted` (line 417):

  ```
  ui.technisch.network_changed=Die Netzwerkverbindung hat sich während der Prüfung geändert (z. B. VPN oder WLAN).
  ```

  In `TechnicalText.humanise`, before the cert-date branch:

  ```java
  } else if (raw.contains("ERR_NETWORK_CHANGED")) {
      key = "ui.technisch.network_changed";
  ```

- [ ] **Step 4: Run the single test — verify it PASSES**

  ```bash
  ./mvnw test -Dtest=TechnicalTextTest
  ```

- [ ] **Step 5: commit**

  ```bash
  git commit -m "feat(reporting): map ERR_NETWORK_CHANGED to its own German sentence"
  ```

---

### Task 3: `UrlVerification.ofSnapshot` — transient unreachable is UNVERIFIABLE

**Files:**
- Modify: [`src/main/java/dev/hendrikhoemberg/webtesthelper/model/UrlVerification.java`](src/main/java/dev/hendrikhoemberg/webtesthelper/model/UrlVerification.java)
- Test: Create [`src/test/java/dev/hendrikhoemberg/webtesthelper/model/UrlVerificationTest.java`](src/test/java/dev/hendrikhoemberg/webtesthelper/model/UrlVerificationTest.java)

**Interfaces:**
- Consumes: `TransientFailure.isTransient` (Task 1)
- Produces: an unreachable snapshot whose reason is transient yields status UNVERIFIABLE (failure text preserved); everything else behaves as before

- [ ] **Step 1: Write the failing test**

  ```java
  package dev.hendrikhoemberg.webtesthelper.model;

  import dev.hendrikhoemberg.webtesthelper.support.Snapshots;
  import org.junit.jupiter.api.Test;

  import static org.assertj.core.api.Assertions.assertThat;

  class UrlVerificationTest {

      @Test
      void anUnreachableSnapshotWithATransientReasonIsUnverifiableNotDead() {
          PageSnapshot snapshot = Snapshots.page("https://example.com/x")
                  .unreachable("net::ERR_NETWORK_CHANGED at https://example.com/x");

          UrlVerification verification = UrlVerification.ofSnapshot(snapshot);

          assertThat(verification.status()).isEqualTo(UrlStatus.UNVERIFIABLE);
          assertThat(verification.httpStatus()).isZero();
          assertThat(verification.failureText()).contains("ERR_NETWORK_CHANGED");
      }

      @Test
      void anUnreachableSnapshotWithAPageLevelReasonIsStillDead() {
          PageSnapshot snapshot = Snapshots.page("https://example.com/x")
                  .unreachable("net::ERR_TOO_MANY_REDIRECTS at https://example.com/x");

          UrlVerification verification = UrlVerification.ofSnapshot(snapshot);

          assertThat(verification.status()).isEqualTo(UrlStatus.DEAD);
          assertThat(verification.failureText()).contains("ERR_TOO_MANY_REDIRECTS");
      }

      @Test
      void aReachableSnapshotCarriesItsHttpStatus() {
          PageSnapshot snapshot = Snapshots.page("https://example.com/x").status(404).build();

          UrlVerification verification = UrlVerification.ofSnapshot(snapshot);

          assertThat(verification.status()).isEqualTo(UrlStatus.DEAD);
          assertThat(verification.httpStatus()).isEqualTo(404);
      }
  }
  ```

- [ ] **Step 2: Run the single test — verify it FAILS**

  ```bash
  ./mvnw test -Dtest=UrlVerificationTest
  ```

  Expected: FAIL — the transient snapshot currently yields DEAD.

- [ ] **Step 3: Write minimal implementation**

  In `ofSnapshot`, replace the unreachable branch:

  ```java
  public static UrlVerification ofSnapshot(PageSnapshot snapshot) {
      if (!snapshot.reachable()) {
          UrlStatus status = TransientFailure.isTransient(snapshot.unreachableReason())
                  ? UrlStatus.UNVERIFIABLE : UrlStatus.DEAD;
          return new UrlVerification(snapshot.url().value(), status, 0, null, 0,
                  null, snapshot.unreachableReason(), Instant.now());
      }
      ...
  ```

- [ ] **Step 4: Run the single test — verify it PASSES**

  ```bash
  ./mvnw test -Dtest=UrlVerificationTest
  ```

- [ ] **Step 5: commit**

  ```bash
  git commit -m "fix(model): transiently unreachable snapshots are unverifiable, not dead"
  ```

---

### Task 4: `UrlVerifier` — transport exceptions are UNVERIFIABLE

**Files:**
- Modify: [`src/main/java/dev/hendrikhoemberg/webtesthelper/crawler/UrlVerifier.java`](src/main/java/dev/hendrikhoemberg/webtesthelper/crawler/UrlVerifier.java)
- Test: [`src/test/java/dev/hendrikhoemberg/webtesthelper/crawler/UrlVerifierTest.java`](src/test/java/dev/hendrikhoemberg/webtesthelper/crawler/UrlVerifierTest.java) — semantics change intentionally

**Interfaces:**
- Consumes: nothing new
- Produces: an `IOException` (connect refused, timeout, DNS, TLS) yields UNVERIFIABLE with the failure text; anything else stays DEAD

- [ ] **Step 1: Write the failing test**

  Replace `aTransportFailureIsDEADWithAReasonAndNoStatus`:

  ```java
  @Test
  void aTransportFailureIsUnverifiableWithAReasonAndNoStatus() {
      // A refused connection says nothing about the page (spec 8): the host may be firewalled
      // for probes while serving real visitors. UNVERIFIABLE keeps the finding at INFO level.
      UrlVerification failed = verifier.verify(url("http://127.0.0.1:9/tot"), AGENT, false);
      assertThat(failed.status()).isEqualTo(UrlStatus.UNVERIFIABLE);
      assertThat(failed.httpStatus()).isZero();
      assertThat(failed.failureText()).isNotBlank();
  }
  ```

- [ ] **Step 2: Run the single test — verify it FAILS**

  ```bash
  ./mvnw test -Dtest=UrlVerifierTest
  ```

  Expected: FAIL — the status is DEAD today.

- [ ] **Step 3: Write minimal implementation**

  In `verify(...)`, the two catch branches become:

  ```java
  } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return unverifiable(url.value(), "Verbindung unterbrochen", checkedAt);
  } catch (Exception e) {
      String text = truncate(e.toString(), FAILURE_TEXT_LIMIT);
      if (e instanceof IOException) {
          return unverifiable(url.value(), text, checkedAt);
      }
      return dead(url.value(), text, 0, checkedAt);
  }
  ```

  In `verifyAll`, the interrupt branch becomes:

  ```java
  results.put(url.value(),
          unverifiable(url.value(), "Verbindung unterbrochen", Instant.now()));
  ```

  And next to `dead(...)`:

  ```java
  /** A transport failure never completed an exchange; the page's state is unknown. */
  private static UrlVerification unverifiable(String url, String failureText, Instant checkedAt) {
      return new UrlVerification(url, UrlStatus.UNVERIFIABLE, 0, null, 0, null, failureText,
              checkedAt);
  }
  ```

- [ ] **Step 4: Run the single test — verify it PASSES**

  ```bash
  ./mvnw test -Dtest=UrlVerifierTest
  ```

  Expected: PASS. `aDeadLinkThatStaysDead...`-style verifier tests that use `/tot` move to `FindingReverifierTest` handling in Task 8.

- [ ] **Step 5: commit**

  ```bash
  git commit -m "fix(crawler): transport failures are unverifiable, not dead"
  ```

---

### Task 5: `DeadLinkCheck` — dedicated message for transport failures

**Files:**
- Modify: [`src/main/java/dev/hendrikhoemberg/webtesthelper/checks/DeadLinkCheck.java`](src/main/java/dev/hendrikhoemberg/webtesthelper/checks/DeadLinkCheck.java)
- Modify: [`src/main/resources/messages.properties`](src/main/resources/messages.properties)
- Test: [`src/test/java/dev/hendrikhoemberg/webtesthelper/checks/DeadLinkCheckTest.java`](src/test/java/dev/hendrikhoemberg/webtesthelper/checks/DeadLinkCheckTest.java)

**Interfaces:**
- Consumes: `UrlVerification.httpStatus()` / `failureText()` (Task 3, 4)
- Produces: a UNVERIFIABLE verification without an HTTP status (a transport failure) reports `finding.DEAD_LINK.technicalFailure` at INFO; a blocking wall (403/429) keeps `finding.DEAD_LINK.unverifiable`

- [ ] **Step 1: Write the failing test**

  In `DeadLinkCheckTest`:

  ```java
  @Test
  void aTransportFailureProducesTechnicalFailureAtInfo() {
      UrlVerification transport = new UrlVerification("https://example.com/fremd",
              UrlStatus.UNVERIFIABLE, 0, null, 0, null,
              "net::ERR_NETWORK_CHANGED at https://example.com/fremd", Instant.EPOCH);

      CheckFinding finding = check.evaluate(
              Snapshots.page("https://example.com/seite")
                      .link("https://example.com/fremd", false).build(),
              Snapshots.config(check, Snapshots.facts(transport))).getFirst();

      assertThat(finding.messageKey()).isEqualTo("finding.DEAD_LINK.technicalFailure");
      assertThat(finding.severity())
              .isEqualTo(dev.hendrikhoemberg.webtesthelper.model.Severity.INFO);
      assertThat(finding.messageArgs()).containsExactly("https://example.com/fremd",
              "net::ERR_NETWORK_CHANGED at https://example.com/fremd");
  }
  ```

- [ ] **Step 2: Run the single test — verify it FAILS**

  ```bash
  ./mvnw test -Dtest=DeadLinkCheckTest
  ```

  Expected: FAIL — the check reports `finding.DEAD_LINK.unverifiable` today (and the new key does not exist).

- [ ] **Step 3: Write minimal implementation**

  In `messages.properties`, after `finding.DEAD_LINK.unverifiable` (line 55):

  ```
  finding.DEAD_LINK.technicalFailure=Der Verweis auf {0} konnte wegen einer technischen Störung nicht geprüft werden ({1}).
  ```

  In `DeadLinkCheck`:

  ```java
  static final String DEAD = "finding.DEAD_LINK.dead";
  static final String UNVERIFIABLE = "finding.DEAD_LINK.unverifiable";
  static final String TECHNICAL_FAILURE = "finding.DEAD_LINK.technicalFailure";
  ```

  `messageKeys()` becomes `Set.of(DEAD, UNVERIFIABLE, TECHNICAL_FAILURE)`.

  The UNVERIFIABLE branch becomes:

  ```java
  } else if (verification.status() == UrlStatus.UNVERIFIABLE) {
      String detail = verification.failureText() == null ? "" : verification.failureText();
      boolean transport = verification.httpStatus() == 0 && !detail.isBlank();
      findings.add(new CheckFinding(type(), Severity.INFO, target.value(),
              snapshot.url(), transport ? TECHNICAL_FAILURE : UNVERIFIABLE,
              List.of(target.value(), detail),
              Evidence.ofVerification(snapshot.screenshotPath(), verification)));
  }
  ```

- [ ] **Step 4: Run the single test — verify it PASSES**

  ```bash
  ./mvnw test -Dtest='DeadLinkCheckTest,CheckDocumentationTest'
  ```

  Expected: PASS (`CheckDocumentationTest` resolves the new key with placeholder args).

- [ ] **Step 5: commit**

  ```bash
  git commit -m "feat(checks): transport-failed links get their own INFO message"
  ```

---

### Task 6: `HreflangCheck` — transiently unreachable alternates are not dead

**Files:**
- Modify: [`src/main/java/dev/hendrikhoemberg/webtesthelper/checks/HreflangCheck.java`](src/main/java/dev/hendrikhoemberg/webtesthelper/checks/HreflangCheck.java)
- Test: [`src/test/java/dev/hendrikhoemberg/webtesthelper/checks/HreflangCheckTest.java`](src/test/java/dev/hendrikhoemberg/webtesthelper/checks/HreflangCheckTest.java)

**Interfaces:**
- Consumes: `TransientFailure.isTransient` (Task 1)
- Produces: a crawled alternate that is unreachable with a transient reason is not reported dead; reachable-with-status or page-level failures behave as before

- [ ] **Step 1: Write the failing tests**

  In `HreflangCheckTest`:

  ```java
  @Test
  void anAlternateWhoseTargetFailedTransientlyIsNotReportedDead() {
      PageSnapshot page = Snapshots.page("https://example.com/a")
              .alternate("en", "https://example.com/b").build();
      PageSnapshot target = Snapshots.page("https://example.com/b")
              .unreachable("net::ERR_NETWORK_CHANGED at https://example.com/b");

      assertThat(check.evaluate(snapshots(page, target), site(), config())).isEmpty();
  }

  @Test
  void anAlternateWhoseTargetFailedForAPageReasonIsReportedDead() {
      PageSnapshot page = Snapshots.page("https://example.com/a")
              .alternate("en", "https://example.com/b").build();
      PageSnapshot target = Snapshots.page("https://example.com/b")
              .unreachable("net::ERR_TOO_MANY_REDIRECTS at https://example.com/b");

      assertThat(check.evaluate(snapshots(page, target), site(), config()))
              .singleElement()
              .satisfies(finding -> assertThat(finding.messageKey())
                      .isEqualTo("finding.HREFLANG.deadAlternate"));
  }
  ```

- [ ] **Step 2: Run the single test — verify it FAILS**

  ```bash
  ./mvnw test -Dtest=HreflangCheckTest
  ```

  Expected: FAIL — `anAlternateWhoseTargetFailedTransientlyIsNotReportedDead` gets a `deadAlternate` finding today.

- [ ] **Step 3: Write minimal implementation**

  In `checkAlternate`, replace the dead computation:

  ```java
  boolean dead = targetSnap.map(HreflangCheck::isDeadAlternate).orElse(false);
  ```

  With the helper:

  ```java
  /** A transient transport failure is not death — the page may be fine (spec 8). */
  private static boolean isDeadAlternate(PageSnapshot snapshot) {
      if (snapshot.reachable()) {
          return snapshot.httpStatus() >= 400;
      }
      return !TransientFailure.isTransient(snapshot.unreachableReason());
  }
  ```

  Add `import dev.hendrikhoemberg.webtesthelper.model.TransientFailure;`.

- [ ] **Step 4: Run the single test — verify it PASSES**

  ```bash
  ./mvnw test -Dtest=HreflangCheckTest
  ```

- [ ] **Step 5: commit**

  ```bash
  git commit -m "fix(checks): transiently unreachable hreflang alternates are not dead"
  ```

---

### Task 7: One retry for transient navigation failures in `CrawlService`

**Files:**
- Modify: [`src/main/java/dev/hendrikhoemberg/webtesthelper/crawler/CrawlService.java`](src/main/java/dev/hendrikhoemberg/webtesthelper/crawler/CrawlService.java)
- Modify: [`src/test/java/dev/hendrikhoemberg/webtesthelper/support/FixtureSite.java`](src/test/java/dev/hendrikhoemberg/webtesthelper/support/FixtureSite.java) — new `/wackeliges-netz.html`
- Test: [`src/test/java/dev/hendrikhoemberg/webtesthelper/crawler/CrawlServiceScopeAndBudgetTest.java`](src/test/java/dev/hendrikhoemberg/webtesthelper/crawler/CrawlServiceScopeAndBudgetTest.java)

**Interfaces:**
- Consumes: `TransientFailure.isTransient` (Task 1)
- Produces: a navigation that failed with a transient reason is retried once (2 attempts total, 2 s apart); a definitive failure or a page that survives both attempts keeps the old FAILED outcome

- [ ] **Step 1: Write the failing test**

  In `FixtureSite.dispatch`, before the `default` case:

  ```java
  case "/wackeliges-netz.html" -> {
      // The network-flap fixture: request 1 outlasts the test profile's 5s navigation
      // timeout (a transient transport failure), every later request serves the page.
      // Without the retry the crawl marks this page unreachable.
      int seen = requestCounts.get(path).get();
      if (seen <= 1) {
          sleep(8000);
      }
      sendHtml(exchange, 200, """
              <!doctype html><html lang="de"><head><meta charset="utf-8">
              <title>Wackeliges Netz</title></head>
              <body><h1>Doch noch da</h1></body></html>
              """);
  }
  ```

  In `CrawlServiceScopeAndBudgetTest`:

  ```java
  @Test
  void aTransientNavigationFailureIsRetriedAndRecovered() {
      // /wackeliges-netz.html answers request 1 past the 5s navigation timeout (transient
      // transport failure) and request 2 normally. One retry must turn the page reachable —
      // an unreachable snapshot would otherwise seed dead-link findings across the site.
      CrawlResult result = crawl(RunScope.PULSE, budget(50, 3, Duration.ofSeconds(60)),
              List.of("/wackeliges-netz.html"));

      assertThat(result.pagesFailed()).isZero();
      assertThat(result.pagesVisited()).isEqualTo(1);
      assertThat(result.coveredUrls()).singleElement()
              .satisfies(url -> assertThat(url).endsWith("/wackeliges-netz.html"));
      assertThat(result.snapshots().snapshots()).singleElement()
              .satisfies(s -> assertThat(s.reachable()).isTrue());
      assertThat(site.requestCount("/wackeliges-netz.html")).isEqualTo(2);
  }
  ```

- [ ] **Step 2: Run the single test — verify it FAILS**

  ```bash
  ./mvnw test -Dtest=CrawlServiceScopeAndBudgetTest#aTransientNavigationFailureIsRetriedAndRecovered
  ```

  Expected: FAIL — without retry, `pagesFailed()` is 1 and the request count is 1.

- [ ] **Step 3: Write minimal implementation**

  In `CrawlService`, next to the existing constants:

  ```java
  /** A transient transport failure gets one retry; two attempts must be enough to survive a flap. */
  private static final int NAVIGATION_ATTEMPTS = 2;
  private static final Duration RETRY_DELAY = Duration.ofSeconds(2);
  ```

  In `visit(...)`, replace the capture:

  ```java
  PageSnapshot snapshot = capture(request, target, runArtifacts);
  ```

  With the helper:

  ```java
  /**
   * One page capture, retried once when the failure is a transient transport error (a network
   * change, a timeout, a reset). Measured on theis-feinwerktechnik.de (run 10, 2026-08-30):
   * four navigations failed with {@code ERR_NETWORK_CHANGED} inside a 14-second network flap
   * and never got a second chance; the four unreachable snapshots then seeded dead-link
   * findings across every page that linked them. Definitive failures — redirect loops,
   * blocked responses, unparseable URLs — fail immediately, and only the final snapshot is
   * reported.
   */
  private PageSnapshot capture(CrawlRequest request, CrawlTarget target, Path runArtifacts) {
      PageSnapshot snapshot = pool.submit(browser ->
              navigator.capture(browser, target, request.site(), runArtifacts));
      for (int attempt = 1;
           !snapshot.reachable()
                   && TransientFailure.isTransient(snapshot.unreachableReason())
                   && attempt < NAVIGATION_ATTEMPTS;
           attempt++) {
          sleep(RETRY_DELAY);
          snapshot = pool.submit(browser ->
                  navigator.capture(browser, target, request.site(), runArtifacts));
      }
      return snapshot;
  }

  private static void sleep(Duration delay) {
      try {
          Thread.sleep(delay.toMillis());
      } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
      }
  }
  ```

  Add `import dev.hendrikhoemberg.webtesthelper.model.TransientFailure;`.

- [ ] **Step 4: Run the single test — verify it PASSES**

  ```bash
  ./mvnw test -Dtest=CrawlServiceScopeAndBudgetTest#aTransientNavigationFailureIsRetriedAndRecovered
  ```

  Expected: PASS — 2 fixture requests, 1 visited page, 0 failed.

- [ ] **Step 5: commit**

  ```bash
  git commit -m "feat(crawler): retry navigations that fail with a transient transport error"
  ```

---

### Task 8: Update the tests whose semantics changed intentionally

**Files:**
- Modify: [`src/test/java/dev/hendrikhoemberg/webtesthelper/checks/PageCheckAcceptanceTest.java`](src/test/java/dev/hendrikhoemberg/webtesthelper/checks/PageCheckAcceptanceTest.java)
- Modify: [`src/test/java/dev/hendrikhoemberg/webtesthelper/crawler/FindingReverifierTest.java`](src/test/java/dev/hendrikhoemberg/webtesthelper/crawler/FindingReverifierTest.java)

**Interfaces:**
- Consumes: Tasks 4, 5
- Produces: the acceptance suite still proves the tombstone link is reported (now at INFO, `technicalFailure`) and the reverifier still proves a healing subject is dropped while a hard-dead subject survives

- [ ] **Step 1: Update the tests**

  In `PageCheckAcceptanceTest`, replace `deadLinkReportsTheExternalTombstoneButNotTheSoft404`:

  ```java
  @Test
  void deadLinkReportsTheExternalTombstoneAsTechnicalFailureButNotTheSoft404() {
      // http://localhost:9/tot refuses every connection. That is a transport failure, not
      // proof of death: the finding must be the INFO "technical failure", never "führt ins
      // Leere" — and the soft-404 page must stay silent either way.
      assertThat(of(CheckType.DEAD_LINK))
              .filteredOn(finding -> finding.messageKey().endsWith(".technicalFailure"))
              .filteredOn(finding -> finding.subjectKey().equals("http://localhost:9/tot"))
              .singleElement()
              .satisfies(finding -> {
                  assertThat(finding.observedOn().path()).isEqualTo("/");
                  assertThat(finding.severity()).isEqualTo(Severity.INFO);
              });

      assertThat(of(CheckType.DEAD_LINK))
              .filteredOn(finding -> finding.messageKey().endsWith(".dead"))
              .extracting(CheckFinding::subjectKey)
              .contains(base() + "hart-404")
              .doesNotContain(base() + "verirrt.html");
  }
  ```

  In `FindingReverifierTest`, replace the two uses of `"http://localhost:9/tot"` with a subject that stays DEAD via HTTP status — the fixture's external 404:

  - Line 152 (`aSubjectThatHealedIsNotProbedAgainByTheRemainingAttempts`): `String dead = site.externalBase() + "hart-404";` (comment: a 404 stays DEAD; a refused connection is UNVERIFIABLE and would never enter the suspect set)
  - Line 193 (`aDeadLinkThatStaysDeadSurvivesAndTheCacheRowIsRefreshed`): `String subject = site.externalBase() + "hart-404";`

- [ ] **Step 2: Run the two tests — verify they PASS**

  ```bash
  ./mvnw test -Dtest='PageCheckAcceptanceTest,FindingReverifierTest'
  ```

  Expected: PASS. Before this change they FAIL: the tombstone asserts `.dead` and the reverifier's `/tot` first pass is UNVERIFIABLE (not a suspect) since Task 4.

- [ ] **Step 3: commit**

  ```bash
  git commit -m "test: reflect transport-failure semantics in acceptance and reverifier tests"
  ```

---

### Task 9: Full verification

- [ ] **Step 1: Fast suite**

  ```bash
  ./mvnw test -Pfast
  ```

  Expected: green.

- [ ] **Step 2: Full suite incl. browser acceptance**

  ```bash
  ./mvnw test
  ```

  Expected: green (~95 s). Browser-verified: the retry recovers `/wackeliges-netz.html`, the tombstone reports at INFO, `/langsam` still fails after two attempts (existing tests untouched).

- [ ] **Step 3: Check git log**

  ```bash
  git log --oneline -12
  ```

---

## Self-Review

### Spec Coverage

| Requirement | Task |
|---|---|
| Retry navigations that failed with a transport error, not on definitive failures | 7 (bounded 2 attempts, `TransientFailure` gate) |
| Classify transport failures as UNVERIFIABLE instead of DEAD | 3 (snapshot seeding), 4 (HttpClient path), 6 (hreflang direct snapshot path) |
| Map `ERR_NETWORK_CHANGED` to a proper German explanation | 2 |
| Don't tell users "the foreign site refuses us" on our own network flap | 5 (dedicated INFO message) |
| Regression guards at unit and browser level | 1–8 |

### Placeholder Scan

No TBDs/TODOs. Every step contains the actual code.

### Type Consistency

- `TransientFailure.isTransient(null)` → false; `""` → false — the Hreflang/retry paths never NPE
- `DeadLinkCheck` transport test: `UNVERIFIABLE` + `httpStatus 0` + non-blank `failureText` → `TECHNICAL_FAILURE`; the 403 case (`httpStatus 403`) still routes to `UNVERIFIABLE`
- `UrlVerifier.unverifiable(...)` mirrors `dead(...)`: same record shape, status swapped
- `CrawlService.capture` returns only the final snapshot; the retry loop never re-adds snapshots
