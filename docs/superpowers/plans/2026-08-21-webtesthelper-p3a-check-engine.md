# WebTestHelper Plan 3a — The Check Engine and the Snapshot-Only Checks

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Turn the `PageSnapshot`s Plan 2 produces into findings — the check SPI, the registry, the §13.7 documentation gate that fails the build when a check cannot explain itself, the eight layer-1 page checks that need nothing but a snapshot, and the check pass wired into the run pipeline.

**Architecture:** A page check is a **pure function** `PageSnapshot -> List<CheckFinding>` (§5.2). It never drives a browser, never touches the database, and never knows about fingerprints or lifecycle — the transient `CheckFinding` it emits is turned into the persistent `Finding` during materialisation in Plan 4 (§7.3). That purity is the whole payoff of "navigate once, check many": adding a check costs one class and a unit test built from a hand-built snapshot. The `checks` module therefore depends on `model` and nothing else, and holds no Spring beans (§5.1).

**Tech Stack:** Java 25, Spring Boot 4.1.1 (only in the runner wiring), Spring Modulith for the boundary, PostgreSQL 17 via Testcontainers for the acceptance test, Playwright/Chromium for the acceptance test only.

**Spec:** `docs/superpowers/specs/2026-08-21-webtesthelper-design.md` — read it alongside this plan. Section references like (§7.1) point there.
**Roadmap:** `docs/superpowers/plans/2026-08-21-webtesthelper-phase-1-roadmap.md` — this is the first half of plan 3 of 5.
**Predecessors:** `…-p1-foundation.md`, `…-p2a-frontier.md` and `…-p2b-browser-crawl.md`, all executed and reviewed; their commits are on `main`.

**Length:** ~3,000 lines against the roadmap's ~1,500 target, and almost two thirds of it is test code — thirteen test classes, because the argument for this architecture is that a check costs one class and a unit test with no browser in it, and a plan that asserted that without showing the tests would be asserting the wrong thing. The four tasks are ~1,000, ~500, ~500 and ~700 lines, so each is still one fresh-subagent sitting, which is what the cap protects. Splitting further would separate the SPI from the first check that proves it works. Noted rather than silently exceeded.

**Ends with:** a `MANUAL` `FULL` run against the fixture site that crawls it, evaluates eight page checks over the real snapshots, and completes `COMPLETED` with a non-zero `findings_total` — reporting the soft 404, the hard 404, the redirect loop, the missing image, the dead video, the blocked iframe and the broken Maps embed, and reporting **nothing** for the two checks that ship disabled.

---

## What Plan 2 leaves you

| Type | What it gives you |
|---|---|
| `model.PageSnapshot` | `url`, `requestedUrl`, `depth`, `reachable`, `unreachableReason`, `httpStatus`, `responseHeaders`, `redirectChain`, `loadMillis`, `title`, `htmlLang`, `textContent`, `textSimhash`, `links`, `images`, `media`, `frames`, `forms`, `consoleMessages`, `failedRequests`, `screenshotPath`; plus `unreachable(...)`, `isSecure()`, `internalLinks()`, `externalLinks()`, `errors()` |
| `model.LinkRef` | `(rawHref, target, anchorText, internal, rel)`, `nofollow()` |
| `model.ImageRef` / `ImageOrigin` | `(rawSource, target, alt, naturalWidth, naturalHeight, origin)`, `rendered()`; `IMG`, `SRCSET`, `CSS_BACKGROUND` |
| `model.MediaRef` / `MediaKind` | `(kind, sources, readyState, duration, errorCode)`, `playable()`; `VIDEO`, `AUDIO` |
| `model.FrameRef` | `(src, title, loaded, contentTextLength, sameOrigin)` |
| `model.ConsoleMessage` / `FailedRequest` | `(level, text, location)` / `(url, method, resourceType, status, failureText)` |
| `model.RunSnapshots` | `(runId, site, snapshots, softNotFound)`, `byUrl`, `visitedUrls()`, `pageCount()` |
| `model.SoftNotFoundProbe` | `(httpStatus, simhash, textLength)`, `NONE`, `usable()` |
| `model.SimHash` | `of(String)`, `hammingDistance(long, long)` |
| `model.NormalizedUrl` | `value()`, `origin()`, `locationKey()`, `registrableHost()`, `sameSiteAs`, `isSecure()`, `path()`, `host()`, `scheme()` |
| `model.UrlNormalizer` | `normalize(String) -> Optional<NormalizedUrl>`, `key(String) -> Optional<String>`, `resolve(base, href)`. Already lowercases scheme and host, drops the default port and the fragment, sorts query parameters and strips `utm_*` and friends — so **`NormalizedUrl.value()` is the `subjectKey` of §6.2 with no further work** |
| `model.CheckType` | `PAGE_STATUS, PAGE_UNREACHABLE, DEAD_LINK, REDIRECT_CHAIN, IMAGE_BROKEN, FILE_DOWNLOAD, MEDIA_PLAYABLE, IFRAME_EMBED, MIXED_CONTENT, CONSOLE_ERRORS, TLS_CERT, HREFLANG, SITEMAP_CONSISTENCY` |
| `model.Severity` | `ERROR, WARN, INFO`, `max(other)` |
| `model.CheckSetting` | `(enabled, severityOverride, config)`, `defaultEnabled()`, `defaultDisabled()` |
| `model.SiteContext` | `enabled(CheckType)`, `settingsFor(CheckType) -> Map<String,Object>`, `severityFor(CheckType, Severity) -> Severity`, `effectiveUserAgent()` |
| `model.RunScope` | `checkTypes() -> Set<CheckType>`, `crawlsWholeSite()` |
| `crawler.CrawlService` | `crawl(CrawlRequest, CrawlProgressListener) -> CrawlResult` |
| `crawler.CrawlResult` | `(snapshots, pagesVisited, pagesFailed, coveredUrls, partialCoverage, budgetStopReason)` |
| `runner.CrawlRunExecutor` | the sole `RunExecutor`; **this plan inserts the check pass into it** |
| `runner.persistence.RunResultJdbcRepository` | `updateProgress`, `saveCrawlOutcome(runId, result, coveredCheckTypes, probe)` — **this plan adds a findings count** |
| `catalog.SiteService` | `create(SiteForm)`, `contextFor(long)`; seeds one `site_check_setting` per `CheckType`, with `CONSOLE_ERRORS` and `SITEMAP_CONSISTENCY` disabled |
| `support.FixtureSite` | `start()`, `baseUrl()`, `externalBase()`, `url(pathWithoutLeadingSlash)`, `close()` |
| `support.AbstractPostgresTest` | shared Testcontainers Postgres; subclasses clear their own tables in `@BeforeEach` |

## Deviations applying to this plan

Carried forward: **D1** (`model` holds shared value types; `checks` depends only on it),
**D4** (a normalised URL lowercases scheme and host, never the path), **D6** (the fixture site
is plain HTTP, so `MIXED_CONTENT` is proven from hand-built snapshots), **D7** (snapshots are
memory-resident for the length of a run).

Applied here for the first time:

- **D2 — page checks run in one post-crawl pass**, not inline in the crawl loop. The crawler
  produces snapshots and evaluates nothing; `CheckEngine` walks the finished `RunSnapshots`.
  This is what keeps a check from influencing crawl order or another check's input.
- **D3 — `CheckConfig` carries run-scoped facts** as a `RunFacts` record, keeping the §7.3
  signature `evaluate(PageSnapshot, CheckConfig)` intact. Without it `PAGE_STATUS` could not
  see the soft-404 probe, which is a fact about the *run*, not about the page.

New in this plan:

- **D14 — `CheckDescriptor` derives its three key names from the check type** (`titleKey()`
  returns `check.PAGE_STATUS.title` and so on) **and adds `messageKeys()`.** The spec names the
  three methods; deriving them makes a typo impossible, and `messageKeys()` extends §13.7's
  build-failing gate to the keys a *finding* actually renders. A finding whose message key does
  not resolve shows the user `???finding.X.y???`, which the enforcement test exists to prevent.
- **D15 — `CheckRegistry.standard()` is an explicit list, not component scanning.** §5.1 says
  `checks` holds no Spring, which rules out `@Component` plus `List<PageCheck>` injection. The
  property §7.3 actually asks for — "adding the twelfth check must not require touching the
  runner" — is preserved: the list lives in `checks`, beside the implementations, and a
  build-failing test asserts every `CheckType` in the catalog has exactly one implementation.
- **D16 — `CONSOLE_ERRORS` ignore patterns are case-insensitive substrings**, not the anchored
  URL globs of D8. They match free-form console text, where a substring is what a human
  actually wants to write, and duplicating the glob compiler across a module boundary to gain
  `?` would be worse.
- **D17 — a cross-origin iframe is never reported empty.** Measured against the fixture: a
  frame blocked by `X-Frame-Options` reports `loaded=true`, `sameOrigin=false`,
  `contentTextLength=0` — byte for byte what a *healthy* YouTube or Maps embed reports, because
  the parent cannot read a cross-origin document. Emptiness is therefore only reported for
  same-origin frames; blocking is detected from the failed document request instead.

## Global Constraints

Every task's requirements implicitly include this section, plus Plan 1's, 2a's and 2b's.

- **Java 25, Spring Boot 4.1.1. No new dependencies in this plan**, and no change to
  `playwright.version`.
- **`spring.jpa.hibernate.ddl-auto=validate` everywhere. This plan adds no migration**: the
  `run` table already carries `findings_total`. Plan 3b adds the first new table since V7.
- **`checks` holds no Spring beans, no database access and no browser access** (§5.1). It may
  import from `model` and the JDK, nothing else. The Modulith test enforces this.
- **A check is a pure function.** Same snapshot plus same config always yields the same
  findings, in the same order. No clocks, no randomness, no I/O, no static mutable state.
- **No internal identifier reaches the screen** (§13.1). Every user-visible string is a message
  key resolved from `messages.properties`; `CheckType` names, enum constants and Chromium error
  codes belong in evidence, never in a rendered sentence. The documentation test enforces this.
- **German is the only locale.** `messages.properties` is the German bundle; there is no
  English bundle to keep in step.
- **Nothing in any test touches a real website** (§15). Every URL is loopback, `example.com`
  in a hand-built snapshot, or a deliberately dead loopback port.
- **Commit after every task.** Conventional commits; code and commits in English, only
  user-facing strings in German.

## Measured constants

These four numbers were measured against the fixture site with a real Chromium before this plan
was written. They are not guesses, and a change to any of them needs a re-measurement, not an
opinion.

| Constant | Value | Measurement |
|---|---|---|
| Soft-404 SimHash cutoff | **16** | Exact clone of the not-found page: 0. Not-found page echoing the requested path: 8–12 (plan 2a), 20 (path spliced mid-sentence). Closest unrelated real page (`/kontakt.html`): 27. Then `/leistungen.html` 31, `/` 33, `/medien.html` 35, `/en/index.html` 37. |
| Blocked-iframe signal | `net::ERR_BLOCKED_BY_RESPONSE` on a `document` request | The fixture's `X-Frame-Options: DENY` frame reports `loaded=true`, `sameOrigin=false`, `textLength=0` — identical to a healthy cross-origin embed. The failed request is the only usable signal. |
| Redirect loop | arrives as `reachable=false` | `/schleife/a` fails navigation with `net::ERR_TOO_MANY_REDIRECTS`; it never yields a chain containing a repeat. |
| Fixture redirect chain | 3 hops, final status 200 | `/weiter/1 → /weiter/2 → /weiter/3 → /ziel.html`, so `redirectChain` has 4 entries. |

---

### Task 1: The SPI, the registry, the documentation gate and the two status checks

The whole plan rests on this task: three value types, three interfaces, a registry, and the
test that makes an undocumented check impossible to merge. Two checks land with it — the ones
that answer "did this page work at all", including the soft-404 rule, which is the subtlest
piece of logic in the catalog.

**Files:**
- Create: `src/main/java/dev/hendrikhoemberg/webtesthelper/model/Evidence.java`
- Create: `src/main/java/dev/hendrikhoemberg/webtesthelper/model/CheckFinding.java`
- Create: `src/main/java/dev/hendrikhoemberg/webtesthelper/model/RunFacts.java`
- Create: `src/main/java/dev/hendrikhoemberg/webtesthelper/checks/package-info.java`
- Create: `src/main/java/dev/hendrikhoemberg/webtesthelper/checks/CheckDescriptor.java`
- Create: `src/main/java/dev/hendrikhoemberg/webtesthelper/checks/PageCheck.java`
- Create: `src/main/java/dev/hendrikhoemberg/webtesthelper/checks/SiteCheck.java`
- Create: `src/main/java/dev/hendrikhoemberg/webtesthelper/checks/CheckConfig.java`
- Create: `src/main/java/dev/hendrikhoemberg/webtesthelper/checks/CheckRegistry.java`
- Create: `src/main/java/dev/hendrikhoemberg/webtesthelper/checks/PageUnreachableCheck.java`
- Create: `src/main/java/dev/hendrikhoemberg/webtesthelper/checks/PageStatusCheck.java`
- Create: `src/main/resources/messages.properties`
- Test: `src/test/java/dev/hendrikhoemberg/webtesthelper/support/Snapshots.java`
- Test: `src/test/java/dev/hendrikhoemberg/webtesthelper/checks/CheckDocumentationTest.java`
- Test: `src/test/java/dev/hendrikhoemberg/webtesthelper/checks/CheckRegistryTest.java`
- Test: `src/test/java/dev/hendrikhoemberg/webtesthelper/checks/PageUnreachableCheckTest.java`
- Test: `src/test/java/dev/hendrikhoemberg/webtesthelper/checks/PageStatusCheckTest.java`

**Interfaces:**
- Consumes: `model.PageSnapshot`, `model.SoftNotFoundProbe`, `model.SimHash`, `model.CheckType`,
  `model.Severity`, `model.RunScope`, `model.NormalizedUrl`, `support.FixtureSite` (not yet).
- Produces:
  - `model.Evidence(String screenshotPath, Integer httpStatus, String requestDetail, String responseDetail, List<String> consoleExcerpt)` with `NONE` and `ofPage(PageSnapshot)`
  - `model.CheckFinding(CheckType type, Severity severity, String subjectKey, NormalizedUrl observedOn, String messageKey, List<String> messageArgs, Evidence evidence)` with `locationKey()`
  - `model.RunFacts(long runId, RunScope scope, Instant startedAt, SoftNotFoundProbe softNotFound)` with `of(RunSnapshots, RunScope, Instant)`
  - `checks.CheckDescriptor` — `type()`, `titleKey()`, `descriptionKey()`, `remediationKey()`, `defaultSeverity()`, `messageKeys()`
  - `checks.PageCheck.evaluate(PageSnapshot, CheckConfig) -> List<CheckFinding>`
  - `checks.SiteCheck.evaluate(RunSnapshots, SiteContext, CheckConfig) -> List<CheckFinding>`
  - `checks.CheckConfig(Severity severity, Map<String,Object> options, RunFacts facts)` with `option(String,int)` and `optionList(String)`
  - `checks.CheckRegistry` — `standard()`, `pageChecks()`, `siteChecks()`, `all()`, `coveredTypes()`
  - `support.Snapshots` — `page(String)`, `url(String)`, `facts()`, `facts(SoftNotFoundProbe)`, `config(PageCheck, RunFacts)`, `config(PageCheck, RunFacts, Map<String,Object>)`

- [ ] **Step 1: Write the test builder and the failing tests**

`src/test/java/dev/hendrikhoemberg/webtesthelper/support/Snapshots.java` — every check test in
this plan is built on it, so it comes first:

```java
package dev.hendrikhoemberg.webtesthelper.support;

import dev.hendrikhoemberg.webtesthelper.checks.CheckConfig;
import dev.hendrikhoemberg.webtesthelper.checks.PageCheck;
import dev.hendrikhoemberg.webtesthelper.model.ConsoleMessage;
import dev.hendrikhoemberg.webtesthelper.model.FailedRequest;
import dev.hendrikhoemberg.webtesthelper.model.FormRef;
import dev.hendrikhoemberg.webtesthelper.model.FrameRef;
import dev.hendrikhoemberg.webtesthelper.model.ImageOrigin;
import dev.hendrikhoemberg.webtesthelper.model.ImageRef;
import dev.hendrikhoemberg.webtesthelper.model.LinkRef;
import dev.hendrikhoemberg.webtesthelper.model.MediaKind;
import dev.hendrikhoemberg.webtesthelper.model.MediaRef;
import dev.hendrikhoemberg.webtesthelper.model.NormalizedUrl;
import dev.hendrikhoemberg.webtesthelper.model.PageSnapshot;
import dev.hendrikhoemberg.webtesthelper.model.RunFacts;
import dev.hendrikhoemberg.webtesthelper.model.RunScope;
import dev.hendrikhoemberg.webtesthelper.model.SimHash;
import dev.hendrikhoemberg.webtesthelper.model.SoftNotFoundProbe;
import dev.hendrikhoemberg.webtesthelper.model.UrlNormalizer;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Hand-built {@link PageSnapshot}s for check unit tests.
 *
 * <p>Spec 5.2's payoff is that a page check is a pure function over a snapshot: adding a check
 * costs one class and a unit test with no browser in it. A twenty-one argument constructor
 * would quietly undo that, so every field here carries a sensible default and a test sets only
 * the two or three fields it is actually about.
 */
public final class Snapshots {

    private Snapshots() {
    }

    public static NormalizedUrl url(String value) {
        return UrlNormalizer.normalize(value)
                .orElseThrow(() -> new IllegalArgumentException("Keine URL: " + value));
    }

    public static Builder page(String url) {
        return new Builder(url);
    }

    /** Run facts with no usable soft-404 probe — the common case for a check that ignores it. */
    public static RunFacts facts() {
        return facts(SoftNotFoundProbe.NONE);
    }

    public static RunFacts facts(SoftNotFoundProbe probe) {
        return new RunFacts(1L, RunScope.FULL, Instant.EPOCH, probe);
    }

    public static CheckConfig config(PageCheck check, RunFacts facts) {
        return config(check, facts, Map.of());
    }

    public static CheckConfig config(PageCheck check, RunFacts facts, Map<String, Object> options) {
        return new CheckConfig(check.defaultSeverity(), options, facts);
    }

    public static final class Builder {

        private final NormalizedUrl url;
        private String requestedUrl;
        private int depth = 1;
        private int httpStatus = 200;
        private final Map<String, String> headers = new LinkedHashMap<>();
        private List<String> redirectChain;
        private String text = "Willkommen bei der Firma Beispiel. Wir beraten Sie gerne zu allen Fragen.";
        private final List<LinkRef> links = new ArrayList<>();
        private final List<ImageRef> images = new ArrayList<>();
        private final List<MediaRef> media = new ArrayList<>();
        private final List<FrameRef> frames = new ArrayList<>();
        private final List<ConsoleMessage> console = new ArrayList<>();
        private final List<FailedRequest> failed = new ArrayList<>();

        private Builder(String url) {
            this.url = Snapshots.url(url);
            this.requestedUrl = url;
        }

        public Builder status(int httpStatus) {
            this.httpStatus = httpStatus;
            return this;
        }

        public Builder depth(int depth) {
            this.depth = depth;
            return this;
        }

        public Builder text(String text) {
            this.text = text;
            return this;
        }

        public Builder header(String name, String value) {
            headers.put(name, value);
            return this;
        }

        /** Requested URL first, final URL last — exactly what {@code PageNavigator} records. */
        public Builder redirectChain(String... urls) {
            this.requestedUrl = urls[0];
            this.redirectChain = List.of(urls);
            return this;
        }

        public Builder link(String href, boolean internal) {
            links.add(new LinkRef(href, Snapshots.url(href), "Weiterlesen", internal, ""));
            return this;
        }

        public Builder image(String src, int naturalWidth) {
            return image(src, naturalWidth, ImageOrigin.IMG);
        }

        public Builder image(String src, int naturalWidth, ImageOrigin origin) {
            images.add(new ImageRef(src, Snapshots.url(src), "Alt-Text", naturalWidth,
                    naturalWidth == 0 ? 0 : 40, origin));
            return this;
        }

        public Builder media(MediaKind kind, String src, int readyState, double duration,
                String errorCode) {
            media.add(new MediaRef(kind, List.of(Snapshots.url(src)), readyState, duration,
                    errorCode));
            return this;
        }

        public Builder frame(String src, boolean sameOrigin, int contentTextLength) {
            frames.add(new FrameRef(Snapshots.url(src), "Eingebettet", true, contentTextLength,
                    sameOrigin));
            return this;
        }

        public Builder consoleError(String message) {
            console.add(new ConsoleMessage("error", message, url.value()));
            return this;
        }

        /** What Chromium reports for a frame refused by X-Frame-Options or a CSP. */
        public Builder blockedDocument(String src) {
            failed.add(new FailedRequest(src, "GET", "document", null,
                    "net::ERR_BLOCKED_BY_RESPONSE"));
            return this;
        }

        public PageSnapshot build() {
            return new PageSnapshot(url, requestedUrl, depth, true, null, httpStatus,
                    Map.copyOf(headers),
                    redirectChain == null ? List.of(url.value()) : redirectChain,
                    120L, "Titel", "de", text, SimHash.of(text), links, images, media, frames,
                    List.<FormRef>of(), console, failed, "seite.png");
        }

        public PageSnapshot unreachable(String reason) {
            return PageSnapshot.unreachable(url, requestedUrl, depth, reason,
                    List.copyOf(console), List.copyOf(failed));
        }
    }
}
```

`src/test/java/dev/hendrikhoemberg/webtesthelper/checks/PageUnreachableCheckTest.java`:

```java
package dev.hendrikhoemberg.webtesthelper.checks;

import dev.hendrikhoemberg.webtesthelper.model.CheckFinding;
import dev.hendrikhoemberg.webtesthelper.model.CheckType;
import dev.hendrikhoemberg.webtesthelper.model.Severity;
import dev.hendrikhoemberg.webtesthelper.support.Snapshots;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PageUnreachableCheckTest {

    private final PageUnreachableCheck check = new PageUnreachableCheck();

    @Test
    void aPageThatTimedOutIsReported() {
        List<CheckFinding> findings = check.evaluate(
                Snapshots.page("https://example.com/langsam").unreachable("Timeout 30000ms exceeded"),
                Snapshots.config(check, Snapshots.facts()));

        assertThat(findings).singleElement().satisfies(finding -> {
            assertThat(finding.type()).isEqualTo(CheckType.PAGE_UNREACHABLE);
            assertThat(finding.severity()).isEqualTo(Severity.ERROR);
            assertThat(finding.subjectKey()).isEqualTo("https://example.com/langsam");
            assertThat(finding.locationKey()).isEqualTo("/langsam");
            assertThat(finding.messageArgs()).containsExactly("Timeout 30000ms exceeded");
            assertThat(check.messageKeys()).contains(finding.messageKey());
        });
    }

    @Test
    void aReachablePageIsNotReported() {
        assertThat(check.evaluate(Snapshots.page("https://example.com/").build(),
                Snapshots.config(check, Snapshots.facts()))).isEmpty();
    }

    @Test
    void aRedirectLoopIsLeftToTheRedirectCheckSoOneBrokenPageYieldsOneFinding() {
        // Measured: Chromium fails a redirect loop with net::ERR_TOO_MANY_REDIRECTS, so the page
        // arrives here as unreachable. Reporting it under two names is the noise spec 8 exists
        // to prevent — REDIRECT_CHAIN owns it because it can say what is actually wrong.
        assertThat(check.evaluate(
                Snapshots.page("https://example.com/schleife")
                        .unreachable("net::ERR_TOO_MANY_REDIRECTS at https://example.com/schleife"),
                Snapshots.config(check, Snapshots.facts()))).isEmpty();
    }

    @Test
    void theRawBrowserErrorIsKeptAsEvidenceRatherThanShownAsProse() {
        CheckFinding finding = check.evaluate(
                Snapshots.page("https://example.com/x").unreachable("Timeout 30000ms\n  at Frame.goto"),
                Snapshots.config(check, Snapshots.facts())).getFirst();

        assertThat(finding.messageArgs()).containsExactly("Timeout 30000ms");   // first line only
        assertThat(finding.evidence().responseDetail()).contains("at Frame.goto");
    }
}
```

`src/test/java/dev/hendrikhoemberg/webtesthelper/checks/PageStatusCheckTest.java`:

```java
package dev.hendrikhoemberg.webtesthelper.checks;

import dev.hendrikhoemberg.webtesthelper.model.CheckFinding;
import dev.hendrikhoemberg.webtesthelper.model.CheckType;
import dev.hendrikhoemberg.webtesthelper.model.SimHash;
import dev.hendrikhoemberg.webtesthelper.model.SoftNotFoundProbe;
import dev.hendrikhoemberg.webtesthelper.support.Snapshots;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class PageStatusCheckTest {

    private static final String NOT_FOUND_TEXT =
            "Seite nicht gefunden. Die gewünschte Seite existiert leider nicht. Zur Startseite";

    private final PageStatusCheck check = new PageStatusCheck();

    private static SoftNotFoundProbe probe() {
        return new SoftNotFoundProbe(200, SimHash.of(NOT_FOUND_TEXT), NOT_FOUND_TEXT.length());
    }

    @Test
    void aServerErrorIsReportedWithItsStatusCode() {
        List<CheckFinding> findings = check.evaluate(
                Snapshots.page("https://example.com/weg").status(404).build(),
                Snapshots.config(check, Snapshots.facts(probe())));

        assertThat(findings).singleElement().satisfies(finding -> {
            assertThat(finding.type()).isEqualTo(CheckType.PAGE_STATUS);
            assertThat(finding.messageArgs()).containsExactly("404");
            assertThat(finding.evidence().httpStatus()).isEqualTo(404);
            assertThat(check.messageKeys()).contains(finding.messageKey());
        });
    }

    @Test
    void aPageThatIsTheNotFoundPageInDisguiseIsReportedAsASoftNotFound() {
        // The whole point of the {baseUrl}/{uuid} probe (spec 7.1): status 200 means nothing on
        // a site that answers 200 for everything.
        List<CheckFinding> findings = check.evaluate(
                Snapshots.page("https://example.com/verirrt").text(NOT_FOUND_TEXT).build(),
                Snapshots.config(check, Snapshots.facts(probe())));

        assertThat(findings).singleElement().satisfies(finding ->
                assertThat(finding.messageKey()).isEqualTo("finding.PAGE_STATUS.soft404"));
    }

    @Test
    void aRealPageIsNotMistakenForTheNotFoundPage() {
        // Measured against the fixture: the closest unrelated real page sits at 27, the cutoff
        // at 16. A check that eats real pages is worse than no check at all (spec 8).
        assertThat(check.evaluate(
                Snapshots.page("https://example.com/kontakt")
                        .text("Kontakt. Zurück zur Startseite. Name E-Mail Nachricht Absenden").build(),
                Snapshots.config(check, Snapshots.facts(probe())))).isEmpty();
    }

    @Test
    void theCutoffIsOverridablePerSite() {
        assertThat(check.evaluate(
                Snapshots.page("https://example.com/kontakt")
                        .text("Kontakt. Zurück zur Startseite. Name E-Mail Nachricht Absenden").build(),
                Snapshots.config(check, Snapshots.facts(probe()), Map.of("maxDistance", 64))))
                .hasSize(1);
    }

    @Test
    void withoutAUsableProbeNothingIsCalledASoftNotFound() {
        // A site whose {uuid} page is a genuine 404 gives us nothing to compare against, and
        // guessing there would turn every short page into a finding.
        assertThat(check.evaluate(
                Snapshots.page("https://example.com/verirrt").text(NOT_FOUND_TEXT).build(),
                Snapshots.config(check, Snapshots.facts(SoftNotFoundProbe.NONE)))).isEmpty();
    }

    @Test
    void anUnreachablePageIsLeftToThePageUnreachableCheck() {
        assertThat(check.evaluate(
                Snapshots.page("https://example.com/x").unreachable("Timeout"),
                Snapshots.config(check, Snapshots.facts(probe())))).isEmpty();
    }

    @Test
    void aHardNotFoundIsReportedOnceAsAStatusErrorAndNotAlsoAsASoftNotFound() {
        assertThat(check.evaluate(
                Snapshots.page("https://example.com/hart-404").status(404).text(NOT_FOUND_TEXT).build(),
                Snapshots.config(check, Snapshots.facts(probe()))))
                .singleElement()
                .satisfies(finding ->
                        assertThat(finding.messageKey()).isEqualTo("finding.PAGE_STATUS.httpError"));
    }
}
```

`src/test/java/dev/hendrikhoemberg/webtesthelper/checks/CheckRegistryTest.java`:

```java
package dev.hendrikhoemberg.webtesthelper.checks;

import dev.hendrikhoemberg.webtesthelper.model.CheckType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CheckRegistryTest {

    private final CheckRegistry registry = CheckRegistry.standard();

    @Test
    void theStandardRegistryHoldsThePageChecksThatShipToday() {
        assertThat(registry.coveredTypes())
                .contains(CheckType.PAGE_STATUS, CheckType.PAGE_UNREACHABLE);
    }

    @Test
    void noCheckTypeIsImplementedTwice() {
        List<CheckType> types = registry.all().stream().map(CheckDescriptor::type).toList();
        assertThat(types).doesNotHaveDuplicates();
    }

    @Test
    void everyCheckDeclaresAtLeastOneFindingMessageKeyForItsOwnType() {
        assertThat(registry.all()).allSatisfy(check -> {
            assertThat(check.messageKeys()).isNotEmpty();
            assertThat(check.messageKeys()).allSatisfy(key ->
                    assertThat(key).startsWith("finding." + check.type().name() + "."));
        });
    }

    @Test
    void theThreeCatalogKeysAreDerivedFromTheTypeSoTheyCannotDrift() {
        assertThat(registry.all()).allSatisfy(check -> {
            assertThat(check.titleKey()).isEqualTo("check." + check.type().name() + ".title");
            assertThat(check.descriptionKey())
                    .isEqualTo("check." + check.type().name() + ".description");
            assertThat(check.remediationKey())
                    .isEqualTo("check." + check.type().name() + ".remediation");
        });
    }
}
```

`src/test/java/dev/hendrikhoemberg/webtesthelper/checks/CheckDocumentationTest.java` — this is
§13.7's first enforcement test, and the reason an undocumented check cannot be merged:

```java
package dev.hendrikhoemberg.webtesthelper.checks;

import dev.hendrikhoemberg.webtesthelper.model.CheckType;
import org.junit.jupiter.api.Test;
import org.springframework.context.MessageSource;
import org.springframework.context.NoSuchMessageException;
import org.springframework.context.support.ResourceBundleMessageSource;

import java.util.List;
import java.util.Locale;
import java.util.ResourceBundle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Spec 13.7, enforcement 1: every registered check resolves its three explanation keys in every
 * supported locale, and every message key a finding can render resolves too (deviation D14).
 * Documentation that cannot rot is worth more than documentation that is merely thorough.
 *
 * <p>No Spring context: a bundle and a registry are all this needs, and a test that costs a
 * Postgres container is a test people start skipping.
 */
class CheckDocumentationTest {

    /** Spec 12: German is the default and only locale. A second entry here is a real project. */
    private static final List<Locale> SUPPORTED = List.of(Locale.GERMAN);

    private final CheckRegistry registry = CheckRegistry.standard();
    private final MessageSource messages = messageSource();

    private static MessageSource messageSource() {
        ResourceBundleMessageSource source = new ResourceBundleMessageSource();
        source.setBasename("messages");
        source.setDefaultEncoding("UTF-8");
        source.setUseCodeAsDefaultMessage(false);
        return source;
    }

    @Test
    void everyCheckExplainsItselfInEverySupportedLocale() {
        for (Locale locale : SUPPORTED) {
            assertThat(registry.all()).allSatisfy(check -> {
                assertThatCode(() -> messages.getMessage(check.titleKey(), null, locale))
                        .as("%s", check.titleKey()).doesNotThrowAnyException();
                assertThatCode(() -> messages.getMessage(check.descriptionKey(), null, locale))
                        .as("%s", check.descriptionKey()).doesNotThrowAnyException();
                assertThatCode(() -> messages.getMessage(check.remediationKey(), null, locale))
                        .as("%s", check.remediationKey()).doesNotThrowAnyException();
            });
        }
    }

    @Test
    void everyMessageKeyAFindingCanRenderResolves() {
        for (Locale locale : SUPPORTED) {
            assertThat(registry.all()).allSatisfy(check ->
                    assertThat(check.messageKeys()).allSatisfy(key ->
                            assertThatCode(() -> messages.getMessage(key, new Object[]{"1", "2"}, locale))
                                    .as("%s", key).doesNotThrowAnyException()));
        }
    }

    @Test
    void noExplanationLeaksAnInternalIdentifier() {
        // Spec 13.1: "Tote Links", never DEAD_LINK. The audience is a colleague, not a developer.
        for (Locale locale : SUPPORTED) {
            assertThat(registry.all()).allSatisfy(check -> {
                for (String key : List.of(check.titleKey(), check.descriptionKey(),
                        check.remediationKey())) {
                    String text = messages.getMessage(key, null, locale);
                    for (CheckType type : CheckType.values()) {
                        assertThat(text).as("%s", key).doesNotContain(type.name());
                    }
                }
            });
        }
    }

    @Test
    void theBundleCarriesNoKeysForChecksThatDoNotExist() {
        // The other direction: a renamed check leaves dead German prose behind, and nobody ever
        // notices because nothing reads it.
        List<String> declared = registry.all().stream()
                .flatMap(check -> java.util.stream.Stream.concat(
                        java.util.stream.Stream.of(check.titleKey(), check.descriptionKey(),
                                check.remediationKey()),
                        check.messageKeys().stream()))
                .toList();
        ResourceBundle bundle = ResourceBundle.getBundle("messages", Locale.GERMAN);
        List<String> orphans = bundle.keySet().stream()
                .filter(key -> key.startsWith("check.") || key.startsWith("finding."))
                .filter(key -> !declared.contains(key))
                .sorted()
                .toList();
        assertThat(orphans).isEmpty();
    }

    @Test
    void aMissingKeyFailsRatherThanRenderingItsOwnName() {
        assertThatCode(() -> messages.getMessage("check.GIBT_ES_NICHT.title", null, Locale.GERMAN))
                .isInstanceOf(NoSuchMessageException.class);
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./mvnw test -DexcludedGroups=browser -Dtest='Check*Test,Page*CheckTest'`
Expected: FAIL — compilation errors, `package dev.hendrikhoemberg.webtesthelper.checks does not exist`.

- [ ] **Step 3: Write the three model types**

`src/main/java/dev/hendrikhoemberg/webtesthelper/model/Evidence.java`:

```java
package dev.hendrikhoemberg.webtesthelper.model;

import java.util.List;

/**
 * What lets an employee judge a finding in five seconds instead of re-checking it by hand
 * (spec 8). Every component is optional; a check fills in what it actually observed and leaves
 * the rest null, because inventing evidence is worse than having none.
 */
public record Evidence(String screenshotPath, Integer httpStatus, String requestDetail,
                       String responseDetail, List<String> consoleExcerpt) {

    public static final Evidence NONE = new Evidence(null, null, null, null, List.of());

    public Evidence {
        consoleExcerpt = consoleExcerpt == null ? List.of() : List.copyOf(consoleExcerpt);
    }

    /** Screenshot and status of the page the finding was observed on. */
    public static Evidence ofPage(PageSnapshot snapshot) {
        return new Evidence(snapshot.screenshotPath(),
                snapshot.reachable() ? snapshot.httpStatus() : null, null, null, List.of());
    }
}
```

`src/main/java/dev/hendrikhoemberg/webtesthelper/model/CheckFinding.java`:

```java
package dev.hendrikhoemberg.webtesthelper.model;

import java.util.List;
import java.util.Objects;

/**
 * A transient result emitted by a check (spec 7.3). The persistent {@code Finding} entity of
 * spec 6.2 is created from it during materialisation, which is the only point where
 * fingerprints and site-wide promotion can be computed — so a check needs no knowledge of
 * identity or lifecycle, and cannot accidentally acquire any.
 *
 * @param subjectKey  the broken thing, already normalised. For a URL subject this is
 *                    {@link NormalizedUrl#value()}, which spec 6.2's normalisation rules are
 *                    already built into.
 * @param observedOn  the page it was seen on; null for a finding about the site as a whole.
 * @param messageArgs arguments for {@code messageKey}, in order. Plain strings: the renderer
 *                    formats, the check does not.
 */
public record CheckFinding(CheckType type, Severity severity, String subjectKey,
                           NormalizedUrl observedOn, String messageKey, List<String> messageArgs,
                           Evidence evidence) {

    public CheckFinding {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(severity, "severity");
        Objects.requireNonNull(subjectKey, "subjectKey");
        Objects.requireNonNull(messageKey, "messageKey");
        messageArgs = messageArgs == null ? List.of() : List.copyOf(messageArgs);
        evidence = evidence == null ? Evidence.NONE : evidence;
    }

    /**
     * Where it was found, as spec 6.2's {@code locationKey}: the page's path plus surviving
     * query. Materialisation may still promote this to {@code "*"} when the subject turns out
     * to be site-wide, which is knowledge no single check can have.
     */
    public String locationKey() {
        return observedOn == null ? "*" : observedOn.locationKey();
    }
}
```

`src/main/java/dev/hendrikhoemberg/webtesthelper/model/RunFacts.java`:

```java
package dev.hendrikhoemberg.webtesthelper.model;

import java.time.Instant;
import java.util.Objects;

/**
 * Run-scoped facts a page check needs but a single {@link PageSnapshot} cannot carry
 * (deviation D3). The soft-404 probe is the motivating case: whether a 200 response is really
 * the site's not-found page is a fact about the run, learned once at crawl start.
 *
 * <p>Plan 3b extends this record with the URL verification results that {@code DEAD_LINK} and
 * {@code FILE_DOWNLOAD} consume.
 */
public record RunFacts(long runId, RunScope scope, Instant startedAt,
                       SoftNotFoundProbe softNotFound) {

    public RunFacts {
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(startedAt, "startedAt");
        softNotFound = softNotFound == null ? SoftNotFoundProbe.NONE : softNotFound;
    }

    public static RunFacts of(RunSnapshots snapshots, RunScope scope, Instant startedAt) {
        return new RunFacts(snapshots.runId(), scope, startedAt, snapshots.softNotFound());
    }
}
```

- [ ] **Step 4: Write the SPI, the registry and the two checks**

`src/main/java/dev/hendrikhoemberg/webtesthelper/checks/package-info.java`:

```java
/**
 * The check catalog (spec 7). A page check is a pure function from a
 * {@link dev.hendrikhoemberg.webtesthelper.model.PageSnapshot} to a list of
 * {@link dev.hendrikhoemberg.webtesthelper.model.CheckFinding}: no Spring beans, no database,
 * no browser (spec 5.1). That is what lets the catalog be developed against hand-built
 * snapshots and regression-tested against the fixture site.
 *
 * <p>Deliberately flat, like {@code model}: Spring Modulith treats a module's sub-packages as
 * internal, and the registry has to see every implementation.
 */
@org.springframework.modulith.ApplicationModule(
        displayName = "Checks",
        allowedDependencies = {"model"})
package dev.hendrikhoemberg.webtesthelper.checks;
```

`CheckDescriptor.java`:

```java
package dev.hendrikhoemberg.webtesthelper.checks;

import dev.hendrikhoemberg.webtesthelper.model.CheckType;
import dev.hendrikhoemberg.webtesthelper.model.Severity;

import java.util.Set;

/**
 * What every check of every kind carries, and what the documentation enforcement test of spec
 * 13.7 walks.
 *
 * <p>Deviation D14: the three explanation keys are derived from the check type rather than
 * spelled out, so they cannot drift from the enum, and {@link #messageKeys()} extends the same
 * build-failing gate to the keys a finding actually renders — a finding whose key does not
 * resolve reaches the user as {@code ???finding.X.y???}.
 */
public interface CheckDescriptor {

    CheckType type();

    Severity defaultSeverity();

    /** Every finding message key this check can emit. Convention: {@code finding.TYPE.variant}. */
    Set<String> messageKeys();

    default String titleKey() {
        return "check." + type().name() + ".title";
    }

    default String descriptionKey() {
        return "check." + type().name() + ".description";
    }

    default String remediationKey() {
        return "check." + type().name() + ".remediation";
    }
}
```

`PageCheck.java`:

```java
package dev.hendrikhoemberg.webtesthelper.checks;

import dev.hendrikhoemberg.webtesthelper.model.CheckFinding;
import dev.hendrikhoemberg.webtesthelper.model.PageSnapshot;

import java.util.List;

/**
 * A check that runs once per page over an immutable snapshot (spec 5.2, 7.3).
 *
 * <p>Implementations must be pure: same snapshot plus same config, same findings, same order.
 * No clock, no randomness, no I/O, no mutable state. A check that needs the network is a
 * different kind of check and belongs behind {@link dev.hendrikhoemberg.webtesthelper.model.RunFacts}.
 */
public interface PageCheck extends CheckDescriptor {

    List<CheckFinding> evaluate(PageSnapshot snapshot, CheckConfig config);
}
```

`SiteCheck.java`:

```java
package dev.hendrikhoemberg.webtesthelper.checks;

import dev.hendrikhoemberg.webtesthelper.model.CheckFinding;
import dev.hendrikhoemberg.webtesthelper.model.RunSnapshots;
import dev.hendrikhoemberg.webtesthelper.model.SiteContext;

import java.util.List;

/**
 * A check that needs cross-page knowledge and therefore runs once, after the crawl (spec 5.2).
 * Plan 3b implements the three of them: hreflang reciprocity, sitemap consistency and TLS.
 */
public interface SiteCheck extends CheckDescriptor {

    List<CheckFinding> evaluate(RunSnapshots snapshots, SiteContext site, CheckConfig config);
}
```

`CheckConfig.java`:

```java
package dev.hendrikhoemberg.webtesthelper.checks;

import dev.hendrikhoemberg.webtesthelper.model.RunFacts;
import dev.hendrikhoemberg.webtesthelper.model.Severity;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * What a check is handed besides its subject: the severity this site resolved for it, the
 * site's per-check options, and the run-scoped facts (deviation D3).
 *
 * @param options the site's {@code site_check_setting.config} jsonb, so a number arrives as
 *                whatever Jackson produced — {@link #option(String, int)} exists because
 *                {@code (Integer) options.get("maxHops")} throws on a perfectly valid Long.
 *                A {@code null} value (`{"maxDistance": null}` is one careless edit away)
 *                is dropped rather than crashing the whole check pass.
 */
public record CheckConfig(Severity severity, Map<String, Object> options, RunFacts facts) {

    public CheckConfig {
        Objects.requireNonNull(severity, "severity");
        Objects.requireNonNull(facts, "facts");
        if (options == null) {
            options = Map.of();
        } else {
            Map<String, Object> copy = new HashMap<>(options);
            copy.values().removeIf(Objects::isNull);    // jsonb: {"maxDistance": null}
            options = Map.copyOf(copy);
        }
    }

    public int option(String key, int fallback) {
        return options.get(key) instanceof Number number ? number.intValue() : fallback;
    }

    /** A list-valued option, e.g. the ignore patterns of {@code CONSOLE_ERRORS}. */
    public List<String> optionList(String key) {
        if (!(options.get(key) instanceof List<?> values)) {
            return List.of();
        }
        return values.stream().filter(Objects::nonNull).map(Object::toString).toList();
    }
}
```

`CheckRegistry.java`:

```java
package dev.hendrikhoemberg.webtesthelper.checks;

import dev.hendrikhoemberg.webtesthelper.model.CheckType;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Every check the system can run, held by kind (spec 7.3).
 *
 * <p>Deviation D15: an explicit list rather than component scanning, because spec 5.1 says this
 * module holds no Spring. The property that matters is preserved — adding the twelfth check
 * means adding one class and one line <em>here</em>, never touching the runner — and
 * {@code CheckRegistryTest} fails the build if a {@link CheckType} ends up with no
 * implementation or two.
 */
public final class CheckRegistry {

    private final List<PageCheck> pageChecks;
    private final List<SiteCheck> siteChecks;

    public CheckRegistry(List<PageCheck> pageChecks, List<SiteCheck> siteChecks) {
        this.pageChecks = List.copyOf(pageChecks);
        this.siteChecks = List.copyOf(siteChecks);
    }

    public static CheckRegistry standard() {
        return new CheckRegistry(
                List.of(new PageUnreachableCheck(),
                        new PageStatusCheck()),
                List.of());     // Plan 3b adds TLS_CERT, HREFLANG and SITEMAP_CONSISTENCY
    }

    public List<PageCheck> pageChecks() {
        return pageChecks;
    }

    public List<SiteCheck> siteChecks() {
        return siteChecks;
    }

    public List<CheckDescriptor> all() {
        return Stream.concat(pageChecks.stream(), siteChecks.stream())
                .map(CheckDescriptor.class::cast)
                .toList();
    }

    public Set<CheckType> coveredTypes() {
        Set<CheckType> types = EnumSet.noneOf(CheckType.class);
        all().forEach(check -> types.add(check.type()));
        return types;
    }
}
```

`PageUnreachableCheck.java`:

```java
package dev.hendrikhoemberg.webtesthelper.checks;

import dev.hendrikhoemberg.webtesthelper.model.CheckFinding;
import dev.hendrikhoemberg.webtesthelper.model.CheckType;
import dev.hendrikhoemberg.webtesthelper.model.Evidence;
import dev.hendrikhoemberg.webtesthelper.model.PageSnapshot;
import dev.hendrikhoemberg.webtesthelper.model.Severity;

import java.util.List;
import java.util.Set;

/**
 * A page that timed out or crashed the tab (spec 14). The crawl already survived it — one bad
 * page never kills a run — and this check is what turns that survival into something a person
 * can act on.
 *
 * <p>A redirect loop is deliberately left to {@code REDIRECT_CHAIN}. Measured: Chromium fails
 * a loop with {@code net::ERR_TOO_MANY_REDIRECTS}, so the page arrives here as unreachable, and
 * reporting it under two names would be exactly the noise spec 8 spends its effort avoiding.
 */
public final class PageUnreachableCheck implements PageCheck {

    static final String NAVIGATION = "finding.PAGE_UNREACHABLE.navigation";

    /** Chromium's marker for a redirect loop. {@code REDIRECT_CHAIN} owns those pages. */
    static final String REDIRECT_LOOP_MARKER = "ERR_TOO_MANY_REDIRECTS";

    @Override
    public CheckType type() {
        return CheckType.PAGE_UNREACHABLE;
    }

    @Override
    public Severity defaultSeverity() {
        return Severity.ERROR;
    }

    @Override
    public Set<String> messageKeys() {
        return Set.of(NAVIGATION);
    }

    @Override
    public List<CheckFinding> evaluate(PageSnapshot snapshot, CheckConfig config) {
        if (snapshot.reachable()) {
            return List.of();
        }
        String reason = snapshot.unreachableReason() == null ? "" : snapshot.unreachableReason();
        if (reason.contains(REDIRECT_LOOP_MARKER)) {
            return List.of();
        }
        return List.of(new CheckFinding(type(), config.severity(), snapshot.url().value(),
                snapshot.url(), NAVIGATION, List.of(firstLine(reason)),
                new Evidence(null, null, null, reason, List.of())));
    }

    /** Playwright's error is a multi-line dump; the first line is the part a human reads. */
    private static String firstLine(String reason) {
        int newline = reason.indexOf('\n');
        return (newline < 0 ? reason : reason.substring(0, newline)).trim();
    }
}
```

`PageStatusCheck.java`:

```java
package dev.hendrikhoemberg.webtesthelper.checks;

import dev.hendrikhoemberg.webtesthelper.model.CheckFinding;
import dev.hendrikhoemberg.webtesthelper.model.CheckType;
import dev.hendrikhoemberg.webtesthelper.model.Evidence;
import dev.hendrikhoemberg.webtesthelper.model.PageSnapshot;
import dev.hendrikhoemberg.webtesthelper.model.Severity;
import dev.hendrikhoemberg.webtesthelper.model.SimHash;
import dev.hendrikhoemberg.webtesthelper.model.SoftNotFoundProbe;

import java.util.List;
import java.util.Set;

/**
 * 2xx, plus soft-404 detection (spec 7.1).
 *
 * <p>The soft-404 rule compares the page's text fingerprint against the {@code {baseUrl}/{uuid}}
 * probe taken at crawl start: a random path cannot be a real page, so whatever answers for it
 * is the site's not-found page. The cutoff is <strong>16</strong> bits of a 64-bit SimHash, and
 * it was measured rather than guessed. Against the fixture site with a real browser: an exact
 * clone of the not-found page scores 0, a not-found page that echoes the requested path 8–20,
 * and the closest unrelated real page 27 (then 31, 33, 35, 37). Sixteen sits in that gap with
 * margin on both sides.
 *
 * <p>Override per site with {@code {"maxDistance": 20}} — and re-measure before doing so, since
 * a cutoff that eats real pages is worse than no check at all (spec 8).
 */
public final class PageStatusCheck implements PageCheck {

    static final String HTTP_ERROR = "finding.PAGE_STATUS.httpError";
    static final String SOFT_404 = "finding.PAGE_STATUS.soft404";
    static final int DEFAULT_MAX_DISTANCE = 16;

    @Override
    public CheckType type() {
        return CheckType.PAGE_STATUS;
    }

    @Override
    public Severity defaultSeverity() {
        return Severity.ERROR;
    }

    @Override
    public Set<String> messageKeys() {
        return Set.of(HTTP_ERROR, SOFT_404);
    }

    @Override
    public List<CheckFinding> evaluate(PageSnapshot snapshot, CheckConfig config) {
        if (!snapshot.reachable()) {
            return List.of();                       // PAGE_UNREACHABLE owns a page that never answered
        }
        if (snapshot.httpStatus() >= 400) {
            return List.of(finding(snapshot, config, HTTP_ERROR,
                    List.of(String.valueOf(snapshot.httpStatus()))));
        }
        SoftNotFoundProbe probe = config.facts().softNotFound();
        if (snapshot.httpStatus() != 200 || !probe.usable() || snapshot.textContent().isBlank()) {
            return List.of();
        }
        int distance = SimHash.hammingDistance(snapshot.textSimhash(), probe.simhash());
        if (distance > config.option("maxDistance", DEFAULT_MAX_DISTANCE)) {
            return List.of();
        }
        return List.of(finding(snapshot, config, SOFT_404, List.of()));
    }

    private CheckFinding finding(PageSnapshot snapshot, CheckConfig config, String messageKey,
            List<String> args) {
        return new CheckFinding(type(), config.severity(), snapshot.url().value(), snapshot.url(),
                messageKey, args, Evidence.ofPage(snapshot));
    }
}
```

- [ ] **Step 5: Write the German message bundle**

`src/main/resources/messages.properties` — the file `spring.messages.basename=messages` has
been pointing at since Plan 1. German only (spec 12), and no apostrophes in any message that
takes arguments, because `MessageFormat` treats a single quote as an escape:

```properties
# Der Prüfkatalog in der Sprache der Kolleginnen und Kollegen, die den Bericht lesen
# (Spezifikation 13.1). Interne Bezeichner erscheinen hier nie.
# Zu jeder Prüfung gehören drei Schlüssel: was geprüft wird, was gefunden wurde, was zu tun ist.

check.PAGE_STATUS.title=Seitenstatus
check.PAGE_STATUS.description=Prüft, ob eine Seite normal ausgeliefert wird und nicht in Wahrheit die Fehlerseite der Website zeigt.
check.PAGE_STATUS.remediation=Seite im Browser aufrufen. Gibt es sie nicht mehr, den Verweis darauf entfernen oder eine Weiterleitung auf die passende neue Seite einrichten.
finding.PAGE_STATUS.httpError=Der Server beantwortet diese Seite mit dem Fehlercode {0}.
finding.PAGE_STATUS.soft404=Die Seite meldet Erfolg, zeigt aber denselben Inhalt wie die Fehlerseite der Website. Besucher landen hier in einer Sackgasse.

check.PAGE_UNREACHABLE.title=Seite nicht erreichbar
check.PAGE_UNREACHABLE.description=Prüft, ob sich eine Seite überhaupt laden lässt.
check.PAGE_UNREACHABLE.remediation=Seite selbst im Browser aufrufen. Bleibt sie hängen, Serverprotokoll und Antwortzeiten prüfen. Tritt der Fehler nur einmal auf, war es meist eine kurzzeitige Störung.
finding.PAGE_UNREACHABLE.navigation=Die Seite liess sich nicht laden: {0}
```

- [ ] **Step 6: Run the tests to verify they pass**

Run: `./mvnw test -DexcludedGroups=browser -Dtest='Check*Test,Page*CheckTest'`
Expected: PASS — `Tests run: 20, Failures: 0, Errors: 0`
(4 in `PageUnreachableCheckTest`, 7 in `PageStatusCheckTest`, 4 in `CheckRegistryTest`,
5 in `CheckDocumentationTest`.)

Then the whole browser-free suite, because a new module can break the Modulith test:

Run: `./mvnw test -DexcludedGroups=browser`
Expected: PASS, `ModularityTest` included.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/dev/hendrikhoemberg/webtesthelper/model src/main/java/dev/hendrikhoemberg/webtesthelper/checks src/main/resources/messages.properties src/test/java/dev/hendrikhoemberg/webtesthelper/checks src/test/java/dev/hendrikhoemberg/webtesthelper/support/Snapshots.java
git commit -m "feat(checks): add the check SPI, its registry and the two status checks"
```

**Post-review fixes (landed in `fix(checks): tolerate null jsonb option values and pin the config contract`):** `CheckConfig`'s options copy is null-tolerant (see the patched code above); a new `CheckConfigTest` (9 tests) pins the Long-valued option, the fallbacks, `optionList` and the null-value path; `CheckDocumentationTest.noExplanationLeaksAnInternalIdentifier` also scans the *values* of every `messageKeys()` entry for `CheckType` names; `PageStatusCheckTest` gained `aThreeHundredFinalStatusIsNotReported` (pins the 3xx gap); `PageStatusCheck`'s javadoc gained the echo-ceiling sentence (the measured 8–20 echo range can exceed the cutoff 16 — it is calibrated against the closest real page at 27). Expected counts after the fixes: 30 targeted, 159 browser-free.

---

### Task 2: The four checks that read the page's own structure

Redirects, images, media, mixed content. Each is a handful of lines over fields the snapshot
already carries, which is the point of §5.2 — and each has one rule that is easy to get subtly
wrong, so each gets a test that pins it.

**Files:**
- Create: `src/main/java/dev/hendrikhoemberg/webtesthelper/checks/RedirectChainCheck.java`
- Create: `src/main/java/dev/hendrikhoemberg/webtesthelper/checks/ImageBrokenCheck.java`
- Create: `src/main/java/dev/hendrikhoemberg/webtesthelper/checks/MediaPlayableCheck.java`
- Create: `src/main/java/dev/hendrikhoemberg/webtesthelper/checks/MixedContentCheck.java`
- Modify: `src/main/java/dev/hendrikhoemberg/webtesthelper/checks/CheckRegistry.java` (the `standard()` list)
- Modify: `src/main/resources/messages.properties` (four checks' worth of German)
- Test: `src/test/java/dev/hendrikhoemberg/webtesthelper/checks/RedirectChainCheckTest.java`
- Test: `src/test/java/dev/hendrikhoemberg/webtesthelper/checks/ImageBrokenCheckTest.java`
- Test: `src/test/java/dev/hendrikhoemberg/webtesthelper/checks/MediaPlayableCheckTest.java`
- Test: `src/test/java/dev/hendrikhoemberg/webtesthelper/checks/MixedContentCheckTest.java`

**Interfaces:**
- Consumes: `PageCheck`, `CheckConfig`, `CheckRegistry`, `Snapshots`, `model.ImageRef.rendered()`,
  `model.MediaRef.playable()`, `model.PageSnapshot.isSecure()`, `model.PageSnapshot.redirectChain()`.
- Produces: `RedirectChainCheck`, `ImageBrokenCheck`, `MediaPlayableCheck`, `MixedContentCheck`,
  all with a no-argument constructor and no state. `CheckRegistry.standard()` now covers six
  `CheckType`s.

- [ ] **Step 1: Write the failing tests**

`RedirectChainCheckTest.java`:

```java
package dev.hendrikhoemberg.webtesthelper.checks;

import dev.hendrikhoemberg.webtesthelper.model.CheckFinding;
import dev.hendrikhoemberg.webtesthelper.support.Snapshots;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class RedirectChainCheckTest {

    private final RedirectChainCheck check = new RedirectChainCheck();

    @Test
    void aChainWithinTheHopLimitIsNotReported() {
        // Measured against the fixture: /weiter/1 takes exactly three hops, which is also the
        // default limit. http to https to www to page is three legitimate hops on a real site.
        assertThat(check.evaluate(
                Snapshots.page("https://example.com/ziel")
                        .redirectChain("https://example.com/w1", "https://example.com/w2",
                                "https://example.com/w3", "https://example.com/ziel").build(),
                Snapshots.config(check, Snapshots.facts()))).isEmpty();
    }

    @Test
    void aLongerChainIsReportedWithItsHopCountAndDestination() {
        CheckFinding finding = check.evaluate(
                Snapshots.page("https://example.com/ziel")
                        .redirectChain("https://example.com/a", "https://example.com/b",
                                "https://example.com/c", "https://example.com/d",
                                "https://example.com/ziel").build(),
                Snapshots.config(check, Snapshots.facts())).getFirst();

        assertThat(finding.messageKey()).isEqualTo("finding.REDIRECT_CHAIN.tooManyHops");
        assertThat(finding.messageArgs()).containsExactly("4", "https://example.com/ziel");
        assertThat(finding.subjectKey()).isEqualTo("https://example.com/a");   // the entry point
        assertThat(check.messageKeys()).contains(finding.messageKey());
    }

    @Test
    void theHopLimitIsOverridablePerSite() {
        assertThat(check.evaluate(
                Snapshots.page("https://example.com/ziel")
                        .redirectChain("https://example.com/w1", "https://example.com/w2",
                                "https://example.com/w3", "https://example.com/ziel").build(),
                Snapshots.config(check, Snapshots.facts(), Map.of("maxHops", 2)))).hasSize(1);
    }

    @Test
    void aPageThatFailedWithARedirectLoopIsReportedAsALoop() {
        // Measured: this is how a loop actually arrives — Chromium refuses to finish the
        // navigation, so there is no chain to inspect, only the error.
        CheckFinding finding = check.evaluate(
                Snapshots.page("https://example.com/schleife")
                        .unreachable("net::ERR_TOO_MANY_REDIRECTS at https://example.com/schleife"),
                Snapshots.config(check, Snapshots.facts())).getFirst();

        assertThat(finding.messageKey()).isEqualTo("finding.REDIRECT_CHAIN.loop");
        assertThat(finding.messageArgs()).isEmpty();
    }

    @Test
    void aChainThatVisitsTheSameUrlTwiceIsALoopEvenIfTheBrowserEscapedIt() {
        CheckFinding finding = check.evaluate(
                Snapshots.page("https://example.com/ende")
                        .redirectChain("https://example.com/a", "https://example.com/b",
                                "https://example.com/a", "https://example.com/ende").build(),
                Snapshots.config(check, Snapshots.facts())).getFirst();

        assertThat(finding.messageKey()).isEqualTo("finding.REDIRECT_CHAIN.loop");
    }

    @Test
    void aPageReachedWithoutAnyRedirectIsNotReported() {
        assertThat(check.evaluate(Snapshots.page("https://example.com/").build(),
                Snapshots.config(check, Snapshots.facts()))).isEmpty();
    }

    @Test
    void anUnreachablePageWithSomeOtherReasonIsNotReported() {
        assertThat(check.evaluate(Snapshots.page("https://example.com/x").unreachable("Timeout"),
                Snapshots.config(check, Snapshots.facts()))).isEmpty();
    }
}
```

`ImageBrokenCheckTest.java`:

```java
package dev.hendrikhoemberg.webtesthelper.checks;

import dev.hendrikhoemberg.webtesthelper.model.CheckFinding;
import dev.hendrikhoemberg.webtesthelper.model.ImageOrigin;
import dev.hendrikhoemberg.webtesthelper.support.Snapshots;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ImageBrokenCheckTest {

    private final ImageBrokenCheck check = new ImageBrokenCheck();

    @Test
    void anImageThatNeverRenderedIsReported() {
        CheckFinding finding = check.evaluate(
                Snapshots.page("https://example.com/")
                        .image("https://example.com/logo.png", 200)
                        .image("https://example.com/fehlt.png", 0).build(),
                Snapshots.config(check, Snapshots.facts())).getFirst();

        assertThat(finding.subjectKey()).isEqualTo("https://example.com/fehlt.png");
        assertThat(finding.observedOn().value()).isEqualTo("https://example.com/");
        assertThat(finding.messageArgs()).containsExactly("https://example.com/fehlt.png");
        assertThat(check.messageKeys()).contains(finding.messageKey());
    }

    @Test
    void srcsetCandidatesAndCssBackgroundsCountAsImagesToo() {
        // Spec 7.1 names all three origins, because "the img tag loaded" is not the test — a
        // retina candidate or a hero background nobody ever decoded fails silently otherwise.
        assertThat(check.evaluate(
                Snapshots.page("https://example.com/leistungen")
                        .image("https://example.com/a.png", 0, ImageOrigin.SRCSET)
                        .image("https://example.com/b.png", 0, ImageOrigin.CSS_BACKGROUND).build(),
                Snapshots.config(check, Snapshots.facts())))
                .extracting(CheckFinding::subjectKey)
                .containsExactly("https://example.com/a.png", "https://example.com/b.png");
    }

    @Test
    void oneBrokenImageUsedTwiceOnAPageIsOneFinding() {
        // The same file in the header and the footer is one broken thing, not two. Occurrences
        // across pages are counted at materialisation (spec 6.2), not here.
        assertThat(check.evaluate(
                Snapshots.page("https://example.com/")
                        .image("https://example.com/fehlt.png", 0)
                        .image("https://example.com/fehlt.png", 0, ImageOrigin.CSS_BACKGROUND).build(),
                Snapshots.config(check, Snapshots.facts()))).hasSize(1);
    }

    @Test
    void anUnreachablePageReportsNoImages() {
        assertThat(check.evaluate(Snapshots.page("https://example.com/x").unreachable("Timeout"),
                Snapshots.config(check, Snapshots.facts()))).isEmpty();
    }
}
```

`MediaPlayableCheckTest.java`:

```java
package dev.hendrikhoemberg.webtesthelper.checks;

import dev.hendrikhoemberg.webtesthelper.model.CheckFinding;
import dev.hendrikhoemberg.webtesthelper.model.MediaKind;
import dev.hendrikhoemberg.webtesthelper.support.Snapshots;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MediaPlayableCheckTest {

    private final MediaPlayableCheck check = new MediaPlayableCheck();

    @Test
    void aVideoWhoseSourceFailedIsReportedAsAVideo() {
        // Spec 13.1: the sentence a colleague reads must say "Video", so the kind picks the key
        // rather than being interpolated as an enum name.
        CheckFinding finding = check.evaluate(
                Snapshots.page("https://example.com/medien")
                        .media(MediaKind.VIDEO, "https://example.com/fehlt.mp4", 0, 0.0,
                                "MEDIA_ERR_SRC_NOT_SUPPORTED").build(),
                Snapshots.config(check, Snapshots.facts())).getFirst();

        assertThat(finding.messageKey()).isEqualTo("finding.MEDIA_PLAYABLE.video");
        assertThat(finding.subjectKey()).isEqualTo("https://example.com/fehlt.mp4");
        assertThat(check.messageKeys()).contains(finding.messageKey());
    }

    @Test
    void anAudioElementWithoutMetadataIsReportedAsAudio() {
        assertThat(check.evaluate(
                Snapshots.page("https://example.com/medien")
                        .media(MediaKind.AUDIO, "https://example.com/ton.wav", 0, 0.0, null).build(),
                Snapshots.config(check, Snapshots.facts())))
                .singleElement()
                .satisfies(finding ->
                        assertThat(finding.messageKey()).isEqualTo("finding.MEDIA_PLAYABLE.audio"));
    }

    @Test
    void mediaThatLoadedItsMetadataAndHasADurationIsNotReported() {
        // Spec 7.1: readyState >= 1 and duration > 0 together, because either alone lies.
        assertThat(check.evaluate(
                Snapshots.page("https://example.com/medien")
                        .media(MediaKind.AUDIO, "https://example.com/ton.wav", 1, 0.5, null).build(),
                Snapshots.config(check, Snapshots.facts()))).isEmpty();
    }
}
```

`MixedContentCheckTest.java`:

```java
package dev.hendrikhoemberg.webtesthelper.checks;

import dev.hendrikhoemberg.webtesthelper.model.CheckFinding;
import dev.hendrikhoemberg.webtesthelper.model.MediaKind;
import dev.hendrikhoemberg.webtesthelper.support.Snapshots;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MixedContentCheckTest {

    private final MixedContentCheck check = new MixedContentCheck();

    @Test
    void anInsecureSubresourceOnASecurePageIsReported() {
        // Deviation D6: the fixture site is plain HTTP, so this check is proven here rather
        // than against it. That is the whole argument for pure functions over snapshots.
        assertThat(check.evaluate(
                Snapshots.page("https://example.com/")
                        .image("http://example.com/logo.png", 40)
                        .image("https://example.com/ok.png", 40)
                        .media(MediaKind.VIDEO, "http://example.com/film.mp4", 1, 9.0, null)
                        .frame("http://example.com/karte", false, 0).build(),
                Snapshots.config(check, Snapshots.facts())))
                .extracting(CheckFinding::subjectKey)
                .containsExactly("http://example.com/logo.png", "http://example.com/film.mp4",
                        "http://example.com/karte");
    }

    @Test
    void anInsecurePageCannotHaveMixedContent() {
        assertThat(check.evaluate(
                Snapshots.page("http://example.com/").image("http://example.com/logo.png", 40).build(),
                Snapshots.config(check, Snapshots.facts()))).isEmpty();
    }

    @Test
    void aPlainLinkToAnInsecurePageIsNotMixedContent() {
        // A link is a destination, not a subresource: nothing is loaded into this page, the
        // padlock survives, and reporting it would be a false positive on every partner link.
        assertThat(check.evaluate(
                Snapshots.page("https://example.com/").link("http://partner.example/", false).build(),
                Snapshots.config(check, Snapshots.facts()))).isEmpty();
    }

    @Test
    void theSameInsecureSubresourceTwiceIsOneFinding() {
        assertThat(check.evaluate(
                Snapshots.page("https://example.com/")
                        .image("http://example.com/logo.png", 40)
                        .image("http://example.com/logo.png", 40).build(),
                Snapshots.config(check, Snapshots.facts()))).hasSize(1);
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./mvnw test -DexcludedGroups=browser -Dtest='RedirectChainCheckTest,ImageBrokenCheckTest,MediaPlayableCheckTest,MixedContentCheckTest'`
Expected: FAIL — compilation errors, `cannot find symbol: class RedirectChainCheck`.

- [ ] **Step 3: Write the four checks**

`RedirectChainCheck.java`:

```java
package dev.hendrikhoemberg.webtesthelper.checks;

import dev.hendrikhoemberg.webtesthelper.model.CheckFinding;
import dev.hendrikhoemberg.webtesthelper.model.CheckType;
import dev.hendrikhoemberg.webtesthelper.model.Evidence;
import dev.hendrikhoemberg.webtesthelper.model.PageSnapshot;
import dev.hendrikhoemberg.webtesthelper.model.Severity;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * No loops and no long hop chains (spec 7.1).
 *
 * <p>{@code redirectChain} lists the requested URL first and the final URL last, so a size of 1
 * means no redirect at all and the hop count is {@code size - 1}. The default limit is three
 * because http to https to www to page is three legitimate hops on a real site; the fixture's
 * chain is exactly three, which is why its test drives the limit down per site instead.
 *
 * <p>A loop reaches this check as an <em>unreachable</em> page: measured, Chromium abandons the
 * navigation with {@code net::ERR_TOO_MANY_REDIRECTS} and there is no chain left to inspect.
 * This check owns those pages, and {@code PAGE_UNREACHABLE} steps aside, so one broken page
 * produces one finding that says what is actually wrong.
 */
public final class RedirectChainCheck implements PageCheck {

    static final String TOO_MANY_HOPS = "finding.REDIRECT_CHAIN.tooManyHops";
    static final String LOOP = "finding.REDIRECT_CHAIN.loop";
    static final int DEFAULT_MAX_HOPS = 3;

    @Override
    public CheckType type() {
        return CheckType.REDIRECT_CHAIN;
    }

    @Override
    public Severity defaultSeverity() {
        return Severity.WARN;
    }

    @Override
    public Set<String> messageKeys() {
        return Set.of(TOO_MANY_HOPS, LOOP);
    }

    @Override
    public List<CheckFinding> evaluate(PageSnapshot snapshot, CheckConfig config) {
        if (!snapshot.reachable()) {
            String reason = snapshot.unreachableReason() == null ? "" : snapshot.unreachableReason();
            return reason.contains(PageUnreachableCheck.REDIRECT_LOOP_MARKER)
                    ? List.of(finding(snapshot, config, LOOP, List.of(), reason))
                    : List.of();
        }
        List<String> chain = snapshot.redirectChain();
        int hops = chain.size() - 1;
        if (hops <= 0) {
            return List.of();
        }
        Set<String> seen = new HashSet<>();
        if (chain.stream().anyMatch(url -> !seen.add(url))) {
            return List.of(finding(snapshot, config, LOOP, List.of(), String.join(" -> ", chain)));
        }
        if (hops <= config.option("maxHops", DEFAULT_MAX_HOPS)) {
            return List.of();
        }
        return List.of(finding(snapshot, config, TOO_MANY_HOPS,
                List.of(String.valueOf(hops), chain.getLast()), String.join(" -> ", chain)));
    }

    /**
     * The subject is the URL that was <em>requested</em>, not the one that answered: that is the
     * address someone wrote into a link and the thing they would have to change.
     */
    private CheckFinding finding(PageSnapshot snapshot, CheckConfig config, String messageKey,
            List<String> args, String detail) {
        return new CheckFinding(type(), config.severity(), snapshot.redirectChain().getFirst(),
                snapshot.url(), messageKey, args,
                new Evidence(snapshot.screenshotPath(), null, null, detail, List.of()));
    }
}
```

`ImageBrokenCheck.java`:

```java
package dev.hendrikhoemberg.webtesthelper.checks;

import dev.hendrikhoemberg.webtesthelper.model.CheckFinding;
import dev.hendrikhoemberg.webtesthelper.model.CheckType;
import dev.hendrikhoemberg.webtesthelper.model.Evidence;
import dev.hendrikhoemberg.webtesthelper.model.ImageRef;
import dev.hendrikhoemberg.webtesthelper.model.NormalizedUrl;
import dev.hendrikhoemberg.webtesthelper.model.PageSnapshot;
import dev.hendrikhoemberg.webtesthelper.model.Severity;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Every image the page references actually renders (spec 7.1) — {@code naturalWidth > 0}, not
 * merely a 200 response. A server can return bytes that no decoder accepts, and the extraction
 * script already measures the srcset candidates and CSS backgrounds that the page itself never
 * decodes, so all three origins are answerable here.
 *
 * <p>One finding per broken file per page: the same missing logo in the header and the footer
 * is one broken thing. Counting it across pages, and promoting it to a site-wide finding, is
 * materialisation's job (spec 6.2).
 */
public final class ImageBrokenCheck implements PageCheck {

    static final String NOT_RENDERED = "finding.IMAGE_BROKEN.notRendered";

    @Override
    public CheckType type() {
        return CheckType.IMAGE_BROKEN;
    }

    @Override
    public Severity defaultSeverity() {
        return Severity.ERROR;
    }

    @Override
    public Set<String> messageKeys() {
        return Set.of(NOT_RENDERED);
    }

    @Override
    public List<CheckFinding> evaluate(PageSnapshot snapshot, CheckConfig config) {
        if (!snapshot.reachable()) {
            return List.of();
        }
        List<CheckFinding> findings = new ArrayList<>();
        Set<String> reported = new HashSet<>();
        for (ImageRef image : snapshot.images()) {
            NormalizedUrl target = image.target();
            // Without a normalised target there is nothing to name the finding with.
            if (target == null) {
                continue;
            }
            String subject = target.value();
            if (image.rendered() || !reported.add(subject)) {
                continue;
            }
            findings.add(new CheckFinding(type(), config.severity(), subject, snapshot.url(),
                    NOT_RENDERED, List.of(subject), Evidence.ofPage(snapshot)));
        }
        return findings;
    }
}
```

`MediaPlayableCheck.java`:

```java
package dev.hendrikhoemberg.webtesthelper.checks;

import dev.hendrikhoemberg.webtesthelper.model.CheckFinding;
import dev.hendrikhoemberg.webtesthelper.model.CheckType;
import dev.hendrikhoemberg.webtesthelper.model.Evidence;
import dev.hendrikhoemberg.webtesthelper.model.MediaKind;
import dev.hendrikhoemberg.webtesthelper.model.MediaRef;
import dev.hendrikhoemberg.webtesthelper.model.PageSnapshot;
import dev.hendrikhoemberg.webtesthelper.model.Severity;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Embedded video and audio load their metadata, have a duration and play without an error
 * (spec 7.1) — readyState &ge; 1, duration &gt; 0 and a null error code, exactly the three
 * clauses of {@link MediaRef#playable()}. Each condition alone lies: a source that 404s still
 * leaves an element on the page, an element that reports a readyState still plays nothing when
 * its duration is zero, and a nonzero duration still plays nothing once the element reports an
 * error.
 *
 * <p>The kind selects the message key rather than being interpolated into it, because
 * {@code VIDEO} is an internal identifier and spec 13.1 says none of those reach the screen.
 */
public final class MediaPlayableCheck implements PageCheck {

    static final String VIDEO = "finding.MEDIA_PLAYABLE.video";
    static final String AUDIO = "finding.MEDIA_PLAYABLE.audio";

    @Override
    public CheckType type() {
        return CheckType.MEDIA_PLAYABLE;
    }

    @Override
    public Severity defaultSeverity() {
        return Severity.ERROR;
    }

    @Override
    public Set<String> messageKeys() {
        return Set.of(VIDEO, AUDIO);
    }

    @Override
    public List<CheckFinding> evaluate(PageSnapshot snapshot, CheckConfig config) {
        if (!snapshot.reachable()) {
            return List.of();
        }
        List<CheckFinding> findings = new ArrayList<>();
        Set<String> reported = new HashSet<>();
        int anonymous = 0;
        for (MediaRef media : snapshot.media()) {
            if (media.playable()) {
                continue;
            }
            // An element with no source at all is still broken; name the page, not a blank.
            boolean hasSource = !media.sources().isEmpty();
            String subject = hasSource ? media.sources().getFirst().value() : snapshot.url().value();
            // Source-less elements all fall back to the page URL, so the dedupe key cannot be the
            // subject: a NUL-prefixed counter keeps each element its own finding while never
            // colliding with a real URL.
            String dedupe = hasSource ? subject : "\u0000no-source-" + anonymous++;
            if (!reported.add(dedupe)) {
                continue;
            }
            findings.add(new CheckFinding(type(), config.severity(), subject, snapshot.url(),
                    media.kind() == MediaKind.VIDEO ? VIDEO : AUDIO, List.of(subject),
                    new Evidence(snapshot.screenshotPath(), null, null, media.errorCode(),
                            List.of())));
        }
        return findings;
    }
}
```

`MixedContentCheck.java`:

```java
package dev.hendrikhoemberg.webtesthelper.checks;

import dev.hendrikhoemberg.webtesthelper.model.CheckFinding;
import dev.hendrikhoemberg.webtesthelper.model.CheckType;
import dev.hendrikhoemberg.webtesthelper.model.Evidence;
import dev.hendrikhoemberg.webtesthelper.model.NormalizedUrl;
import dev.hendrikhoemberg.webtesthelper.model.PageSnapshot;
import dev.hendrikhoemberg.webtesthelper.model.Severity;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * No http subresources on an https page (spec 7.1). The browser either blocks them, which
 * leaves a visible hole, or downgrades the padlock — both are defects a client notices.
 *
 * <p>Links are deliberately not subresources. A link is a destination; nothing is loaded into
 * this page and the padlock survives, so reporting one would be a false positive on every
 * partner link that has not moved to https yet.
 *
 * <p>Deviation D6: the fixture site is plain HTTP, so this check is proven from hand-built
 * snapshots. That costs nothing precisely because a page check is a pure function (spec 5.2).
 */
public final class MixedContentCheck implements PageCheck {

    static final String INSECURE_SUBRESOURCE = "finding.MIXED_CONTENT.insecureSubresource";

    @Override
    public CheckType type() {
        return CheckType.MIXED_CONTENT;
    }

    @Override
    public Severity defaultSeverity() {
        return Severity.WARN;
    }

    @Override
    public Set<String> messageKeys() {
        return Set.of(INSECURE_SUBRESOURCE);
    }

    @Override
    public List<CheckFinding> evaluate(PageSnapshot snapshot, CheckConfig config) {
        if (!snapshot.reachable() || !snapshot.isSecure()) {
            return List.of();
        }
        Set<String> insecure = new LinkedHashSet<>();
        snapshot.images().forEach(image -> collect(image.target(), insecure));
        snapshot.media().forEach(media -> media.sources().forEach(source -> collect(source, insecure)));
        snapshot.frames().forEach(frame -> collect(frame.src(), insecure));

        List<CheckFinding> findings = new ArrayList<>();
        for (String subject : insecure) {
            findings.add(new CheckFinding(type(), config.severity(), subject, snapshot.url(),
                    INSECURE_SUBRESOURCE, List.of(subject), Evidence.ofPage(snapshot)));
        }
        return findings;
    }

    private static void collect(NormalizedUrl url, Set<String> insecure) {
        if (url != null && !url.isSecure()) {
            insecure.add(url.value());
        }
    }
}
```

- [ ] **Step 4: Register them and write their German**

Replace the `standard()` list in `CheckRegistry.java`:

```java
    public static CheckRegistry standard() {
        return new CheckRegistry(
                List.of(new PageUnreachableCheck(),
                        new PageStatusCheck(),
                        new RedirectChainCheck(),
                        new ImageBrokenCheck(),
                        new MediaPlayableCheck(),
                        new MixedContentCheck()),
                List.of());     // Plan 3b adds TLS_CERT, HREFLANG and SITEMAP_CONSISTENCY
    }
```

Append to `src/main/resources/messages.properties`:

```properties

check.REDIRECT_CHAIN.title=Weiterleitungen
check.REDIRECT_CHAIN.description=Prüft, ob Weiterleitungen ans Ziel führen und nicht über unnötig viele Zwischenstationen laufen.
check.REDIRECT_CHAIN.remediation=Weiterleitung so einrichten, dass sie direkt auf die Zielseite zeigt, und die Verweise im Inhalt auf die Zieladresse umstellen.
finding.REDIRECT_CHAIN.tooManyHops=Der Aufruf läuft über {0} Weiterleitungen, bis er bei {1} ankommt.
finding.REDIRECT_CHAIN.loop=Die Weiterleitungen verweisen im Kreis aufeinander, die Seite wird nie ausgeliefert.

check.IMAGE_BROKEN.title=Fehlende Bilder
check.IMAGE_BROKEN.description=Prüft, ob jedes eingebundene Bild tatsächlich angezeigt wird. Ein Bild kann eingebunden sein und trotzdem leer bleiben.
check.IMAGE_BROKEN.remediation=Bilddatei hochladen oder den Verweis im Inhalt auf die richtige Datei korrigieren.
finding.IMAGE_BROKEN.notRendered=Das Bild {0} bleibt leer.

check.MEDIA_PLAYABLE.title=Video und Ton
check.MEDIA_PLAYABLE.description=Prüft, ob eingebundene Videos und Tonspuren abspielbar sind.
check.MEDIA_PLAYABLE.remediation=Mediendatei prüfen: Ist sie vorhanden, ist das Format für Browser geeignet, stimmt der Verweis darauf?
finding.MEDIA_PLAYABLE.video=Das eingebundene Video {0} lässt sich nicht abspielen.
finding.MEDIA_PLAYABLE.audio=Die eingebundene Tonspur {0} lässt sich nicht abspielen.

check.MIXED_CONTENT.title=Unsichere Inhalte auf sicherer Seite
check.MIXED_CONTENT.description=Prüft, ob eine verschlüsselt ausgelieferte Seite Bilder oder andere Bestandteile unverschlüsselt nachlädt.
check.MIXED_CONTENT.remediation=Verweis auf die verschlüsselte Adresse umstellen. Sonst blockiert der Browser diese Bestandteile und das Schloss-Symbol verschwindet.
finding.MIXED_CONTENT.insecureSubresource=Die Seite lädt {0} unverschlüsselt nach.
```

- [ ] **Step 5: Run the tests to verify they pass**

Run: `./mvnw test -DexcludedGroups=browser -Dtest='RedirectChainCheckTest,ImageBrokenCheckTest,MediaPlayableCheckTest,MixedContentCheckTest,CheckDocumentationTest,CheckRegistryTest'`
Expected: PASS — `Tests run: 27, Failures: 0, Errors: 0`

If `theBundleCarriesNoKeysForChecksThatDoNotExist` fails, a key was typed in the properties file
that no check declares — compare the reported orphan against the check's constants rather than
deleting the assertion.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/dev/hendrikhoemberg/webtesthelper/checks src/main/resources/messages.properties src/test/java/dev/hendrikhoemberg/webtesthelper/checks
git commit -m "feat(checks): add the redirect, image, media and mixed-content checks"
```

**Post-review fixes (landed in `fix(checks): report each source-less media element and pin the loop marker`):** `MediaPlayableCheck` reports one finding per source-less element (NUL-prefixed counter dedupe key — see the patched code); `ImageBrokenCheck` guards a null target; `MediaPlayableCheck`'s javadoc names the three `playable()` clauses; `PageNavigatorTest`'s redirect-loop test additionally asserts the exact `ERR_TOO_MANY_REDIRECTS` marker so the REDIRECT_CHAIN/PAGE_UNREACHABLE ownership seam cannot rot; `Snapshots` gained `media(MediaKind)` (empty sources), `image(src, w, h, origin)` and `image(ImageRef)` overloads; new tests: two source-less elements → two findings, same broken source twice → one, mixed-content unreachable → none, naturalWidth>0/naturalHeight==0 → broken, null image target → none.

---

### Task 3: The two checks that read the browser console

`IFRAME_EMBED` is the flagship of §7.1's "three implementations where the obvious approach is
the useless one": *the iframe loaded* passes a grey Maps tile with a billing error behind it.
`CONSOLE_ERRORS` is the check that ships disabled, and its ignore list is what makes enabling
it survivable.

**Files:**
- Create: `src/main/java/dev/hendrikhoemberg/webtesthelper/checks/IframeEmbedCheck.java`
- Create: `src/main/java/dev/hendrikhoemberg/webtesthelper/checks/ConsoleErrorsCheck.java`
- Modify: `src/main/java/dev/hendrikhoemberg/webtesthelper/checks/CheckRegistry.java`
- Modify: `src/main/resources/messages.properties`
- Test: `src/test/java/dev/hendrikhoemberg/webtesthelper/checks/IframeEmbedCheckTest.java`
- Test: `src/test/java/dev/hendrikhoemberg/webtesthelper/checks/ConsoleErrorsCheckTest.java`

**Interfaces:**
- Consumes: `PageCheck`, `CheckConfig.optionList(String)`, `model.FrameRef`,
  `model.FailedRequest`, `model.PageSnapshot.errors()`, `model.UrlNormalizer.key(String)`.
- Produces: `IframeEmbedCheck`, `ConsoleErrorsCheck`. `CheckRegistry.standard()` now covers
  eight `CheckType`s — everything in the catalog except the five Plan 3b implements.

- [ ] **Step 1: Write the failing tests**

`IframeEmbedCheckTest.java`:

```java
package dev.hendrikhoemberg.webtesthelper.checks;

import dev.hendrikhoemberg.webtesthelper.model.CheckFinding;
import dev.hendrikhoemberg.webtesthelper.support.Snapshots;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class IframeEmbedCheckTest {

    private static final String MAPS_ERROR =
            "Google Maps JavaScript API error: ApiNotActivatedMapError";

    private final IframeEmbedCheck check = new IframeEmbedCheck();

    @Test
    void aFrameTheBrowserRefusedToDisplayIsReportedAsBlocked() {
        // Measured against the fixture: an X-Frame-Options refusal shows up as a failed
        // document request, and nowhere else that can be tied back to the frame.
        CheckFinding finding = check.evaluate(
                Snapshots.page("https://example.com/kontakt")
                        .frame("https://bewertungen.example/widget", false, 0)
                        .blockedDocument("https://bewertungen.example/widget").build(),
                Snapshots.config(check, Snapshots.facts())).getFirst();

        assertThat(finding.messageKey()).isEqualTo("finding.IFRAME_EMBED.blocked");
        assertThat(finding.subjectKey()).isEqualTo("https://bewertungen.example/widget");
        assertThat(check.messageKeys()).contains(finding.messageKey());
    }

    @Test
    void aMapsEmbedWithAProviderErrorIsReportedEvenThoughItLoaded() {
        // Spec 7.1: the real failure is billing or an API key, and "the iframe loaded" passes
        // a grey tile with a watermark. The console is where the truth is.
        CheckFinding finding = check.evaluate(
                Snapshots.page("https://example.com/kontakt")
                        .frame("https://www.google.com/maps/embed/v1/place", false, 0)
                        .consoleError(MAPS_ERROR).build(),
                Snapshots.config(check, Snapshots.facts())).getFirst();

        assertThat(finding.messageKey()).isEqualTo("finding.IFRAME_EMBED.maps");
        assertThat(finding.messageArgs()).containsExactly("ApiNotActivatedMapError");
        assertThat(finding.evidence().consoleExcerpt()).contains(MAPS_ERROR);
    }

    @Test
    void aHealthyMapsEmbedIsNotReported() {
        assertThat(check.evaluate(
                Snapshots.page("https://example.com/kontakt")
                        .frame("https://www.google.com/maps/embed/v1/place", false, 0).build(),
                Snapshots.config(check, Snapshots.facts()))).isEmpty();
    }

    @Test
    void aCrossOriginFrameIsNeverReportedMerelyForBeingUnreadable() {
        // Deviation D17. Measured: a healthy cross-origin embed and a blocked one both report
        // textLength 0, because the parent cannot read the document either way. Reporting on
        // that would fire on every YouTube embed on every page — the false positive spec 8
        // exists to prevent.
        assertThat(check.evaluate(
                Snapshots.page("https://example.com/kontakt")
                        .frame("https://www.youtube.com/embed/abc", false, 0).build(),
                Snapshots.config(check, Snapshots.facts()))).isEmpty();
    }

    @Test
    void aSameOriginFrameWithNoContentIsReportedAsEmpty() {
        assertThat(check.evaluate(
                Snapshots.page("https://example.com/kontakt")
                        .frame("https://example.com/teil/anfahrt", true, 0).build(),
                Snapshots.config(check, Snapshots.facts())))
                .singleElement()
                .satisfies(finding ->
                        assertThat(finding.messageKey()).isEqualTo("finding.IFRAME_EMBED.empty"));
    }

    @Test
    void aSameOriginFrameWithContentIsNotReported() {
        assertThat(check.evaluate(
                Snapshots.page("https://example.com/kontakt")
                        .frame("https://example.com/teil/anfahrt", true, 240).build(),
                Snapshots.config(check, Snapshots.facts()))).isEmpty();
    }

    @Test
    void aFrameThatIsBothBlockedAndEmptyIsReportedOnce() {
        assertThat(check.evaluate(
                Snapshots.page("https://example.com/kontakt")
                        .frame("https://example.com/teil/anfahrt", true, 0)
                        .blockedDocument("https://example.com/teil/anfahrt").build(),
                Snapshots.config(check, Snapshots.facts())))
                .singleElement()
                .satisfies(finding ->
                        assertThat(finding.messageKey()).isEqualTo("finding.IFRAME_EMBED.blocked"));
    }
}
```

`ConsoleErrorsCheckTest.java`:

```java
package dev.hendrikhoemberg.webtesthelper.checks;

import dev.hendrikhoemberg.webtesthelper.model.CheckFinding;
import dev.hendrikhoemberg.webtesthelper.model.Severity;
import dev.hendrikhoemberg.webtesthelper.support.Snapshots;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ConsoleErrorsCheckTest {

    private final ConsoleErrorsCheck check = new ConsoleErrorsCheck();

    @Test
    void anUncaughtScriptErrorIsReported() {
        CheckFinding finding = check.evaluate(
                Snapshots.page("https://example.com/")
                        .consoleError("Uncaught TypeError: kunde is not defined").build(),
                Snapshots.config(check, Snapshots.facts())).getFirst();

        assertThat(finding.severity()).isEqualTo(Severity.INFO);
        assertThat(finding.subjectKey()).isEqualTo("Uncaught TypeError: kunde is not defined");
        assertThat(check.messageKeys()).contains(finding.messageKey());
    }

    @Test
    void aFailedSubresourceIsNotAScriptError() {
        // "Failed to load resource" is what a missing image logs. IMAGE_BROKEN and DEAD_LINK
        // already report those with the URL attached; repeating them here is pure noise.
        assertThat(check.evaluate(
                Snapshots.page("https://example.com/")
                        .consoleError("Failed to load resource: the server responded with a status of 404")
                        .build(),
                Snapshots.config(check, Snapshots.facts()))).isEmpty();
    }

    @Test
    void aConfiguredIgnoreSubstringSilencesAMessageRegardlessOfCase() {
        // Deviation D16: substrings, not the crawler's anchored URL globs. What a colleague
        // types into this list is a fragment of a message, not a path pattern.
        assertThat(check.evaluate(
                Snapshots.page("https://example.com/")
                        .consoleError("Cookiebot: consent not given").build(),
                Snapshots.config(check, Snapshots.facts(), Map.of("ignorePatterns",
                        List.of("cookiebot"))))).isEmpty();
    }

    @Test
    void theSameMessageTwiceOnOnePageIsOneFinding() {
        assertThat(check.evaluate(
                Snapshots.page("https://example.com/")
                        .consoleError("Uncaught TypeError: x")
                        .consoleError("Uncaught TypeError: x").build(),
                Snapshots.config(check, Snapshots.facts()))).hasSize(1);
    }

    @Test
    void aVeryLongMessageIsTruncatedSoTheSubjectStaysAUsableKey() {
        String long1 = "Uncaught Error: " + "x".repeat(500);
        CheckFinding finding = check.evaluate(
                Snapshots.page("https://example.com/").consoleError(long1).build(),
                Snapshots.config(check, Snapshots.facts())).getFirst();

        assertThat(finding.subjectKey()).hasSize(200);
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./mvnw test -DexcludedGroups=browser -Dtest='IframeEmbedCheckTest,ConsoleErrorsCheckTest'`
Expected: FAIL — compilation errors, `cannot find symbol: class IframeEmbedCheck`.

- [ ] **Step 3: Write the two checks**

`IframeEmbedCheck.java`:

```java
package dev.hendrikhoemberg.webtesthelper.checks;

import dev.hendrikhoemberg.webtesthelper.model.CheckFinding;
import dev.hendrikhoemberg.webtesthelper.model.CheckType;
import dev.hendrikhoemberg.webtesthelper.model.ConsoleMessage;
import dev.hendrikhoemberg.webtesthelper.model.Evidence;
import dev.hendrikhoemberg.webtesthelper.model.FailedRequest;
import dev.hendrikhoemberg.webtesthelper.model.FrameRef;
import dev.hendrikhoemberg.webtesthelper.model.NormalizedUrl;
import dev.hendrikhoemberg.webtesthelper.model.PageSnapshot;
import dev.hendrikhoemberg.webtesthelper.model.Severity;
import dev.hendrikhoemberg.webtesthelper.model.UrlNormalizer;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * An embedded frame is not blocked, and shows something (spec 7.1).
 *
 * <p>Three signals, applied per frame in the order they can be trusted:
 *
 * <ol>
 *   <li><strong>Blocked.</strong> The snapshot carries a failed {@code document} request for the
 *       frame's URL whose failure text is {@code net::ERR_BLOCKED_BY_RESPONSE} — the signal
 *       measured against the fixture for an {@code X-Frame-Options: DENY} frame. A failed
 *       document request without that text (e.g. a 404) is not a refusal and is left to plan 3b's
 *       DEAD_LINK; the console message Chromium also writes names the <em>parent</em> page, so it
 *       cannot be tied back to a frame and is not used. Known limitation (recorded for plan 3b):
 *       the failed request URL is compared against the frame's declared {@code src}, so a frame
 *       whose document <em>redirects</em> before being refused is missed — the failed URL is the
 *       post-redirect one and matches nothing.
 *   <li><strong>Maps.</strong> Spec 7.1's named case: the real failure is billing or an API key,
 *       and "the iframe loaded" passes a grey tile with a <em>for development purposes only</em>
 *       watermark. The provider's error code in the console is the signal.
 *   <li><strong>Empty.</strong> A same-origin frame whose document has no text at all.
 * </ol>
 *
 * <p>Deviation D17: emptiness is never reported for a cross-origin frame. Measured, a healthy
 * cross-origin embed and a blocked one both report {@code contentTextLength = 0}, because the
 * parent cannot read either document — so the rule would fire on every healthy YouTube and Maps
 * embed on every page.
 */
public final class IframeEmbedCheck implements PageCheck {

    static final String BLOCKED = "finding.IFRAME_EMBED.blocked";
    static final String MAPS = "finding.IFRAME_EMBED.maps";
    static final String EMPTY = "finding.IFRAME_EMBED.empty";

    /** The codes Google Maps writes to the console when a key or the billing account is wrong. */
    static final List<String> MAPS_ERROR_CODES = List.of(
            "ApiNotActivatedMapError", "BillingNotEnabledMapError", "InvalidKeyMapError",
            "MissingKeyMapError", "ExpiredKeyMapError", "RefererNotAllowedMapError",
            "DeletedApiProjectMapError", "OverQuotaMapError");

    @Override
    public CheckType type() {
        return CheckType.IFRAME_EMBED;
    }

    @Override
    public Severity defaultSeverity() {
        return Severity.ERROR;
    }

    @Override
    public Set<String> messageKeys() {
        return Set.of(BLOCKED, MAPS, EMPTY);
    }

    @Override
    public List<CheckFinding> evaluate(PageSnapshot snapshot, CheckConfig config) {
        if (!snapshot.reachable()) {
            return List.of();
        }
        Set<String> blocked = snapshot.failedRequests().stream()
                .filter(request -> "document".equals(request.resourceType()))
                .filter(request -> request.failureText() != null
                        && request.failureText().contains("ERR_BLOCKED_BY_RESPONSE"))
                .map(FailedRequest::url)
                .map(UrlNormalizer::key)
                .flatMap(Optional::stream)
                .collect(Collectors.toSet());
        List<String> mapsErrors = snapshot.errors().stream()
                .map(ConsoleMessage::text)
                .filter(text -> text != null && mapsCodeIn(text) != null)
                .toList();

        // A console error whose location is a maps frame's src is owned by that frame, not by
        // every maps embed on the page. Only when no error's location lines up with any maps
        // frame do we fall back to the page-global set (the fixture writes the page URL as the
        // location, which matches no frame).
        Map<String, List<String>> perFrame = new HashMap<>();
        boolean anyLocationMatch = false;
        for (FrameRef frame : snapshot.frames()) {
            String frameSubject = frame.src().value();
            if (!isMapsEmbed(frame.src()) || blocked.contains(frameSubject)) {
                continue;
            }
            String frameKey = UrlNormalizer.key(frameSubject).orElse(null);
            List<String> matched = new ArrayList<>();
            for (ConsoleMessage error : snapshot.errors()) {
                if (mapsCodeIn(error.text()) == null) {
                    continue;
                }
                String locationKey = UrlNormalizer.key(error.location()).orElse(null);
                if (locationKey != null && locationKey.equals(frameKey)) {
                    matched.add(error.text());
                }
            }
            if (!matched.isEmpty()) {
                anyLocationMatch = true;
                perFrame.put(frameSubject, matched);
            }
        }

        List<CheckFinding> findings = new ArrayList<>();
        Set<String> reported = new HashSet<>();
        for (FrameRef frame : snapshot.frames()) {
            String subject = frame.src().value();
            if (!reported.add(subject)) {
                continue;
            }
            if (blocked.contains(subject)) {
                findings.add(finding(snapshot, config, BLOCKED, subject, List.of(subject),
                        List.of()));
            } else if (isMapsEmbed(frame.src())) {
                List<String> errors = anyLocationMatch
                        ? perFrame.getOrDefault(subject, List.of())
                        : mapsErrors;
                if (!errors.isEmpty()) {
                    findings.add(finding(snapshot, config, MAPS, subject,
                            List.of(mapsCodeIn(errors.getFirst())), errors));
                }
            } else if (frame.sameOrigin() && frame.contentTextLength() == 0) {
                findings.add(finding(snapshot, config, EMPTY, subject, List.of(subject),
                        List.of()));
            }
        }
        return findings;
    }

    private CheckFinding finding(PageSnapshot snapshot, CheckConfig config, String messageKey,
            String subject, List<String> args, List<String> console) {
        return new CheckFinding(type(), config.severity(), subject, snapshot.url(), messageKey,
                args, new Evidence(snapshot.screenshotPath(), null, null, null, console));
    }

    /**
     * The provider's code inside a longer console line, or null when there is none. Case
     * matters to no one, so the match is case-insensitive; the canonical code is returned.
     */
    private static String mapsCodeIn(String text) {
        String lower = text.toLowerCase(Locale.ROOT);
        return MAPS_ERROR_CODES.stream()
                .filter(code -> lower.contains(code.toLowerCase(Locale.ROOT)))
                .findFirst()
                .orElse(null);
    }

    private static boolean isMapsEmbed(NormalizedUrl src) {
        String path = src.path().toLowerCase(Locale.ROOT);
        return path.contains("/maps/embed")
                || (src.registrableHost().contains("google") && path.contains("/maps"));
    }
}
```

`ConsoleErrorsCheck.java`:

```java
package dev.hendrikhoemberg.webtesthelper.checks;

import dev.hendrikhoemberg.webtesthelper.model.CheckFinding;
import dev.hendrikhoemberg.webtesthelper.model.CheckType;
import dev.hendrikhoemberg.webtesthelper.model.ConsoleMessage;
import dev.hendrikhoemberg.webtesthelper.model.Evidence;
import dev.hendrikhoemberg.webtesthelper.model.PageSnapshot;
import dev.hendrikhoemberg.webtesthelper.model.Severity;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Uncaught JavaScript errors — <strong>off by default</strong> (spec 7.1). Real sites throw
 * console errors constantly: third-party scripts, tracking pixels, consent tools. Enabled by
 * default this check would make the very first report mostly noise, which is the one thing this
 * design cannot afford.
 *
 * <p>Two things make it survivable once a site does enable it. Messages about a subresource
 * that failed to load are dropped outright — {@code IMAGE_BROKEN} and {@code DEAD_LINK} already
 * report those with the URL attached, so repeating them here is duplication, not coverage. And
 * the site's own ignore list, {@code {"ignorePatterns": ["cookiebot", "gtm.js"]}}, is matched as
 * case-insensitive substrings (deviation D16): what a colleague types is a fragment of a
 * message, not a path pattern.
 *
 * <p>With this check enabled, a Maps billing or key failure reports twice — {@code
 * IFRAME_EMBED.maps} names the embed, {@code CONSOLE_ERRORS.uncaught} the raw provider error.
 * That is deliberate: the two checks answer different questions, and the second is opt-in.
 */
public final class ConsoleErrorsCheck implements PageCheck {

    static final String UNCAUGHT = "finding.CONSOLE_ERRORS.uncaught";
    static final int MAX_SUBJECT_LENGTH = 200;

    /** Owned by other checks, which can also say which file is missing. */
    private static final List<String> ALWAYS_IGNORED = List.of("failed to load resource");

    @Override
    public CheckType type() {
        return CheckType.CONSOLE_ERRORS;
    }

    @Override
    public Severity defaultSeverity() {
        return Severity.INFO;
    }

    @Override
    public Set<String> messageKeys() {
        return Set.of(UNCAUGHT);
    }

    @Override
    public List<CheckFinding> evaluate(PageSnapshot snapshot, CheckConfig config) {
        if (!snapshot.reachable()) {
            return List.of();
        }
        List<String> ignored = new ArrayList<>(ALWAYS_IGNORED);
        config.optionList("ignorePatterns").forEach(
                pattern -> ignored.add(pattern.toLowerCase(Locale.ROOT)));

        List<CheckFinding> findings = new ArrayList<>();
        Set<String> reported = new HashSet<>();
        for (ConsoleMessage message : snapshot.errors()) {
            String subject = normalise(message.text());
            if (subject.isEmpty() || !reported.add(subject)) {
                continue;
            }
            String lower = subject.toLowerCase(Locale.ROOT);
            if (ignored.stream().anyMatch(lower::contains)) {
                continue;
            }
            findings.add(new CheckFinding(type(), config.severity(), subject, snapshot.url(),
                    UNCAUGHT, List.of(subject),
                    new Evidence(snapshot.screenshotPath(), null, null, message.location(),
                            List.of(message.text()))));
        }
        return findings;
    }

    /**
     * The subject key doubles as the fingerprint input (spec 6.2), so it has to be stable
     * across runs: collapse the whitespace a stack trace brings and cap the length. The cap is
     * code-point-aware so it can never cut a surrogate pair in half.
     */
    private static String normalise(String text) {
        if (text == null) {
            return "";
        }
        String collapsed = text.replaceAll("\\s+", " ").trim();
        if (collapsed.codePointCount(0, collapsed.length()) <= MAX_SUBJECT_LENGTH) {
            return collapsed;
        }
        return collapsed.substring(0, collapsed.offsetByCodePoints(0, MAX_SUBJECT_LENGTH));
    }
}
```

- [ ] **Step 4: Register them and write their German**

Replace the `standard()` list in `CheckRegistry.java`:

```java
    public static CheckRegistry standard() {
        return new CheckRegistry(
                List.of(new PageUnreachableCheck(),
                        new PageStatusCheck(),
                        new RedirectChainCheck(),
                        new ImageBrokenCheck(),
                        new MediaPlayableCheck(),
                        new MixedContentCheck(),
                        new IframeEmbedCheck(),
                        new ConsoleErrorsCheck()),
                List.of());     // Plan 3b adds TLS_CERT, HREFLANG and SITEMAP_CONSISTENCY
    }
```

Append to `src/main/resources/messages.properties`:

```properties

check.IFRAME_EMBED.title=Eingebettete Inhalte
check.IFRAME_EMBED.description=Prüft eingebettete Fremdinhalte wie Karten oder Bewertungen darauf, ob sie angezeigt werden statt leer oder blockiert zu bleiben.
check.IFRAME_EMBED.remediation=Bei Karten den Abrechnungsstatus und den Schlüssel beim Anbieter prüfen. Bei blockierten Inhalten erlaubt der Anbieter die Einbettung nicht; Einbettungscode dort neu holen.
finding.IFRAME_EMBED.blocked=Der eingebettete Inhalt {0} wird vom Anbieter nicht zur Einbettung freigegeben und bleibt leer.
finding.IFRAME_EMBED.maps=Die eingebettete Karte zeigt nur eine graue Fläche. Der Kartenanbieter lehnt den Zugriff ab und meldet dazu: {0}
finding.IFRAME_EMBED.empty=Der eingebettete Inhalt {0} lädt, zeigt aber nichts an.

check.CONSOLE_ERRORS.title=Fehlermeldungen im Browser
check.CONSOLE_ERRORS.description=Prüft, ob die Seite beim Laden Programmfehler meldet. Standardmässig abgeschaltet, weil eingebundene Fremdskripte laufend harmlose Meldungen erzeugen.
check.CONSOLE_ERRORS.remediation=Meldung an die Entwicklung weitergeben. Wiederkehrende harmlose Meldungen in die Ausnahmeliste dieser Prüfung aufnehmen.
finding.CONSOLE_ERRORS.uncaught=Beim Laden der Seite meldet der Browser einen Fehler: {0}
```

- [ ] **Step 5: Run the tests to verify they pass**

Run: `./mvnw test -DexcludedGroups=browser`
Expected: PASS — the whole browser-free suite, with 12 new tests in the two new classes.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/dev/hendrikhoemberg/webtesthelper/checks src/main/resources/messages.properties src/test/java/dev/hendrikhoemberg/webtesthelper/checks
git commit -m "feat(checks): add the iframe embed and console error checks"
```

**Post-review fixes (landed in `fix(checks): attribute maps errors to their frame and make subject truncation code-point safe`, then `fix(checks): report a frame as blocked only on the measured refusal signal`):** maps errors are attributed to the frame whose `src` matches the console error's location, with the page-global fallback only when no location matches any maps frame (see the patched code); `mapsCodeIn` is case-insensitive; the blocked lookup requires `ERR_BLOCKED_BY_RESPONSE` in the failure text, so a frame whose document 404s is left to 3b's `DEAD_LINK`; `normalise()` truncates code-point-aware; `Snapshots` gained `consoleError(String, String)` and `failedDocument(String, Integer)`; new tests: per-frame attribution (two maps frames → one finding), the no-match fallback, a 404'd document not reported blocked, the ==200 cap boundary, whitespace collapse below the cap, and surrogate-pair truncation.

---

### Task 4: The check pass in the run pipeline

Everything so far is a library. This task makes a run use it: the engine that decides which
checks apply to which page, the two Spring beans that expose it, the pass inserted between the
crawl and the outcome write, and the acceptance test that proves the eight checks find the
eight failure modes the fixture site was built to contain.

**Findings are counted, not stored.** Plan 4 owns materialisation — fingerprinting, site-wide
promotion, occurrences and the coverage-scoped diff (§6.2) — and inventing a findings table here
would be a schema Plan 4 immediately replaces. What lands on the run row is `findings_total`,
which is real, visible, and enough to prove the pass ran.

**Files:**
- Create: `src/main/java/dev/hendrikhoemberg/webtesthelper/checks/CheckEngine.java`
- Create: `src/main/java/dev/hendrikhoemberg/webtesthelper/checks/CheckEvaluationException.java`
- Create: `src/main/java/dev/hendrikhoemberg/webtesthelper/runner/CheckEngineConfiguration.java`
- Modify: `src/main/java/dev/hendrikhoemberg/webtesthelper/runner/package-info.java`
- Modify: `src/main/java/dev/hendrikhoemberg/webtesthelper/runner/CrawlRunExecutor.java`
- Modify: `src/main/java/dev/hendrikhoemberg/webtesthelper/runner/persistence/RunResultJdbcRepository.java`
- Test: `src/test/java/dev/hendrikhoemberg/webtesthelper/checks/CheckEngineTest.java`
- Test: `src/test/java/dev/hendrikhoemberg/webtesthelper/checks/PageCheckAcceptanceTest.java`
- Modify: `src/test/java/dev/hendrikhoemberg/webtesthelper/checks/CheckRegistryTest.java` (the coverage guard)
- Modify: `src/test/java/dev/hendrikhoemberg/webtesthelper/runner/CrawlRunExecutorTest.java`

**Interfaces:**
- Consumes: `CheckRegistry`, `PageCheck`, `CheckConfig`, `model.RunFacts`, `model.SiteContext`,
  `model.RunScope.checkTypes()`, `crawler.CrawlService`, `catalog.SiteService.contextFor(long)`,
  `runner.persistence.RunResultJdbcRepository`.
- Produces:
  - `checks.CheckEngine(CheckRegistry)` — `evaluatePage(PageSnapshot, SiteContext, RunFacts)`,
    `evaluateRun(RunSnapshots, SiteContext, RunFacts)`, both returning `List<CheckFinding>`
  - `checks.CheckEvaluationException(CheckType, String url, Throwable)`
  - `RunResultJdbcRepository.saveCrawlOutcome(long, CrawlResult, List<String>, SoftNotFoundProbe, int)`
    — the fifth parameter is new and Plan 3b keeps it

- [ ] **Step 1: Write the failing tests**

`CheckEngineTest.java` — no browser, no database:

```java
package dev.hendrikhoemberg.webtesthelper.checks;

import dev.hendrikhoemberg.webtesthelper.model.CheckFinding;
import dev.hendrikhoemberg.webtesthelper.model.CheckSetting;
import dev.hendrikhoemberg.webtesthelper.model.CheckType;
import dev.hendrikhoemberg.webtesthelper.model.CrawlBudget;
import dev.hendrikhoemberg.webtesthelper.model.PageSnapshot;
import dev.hendrikhoemberg.webtesthelper.model.RunFacts;
import dev.hendrikhoemberg.webtesthelper.model.RunScope;
import dev.hendrikhoemberg.webtesthelper.model.RunSnapshots;
import dev.hendrikhoemberg.webtesthelper.model.Severity;
import dev.hendrikhoemberg.webtesthelper.model.SiteContext;
import dev.hendrikhoemberg.webtesthelper.model.SoftNotFoundProbe;
import dev.hendrikhoemberg.webtesthelper.support.Snapshots;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CheckEngineTest {

    private final CheckEngine engine = new CheckEngine(CheckRegistry.standard());

    private static SiteContext site(Map<CheckType, CheckSetting> settings) {
        return new SiteContext(1L, "Beispiel", Snapshots.url("https://example.com/"),
                CrawlBudget.DEFAULT, List.of(), List.of(), List.of(), true, null, settings);
    }

    private static Map<CheckType, CheckSetting> allEnabled() {
        Map<CheckType, CheckSetting> settings = new EnumMap<>(CheckType.class);
        for (CheckType type : CheckType.values()) {
            settings.put(type, CheckSetting.defaultEnabled());
        }
        return settings;
    }

    private static RunFacts facts(RunScope scope) {
        return new RunFacts(1L, scope, Instant.EPOCH, SoftNotFoundProbe.NONE);
    }

    private static PageSnapshot brokenPage() {
        return Snapshots.page("https://example.com/x").status(500)
                .image("https://example.com/fehlt.png", 0).build();
    }

    @Test
    void everyEnabledCheckContributesToTheSamePage() {
        List<CheckFinding> findings =
                engine.evaluatePage(brokenPage(), site(allEnabled()), facts(RunScope.FULL));

        assertThat(findings).extracting(CheckFinding::type)
                .containsExactlyInAnyOrder(CheckType.PAGE_STATUS, CheckType.IMAGE_BROKEN);
    }

    @Test
    void aCheckTheSiteDisabledDoesNotRun() {
        Map<CheckType, CheckSetting> settings = allEnabled();
        settings.put(CheckType.IMAGE_BROKEN, CheckSetting.defaultDisabled());

        assertThat(engine.evaluatePage(brokenPage(), site(settings), facts(RunScope.FULL)))
                .extracting(CheckFinding::type).containsExactly(CheckType.PAGE_STATUS);
    }

    @Test
    void aCheckOutsideTheRunScopeDoesNotRun() {
        // Spec 6.4: a run's coverage is the set of check types it ran. A pulse that quietly ran
        // a full crawl's checks would resolve findings it has no business resolving.
        PageSnapshot page = Snapshots.page("https://example.com/medien")
                .media(dev.hendrikhoemberg.webtesthelper.model.MediaKind.VIDEO,
                        "https://example.com/fehlt.mp4", 0, 0.0, "MEDIA_ERR_SRC_NOT_SUPPORTED")
                .build();

        assertThat(engine.evaluatePage(page, site(allEnabled()), facts(RunScope.FULL)))
                .extracting(CheckFinding::type).contains(CheckType.MEDIA_PLAYABLE);
        assertThat(engine.evaluatePage(page, site(allEnabled()), facts(RunScope.PULSE)))
                .isEmpty();
    }

    @Test
    void aSiteSeverityOverrideReachesTheFinding() {
        Map<CheckType, CheckSetting> settings = allEnabled();
        settings.put(CheckType.IMAGE_BROKEN, new CheckSetting(true, Severity.INFO, Map.of()));

        assertThat(engine.evaluatePage(brokenPage(), site(settings), facts(RunScope.FULL)))
                .filteredOn(finding -> finding.type() == CheckType.IMAGE_BROKEN)
                .singleElement()
                .satisfies(finding -> assertThat(finding.severity()).isEqualTo(Severity.INFO));
    }

    @Test
    void aWholeRunIsEvaluatedPageByPage() {
        RunSnapshots snapshots = new RunSnapshots(1L, site(allEnabled()),
                List.of(brokenPage(), Snapshots.page("https://example.com/y").status(500).build()),
                SoftNotFoundProbe.NONE);

        assertThat(engine.evaluateRun(snapshots, site(allEnabled()), facts(RunScope.FULL)))
                .hasSize(3);
    }

    @Test
    void aCheckThatThrowsNamesItselfInsteadOfFailingAnonymously() {
        // Spec 14 is about a bad page not killing a run. A deterministically broken check is a
        // different animal: it would fail every run of every site until someone fixed it, so it
        // fails loudly and says which check and which page.
        CheckEngine broken = new CheckEngine(new CheckRegistry(List.of(new PageCheck() {
            @Override public CheckType type() { return CheckType.PAGE_STATUS; }
            @Override public Severity defaultSeverity() { return Severity.ERROR; }
            @Override public Set<String> messageKeys() { return Set.of("finding.PAGE_STATUS.x"); }
            @Override public List<CheckFinding> evaluate(PageSnapshot s, CheckConfig c) {
                throw new IllegalStateException("kaputt");
            }
        }), List.of()));

        assertThatThrownBy(() ->
                broken.evaluatePage(brokenPage(), site(allEnabled()), facts(RunScope.FULL)))
                .isInstanceOf(CheckEvaluationException.class)
                .hasMessageContaining("PAGE_STATUS")
                .hasMessageContaining("https://example.com/x")
                .hasRootCauseMessage("kaputt");
    }
}
```

`PageCheckAcceptanceTest.java` — the whole-plan acceptance, against the fixture site with a real
Chromium. Named `*Test`, not `*IT`: surefire's default includes match class names and a `*IT`
class is silently skipped by `./mvnw test` (a Plan 2b execution finding).

```java
package dev.hendrikhoemberg.webtesthelper.checks;

import dev.hendrikhoemberg.webtesthelper.catalog.SiteForm;
import dev.hendrikhoemberg.webtesthelper.catalog.SiteService;
import dev.hendrikhoemberg.webtesthelper.crawler.CrawlRequest;
import dev.hendrikhoemberg.webtesthelper.crawler.CrawlResult;
import dev.hendrikhoemberg.webtesthelper.crawler.CrawlService;
import dev.hendrikhoemberg.webtesthelper.model.CheckFinding;
import dev.hendrikhoemberg.webtesthelper.model.CheckSetting;
import dev.hendrikhoemberg.webtesthelper.model.CheckType;
import dev.hendrikhoemberg.webtesthelper.model.RunFacts;
import dev.hendrikhoemberg.webtesthelper.model.RunScope;
import dev.hendrikhoemberg.webtesthelper.model.SiteContext;
import dev.hendrikhoemberg.webtesthelper.support.AbstractPostgresTest;
import dev.hendrikhoemberg.webtesthelper.support.FixtureSite;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Spec 15: every check is developed and regression-tested against the fixture site, with a real
 * Chromium. The crawl runs <em>once</em> and the snapshots are then evaluated twice under
 * different configuration — which is the "navigate once, check many" claim of spec 5.2 in
 * literal form.
 */
@Tag("browser")
class PageCheckAcceptanceTest extends AbstractPostgresTest {

    @Autowired CrawlService crawler;
    @Autowired CheckEngine engine;
    @Autowired SiteService sites;
    @Autowired JdbcTemplate jdbc;

    private static FixtureSite site;

    private SiteContext context;
    private CrawlResult result;
    private List<CheckFinding> findings;

    @BeforeAll static void startSite() { site = FixtureSite.start(); }
    @AfterAll static void stopSite() { site.close(); }

    @BeforeEach
    void crawlOnce() {
        jdbc.update("DELETE FROM crawl_queue_item");
        jdbc.update("DELETE FROM run");
        jdbc.update("DELETE FROM site_check_setting");
        jdbc.update("DELETE FROM site");
        long siteId = sites.create(new SiteForm("Fixture", site.baseUrl(), 30, 3,
                Duration.ofMinutes(3), List.of(), List.of(), true, null));
        long runId = jdbc.queryForObject("INSERT INTO run (site_id, trigger_type, scope, status) "
                + "VALUES (?, 'MANUAL', 'FULL', 'RUNNING') RETURNING id", Long.class, siteId);

        context = sites.contextFor(siteId);
        result = crawler.crawl(new CrawlRequest(runId, context, RunScope.FULL, "test-worker"),
                (visited, failed) -> { });
        findings = engine.evaluateRun(result.snapshots(), context,
                RunFacts.of(result.snapshots(), RunScope.FULL, Instant.now()));
    }

    private List<CheckFinding> of(CheckType type) {
        return findings.stream().filter(finding -> finding.type() == type).toList();
    }

    @Test
    void thePageThatPretendsToExistIsReportedAsASoftNotFound() {
        assertThat(of(CheckType.PAGE_STATUS))
                .filteredOn(finding -> finding.messageKey().endsWith(".soft404"))
                .extracting(finding -> finding.observedOn().path())
                .contains("/verirrt.html");
    }

    @Test
    void theHardNotFoundPageIsReportedWithItsStatusCode() {
        assertThat(of(CheckType.PAGE_STATUS))
                .filteredOn(finding -> finding.observedOn().path().equals("/hart-404"))
                .singleElement()
                .satisfies(finding -> assertThat(finding.messageArgs()).containsExactly("404"));
    }

    @Test
    void noRealPageOfTheFixtureIsMistakenForTheNotFoundPage() {
        // The measured margin: the closest real page sits at 27, the cutoff at 16. If this ever
        // fails, re-measure before touching the cutoff.
        //
        // Exactly two pages are soft 404s. /verirrt.html is linked from the start page, and
        // /nicht-vorhanden.html is listed in the fixture's sitemap.xml — both paths the fixture
        // answers 200 for with its not-found body, which is what makes them soft 404s.
        assertThat(of(CheckType.PAGE_STATUS))
                .filteredOn(finding -> finding.messageKey().endsWith(".soft404"))
                .extracting(finding -> finding.observedOn().path())
                .containsExactlyInAnyOrder("/verirrt.html", "/nicht-vorhanden.html");
    }

    @Test
    void theRedirectLoopIsReportedAsALoopAndNotAlsoAsAnUnreachablePage() {
        assertThat(of(CheckType.REDIRECT_CHAIN))
                .filteredOn(finding -> finding.messageKey().endsWith(".loop"))
                .isNotEmpty();
        assertThat(of(CheckType.PAGE_UNREACHABLE))
                .extracting(finding -> finding.observedOn().path())
                .doesNotContain("/schleife/a");
    }

    @Test
    void theMissingFooterImageIsReportedOnEveryPageThatShowsIt() {
        // The same subject on many pages is what Plan 4 promotes to a site-wide finding
        // (spec 6.2). Here it must simply be reported once per page, with the same subject.
        assertThat(of(CheckType.IMAGE_BROKEN))
                .filteredOn(finding -> finding.subjectKey().endsWith("/assets/fehlt.png"))
                .hasSizeGreaterThanOrEqualTo(4);
    }

    @Test
    void theVideoWithABrokenSourceIsReportedAndTheWorkingAudioIsNot() {
        assertThat(of(CheckType.MEDIA_PLAYABLE)).singleElement().satisfies(finding -> {
            assertThat(finding.observedOn().path()).isEqualTo("/medien.html");
            assertThat(finding.subjectKey()).endsWith("/medien/fehlt.mp4");
            assertThat(finding.messageKey()).isEqualTo("finding.MEDIA_PLAYABLE.video");
        });
    }

    @Test
    void bothBrokenEmbedsOnTheContactPageAreReported() {
        assertThat(of(CheckType.IFRAME_EMBED))
                .extracting(CheckFinding::messageKey)
                .containsExactlyInAnyOrder("finding.IFRAME_EMBED.blocked",
                        "finding.IFRAME_EMBED.maps");
        assertThat(of(CheckType.IFRAME_EMBED))
                .allSatisfy(finding ->
                        assertThat(finding.observedOn().path()).isEqualTo("/kontakt.html"));
    }

    @Test
    void theCheckThatShipsDisabledReportsNothingEvenThoughItHadSomethingToSay() {
        // Spec 7.1: enabled by default this would make the first report mostly noise. The
        // fixture does log console errors, so this asserts the switch, not an empty console.
        // SITEMAP_CONSISTENCY, the other check that ships disabled, arrives in Plan 3b.
        assertThat(result.snapshots().snapshots())
                .anySatisfy(snapshot -> assertThat(snapshot.errors()).isNotEmpty());
        assertThat(of(CheckType.CONSOLE_ERRORS)).isEmpty();
    }

    @Test
    void aPlainHttpSiteHasNoMixedContent() {
        // Deviation D6: the fixture is served over http, so this check has nothing to say here.
        // Its positive case lives in MixedContentCheckTest, on a hand-built snapshot.
        assertThat(of(CheckType.MIXED_CONTENT)).isEmpty();
    }

    @Test
    void theSameSnapshotsUnderAStricterHopLimitReportTheRedirectChain() {
        // Navigate once, check many (spec 5.2): no second crawl, only a second evaluation.
        Map<CheckType, CheckSetting> stricter = new EnumMap<>(context.checkSettings());
        stricter.put(CheckType.REDIRECT_CHAIN,
                new CheckSetting(true, null, Map.of("maxHops", 2)));
        SiteContext strictSite = new SiteContext(context.siteId(), context.name(),
                context.baseUrl(), context.budget(), context.includePatterns(),
                context.excludePatterns(), context.pinnedKeyPages(), context.respectRobots(),
                context.userAgent(), stricter);

        List<CheckFinding> stricterFindings = engine.evaluateRun(result.snapshots(), strictSite,
                RunFacts.of(result.snapshots(), RunScope.FULL, Instant.now()));

        assertThat(stricterFindings)
                .filteredOn(finding -> finding.messageKey().endsWith(".tooManyHops"))
                .singleElement()
                .satisfies(finding -> {
                    assertThat(finding.subjectKey()).endsWith("/weiter/1");
                    assertThat(finding.messageArgs().getFirst()).isEqualTo("3");
                });
    }
}
```

Replace `theStandardRegistryHoldsThePageChecksThatShipToday` in `CheckRegistryTest.java` with the
coverage guard — this is what makes forgetting to register a check a build failure:

```java
    @Test
    void everyCheckTypeThatShipsInPhaseOneHasExactlyOneImplementation() {
        // Plan 3b implements these five; delete them from this set as they land, and the test
        // starts demanding them. Spec 7.3: adding a check must not require touching the runner,
        // and this is what makes forgetting to register one impossible to miss.
        Set<CheckType> pendingInPlan3b = EnumSet.of(CheckType.DEAD_LINK, CheckType.FILE_DOWNLOAD,
                CheckType.TLS_CERT, CheckType.HREFLANG, CheckType.SITEMAP_CONSISTENCY);
        Set<CheckType> expected = EnumSet.allOf(CheckType.class);
        expected.removeAll(pendingInPlan3b);

        assertThat(registry.coveredTypes()).containsExactlyInAnyOrderElementsOf(expected);
    }
```

(with `import java.util.EnumSet;` and `import java.util.Set;` added.)

Add to `CrawlRunExecutorTest.java`, inside `aManualRunCrawlsTheFixtureSiteEndToEnd`:

```java
        // The check pass ran: the fixture contains one of every failure mode (spec 15), so a
        // run that found nothing means the checks were never invoked.
        assertThat(jdbc.queryForObject("SELECT findings_total FROM run WHERE id = ?", Integer.class, runId))
                .isGreaterThan(0);
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./mvnw test -DexcludedGroups=browser -Dtest=CheckEngineTest`
Expected: FAIL — compilation errors, `cannot find symbol: class CheckEngine`.

- [ ] **Step 3: Write the engine**

`CheckEvaluationException.java`:

```java
package dev.hendrikhoemberg.webtesthelper.checks;

import dev.hendrikhoemberg.webtesthelper.model.CheckType;

/**
 * A check threw. Spec 14's "one bad page must never kill a run" is about pages: a check that
 * throws is deterministic, would fail every run of every site until someone fixed it, and
 * silently dropping its findings would mean a site quietly stops being checked. So it fails
 * the run — loudly, and saying which check on which page.
 */
public class CheckEvaluationException extends RuntimeException {

    public CheckEvaluationException(CheckType type, String url, Throwable cause) {
        super("Prüfung " + type.name() + " fehlgeschlagen für " + url, cause);
    }
}
```

`CheckEngine.java`:

```java
package dev.hendrikhoemberg.webtesthelper.checks;

import dev.hendrikhoemberg.webtesthelper.model.CheckFinding;
import dev.hendrikhoemberg.webtesthelper.model.CheckType;
import dev.hendrikhoemberg.webtesthelper.model.PageSnapshot;
import dev.hendrikhoemberg.webtesthelper.model.RunFacts;
import dev.hendrikhoemberg.webtesthelper.model.RunSnapshots;
import dev.hendrikhoemberg.webtesthelper.model.SiteContext;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Runs the applicable page checks over the snapshots a crawl produced (deviation D2: one
 * post-crawl pass, never inline in the crawl loop, so no check can influence crawl order or
 * another check's input).
 *
 * <p>A check applies when the run's scope includes its type <em>and</em> the site has it
 * enabled. Both conditions matter and for different reasons: the scope decides what a pulse is
 * allowed to look at, and it becomes the run's coverage, which is what makes resolution safe
 * (spec 6.4). The site setting is the human's switch.
 *
 * <p>The site arrives as an explicit parameter rather than being read off {@code RunSnapshots},
 * so the same snapshots can be evaluated under different configuration without re-crawling —
 * which is spec 5.2's whole promise.
 */
public final class CheckEngine {

    private final CheckRegistry registry;

    public CheckEngine(CheckRegistry registry) {
        this.registry = registry;
    }

    public List<CheckFinding> evaluateRun(RunSnapshots snapshots, SiteContext site,
            RunFacts facts) {
        List<CheckFinding> findings = new ArrayList<>();
        for (PageSnapshot snapshot : snapshots.snapshots()) {
            findings.addAll(evaluatePage(snapshot, site, facts));
        }
        return findings;
    }

    /**
     * The check types this engine can actually run (spec 6.4): a run's coverage may not claim a
     * check the registry does not implement, or resolving would trust checks that never ran.
     */
    public Set<CheckType> coveredTypes() {
        return registry.coveredTypes();
    }

    public List<CheckFinding> evaluatePage(PageSnapshot snapshot, SiteContext site,
            RunFacts facts) {
        List<CheckFinding> findings = new ArrayList<>();
        for (PageCheck check : registry.pageChecks()) {
            if (!facts.scope().checkTypes().contains(check.type()) || !site.enabled(check.type())) {
                continue;
            }
            CheckConfig config = new CheckConfig(
                    site.severityFor(check.type(), check.defaultSeverity()),
                    site.settingsFor(check.type()), facts);
            try {
                findings.addAll(check.evaluate(snapshot, config));
            } catch (RuntimeException e) {
                throw new CheckEvaluationException(check.type(), snapshot.url().value(), e);
            }
        }
        return findings;
    }
}
```

- [ ] **Step 4: Wire it into the run**

`src/main/java/dev/hendrikhoemberg/webtesthelper/runner/package-info.java` — add the module:

```java
@org.springframework.modulith.ApplicationModule(
        displayName = "Runner",
        allowedDependencies = {"model", "catalog", "crawler", "checks"})
package dev.hendrikhoemberg.webtesthelper.runner;
```

`src/main/java/dev/hendrikhoemberg/webtesthelper/runner/CheckEngineConfiguration.java` — the
one place Spring learns about the catalog, so the `checks` module stays free of it (§5.1):

```java
package dev.hendrikhoemberg.webtesthelper.runner;

import dev.hendrikhoemberg.webtesthelper.checks.CheckEngine;
import dev.hendrikhoemberg.webtesthelper.checks.CheckRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The checks module holds no Spring beans (spec 5.1), so the container learns about the
 * catalog here — in one place, from the registry's own list (deviation D15).
 */
@Configuration
class CheckEngineConfiguration {

    @Bean
    CheckRegistry checkRegistry() {
        return CheckRegistry.standard();
    }

    @Bean
    CheckEngine checkEngine(CheckRegistry registry) {
        return new CheckEngine(registry);
    }
}
```

`RunResultJdbcRepository.java` — add the count to the outcome write. Change the SQL constant and
the method:

```java
    private static final String OUTCOME_SQL = """
            UPDATE run
               SET pages_visited       = ?,
                   pages_failed        = ?,
                   findings_total      = ?,
                   covered_check_types = ?::jsonb,
                   covered_urls        = ?::jsonb,
                   partial_coverage    = ?,
                   budget_stop_reason  = ?,
                   soft404_status      = ?,
                   soft404_simhash     = ?,
                   soft404_text_length = ?
             WHERE id = ?
            """;

    public void saveCrawlOutcome(long runId, CrawlResult result, List<String> coveredCheckTypes,
            SoftNotFoundProbe probe, int findingsTotal) {
        try {
            jdbc.update(OUTCOME_SQL,
                    result.pagesVisited(),
                    result.pagesFailed(),
                    findingsTotal,
                    objectMapper.writeValueAsString(coveredCheckTypes),
                    objectMapper.writeValueAsString(result.coveredUrls()),
                    result.partialCoverage(),
                    result.budgetStopReason(),
                    probe.httpStatus(),
                    probe.simhash(),
                    probe.textLength(),
                    runId);
        } catch (JacksonException e) {
            // A list of strings that will not serialise is a bug, not a condition.
            throw new IllegalStateException("Lauf-" + runId + "-Ergebnis nicht als JSON serialisierbar",
                    e);
        }
    }
```

`CrawlRunExecutor.java` — insert the check pass. Replace the class body's javadoc, fields,
constructor and `execute`:

```java
/**
 * The sole {@link RunExecutor}: crawl the leased site, evaluate the page checks over what the
 * crawl saw, then record coverage, the soft-404 probe and the finding count on the run row.
 *
 * <p>The check pass sits here rather than in the crawler because the crawler evaluates nothing
 * (spec 5.2) — it produces snapshots, which is why {@link CrawlResult} carries the whole
 * {@code RunSnapshots} rather than counts.
 *
 * <p>Plan 4 replaces {@code findings.size()} with materialisation: fingerprinting, site-wide
 * promotion, occurrences and the coverage-scoped diff (spec 6.2). Until then the findings are
 * computed, counted and dropped — which is enough to prove the pass runs and avoids inventing
 * a findings schema that materialisation would immediately replace.
 */
@Component
public class CrawlRunExecutor implements RunExecutor {

    private static final Logger log = LoggerFactory.getLogger(CrawlRunExecutor.class);

    /**
     * A crawl outlives the 30-minute lease it was claimed under; without the heartbeat the
     * sweep would reclaim a run that is perfectly healthy (spec 14).
     */
    private static final Duration LEASE_EXTENSION = Duration.ofMinutes(30);

    private final CrawlService crawler;
    private final CheckEngine checks;
    private final SiteService sites;
    private final RunResultJdbcRepository results;
    private final RunLeaseJdbcRepository leases;
    private final WorkerIdentity identity;

    public CrawlRunExecutor(CrawlService crawler, CheckEngine checks, SiteService sites,
            RunResultJdbcRepository results, RunLeaseJdbcRepository leases,
            WorkerIdentity identity) {
        this.crawler = crawler;
        this.checks = checks;
        this.sites = sites;
        this.results = results;
        this.leases = leases;
        this.identity = identity;
    }

    @Override
    public void execute(RunLease lease) {
        SiteContext site = sites.contextFor(lease.siteId());
        Instant startedAt = Instant.now();
        CrawlResult result = crawler.crawl(
                new CrawlRequest(lease.runId(), site, lease.scope(), identity.name()),
                (visited, failed) -> {
                    // A crawl outlives the 30-minute lease it was claimed under; without this
                    // the sweep would reclaim a run that is perfectly healthy (spec 14).
                    leases.heartbeat(lease.runId(), identity.name(), LEASE_EXTENSION);
                    results.updateProgress(lease.runId(), visited, failed);
                });

        // The check pass runs outside the crawl's heartbeat callback; one more extension closes
        // the stale-lease window so the sweep cannot reclaim a run that is still working (spec 14).
        leases.heartbeat(lease.runId(), identity.name(), LEASE_EXTENSION);

        RunFacts facts = RunFacts.of(result.snapshots(), lease.scope(), startedAt);
        List<CheckFinding> findings = checks.evaluateRun(result.snapshots(), site, facts);
        log.info("Lauf {}: {} Befunde auf {} Seiten", lease.runId(), findings.size(),
                result.snapshots().pageCount());

        List<String> coveredCheckTypes = lease.scope().checkTypes().stream()
                .filter(site::enabled)
                .filter(checks.coveredTypes()::contains)
                .map(Enum::name)
                .sorted()
                .toList();
        results.saveCrawlOutcome(lease.runId(), result, coveredCheckTypes,
                result.snapshots().softNotFound(), findings.size());
    }
}
```

Add these imports to `CrawlRunExecutor.java`:

```java
import dev.hendrikhoemberg.webtesthelper.checks.CheckEngine;
import dev.hendrikhoemberg.webtesthelper.model.CheckFinding;
import dev.hendrikhoemberg.webtesthelper.model.RunFacts;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
```

- [ ] **Step 5: Run the tests to verify they pass**

Run: `./mvnw test -DexcludedGroups=browser`
Expected: PASS. `ModularityTest` now prints five modules and accepts `runner → checks`; if it
fails with a dependency violation, `runner/package-info.java` was not updated.

Run: `./mvnw test`
Expected: PASS, browser tests included — `PageCheckAcceptanceTest` (10 tests) and the extended
`CrawlRunExecutorTest` among them.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/dev/hendrikhoemberg/webtesthelper src/test/java/dev/hendrikhoemberg/webtesthelper
git commit -m "feat(runner): evaluate the page checks over every crawled snapshot"
```

**Post-review fixes (landed in `fix(runner): record only the check types the engine actually ran`):** `covered_check_types` is now the three-way intersection scope ∩ enabled ∩ implemented, via the new `CheckEngine.coveredTypes()` — before, a FULL run claimed `DEAD_LINK` and the three site checks as covered though no implementation exists (§6.4 says coverage records what actually ran; Plan 4's resolution will trust this column). One `leases.heartbeat` was added between the crawl and the check pass, closing the stale-lease window outside the crawl's heartbeat callback. `CrawlRunExecutorTest.theRunRecordsWhatItCovered` asserts `PAGE_STATUS` covered and `DEAD_LINK` not; `CheckEngineTest` gained `coveredTypesIsExactlyWhatTheRegistryImplements`; `PageCheckAcceptanceTest` gained the soft-404 margin canary (every reachable 200 page outside the two known soft-404s keeps a SimHash distance > 20 to the probe, guarding the measured 27-vs-16 margin).

---

## Plan 3a completion check

- [x] `./mvnw test` passes, browser tests included (251 tests)
- [x] `./mvnw test -DexcludedGroups=browser` also passes (208 tests) — every check unit test
      runs without Chromium, which is the property that makes the catalog cheap to extend
- [x] Nine commits landed: four task commits (`feat(checks)` ×3, `feat(runner)`) plus five
      review-fix commits (`fix(checks)` ×4, `fix(runner)`) — the two-stage review added the fixes
      below and each was re-reviewed
- [x] `ModularityTest` proves `checks → {model}` and rejects anything else; `grep -rn
      "org.springframework" src/main/java/dev/hendrikhoemberg/webtesthelper/checks/` returns only
      the `@ApplicationModule` line in `package-info.java`
- [x] `CheckRegistry.standard()` covers eight `CheckType`s; the five Plan 3b implements are the
      only ones in `pendingInPlan3b`
- [x] The documentation gate is real: deleting one line from `messages.properties` failed
      `CheckDocumentationTest` (verified during execution), restored afterwards
- [x] No migration was added; `ddl-auto=validate` still passes
- [ ] **Write `2026-08-21-webtesthelper-p3b-verification.md` next**, feeding back anything
      execution revealed — the section below is its input

## Execution findings fed back to Plan 3b's writer

Recorded during 3a's execution and reviews. The verbatim code above already matches the tree —
these are the open items and decisions 3b/4 must make.

- **Blocked-iframe: declared `src` vs post-redirect final URL.** The failed document request
  carries the *final* URL; `FrameRef.src` is the declared attribute. A frame whose document
  redirects and is then refused by `X-Frame-Options`/CSP matches no frame and is silently missed
  (javadoc-documented in `IframeEmbedCheck`). 3b/4 must record the frame's resolved URL at
  extraction or map failed document requests back to the frame element.
- **A frame whose document 404s/500s is no longer reported by `IFRAME_EMBED`.** The blocked
  classification now requires the measured `net::ERR_BLOCKED_BY_RESPONSE` signal (a plain HTTP
  failure is not a provider refusal). `DEAD_LINK` in 3b owns those URLs.
- **Coverage stays in lockstep.** `covered_check_types` is now the three-way intersection
  scope ∩ enabled ∩ implemented (`CrawlRunExecutor` via `CheckEngine.coveredTypes()`). 3b's site
  checks enter automatically once registered; keep `saveCrawlOutcome`'s fifth `int` parameter as
  the findings-count handoff.
- **Maps attribution semantics.** Once any maps frame has a location-attributed console error,
  unattributed errors are dropped (page-global fallback only when no location matches any maps
  frame). Works for the fixture; decide the semantics before a second embed provider arrives.
- **Source-less media findings collapse at materialisation.** Subject is the page URL and there
  is one finding per element, so two source-less elements on one page share
  `(type, subjectKey, locationKey)` and will merge to one fingerprint in Plan 4 — decide a
  per-element identity there.
- **`REDIRECT_CHAIN.loop` is WARN** while the `PAGE_UNREACHABLE` finding it displaced was ERROR,
  so loops stay below the default notification threshold (§8/§11.1) — revisit in Plan 5's
  notification work.
- **`CheckEvaluationException` embeds the `CheckType` name** in its message. Diagnostic-only
  today; §13.1 applies if `run.error_message` is ever surfaced in the UI.
- **`ConsoleErrorsCheck` stores `message.location()` in `Evidence.responseDetail`** — consider a
  dedicated field when rendering lands.
- **hreflang input still does not exist** (unchanged from the pre-plan gap): no `AlternateRef`,
  no `<link rel="alternate" hreflang>` extraction. 3b must extend `extract.js`, add the value
  type and a `PageSnapshot` component, and map it in `PageNavigator` — budget a standalone task.
- The Plan 2b carry-overs remain open: `SiteResourceFetcher` hardcodes the User-Agent,
  `CrawlService.visit()` double-counts on enqueue failure, the probe leaves one orphan
  screenshot, the snapshot memory bound is soft in the all-unreachable corner.

## What Plan 3b consumes from this plan

- `checks.PageCheck` / `checks.SiteCheck` — the SPI is complete; 3b writes implementations and
  touches neither interface
- `checks.CheckRegistry.standard()` — five more lines, two of them in the site-check list
- `model.RunFacts` — 3b adds the URL verification results as a component, and `RunFacts.of(...)`
  grows a parameter. `DEAD_LINK` and `FILE_DOWNLOAD` read them through `CheckConfig.facts()`
- `checks.CheckEngine` — 3b adds `evaluateSite(RunSnapshots, SiteContext, RunFacts)` alongside
  the existing page pass, and `CrawlRunExecutor` calls both
- `runner.CrawlRunExecutor` — 3b inserts the verification pass between the crawl and the check
  pass, so the facts are populated before any check reads them
- `support.Snapshots` — 3b extends the builder with verification results and hreflang alternates
- `CheckDocumentationTest` and `CheckRegistryTest` — no changes needed beyond shrinking
  `pendingInPlan3b`; both start demanding documentation for the new checks automatically

**Known gaps 3b must close**, carried from the Plan 2b review and unchanged by this plan:

- `SiteResourceFetcher` hardcodes the User-Agent; wire `site.effectiveUserAgent()` through when
  it grows into asset verification (§8 wants the company's access logs greppable)
- `CrawlService.visit()` counts a page as visited *and* failed if the discovery enqueue throws
- The soft-404 probe leaves one unreferenced screenshot per run in the run's artifact directory
- The snapshot memory bound is soft in the all-unreachable corner
- **`<link rel="alternate" hreflang>` is not extracted at all.** `extract.js` collects `a[href]`
  and nothing else from `<head>`, so `HREFLANG` has no input. 3b must extend the script, add an
  `AlternateRef` value type and a `PageSnapshot` component, and map it in `PageNavigator` —
  budget a task for it rather than discovering it mid-check.
