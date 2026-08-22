# WebTestHelper Plan 2a — Fixture Site, `PageSnapshot` and the Crawl Frontier

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build everything the crawl needs before a browser is involved: the fixture site containing one of every failure mode, the `PageSnapshot` value family every check will read, and the durable batched crawl frontier.

**Architecture:** *Navigate once, check many* (§5.2) — the crawler's only job will be to turn URLs into immutable `PageSnapshot` records, so those records are defined first, in the dependency-free `model` package, and unit-tested by hand without a browser. The frontier is a database table claimed and completed in batches (§6.5, §14), which is what makes a run resumable and its progress live.

**Tech Stack:** Java 25, Spring Boot 4.1.1, PostgreSQL 17 via Testcontainers, `JdbcTemplate`, `com.sun.net.httpserver.HttpServer` (JDK built-in) for the fixture site. **No Playwright in this plan** — Plan 2b starts the browser.

**Spec:** `docs/superpowers/specs/2026-08-21-webtesthelper-design.md` — read it alongside this plan. Section references like (§5.4) point there.
**Roadmap:** `docs/superpowers/plans/2026-08-21-webtesthelper-phase-1-roadmap.md` — this is the first half of plan 2 of 5. The roadmap caps a plan at ~1,500 lines and splits when it would exceed that; plan 2 exceeded it, so the browser half is `2026-08-21-webtesthelper-p2b-browser-crawl.md`.
**Predecessor:** `docs/superpowers/plans/2026-08-21-webtesthelper-p1-foundation.md` — executed; its six commits are on `main`.

**Ends with:** the fixture site serving every failure mode of §15, a `PageSnapshot` family proven by hand-built unit tests, and a frontier that two workers can claim from concurrently without ever handing out the same URL twice.

---

## What Plan 1 leaves you

Read these before starting; this plan builds directly on them.

| Type | Location | What it gives you |
|---|---|---|
| `model.NormalizedUrl` | record `(scheme, host, port, path, query)` | `value()`, `origin()`, `locationKey()`, `registrableHost()`, `sameSiteAs()`, `isSecure()` |
| `model.UrlNormalizer` | static | `normalize(String) -> Optional<NormalizedUrl>`, `resolve(String base, String href) -> Optional<NormalizedUrl>`, `key(String) -> Optional<String>` |
| `model.SiteContext` | record | `siteId`, `baseUrl`, `budget`, include/exclude patterns, `pinnedKeyPages`, `respectRobots`, `effectiveUserAgent()`, `enabled(CheckType)` |
| `model.CrawlBudget` | record | `maxPages`, `maxDepth`, `maxDuration` |
| `model.RunScope` | enum | `checkTypes()`, `crawlsWholeSite()` — `PULSE` crawls only pinned key pages |
| `runner.RunExecutor` | interface | `execute(RunLease) throws Exception` — **the seam this plan fills** |
| `runner.NoopRunExecutor` | `@Component` | **deleted in Task 7** |
| `runner.RunLease` | record | `runId`, `siteId`, `scope`, `trigger`, `leaseExpiresAt` |
| `runner.persistence.RunLeaseJdbcRepository` | `@Repository` | `claimNext`, `heartbeat(runId, owner, extendBy)`, `finish`, `reclaimExpiredLeases` |
| `catalog.SiteService` | `@Service` | `contextFor(long siteId) -> SiteContext` |
| `support.AbstractPostgresTest` | test base | singleton Testcontainers Postgres, `@ActiveProfiles("test")` |

The `run` table already carries every column this plan writes: `pages_visited`, `pages_failed`,
`covered_check_types`, `covered_urls`, `partial_coverage`, `budget_stop_reason`,
`soft404_simhash`, `soft404_status`, `soft404_text_length`. No migration touches `run`.

## Deviations applying to this plan

Carried forward: **D1** (`model` holds shared value types; `checks`/`findings` depend only on it).
New, and recorded in the roadmap at the end of this plan (D8 belongs to Plan 2b):

- **D5 — Module direction is `runner → crawler`.** `crawler` declares
  `allowedDependencies = {"model"}` and never imports `runner`. The `RunExecutor`
  implementation stays in `runner`, calls `crawler.CrawlService`, and owns everything that
  writes to the `run` table. The crawler owns only the frontier.
- **D6 — The fixture site is served over plain HTTP.** A self-signed HTTPS listener would
  buy one check (`MIXED_CONTENT`, which needs a secure page) at the cost of certificate
  plumbing in every browser context. `PageSnapshot` records the page's scheme, so
  `MIXED_CONTENT` is proven in Plan 3 from a hand-built snapshot — which is what §5.2 says
  page checks are for. The fixture still carries a mixed-content page so the crawl
  extracts the http subresource on an http page.
- **D12 — The fixture's *working* media source is audio, not video.** §15 asks for "a video
  with a broken source and one with a working source". The smallest genuinely playable MP4 is a
  ~10 KB binary blob to check in and maintain; a playable WAV is 44 header bytes plus samples,
  generated in four lines of Java. So the fixture serves a broken `<video>` and a working
  `<audio>`, which exercises both sides of `MEDIA_PLAYABLE.playable()` — `readyState >= 1` and
  `duration > 0` are element properties, identical for both tags.
- **D7 — Snapshots are memory-resident for the length of a run.** `RunSnapshots` holds
  every `PageSnapshot` of the run, because site checks need cross-page knowledge (§5.2) and
  materialisation is a single end-of-run step (§6.5). Bounded by `CrawlBudget.maxPages`
  (default 300). Screenshots and page HTML go to disk, not into the record.
## Global Constraints

Every task's requirements implicitly include this section, plus Plan 1's.

- **Java 25, Spring Boot 4.1.1.** `pom.xml` needs **no new dependencies** in this plan — every
  library used here is already declared or is part of the JDK.
- **`spring.jpa.hibernate.ddl-auto=validate` everywhere, tests included.** Flyway owns the schema.
- **Repository tests run against real PostgreSQL** via `AbstractPostgresTest`. No H2, ever.
- **Nothing in any test touches a real website** (§15). Every URL a test resolves is either
  the fixture site's loopback address or a deliberately dead loopback port.
- **`checks` and `findings` do not exist yet.** Do not create them, do not evaluate anything,
  do not emit a `CheckFinding`. The crawler produces snapshots; Plan 3 checks them.
- **No cross-module JPA associations.** The frontier is `JdbcTemplate`-only — it is one of the
  two high-volume tables JPA is explicitly not used for (§6.5).
- **Java `record`s for value types; Lombok only for JPA entities.**
- **No browser code here.** If you find yourself importing `com.microsoft.playwright`, you are
  writing Plan 2b. The fixture site is exercised over plain HTTP in this plan.
- **Commit after every task.** Conventional commits; code and commits in English, only
  user-facing strings in German.


### Task 1: The fixture site

> *"The fixture site is the highest-value asset in the project"* (§15). Every check in Plans 3
> and 4 is developed and regression-tested against it. Build it first and build it complete —
> a failure mode missing here is a check that never gets tested.

It is a `com.sun.net.httpserver.HttpServer` (JDK built-in — no dependency, no Spring, starts in
milliseconds) bound to `127.0.0.1:0`. Static pages come from the test classpath; everything
whose *response* is the interesting part — statuses, redirects, content types, binary bodies —
is a Java route.

**Two loopback names, deliberately.** The site is served as `http://127.0.0.1:{port}/`, so links
to `http://localhost:{port}/...` are *external* by `NormalizedUrl.registrableHost()` while being
the same server. That gives the crawl a real external link without leaving the machine.
`http://localhost:9/…` (the discard port) is a deterministic dead external link.

**Files:**
- Create: `src/test/java/dev/hendrikhoemberg/webtesthelper/support/FixtureSite.java`
- Create: `src/test/resources/fixture-site/index.html`
- Create: `src/test/resources/fixture-site/leistungen.html`
- Create: `src/test/resources/fixture-site/kontakt.html`
- Create: `src/test/resources/fixture-site/medien.html`
- Create: `src/test/resources/fixture-site/mixed-content.html`
- Create: `src/test/resources/fixture-site/ziel.html`
- Create: `src/test/resources/fixture-site/geheim/intern.html`
- Create: `src/test/resources/fixture-site/en/index.html`
- Create: `src/test/resources/fixture-site/robots.txt`
- Create: `src/test/resources/fixture-site/sitemap.xml`
- Test: `src/test/java/dev/hendrikhoemberg/webtesthelper/support/FixtureSiteTest.java`

**Interfaces:**
- Consumes: nothing.
- Produces:
  - `FixtureSite.start() -> FixtureSite` (implements `AutoCloseable`)
  - `FixtureSite.baseUrl() -> String` — e.g. `http://127.0.0.1:38271/`
  - `FixtureSite.url(String path) -> String` — `path` without a leading slash
  - `FixtureSite.externalBase() -> String` — the same server under `localhost`
  - `FixtureSite.port() -> int`

**The route table.** Every row exists because a check needs it.

| Route | Response | Exercises |
|---|---|---|
| `/` `/leistungen.html` `/kontakt.html` `/medien.html` `/mixed-content.html` `/ziel.html` `/en/index.html` `/geheim/intern.html` | static, 200 `text/html` | the crawl |
| `/assets/logo.png` | 200 `image/png`, 1×1 PNG | a valid image |
| `/assets/fehlt.png` | 404 | `IMAGE_BROKEN` — and it is in every page's footer, so it is the **site-wide promotion** case (§6.2) |
| `/dateien/handbuch.pdf` | 200 `application/pdf`, `%PDF` magic, >1 KB | `FILE_DOWNLOAD` passing |
| `/dateien/preisliste.pdf` | 200 **`text/html`**, a login page | `FILE_DOWNLOAD` — the trap: 200 is not enough (§7.1) |
| `/weiter/1` → `/weiter/2` → `/weiter/3` → `/ziel.html` | three 302s | `REDIRECT_CHAIN` — long chain |
| `/schleife/a` ⇄ `/schleife/b` | mutual 302 | `REDIRECT_CHAIN` — loop |
| `/medien/ton.wav` | 200 `audio/wav`, 0.5 s generated tone | `MEDIA_PLAYABLE` passing (`readyState ≥ 1`, `duration > 0`) |
| `/medien/fehlt.mp4` | 404 | `MEDIA_PLAYABLE` failing |
| `/maps/embed/v1/place` | 200 grey box + `console.error("… ApiNotActivatedMapError")` | `IFRAME_EMBED` — the Maps billing failure (§7.1) |
| `/blockiert` | 200 + `X-Frame-Options: DENY` | `IFRAME_EMBED` — blocked embed |
| `/hart-404` | 404 `text/html` | a true not-found |
| `/langsam` | 200 after 5 s | navigation timeout → `PAGE_UNREACHABLE` |
| `/extern/ok` | 200 | a healthy external link (reached via `localhost`) |
| `/robots.txt` `/sitemap.xml` | static | robots policy, `SITEMAP_CONSISTENCY` |
| **anything else** | **200** + *"Seite nicht gefunden"* | **the soft 404** (§7.1) — and therefore what the `{baseUrl}/{uuid}` probe learns |

The catch-all returning 200 is the point: it is what makes the fixture a soft-404 site, so the
probe has something to fingerprint and `/verirrt.html` (linked from `index.html`) is a real soft 404.

- [ ] **Step 1: Write the failing test**

`src/test/java/dev/hendrikhoemberg/webtesthelper/support/FixtureSiteTest.java`:

```java
package dev.hendrikhoemberg.webtesthelper.support;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class FixtureSiteTest {

    private static FixtureSite site;
    private static HttpClient client;

    @BeforeAll
    static void startSite() {
        site = FixtureSite.start();
        client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(Duration.ofSeconds(2))
                .build();
    }

    @AfterAll
    static void stopSite() {
        site.close();
    }

    private HttpResponse<byte[]> get(String path) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create(site.url(path))).build(),
                HttpResponse.BodyHandlers.ofByteArray());
    }

    @Test
    void staticPagesAreServedAndPortPlaceholdersAreSubstituted() throws Exception {
        HttpResponse<byte[]> response = get("");
        assertThat(response.statusCode()).isEqualTo(200);
        String body = new String(response.body(), java.nio.charset.StandardCharsets.UTF_8);
        assertThat(body).contains("Startseite");
        assertThat(body).doesNotContain("{{PORT}}");
        assertThat(body).contains("http://localhost:" + site.port() + "/extern/ok");
    }

    @Test
    void theMissingFooterImageIs404ButTheLogoIsAValidPng() throws Exception {
        assertThat(get("assets/fehlt.png").statusCode()).isEqualTo(404);

        HttpResponse<byte[]> logo = get("assets/logo.png");
        assertThat(logo.statusCode()).isEqualTo(200);
        assertThat(logo.headers().firstValue("content-type")).contains("image/png");
        assertThat(logo.body()).startsWith((byte) 0x89, (byte) 'P', (byte) 'N', (byte) 'G');
    }

    @Test
    void theRealPdfHasMagicBytesAndTheTrapServesHtml() throws Exception {
        HttpResponse<byte[]> pdf = get("dateien/handbuch.pdf");
        assertThat(pdf.headers().firstValue("content-type")).contains("application/pdf");
        assertThat(new String(pdf.body(), 0, 5, java.nio.charset.StandardCharsets.US_ASCII))
                .isEqualTo("%PDF-");
        assertThat(pdf.body().length).isGreaterThan(1024);

        HttpResponse<byte[]> trap = get("dateien/preisliste.pdf");
        assertThat(trap.statusCode()).isEqualTo(200);
        assertThat(trap.headers().firstValue("content-type")).hasValue("text/html; charset=utf-8");
    }

    @Test
    void redirectsFormAChainAndALoop() throws Exception {
        assertThat(get("weiter/1").statusCode()).isEqualTo(302);
        assertThat(get("weiter/1").headers().firstValue("location")).contains("/weiter/2");
        assertThat(get("weiter/3").headers().firstValue("location")).contains("/ziel.html");

        assertThat(get("schleife/a").headers().firstValue("location")).contains("/schleife/b");
        assertThat(get("schleife/b").headers().firstValue("location")).contains("/schleife/a");
    }

    @Test
    void theAudioFileIsAPlayableWav() throws Exception {
        HttpResponse<byte[]> wav = get("medien/ton.wav");
        assertThat(wav.headers().firstValue("content-type")).contains("audio/wav");
        assertThat(new String(wav.body(), 0, 4, java.nio.charset.StandardCharsets.US_ASCII))
                .isEqualTo("RIFF");
        assertThat(get("medien/fehlt.mp4").statusCode()).isEqualTo(404);
    }

    @Test
    void theFrameRoutesReproduceTheirRealWorldFailures() throws Exception {
        String maps = new String(get("maps/embed/v1/place").body(),
                java.nio.charset.StandardCharsets.UTF_8);
        assertThat(maps).contains("ApiNotActivatedMapError");
        assertThat(get("blockiert").headers().firstValue("x-frame-options")).contains("DENY");
    }

    @Test
    void unknownPathsAreSoft404sAndOnlyHart404IsAHardOne() throws Exception {
        HttpResponse<byte[]> soft = get("gibt-es-nicht-" + java.util.UUID.randomUUID());
        assertThat(soft.statusCode()).isEqualTo(200);
        assertThat(new String(soft.body(), java.nio.charset.StandardCharsets.UTF_8))
                .contains("Seite nicht gefunden");

        assertThat(get("hart-404").statusCode()).isEqualTo(404);
    }

    @Test
    void twoProbesOfDifferentUnknownPathsProduceTheSameBody() throws Exception {
        // The soft-404 probe compares a random path's body against later pages. If the
        // not-found page varied per request, every page would look like a soft 404.
        String first = new String(get("aaa-" + java.util.UUID.randomUUID()).body(),
                java.nio.charset.StandardCharsets.UTF_8);
        String second = new String(get("bbb-" + java.util.UUID.randomUUID()).body(),
                java.nio.charset.StandardCharsets.UTF_8);
        assertThat(first).isEqualTo(second);
    }

    @Test
    void robotsDisallowsTheSecretAreaThatIsNeverthelessReachable() throws Exception {
        assertThat(new String(get("robots.txt").body(), java.nio.charset.StandardCharsets.UTF_8))
                .contains("Disallow: /geheim/");
        assertThat(get("geheim/intern.html").statusCode()).isEqualTo(200);
    }

    @Test
    void theSameServerAnsweredUnderLocalhostCountsAsExternal() throws Exception {
        HttpResponse<byte[]> external = client.send(
                HttpRequest.newBuilder(URI.create(site.externalBase() + "extern/ok")).build(),
                HttpResponse.BodyHandlers.ofByteArray());
        assertThat(external.statusCode()).isEqualTo(200);
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./mvnw test -Dtest=FixtureSiteTest`
Expected: compilation failure — `FixtureSite` does not exist.

- [ ] **Step 3: Write the static pages**

`src/test/resources/fixture-site/index.html` — the hub. Note `{{PORT}}`: `FixtureSite`
substitutes the real port into every `text/html` body it serves.

```html
<!doctype html>
<html lang="de">
<head><meta charset="utf-8"><title>Startseite — Fixture</title>
  <link rel="alternate" hreflang="en" href="/en/index.html">
  <link rel="alternate" hreflang="de" href="/">
</head>
<body>
<h1>Startseite</h1>
<nav>
  <a href="/leistungen.html">Leistungen</a>
  <a href="/kontakt.html">Kontakt</a>
  <a href="/medien.html">Medien</a>
  <a href="/mixed-content.html">Gemischte Inhalte</a>
  <a href="/en/index.html">English</a>
</nav>
<ul>
  <li><a href="/verirrt.html">Seite die es nicht mehr gibt</a></li>
  <li><a href="/hart-404">Harte 404</a></li>
  <li><a href="/weiter/1">Weiterleitungskette</a></li>
  <li><a href="/schleife/a">Weiterleitungsschleife</a></li>
  <li><a href="/dateien/handbuch.pdf">Handbuch (PDF)</a></li>
  <li><a href="/dateien/preisliste.pdf">Preisliste (angeblich PDF)</a></li>
  <li><a href="http://localhost:{{PORT}}/extern/ok">Externer Partner</a></li>
  <li><a href="http://localhost:9/tot">Externer toter Link</a></li>
  <li><a href="/geheim/intern.html">Interner Bereich</a></li>
</ul>
<button type="button" id="tut-nichts">Mehr erfahren</button>
<footer><img src="/assets/logo.png" alt="Logo" width="1" height="1">
  <img src="/assets/fehlt.png" alt="Auszeichnung"></footer>
</body>
</html>
```

`leistungen.html` — the same footer (so `/assets/fehlt.png` appears on several pages), plus
a `srcset` image and a CSS background image:

```html
<!doctype html>
<html lang="de">
<head><meta charset="utf-8"><title>Leistungen — Fixture</title>
  <style>.held { background-image: url('/assets/fehlt.png'); width: 40px; height: 40px; }</style>
</head>
<body>
<h1>Leistungen</h1>
<p><a href="/">Zurück zur Startseite</a> · <a href="/kontakt.html">Kontakt</a></p>
<img src="/assets/logo.png" srcset="/assets/logo.png 1x, /assets/fehlt.png 2x" alt="Beratung">
<div class="held"></div>
<footer><img src="/assets/logo.png" alt="Logo"><img src="/assets/fehlt.png" alt="Auszeichnung"></footer>
</body>
</html>
```

`kontakt.html` — the contact form (Plan 3's `CONTACT_FORM` target) and both iframes:

```html
<!doctype html>
<html lang="de">
<head><meta charset="utf-8"><title>Kontakt — Fixture</title></head>
<body>
<h1>Kontakt</h1>
<p><a href="/">Zurück zur Startseite</a></p>
<form id="kontaktformular" action="/kontakt/absenden" method="post">
  <label for="name">Name</label>
  <input type="text" id="name" name="name" autocomplete="name" required>
  <label for="email">E-Mail</label>
  <input type="email" id="email" name="email" autocomplete="email" required>
  <label for="nachricht">Nachricht</label>
  <textarea id="nachricht" name="nachricht" required></textarea>
  <button type="submit">Absenden</button>
</form>
<iframe src="/maps/embed/v1/place" title="Anfahrt" width="300" height="200"></iframe>
<iframe src="/blockiert" title="Bewertungen" width="300" height="200"></iframe>
<footer><img src="/assets/logo.png" alt="Logo"><img src="/assets/fehlt.png" alt="Auszeichnung"></footer>
</body>
</html>
```

`medien.html`:

```html
<!doctype html>
<html lang="de">
<head><meta charset="utf-8"><title>Medien — Fixture</title></head>
<body>
<h1>Medien</h1>
<p><a href="/">Zurück zur Startseite</a></p>
<audio id="ton" controls preload="metadata"><source src="/medien/ton.wav" type="audio/wav"></audio>
<video id="film" controls preload="metadata"><source src="/medien/fehlt.mp4" type="video/mp4"></video>
<footer><img src="/assets/logo.png" alt="Logo"><img src="/assets/fehlt.png" alt="Auszeichnung"></footer>
</body>
</html>
```

`mixed-content.html` — an http subresource. Per D6 the page itself is http here, so the crawl
merely records the subresource; Plan 3 proves the check from a snapshot with `secure = true`:

```html
<!doctype html>
<html lang="de">
<head><meta charset="utf-8"><title>Gemischte Inhalte — Fixture</title></head>
<body>
<h1>Gemischte Inhalte</h1>
<p><a href="/">Zurück zur Startseite</a></p>
<img src="http://localhost:9/unsicher.png" alt="Unsicher geladen">
<footer><img src="/assets/logo.png" alt="Logo"><img src="/assets/fehlt.png" alt="Auszeichnung"></footer>
</body>
</html>
```

`ziel.html` (the redirect chain's destination):

```html
<!doctype html>
<html lang="de">
<head><meta charset="utf-8"><title>Ziel — Fixture</title></head>
<body><h1>Ziel erreicht</h1><p><a href="/">Zurück zur Startseite</a></p></body>
</html>
```

`geheim/intern.html` (reachable, but robots-disallowed — the crawl must not visit it):

```html
<!doctype html>
<html lang="de">
<head><meta charset="utf-8"><title>Intern — Fixture</title></head>
<body><h1>Interner Bereich</h1><p><a href="/">Zurück zur Startseite</a></p></body>
</html>
```

`en/index.html` — the language switcher that changes the URL and serves German anyway
(`lang="en"`, German body text — exactly the real-world failure of §7.2):

```html
<!doctype html>
<html lang="en">
<head><meta charset="utf-8"><title>Startseite — Fixture</title>
  <link rel="alternate" hreflang="de" href="/">
</head>
<body>
<h1>Startseite</h1>
<p><a href="/">Deutsch</a></p>
<footer><img src="/assets/logo.png" alt="Logo"><img src="/assets/fehlt.png" alt="Auszeichnung"></footer>
</body>
</html>
```

`robots.txt`:

```
User-agent: *
Disallow: /geheim/
Disallow: /kontakt/absenden
Allow: /

Sitemap: /sitemap.xml
```

`sitemap.xml` — deliberately lists one page that does not exist and omits `/medien.html`,
so `SITEMAP_CONSISTENCY` has both of its failure modes:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9">
  <url><loc>http://127.0.0.1:{{PORT}}/</loc></url>
  <url><loc>http://127.0.0.1:{{PORT}}/leistungen.html</loc></url>
  <url><loc>http://127.0.0.1:{{PORT}}/kontakt.html</loc></url>
  <url><loc>http://127.0.0.1:{{PORT}}/nicht-vorhanden.html</loc></url>
</urlset>
```

- [ ] **Step 4: Write `FixtureSite`**

```java
package dev.hendrikhoemberg.webtesthelper.support;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.Executors;

/**
 * The fixture site (spec 15): a small static site containing one of every failure mode,
 * served from loopback. Nothing in CI ever touches a real customer site.
 *
 * <p>Served as {@code 127.0.0.1} while {@link #externalBase()} addresses the same server as
 * {@code localhost}, so a link between the two is "external" by registrable host without
 * leaving the machine.
 *
 * <p>Unknown paths answer <strong>200</strong> with a not-found body: the fixture is a
 * soft-404 site by design, which is what gives the {@code {baseUrl}/{uuid}} probe something
 * to fingerprint. {@code /hart-404} is the only genuine 404 page.
 */
public final class FixtureSite implements AutoCloseable {

    private static final byte[] PNG_1X1 = Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==");

    private static final String SOFT_404_BODY = """
            <!doctype html><html lang="de"><head><meta charset="utf-8">
            <title>Seite nicht gefunden</title></head>
            <body><h1>Seite nicht gefunden</h1>
            <p>Die gewünschte Seite existiert leider nicht. <a href="/">Zur Startseite</a></p>
            </body></html>
            """;

    private static final String MAPS_BODY = """
            <!doctype html><html lang="en"><head><meta charset="utf-8"><title>Map</title></head>
            <body style="margin:0"><div style="width:100%;height:100%;background:#e5e3df">
            <p>For development purposes only</p></div>
            <script>console.error("Google Maps JavaScript API error: ApiNotActivatedMapError");</script>
            </body></html>
            """;

    private final HttpServer server;
    private final int port;

    private FixtureSite(HttpServer server) {
        this.server = server;
        this.port = server.getAddress().getPort();
    }

    public static FixtureSite start() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
            FixtureSite site = new FixtureSite(server);
            server.createContext("/", site::dispatch);
            server.start();
            return site;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public int port() {
        return port;
    }

    /** The site under test: {@code http://127.0.0.1:{port}/}. */
    public String baseUrl() {
        return "http://127.0.0.1:" + port + "/";
    }

    /** The same server under a different host name, so links to it count as external. */
    public String externalBase() {
        return "http://localhost:" + port + "/";
    }

    /** @param path without a leading slash */
    public String url(String path) {
        return baseUrl() + path;
    }

    @Override
    public void close() {
        server.stop(0);
    }

    private void dispatch(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        try {
            switch (path) {
                case "/assets/logo.png" -> send(exchange, 200, "image/png", PNG_1X1);
                case "/assets/fehlt.png" -> send(exchange, 404, "text/plain", "nicht gefunden".getBytes(StandardCharsets.UTF_8));
                case "/dateien/handbuch.pdf" -> send(exchange, 200, "application/pdf", pdf());
                case "/dateien/preisliste.pdf" -> sendHtml(exchange, 200,
                        "<!doctype html><html lang=\"de\"><body><h1>Bitte anmelden</h1></body></html>");
                case "/weiter/1" -> redirect(exchange, "/weiter/2");
                case "/weiter/2" -> redirect(exchange, "/weiter/3");
                case "/weiter/3" -> redirect(exchange, "/ziel.html");
                case "/schleife/a" -> redirect(exchange, "/schleife/b");
                case "/schleife/b" -> redirect(exchange, "/schleife/a");
                case "/medien/ton.wav" -> send(exchange, 200, "audio/wav", wav());
                case "/medien/fehlt.mp4" -> send(exchange, 404, "text/plain", "weg".getBytes(StandardCharsets.UTF_8));
                case "/maps/embed/v1/place" -> sendHtml(exchange, 200, MAPS_BODY);
                case "/blockiert" -> {
                    exchange.getResponseHeaders().add("X-Frame-Options", "DENY");
                    sendHtml(exchange, 200, "<!doctype html><html lang=\"de\"><body><p>Bewertungen</p></body></html>");
                }
                case "/hart-404" -> sendHtml(exchange, 404, SOFT_404_BODY);
                case "/extern/ok" -> sendHtml(exchange, 200,
                        "<!doctype html><html lang=\"de\"><body><h1>Partnerseite</h1></body></html>");
                case "/langsam" -> {
                    sleep();
                    sendHtml(exchange, 200, "<!doctype html><html lang=\"de\"><body><h1>Endlich</h1></body></html>");
                }
                default -> serveStaticOrSoft404(exchange, path);
            }
        } finally {
            exchange.close();
        }
    }

    private void serveStaticOrSoft404(HttpExchange exchange, String path) throws IOException {
        String resource = "fixture-site" + (path.endsWith("/") ? path + "index.html" : path);
        try (InputStream in = FixtureSite.class.getClassLoader().getResourceAsStream(resource)) {
            if (in == null) {
                sendHtml(exchange, 200, SOFT_404_BODY);   // the soft 404 — deliberately not 404
                return;
            }
            byte[] body = in.readAllBytes();
            String contentType = contentTypeOf(path);
            if (contentType.startsWith("text/html") || contentType.startsWith("application/xml")) {
                send(exchange, 200, contentType,
                        new String(body, StandardCharsets.UTF_8)
                                .replace("{{PORT}}", String.valueOf(port))
                                .getBytes(StandardCharsets.UTF_8));
            } else {
                send(exchange, 200, contentType, body);
            }
        }
    }

    private static String contentTypeOf(String path) {
        int dot = path.lastIndexOf('.');
        String extension = dot < 0 ? "" : path.substring(dot + 1);
        return switch (extension) {
            case "html", "" -> "text/html; charset=utf-8";
            case "xml" -> "application/xml; charset=utf-8";
            case "txt" -> "text/plain; charset=utf-8";
            case "png" -> "image/png";
            case "css" -> "text/css";
            case "js" -> "text/javascript";
            default -> "application/octet-stream";
        };
    }

    private static void redirect(HttpExchange exchange, String location) throws IOException {
        exchange.getResponseHeaders().add("Location", location);
        exchange.sendResponseHeaders(302, -1);
    }

    private static void sendHtml(HttpExchange exchange, int status, String body) throws IOException {
        send(exchange, status, "text/html; charset=utf-8", body.getBytes(StandardCharsets.UTF_8));
    }

    private static void send(HttpExchange exchange, int status, String contentType, byte[] body)
            throws IOException {
        exchange.getResponseHeaders().add("Content-Type", contentType);
        exchange.sendResponseHeaders(status, body.length);
        exchange.getResponseBody().write(body);
    }

    /**
     * A PDF that satisfies the three things FILE_DOWNLOAD asserts (spec 7.1): the %PDF magic
     * bytes, a matching content type and a non-trivial size. Padded past 1 KB with a comment.
     */
    private static byte[] pdf() {
        String body = """
                %PDF-1.4
                1 0 obj<</Type/Catalog/Pages 2 0 R>>endobj
                2 0 obj<</Type/Pages/Kids[3 0 R]/Count 1>>endobj
                3 0 obj<</Type/Page/Parent 2 0 R/MediaBox[0 0 200 200]>>endobj
                trailer<</Root 1 0 R>>
                """
                + "%" + "Fülltext ".repeat(160) + "\n%%EOF\n";
        return body.getBytes(StandardCharsets.ISO_8859_1);
    }

    /** Half a second of 440 Hz PCM — enough for readyState >= 1 and duration > 0. */
    private static byte[] wav() {
        int sampleRate = 8000;
        int frames = sampleRate / 2;
        ByteBuffer buffer = ByteBuffer.allocate(44 + frames * 2).order(ByteOrder.LITTLE_ENDIAN);
        buffer.put("RIFF".getBytes(StandardCharsets.US_ASCII)).putInt(36 + frames * 2);
        buffer.put("WAVE".getBytes(StandardCharsets.US_ASCII));
        buffer.put("fmt ".getBytes(StandardCharsets.US_ASCII)).putInt(16);
        buffer.putShort((short) 1).putShort((short) 1).putInt(sampleRate).putInt(sampleRate * 2);
        buffer.putShort((short) 2).putShort((short) 16);
        buffer.put("data".getBytes(StandardCharsets.US_ASCII)).putInt(frames * 2);
        for (int i = 0; i < frames; i++) {
            buffer.putShort((short) (Math.sin(2 * Math.PI * 440 * i / sampleRate) * 8000));
        }
        return buffer.array();
    }

    private static void sleep() {
        try {
            Thread.sleep(5000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Unused today; kept so a route can declare extra response headers in one place. */
    static Map<String, String> noHeaders() {
        return Map.of();
    }
}
```

Delete the `noHeaders()` method before committing if your linter objects — it is a
convenience, not a contract.

- [ ] **Step 5: Run the test to verify it passes**

Run: `./mvnw test -Dtest=FixtureSiteTest`
Expected: PASS, all ten cases.

- [ ] **Step 6: Commit**

```bash
git add src/test/java/dev/hendrikhoemberg/webtesthelper/support/FixtureSite.java \
        src/test/java/dev/hendrikhoemberg/webtesthelper/support/FixtureSiteTest.java \
        src/test/resources/fixture-site
git commit -m "test: add the fixture site with one of every failure mode

A loopback HttpServer serving static pages plus routes for the responses that
are the interesting part: 404 image, soft 404 catch-all, PDF-that-is-HTML,
redirect chain and loop, playable and broken media, a Maps embed throwing
ApiNotActivatedMapError, and an X-Frame-Options-blocked embed. Spec 15."
```

---

### Task 2: `PageSnapshot` — the value family every check reads

The one type this whole plan exists to produce. It lives in `model` (deviation D1) because
`checks` must depend on value types and nothing else (§5.1), and it is **immutable and
browser-free**: a check receives one of these and never a `Page`.

**Files:** all under `src/main/java/dev/hendrikhoemberg/webtesthelper/model/`
- Create: `PageSnapshot.java`, `LinkRef.java`, `ImageRef.java`, `ImageOrigin.java`,
  `MediaRef.java`, `MediaKind.java`, `FrameRef.java`, `FormRef.java`, `FormFieldRef.java`,
  `ConsoleMessage.java`, `FailedRequest.java`, `RunSnapshots.java`, `SoftNotFoundProbe.java`,
  `SimHash.java`
- Test: `src/test/java/dev/hendrikhoemberg/webtesthelper/model/SimHashTest.java`,
  `src/test/java/dev/hendrikhoemberg/webtesthelper/model/PageSnapshotTest.java`

**Interfaces:**
- Consumes: `NormalizedUrl`, `SiteContext`.
- Produces: everything below. Plan 3's checks read exactly these accessors; Plan 3's `SiteCheck`
  SPI takes `RunSnapshots` as its first parameter (§7.3).

The exact shapes — copy them verbatim, later tasks and plans reference these names:

```java
public record PageSnapshot(
        NormalizedUrl url,              // normalised final URL, after redirects
        String requestedUrl,            // what the frontier asked for, unnormalised
        int depth,
        boolean reachable,              // false => navigation failed; most fields are empty
        String unreachableReason,       // null when reachable
        int httpStatus,                 // 0 when unreachable
        Map<String, String> responseHeaders,   // lowercase keys
        List<String> redirectChain,     // requested URL first, final URL last; size 1 = no redirect
        long loadMillis,
        String title,
        String htmlLang,                // <html lang>, "" when absent
        String textContent,
        long textSimhash,               // SimHash.of(textContent)
        List<LinkRef> links,
        List<ImageRef> images,
        List<MediaRef> media,
        List<FrameRef> frames,
        List<FormRef> forms,
        List<ConsoleMessage> consoleMessages,
        List<FailedRequest> failedRequests,
        String screenshotPath) {        // relative to the run's artifact directory; null if none

    public PageSnapshot { /* defensive copies of every collection, Map/List.copyOf */ }

    public boolean isSecure()                       // url.isSecure()
    public List<LinkRef> internalLinks()            // links where internal() is true
    public List<LinkRef> externalLinks()
    public List<ConsoleMessage> errors()            // level "error"
    public static PageSnapshot unreachable(NormalizedUrl url, String requestedUrl, int depth,
            String reason, List<ConsoleMessage> console, List<FailedRequest> failed)
}

public record LinkRef(String rawHref, NormalizedUrl target, String anchorText,
                      boolean internal, String rel) {
    public boolean nofollow()   // rel contains "nofollow", case-insensitive
}

public enum ImageOrigin { IMG, SRCSET, CSS_BACKGROUND }

public record ImageRef(String rawSource, NormalizedUrl target, String alt,
                       int naturalWidth, int naturalHeight, ImageOrigin origin) {
    /** Status 200 is not enough (spec 7.1) — a broken image still returns bytes sometimes. */
    public boolean rendered()   // naturalWidth > 0 && naturalHeight > 0
}

public enum MediaKind { VIDEO, AUDIO }

public record MediaRef(MediaKind kind, List<NormalizedUrl> sources,
                       int readyState, double duration, String errorCode) {
    public boolean playable()   // readyState >= 1 && duration > 0 && errorCode == null
}

public record FrameRef(NormalizedUrl src, String title, boolean loaded,
                       int contentTextLength, boolean sameOrigin) {}

public record FormRef(String id, String action, String method, List<FormFieldRef> fields) {}

public record FormFieldRef(String name, String type, String label,
                           String autocomplete, boolean required) {}

public record ConsoleMessage(String level, String text, String location) {}

public record FailedRequest(String url, String method, String resourceType,
                            Integer status, String failureText) {}

/** What the {baseUrl}/{uuid} probe learned about the site's not-found page (spec 7.1). */
public record SoftNotFoundProbe(int httpStatus, long simhash, int textLength) {
    public static final SoftNotFoundProbe NONE = new SoftNotFoundProbe(0, 0L, 0);
    public boolean usable()   // httpStatus == 200 && textLength > 0 — a hard-404 site needs no probe
}

/** Everything one run saw. The input to every SiteCheck (spec 7.3) and to materialisation. */
public record RunSnapshots(long runId, SiteContext site, List<PageSnapshot> snapshots,
                           SoftNotFoundProbe softNotFound) {
    public Optional<PageSnapshot> byUrl(String normalizedUrl)
    public Set<String> visitedUrls()     // snapshot.url().value() for every snapshot
    public int pageCount()
}
```

**`SimHash` is the one piece with real logic here** — soft-404 detection compares a page's text
against the probe's, and equality is useless because the not-found page usually echoes the
requested path. Write it in full:

```java
package dev.hendrikhoemberg.webtesthelper.model;

import java.util.Locale;

/**
 * 64-bit SimHash over word trigrams. Near-duplicate detection for soft-404s (spec 7.1):
 * a not-found page that echoes the requested path differs textually from the probe but must
 * still be recognised as the same page.
 *
 * <p>Trigrams rather than single words: single words make every German page of similar
 * vocabulary look alike, which is how a soft-404 detector starts eating real pages.
 */
public final class SimHash {

    private SimHash() {
    }

    public static long of(String text) {
        if (text == null || text.isBlank()) {
            return 0L;
        }
        String[] words = text.toLowerCase(Locale.ROOT).split("\\W+");
        int[] bits = new int[64];
        int shingles = 0;
        for (int i = 0; i + 2 < words.length; i++) {
            if (words[i].isEmpty()) {
                continue;
            }
            long hash = hash64(words[i] + ' ' + words[i + 1] + ' ' + words[i + 2]);
            shingles++;
            for (int bit = 0; bit < 64; bit++) {
                bits[bit] += ((hash >>> bit) & 1L) == 1L ? 1 : -1;
            }
        }
        if (shingles == 0) {
            return hash64(text.toLowerCase(Locale.ROOT));
        }
        long result = 0L;
        for (int bit = 0; bit < 64; bit++) {
            if (bits[bit] > 0) {
                result |= 1L << bit;
            }
        }
        return result;
    }

    public static int hammingDistance(long a, long b) {
        return Long.bitCount(a ^ b);
    }

    /** FNV-1a, 64-bit. Deterministic across JVMs — String.hashCode is only 32 bits. */
    private static long hash64(String value) {
        long hash = 0xcbf29ce484222325L;
        for (int i = 0; i < value.length(); i++) {
            hash ^= value.charAt(i);
            hash *= 0x100000001b3L;
        }
        return hash;
    }
}
```

- [ ] **Step 1: Write the failing tests**

`SimHashTest` — five cases, and the thresholds matter because Plan 3 sets the soft-404 cutoff
from them:

```java
class SimHashTest {

    private static final String NOT_FOUND = """
            Seite nicht gefunden. Die gewünschte Seite existiert leider nicht.
            Bitte prüfen Sie die Adresse oder kehren Sie zur Startseite zurück.
            """;

    @Test
    void identicalTextHashesIdentically() {
        assertThat(SimHash.of(NOT_FOUND)).isEqualTo(SimHash.of(NOT_FOUND));
    }

    @Test
    void aNotFoundPageEchoingADifferentPathStaysNear() {
        long probe = SimHash.of(NOT_FOUND + " Angefordert: /1f4c-9a2b");
        long other = SimHash.of(NOT_FOUND + " Angefordert: /leistungen-alt");
        assertThat(SimHash.hammingDistance(probe, other)).isLessThanOrEqualTo(6);
    }

    @Test
    void anUnrelatedPageIsFar() {
        String real = """
                Leistungen. Wir beraten mittelständische Unternehmen bei der Digitalisierung
                ihrer Vertriebsprozesse und begleiten die Einführung neuer Systeme.
                """;
        assertThat(SimHash.hammingDistance(SimHash.of(NOT_FOUND), SimHash.of(real)))
                .isGreaterThan(15);
    }

    @Test
    void emptyAndBlankTextHashToZero() {
        assertThat(SimHash.of("")).isZero();
        assertThat(SimHash.of("   \n ")).isZero();
        assertThat(SimHash.of(null)).isZero();
    }

    @Test
    void textShorterThanOneTrigramStillHashesDistinctly() {
        assertThat(SimHash.of("Fehler")).isNotZero();
        assertThat(SimHash.of("Fehler")).isNotEqualTo(SimHash.of("Erfolg"));
    }
}
```

`PageSnapshotTest` — the derived accessors and the defensive copying, over a hand-built
snapshot (no browser — that is the whole point of §5.2):

```java
class PageSnapshotTest {

    @Test
    void collectionsAreCopiedSoACallerCannotMutateASnapshot() {
        List<LinkRef> links = new ArrayList<>();
        links.add(link("https://example.com/a", true));
        PageSnapshot snapshot = snapshotWith(links);
        links.clear();
        assertThat(snapshot.links()).hasSize(1);
        assertThatThrownBy(() -> snapshot.links().add(link("https://example.com/b", true)))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void internalAndExternalLinksArePartitioned() { /* two internal, one external */ }

    @Test
    void anImageWithZeroNaturalWidthIsNotRendered() {
        assertThat(new ImageRef("/a.png", url("http://h/a.png"), "", 0, 0, ImageOrigin.IMG)
                .rendered()).isFalse();
        assertThat(new ImageRef("/b.png", url("http://h/b.png"), "", 1, 1, ImageOrigin.IMG)
                .rendered()).isTrue();
    }

    @Test
    void mediaIsPlayableOnlyWithMetadataAndDurationAndNoError() {
        assertThat(new MediaRef(MediaKind.AUDIO, List.of(), 1, 0.5, null).playable()).isTrue();
        assertThat(new MediaRef(MediaKind.AUDIO, List.of(), 0, 0.5, null).playable()).isFalse();
        assertThat(new MediaRef(MediaKind.AUDIO, List.of(), 1, 0.0, null).playable()).isFalse();
        assertThat(new MediaRef(MediaKind.VIDEO, List.of(), 1, 9.0, "MEDIA_ERR_SRC_NOT_SUPPORTED")
                .playable()).isFalse();
    }

    @Test
    void anUnreachableSnapshotCarriesTheReasonAndNoContent() {
        PageSnapshot snapshot = PageSnapshot.unreachable(
                url("http://h/langsam"), "http://h/langsam", 2, "Timeout 30000ms",
                List.of(), List.of());
        assertThat(snapshot.reachable()).isFalse();
        assertThat(snapshot.httpStatus()).isZero();
        assertThat(snapshot.unreachableReason()).contains("Timeout");
        assertThat(snapshot.links()).isEmpty();
    }

    @Test
    void runSnapshotsIndexesByNormalisedUrl() {
        RunSnapshots run = new RunSnapshots(7L, siteContext(),
                List.of(snapshotAt("http://h/"), snapshotAt("http://h/a.html")),
                SoftNotFoundProbe.NONE);
        assertThat(run.byUrl("http://h/a.html")).isPresent();
        assertThat(run.byUrl("http://h/fehlt")).isEmpty();
        assertThat(run.visitedUrls()).containsExactlyInAnyOrder("http://h/", "http://h/a.html");
    }

    @Test
    void aProbeThatReturnedAHard404IsNotUsable() {
        assertThat(new SoftNotFoundProbe(404, 123L, 400).usable()).isFalse();
        assertThat(new SoftNotFoundProbe(200, 123L, 400).usable()).isTrue();
        assertThat(SoftNotFoundProbe.NONE.usable()).isFalse();
    }
}
```

Write the helper factories (`link`, `url`, `snapshotWith`, `snapshotAt`, `siteContext`) as
private static methods in the test. `snapshotAt` builds a reachable snapshot with empty
collections; Plan 3 will grow this into a shared `Snapshots` test fixture builder.

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./mvnw test -Dtest='SimHashTest,PageSnapshotTest'`
Expected: compilation failure — none of the types exist.

- [ ] **Step 3: Write the value types and `SimHash`**

Use the shapes above verbatim. Rules for the compact constructors:
`Map.copyOf` / `List.copyOf` every collection; `responseHeaders` keys are lowercased by the
producer (Task 6), not here; `unreachableReason` stays null when `reachable` is true.

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./mvnw test -Dtest='SimHashTest,PageSnapshotTest'`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/dev/hendrikhoemberg/webtesthelper/model \
        src/test/java/dev/hendrikhoemberg/webtesthelper/model
git commit -m "feat(model): add PageSnapshot and the value family checks read

Navigate once, check many (spec 5.2): the crawler produces one immutable
PageSnapshot per page and every page check becomes a pure function over it.
Adds SimHash for soft-404 near-duplicate detection and RunSnapshots as the
SiteCheck input."
```

---

### Task 3: The crawl frontier

The frontier is a **table, not an in-memory queue** (§14): live progress, resumability after a
container restart, orphaned-run recovery. It is also the second of the two high-volume tables
JPA is explicitly not used for (§6.5) — workers claim a batch of URLs under a single statement
and report completion in batches, so 15,000 pages cost hundreds of statements, not tens of
thousands.

**Files:**
- Create: `src/main/resources/db/migration/V7__crawl_queue_item.sql`
- Create: `src/main/java/dev/hendrikhoemberg/webtesthelper/crawler/package-info.java`
- Create: `src/main/java/dev/hendrikhoemberg/webtesthelper/crawler/CrawlTarget.java`
- Create: `src/main/java/dev/hendrikhoemberg/webtesthelper/crawler/CrawlItemStatus.java`
- Create: `src/main/java/dev/hendrikhoemberg/webtesthelper/crawler/CrawlOutcome.java`
- Create: `src/main/java/dev/hendrikhoemberg/webtesthelper/crawler/persistence/CrawlFrontierJdbcRepository.java`
- Test: `src/test/java/dev/hendrikhoemberg/webtesthelper/crawler/CrawlFrontierJdbcRepositoryTest.java`

**Interfaces:**
- Consumes: `JdbcTemplate`, `AbstractPostgresTest`.
- Produces:
  - `record CrawlTarget(long id, String url, int depth)`
  - `enum CrawlItemStatus { PENDING, CLAIMED, DONE, FAILED, SKIPPED }`
  - `record CrawlOutcome(long id, CrawlItemStatus status, Integer httpStatus, String errorMessage)`
  - `CrawlFrontierJdbcRepository`:
    - `int seed(long runId, Collection<String> urls, int depth)` — rows inserted (duplicates ignored)
    - `int enqueue(long runId, Collection<String> urls, int depth, String discoveredFrom)`
    - `List<CrawlTarget> claimBatch(long runId, String owner, int batchSize)`
    - `void complete(Collection<CrawlOutcome> outcomes)`
    - `int reclaimStale(long runId, Duration olderThan, int maxAttempts)`
    - `int countPending(long runId)`
    - `Map<CrawlItemStatus, Integer> countByStatus(long runId)`
    - `List<String> visitedUrls(long runId)` — DONE rows, for the run's coverage (§6.4)

- [ ] **Step 1: Write the migration**

`V7__crawl_queue_item.sql`:

```sql
-- The crawl frontier (spec 14). A table rather than an in-memory queue: live progress,
-- resumability after a restart, and orphaned-run recovery. Claimed and completed in
-- batches (spec 6.5) so a 15,000-page run costs hundreds of statements, not tens of thousands.
CREATE TABLE crawl_queue_item (
    id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    run_id BIGINT NOT NULL REFERENCES run (id) ON DELETE CASCADE,
    url TEXT NOT NULL,
    depth INTEGER NOT NULL,
    discovered_from TEXT,

    status TEXT NOT NULL DEFAULT 'PENDING',
    claimed_by TEXT,
    claimed_at TIMESTAMPTZ,
    attempts INTEGER NOT NULL DEFAULT 0,

    http_status INTEGER,
    error_message TEXT,
    completed_at TIMESTAMPTZ,

    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- The dedupe key. Discovery re-inserts the same URL from every page that links to it;
-- ON CONFLICT DO NOTHING makes that free.
CREATE UNIQUE INDEX ux_crawl_queue_run_url ON crawl_queue_item (run_id, url);

-- Serves the claim statement's WHERE + ORDER BY. Breadth-first: shallower pages first, so a
-- run that hits its page budget has covered the pages nearest the entry points.
CREATE INDEX ix_crawl_queue_claim ON crawl_queue_item (run_id, status, depth, id);

-- Serves visitedUrls() and the live progress counts.
CREATE INDEX ix_crawl_queue_run_status ON crawl_queue_item (run_id, status);
```

- [ ] **Step 2: Write the failing test**

```java
package dev.hendrikhoemberg.webtesthelper.crawler;

import dev.hendrikhoemberg.webtesthelper.crawler.persistence.CrawlFrontierJdbcRepository;
import dev.hendrikhoemberg.webtesthelper.support.AbstractPostgresTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

class CrawlFrontierJdbcRepositoryTest extends AbstractPostgresTest {

    @Autowired
    CrawlFrontierJdbcRepository frontier;

    @Autowired
    JdbcTemplate jdbc;

    private long runId;

    @BeforeEach
    void freshRun() {
        jdbc.update("DELETE FROM crawl_queue_item");
        jdbc.update("DELETE FROM run");
        jdbc.update("DELETE FROM site");
        Long siteId = jdbc.queryForObject(
                "INSERT INTO site (name, base_url) VALUES ('Fixture', 'http://127.0.0.1:1/') RETURNING id",
                Long.class);
        runId = jdbc.queryForObject(
                "INSERT INTO run (site_id, trigger_type, scope, status) "
                        + "VALUES (?, 'MANUAL', 'FULL', 'RUNNING') RETURNING id",
                Long.class, siteId);
    }

    @Test
    void seedingTheSameUrlTwiceInsertsItOnce() {
        assertThat(frontier.seed(runId, List.of("http://h/", "http://h/a"), 0)).isEqualTo(2);
        assertThat(frontier.seed(runId, List.of("http://h/", "http://h/b"), 0)).isEqualTo(1);
        assertThat(frontier.countPending(runId)).isEqualTo(3);
    }

    @Test
    void discoveryReEnqueuingAKnownUrlIsANoOp() {
        frontier.seed(runId, List.of("http://h/"), 0);
        assertThat(frontier.enqueue(runId, List.of("http://h/"), 1, "http://h/a")).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT depth FROM crawl_queue_item WHERE run_id = ? AND url = 'http://h/'",
                Integer.class, runId)).isZero();   // ON CONFLICT DO NOTHING: the first insert
        // keeps its depth, and breadth-first claiming means the first insert is the shallow one
    }

    @Test
    void claimingTakesABatchShallowestFirstAndMarksItClaimed() {
        frontier.seed(runId, List.of("http://h/"), 0);
        frontier.enqueue(runId, List.of("http://h/tief"), 3, "http://h/");
        frontier.enqueue(runId, List.of("http://h/flach"), 1, "http://h/");

        List<CrawlTarget> batch = frontier.claimBatch(runId, "worker-1", 2);

        assertThat(batch).extracting(CrawlTarget::url)
                .containsExactly("http://h/", "http://h/flach");
        assertThat(batch).extracting(CrawlTarget::depth).containsExactly(0, 1);
        assertThat(frontier.countPending(runId)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM crawl_queue_item WHERE status = 'CLAIMED' AND claimed_by = 'worker-1'",
                Integer.class)).isEqualTo(2);
    }

    @Test
    void twoWorkersClaimingConcurrentlyNeverGetTheSameUrl() throws Exception {
        List<String> urls = new java.util.ArrayList<>();
        for (int i = 0; i < 200; i++) {
            urls.add("http://h/seite-" + i);
        }
        frontier.seed(runId, urls, 0);

        try (ExecutorService pool = Executors.newFixedThreadPool(4)) {
            List<Callable<List<String>>> claimers = new java.util.ArrayList<>();
            for (int worker = 0; worker < 4; worker++) {
                String owner = "worker-" + worker;
                claimers.add(() -> {
                    List<String> mine = new java.util.ArrayList<>();
                    List<CrawlTarget> batch;
                    while (!(batch = frontier.claimBatch(runId, owner, 20)).isEmpty()) {
                        batch.forEach(target -> mine.add(target.url()));
                    }
                    return mine;
                });
            }
            List<String> all = new java.util.ArrayList<>();
            for (Future<List<String>> claimed : pool.invokeAll(claimers)) {
                all.addAll(claimed.get());
            }
            assertThat(all).hasSize(200).doesNotHaveDuplicates();
        }
    }

    @Test
    void completingABatchWritesStatusAndHttpCodeInOneRoundTrip() {
        frontier.seed(runId, List.of("http://h/", "http://h/kaputt"), 0);
        List<CrawlTarget> batch = frontier.claimBatch(runId, "worker-1", 10);

        frontier.complete(List.of(
                new CrawlOutcome(batch.get(0).id(), CrawlItemStatus.DONE, 200, null),
                new CrawlOutcome(batch.get(1).id(), CrawlItemStatus.FAILED, null, "Timeout 30000ms")));

        assertThat(frontier.countByStatus(runId))
                .containsEntry(CrawlItemStatus.DONE, 1)
                .containsEntry(CrawlItemStatus.FAILED, 1);
        assertThat(frontier.visitedUrls(runId)).containsExactly("http://h/");
        assertThat(jdbc.queryForObject(
                "SELECT error_message FROM crawl_queue_item WHERE url = 'http://h/kaputt'",
                String.class)).isEqualTo("Timeout 30000ms");
    }

    @Test
    void aClaimAbandonedByADeadWorkerIsReclaimed() {
        frontier.seed(runId, List.of("http://h/"), 0);
        frontier.claimBatch(runId, "worker-die", 10);
        jdbc.update("UPDATE crawl_queue_item SET claimed_at = now() - interval '10 minutes'");

        assertThat(frontier.reclaimStale(runId, Duration.ofMinutes(5), 3)).isEqualTo(1);
        assertThat(frontier.countPending(runId)).isEqualTo(1);
    }

    @Test
    void aUrlThatKeepsKillingItsWorkerIsGivenUpOnRatherThanReclaimedForever() {
        frontier.seed(runId, List.of("http://h/gift"), 0);
        for (int attempt = 0; attempt < 3; attempt++) {
            frontier.claimBatch(runId, "worker-1", 10);
            jdbc.update("UPDATE crawl_queue_item SET claimed_at = now() - interval '10 minutes'");
            frontier.reclaimStale(runId, Duration.ofMinutes(5), 3);
        }
        assertThat(frontier.countPending(runId)).isZero();
        assertThat(frontier.countByStatus(runId)).containsEntry(CrawlItemStatus.FAILED, 1);
    }

    @Test
    void frontierRowsBelongToTheirRunAndVanishWithIt() {
        frontier.seed(runId, List.of("http://h/"), 0);
        jdbc.update("DELETE FROM run WHERE id = ?", runId);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM crawl_queue_item", Integer.class))
                .isZero();
    }
}
```

- [ ] **Step 3: Run the test to verify it fails**

Run: `./mvnw test -Dtest=CrawlFrontierJdbcRepositoryTest`
Expected: compilation failure — `CrawlFrontierJdbcRepository` does not exist.

- [ ] **Step 4: Declare the module and write the repository**

`crawler/package-info.java` — note the dependency list is `{"model"}` only (deviation D5):

```java
@org.springframework.modulith.ApplicationModule(
        displayName = "Crawler",
        allowedDependencies = {"model"})
package dev.hendrikhoemberg.webtesthelper.crawler;
```

The SQL is the load-bearing part; write it exactly:

```java
package dev.hendrikhoemberg.webtesthelper.crawler.persistence;

import dev.hendrikhoemberg.webtesthelper.crawler.CrawlItemStatus;
import dev.hendrikhoemberg.webtesthelper.crawler.CrawlOutcome;
import dev.hendrikhoemberg.webtesthelper.crawler.CrawlTarget;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.time.Duration;
import java.util.Collection;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * The crawl frontier. Raw SQL throughout: batched claim/complete is the point (spec 6.5), and
 * FOR UPDATE SKIP LOCKED has no JPA equivalent.
 */
@Repository
public class CrawlFrontierJdbcRepository {

    /**
     * Claims a batch under one statement. SKIP LOCKED lets several browser workers claim
     * simultaneously without blocking each other; ORDER BY depth makes the crawl
     * breadth-first, so a budget-capped run has covered the pages nearest the entry points.
     */
    private static final String CLAIM_SQL = """
            UPDATE crawl_queue_item
               SET status     = 'CLAIMED',
                   claimed_by = ?,
                   claimed_at = now(),
                   attempts   = attempts + 1
             WHERE id IN (SELECT id
                            FROM crawl_queue_item
                           WHERE run_id = ? AND status = 'PENDING'
                           ORDER BY depth, id
                           LIMIT ?
                           FOR UPDATE SKIP LOCKED)
         RETURNING id, url, depth
            """;

    private static final String ENQUEUE_SQL = """
            INSERT INTO crawl_queue_item (run_id, url, depth, discovered_from)
            VALUES (?, ?, ?, ?)
            ON CONFLICT (run_id, url) DO NOTHING
            """;

    private static final String COMPLETE_SQL = """
            UPDATE crawl_queue_item
               SET status = ?, http_status = ?, error_message = ?, completed_at = now()
             WHERE id = ?
            """;

    /**
     * Returns an abandoned claim to the queue — a worker whose browser died, or a container
     * that restarted mid-run (spec 14). A URL that has burned {@code maxAttempts} workers is
     * marked FAILED instead: it is far likelier to be a page that crashes the tab than a
     * coincidence, and one bad page must never keep a run alive forever.
     */
    private static final String RECLAIM_SQL = """
            UPDATE crawl_queue_item
               SET status     = CASE WHEN attempts >= ? THEN 'FAILED' ELSE 'PENDING' END,
                   claimed_by = NULL,
                   claimed_at = NULL,
                   error_message = CASE WHEN attempts >= ?
                                        THEN 'Nach ' || attempts || ' Versuchen aufgegeben'
                                        ELSE error_message END,
                   completed_at  = CASE WHEN attempts >= ? THEN now() ELSE NULL END
             WHERE run_id = ?
               AND status = 'CLAIMED'
               AND claimed_at < now() - make_interval(secs => ?)
            """;

    private static final RowMapper<CrawlTarget> TARGET_MAPPER = (rs, row) ->
            new CrawlTarget(rs.getLong("id"), rs.getString("url"), rs.getInt("depth"));

    private final JdbcTemplate jdbc;

    public CrawlFrontierJdbcRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public int seed(long runId, Collection<String> urls, int depth) {
        return enqueue(runId, urls, depth, null);
    }

    public int enqueue(long runId, Collection<String> urls, int depth, String discoveredFrom) {
        if (urls.isEmpty()) {
            return 0;
        }
        List<Object[]> batch = urls.stream()
                .distinct()
                .map(url -> new Object[]{runId, url, depth, discoveredFrom})
                .toList();
        int[] inserted = jdbc.batchUpdate(ENQUEUE_SQL, batch);
        return java.util.Arrays.stream(inserted).map(rows -> Math.max(rows, 0)).sum();
    }

    public List<CrawlTarget> claimBatch(long runId, String owner, int batchSize) {
        return jdbc.query(CLAIM_SQL, TARGET_MAPPER, owner, runId, batchSize);
    }

    public void complete(Collection<CrawlOutcome> outcomes) {
        if (outcomes.isEmpty()) {
            return;
        }
        jdbc.batchUpdate(COMPLETE_SQL, outcomes.stream()
                .map(outcome -> new Object[]{outcome.status().name(), outcome.httpStatus(),
                        outcome.errorMessage(), outcome.id()})
                .toList());
    }

    public int reclaimStale(long runId, Duration olderThan, int maxAttempts) {
        return jdbc.update(RECLAIM_SQL, maxAttempts, maxAttempts, maxAttempts,
                runId, olderThan.toSeconds());
    }

    public int countPending(long runId) {
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM crawl_queue_item WHERE run_id = ? AND status = 'PENDING'",
                Integer.class, runId);
        return count == null ? 0 : count;
    }

    public Map<CrawlItemStatus, Integer> countByStatus(long runId) {
        Map<CrawlItemStatus, Integer> counts = new EnumMap<>(CrawlItemStatus.class);
        jdbc.query("SELECT status, count(*) AS n FROM crawl_queue_item WHERE run_id = ? GROUP BY status",
                rs -> {
                    counts.put(CrawlItemStatus.valueOf(rs.getString("status")), rs.getInt("n"));
                }, runId);
        return counts;
    }

    /** The URLs this run actually visited — the URL half of its coverage (spec 6.4). */
    public List<String> visitedUrls(long runId) {
        return jdbc.queryForList(
                "SELECT url FROM crawl_queue_item WHERE run_id = ? AND status = 'DONE' ORDER BY url",
                String.class, runId);
    }
}
```

`CrawlTarget`, `CrawlItemStatus` and `CrawlOutcome` are the three-line records and the enum
listed in **Interfaces** above.

- [ ] **Step 5: Run the test to verify it passes**

Run: `./mvnw test -Dtest=CrawlFrontierJdbcRepositoryTest`
Expected: PASS, all eight cases. If `twoWorkersClaimingConcurrentlyNeverGetTheSameUrl` returns
fewer than 200 URLs, the `FOR UPDATE SKIP LOCKED` subquery was dropped — SKIP LOCKED without it
silently claims nothing under contention.

- [ ] **Step 6: Run the whole suite and commit**

Run: `./mvnw test`
Expected: PASS — `FlywayMigrationTest` proves V7 applies to an empty database and
`ModularityTest` accepts the new `crawler` module.

```bash
git add src/main/resources/db/migration/V7__crawl_queue_item.sql \
        src/main/java/dev/hendrikhoemberg/webtesthelper/crawler \
        src/test/java/dev/hendrikhoemberg/webtesthelper/crawler
git commit -m "feat(crawler): add the durable batched crawl frontier

A table rather than an in-memory queue (spec 14): live progress, resumability
and orphaned-run recovery. Claim is one FOR UPDATE SKIP LOCKED statement per
batch and completion is one batchUpdate, so a 15,000-page run costs hundreds
of statements. Abandoned claims are reclaimed; a URL that burns three workers
is given up on rather than retried forever."
```

---

## Plan 2a completion check

- [ ] `./mvnw test` passes on real Postgres
- [ ] Three commits landed (one per task)
- [ ] The fixture site serves every row of the route table, and `FixtureSiteTest` proves it
- [ ] `PageSnapshot` and its family are unit-tested **without a browser** — that is the §5.2
      contract, and Plan 3's entire check catalog depends on it holding
- [ ] `ModularityTest` accepts the new `crawler` module and would fail if it imported `runner`
- [ ] `FlywayMigrationTest` applies V7 to an empty database; `ddl-auto=validate` still passes
      (no JPA entity maps `crawl_queue_item`, by design — it is `JdbcTemplate`-only)
- [ ] Proceed to `2026-08-21-webtesthelper-p2b-browser-crawl.md`

## What Plan 2b consumes from this plan

Written down because Plan 2b's implementer sees only their own plan:

- `support.FixtureSite` — `start()`, `baseUrl()`, `externalBase()`, `url(String)`, `port()`
- `model.PageSnapshot` and `LinkRef`, `ImageRef`/`ImageOrigin`, `MediaRef`/`MediaKind`,
  `FrameRef`, `FormRef`/`FormFieldRef`, `ConsoleMessage`, `FailedRequest`,
  `RunSnapshots`, `SoftNotFoundProbe`, `SimHash.of(String)` / `SimHash.hammingDistance(long, long)`
- `crawler.CrawlTarget(long id, String url, int depth)`, `crawler.CrawlItemStatus`,
  `crawler.CrawlOutcome(long id, CrawlItemStatus status, Integer httpStatus, String errorMessage)`
- `crawler.persistence.CrawlFrontierJdbcRepository` — `seed`, `enqueue`, `claimBatch`,
  `complete`, `reclaimStale`, `countPending`, `countByStatus`, `visitedUrls`
- the `crawler` module declaration, currently `allowedDependencies = {"model"}`
