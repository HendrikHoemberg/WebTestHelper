# False-Positive Findings Fix — Implementation Plan

**Goal:** Eliminate three classes of false-positive findings (lazy/slow images, HEAD-lying servers, email-cloak wrappers) without masking genuine defects.

**Architecture:** Three independent fixes, each at a distinct choke-point: extract.js (image probing + link filtering), UrlVerifier (HTTP fallback), and their corresponding Java model/check classes. Changes propagate from extraction → model → check, tested bottom-up.

**Tech Stack:** Spring Boot, Playwright Chromium, JUnit 5, FixtureSite loopback server

**Spec:** [Review artifact](file:///home/hendrik/.gemini/antigravity-cli/brain/4306505b-ddf3-46c8-bada-e590fb2f6976/review.md)

## Global Constraints

- Package: `dev.hendrikhoemberg.webtesthelper` — not the simplified `de.hhoemberg` from the proposal
- German-only UI copy; message keys `ui.*`; no raw identifiers in rendered HTML
- Verify command: `./mvnw test -Pfast` (fast loop, skips browser); full: `./mvnw test`
- Single test: `./mvnw test -Dtest=ClassName`
- All existing tests must survive unchanged (except for signature changes in `ImageRef`)

---

### Task 1: `ImageState` Enum + `ImageRef` Record Extension

**Files:**
- Create: [`src/main/java/dev/hendrikhoemberg/webtesthelper/model/ImageState.java`](file:///home/hendrik/Documents/Coding/WebTestHelper/src/main/java/dev/hendrikhoemberg/webtesthelper/model/ImageState.java)
- Modify: [`src/main/java/dev/hendrikhoemberg/webtesthelper/model/ImageRef.java`](file:///home/hendrik/Documents/Coding/WebTestHelper/src/main/java/dev/hendrikhoemberg/webtesthelper/model/ImageRef.java) — add `ImageState state` field
- Modify: [`src/test/java/dev/hendrikhoemberg/webtesthelper/support/Snapshots.java`](file:///home/hendrik/Documents/Coding/WebTestHelper/src/test/java/dev/hendrikhoemberg/webtesthelper/support/Snapshots.java) — update `image()` builder methods
- Test: [`src/test/java/dev/hendrikhoemberg/webtesthelper/checks/ImageBrokenCheckTest.java`](file:///home/hendrik/Documents/Coding/WebTestHelper/src/test/java/dev/hendrikhoemberg/webtesthelper/checks/ImageBrokenCheckTest.java) — add UNKNOWN-skip test

**Interfaces:**
- Produces: `ImageState` enum (`DECODED`, `BROKEN`, `UNKNOWN`); `ImageRef` with 7-field constructor; updated `Snapshots.Builder.image()` overloads

- [ ] **Step 1: Write the failing test**

  Add to `ImageBrokenCheckTest`:

  ```java
  @Test
  void anUnknownImageIsNotReportedBecauseItMayBeHealthy() {
      // A lazy image or a slow CDN that timed out before the probe completed is not
      // confirmed broken — reporting it would be a false positive (same philosophy as
      // UNVERIFIABLE ≠ DEAD for links).
      assertThat(check.evaluate(
              Snapshots.page("https://example.com/")
                      .image("https://example.com/langsam.png", 0, 0, ImageOrigin.IMG,
                              ImageState.UNKNOWN)
                      .build(),
              Snapshots.config(check, Snapshots.facts()))).isEmpty();
  }
  ```

  Also add the dual — a BROKEN image IS reported:

  ```java
  @Test
  void aBrokenImageWithExplicitStateIsStillReported() {
      assertThat(check.evaluate(
              Snapshots.page("https://example.com/")
                      .image("https://example.com/kaputt.png", 0, 0, ImageOrigin.IMG,
                              ImageState.BROKEN)
                      .build(),
              Snapshots.config(check, Snapshots.facts())))
              .singleElement()
              .satisfies(f -> assertThat(f.subjectKey())
                      .isEqualTo("https://example.com/kaputt.png"));
  }
  ```

- [ ] **Step 2: Run the single test — verify it FAILS**

  ```bash
  ./mvnw test -Dtest=ImageBrokenCheckTest
  ```

  Expected: compilation failure — `ImageState` does not exist, `image()` overload missing.

- [ ] **Step 3: Write minimal implementation**

  **`ImageState.java`** (new file):

  ```java
  package dev.hendrikhoemberg.webtesthelper.model;

  /**
   * The decode outcome of an image as determined by the extraction probe.
   *
   * <p>{@code DECODED} means the browser loaded and decoded the image to non-zero dimensions.
   * {@code BROKEN} means the load failed ({@code onerror}). {@code UNKNOWN} means the probe
   * timed out before a definitive answer — the image may be healthy but slow, or lazy-loaded
   * and never triggered. Reporting UNKNOWN as broken would be a false positive.
   */
  public enum ImageState {
      DECODED,
      BROKEN,
      UNKNOWN;

      /**
       * Parse the state string from extract.js. Falls back to {@code UNKNOWN} for any
       * unrecognised value, so a future script change cannot crash the Java side.
       */
      public static ImageState parse(String raw) {
          if (raw == null) return UNKNOWN;
          return switch (raw) {
              case "decoded" -> DECODED;
              case "broken" -> BROKEN;
              default -> UNKNOWN;
          };
      }
  }
  ```

  **`ImageRef.java`** — add `state` field:

  ```java
  package dev.hendrikhoemberg.webtesthelper.model;

  /** An image as found in the page. */
  public record ImageRef(String rawSource, NormalizedUrl target, String alt,
                         int naturalWidth, int naturalHeight, ImageOrigin origin,
                         ImageState state) {

      /** Backwards-compatible constructor: state defaults to dimension-based inference. */
      public ImageRef(String rawSource, NormalizedUrl target, String alt,
                      int naturalWidth, int naturalHeight, ImageOrigin origin) {
          this(rawSource, target, alt, naturalWidth, naturalHeight, origin,
                  (naturalWidth > 0 && naturalHeight > 0) ? ImageState.DECODED : ImageState.BROKEN);
      }

      /** Status 200 is not enough (spec 7.1) — a broken image still returns bytes sometimes. */
      public boolean rendered() {
          return naturalWidth > 0 && naturalHeight > 0;
      }
  }
  ```

  **`ImageBrokenCheck.java`** — skip UNKNOWN images. In `evaluate()`, modify the loop guard (around line 56). Change:

  ```java
  if (image.rendered() || !reported.add(subject)) {
      continue;
  }
  ```

  To:

  ```java
  if (image.state() == ImageState.UNKNOWN || image.rendered() || !reported.add(subject)) {
      continue;
  }
  ```

  **`Snapshots.Builder`** — add overload with `ImageState`:

  ```java
  public Builder image(String src, int naturalWidth, int naturalHeight, ImageOrigin origin,
                       ImageState state) {
      images.add(new ImageRef(src, Snapshots.url(src), "Alt-Text", naturalWidth,
              naturalHeight, origin, state));
      return this;
  }
  ```

  Existing `image()` overloads keep working via the backwards-compatible `ImageRef` constructor (6-arg → infers state from dimensions).

- [ ] **Step 4: Run the single test — verify it PASSES**

  ```bash
  ./mvnw test -Dtest=ImageBrokenCheckTest
  ```

  Expected: all tests pass, including the existing `naturalWidthWithoutNaturalHeightIsBroken` (uses 6-arg constructor → state inferred as BROKEN → still reported).

- [ ] **Step 5: Commit**

  ```bash
  git commit -m "feat: add ImageState tri-state to distinguish broken from unknown images"
  ```

---

### Task 2: Tri-State Probing in `extract.js`

**Files:**
- Modify: [`src/main/resources/crawler/extract.js`](file:///home/hendrik/Documents/Coding/WebTestHelper/src/main/resources/crawler/extract.js) — `measure()` returns `{ state, w, h }`; IMG entries with `naturalWidth == 0` enter the probe batch
- Modify: [`src/main/java/dev/hendrikhoemberg/webtesthelper/crawler/PageNavigator.java`](file:///home/hendrik/Documents/Coding/WebTestHelper/src/main/java/dev/hendrikhoemberg/webtesthelper/crawler/PageNavigator.java) — `map()` reads `state` field
- Create: `src/test/resources/fixture-site/faul.html` — lazy-image fixture page
- Create: `src/test/resources/fixture-site/langsam-bild.html` — slow-image fixture page
- Modify: [`src/test/java/dev/hendrikhoemberg/webtesthelper/support/FixtureSite.java`](file:///home/hendrik/Documents/Coding/WebTestHelper/src/test/java/dev/hendrikhoemberg/webtesthelper/support/FixtureSite.java) — add slow endpoint
- Test: [`src/test/java/dev/hendrikhoemberg/webtesthelper/crawler/PageNavigatorTest.java`](file:///home/hendrik/Documents/Coding/WebTestHelper/src/test/java/dev/hendrikhoemberg/webtesthelper/crawler/PageNavigatorTest.java) — add lazy-image and timeout-image tests (browser)

**Interfaces:**
- Consumes: `ImageState.parse(String)` (from Task 1)
- Produces: extract.js emits `{ state: 'decoded'|'broken'|'unknown', w, h }` per image; PageNavigator maps it to `ImageRef` with `ImageState`

- [ ] **Step 1: Write the failing test**

  Add to `PageNavigatorTest` (browser-tagged):

  ```java
  @Test
  void aLazyImageBelowTheFoldIsNotBroken() {
      PageSnapshot snapshot = capture("faul.html", 1);

      // The above-fold logo must be decoded.
      assertThat(snapshot.images())
              .filteredOn(image -> image.rawSource().equals("/assets/logo.png")
                      && image.origin() == ImageOrigin.IMG)
              .anySatisfy(image -> assertThat(image.state()).isEqualTo(ImageState.DECODED));

      // The lazy image below 2000px of spacer: never triggered by viewport.
      // The probe's new Image() loads the same URL (/assets/logo.png) independently
      // of lazy loading, so it should resolve as DECODED (local 1×1 PNG, instant).
      // The key assertion: NOT BROKEN.
      assertThat(snapshot.images())
              .filteredOn(image -> image.rawSource().equals("/assets/lazy-ok.png"))
              .allSatisfy(image ->
                      assertThat(image.state()).isNotEqualTo(ImageState.BROKEN));
  }

  @Test
  void aSlowImageThatTimesOutIsUnknownNotBroken() {
      PageSnapshot snapshot = capture("langsam-bild.html", 1);

      assertThat(snapshot.images())
              .filteredOn(image -> image.rawSource().equals("/assets/verspaetet.png"))
              .singleElement()
              .satisfies(image -> assertThat(image.state()).isEqualTo(ImageState.UNKNOWN));
  }
  ```

- [ ] **Step 2: Run the single test — verify it FAILS**

  ```bash
  ./mvnw test -Dtest=PageNavigatorTest#aLazyImageBelowTheFoldIsNotBroken
  ```

  Expected: fixture pages not found → assertion failure (no such image in snapshot), or `state()` method returns BROKEN instead of DECODED/UNKNOWN.

- [ ] **Step 3: Write minimal implementation**

  **Fixture pages** — create under `src/test/resources/fixture-site/`:

  `faul.html`:
  ```html
  <!doctype html>
  <html lang="de">
  <head><meta charset="utf-8"><title>Faul — Fixture</title></head>
  <body>
  <img src="/assets/logo.png" alt="Logo">
  <div style="height: 2000px">Platzhalter</div>
  <img src="/assets/lazy-ok.png" alt="Faules Bild" loading="lazy">
  </body>
  </html>
  ```

  `langsam-bild.html`:
  ```html
  <!doctype html>
  <html lang="de">
  <head><meta charset="utf-8"><title>Langsames Bild — Fixture</title></head>
  <body>
  <img src="/assets/verspaetet.png" alt="Langsames Bild">
  </body>
  </html>
  ```

  **FixtureSite.java** — add endpoints in `dispatch()`:

  ```java
  case "/assets/lazy-ok.png" -> send(exchange, 200, "image/png", PNG_1X1);
  case "/assets/verspaetet.png" -> {
      sleep(6000);  // longer than measure()'s 5s timeout
      send(exchange, 200, "image/png", PNG_1X1);
  }
  ```

  **extract.js** — three changes:

  **(a)** Modify `measure()` to return tri-state (replace lines 21–31):

  ```javascript
  const measure = (url) => {
    if (measured.has(url)) return measured.get(url);
    const pending = new Promise(resolve => {
      const probe = new Image();
      probe.onload = () => resolve({
        state: 'decoded', w: probe.naturalWidth, h: probe.naturalHeight
      });
      probe.onerror = () => resolve({ state: 'broken', w: 0, h: 0 });
      probe.src = url;
      setTimeout(() => resolve({ state: 'unknown', w: 0, h: 0 }), 5000);
    });
    measured.set(url, pending);
    return pending;
  };
  ```

  **(b)** After collecting IMG entries (after line 44), mark unresolved ones for probing. Insert before line 46:

  ```javascript
  // IMG entries the page already decoded get their state from dimensions.
  // Lazy or still-loading images (naturalWidth == 0) are re-probed via measure().
  for (const image of images) {
    if (image.origin === 'IMG') {
      if (image.w > 0 && image.h > 0) {
        image.state = 'decoded';
      } else {
        image.w = -1; image.h = -1;  // sentinel: enters the probe batch below
      }
    }
  }
  ```

  **(c)** Update the `Promise.all` batch (lines 57–60) to use tri-state result:

  ```javascript
  await Promise.all(images.filter(i => i.w < 0 && i.abs).map(async image => {
    const result = await measure(image.abs);
    image.w = result.w;
    image.h = result.h;
    image.state = result.state;
  }));
  // Guarantee every image carries a state.
  for (const image of images) {
    if (!image.state) image.state = 'unknown';
  }
  ```

  **PageNavigator.java** — update `map()` to read `state` field. In the images loop, replace the `images.add(new ImageRef(...))` call:

  ```java
  images.add(new ImageRef(asString(image.get("raw")), target,
          asString(image.get("alt")), intOf(image.get("w")), intOf(image.get("h")),
          ImageOrigin.valueOf(origin),
          ImageState.parse(asString(image.get("state")))));
  ```

  Add the import at the top of the file:

  ```java
  import dev.hendrikhoemberg.webtesthelper.model.ImageState;
  ```

- [ ] **Step 4: Run the single test — verify it PASSES**

  ```bash
  ./mvnw test -Dtest=PageNavigatorTest
  ```

  Expected: all tests pass, including `aBrokenImageIsRecognisedByNaturalWidthRatherThanByStatus` (the 404 image `onerror` → `state: 'broken'`).

- [ ] **Step 5: Commit**

  ```bash
  git commit -m "feat: extract.js tri-state probing for lazy and slow images"
  ```

---

### Task 3: UrlVerifier HEAD→GET Fallback

**Files:**
- Modify: [`src/main/java/dev/hendrikhoemberg/webtesthelper/crawler/UrlVerifier.java`](file:///home/hendrik/Documents/Coding/WebTestHelper/src/main/java/dev/hendrikhoemberg/webtesthelper/crawler/UrlVerifier.java) — widen HEAD fallback condition
- Modify: [`src/test/java/dev/hendrikhoemberg/webtesthelper/support/FixtureSite.java`](file:///home/hendrik/Documents/Coding/WebTestHelper/src/test/java/dev/hendrikhoemberg/webtesthelper/support/FixtureSite.java) — add `/extern/head-taeuscht` endpoint
- Test: [`src/test/java/dev/hendrikhoemberg/webtesthelper/crawler/UrlVerifierTest.java`](file:///home/hendrik/Documents/Coding/WebTestHelper/src/test/java/dev/hendrikhoemberg/webtesthelper/crawler/UrlVerifierTest.java) — add HEAD-faking test

**Interfaces:**
- Consumes: `UrlStatus.ofHttpStatus(int)` (unchanged)
- Produces: `verify()` now falls back to GET when HEAD returns a DEAD-classified status, not only 405/501

- [ ] **Step 1: Write the failing test**

  Add to `UrlVerifierTest`:

  ```java
  @Test
  void aHeadThatLiesWithA404IsHealedByTheGetFallback() {
      // Google support pages and some CDNs return HEAD 404 but GET 200. The verifier
      // must not trust a HEAD-only DEAD verdict — confirm with GET.
      UrlVerification result = verifier.verify(
              url(site.url("extern/head-taeuscht")), AGENT, false);
      assertThat(result.status()).isEqualTo(UrlStatus.OK);
      assertThat(result.httpStatus()).isEqualTo(200);
  }

  @Test
  void a403StaysTrustedFromHeadWithoutAnExtraGet() {
      // UNVERIFIABLE statuses (403, 429, etc.) should NOT trigger the GET fallback.
      verifier.verify(url(site.url("geblockt-403")), AGENT, false);
      assertThat(site.requestCount("/geblockt-403")).isEqualTo(1);
  }

  @Test
  void aGenuine404CostsExactlyTwoRequests() {
      // HEAD 404 → GET 404. Exactly two requests, not more.
      UrlVerification result = verifier.verify(
              url(site.url("hart-404")), AGENT, false);
      assertThat(result.status()).isEqualTo(UrlStatus.DEAD);
      assertThat(site.requestCount("/hart-404")).isEqualTo(2);
  }
  ```

- [ ] **Step 2: Run the single test — verify it FAILS**

  ```bash
  ./mvnw test -Dtest=UrlVerifierTest#aHeadThatLiesWithA404IsHealedByTheGetFallback
  ```

  Expected: FAIL — `/extern/head-taeuscht` not yet in FixtureSite, or HEAD 404 not falling back to GET → status is DEAD.

- [ ] **Step 3: Write minimal implementation**

  **FixtureSite.java** — add endpoint in `dispatch()`:

  ```java
  case "/extern/head-taeuscht" -> {
      if ("HEAD".equals(exchange.getRequestMethod())) {
          exchange.sendResponseHeaders(404, -1);
      } else {
          sendHtml(exchange, 200,
                  "<!doctype html><html lang=\"de\"><body><p>Inhalt da</p></body></html>");
      }
  }
  ```

  **UrlVerifier.java** — modify the fallback condition in `verify()`. Replace:

  ```java
  Exchange exchange = safeHead(url, userAgent);
  if (exchange == null || exchange.response().statusCode() == 405
          || exchange.response().statusCode() == 501) {
      return withBodyPrefix(url.value(), get(url, userAgent), checkedAt);
  }
  ```

  With:

  ```java
  Exchange exchange = safeHead(url, userAgent);
  if (exchange == null
          || exchange.response().statusCode() == 405
          || exchange.response().statusCode() == 501
          || UrlStatus.ofHttpStatus(exchange.response().statusCode()) == UrlStatus.DEAD) {
      return withBodyPrefix(url.value(), get(url, userAgent), checkedAt);
  }
  ```

  Add the import:

  ```java
  import dev.hendrikhoemberg.webtesthelper.model.UrlStatus;
  ```

- [ ] **Step 4: Run the single test — verify it PASSES**

  ```bash
  ./mvnw test -Dtest=UrlVerifierTest
  ```

  Expected: all tests pass. Existing semantics:
  - `a405HeadFallsBackToGet…` — 405 explicit → unchanged
  - `aBlockingWallIsUnverifiable` — 403 is UNVERIFIABLE → HEAD trusted, no GET
  - `anAliveLinkIsOKAndADeadOneIsDEAD` — `/hart-404` HEAD 404 → GET 404 → still DEAD

- [ ] **Step 5: Commit**

  ```bash
  git commit -m "fix: fall back to GET when HEAD returns a DEAD status code"
  ```

---

### Task 4: Cloak-Wrapper Filter in `extract.js`

**Files:**
- Modify: [`src/main/resources/crawler/extract.js`](file:///home/hendrik/Documents/Coding/WebTestHelper/src/main/resources/crawler/extract.js) — filter nested anchors
- Create: `src/test/resources/fixture-site/mantel.html` — cloak fixture page
- Test: [`src/test/java/dev/hendrikhoemberg/webtesthelper/crawler/PageNavigatorTest.java`](file:///home/hendrik/Documents/Coding/WebTestHelper/src/test/java/dev/hendrikhoemberg/webtesthelper/crawler/PageNavigatorTest.java) — add cloak test (browser)

**Interfaces:**
- Produces: extract.js no longer emits outer anchors wrapping an inner `<a href>`; only the innermost (user-visible) link is extracted

- [ ] **Step 1: Write the failing test**

  Add to `PageNavigatorTest`:

  ```java
  @Test
  void aCloakWrapperAnchorIsFilteredAndOnlyTheInnerLinkIsExtracted() {
      PageSnapshot snapshot = capture("mantel.html", 1);

      // The outer anchor href="/kontakt@example.com" must NOT appear.
      assertThat(snapshot.links())
              .extracting(link -> link.target().path())
              .doesNotContain("/kontakt@example.com");

      // A normal link on the same page must survive the filter.
      assertThat(snapshot.links())
              .extracting(link -> link.target().path())
              .contains("/leistungen.html");
  }
  ```

- [ ] **Step 2: Run the single test — verify it FAILS**

  ```bash
  ./mvnw test -Dtest=PageNavigatorTest#aCloakWrapperAnchorIsFilteredAndOnlyTheInnerLinkIsExtracted
  ```

  Expected: FAIL — fixture page missing or outer anchor extracted.

- [ ] **Step 3: Write minimal implementation**

  **Fixture page** `src/test/resources/fixture-site/mantel.html`:

  ```html
  <!doctype html>
  <html lang="de">
  <head><meta charset="utf-8"><title>Mantel — Fixture</title></head>
  <body>
  <h1>Kontakt</h1>
  <!-- CCM19-style email cloak: outer anchor wraps inner mailto anchor -->
  <a href="/kontakt@example.com" target="_blank">
    <span id="cloak123">
      <a href="mailto:kontakt@example.com">kontakt@example.com</a>
    </span>
  </a>
  <p><a href="/leistungen.html">Leistungen</a></p>
  </body>
  </html>
  ```

  **extract.js** — add `.filter()` at line 8. Change:

  ```javascript
  const links = [...document.querySelectorAll('a[href]')]
    .map(a => ({
  ```

  To:

  ```javascript
  const links = [...document.querySelectorAll('a[href]')]
    .filter(a => !a.querySelector('a[href]'))
    .map(a => ({
  ```

- [ ] **Step 4: Run the single test — verify it PASSES**

  ```bash
  ./mvnw test -Dtest=PageNavigatorTest
  ```

  Expected: all tests pass. Existing fixture pages have no nested anchors.

- [ ] **Step 5: Commit**

  ```bash
  git commit -m "fix: skip outer anchors wrapping inner a[href] (email cloak pattern)"
  ```

---

### Task 5: Acceptance Test Coverage

**Files:**
- Modify: `src/test/resources/fixture-site/index.html` — add links to new pages
- Modify: [`src/test/java/dev/hendrikhoemberg/webtesthelper/checks/PageCheckAcceptanceTest.java`](file:///home/hendrik/Documents/Coding/WebTestHelper/src/test/java/dev/hendrikhoemberg/webtesthelper/checks/PageCheckAcceptanceTest.java) — add acceptance tests

**Interfaces:**
- Consumes: all three fixes (Tasks 1–4)
- Produces: acceptance-level proof of no false positives

- [ ] **Step 1: Write the failing tests**

  Add to `PageCheckAcceptanceTest`:

  ```java
  @Test
  void aLazyImageBelowTheFoldIsNotReportedAsBroken() {
      // /faul.html has a loading="lazy" image below a 2000px spacer.
      // The image file exists — it must not be IMAGE_BROKEN.
      assertThat(of(CheckType.IMAGE_BROKEN))
              .filteredOn(f -> f.observedOn().path().equals("/faul.html"))
              .extracting(CheckFinding::subjectKey)
              // Only /assets/fehlt.png (genuinely broken) may appear, not the lazy one.
              .noneMatch(s -> s.endsWith("/assets/lazy-ok.png"));
  }

  @Test
  void aHeadLyingExternalLinkIsNotReportedAsDead() {
      // /extern/head-taeuscht: HEAD 404, GET 200. Must not be DEAD_LINK.
      assertThat(of(CheckType.DEAD_LINK))
              .extracting(CheckFinding::subjectKey)
              .noneMatch(s -> s.contains("extern/head-taeuscht"));
  }

  @Test
  void aCloakWrapperDoesNotProduceADeadLinkOrPageStatus() {
      // /mantel.html has a cloak wrapper. The outer anchor's resolved URL must not
      // appear as a DEAD_LINK or PAGE_STATUS finding.
      assertThat(of(CheckType.DEAD_LINK))
              .extracting(CheckFinding::subjectKey)
              .noneMatch(s -> s.contains("kontakt@example.com"));
      assertThat(of(CheckType.PAGE_STATUS))
              .filteredOn(f -> f.observedOn().path().contains("kontakt@example.com"))
              .isEmpty();
  }
  ```

- [ ] **Step 2: Run the tests — verify they FAIL**

  ```bash
  ./mvnw test -Dtest=PageCheckAcceptanceTest
  ```

  Expected: FAIL — new fixture pages not linked from start page → crawler doesn't visit them.

- [ ] **Step 3: Write minimal implementation**

  **`index.html`** — add links in the `<ul>` section:

  ```html
  <li><a href="/faul.html">Faule Bilder</a></li>
  <li><a href="/langsam-bild.html">Langsames Bild</a></li>
  <li><a href="/mantel.html">Kontakt (Mantel)</a></li>
  <li><a href="http://localhost:{{PORT}}/extern/head-taeuscht">HEAD-Lügner</a></li>
  ```

- [ ] **Step 4: Run the full acceptance tests — verify they PASS**

  ```bash
  ./mvnw test -Dtest=PageCheckAcceptanceTest
  ```

  Expected: all tests pass, including existing `theMissingFooterImageIsReportedOnEveryPageThatShowsIt`, `deadLinkReportsTheExternalTombstoneButNotTheSoft404`, etc.

- [ ] **Step 5: Commit**

  ```bash
  git commit -m "test: acceptance tests for lazy-image, HEAD-fallback, and cloak fixes"
  ```

---

### Task 6: Full Verification

**Files:** none (verification only)

- [ ] **Step 1: Run the fast test suite**

  ```bash
  ./mvnw test -Pfast
  ```

  Expected: all non-browser tests pass.

- [ ] **Step 2: Run the full test suite including browser tests**

  ```bash
  ./mvnw test
  ```

  Expected: all tests pass (~95s).

- [ ] **Step 3: Final commit**

  ```bash
  git log --oneline -5
  ```

  Review the commits. Squash or amend if needed.

---

## Self-Review

### Spec Coverage

| Requirement | Task |
|---|---|
| Lazy images below viewport → not reported | Task 2 (extract.js re-probe) + Task 1 (UNKNOWN filter) |
| Slow images (timeout) → not reported | Task 2 (measure → 'unknown') + Task 1 (UNKNOWN filter) |
| HEAD 404 / GET 200 → not DEAD | Task 3 (fallback widening) |
| HEAD 403 → still UNVERIFIABLE, no extra GET | Task 3 (refined condition) |
| Genuine 404 → still DEAD | Task 3 (GET confirms) |
| Email cloak wrappers → no false link | Task 4 (filter) |
| SVG without intrinsic size → still BROKEN | Task 1 (onload → 'decoded' w=0/h=0 → rendered()=false, state≠UNKNOWN → reported) |
| Spec-7.1 dimension rule preserved | Task 1 (6-arg constructor infers BROKEN for w>0/h=0) |
| Acceptance-level proof | Task 5 |

### Placeholder Scan

No TBDs, TODOs, or "implement later" found. Every step contains the actual code.

### Type Consistency

- `ImageState.parse(String)` matches `"state"` field from extract.js (`'decoded'`/`'broken'`/`'unknown'`)
- `ImageRef` 7-arg constructor used in `PageNavigator.map()`; 6-arg backwards-compatible constructor used by all existing test code and `Snapshots.Builder` overloads without explicit state
- `UrlStatus.ofHttpStatus()` used consistently in `UrlVerifier` fallback condition and `UrlStatus` enum
- `FixtureSite.requestCount(String)` uses path with leading `/` — callers use `/geblockt-403`, `/hart-404` etc., matching `dispatch()` paths
