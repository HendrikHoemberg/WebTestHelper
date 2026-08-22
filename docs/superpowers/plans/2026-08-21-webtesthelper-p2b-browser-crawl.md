# WebTestHelper Plan 2b — Browser Pool, Snapshot Extraction and the Crawl

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace Plan 1's `NoopRunExecutor` with a real crawl — admission rules, a thread-confined browser pool, snapshot extraction, and the pipeline that ties them to the frontier — so a manual run crawls the fixture site end to end and captures one `PageSnapshot` per page.

**Architecture:** Each browser worker is a **thread-confined `Playwright` + `Browser` pair** (§5.4). Playwright's Java API is not thread-safe: an object may only be touched on the thread that created it, and that single constraint dictates the pool design — four Chromium *processes*, not four contexts in one browser. A page is navigated exactly once and reduced to an immutable `PageSnapshot`; the crawler evaluates nothing (§5.2). Module direction is `runner → crawler`: the crawler owns the frontier and produces snapshots, the runner owns everything that writes to the `run` table.

**Tech Stack:** Java 25, Spring Boot 4.1.1, Playwright for Java (Chromium), `java.net.http.HttpClient` for robots.txt and sitemaps, PostgreSQL 17 via Testcontainers.

**Spec:** `docs/superpowers/specs/2026-08-21-webtesthelper-design.md` — read it alongside this plan. Section references like (§5.4) point there.
**Roadmap:** `docs/superpowers/plans/2026-08-21-webtesthelper-phase-1-roadmap.md` — this is the second half of plan 2 of 5.
**Predecessors:** `…-p1-foundation.md` and `…-p2a-frontier.md`, both executed; their commits are on `main`.

**Length:** ~2,200 lines against the roadmap's ~1,500 target. Test code is most of the excess, and the four tasks are roughly 500 lines each — still one fresh-subagent sitting apiece, which is what the cap protects. Splitting further would separate the browser pool from the only code that uses it. Noted rather than silently exceeded.

**Ends with:** enqueue a `MANUAL` `FULL` run against the fixture site, call `RunWorker.workOnce()`, and the run completes `COMPLETED` having visited every crawlable fixture page, skipped the robots-disallowed one, captured a screenshot per page, learned the site's soft-404 fingerprint, and recorded its coverage.

---

## What Plan 2a leaves you

| Type | What it gives you |
|---|---|
| `support.FixtureSite` | `start()`, `baseUrl()` (`http://127.0.0.1:{port}/`), `externalBase()` (same server as `localhost`, so links to it are *external*), `url(String pathWithoutLeadingSlash)`, `port()`, `close()` |
| `model.PageSnapshot` | the immutable page record — `url`, `requestedUrl`, `depth`, `reachable`, `unreachableReason`, `httpStatus`, `responseHeaders`, `redirectChain`, `loadMillis`, `title`, `htmlLang`, `textContent`, `textSimhash`, `links`, `images`, `media`, `frames`, `forms`, `consoleMessages`, `failedRequests`, `screenshotPath`; plus `unreachable(...)`, `internalLinks()`, `externalLinks()`, `errors()` |
| `model.LinkRef` | `(rawHref, target, anchorText, internal, rel)`, `nofollow()` |
| `model.ImageRef` / `ImageOrigin` | `(rawSource, target, alt, naturalWidth, naturalHeight, origin)`, `rendered()`; `IMG`, `SRCSET`, `CSS_BACKGROUND` |
| `model.MediaRef` / `MediaKind` | `(kind, sources, readyState, duration, errorCode)`, `playable()`; `VIDEO`, `AUDIO` |
| `model.FrameRef` | `(src, title, loaded, contentTextLength, sameOrigin)` |
| `model.FormRef` / `FormFieldRef` | `(id, action, method, fields)` / `(name, type, label, autocomplete, required)` |
| `model.ConsoleMessage` / `FailedRequest` | `(level, text, location)` / `(url, method, resourceType, status, failureText)` |
| `model.RunSnapshots` | `(runId, site, snapshots, softNotFound)`, `byUrl`, `visitedUrls()`, `pageCount()` |
| `model.SoftNotFoundProbe` | `(httpStatus, simhash, textLength)`, `NONE`, `usable()` |
| `model.SimHash` | `of(String)`, `hammingDistance(long, long)` |
| `crawler.CrawlTarget` / `CrawlItemStatus` / `CrawlOutcome` | `(id, url, depth)`; `PENDING, CLAIMED, DONE, FAILED, SKIPPED`; `(id, status, httpStatus, errorMessage)` |
| `crawler.persistence.CrawlFrontierJdbcRepository` | `seed`, `enqueue`, `claimBatch`, `complete`, `reclaimStale`, `countPending`, `countByStatus`, `visitedUrls` |

And from Plan 1: `runner.RunExecutor` (the seam), `runner.NoopRunExecutor` (**deleted in Task 4**),
`runner.RunWorker.workOnce()`, `runner.WorkerIdentity.name()`,
`runner.persistence.RunLeaseJdbcRepository.heartbeat(runId, owner, extendBy)`,
`catalog.SiteService.contextFor(long)` and `create(SiteForm)`.

`SiteForm` is `(name, baseUrl, maxPages, maxDepth, maxDuration, includePatterns, excludePatterns, respectRobots, userAgent)`.

## Deviations applying to this plan

Carried forward: **D1** (`model` holds shared value types), **D5** (module direction is
`runner → crawler`; `crawler` never imports `runner`), **D6** (the fixture site is plain HTTP,
so `MIXED_CONTENT` is proven in Plan 3 from a hand-built snapshot), **D7** (snapshots are
memory-resident for the length of a run, bounded by `CrawlBudget.maxPages`).

New:

- **D8 — Include/exclude pattern syntax is defined here**, since the spec leaves it open:
  a pattern matches the full `locationKey()` (path plus surviving query), `*` matches any run
  of characters including `/`, `?` matches exactly one character, and the match is anchored at
  both ends. `/blog/*` therefore matches `/blog/beitrag` but not `/blog`.
- **D9 — robots.txt honours the `User-agent: *` group only.** Per-agent groups are parsed and
  ignored. We crawl the company's own sites with an identifying User-Agent (§8); a site with a
  group naming us would be configuring the tool through the wrong file, and the per-site
  `respectRobots` override is the supported way to say "crawl anyway".
- **D10 — Non-HTML URLs never enter the frontier.** A link to `.pdf`, `.png`, `.zip` and the
  like is an *asset*, verified over HTTP on virtual threads in Plan 3 (§5.3), not navigated by
  a browser. Admission rejects them with `NOT_NAVIGABLE`, and the snapshot still records the
  link, so nothing is lost.
- **D11 — The soft-404 probe navigates in a browser**, not over `HttpClient`. Its fingerprint
  is compared against page text extracted by `document.body.innerText`; fingerprinting raw HTML
  on one side and rendered text on the other would compare two different things.

## Global Constraints

Every task's requirements implicitly include this section, plus Plan 1's and 2a's.

- **Java 25, Spring Boot 4.1.1.** Playwright is already declared in `pom.xml` at
  `${playwright.version}` — do not change the version. **No new dependencies in this plan.**
- **`spring.jpa.hibernate.ddl-auto=validate` everywhere.** This plan adds **no migration**:
  the `run` table already has `pages_visited`, `pages_failed`, `covered_check_types`,
  `covered_urls`, `partial_coverage`, `budget_stop_reason`, `soft404_simhash`,
  `soft404_status` and `soft404_text_length`.
- **Playwright objects are thread-confined.** No `Playwright`, `Browser`, `BrowserContext`,
  `Page` or `Response` reference may cross a thread boundary or outlive the task that
  created it. Everything touching one runs inside `BrowserPool.submit`. A `PageSnapshot`
  crosses threads; a `Page` never does.
- **The crawler evaluates nothing.** No `CheckFinding`, no severity, no fingerprint. It
  produces snapshots. Plan 3 checks them.
- **Nothing in any test touches a real website** (§15). Every URL is loopback or a
  deliberately dead loopback port.
- **Commit after every task.** Conventional commits; code and commits in English, only
  user-facing strings in German.

## Install Chromium before Task 2

Playwright downloads its browser bundle on first `Playwright.create()`. Do it once, up front,
so a test failure is never a download failure:

```bash
./mvnw -q exec:java -e -Dexec.mainClass=com.microsoft.playwright.CLI -Dexec.args="install chromium"
```

Expected: `Downloading Chromium …` then a `Chromium … downloaded to …` line.
If your machine lacks Chromium's system libraries, use `install --with-deps chromium` (asks for sudo).
Verify: `ls ~/.cache/ms-playwright/` prints a `chromium-*` directory.

Tests that start a browser carry `@Tag("browser")`. The suite runs them by default;
`./mvnw test -DexcludedGroups=browser` gives a fast inner loop without one.

---

### Task 1: Admission rules — robots.txt, patterns, sitemap

Pure functions plus one small fetcher. This is what decides which URLs are allowed into the
frontier at all, and it is worth getting right before a browser is anywhere near it: a bug here
crawls a customer's admin area or silently skips half a site.

**Files:**
- Create: `src/main/java/…/crawler/RobotsRules.java`
- Create: `src/main/java/…/crawler/UrlAdmission.java`
- Create: `src/main/java/…/crawler/SitemapReader.java`
- Create: `src/main/java/…/crawler/SiteResourceFetcher.java`
- Test: `src/test/java/…/crawler/RobotsRulesTest.java`
- Test: `src/test/java/…/crawler/UrlAdmissionTest.java`
- Test: `src/test/java/…/crawler/SitemapReaderTest.java`
- Test: `src/test/java/…/crawler/SiteResourceFetcherTest.java`

**Interfaces:**
- Consumes: `model.NormalizedUrl`, `model.UrlNormalizer`, `model.SiteContext`, `support.FixtureSite`.
- Produces:
  - `RobotsRules.parse(String body) -> RobotsRules`; `RobotsRules.ALLOW_ALL`;
    `rules.allows(String path) -> boolean`; `rules.sitemaps() -> List<String>`
  - `new UrlAdmission(SiteContext site, RobotsRules robots)`;
    `admission.admit(NormalizedUrl url, int depth) -> UrlAdmission.Decision`;
    `record Decision(boolean admitted, Reason reason)`;
    `enum Reason { OK, OFF_SITE, TOO_DEEP, EXCLUDED, NOT_INCLUDED, ROBOTS, NOT_NAVIGABLE, BAD_SCHEME }`
  - `SitemapReader.locations(String xml) -> List<String>`; `SitemapReader.isIndex(String xml) -> boolean`
  - `SiteResourceFetcher.fetchText(NormalizedUrl url) -> Optional<String>` (`@Component`)

- [ ] **Step 1: Write the failing tests**

`RobotsRulesTest`:

```java
class RobotsRulesTest {

    private static final String ROBOTS = """
            # Kommentar
            User-agent: Bingbot
            Disallow: /

            User-agent: *
            Disallow: /geheim/
            Disallow: /suche?
            Allow: /geheim/oeffentlich.html
            Disallow: /*.json$

            Sitemap: https://example.com/sitemap.xml
            """;

    @Test
    void theStarGroupApplies() {
        RobotsRules rules = RobotsRules.parse(ROBOTS);
        assertThat(rules.allows("/")).isTrue();
        assertThat(rules.allows("/leistungen.html")).isTrue();
        assertThat(rules.allows("/geheim/intern.html")).isFalse();
    }

    @Test
    void aGroupNamingAnotherAgentIsIgnoredEntirely() {
        // Bingbot's blanket Disallow: / must not leak into our rules (deviation D9).
        assertThat(RobotsRules.parse(ROBOTS).allows("/impressum.html")).isTrue();
    }

    @Test
    void theLongestMatchingRuleWinsAndAllowBreaksTies() {
        RobotsRules rules = RobotsRules.parse(ROBOTS);
        assertThat(rules.allows("/geheim/oeffentlich.html")).isTrue();
        assertThat(rules.allows("/geheim/sonstiges.html")).isFalse();
    }

    @Test
    void wildcardsAndEndAnchorsAreHonoured() {
        RobotsRules rules = RobotsRules.parse(ROBOTS);
        assertThat(rules.allows("/daten/export.json")).isFalse();
        assertThat(rules.allows("/daten/export.json.html")).isTrue();
        assertThat(rules.allows("/suche?q=test")).isFalse();
    }

    @Test
    void sitemapLinesAreCollectedRegardlessOfGroup() {
        assertThat(RobotsRules.parse(ROBOTS).sitemaps())
                .containsExactly("https://example.com/sitemap.xml");
    }

    @Test
    void anEmptyDisallowMeansEverythingIsAllowed() {
        assertThat(RobotsRules.parse("User-agent: *\nDisallow:").allows("/beliebig")).isTrue();
    }

    @Test
    void anUnreadableOrAbsentRobotsFileAllowsEverything() {
        assertThat(RobotsRules.parse("").allows("/beliebig")).isTrue();
        assertThat(RobotsRules.ALLOW_ALL.allows("/beliebig")).isTrue();
    }
}
```

`UrlAdmissionTest` — build the `SiteContext` with a private helper
`site(List<String> include, List<String> exclude, boolean respectRobots, int maxDepth)`:

```java
class UrlAdmissionTest {

    @Test
    void aPageOnTheSameSiteWithinDepthIsAdmitted() {
        assertThat(admission().admit(url("https://example.com/leistungen.html"), 1).admitted())
                .isTrue();
    }

    @Test
    void wwwAndTheApexAreTheSameSite() {
        assertThat(admission().admit(url("https://www.example.com/a.html"), 1).admitted()).isTrue();
    }

    @Test
    void anotherHostIsOffSite() {
        assertThat(admission().admit(url("https://partner.example/a"), 1).reason())
                .isEqualTo(UrlAdmission.Reason.OFF_SITE);
    }

    @Test
    void beyondMaxDepthIsRejectedWithoutBeingAnError() {
        assertThat(admission(3).admit(url("https://example.com/tief"), 4).reason())
                .isEqualTo(UrlAdmission.Reason.TOO_DEEP);
    }

    @Test
    void excludePatternsWinOverIncludePatterns() {
        UrlAdmission admission = new UrlAdmission(
                site(List.of("/blog/*"), List.of("/blog/entwurf-*"), true, 5), RobotsRules.ALLOW_ALL);
        assertThat(admission.admit(url("https://example.com/blog/beitrag"), 1).admitted()).isTrue();
        assertThat(admission.admit(url("https://example.com/blog/entwurf-7"), 1).reason())
                .isEqualTo(UrlAdmission.Reason.EXCLUDED);
        assertThat(admission.admit(url("https://example.com/impressum"), 1).reason())
                .isEqualTo(UrlAdmission.Reason.NOT_INCLUDED);
    }

    @Test
    void anEmptyIncludeListMeansEverythingIsIncluded() {
        assertThat(admission().admit(url("https://example.com/irgendwo"), 1).admitted()).isTrue();
    }

    @Test
    void patternsMatchPathAndQueryAnchoredAtBothEnds() {
        // Deviation D8: /blog/* matches /blog/beitrag, not /blog.
        UrlAdmission admission = new UrlAdmission(
                site(List.of(), List.of("/blog/*"), true, 5), RobotsRules.ALLOW_ALL);
        assertThat(admission.admit(url("https://example.com/blog"), 1).admitted()).isTrue();
        assertThat(admission.admit(url("https://example.com/blog/x"), 1).admitted()).isFalse();
    }

    @Test
    void robotsIsHonouredUnlessTheSiteOverridesIt() {
        RobotsRules robots = RobotsRules.parse("User-agent: *\nDisallow: /geheim/");
        assertThat(new UrlAdmission(site(List.of(), List.of(), true, 5), robots)
                .admit(url("https://example.com/geheim/x"), 1).reason())
                .isEqualTo(UrlAdmission.Reason.ROBOTS);
        assertThat(new UrlAdmission(site(List.of(), List.of(), false, 5), robots)
                .admit(url("https://example.com/geheim/x"), 1).admitted())
                .isTrue();
    }

    @Test
    void assetsAreNotNavigableAndNeverEnterTheFrontier() {
        // Deviation D10: verified over HTTP on virtual threads in Plan 3, not by a browser.
        for (String asset : List.of("/handbuch.pdf", "/logo.PNG", "/archiv.zip", "/stil.css")) {
            assertThat(admission().admit(url("https://example.com" + asset), 1).reason())
                    .isEqualTo(UrlAdmission.Reason.NOT_NAVIGABLE);
        }
        assertThat(admission().admit(url("https://example.com/seite.html"), 1).admitted()).isTrue();
        assertThat(admission().admit(url("https://example.com/seite"), 1).admitted()).isTrue();
    }

    @Test
    void nonHttpSchemesAreRejected() {
        assertThat(UrlNormalizer.normalize("mailto:info@example.com")
                .map(u -> admission().admit(u, 1).reason()))
                .hasValueSatisfying(reason -> assertThat(reason)
                        .isEqualTo(UrlAdmission.Reason.BAD_SCHEME));
    }
}
```

If `UrlNormalizer.normalize("mailto:…")` returns `Optional.empty()` on your build, keep the
`BAD_SCHEME` branch in `UrlAdmission` anyway (a `NormalizedUrl` can also be constructed
directly) and assert it by constructing `new NormalizedUrl("ftp", "example.com", 21, "/x", null)`.

`SitemapReaderTest`:

```java
class SitemapReaderTest {

    @Test
    void locationsAreExtractedAndEntitiesDecoded() {
        String xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9">
                  <url><loc>https://example.com/</loc><lastmod>2026-08-01</lastmod></url>
                  <url><loc>https://example.com/a?x=1&amp;y=2</loc></url>
                </urlset>
                """;
        assertThat(SitemapReader.locations(xml))
                .containsExactly("https://example.com/", "https://example.com/a?x=1&y=2");
        assertThat(SitemapReader.isIndex(xml)).isFalse();
    }

    @Test
    void aSitemapIndexIsRecognisedSoItsChildrenCanBeFetched() {
        String xml = """
                <sitemapindex xmlns="http://www.sitemaps.org/schemas/sitemap/0.9">
                  <sitemap><loc>https://example.com/sitemap-1.xml</loc></sitemap>
                </sitemapindex>
                """;
        assertThat(SitemapReader.isIndex(xml)).isTrue();
        assertThat(SitemapReader.locations(xml)).containsExactly("https://example.com/sitemap-1.xml");
    }

    @Test
    void garbageIsNotAnException() {
        assertThat(SitemapReader.locations("<html>Seite nicht gefunden</html>")).isEmpty();
        assertThat(SitemapReader.locations("")).isEmpty();
    }
}
```

`SiteResourceFetcherTest` — against the fixture site, no browser:

```java
class SiteResourceFetcherTest {

    private static FixtureSite site;
    private final SiteResourceFetcher fetcher = new SiteResourceFetcher();

    @BeforeAll static void start() { site = FixtureSite.start(); }
    @AfterAll static void stop() { site.close(); }

    private static NormalizedUrl url(String value) {
        return UrlNormalizer.normalize(value).orElseThrow();
    }

    @Test
    void robotsTxtIsFetched() {
        assertThat(fetcher.fetchText(url(site.url("robots.txt"))))
                .hasValueSatisfying(body -> assertThat(body).contains("Disallow: /geheim/"));
    }

    @Test
    void aDeadHostYieldsEmptyRatherThanThrowing() {
        assertThat(fetcher.fetchText(url("http://localhost:9/robots.txt"))).isEmpty();
    }

    @Test
    void theFixturesSoft404CatchAllStillReturnsABodyAndThatIsTheCallersProblem() {
        // fetchText only reports transport and status; recognising a soft 404 is a check's job.
        assertThat(fetcher.fetchText(url(site.url("sitemap-gibt-es-nicht.xml")))).isPresent();
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./mvnw test -Dtest='RobotsRulesTest,UrlAdmissionTest,SitemapReaderTest,SiteResourceFetcherTest'`
Expected: compilation failure — none of the four types exist.

- [ ] **Step 3: Write `RobotsRules`**

```java
package dev.hendrikhoemberg.webtesthelper.crawler;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * The {@code User-agent: *} group of a robots.txt (deviation D9). Politeness is on by default
 * and overridable per site, because the company hosts these sites (spec 8).
 *
 * <p>Longest match wins between Allow and Disallow, with Allow breaking ties — the rule every
 * major crawler implements, and the reason a bare {@code Allow:} line can carve an exception
 * out of a broader {@code Disallow:}.
 */
public record RobotsRules(List<Rule> allow, List<Rule> disallow, List<String> sitemaps) {

    public record Rule(String source, Pattern pattern) {
    }

    public static final RobotsRules ALLOW_ALL = new RobotsRules(List.of(), List.of(), List.of());

    public RobotsRules {
        allow = List.copyOf(allow);
        disallow = List.copyOf(disallow);
        sitemaps = List.copyOf(sitemaps);
    }

    public static RobotsRules parse(String body) {
        if (body == null || body.isBlank()) {
            return ALLOW_ALL;
        }
        List<Rule> allow = new ArrayList<>();
        List<Rule> disallow = new ArrayList<>();
        List<String> sitemaps = new ArrayList<>();
        boolean inStarGroup = false;
        boolean previousLineWasAgent = false;
        for (String rawLine : body.split("\\R")) {
            String line = rawLine;
            int comment = line.indexOf('#');
            if (comment >= 0) {
                line = line.substring(0, comment);
            }
            line = line.trim();
            if (line.isEmpty()) {
                continue;
            }
            int colon = line.indexOf(':');
            if (colon < 0) {
                continue;
            }
            String field = line.substring(0, colon).trim().toLowerCase(Locale.ROOT);
            String value = line.substring(colon + 1).trim();
            switch (field) {
                case "user-agent" -> {
                    // Consecutive User-agent lines share one group.
                    inStarGroup = previousLineWasAgent ? (inStarGroup || "*".equals(value))
                            : "*".equals(value);
                    previousLineWasAgent = true;
                    continue;
                }
                case "disallow" -> {
                    if (inStarGroup && !value.isEmpty()) {
                        disallow.add(rule(value));
                    }
                }
                case "allow" -> {
                    if (inStarGroup && !value.isEmpty()) {
                        allow.add(rule(value));
                    }
                }
                case "sitemap" -> sitemaps.add(value);
                default -> {
                }
            }
            previousLineWasAgent = false;
        }
        return new RobotsRules(allow, disallow, sitemaps);
    }

    /** @param path the URL's path plus query, i.e. {@code NormalizedUrl.locationKey()} */
    public boolean allows(String path) {
        int allowLength = longestMatch(allow, path);
        int disallowLength = longestMatch(disallow, path);
        return disallowLength < 0 || allowLength >= disallowLength;
    }

    private static int longestMatch(List<Rule> rules, String path) {
        int longest = -1;
        for (Rule rule : rules) {
            if (rule.pattern().matcher(path).find()) {
                longest = Math.max(longest, rule.source().length());
            }
        }
        return longest;
    }

    /** {@code *} is any run of characters, {@code $} anchors the end; everything else is literal. */
    private static Rule rule(String source) {
        StringBuilder regex = new StringBuilder("^");
        for (int i = 0; i < source.length(); i++) {
            char c = source.charAt(i);
            if (c == '*') {
                regex.append(".*");
            } else if (c == '$' && i == source.length() - 1) {
                regex.append('$');
            } else {
                regex.append(Pattern.quote(String.valueOf(c)));
            }
        }
        return new Rule(source, Pattern.compile(regex.toString()));
    }
}
```

- [ ] **Step 4: Write `UrlAdmission`, `SitemapReader` and `SiteResourceFetcher`**

`UrlAdmission` — the order of the checks is the specification; keep it:

```java
package dev.hendrikhoemberg.webtesthelper.crawler;

import dev.hendrikhoemberg.webtesthelper.model.NormalizedUrl;
import dev.hendrikhoemberg.webtesthelper.model.SiteContext;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/** Decides what may enter the crawl frontier. Pure; one instance per run. */
public record UrlAdmission(SiteContext site, RobotsRules robots) {

    public enum Reason { OK, BAD_SCHEME, OFF_SITE, NOT_NAVIGABLE, TOO_DEEP, EXCLUDED,
                         NOT_INCLUDED, ROBOTS }

    public record Decision(boolean admitted, Reason reason) {
        static final Decision OK = new Decision(true, Reason.OK);

        static Decision no(Reason reason) {
            return new Decision(false, reason);
        }
    }

    /**
     * Extensions a browser must not be sent to (deviation D10). These are assets: Plan 3
     * verifies them over HTTP on virtual threads, which is minutes instead of hours (spec 5.3).
     */
    private static final Set<String> NOT_NAVIGABLE = Set.of(
            "pdf", "zip", "rar", "7z", "gz", "tar", "doc", "docx", "xls", "xlsx", "ppt", "pptx",
            "csv", "png", "jpg", "jpeg", "gif", "webp", "avif", "svg", "ico", "bmp", "tif", "tiff",
            "mp3", "mp4", "wav", "ogg", "webm", "mov", "avi", "css", "js", "json", "xml", "rss",
            "woff", "woff2", "ttf", "eot", "exe", "dmg", "apk");

    public Decision admit(NormalizedUrl url, int depth) {
        if (!"http".equals(url.scheme()) && !"https".equals(url.scheme())) {
            return Decision.no(Reason.BAD_SCHEME);
        }
        if (!site.baseUrl().sameSiteAs(url)) {
            return Decision.no(Reason.OFF_SITE);
        }
        if (NOT_NAVIGABLE.contains(extensionOf(url.path()))) {
            return Decision.no(Reason.NOT_NAVIGABLE);
        }
        if (depth > site.budget().maxDepth()) {
            return Decision.no(Reason.TOO_DEEP);
        }
        String locationKey = url.locationKey();
        if (matchesAny(site.excludePatterns(), locationKey)) {
            return Decision.no(Reason.EXCLUDED);
        }
        if (!site.includePatterns().isEmpty() && !matchesAny(site.includePatterns(), locationKey)) {
            return Decision.no(Reason.NOT_INCLUDED);
        }
        if (site.respectRobots() && !robots.allows(locationKey)) {
            return Decision.no(Reason.ROBOTS);
        }
        return Decision.OK;
    }

    private static String extensionOf(String path) {
        int slash = path.lastIndexOf('/');
        int dot = path.lastIndexOf('.');
        return dot > slash ? path.substring(dot + 1).toLowerCase(Locale.ROOT) : "";
    }

    private static boolean matchesAny(List<String> patterns, String locationKey) {
        return patterns.stream().anyMatch(pattern -> globOf(pattern).matcher(locationKey).matches());
    }

    /** Deviation D8: {@code *} is any run of characters, {@code ?} exactly one, anchored. */
    private static Pattern globOf(String glob) {
        StringBuilder regex = new StringBuilder();
        for (int i = 0; i < glob.length(); i++) {
            char c = glob.charAt(i);
            switch (c) {
                case '*' -> regex.append(".*");
                case '?' -> regex.append('.');
                default -> regex.append(Pattern.quote(String.valueOf(c)));
            }
        }
        return Pattern.compile(regex.toString());
    }
}
```

`globOf` compiles on every call; that is fine at frontier volumes (a few thousand URLs against
a handful of patterns). If a profile ever says otherwise, memoise in the record's constructor.

`SitemapReader` — regex rather than a DOM parser: sitemaps are a two-element format, and a
regex has no XXE surface to harden.

```java
package dev.hendrikhoemberg.webtesthelper.crawler;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class SitemapReader {

    private static final Pattern LOC = Pattern.compile("<loc>\\s*(.*?)\\s*</loc>",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    private SitemapReader() {
    }

    public static boolean isIndex(String xml) {
        return xml != null && xml.contains("<sitemapindex");
    }

    public static List<String> locations(String xml) {
        if (xml == null || xml.isBlank()) {
            return List.of();
        }
        List<String> locations = new ArrayList<>();
        Matcher matcher = LOC.matcher(xml);
        while (matcher.find()) {
            String location = matcher.group(1)
                    .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
                    .replace("&quot;", "\"").replace("&apos;", "'");
            if (!location.isBlank()) {
                locations.add(location);
            }
        }
        return List.copyOf(locations);
    }
}
```

`SiteResourceFetcher` — the only HTTP client in this plan; Plan 3 grows it into asset verification:

```java
package dev.hendrikhoemberg.webtesthelper.crawler;

import dev.hendrikhoemberg.webtesthelper.model.NormalizedUrl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Optional;

/** Fetches robots.txt and sitemaps. No browser needed, and none wanted. */
@Component
public class SiteResourceFetcher {

    private static final Logger log = LoggerFactory.getLogger(SiteResourceFetcher.class);

    private final HttpClient client = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    /** Empty on any transport failure or non-2xx status — an absent robots.txt is normal. */
    public Optional<String> fetchText(NormalizedUrl url) {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url.value()))
                    .timeout(Duration.ofSeconds(10))
                    .header("User-Agent", "WebTestHelper/1.0 (+internes Website-Monitoring)")
                    .GET()
                    .build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                return Optional.empty();
            }
            return Optional.of(response.body());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        } catch (Exception e) {
            log.debug("{} nicht abrufbar: {}", url.value(), e.toString());
            return Optional.empty();
        }
    }
}
```

- [ ] **Step 5: Run the tests to verify they pass**

Run: `./mvnw test -Dtest='RobotsRulesTest,UrlAdmissionTest,SitemapReaderTest,SiteResourceFetcherTest'`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/dev/hendrikhoemberg/webtesthelper/crawler \
        src/test/java/dev/hendrikhoemberg/webtesthelper/crawler
git commit -m "feat(crawler): add robots, pattern and sitemap admission rules

Decides what may enter the frontier: same site, within depth, not excluded,
robots-permitted unless the site overrides it, and navigable — assets are
verified over HTTP in a later phase rather than driven through a browser."
```

---

### Task 2: The thread-confined browser pool

> *"Playwright's Java API is not thread-safe. A `Playwright` instance and every object created
> from it may only be used on the thread that created it. This is a hard constraint and it
> dictates the pool design."* (§5.4)

So a browser worker is **not** a context borrowed from a shared browser. It is a thread that
owns a `Playwright` and a `Browser` and never lets either escape. Four workers means four
Chromium *processes*, which is what drives the container's memory sizing (§16).

**Files:**
- Create: `src/main/java/…/crawler/CrawlerProperties.java`
- Create: `src/main/java/…/crawler/BrowserPool.java`
- Create: `src/main/java/…/crawler/HostThrottle.java`
- Modify: `src/main/java/…/WebtesthelperApplication.java` (add `@ConfigurationPropertiesScan`)
- Modify: `src/main/resources/application.properties`
- Modify: `src/test/resources/application-test.properties`
- Test: `src/test/java/…/crawler/BrowserPoolTest.java`
- Test: `src/test/java/…/crawler/HostThrottleTest.java`

**Interfaces:**
- Consumes: `com.microsoft.playwright.*`, `FixtureSite`.
- Produces:
  - `CrawlerProperties` — record bound to `webtesthelper.crawler`:
    `(int browserWorkers, int batchSize, Duration navigationTimeout, Duration perHostDelay, Path artifactDir, boolean headless)`
  - `BrowserPool` (`@Component`, `AutoCloseable`):
    `interface BrowserTask<T> { T run(Browser browser) throws Exception; }`,
    `<T> T submit(BrowserTask<T> task)`, `int size()`
  - `HostThrottle` (`@Component`): `void await(String host, Duration minInterval)`

- [ ] **Step 1: Write the failing tests**

`BrowserPoolTest` — the first assertion is the one that matters: **Playwright work never runs
on the calling thread.** If it ever does, the confinement contract is broken and the failures
that follow are non-deterministic native crashes.

```java
package dev.hendrikhoemberg.webtesthelper.crawler;

import dev.hendrikhoemberg.webtesthelper.support.FixtureSite;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("browser")
class BrowserPoolTest {

    private static FixtureSite site;

    @BeforeAll static void start() { site = FixtureSite.start(); }
    @AfterAll static void stop() { site.close(); }

    private static CrawlerProperties properties(int workers) {
        return new CrawlerProperties(workers, 20, Duration.ofSeconds(15), Duration.ZERO,
                Path.of(System.getProperty("java.io.tmpdir"), "wth-pool-test"), true);
    }

    @Test
    void browserWorkNeverRunsOnTheCallingThread() throws Exception {
        try (BrowserPool pool = new BrowserPool(properties(1))) {
            String caller = Thread.currentThread().getName();
            String inside = pool.submit(browser -> Thread.currentThread().getName());
            assertThat(inside).startsWith("browser-worker-").isNotEqualTo(caller);
        }
    }

    @Test
    void everyTaskOfOneWorkerRunsOnThatWorkersSingleThread() throws Exception {
        try (BrowserPool pool = new BrowserPool(properties(1))) {
            List<String> threads = new java.util.ArrayList<>();
            for (int i = 0; i < 5; i++) {
                threads.add(pool.submit(browser -> Thread.currentThread().getName()));
            }
            assertThat(threads).hasSize(5).containsOnly(threads.getFirst());
        }
    }

    @Test
    void concurrentCallersAreBoundedByThePoolSize() throws Exception {
        try (BrowserPool pool = new BrowserPool(properties(2));
             ExecutorService callers = Executors.newFixedThreadPool(8)) {
            List<Callable<String>> work = new java.util.ArrayList<>();
            for (int i = 0; i < 8; i++) {
                work.add(() -> pool.submit(browser -> {
                    try (var context = browser.newContext()) {
                        var page = context.newPage();
                        page.navigate(site.baseUrl());
                        return page.title();
                    }
                }));
            }
            List<String> titles = new java.util.ArrayList<>();
            for (Future<String> future : callers.invokeAll(work)) {
                titles.add(future.get());
            }
            assertThat(titles).hasSize(8).allSatisfy(title ->
                    assertThat(title).contains("Startseite"));
            assertThat(pool.size()).isEqualTo(2);
        }
    }

    @Test
    void aWorkerWhoseBrowserDiedRestartsItRatherThanFailingEveryLaterTask() throws Exception {
        // Spec 14: "if the Browser dies, it is restarted and the run resumes".
        try (BrowserPool pool = new BrowserPool(properties(1))) {
            pool.submit(browser -> {
                browser.close();
                return null;
            });
            String title = pool.submit(browser -> {
                try (var context = browser.newContext()) {
                    var page = context.newPage();
                    page.navigate(site.baseUrl());
                    return page.title();
                }
            });
            assertThat(title).contains("Startseite");
        }
    }

    @Test
    void aFailingTaskPropagatesItsCauseAndLeavesTheWorkerUsable() throws Exception {
        try (BrowserPool pool = new BrowserPool(properties(1))) {
            org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                            pool.submit(browser -> { throw new IllegalStateException("kaputt"); }))
                    .hasRootCauseMessage("kaputt");
            assertThat(pool.<Boolean>submit(browser -> browser.isConnected())).isTrue();
        }
    }
}
```

`HostThrottleTest` — no browser needed:

```java
class HostThrottleTest {

    @Test
    void requestsToOneHostAreSpacedOut() {
        HostThrottle throttle = new HostThrottle();
        long start = System.nanoTime();
        for (int i = 0; i < 3; i++) {
            throttle.await("example.com", Duration.ofMillis(120));
        }
        assertThat(Duration.ofNanos(System.nanoTime() - start))
                .isGreaterThanOrEqualTo(Duration.ofMillis(240));
    }

    @Test
    void differentHostsDoNotWaitForEachOther() {
        HostThrottle throttle = new HostThrottle();
        throttle.await("a.example", Duration.ofMillis(500));
        long start = System.nanoTime();
        throttle.await("b.example", Duration.ofMillis(500));
        assertThat(Duration.ofNanos(System.nanoTime() - start))
                .isLessThan(Duration.ofMillis(200));
    }

    @Test
    void aZeroDelayDoesNotSleep() {
        HostThrottle throttle = new HostThrottle();
        long start = System.nanoTime();
        for (int i = 0; i < 50; i++) {
            throttle.await("example.com", Duration.ZERO);
        }
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofMillis(100));
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./mvnw test -Dtest='BrowserPoolTest,HostThrottleTest'`
Expected: compilation failure — `BrowserPool`, `CrawlerProperties` and `HostThrottle` do not exist.

- [ ] **Step 3: Add the configuration**

`CrawlerProperties.java`:

```java
package dev.hendrikhoemberg.webtesthelper.crawler;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.file.Path;
import java.time.Duration;

/**
 * @param browserWorkers    platform threads, each owning one Playwright + Chromium process.
 *                          Four means ~2 GB of Chromium under load (spec 16).
 * @param batchSize         URLs claimed from the frontier per statement (spec 6.5)
 * @param navigationTimeout per-page navigation budget; exceeding it is PAGE_UNREACHABLE, not
 *                          a dead run (spec 14)
 * @param perHostDelay      politeness gap between navigations to the same host (spec 8)
 * @param artifactDir       screenshots land under {artifactDir}/{runId}/ (spec 16)
 */
@ConfigurationProperties("webtesthelper.crawler")
public record CrawlerProperties(int browserWorkers, int batchSize, Duration navigationTimeout,
                                Duration perHostDelay, Path artifactDir, boolean headless) {
}
```

In `application.properties`, **replace** the `webtesthelper.browser-workers` line with:

```properties
webtesthelper.crawler.browser-workers=${WTH_BROWSER_WORKERS:4}
webtesthelper.crawler.batch-size=20
webtesthelper.crawler.navigation-timeout=30s
webtesthelper.crawler.per-host-delay=250ms
webtesthelper.crawler.artifact-dir=${webtesthelper.data-dir}/artifacts
webtesthelper.crawler.headless=true
```

Append to `src/test/resources/application-test.properties` — a smaller pool and no politeness
delay, because the fixture site is loopback and there is nobody to be polite to:

```properties
webtesthelper.crawler.browser-workers=2
webtesthelper.crawler.batch-size=10
webtesthelper.crawler.navigation-timeout=15s
webtesthelper.crawler.per-host-delay=0s
webtesthelper.crawler.artifact-dir=${java.io.tmpdir}/webtesthelper-test/artifacts
webtesthelper.crawler.headless=true
```

Add the scan to `WebtesthelperApplication`:

```java
@SpringBootApplication
@org.springframework.boot.context.properties.ConfigurationPropertiesScan
public class WebtesthelperApplication {
```

- [ ] **Step 4: Write `BrowserPool` and `HostThrottle`**

```java
package dev.hendrikhoemberg.webtesthelper.crawler;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Playwright;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * A fixed set of thread-confined browser workers (spec 5.4). Playwright's Java API is not
 * thread-safe: a {@code Playwright} instance and every object created from it may only be
 * touched on the creating thread. Each worker therefore owns a single-thread executor, creates
 * its {@code Playwright} and {@code Browser} on that thread, and runs every task there.
 *
 * <p>Platform threads, not virtual ones: Playwright pins native resources and blocks in JNI,
 * which is exactly the workload virtual threads are wrong for.
 *
 * <p>Callers hand in a {@link BrowserTask} and receive its return value. Anything derived from
 * the {@code Browser} — contexts, pages, responses — must be created and closed inside the
 * task. A {@code PageSnapshot} may leave; a {@code Page} may not.
 */
@Component
public class BrowserPool implements AutoCloseable {

    @FunctionalInterface
    public interface BrowserTask<T> {
        T run(Browser browser) throws Exception;
    }

    private static final Logger log = LoggerFactory.getLogger(BrowserPool.class);

    private final List<Worker> workers = new ArrayList<>();
    private final BlockingQueue<Worker> available;

    public BrowserPool(CrawlerProperties properties) {
        int size = Math.max(1, properties.browserWorkers());
        this.available = new ArrayBlockingQueue<>(size);
        for (int i = 0; i < size; i++) {
            Worker worker = new Worker(i, properties.headless());
            workers.add(worker);
            available.add(worker);
        }
    }

    public int size() {
        return workers.size();
    }

    /** Borrows a worker, runs the task on its thread, and returns the worker. Blocks if busy. */
    public <T> T submit(BrowserTask<T> task) {
        Worker worker;
        try {
            worker = available.take();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Warten auf Browser-Worker unterbrochen", e);
        }
        try {
            return worker.call(task);
        } finally {
            available.add(worker);
        }
    }

    @Override
    public void close() {
        workers.forEach(Worker::close);
        workers.clear();
        available.clear();
    }

    private static final class Worker {

        private final int index;
        private final boolean headless;
        private final ExecutorService thread;
        private Playwright playwright;
        private Browser browser;

        private Worker(int index, boolean headless) {
            this.index = index;
            this.headless = headless;
            this.thread = Executors.newSingleThreadExecutor(runnable -> {
                Thread t = new Thread(runnable, "browser-worker-" + index);
                t.setDaemon(true);
                return t;
            });
        }

        <T> T call(BrowserTask<T> task) {
            Future<T> future = thread.submit(() -> {
                ensureBrowser();
                return task.run(browser);
            });
            try {
                return future.get();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Browser-Aufgabe unterbrochen", e);
            } catch (ExecutionException e) {
                throw new IllegalStateException("Browser-Aufgabe fehlgeschlagen", e.getCause());
            }
        }

        /** Runs on the worker thread only. Restarts a browser that died mid-run (spec 14). */
        private void ensureBrowser() {
            if (playwright != null && browser != null && browser.isConnected()) {
                return;
            }
            if (playwright != null) {
                log.warn("Browser-Worker {} startet Chromium neu", index);
                closeQuietly();
            }
            playwright = Playwright.create();
            browser = playwright.chromium().launch(
                    new BrowserType.LaunchOptions().setHeadless(headless));
        }

        private void close() {
            try {
                thread.submit(this::closeQuietly).get();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (ExecutionException e) {
                log.debug("Browser-Worker {} beim Schließen: {}", index, e.getCause().toString());
            } finally {
                thread.shutdownNow();
            }
        }

        /** Must run on the worker thread — closing from elsewhere violates the confinement. */
        private void closeQuietly() {
            try {
                if (browser != null && browser.isConnected()) {
                    browser.close();
                }
            } catch (RuntimeException e) {
                log.debug("Browser {} liess sich nicht schliessen: {}", index, e.toString());
            }
            try {
                if (playwright != null) {
                    playwright.close();
                }
            } catch (RuntimeException e) {
                log.debug("Playwright {} liess sich nicht schliessen: {}", index, e.toString());
            }
            browser = null;
            playwright = null;
        }
    }
}
```

`HostThrottle`:

```java
package dev.hendrikhoemberg.webtesthelper.crawler;

import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Politeness, enforced where it belongs (spec 8): a minimum gap between navigations to the
 * same host, independent of how many workers are crawling. Reserving the slot before sleeping
 * means N waiting workers queue up N intervals instead of all waking at once.
 */
@Component
public class HostThrottle {

    private final Map<String, AtomicLong> nextAllowedAt = new ConcurrentHashMap<>();

    public void await(String host, Duration minInterval) {
        if (minInterval == null || minInterval.isZero() || minInterval.isNegative()) {
            return;
        }
        AtomicLong slot = nextAllowedAt.computeIfAbsent(host, ignored -> new AtomicLong(0L));
        long waitUntil;
        synchronized (slot) {
            long now = System.currentTimeMillis();
            waitUntil = Math.max(slot.get(), now);
            slot.set(waitUntil + minInterval.toMillis());
        }
        long sleepFor = waitUntil - System.currentTimeMillis();
        if (sleepFor > 0) {
            try {
                Thread.sleep(sleepFor);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
```

- [ ] **Step 5: Run the tests to verify they pass**

Run: `./mvnw test -Dtest='BrowserPoolTest,HostThrottleTest'`
Expected: PASS. The first run launches Chromium — a few seconds. If it fails with
*"Executable doesn't exist"*, the install step at the top of this plan was skipped.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/dev/hendrikhoemberg/webtesthelper/crawler \
        src/main/java/dev/hendrikhoemberg/webtesthelper/WebtesthelperApplication.java \
        src/main/resources/application.properties \
        src/test/resources/application-test.properties \
        src/test/java/dev/hendrikhoemberg/webtesthelper/crawler
git commit -m "feat(crawler): add the thread-confined browser pool

Playwright's Java API is not thread-safe (spec 5.4), so a worker is a thread
owning its own Playwright and Chromium process rather than a context borrowed
from a shared browser. A browser that dies is restarted in-thread so a run
survives it, and per-host politeness is a throttle rather than a pool size."
```

---

### Task 3: Snapshot extraction

One navigation, one script evaluation, one screenshot — and out comes a `PageSnapshot`. This is
the heart of §5.2: ~15,000 page visits per sweep become affordable because each page is visited
exactly once and every check is then a pure function over the result.

**One `page.evaluate` call, not fifteen.** Each round-trip to the browser costs milliseconds
that multiply by page count; the extraction script collects links, images, media, frames, forms,
text and language in a single pass and returns one object.

**Files:**
- Create: `src/main/resources/crawler/extract.js`
- Create: `src/main/java/…/crawler/PageNavigator.java`
- Test: `src/test/java/…/crawler/PageNavigatorTest.java`

**Interfaces:**
- Consumes: `BrowserPool`, `CrawlerProperties`, `HostThrottle`, `CrawlTarget`, every `model` value type.
- Produces: `PageNavigator` (`@Component`):
  `PageSnapshot capture(Browser browser, CrawlTarget target, SiteContext site, Path runArtifactDir)`
  — **called only from inside `BrowserPool.submit`.**

- [ ] **Step 1: Write the failing test**

```java
package dev.hendrikhoemberg.webtesthelper.crawler;

import dev.hendrikhoemberg.webtesthelper.model.*;
import dev.hendrikhoemberg.webtesthelper.support.FixtureSite;
import org.junit.jupiter.api.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("browser")
class PageNavigatorTest {

    private static FixtureSite site;
    private static BrowserPool pool;
    private static PageNavigator navigator;
    private static Path artifacts;

    @BeforeAll
    static void start() throws Exception {
        site = FixtureSite.start();
        artifacts = Files.createTempDirectory("wth-navigator");
        CrawlerProperties properties = new CrawlerProperties(1, 10, Duration.ofSeconds(15),
                Duration.ZERO, artifacts, true);
        pool = new BrowserPool(properties);
        navigator = new PageNavigator(properties, new HostThrottle());
    }

    @AfterAll
    static void stop() {
        pool.close();
        site.close();
    }

    private PageSnapshot capture(String path, int depth) {
        String url = site.url(path);
        return pool.submit(browser -> navigator.capture(
                browser, new CrawlTarget(1L, url, depth), siteContext(), artifacts));
    }

    /** SiteContext for the fixture: no patterns, robots respected, default budget. */
    private static SiteContext siteContext() {
        return new SiteContext(1L, "Fixture",
                UrlNormalizer.normalize(site.baseUrl()).orElseThrow(),
                CrawlBudget.DEFAULT, List.of(), List.of(), List.of(), true, null, java.util.Map.of());
    }

    @Test
    void theStartPageYieldsStatusTitleLanguageTextAndAScreenshot() {
        PageSnapshot snapshot = capture("", 0);

        assertThat(snapshot.reachable()).isTrue();
        assertThat(snapshot.httpStatus()).isEqualTo(200);
        assertThat(snapshot.title()).contains("Startseite");
        assertThat(snapshot.htmlLang()).isEqualTo("de");
        assertThat(snapshot.textContent()).contains("Startseite");
        assertThat(snapshot.textSimhash()).isNotZero();
        assertThat(snapshot.loadMillis()).isPositive();
        assertThat(snapshot.responseHeaders()).containsKey("content-type");
        assertThat(artifacts.resolve(snapshot.screenshotPath())).exists();
    }

    @Test
    void linksArePartitionedIntoInternalAndExternalWithTheirAnchorText() {
        PageSnapshot snapshot = capture("", 0);

        assertThat(snapshot.internalLinks()).extracting(LinkRef::anchorText)
                .contains("Leistungen", "Kontakt");
        assertThat(snapshot.externalLinks()).extracting(link -> link.target().value())
                .anySatisfy(url -> assertThat(url).contains("localhost:9/tot"));
        assertThat(snapshot.links()).extracting(LinkRef::rawHref).contains("/leistungen.html");
    }

    @Test
    void aBrokenImageIsRecognisedByNaturalWidthRatherThanByStatus() {
        // Spec 7.1: status 200 is not the test — the image must actually render.
        PageSnapshot snapshot = capture("", 0);

        assertThat(snapshot.images())
                .filteredOn(image -> image.rawSource().endsWith("/assets/fehlt.png"))
                .isNotEmpty()
                .allSatisfy(image -> assertThat(image.rendered()).isFalse());
        assertThat(snapshot.images())
                .filteredOn(image -> image.rawSource().endsWith("/assets/logo.png"))
                .allSatisfy(image -> assertThat(image.rendered()).isTrue());
    }

    @Test
    void srcsetCandidatesAndCssBackgroundsAreExtractedAndMeasured() {
        PageSnapshot snapshot = capture("leistungen.html", 1);

        assertThat(snapshot.images()).extracting(ImageRef::origin)
                .contains(ImageOrigin.IMG, ImageOrigin.SRCSET, ImageOrigin.CSS_BACKGROUND);
        assertThat(snapshot.images())
                .filteredOn(image -> image.origin() == ImageOrigin.CSS_BACKGROUND)
                .allSatisfy(image -> assertThat(image.rendered()).isFalse());
    }

    @Test
    void mediaMetadataIsWaitedForSoPlayabilityIsRealNotAssumed() {
        PageSnapshot snapshot = capture("medien.html", 1);

        assertThat(snapshot.media()).filteredOn(media -> media.kind() == MediaKind.AUDIO)
                .singleElement()
                .satisfies(audio -> {
                    assertThat(audio.readyState()).isGreaterThanOrEqualTo(1);
                    assertThat(audio.duration()).isGreaterThan(0.0);
                    assertThat(audio.playable()).isTrue();
                });
        assertThat(snapshot.media()).filteredOn(media -> media.kind() == MediaKind.VIDEO)
                .singleElement()
                .satisfies(video -> assertThat(video.playable()).isFalse());
    }

    @Test
    void iframesAreCapturedIncludingTheirConsoleErrorsAndBlockedState() {
        PageSnapshot snapshot = capture("kontakt.html", 1);

        assertThat(snapshot.frames()).hasSize(2);
        assertThat(snapshot.frames()).extracting(frame -> frame.src().path())
                .contains("/maps/embed/v1/place", "/blockiert");
        // The Maps billing failure is only visible in the console (spec 7.1).
        assertThat(snapshot.consoleMessages()).extracting(ConsoleMessage::text)
                .anySatisfy(text -> assertThat(text).contains("ApiNotActivatedMapError"));
        assertThat(snapshot.frames())
                .filteredOn(frame -> frame.src().path().equals("/blockiert"))
                .singleElement()
                .satisfies(frame -> assertThat(frame.contentTextLength()).isZero());
    }

    @Test
    void formFieldsCarryEnoughToClassifyThemLater() {
        PageSnapshot snapshot = capture("kontakt.html", 1);

        assertThat(snapshot.forms()).singleElement().satisfies(form -> {
            assertThat(form.id()).isEqualTo("kontaktformular");
            assertThat(form.method()).isEqualTo("post");
            assertThat(form.fields()).extracting(FormFieldRef::name)
                    .contains("name", "email", "nachricht");
            assertThat(form.fields())
                    .filteredOn(field -> field.name().equals("email"))
                    .singleElement()
                    .satisfies(field -> {
                        assertThat(field.type()).isEqualTo("email");
                        assertThat(field.label()).contains("E-Mail");
                        assertThat(field.autocomplete()).isEqualTo("email");
                        assertThat(field.required()).isTrue();
                    });
        });
    }

    @Test
    void aRedirectChainIsRecordedFromRequestedToFinalUrl() {
        PageSnapshot snapshot = capture("weiter/1", 1);

        assertThat(snapshot.redirectChain()).hasSize(4);
        assertThat(snapshot.redirectChain().getFirst()).endsWith("/weiter/1");
        assertThat(snapshot.redirectChain().getLast()).endsWith("/ziel.html");
        assertThat(snapshot.url().path()).isEqualTo("/ziel.html");
        assertThat(snapshot.requestedUrl()).endsWith("/weiter/1");
    }

    @Test
    void aFailedSubresourceIsRecorded() {
        PageSnapshot snapshot = capture("", 0);

        assertThat(snapshot.failedRequests()).extracting(FailedRequest::url)
                .anySatisfy(url -> assertThat(url).endsWith("/assets/fehlt.png"));
    }

    @Test
    void aPageThatTimesOutBecomesAnUnreachableSnapshotAndNotAnException() {
        // Spec 14: one bad page must never kill a run.
        PageSnapshot snapshot = capture("langsam", 1);

        assertThat(snapshot.reachable()).isFalse();
        assertThat(snapshot.unreachableReason()).isNotBlank();
        assertThat(snapshot.links()).isEmpty();
    }

    @Test
    void aRedirectLoopFailsTheNavigationWithoutFailingTheCrawl() {
        PageSnapshot snapshot = capture("schleife/a", 1);

        assertThat(snapshot.reachable()).isFalse();
        assertThat(snapshot.unreachableReason()).containsIgnoringCase("redirect");
    }

    @Test
    void twoCapturesOfTheSameSoftNotFoundPageAgreeOnTheirFingerprint() {
        long first = capture("gibt-es-nicht-a", 1).textSimhash();
        long second = capture("gibt-es-nicht-b", 1).textSimhash();

        assertThat(SimHash.hammingDistance(first, second)).isLessThanOrEqualTo(6);
        assertThat(capture("", 0).textSimhash()).isNotEqualTo(first);
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./mvnw test -Dtest=PageNavigatorTest`
Expected: compilation failure — `PageNavigator` does not exist.

- [ ] **Step 3: Write the extraction script**

`src/main/resources/crawler/extract.js` — one async function, evaluated once per page:

```javascript
// Extracts everything a page check could need, in a single round-trip (spec 5.2).
// Returns plain JSON: Playwright hands it to Java as nested Maps and Lists.
async () => {
  const absolute = (value) => {
    try { return new URL(value, document.baseURI).href; } catch (e) { return null; }
  };

  const links = [...document.querySelectorAll('a[href]')]
    .map(a => ({
      raw: a.getAttribute('href'),
      abs: absolute(a.getAttribute('href')),
      text: (a.textContent || '').trim().slice(0, 200),
      rel: a.getAttribute('rel') || ''
    }))
    .filter(link => link.abs);

  // Images from three origins. <img> reports naturalWidth directly; srcset candidates and CSS
  // backgrounds are never decoded by the page, so measure them — "status 200" is not the test.
  const images = [];
  const measured = new Map();
  const measure = (url) => {
    if (measured.has(url)) return measured.get(url);
    const pending = new Promise(resolve => {
      const probe = new Image();
      probe.onload = () => resolve([probe.naturalWidth, probe.naturalHeight]);
      probe.onerror = () => resolve([0, 0]);
      probe.src = url;
      setTimeout(() => resolve([0, 0]), 5000);
    });
    measured.set(url, pending);
    return pending;
  };

  for (const img of document.querySelectorAll('img')) {
    const alt = img.getAttribute('alt') || '';
    images.push({ raw: img.getAttribute('src') || '', abs: img.currentSrc || img.src,
                  alt, w: img.naturalWidth, h: img.naturalHeight, origin: 'IMG' });
    for (const part of (img.getAttribute('srcset') || '').split(',')) {
      const candidate = part.trim().split(/\s+/)[0];
      if (candidate) {
        images.push({ raw: candidate, abs: absolute(candidate), alt, w: -1, h: -1,
                      origin: 'SRCSET' });
      }
    }
  }
  for (const element of document.querySelectorAll('*')) {
    const background = getComputedStyle(element).backgroundImage;
    if (!background || background === 'none') continue;
    for (const match of background.matchAll(/url\((['"]?)(.*?)\1\)/g)) {
      const candidate = match[2];
      if (candidate && !candidate.startsWith('data:')) {
        images.push({ raw: candidate, abs: absolute(candidate), alt: '', w: -1, h: -1,
                      origin: 'CSS_BACKGROUND' });
      }
    }
  }
  await Promise.all(images.filter(i => i.w < 0 && i.abs).map(async image => {
    const [width, height] = await measure(image.abs);
    image.w = width;
    image.h = height;
  }));

  // Media: readyState >= 1 and duration > 0 are the assertions (spec 7.1), so metadata has to
  // have been given a chance to load before they are read.
  const mediaElements = [...document.querySelectorAll('video'), ...document.querySelectorAll('audio')];
  await Promise.all(mediaElements.map(element => new Promise(resolve => {
    if (element.readyState >= 1 || element.error) return resolve();
    element.addEventListener('loadedmetadata', resolve, { once: true });
    element.addEventListener('error', resolve, { once: true });
    try { element.load(); } catch (e) { /* already loading */ }
    setTimeout(resolve, 4000);
  })));
  const media = mediaElements.map(element => {
    const sources = [];
    if (element.getAttribute('src')) sources.push(absolute(element.getAttribute('src')));
    for (const source of element.querySelectorAll('source[src]')) {
      sources.push(absolute(source.getAttribute('src')));
    }
    return {
      kind: element.tagName === 'VIDEO' ? 'VIDEO' : 'AUDIO',
      sources: sources.filter(Boolean),
      readyState: element.readyState,
      duration: isFinite(element.duration) ? element.duration : 0,
      error: element.error ? 'MEDIA_ERR_' + element.error.code : null
    };
  });

  const frames = [...document.querySelectorAll('iframe')].map(frame => {
    let sameOrigin = false;
    let textLength = 0;
    try {
      const doc = frame.contentDocument;
      if (doc) {
        sameOrigin = true;
        textLength = ((doc.body && doc.body.innerText) || '').trim().length;
      }
    } catch (e) { /* cross-origin: not an error, just opaque */ }
    return {
      src: absolute(frame.getAttribute('src') || ''),
      title: frame.getAttribute('title') || '',
      loaded: !!frame.contentWindow,
      sameOrigin,
      textLength
    };
  }).filter(frame => frame.src);

  const labelOf = (element) => {
    if (element.id) {
      const label = document.querySelector('label[for="' + CSS.escape(element.id) + '"]');
      if (label) return (label.textContent || '').trim();
    }
    const wrapping = element.closest('label');
    return wrapping ? (wrapping.textContent || '').trim() : '';
  };
  const forms = [...document.querySelectorAll('form')].map(form => ({
    id: form.getAttribute('id') || '',
    action: absolute(form.getAttribute('action') || '') || '',
    method: (form.getAttribute('method') || 'get').toLowerCase(),
    fields: [...form.querySelectorAll('input, textarea, select')].map(field => ({
      name: field.getAttribute('name') || '',
      type: (field.getAttribute('type') || field.tagName).toLowerCase(),
      label: labelOf(field),
      autocomplete: field.getAttribute('autocomplete') || '',
      required: !!field.required
    }))
  }));

  return {
    title: document.title || '',
    lang: document.documentElement.getAttribute('lang') || '',
    text: (document.body && document.body.innerText) || '',
    links, images, media, frames, forms
  };
};
```

- [ ] **Step 4: Write `PageNavigator`**

Structure — write it in this order, it is all one class:

1. **Fields and constructor.** `CrawlerProperties properties`, `HostThrottle throttle`, and a
   `static final String EXTRACT_JS` read once from the classpath in a static initialiser
   (`new ClassPathResource("crawler/extract.js")`, UTF-8; wrap `IOException` in
   `IllegalStateException` — a missing script is a broken build, not a runtime condition).

2. **`capture(Browser browser, CrawlTarget target, SiteContext site, Path runArtifactDir)`** —
   the whole method runs on the worker's thread:

```java
NormalizedUrl requested = UrlNormalizer.normalize(target.url()).orElse(null);
if (requested == null) {
    return PageSnapshot.unreachable(fallbackUrl(target), target.url(), target.depth(),
            "Nicht als URL interpretierbar", List.of(), List.of());
}
throttle.await(requested.host(), properties.perHostDelay());

List<ConsoleMessage> console = Collections.synchronizedList(new ArrayList<>());
List<FailedRequest> failed = Collections.synchronizedList(new ArrayList<>());
long startedAt = System.nanoTime();

try (BrowserContext context = browser.newContext(new Browser.NewContextOptions()
        .setUserAgent(site.effectiveUserAgent())
        .setViewportSize(1366, 900)
        .setIgnoreHTTPSErrors(true)
        .setLocale("de-DE"))) {

    Page page = context.newPage();
    page.onConsoleMessage(message -> console.add(new ConsoleMessage(
            message.type(), truncate(message.text(), 500), message.location())));
    page.onPageError(error -> console.add(new ConsoleMessage(
            "error", truncate(error, 500), target.url())));
    page.onRequestFailed(request -> failed.add(new FailedRequest(
            request.url(), request.method(), request.resourceType(), null,
            request.failure())));
    page.onResponse(response -> {
        if (response.status() >= 400) {
            failed.add(new FailedRequest(response.url(), response.request().method(),
                    response.request().resourceType(), response.status(), null));
        }
    });

    Response response = page.navigate(target.url(), new Page.NavigateOptions()
            .setTimeout(properties.navigationTimeout().toMillis())
            .setWaitUntil(WaitUntilState.LOAD));
    try {
        page.waitForLoadState(LoadState.NETWORKIDLE,
                new Page.WaitForLoadStateOptions().setTimeout(5000));
    } catch (PlaywrightException stillBusy) {
        // A page with a poll or a live widget never goes idle. Not a failure — extract anyway.
    }

    // ... evaluate, screenshot, build (steps 3-5 below)
} catch (PlaywrightException e) {
    return PageSnapshot.unreachable(requested, target.url(), target.depth(),
            truncate(e.getMessage(), 500), List.copyOf(console), List.copyOf(failed));
}
```

3. **Evaluate and map.** `Object raw = page.evaluate(EXTRACT_JS);` then a private
   `Extracted map(Object raw, NormalizedUrl pageUrl, SiteContext site)` that reads the nested
   `Map<String, Object>`. Rules that matter:
   - Coerce numbers through `((Number) value).intValue()` / `.doubleValue()` — Playwright hands
     back `Integer` for whole numbers and `Double` otherwise.
   - Every `abs` string goes through `UrlNormalizer.normalize`; entries that fail to normalise
     are dropped, not turned into nulls.
   - `LinkRef.internal` is `site.baseUrl().sameSiteAs(target)`.
   - Deduplicate images by `(abs, origin)` — a background image repeated on 40 elements is one
     `ImageRef`.
   - `ImageOrigin.valueOf(origin)`, `MediaKind.valueOf(kind)`.
   - The script's key names are not the record's: `raw`/`abs`/`w`/`h` become
     `rawSource`/`target`/`naturalWidth`/`naturalHeight`, the script's `textLength` becomes
     `FrameRef.contentTextLength`, and `error` becomes `MediaRef.errorCode`. Keep the mapping in
     one method so the two vocabularies meet in exactly one place.

4. **Screenshot.** `String name = screenshotName(requested);` →
   `sha256Hex(requested.value()).substring(0, 32) + ".png"`, written with
   `page.screenshot(new Page.ScreenshotOptions().setPath(runArtifactDir.resolve(name)).setFullPage(false))`.
   Wrap in try/catch: a screenshot failure downgrades to `screenshotPath = null`, it never
   fails the page. `Files.createDirectories(runArtifactDir)` before writing.

5. **Build the snapshot.** Final URL is `UrlNormalizer.normalize(page.url()).orElse(requested)`.
   Headers: `response.allHeaders()` with keys lowercased — Playwright already lowercases them,
   but do not depend on it. Redirect chain: walk `response.request().redirectedFrom()` back to
   the origin, collecting `request.url()`, then reverse; the list always ends with the final URL
   and has size 1 when there was no redirect. `loadMillis` from `startedAt`. `textSimhash` is
   `SimHash.of(text)`. `httpStatus` is `response == null ? 0 : response.status()`.

6. **Helpers.** `truncate(String, int)`, `sha256Hex(String)`, `fallbackUrl(CrawlTarget)`
   (`new NormalizedUrl("http", "ungueltig", 80, "/", null)`).

- [ ] **Step 5: Run the test to verify it passes**

Run: `./mvnw test -Dtest=PageNavigatorTest`
Expected: PASS, all twelve cases.

Two failures are likely and both are the test being right:
- *`srcsetCandidatesAndCssBackgroundsAreExtractedAndMeasured` fails with `rendered() == true`
  for the CSS background* — `measure()` resolved before the image errored. Check that
  `onerror` resolves `[0, 0]` and that the `w < 0` filter runs over `abs`, not `raw`.
- *`aRedirectChainIsRecordedFromRequestedToFinalUrl` returns size 1* — the walk over
  `redirectedFrom()` collected only the last hop. It must loop until `redirectedFrom()` is null.

- [ ] **Step 6: Commit**

```bash
git add src/main/resources/crawler/extract.js \
        src/main/java/dev/hendrikhoemberg/webtesthelper/crawler/PageNavigator.java \
        src/test/java/dev/hendrikhoemberg/webtesthelper/crawler/PageNavigatorTest.java
git commit -m "feat(crawler): extract a PageSnapshot in one navigation

Navigate once, check many (spec 5.2): a single page.evaluate collects links,
images from all three origins, media with metadata actually awaited, frames,
forms, text and language, while Java-side listeners collect console output and
failed requests. A timeout or a redirect loop yields an unreachable snapshot
rather than an exception, so one bad page never kills a run."
```

---

### Task 4: The crawl pipeline and the run executor

Everything assembled: seed the frontier, probe for the site's not-found page, drain the frontier
in batches, respect the budgets, record coverage — and plug it into the `RunExecutor` seam Plan 1
left behind.

**Files:**
- Create: `src/main/java/…/crawler/CrawlRequest.java`, `CrawlResult.java`, `CrawlProgressListener.java`, `CrawlService.java`
- Create: `src/main/java/…/runner/CrawlRunExecutor.java`
- Create: `src/main/java/…/runner/persistence/RunResultJdbcRepository.java`
- Modify: `src/main/java/…/runner/package-info.java` (allow `crawler`)
- Delete: `src/main/java/…/runner/NoopRunExecutor.java`
- Test: `src/test/java/…/crawler/CrawlServiceTest.java`
- Test: `src/test/java/…/runner/CrawlRunExecutorTest.java`

**Interfaces:**
- Consumes: `CrawlFrontierJdbcRepository`, `BrowserPool`, `PageNavigator`, `SiteResourceFetcher`,
  `UrlAdmission`, `RobotsRules`, `SitemapReader`, `CrawlerProperties`, `SiteService`,
  `RunLeaseJdbcRepository`, `WorkerIdentity`.
- Produces:
  - `record CrawlRequest(long runId, SiteContext site, RunScope scope, String owner)`
  - `record CrawlResult(RunSnapshots snapshots, int pagesVisited, int pagesFailed, List<String> coveredUrls, boolean partialCoverage, String budgetStopReason)`
  - `interface CrawlProgressListener { void onProgress(int visited, int failed); }`
  - `CrawlService.crawl(CrawlRequest request, CrawlProgressListener listener) -> CrawlResult` (`@Service`)
  - `RunResultJdbcRepository.updateProgress(long runId, int visited, int failed)` and
    `saveCrawlOutcome(long runId, CrawlResult result, List<String> coveredCheckTypes, SoftNotFoundProbe probe)`
  - `CrawlRunExecutor implements RunExecutor` (`@Component`) — the only `RunExecutor` bean

**Two things the pipeline must get right, both of them §6.4:**

- **Coverage is the set of URLs actually visited**, not the set seeded. A run that stops on
  budget resolves nothing it did not reach, so `coveredUrls` comes from the frontier's `DONE`
  rows and nowhere else.
- **`partialCoverage` is true whenever the frontier still has `PENDING` rows** when the loop
  ends, whatever the reason. Plan 4 leans on this flag; getting it wrong makes a weekly full
  crawl report last week's unreached pages as regressed, forever.

- [ ] **Step 1: Write the failing tests**

`CrawlServiceTest` (`@Tag("browser")`, extends `AbstractPostgresTest`) — the pipeline against the
fixture site:

```java
@Tag("browser")
class CrawlServiceTest extends AbstractPostgresTest {

    @Autowired CrawlService crawler;
    @Autowired JdbcTemplate jdbc;
    @Autowired CrawlFrontierJdbcRepository frontier;

    private static FixtureSite site;
    private long siteId;
    private long runId;

    @BeforeAll static void startSite() { site = FixtureSite.start(); }
    @AfterAll static void stopSite() { site.close(); }

    @BeforeEach
    void freshRun() {
        jdbc.update("DELETE FROM crawl_queue_item");
        jdbc.update("DELETE FROM run");
        jdbc.update("DELETE FROM site");
        siteId = jdbc.queryForObject("INSERT INTO site (name, base_url) VALUES (?, ?) RETURNING id",
                Long.class, "Fixture", site.baseUrl());
        runId = jdbc.queryForObject("INSERT INTO run (site_id, trigger_type, scope, status) "
                + "VALUES (?, 'MANUAL', 'FULL', 'RUNNING') RETURNING id", Long.class, siteId);
    }

    private static CrawlBudget budget(int maxPages, int maxDepth, Duration maxDuration) {
        return new CrawlBudget(maxPages, maxDepth, maxDuration);
    }

    /**
     * The SiteContext is built here rather than read back through SiteService: these cases vary
     * the budget and the pinned pages, and the site row never needs to.
     */
    private CrawlRequest request(RunScope scope, CrawlBudget budget, List<String> pinned) {
        SiteContext context = new SiteContext(siteId, "Fixture",
                UrlNormalizer.normalize(site.baseUrl()).orElseThrow(), budget,
                List.of(), List.of(), pinned, true, null, Map.of());
        return new CrawlRequest(runId, context, scope, "test-worker");
    }

    private CrawlResult crawl(RunScope scope, CrawlBudget budget, List<String> pinned) {
        return crawler.crawl(request(scope, budget, pinned), (visited, failed) -> { });
    }

    @Test
    void aFullCrawlReachesEveryCrawlablePageExactlyOnce() {
        CrawlResult result = crawl(RunScope.FULL, budget(50, 3, Duration.ofMinutes(3)), List.of());

        assertThat(result.coveredUrls()).doesNotHaveDuplicates();
        assertThat(result.coveredUrls()).anySatisfy(url -> assertThat(url).endsWith("/leistungen.html"));
        assertThat(result.coveredUrls()).anySatisfy(url -> assertThat(url).endsWith("/kontakt.html"));
        assertThat(result.coveredUrls()).anySatisfy(url -> assertThat(url).endsWith("/medien.html"));
        assertThat(result.snapshots().pageCount()).isEqualTo(result.pagesVisited() + result.pagesFailed());
        assertThat(result.partialCoverage()).isFalse();
        assertThat(result.budgetStopReason()).isNull();
    }

    @Test
    void theRobotsDisallowedPageIsNeverVisited() {
        CrawlResult result = crawl(RunScope.FULL, budget(50, 3, Duration.ofMinutes(3)), List.of());
        assertThat(result.coveredUrls()).noneSatisfy(url -> assertThat(url).contains("/geheim/"));
    }

    @Test
    void assetsAndOffSiteLinksNeverEnterTheFrontier() {
        crawl(RunScope.FULL, budget(50, 3, Duration.ofMinutes(3)), List.of());
        List<String> queued = jdbc.queryForList(
                "SELECT url FROM crawl_queue_item WHERE run_id = ?", String.class, runId);
        assertThat(queued).noneSatisfy(url -> assertThat(url).endsWith(".pdf"));
        assertThat(queued).allSatisfy(url -> assertThat(url).contains("127.0.0.1"));
    }

    @Test
    void theSoftNotFoundProbeLearnsTheSitesNotFoundPage() {
        CrawlResult result = crawl(RunScope.FULL, budget(50, 3, Duration.ofMinutes(3)), List.of());

        SoftNotFoundProbe probe = result.snapshots().softNotFound();
        assertThat(probe.usable()).isTrue();
        assertThat(probe.httpStatus()).isEqualTo(200);        // the fixture is a soft-404 site
        assertThat(probe.simhash()).isNotZero();
        // …and a page that IS the not-found page fingerprints close to it.
        PageSnapshot verirrt = result.snapshots().snapshots().stream()
                .filter(s -> s.url().path().equals("/verirrt.html")).findFirst().orElseThrow();
        assertThat(SimHash.hammingDistance(verirrt.textSimhash(), probe.simhash()))
                .isLessThanOrEqualTo(6);
    }

    @Test
    void aPageBudgetStopsTheRunWithPartialCoverageAndResolvesNothingItDidNotReach() {
        CrawlResult result = crawl(RunScope.FULL, budget(2, 3, Duration.ofMinutes(3)), List.of());

        assertThat(result.pagesVisited()).isLessThanOrEqualTo(2);
        assertThat(result.coveredUrls()).hasSizeLessThanOrEqualTo(2);
        assertThat(result.partialCoverage()).isTrue();
        assertThat(result.budgetStopReason()).isEqualTo("maxPages");
        assertThat(frontier.countPending(runId)).isPositive();
    }

    @Test
    void maxDepthTruncatesDiscoveryWithoutBeingABudgetStop() {
        CrawlResult result = crawl(RunScope.FULL, budget(50, 0, Duration.ofMinutes(3)), List.of());

        assertThat(result.coveredUrls()).hasSize(1);          // the start page only
        assertThat(result.budgetStopReason()).isNull();       // the frontier simply ran dry
        assertThat(result.partialCoverage()).isFalse();
    }

    @Test
    void aPulseScopeCrawlsOnlyThePinnedKeyPages() {
        CrawlResult result = crawl(RunScope.PULSE, budget(50, 3, Duration.ofMinutes(3)),
                List.of("/kontakt.html"));

        assertThat(result.coveredUrls()).singleElement()
                .satisfies(url -> assertThat(url).endsWith("/kontakt.html"));
    }

    @Test
    void anUnreachablePageIsCountedAsFailedAndDoesNotKillTheRun() {
        // /langsam is linked from nowhere, so seed it directly through a pinned PULSE run.
        CrawlResult result = crawl(RunScope.PULSE, budget(50, 3, Duration.ofSeconds(60)),
                List.of("/langsam"));

        assertThat(result.pagesFailed()).isEqualTo(1);
        assertThat(result.pagesVisited()).isZero();
        assertThat(result.coveredUrls()).isEmpty();           // never reached, never covered
        assertThat(result.snapshots().pageCount()).isEqualTo(1);
    }

    @Test
    void progressIsReportedDuringTheCrawlNotOnlyAtTheEnd() {
        List<Integer> reports = Collections.synchronizedList(new ArrayList<>());
        crawler.crawl(request(RunScope.FULL, budget(50, 3, Duration.ofMinutes(3)), List.of()),
                (visited, failed) -> reports.add(visited));
        assertThat(reports).isNotEmpty().isSorted();
    }
}
```

`CrawlRunExecutorTest` (`@Tag("browser")`, extends `AbstractPostgresTest`) — the whole plan's
acceptance test: enqueue a run, turn the Plan 1 worker once, read the `run` row. It is
named `…Test`, not `…IT`: surefire's default includes match on class name and would silently
skip an `*IT` class, leaving the whole plan's acceptance unproven by `./mvnw test`.

```java
@Tag("browser")
class CrawlRunExecutorTest extends AbstractPostgresTest {

    @Autowired RunWorker worker;
    @Autowired RunService runs;
    @Autowired SiteService sites;
    @Autowired JdbcTemplate jdbc;
    @Autowired CrawlerProperties properties;

    private static FixtureSite site;
    private long siteId;

    @BeforeAll static void startSite() { site = FixtureSite.start(); }
    @AfterAll static void stopSite() { site.close(); }

    @BeforeEach
    void freshSite() {
        jdbc.update("DELETE FROM crawl_queue_item");
        jdbc.update("DELETE FROM run");
        jdbc.update("DELETE FROM site_check_setting");
        jdbc.update("DELETE FROM site");
        siteId = sites.create(new SiteForm("Fixture", site.baseUrl(), 30, 3,
                Duration.ofMinutes(3), List.of(), List.of(), true, null));
    }

    @Test
    void aManualRunCrawlsTheFixtureSiteEndToEnd() {
        long runId = runs.enqueue(siteId, RunTrigger.MANUAL, RunScope.FULL);

        assertThat(worker.workOnce()).isTrue();

        assertThat(jdbc.queryForObject("SELECT status FROM run WHERE id = ?", String.class, runId))
                .isEqualTo("COMPLETED");
        assertThat(jdbc.queryForObject("SELECT pages_visited FROM run WHERE id = ?", Integer.class, runId))
                .isGreaterThanOrEqualTo(6);
        assertThat(jdbc.queryForObject("SELECT partial_coverage FROM run WHERE id = ?", Boolean.class, runId))
                .isFalse();
        assertThat(jdbc.queryForObject("SELECT soft404_status FROM run WHERE id = ?", Integer.class, runId))
                .isEqualTo(200);
        assertThat(jdbc.queryForObject("SELECT soft404_simhash FROM run WHERE id = ?", Long.class, runId))
                .isNotZero();
        assertThat(jdbc.queryForObject("SELECT lease_owner FROM run WHERE id = ?", String.class, runId))
                .isNull();
    }

    @Test
    void theRunRecordsWhatItCovered() {
        long runId = runs.enqueue(siteId, RunTrigger.MANUAL, RunScope.FULL);
        worker.workOnce();

        String coveredUrls = jdbc.queryForObject(
                "SELECT covered_urls::text FROM run WHERE id = ?", String.class, runId);
        assertThat(coveredUrls).contains("/leistungen.html").contains("/kontakt.html");

        String coveredCheckTypes = jdbc.queryForObject(
                "SELECT covered_check_types::text FROM run WHERE id = ?", String.class, runId);
        assertThat(coveredCheckTypes).contains("PAGE_STATUS").contains("DEAD_LINK");
        // Spec 7.1: these two ship disabled, so a run does not claim to have covered them.
        assertThat(coveredCheckTypes).doesNotContain("CONSOLE_ERRORS")
                .doesNotContain("SITEMAP_CONSISTENCY");
    }

    @Test
    void theFrontierAndTheArtifactsSurviveTheRun() {
        long runId = runs.enqueue(siteId, RunTrigger.MANUAL, RunScope.FULL);
        worker.workOnce();

        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM crawl_queue_item WHERE run_id = ? AND status = 'DONE'",
                Integer.class, runId)).isGreaterThanOrEqualTo(6);
        assertThat(properties.artifactDir().resolve(String.valueOf(runId))).exists();
        assertThat(properties.artifactDir().resolve(String.valueOf(runId)).toFile().list())
                .isNotEmpty();
    }

    @Test
    void aSiteThatCannotBeReachedFailsTheRunRatherThanHangingIt() {
        long deadSiteId = sites.create(new SiteForm("Tot", "http://localhost:9/", 10, 2,
                Duration.ofSeconds(30), List.of(), List.of(), true, null));
        long runId = runs.enqueue(deadSiteId, RunTrigger.MANUAL, RunScope.FULL);

        assertThat(worker.workOnce()).isTrue();

        // The start page is unreachable, so the crawl completes having visited nothing.
        assertThat(jdbc.queryForObject("SELECT status FROM run WHERE id = ?", String.class, runId))
                .isEqualTo("COMPLETED");
        assertThat(jdbc.queryForObject("SELECT pages_visited FROM run WHERE id = ?", Integer.class, runId))
                .isZero();
        assertThat(jdbc.queryForObject("SELECT pages_failed FROM run WHERE id = ?", Integer.class, runId))
                .isEqualTo(1);
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./mvnw test -Dtest='CrawlServiceTest,CrawlRunExecutorTest'`
Expected: compilation failure — `CrawlService` does not exist.

- [ ] **Step 3: Write `CrawlService`**

The value types first: `CrawlRequest`, `CrawlResult` and `CrawlProgressListener` exactly as in
**Interfaces** above. Then the service. Its `crawl` method, in order:

```java
Path runArtifacts = properties.artifactDir().resolve(String.valueOf(request.runId()));
Files.createDirectories(runArtifacts);

RobotsRules robots = request.site().respectRobots()
        ? UrlNormalizer.resolve(request.site().baseUrl().value(), "/robots.txt")
            .flatMap(fetcher::fetchText)
            .map(RobotsRules::parse)
            .orElse(RobotsRules.ALLOW_ALL)
        : RobotsRules.ALLOW_ALL;
UrlAdmission admission = new UrlAdmission(request.site(), robots);

// A run whose lease expired is re-queued and re-executed (spec 14) with its dead worker's
// rows still CLAIMED: never visited, never pending, so the resumed run would claim full
// coverage for pages it never reached (spec 6.4).
frontier.reclaimStale(request.runId(), STALE_CLAIM_TIMEOUT, MAX_CLAIM_ATTEMPTS);

SoftNotFoundProbe probe = probe(request, runArtifacts);
seedFrontier(request, admission, robots);

List<PageSnapshot> snapshots = Collections.synchronizedList(new ArrayList<>());
AtomicInteger visited = new AtomicInteger();
AtomicInteger failed = new AtomicInteger();
Instant deadline = Instant.now().plus(request.site().budget().maxDuration());
int maxPages = request.site().budget().maxPages();
String stopReason = null;

try (ExecutorService fanOut = Executors.newVirtualThreadPerTaskExecutor()) {
    while (true) {
        if (visited.get() >= maxPages) { stopReason = "maxPages"; break; }
        if (Instant.now().isAfter(deadline)) { stopReason = "maxDuration"; break; }

        int room = Math.min(properties.batchSize(), maxPages - visited.get());
        List<CrawlTarget> batch = frontier.claimBatch(request.runId(), request.owner(), room);
        if (batch.isEmpty()) {
            break;                       // the frontier ran dry — a complete crawl
        }
        List<Future<CrawlOutcome>> pending = batch.stream()
                .map(target -> fanOut.submit(() -> visit(
                        target, request, admission, runArtifacts, snapshots, visited, failed)))
                .toList();
        List<CrawlOutcome> outcomes = new ArrayList<>(pending.size());
        for (Future<CrawlOutcome> future : pending) {
            outcomes.add(future.get());  // visit() never throws; it returns a FAILED outcome
        }
        frontier.complete(outcomes);
        listener.onProgress(visited.get(), failed.get());
    }
}

List<String> coveredUrls = frontier.visitedUrls(request.runId());
boolean partial = frontier.countPending(request.runId()) > 0;
return new CrawlResult(
        new RunSnapshots(request.runId(), request.site(), List.copyOf(snapshots), probe),
        visited.get(), failed.get(), coveredUrls, partial, stopReason);
```

The fan-out is virtual threads *submitting into* `BrowserPool`, which is what bounds real
concurrency to the pool size. The virtual threads only wait; the browsers do the work.

`visit(...)` — never throws, always returns an outcome:

```java
PageSnapshot snapshot = pool.submit(browser ->
        navigator.capture(browser, target, request.site(), runArtifacts));
snapshots.add(snapshot);
if (!snapshot.reachable()) {
    failed.incrementAndGet();
    return new CrawlOutcome(target.id(), CrawlItemStatus.FAILED, null, snapshot.unreachableReason());
}
visited.incrementAndGet();
if (request.scope().crawlsWholeSite()) {
    List<String> discovered = snapshot.internalLinks().stream()
            .map(LinkRef::target)
            .filter(url -> admission.admit(url, target.depth() + 1).admitted())
            .map(NormalizedUrl::value)
            .distinct()
            .toList();
    frontier.enqueue(request.runId(), discovered, target.depth() + 1, target.url());
}
return new CrawlOutcome(target.id(), CrawlItemStatus.DONE, snapshot.httpStatus(), null);
```

Wrap the body in `try/catch (RuntimeException e)` returning
`new CrawlOutcome(target.id(), CrawlItemStatus.FAILED, null, truncate(e.getMessage(), 500))` —
a browser worker that dies mid-page must cost one URL, not the run (§14).

`seedFrontier(...)`:

```java
if (!request.scope().crawlsWholeSite()) {                       // PULSE (spec 9)
    // Pinning is not a way around robots or the site's exclude patterns (spec 8), so the
    // pinned set goes through admission too — at depth 0, these being entry points.
    List<String> pinned = request.site().pinnedKeyPages().stream()
            .map(page -> UrlNormalizer.resolve(request.site().baseUrl().value(), page))
            .flatMap(Optional::stream)
            .filter(page -> admission.admit(page, 0).admitted())
            .map(NormalizedUrl::value).toList();
    frontier.seed(request.runId(), pinned, 0);
    return;
}
// The base URL is seeded unfiltered: a site whose include patterns miss its own start page
// would otherwise crawl nothing at all.
frontier.seed(request.runId(), List.of(request.site().baseUrl().value()), 0);

// sitemap.xml, one level of sitemap-index following (spec 5.3). Depth 1, not 0: sitemap
// entries sit one level below the base URL, and at depth 0 a maxDepth=0 run would crawl
// every page the sitemap names (execution finding).
for (NormalizedUrl sitemapUrl : sitemapUrls(request.site(), robots)) {
    fetcher.fetchText(sitemapUrl).ifPresent(xml -> {
        List<String> locations = sitemapLocations(sitemapUrl, xml);   // follows an index once
        List<String> admitted = locations.stream()
                .map(UrlNormalizer::normalize).flatMap(Optional::stream)
                .filter(candidate -> admission.admit(candidate, 1).admitted())
                .map(NormalizedUrl::value).toList();
        frontier.enqueue(request.runId(), admitted, 1, "sitemap.xml");
    });
}
```

`sitemapUrls` is `robots.sitemaps()` resolved against the base URL, falling back to
`{base}/sitemap.xml` when robots names none. Guard the index recursion with a hard cap of 10
child sitemaps — a malformed index that points at itself must not become an infinite loop.

`probe(...)` — deviation D11, it navigates:

```java
NormalizedUrl probeUrl = UrlNormalizer
        .resolve(request.site().baseUrl().value(), "/" + UUID.randomUUID()).orElseThrow();
PageSnapshot snapshot = pool.submit(browser -> navigator.capture(browser,
        new CrawlTarget(-1L, probeUrl.value(), 0), request.site(), runArtifacts));
return snapshot.reachable()
        ? new SoftNotFoundProbe(snapshot.httpStatus(), snapshot.textSimhash(),
                                snapshot.textContent().length())
        : SoftNotFoundProbe.NONE;
```

The probe is not added to `snapshots` and its frontier id is `-1`, so it is never completed —
it is a measurement of the site, not a page of it.

Wrap the whole method body so one run's history is greppable (§14):

```java
MDC.put("runId", String.valueOf(request.runId()));
MDC.put("siteId", String.valueOf(request.site().siteId()));
try {
    // the body above
} finally {
    MDC.remove("runId");
    MDC.remove("siteId");
}
```

MDC is thread-local, so these values do not reach the fan-out's virtual threads or the browser
worker threads. Setting them on the crawl thread is still worth it: every seeding, budget and
completion line carries them, and Plan 5 adds the log pattern that prints them.

- [ ] **Step 4: Write the runner side and delete the no-op**

`RunResultJdbcRepository` — `JdbcTemplate`, Jackson `ObjectMapper` for the two `jsonb` columns:

```java
private static final String PROGRESS_SQL =
        "UPDATE run SET pages_visited = ?, pages_failed = ? WHERE id = ?";

private static final String OUTCOME_SQL = """
        UPDATE run
           SET pages_visited       = ?,
               pages_failed        = ?,
               covered_check_types = ?::jsonb,
               covered_urls        = ?::jsonb,
               partial_coverage    = ?,
               budget_stop_reason  = ?,
               soft404_status      = ?,
               soft404_simhash     = ?,
               soft404_text_length = ?
         WHERE id = ?
        """;
```

Serialise with `objectMapper.writeValueAsString(list)`, wrapping `JsonProcessingException` in
`IllegalStateException` — a list of strings that will not serialise is a bug, not a condition.

`CrawlRunExecutor`:

```java
@Component
public class CrawlRunExecutor implements RunExecutor {

    private static final Duration LEASE_EXTENSION = Duration.ofMinutes(30);

    // fields: CrawlService crawler, SiteService sites, RunResultJdbcRepository results,
    //         RunLeaseJdbcRepository leases, WorkerIdentity identity

    @Override
    public void execute(RunLease lease) {
        SiteContext site = sites.contextFor(lease.siteId());
        CrawlResult result = crawler.crawl(
                new CrawlRequest(lease.runId(), site, lease.scope(), identity.name()),
                (visited, failed) -> {
                    // A crawl outlives the 30-minute lease it was claimed under; without this
                    // the sweep would reclaim a run that is perfectly healthy (spec 14).
                    leases.heartbeat(lease.runId(), identity.name(), LEASE_EXTENSION);
                    results.updateProgress(lease.runId(), visited, failed);
                });

        List<String> coveredCheckTypes = lease.scope().checkTypes().stream()
                .filter(site::enabled)
                .map(Enum::name)
                .sorted()
                .toList();
        results.saveCrawlOutcome(lease.runId(), result, coveredCheckTypes,
                result.snapshots().softNotFound());
    }
}
```

Then:
- `rm src/main/java/dev/hendrikhoemberg/webtesthelper/runner/NoopRunExecutor.java` — leaving it
  would make the `RunExecutor` injection into `RunWorker` ambiguous and fail context startup.
- `runner/package-info.java` becomes
  `allowedDependencies = {"model", "catalog", "crawler"}`.

**Plan 3 note, deliberately not done here:** `CrawlRunExecutor` currently discards
`result.snapshots()` after writing coverage. Plan 3 inserts the check pass between the crawl and
`saveCrawlOutcome`, which is why `CrawlResult` carries the whole `RunSnapshots` rather than just
counts.

- [ ] **Step 5: Run the tests to verify they pass**

Run: `./mvnw test -Dtest='CrawlServiceTest,CrawlRunExecutorTest'`
Expected: PASS.

If `aFullCrawlReachesEveryCrawlablePageExactlyOnce` reports duplicates, the discovery enqueue is
not going through `NormalizedUrl.value()` — the frontier's uniqueness is per normalised URL, and
`/leistungen.html` versus `/leistungen.html#kontakt` must be one row.

- [ ] **Step 6: Run the whole suite and commit**

Run: `./mvnw test`
Expected: PASS, including `ModularityTest` — which now proves `runner → crawler` and would fail
loudly if `crawler` ever imported `runner`.

```bash
git add src/main/java/dev/hendrikhoemberg/webtesthelper/crawler \
        src/main/java/dev/hendrikhoemberg/webtesthelper/runner \
        src/test/java/dev/hendrikhoemberg/webtesthelper/crawler \
        src/test/java/dev/hendrikhoemberg/webtesthelper/runner
git rm src/main/java/dev/hendrikhoemberg/webtesthelper/runner/NoopRunExecutor.java
git commit -m "feat(crawler): crawl a site end to end and record its coverage

Seeds the frontier from the base URL and sitemap, probes {base}/{uuid} for the
site's not-found fingerprint, then drains the frontier in batches through the
browser pool. Budgets end a run cleanly with partial coverage, and coverage is
the set of URLs actually visited — so a capped run resolves nothing it did not
reach (spec 6.4). Replaces NoopRunExecutor."
```

---

## Plan 2b completion check

- [x] `./mvnw test` passes, browser tests included (161 tests, 0 failures)
- [x] `./mvnw test -DexcludedGroups=browser` also passes (129 tests) — no non-browser test grew
      a browser dependency
- [x] Four commits landed (one per task)
- [x] `NoopRunExecutor` is gone and `CrawlRunExecutor` is the only `RunExecutor` bean
- [x] `ModularityTest` proves `runner → crawler` and rejects the reverse
- [x] No `Page`, `BrowserContext`, `Browser` or `Playwright` reference escapes a
      `BrowserPool.submit` block — the only hit outside `BrowserPool` is `PageNavigator`
      catching `PlaywrightException`, inside the confined call
- [x] No migration was added; `ddl-auto=validate` still passes
- [x] **Roadmap plan 2 is done.** Write `2026-08-21-webtesthelper-p3-checks.md` next, feeding
      back anything execution revealed.

## What Plan 3 consumes from this plan

- `model.RunSnapshots` — the `SiteCheck` SPI's first parameter (§7.3), carrying every
  `PageSnapshot` of the run plus the `SoftNotFoundProbe`
- `model.PageSnapshot` — the `PageCheck` SPI's first parameter; hand-build them in check unit
  tests, no browser required
- `crawler.SiteResourceFetcher` — grows into asset verification on virtual threads (§5.3)
- `crawler.BrowserPool` — Plan 3's interaction checks are not in Phase 1, but the pool is what
  the end-of-run re-verification pass (§8) will use
- `runner.CrawlRunExecutor` — Plan 3 inserts the check pass between `crawler.crawl(...)` and
  `results.saveCrawlOutcome(...)`
- `support.FixtureSite` — every check is developed and regression-tested against it
