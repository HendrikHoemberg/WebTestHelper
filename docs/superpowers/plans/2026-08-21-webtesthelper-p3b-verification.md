# WebTestHelper Plan 3b — URL Verification, the External Cache and the Site Checks

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Give the checks the facts a snapshot cannot hold — whether a link target answers, what
a file really contains, when the certificate expires, what the sitemap declares — and add the five
checks that consume them: `DEAD_LINK`, `FILE_DOWNLOAD`, `TLS_CERT`, `HREFLANG`,
`SITEMAP_CONSISTENCY`. After this plan the full layer-1 catalog of §7.1 exists.

**Architecture:** Every check stays a pure function; nothing in `checks` opens a socket. The
network work happens in `crawler` on virtual threads bounded by a per-host semaphore (§5.4), its
results land in `RunFacts` (deviation D3, established by 3a), and checks read them through
`CheckConfig.facts()`. External results pass through the shared `external_url_check` table, so
twenty sites linking to one partner URL cost one request (§8.1). The pipeline becomes crawl →
verify → page checks → site checks. 3a's "What Plan 3b consumes from this plan" lists the seams
this plan moves; each task's **Files** and **Interfaces** repeat the ones it touches.

**Tech Stack:** Java 25, `java.net.http.HttpClient` and `javax.net.ssl` from the JDK — **no new
dependency**. Spring Boot 4.1.1 in the crawler/runner wiring only, PostgreSQL 17 via
Testcontainers, Playwright/Chromium in two tests only.

**Spec:** `docs/superpowers/specs/2026-08-21-webtesthelper-design.md`; §-references point there.
**Roadmap:** `…-phase-1-roadmap.md` — second half of plan 3 of 5. **Predecessors:** p1, p2a, p2b,
p3a, all executed and on `main`. Written from 3a's execution findings, under `CLAUDE.md`'s plan
calibration rules, which override `superpowers:writing-plans`' "No Placeholders" section:
signatures, paths and acceptance *assertions* are exact; obvious bodies are not written out.

**Ends with:** the fixture run reporting its dead external link, its 403 that must not be called
dead, its PDF that is really an HTML login wall, its unreciprocated language alternate and its
missing sitemap entries — and the external link fetched once per 24 h across every site.

---

## Deviations and constraints

The roadmap's deviation table (D1–D17) and the constraints p1, p2a, p2b and p3a established
apply unchanged and are **not** restated here — read them there. `CLAUDE.md` holds the test
rules. Five deviations are new in this plan; the roadmap table carries their one-line form,
and this is the reasoning behind them:

- **D18 — a check may pin one message variant's severity below the site's resolved severity.**
  §8 requires `UNVERIFIABLE` at `INFO`, and an expiring certificate is not an expired one. So
  `DEAD_LINK.unverifiable` is always `INFO` and `TLS_CERT.expiringSoon` always `WARN`; the site
  override governs the genuine-failure variants. Per-key overrides are not in the schema.
- **D19 — the verification *set* comes from the crawl, not from the checks.** `CrawlResult`
  carries `verificationCandidates` (link and iframe targets `UrlAdmission` refused to navigate —
  assets, external hosts, excluded and too-deep pages — minus anything robots disallows) and
  `sitemapUrls`. A check that decided what to fetch would not be a pure function.
- **D20 — only external URLs use the shared cache.** §8.1's payoff is collapsing third-party URLs
  across sites; a site's own pages are where a day-old answer would be wrong, and they are cheap
  because they sit on the host we are already crawling.
- **D21 — TLS is a probe, not a handshake inside the check.** `crawler.TlsProbe` performs it and
  `model.TlsCertificateFact` carries it on `RunFacts`, for D3's reason; `TlsCertCheck` then
  compares two instants and needs no server.
- **D22 — a sitemap entry that is a soft 404 belongs to `PAGE_STATUS`.** The sitemap check reports
  an entry only on a non-2xx status, an unreachable page or a `DEAD` verification; otherwise the
  fixture's `/nicht-vorhanden.html` yields two findings for one defect.

New constraints this plan introduces, none of which exist in an earlier plan:

- **No new dependency.** `java.net.http.HttpClient` and `javax.net.ssl` cover all of it.
- **`crawler` may not import `checks`** — anything both need lives in `model`. This is what puts
  `DocumentTypes` there.
- **Message keys take at most two arguments.** `CheckDocumentationTest` renders every key with
  exactly two, so a third placeholder survives to the reader as a literal `{2}`.
- **`MessageFormat` eats straight apostrophes** in a message that has arguments — write `„…"`.
- **Every network call gets a timeout, and the timeout is configuration.**
- **A site check reads "now" from `config.facts().startedAt()`**, never from a clock, and formats
  dates in a fixed zone — otherwise `TLS_CERT` is not a pure function.

## Decided constants

Rules, not guesses — from §7.1, §8 and §8.1. Unlike 3a's soft-404 cutoff none needed a browser.

| Constant | Value | Why |
|---|---|---|
| `2xx`, `3xx` | `OK` | a redirect is `REDIRECT_CHAIN`'s subject, not a link failure |
| `401, 403, 407, 429, 451, 999` | `UNVERIFIABLE` | §8: "they blocked our checker", not "your link is broken" |
| other `4xx` (`400, 404, 410`, …) | `DEAD` | |
| `5xx` | `DEAD` | Plan 4's end-of-run re-verification removes the transient ones (§8) |
| transport failure / timeout | `DEAD` + `failureText` | a link that never answers is broken for a visitor too |
| body prefix read | 1024 bytes, ISO-8859-1 | enough for magic bytes; the rest is never transferred |
| non-trivial file size | ≥ 1024 bytes | the fixture's valid PDF is padded past 1 KB for exactly this |
| PDF magic | prefix starts with `%PDF` | §7.1: a 200 `text/html` "PDF" is a login wall |
| cache TTL, success / failure | 24 h / 1 h | §8.1 — a recovered link must not stay dead for a day |
| per-host permits | 4 | §5.4: politeness bounded by semaphore, not by pool size |
| request / connect timeout | 10 s / 5 s | matches `SiteResourceFetcher` |
| certificate warning window | 30 days, per-site `warnDays` | §7.1 "not expiring within N days" |
| verifier redirects | `HttpClient.Redirect.NORMAL` | the browser already reported the chain |

---

### Task 1: The URL verifier

**Files:**
- Create: `model/UrlStatus.java`, `model/UrlVerification.java`, `model/UrlVerifications.java`,
  `model/DocumentTypes.java`
- Create: `crawler/VerifierProperties.java`, `crawler/UrlVerifier.java`
- Modify: `crawler/SiteResourceFetcher.java`, `crawler/CrawlService.java` (both `fetchText` call
  sites), `src/main/resources/application.properties`,
  `src/test/resources/application-test.properties`, `src/test/java/…/support/FixtureSite.java`
- Test: `crawler/UrlVerifierTest.java`, `support/FixtureSiteTest.java` (both browser-free)

**Interfaces (produces):**
- `enum UrlStatus { OK, DEAD, UNVERIFIABLE }` with `static UrlStatus ofHttpStatus(int)` — the
  constants table, nothing more.
- `record UrlVerification(String url, UrlStatus status, int httpStatus, String contentType,
  long contentLength, String bodyPrefix, String failureText, Instant checkedAt)` with `ok()`,
  `hasBody()` (`bodyPrefix != null`) and `static UrlVerification ofSnapshot(PageSnapshot)` — the
  crawl already answered for a page it visited. An unreachable snapshot maps to `DEAD` with its
  reason as `failureText`, a reachable one through `ofHttpStatus`, carrying
  `content-type`/`content-length` out of `responseHeaders()`.
- `record UrlVerifications(Map<String, UrlVerification> byUrl)` with `EMPTY`,
  `Optional<UrlVerification> of(NormalizedUrl)`, `size()`, `static of(Collection<…>)`.
- `final class DocumentTypes` — `static boolean isDocument(NormalizedUrl)` over `pdf, doc, docx,
  xls, xlsx, ppt, pptx, zip, csv` and `static boolean isPdf(NormalizedUrl)`. In `model` because
  `crawler` decides what to fetch a body for and `checks` what to judge, and `crawler` may not
  import `checks`.
- `record VerifierProperties(int perHostPermits, Duration requestTimeout, Duration successTtl,
  Duration failureTtl)` bound to `webtesthelper.verifier` (the existing
  `@ConfigurationPropertiesScan` picks it up).
- `UrlVerifier` (`@Component`): `UrlVerification verify(NormalizedUrl url, String userAgent,
  boolean wantBody)`, `Map<String, UrlVerification> verifyAll(Collection<NormalizedUrl> urls,
  String userAgent, Predicate<NormalizedUrl> wantBody)`.
- `SiteResourceFetcher.fetchText(NormalizedUrl url, String userAgent)` — the p2b carry-over.

- [ ] **Step 1: Teach the fixture to answer `HEAD`, and add four slots**

`com.sun.net.httpserver` lets you write a body for a `HEAD` and then fails the exchange. Guard
`send(...)` — the whole HEAD path depends on getting a `Content-Length` back:

```java
private static void send(HttpExchange exchange, int status, String contentType, byte[] body)
        throws IOException {                          // HEAD: length as a header, no body
    exchange.getResponseHeaders().add("Content-Type", contentType);
    if ("HEAD".equals(exchange.getRequestMethod())) {
        exchange.getResponseHeaders().add("Content-Length", String.valueOf(body.length));
        exchange.sendResponseHeaders(status, -1);     // -1: headers only
        return;
    }
    exchange.sendResponseHeaders(status, body.length);
    exchange.getResponseBody().write(body);
}
```

New dispatch slots:
- `/geblockt-403` → 403 `text/html`, body `<h1>Zugriff verweigert</h1>` — the `UNVERIFIABLE`
  case, a Cloudflare wall without leaving loopback. Link it from `index.html` as
  `<li><a href="/geblockt-403">Gesperrter Bereich</a></li>`.
- `/kein-head` → 405 and no body on `HEAD`, 200 `text/html` on `GET`: forces the GET fallback.
- `/dateien/winzig.pdf` → 200 `application/pdf`, exactly `%PDF-1.4\n` — right type, right magic,
  9 bytes: the `tooSmall` case.
- `/echo` → 200 `text/plain` whose body is the request's `User-Agent`, sleeping 50 ms and keeping
  an in-flight counter. Expose `int maxConcurrent()` and `int requestCount(String path)` on
  `FixtureSite`; Task 2 uses them too.

- [ ] **Step 2: Pin the new fixture behaviour in `FixtureSiteTest`**

`HEAD /dateien/handbuch.pdf` → 200, `Content-Length` > 1024, empty body; `/kein-head` → 405 on
`HEAD`, 200 on `GET`; `/geblockt-403` → 403; `/dateien/winzig.pdf` → 200 `application/pdf` under
1024 bytes; `/echo` returns the sent User-Agent. Run
`./mvnw test -Pfast -Dtest=FixtureSiteTest`, watch it fail, make it pass.

- [ ] **Step 3: Add the four `model` types** (signatures above).

- [ ] **Step 4: Write `UrlVerifierTest`** — one `FixtureSite` per class, a `UrlVerifier` built
      directly from test `VerifierProperties`, no Spring context. Assertions:

- `/extern/ok` → `OK`, 200; `/hart-404` → `DEAD`, 404.
- `/geblockt-403` → `UNVERIFIABLE`, 403 — **the assertion this check exists for**: not `DEAD`.
- `http://127.0.0.1:9/tot` → `DEAD`, `failureText` non-blank, `httpStatus` 0.
- `/dateien/handbuch.pdf`, `wantBody=false` → `contentLength` > 1024, `bodyPrefix` null.
- `/dateien/handbuch.pdf`, `wantBody=true` → `bodyPrefix` starts with `%PDF`, ≤ 1024 bytes: a
  prefix, not the file.
- `/dateien/preisliste.pdf`, `wantBody=true` → `OK`, `contentType` `text/html`, prefix not `%PDF`.
- `/kein-head` → `OK`: the 405 fell back to a `GET` instead of being reported dead.
- `verifyAll` over those six returns six entries keyed by `NormalizedUrl.value()`.
- With `perHostPermits = 2`, a `verifyAll` of 20 `/echo` URLs leaves `maxConcurrent() <= 2`.
- `/echo` returns the User-Agent the caller passed.

- [ ] **Step 5: Implement `UrlVerifier`**

One shared `HttpClient` (`followRedirects(NORMAL)`, 5 s connect). Protocol: `HEAD` unless
`wantBody`; on HEAD status `405`/`501` or any transport failure, retry as `GET` with
`Range: bytes=0-1023`. Read the prefix without transferring the body — a server that ignores
`Range` must not cost a 40 MB download:

```java
try (InputStream body = response.body()) {                    // BodyHandlers.ofInputStream()
    byte[] prefix = body.readNBytes(PREFIX_BYTES);            // 1024
    return new String(prefix, StandardCharsets.ISO_8859_1);
}                                                              // closing aborts the transfer
```

The fan-out is the part worth writing out: virtual threads for blocking I/O (§5.4), a semaphore
per registrable host for politeness, and `HostThrottle` for the gap between two requests to one
host, which is free to sleep on a virtual thread.

```java
public Map<String, UrlVerification> verifyAll(Collection<NormalizedUrl> urls, String userAgent,
        Predicate<NormalizedUrl> wantBody) {
    Map<String, UrlVerification> results = new ConcurrentHashMap<>();
    try (ExecutorService fanOut = Executors.newVirtualThreadPerTaskExecutor()) {
        for (NormalizedUrl url : urls) {
            fanOut.submit(() -> {
                Semaphore host = permits.computeIfAbsent(url.registrableHost(),
                        ignored -> new Semaphore(properties.perHostPermits()));
                host.acquire();
                try {
                    throttle.await(url.host(), crawler.perHostDelay());
                    results.put(url.value(), verify(url, userAgent, wantBody.test(url)));
                } finally {
                    host.release();
                }
                return null;
            });
        }
    }   // close() waits for every task; a pile of waiting virtual threads costs nothing
    return Map.copyOf(results);
}
```

`verify` never throws: every exception becomes a `DEAD` result carrying the text truncated to 500
characters (§14), and an `InterruptedException` re-sets the interrupt flag.

- [ ] **Step 6: Close the User-Agent carry-over.** `SiteResourceFetcher.fetchText` takes the agent
      as a parameter and `CrawlService` passes `request.site().effectiveUserAgent()` at both call
      sites — §8 wants the company's access logs greppable, and robots fetches lie today.
      `SiteResourceFetcherTest` asserts the header arrives verbatim (`/echo`).

- [ ] **Step 7: Configuration.** `application.properties`: `per-host-permits=4`,
      `request-timeout=10s`, `success-ttl=24h`, `failure-ttl=1h` under `webtesthelper.verifier`;
      `application-test.properties` the same with `request-timeout=3s`, so a hung slot cannot
      stretch the suite.

- [ ] **Step 8: Run and commit** — `./mvnw test -Pfast`, then `./mvnw test` once.

      Commit: `git add src/main src/test && git commit -m "feat(crawler): verify URLs on virtual threads with a per-host bound"`

---

### Task 2: The external URL cache

**Files:**
- Create: `src/main/resources/db/migration/V8__external_url_check.sql`,
  `crawler/persistence/ExternalUrlCacheJdbcRepository.java`,
  `crawler/UrlVerificationService.java`
- Test: `crawler/ExternalUrlCacheJdbcRepositoryTest.java`,
  `crawler/UrlVerificationServiceTest.java` — browser-free, both extend `AbstractPostgresTest`
  and clear `external_url_check` in `@BeforeEach`

**Interfaces:**
- Consumes: Task 1's `UrlVerifier`, `UrlVerification`, `UrlVerifications`, `VerifierProperties`,
  `DocumentTypes`.
- Produces: `ExternalUrlCacheJdbcRepository` (`@Repository`) with
  `Map<String, UrlVerification> fresh(Collection<String> urls, Instant now)` and
  `void store(Collection<UrlVerification> results, long siteId)`; `UrlVerificationService`
  (`@Component`) with
  `UrlVerifications verify(SiteContext site, RunSnapshots snapshots, List<String> candidates)`.

- [ ] **Step 1: The migration**

```sql
-- The shared external URL cache (spec 8.1). Twenty sites linking to one partner URL cost one
-- request per TTL window instead of twenty per sweep — the largest single cost driver in a sweep,
-- and the largest source of UNVERIFIABLE findings, since we stop tripping third-party limits.
CREATE TABLE external_url_check (
    url            TEXT PRIMARY KEY,               -- NormalizedUrl.value(), spec 6.2's key
    status         TEXT        NOT NULL CHECK (status IN ('OK','DEAD','UNVERIFIABLE')),
    http_status    INTEGER     NOT NULL DEFAULT 0,
    content_type   TEXT,
    content_length BIGINT,
    body_prefix    TEXT,                           -- null when a HEAD was enough
    failure_text   TEXT,
    checked_at     TIMESTAMPTZ NOT NULL,
    -- Which sites depend on this URL: one that turns dead must produce findings on every
    -- affected site, not only the one that happened to re-check it (spec 8.1).
    dependent_site_ids JSONB   NOT NULL DEFAULT '[]'::jsonb
);

CREATE INDEX ix_external_url_check_checked_at ON external_url_check (checked_at);
```

- [ ] **Step 2: `ExternalUrlCacheJdbcRepositoryTest`, red.** Assertions:

- `store` then `fresh` round-trips every column, including a null `body_prefix`.
- An `OK` row checked 23 h ago is fresh, one checked 25 h ago is not; a `DEAD` row checked 30 min
  ago is fresh, one checked 2 h ago is not — the failure TTL is the short one.
- `store` for site 1 then site 2 leaves both ids in `dependent_site_ids`, once each; a third
  `store` for site 1 adds no duplicate.
- Storing the same URL twice overwrites status and `checked_at`.
- `fresh(List.of(), now)` returns an empty map without touching the database.

- [ ] **Step 3: Implement the repository**

`fresh` is one statement — `WHERE url = ANY(?)` (a `String[]` via
`connection.createArrayOf("text", …)`) plus the TTL predicate

```sql
AND ((status =  'OK' AND checked_at > ?)          -- now - successTtl
  OR (status <> 'OK' AND checked_at > ?))         -- now - failureTtl
```

`store` batches one upsert per result. The dependent-id merge is the non-obvious part — a set
union inside the statement, so two concurrent workers cannot lose each other's site id:

```sql
INSERT INTO external_url_check (url, status, http_status, content_type, content_length,
                                body_prefix, failure_text, checked_at, dependent_site_ids)
VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb)
ON CONFLICT (url) DO UPDATE SET
    status = excluded.status, http_status = excluded.http_status,
    content_type = excluded.content_type, content_length = excluded.content_length,
    body_prefix = coalesce(excluded.body_prefix, external_url_check.body_prefix),
    failure_text = excluded.failure_text, checked_at = excluded.checked_at,
    dependent_site_ids = (SELECT coalesce(jsonb_agg(DISTINCT value), '[]'::jsonb)
                            FROM jsonb_array_elements(
                                 external_url_check.dependent_site_ids
                                 || excluded.dependent_site_ids) AS value)
```

`body_prefix` uses `coalesce` deliberately: a later HEAD-only check must not erase a prefix
already read, or `FILE_DOWNLOAD` would re-download every run.

- [ ] **Step 4: `UrlVerificationServiceTest`, red.** One `FixtureSite`, the service from the
      Spring context, hand-built `RunSnapshots`. Assertions:

- A page the crawl visited appears with **no HTTP request** (`requestCount` stays 0) and a status
  matching the snapshot's.
- An external candidate (`localhost` — same server, different registrable host) is fetched once,
  written to `external_url_check`, and a second `verify` for a **different** site id answers from
  the cache: `requestCount` stays 1 and `dependent_site_ids` holds both sites (§8.1).
- An internal candidate is fetched on both runs and leaves **no** `external_url_check` row (D20).
- A `DocumentTypes.isDocument` candidate comes back with a non-null `bodyPrefix`, an ordinary
  page candidate with a null one.
- A cached external row **without** `body_prefix` is a miss for a document candidate and is
  re-fetched — otherwise a cached PDF could never be judged.
- A candidate that is not a valid URL is skipped, not thrown on.

- [ ] **Step 5: Implement `UrlVerificationService`.** Seed from the snapshots
      (`UrlVerification.ofSnapshot`), drop candidates already seeded, then split the rest on
      `site.baseUrl().sameSiteAs(...)`: external go through `fresh(...)` and only the misses reach
      the verifier, followed by `store(...)`; internal always reach the verifier. `wantBody` is
      `DocumentTypes::isDocument`, the agent `site.effectiveUserAgent()`. Log one INFO line per
      run (candidates, cache hits, fetched, counts by status) — a pass that silently fetched
      nothing is otherwise invisible.

- [ ] **Step 6: Run and commit** — `./mvnw test -Pfast`, then `./mvnw test`.

      Commit: `git add src/main src/test && git commit -m "feat(crawler): share external URL results through a TTL cache"`

---

### Task 3: `DEAD_LINK` and `FILE_DOWNLOAD`

**Files:**
- Create: `model/TlsCertificateFact.java`, `checks/DeadLinkCheck.java`,
  `checks/FileDownloadCheck.java`
- Modify: `model/RunFacts.java`, `checks/CheckRegistry.java`,
  `src/main/resources/messages.properties`, `src/test/java/…/support/Snapshots.java`,
  `checks/CheckRegistryTest.java`
- Test: `checks/DeadLinkCheckTest.java`, `checks/FileDownloadCheckTest.java`

**Interfaces (produces):**
- `record TlsCertificateFact(String host, boolean handshakeOk, String failureText,
  Instant notBefore, Instant notAfter, String issuer)` with `NONE` and `applicable()`
  (`host != null`). Nothing produces one until Task 5; it is created here so `RunFacts` is
  reshaped once instead of twice.
- `RunFacts(long runId, RunScope scope, Instant startedAt, SoftNotFoundProbe softNotFound,
  UrlVerifications verifications, TlsCertificateFact tlsCertificate, List<String> sitemapUrls)`,
  the compact constructor defaulting the three new components to `EMPTY`/`NONE`/`List.of()`.
  **Keep** `of(RunSnapshots, RunScope, Instant)` (every unit test uses it) and add
  `of(RunSnapshots, RunScope, Instant, UrlVerifications, TlsCertificateFact, List<String>)`.
- `DeadLinkCheck implements PageCheck` — `ERROR`; keys `finding.DEAD_LINK.dead`, `.unverifiable`.
- `FileDownloadCheck implements PageCheck` — `ERROR`; keys `finding.FILE_DOWNLOAD.wrongType`,
  `.notAPdf`, `.tooSmall`.
- `Snapshots.facts(UrlVerification...)` for hand-built verification facts.

- [ ] **Step 1: Reshape `RunFacts`, add `TlsCertificateFact`.** Everything still compiles because
      the three-argument `of` stays; run `./mvnw test -Pfast` to prove it before writing a check.

- [ ] **Step 2: `DeadLinkCheckTest`, red.** Hand-built snapshots and verifications:

- A target verifying `DEAD` → one finding, key `.dead`, `subjectKey` the target's
  `NormalizedUrl.value()`, `observedOn` the page, `ERROR`, args target and HTTP status (or the
  failure text when the status is 0).
- A target verifying `UNVERIFIABLE` → `.unverifiable` at **`INFO` even when the site raised the
  check to `ERROR`** (D18). An unreachable snapshot → nothing (`PAGE_UNREACHABLE` owns it).
- A target verifying `OK` → nothing.
- A target with **no** verification entry → nothing: an unverified target is not a defect (§6.4).
- Two links to the same target on one page → one finding.
- An iframe `src` verifying `DEAD` → a finding: 3a handed those over when `IFRAME_EMBED` narrowed
  to `ERR_BLOCKED_BY_RESPONSE`.
- The same input twice yields the same findings in the same order (purity).

- [ ] **Step 3: Implement `DeadLinkCheck`** — walk `links()` then `frames()`, dedupe by target
      value, look each up in `config.facts().verifications()`. Evidence: the page screenshot, the
      verification's HTTP status, its `failureText` as `responseDetail`.

- [ ] **Step 4: `FileDownloadCheckTest`, red.**

- `.pdf`, `OK`, `application/pdf`, prefix `%PDF-1.4`, length 4096 → nothing.
- `.pdf`, `OK`, `text/html` → `.wrongType`, args target and content type. §7.1's login wall, and
  the most important assertion in the class.
- `.pdf`, `application/pdf`, prefix `<!doctype html>` → `.notAPdf`.
- `.pdf`, `application/pdf`, prefix `%PDF`, length 900 → `.tooSmall`, args target and `900`.
- `.docx` with `text/html` → `.wrongType`; with
  `application/vnd.openxmlformats-officedocument.wordprocessingml.document` → nothing.
- `.pdf` verifying `DEAD`, with no verification entry, or with `hasBody() == false` → nothing
  (`DEAD_LINK` owns the dead one: one defect, one finding).
- `.html` → nothing whatever its content type: `DocumentTypes.isDocument` scopes this check.
- At most one finding per link: a 500-byte `text/html` "PDF" reports `.wrongType` only.

- [ ] **Step 5: Implement `FileDownloadCheck`** — first failing rule wins, `wrongType` →
      `notAPdf` → `tooSmall`. The non-PDF rule is deliberately weak ("a document link answering
      `text/html` is a wall, not a file"): office content types are a swamp, and a false positive
      costs more than a miss (§8).

- [ ] **Step 6: Register both and write their German.** `CheckRegistry.standard()` gains the two,
      `CheckRegistryTest.pendingInPlan3b` loses them both, and `messages.properties` gains:

```properties
check.DEAD_LINK.title=Tote Links
check.DEAD_LINK.description=Prüft, ob jeder Verweis noch zu einer Seite oder Datei führt — auf dieser Website wie auf fremden.
check.DEAD_LINK.remediation=Verweis auf die richtige Adresse korrigieren oder entfernen. Zeigt er auf eine fremde Website, dort die neue Adresse suchen.
finding.DEAD_LINK.dead=Der Verweis auf {0} führt ins Leere ({1}).
finding.DEAD_LINK.unverifiable=Der Verweis auf {0} liess sich nicht prüfen, weil die fremde Website unsere Anfrage abweist ({1}). Bitte einmal von Hand aufrufen.

check.FILE_DOWNLOAD.title=Dateien zum Herunterladen
check.FILE_DOWNLOAD.description=Prüft, ob verlinkte Dateien wirklich als Datei ankommen und nicht als Fehler- oder Anmeldeseite.
check.FILE_DOWNLOAD.remediation=Datei erneut hochladen und den Verweis darauf prüfen. Liegt sie hinter einer Anmeldung, auf eine öffentlich erreichbare Fassung verweisen.
finding.FILE_DOWNLOAD.wrongType=Statt der Datei {0} liefert der Server eine Webseite aus ({1}).
finding.FILE_DOWNLOAD.notAPdf=Die Datei {0} wird als PDF angeboten, ihr Inhalt ist aber kein PDF.
finding.FILE_DOWNLOAD.tooSmall=Die Datei {0} ist mit {1} Byte zu klein, um Inhalt zu enthalten.
```

`CheckDocumentationTest` now demands exactly these keys and no others, in both directions.

- [ ] **Step 7: Run and commit** — `./mvnw test -Pfast`, then `./mvnw test`.

      Commit: `git add src/main src/test && git commit -m "feat(checks): report dead links and files that are not files"`

---

### Task 4: hreflang reaches the snapshot

The gap 3a flagged twice: `extract.js` collects `a[href]` and nothing else from `<head>`, so
`HREFLANG` has no input at all. Its own task because it crosses the browser boundary.

**Files:**
- Create: `model/AlternateRef.java`
- Modify: `src/main/resources/crawler/extract.js`, `model/PageSnapshot.java`,
  `crawler/PageNavigator.java`, `src/test/java/…/support/Snapshots.java`,
  `model/PageSnapshotTest.java`, `src/test/resources/fixture-site/en/index.html`,
  `…/leistungen.html`
- Test: `crawler/PageNavigatorTest.java` (already `@Tag("browser")`)

**Interfaces (produces):** `record AlternateRef(String hreflang, NormalizedUrl target)`;
`PageSnapshot` gains `List<AlternateRef> alternates` **immediately after `frames`** — five
construction sites: `PageNavigator`, `PageSnapshot.unreachable`, `Snapshots.Builder.build` and
two in `PageSnapshotTest`; `Snapshots.Builder.alternate(String hreflang, String href)`.

- [ ] **Step 1: Give the fixture the two failure modes it lacks.** Today `/` and `/en/index.html`
      reciprocate correctly and there is nothing to find. Add to `en/index.html`'s `<head>`
      `<link rel="alternate" hreflang="fr" href="http://localhost:9/fr/">` (dead alternate) and to
      `leistungen.html`'s `<head>` `<link rel="alternate" hreflang="en" href="/en/index.html">`
      (no back-reference, since `/en/index.html` names only `/`). Neither changes the crawl —
      `extract.js` discovers `a[href]`, never `<link>` — so every page-count assertion holds; say
      so in a comment in both files.

- [ ] **Step 2: Extend `extract.js`**

```js
  const alternates = [...document.querySelectorAll('link[rel~="alternate"][hreflang]')]
    .map(link => ({ lang: link.getAttribute('hreflang') || '',
                    abs: absolute(link.getAttribute('href') || '') }))
    .filter(alternate => alternate.abs);
```

and add `alternates` to the returned object. `rel~="alternate"` matches a multi-valued `rel`;
requiring `[hreflang]` keeps RSS links and print stylesheets out.

- [ ] **Step 3: `PageNavigatorTest`, red.** Against the fixture start page,
      `snapshot.alternates()` holds exactly two entries — `en` → `/en/index.html` and `de` → `/` —
      and a page without alternates returns an empty list, not null. Run
      `./mvnw test -Dtest=PageNavigatorTest`, watch it fail.

- [ ] **Step 4: Add `AlternateRef`, the `PageSnapshot` component and the `PageNavigator`
      mapping**, beside the frame mapping, dropping any alternate whose `abs` does not normalise —
      the same shape as every other ref list there.

- [ ] **Step 5: Extend `Snapshots.Builder`** with `alternate(...)`, so Task 5 needs no browser.
      Then run `./mvnw test` — the browser tests are what matter here — and commit.

      Commit: `git add src/main src/test && git commit -m "feat(crawler): extract hreflang alternates into the snapshot"`

---

### Task 5: The three site checks

**Files:**
- Create: `crawler/TlsProbe.java`, `checks/TlsCertCheck.java`, `checks/HreflangCheck.java`,
  `checks/SitemapConsistencyCheck.java`, `src/test/resources/fixture-tls.p12` (generated),
  `src/test/java/…/support/FixtureTlsSite.java`
- Modify: `checks/CheckEngine.java`, `checks/CheckRegistry.java`, `checks/PageStatusCheck.java`,
  `src/main/resources/messages.properties`, `checks/CheckRegistryTest.java`
- Test: `crawler/TlsProbeTest.java` (browser-free), `checks/TlsCertCheckTest.java`,
  `checks/HreflangCheckTest.java`, `checks/SitemapConsistencyCheckTest.java`,
  `checks/CheckEngineTest.java`

**Interfaces (produces):**
- `TlsProbe` (`@Component`): `TlsCertificateFact probe(NormalizedUrl baseUrl)`, returning
  `TlsCertificateFact.NONE` for an http base URL.
- `CheckEngine.evaluateSite(RunSnapshots snapshots, SiteContext site, RunFacts facts)` — same
  scope ∩ enabled filter and `CheckEvaluationException` wrapping as the page pass, with the
  site's base URL in place of the page URL.
- `PageStatusCheck.looksLikeNotFound(PageSnapshot, SoftNotFoundProbe, int maxDistance)` —
  package-private, extracted from its `evaluate` so the sitemap check uses the same rule instead
  of a second copy of the measured cutoff.
- `TlsCertCheck` (`ERROR`), `HreflangCheck` (`WARN`), `SitemapConsistencyCheck` (`WARN`, ships
  disabled), all `SiteCheck`s.

- [ ] **Step 1: Generate the TLS fixture certificate**

```bash
keytool -genkeypair -alias fixture -keyalg RSA -keysize 2048 -validity 3650 \
  -dname "CN=localhost, O=WebTestHelper Fixture" -keystore src/test/resources/fixture-tls.p12 \
  -storetype PKCS12 -storepass fixture -ext SAN=dns:localhost,ip:127.0.0.1
```

Self-signed and loopback-only, so §15 holds. Commit it: ten-year life, and regenerating it per
build would make the probe test depend on `keytool` being on the PATH.

- [ ] **Step 2: `FixtureTlsSite`** — `HttpsServer` on `127.0.0.1:0` with an `SSLContext` from
      `fixture-tls.p12`, one handler answering 200 `text/plain`, `baseUrl()` returning
      `https://localhost:{port}/` (the SAN name, so the handshake succeeds), `AutoCloseable`;
      same shape as `FixtureSite`, ~40 lines.

- [ ] **Step 3: `TlsProbeTest`, red.**

- Against `FixtureTlsSite`: `handshakeOk` true, `host` the fixture host, `notAfter` more than
  3000 days out, `issuer` containing `WebTestHelper Fixture`.
- Against `https://localhost:9/`: `handshakeOk` false, `failureText` non-blank — and it
  **returns rather than throwing**. Against an http base URL: `NONE`, `applicable()` false.
- Against a `ServerSocket` that accepts and never speaks: a failure fact within the timeout,
  not a hang.

- [ ] **Step 4: Implement `TlsProbe`.** The handshake is not obvious from the signature. SNI
      comes from `setSSLParameters`; without it a shared-hosting IP returns somebody else's
      certificate and the check reports the wrong expiry.

```java
SSLSocketFactory factory = permissiveContext().getSocketFactory();
try (SSLSocket socket = (SSLSocket) factory.createSocket()) {
    socket.connect(new InetSocketAddress(url.host(), url.port()), (int) connectTimeout.toMillis());
    socket.setSoTimeout((int) requestTimeout.toMillis());
    SSLParameters parameters = socket.getSSLParameters();
    parameters.setServerNames(List.of(new SNIHostName(url.host())));
    socket.setSSLParameters(parameters);
    socket.startHandshake();
    X509Certificate leaf = (X509Certificate) socket.getSession().getPeerCertificates()[0];
    return new TlsCertificateFact(url.host(), true, null, leaf.getNotBefore().toInstant(),
            leaf.getNotAfter().toInstant(), leaf.getIssuerX500Principal().getName());
} catch (IOException | RuntimeException e) {
    return new TlsCertificateFact(url.host(), false, truncate(e.toString(), 500), null, null, null);
}
```

`permissiveContext()` is an `SSLContext` whose trust manager accepts any chain, and its javadoc
must say why: this check answers "is it expiring", and chain validation would turn every
self-signed or private-CA site — the test fixture included — into a handshake failure that says
nothing about the expiry date.

- [ ] **Step 5: `TlsCertCheckTest`, red — pure, no sockets.** Facts hand-built,
      `facts.startedAt()` as "now":

- `NONE` (an http site, which the fixture is) → no findings.
- `handshakeOk=false` → `.handshakeFailed`, `ERROR`, args host and failure text.
- `notAfter` yesterday → `.expired`, `ERROR`, args host and the date as `dd.MM.yyyy`.
- `notAfter` in 10 days → `.expiringSoon`, args host and `10`, **`WARN` even when the site set
  the check to `ERROR`** (D18); in 90 days → nothing, unless the site sets `warnDays: 120`.
- `subjectKey` is the host, `observedOn` is null, so `locationKey()` is `"*"`: a certificate is a
  fact about the site, not about a page.

Dates format with `DateTimeFormatter.ofPattern("dd.MM.yyyy").withZone(ZoneId.of("Europe/Berlin"))`
— a fixed zone, because a check that reads the system default is not a pure function.

- [ ] **Step 6: `HreflangCheckTest`, red.** Targets resolve through `facts.verifications()` and
      `snapshots.byUrl(...)`. One finding per (page, alternate), first failing clause wins:

```
1. hreflang is not "x-default" and does not match
   ^[A-Za-z]{2,3}(-[A-Za-z]{4})?(-([A-Za-z]{2}|[0-9]{3}))?$
     -> .invalidLanguage   subject = page,   args (hreflang, page)
2. the target has a snapshot that is unreachable or not 2xx,
   or a verification that says DEAD
     -> .deadAlternate     subject = target, args (target, hreflang)
3. the target has a snapshot, is not the page itself, hreflang is not "x-default",
   and no alternate of that snapshot targets this page
     -> .notReciprocated   subject = target, args (page, target)
otherwise nothing.
```

Assertions: each clause fires exactly once for its case; a correctly reciprocated pair, a
self-referencing alternate, an alternate whose target was never crawled and has no verification
(§6.4), and a page without alternates all report nothing; `x-default` is exempt from clause 3 but
not clause 2. Clause 3 is scoped to the crawled set on purpose — §7.1 says "reciprocate across
the crawled set", and demanding it of an unvisited page would fire on every budget-capped run.

- [ ] **Step 7: `SitemapConsistencyCheckTest`, red.**

- `facts.sitemapUrls()` empty → no findings at all: a site without a sitemap has not failed a
  check it never took.
- A sitemap entry whose snapshot is unreachable or non-2xx → `.deadEntry`, subject the entry,
  `observedOn` null.
- A sitemap entry with no snapshot whose verification says `DEAD` → `.deadEntry`.
- A sitemap entry that is a soft 404 (200, fingerprint inside the cutoff) → **nothing** (D22):
  build the probe so `looksLikeNotFound` returns true and assert silence.
- A crawled 200 page absent from the sitemap → `.missingPage`, subject and `observedOn` the page.
- A crawled page that is unreachable, non-2xx or a soft 404 and absent from the sitemap →
  nothing: demanding that a broken page be listed is backwards.
- Trailing-slash forms compare equal — both sides go through `NormalizedUrl.value()`.

- [ ] **Step 8: Implement the three checks and `CheckEngine.evaluateSite`.** `CheckEngineTest`
      gains: a site check outside the run's scope does not run; a disabled one does not run; one
      that throws names itself; `coveredTypes()` now holds all thirteen types.

- [ ] **Step 9: Register the three and write their German.** The registry's second list stops being
      empty and `pendingInPlan3b` disappears, so `CheckRegistryTest` permanently demands an
      implementation for all thirteen `CheckType`s.

```properties
check.TLS_CERT.title=Verschlüsselung der Website
check.TLS_CERT.description=Prüft das Sicherheitszertifikat der Website auf Gültigkeit und Restlaufzeit.
check.TLS_CERT.remediation=Zertifikat beim Anbieter erneuern lassen. Läuft es ab, warnen Browser die Besucher und die meisten brechen ab.
finding.TLS_CERT.handshakeFailed=Die verschlüsselte Verbindung zu {0} kam nicht zustande: {1}
finding.TLS_CERT.expired=Das Sicherheitszertifikat für {0} ist seit dem {1} abgelaufen.
finding.TLS_CERT.expiringSoon=Das Sicherheitszertifikat für {0} läuft in {1} Tagen ab.

check.HREFLANG.title=Sprachverweise
check.HREFLANG.description=Prüft die Verweise zwischen den Sprachfassungen einer Seite: Gibt es die angegebene Fassung, und verweist sie zurück?
check.HREFLANG.remediation=Sprachverweis auf die richtige Adresse korrigieren und in der anderen Sprachfassung den Rückverweis ergänzen.
finding.HREFLANG.invalidLanguage=Die Sprachangabe „{0}" auf {1} ist keine gültige Sprachkennung.
finding.HREFLANG.deadAlternate=Die angegebene Sprachfassung {0} („{1}") gibt es nicht.
finding.HREFLANG.notReciprocated={0} verweist auf die Sprachfassung {1}, diese aber nicht zurück.

check.SITEMAP_CONSISTENCY.title=Inhaltsverzeichnis der Website
check.SITEMAP_CONSISTENCY.description=Vergleicht das Inhaltsverzeichnis (sitemap.xml) mit den tatsächlich vorhandenen Seiten. Standardmässig abgeschaltet, weil viele Seiten dort zu Recht fehlen.
check.SITEMAP_CONSISTENCY.remediation=Eintrag aus dem Inhaltsverzeichnis entfernen oder die fehlende Seite dort ergänzen. Das Verzeichnis erzeugt meist das Redaktionssystem.
finding.SITEMAP_CONSISTENCY.deadEntry=Das Inhaltsverzeichnis führt {0} auf, diese Seite gibt es aber nicht.
finding.SITEMAP_CONSISTENCY.missingPage=Die Seite {0} fehlt im Inhaltsverzeichnis der Website.
```

- [ ] **Step 10: Run and commit** — `./mvnw test -Pfast`, then `./mvnw test`.

      Commit: `git add src/main src/test && git commit -m "feat(checks): add the certificate, hreflang and sitemap site checks"`

---

### Task 6: The pipeline — crawl, verify, check

**Files:**
- Modify: `crawler/CrawlResult.java`, `crawler/CrawlService.java`, `crawler/UrlAdmission.java`,
  `runner/CrawlRunExecutor.java`
- Test: `checks/PageCheckAcceptanceTest.java`, `runner/CrawlRunExecutorTest.java`,
  `crawler/UrlAdmissionTest.java`, `crawler/CrawlServiceFullCrawlTest.java`

**Interfaces (produces):**
- `UrlAdmission.verifiable(NormalizedUrl url)` — true for an http(s) URL that is external, or
  internal and allowed by robots. The verifier's politeness gate (D19).
- `CrawlResult(RunSnapshots, int, int, List<String> coveredUrls, boolean, String,
  List<String> verificationCandidates, List<String> sitemapUrls)` — two components appended, one
  construction site.
- `CrawlRunExecutor`: crawl → `UrlVerificationService.verify(...)` + `TlsProbe.probe(...)` →
  `checks.evaluateRun(...)` → `checks.evaluateSite(...)` → `saveCrawlOutcome(...)` with the
  combined finding count.

- [ ] **Step 1: `UrlAdmissionTest`, red** — `verifiable` is true for an external http URL, true
      for an internal URL the crawl excluded by pattern or by depth, **false** for an internal URL
      robots disallows (`/geheim/intern.html` with `respectRobots`), true for that same URL when
      `respectRobots` is false, and false for any non-http scheme. Then implement it.

- [ ] **Step 2: `CrawlServiceFullCrawlTest`, red** — the shared crawl's `CrawlResult` carries
      `verificationCandidates` containing `/dateien/handbuch.pdf` and `http://localhost:9/tot`,
      containing **no** URL under `/geheim/` (robots), containing no URL that was visited, and
      free of duplicates; and `sitemapUrls` containing all four `<loc>` entries, including
      `/nicht-vorhanden.html` — the check needs the *declared* list, not the crawled one.

- [ ] **Step 3: Collect both in `CrawlService`.** Candidates are computed once at the end of
      `doCrawl` from the finished snapshots: every `LinkRef.target()` and `FrameRef.src()`,
      filtered by `admission.verifiable`, minus the visited set, in first-seen order so a run is
      reproducible. Sitemap locations are recorded in `seedFrontier` **before** admission
      filtering, normalised and deduped.


- [ ] **Step 4: Close the last two p2b carry-overs, with tests.** `CrawlService.visit()` counts a
      page as visited *and* failed when the discovery enqueue throws: move the enqueue into its
      own try/catch, log at WARN and return `DONE` — the page *was* visited, and a database blip
      must not corrupt the arithmetic `partialCoverage` reports. The soft-404 probe leaves an
      unreferenced screenshot per run: delete it after reading the probe (`Files.deleteIfExists`,
      ignoring failure — a temp file, not evidence) and assert in `CrawlServiceFullCrawlTest` that
      the artifact directory holds one file per snapshot. The soft snapshot-memory bound stays
      open and moves to Plan 4: a budget refinement, not a correctness bug.

- [ ] **Step 5: `CrawlRunExecutorTest`, red.** `theRunRecordsWhatItCovered` now asserts
      `covered_check_types` **contains** `DEAD_LINK`, `FILE_DOWNLOAD`, `TLS_CERT` and `HREFLANG`
      and still excludes `CONSOLE_ERRORS` and `SITEMAP_CONSISTENCY` (both ship disabled). New case:
      after a fixture run `external_url_check` holds a row for the external `localhost` URL and
      **no** row for any `127.0.0.1` URL — D20 end to end. `findings_total` gets a lower bound of
      10 rather than a brittle exact number.

- [ ] **Step 6: Wire the pipeline in `CrawlRunExecutor`** — between the post-crawl heartbeat and
      the check pass: verify, probe TLS, build the full `RunFacts`, evaluate pages, evaluate the
      site, concatenate. Extend the lease once more **before** verification: a large site's
      verification pass is minutes of blocking I/O and the stale-lease sweep must not reclaim a
      healthy run (§14). The existing log line grows the verification counts.

- [ ] **Step 7: The acceptance gate — extend `PageCheckAcceptanceTest`.** Its `@BeforeAll`
      already crawls once for the class; it now also runs the verification pass and the site
      checks into the same `findings` list. **Do not add a second crawl** — the suite's runtime
      depends on that rule. New assertions:

- `DEAD_LINK` reports `http://localhost:9/tot` as `.dead`, observed on `/`.
- `DEAD_LINK` reports `/geblockt-403` as `.unverifiable` at `INFO`, **not** as `.dead` — §8's
  false-positive rule, proven against a real 403.
- `DEAD_LINK` reports `/hart-404` and does **not** report `/verirrt.html` — a soft 404 answers
  200, and `PAGE_STATUS` owns it.
- `DEAD_LINK` reports nothing for `/dateien/handbuch.pdf` or `/extern/ok`.
- `FILE_DOWNLOAD` reports `/dateien/preisliste.pdf` as `.wrongType` naming `text/html`, nothing
  for `/dateien/handbuch.pdf`.
- `HREFLANG` reports `.deadAlternate` for the `fr` alternate and `.notReciprocated` for
  `/leistungen.html` → `/en/index.html`, and nothing for `/` ↔ `/en/index.html`.
- `TLS_CERT` reports nothing: the fixture is plain http (D6), and a check with nothing to say
  stays silent rather than reporting the absence of encryption as a defect.
- `SITEMAP_CONSISTENCY` reports nothing while disabled; re-evaluating the *same* snapshots with
  it enabled (the `SiteContext` copy the class already uses for `maxHops`) reports `.missingPage`
  for `/medien.html` and **no** `.deadEntry` for `/nicht-vorhanden.html` (D22). Navigate once,
  check many — still no second crawl.
- The whole list holds no duplicate `(type, subjectKey, locationKey, messageKey)` tuple: Plan 4
  fingerprints on the first three, and a duplicate here is a lost occurrence there.

- [ ] **Step 8: Run the full suite and commit.** `./mvnw test`, browser tests included. Expect
      roughly 300 tests and no change to the suite's shape: one Chromium sweep each in
      `PageCheckAcceptanceTest`, `CrawlServiceFullCrawlTest` and `CrawlRunExecutorTest`.

      Commit: `git add src/main src/test && git commit -m "feat(runner): verify URLs and run the site checks in the run pipeline"`

---

## Plan 3b completion check

- [ ] `./mvnw test` passes, browser tests included, and `-Pfast` passes too — everything but
      Tasks 4 and 6 is browser-free, which is what keeps the catalog cheap to extend
- [ ] Six task commits landed, plus whatever the reviews add
- [ ] `ModularityTest` still proves `checks → {model}`, and
      `grep -rn "org.springframework\|java.net.http\|javax.net.ssl" src/main/java/…/checks/`
      returns only `package-info.java`'s `@ApplicationModule` line
- [ ] `CheckRegistry.standard()` covers all thirteen `CheckType`s; `pendingInPlan3b` is gone
- [ ] `CheckDocumentationTest` passes in both directions — no missing key, no orphaned German
- [ ] `V8` is the only new migration, `ddl-auto=validate` still starts the app, and the p2b
      carry-overs are closed except the soft memory bound, which is stated as Plan 4's
- [ ] **Write `2026-08-21-webtesthelper-p4-findings.md` next**, from the section below

## Execution findings fed back to Plan 4's writer

Executed 2026-08-22 with subagent-driven development: six task commits plus nine
review-fix commits on `main` (`6831708`…`6f226d5`), 297 `-Pfast` / 354 full tests green,
browser acceptance included. Every task passed a two-stage review; the deviations and
measurements below are the executed truth, not plan text.

- **Verification candidates include `AlternateRef.target()`**, not just `LinkRef`+`FrameRef`
  as Step 3 of Task 6 literally says. The plan's own Task 6 acceptance assertion
  (`.deadAlternate` for the `fr` alternate) cannot pass otherwise: the alternate is a
  `<link>`, and `HreflangCheck` resolves targets through `facts.verifications()`. Plan 4
  inherits candidates as *all three ref kinds*.
- **The verification count log label counts internal candidates too** ("geprüfte URLs").
  Plan 4's end-of-run re-verification is the other consumer of `UrlVerifier`; the verifier
  is bounded per host already, exactly as §8 needs.
- **`UrlVerification.contentLength` from a ranged GET is the *part*, not the resource**
  (206 answers `content-length: 1024`; the fixture ignores `Range`, so no test catches it).
  Harmless for `FILE_DOWNLOAD.tooSmall` (1024 is never < 1024) — but if Plan 4's
  re-verification ever needs true size, parse `Content-Range`'s total or use the HEAD pass.
- **`FILE_DOWNLOAD` gates all three rules on `hasBody()`** (the plan's literal wording).
  In practice the verifier fetches a body for every `DocumentTypes.isDocument` target, so
  the gate is invisible; `tooSmall` additionally requires `contentLength > 0` so chunked
  PDFs (no content-length header) are never flagged.
- **Cache timestamps are truncated to microseconds at the write site**
  (`store` truncates `checkedAt` to `ChronoUnit.MICROS`); Postgres truncates silently, and
  a test comparing `Instant.now()` against a round-tripped row needs the same truncation.
- **The soft-404 probe screenshot name is a shared helper** (`crawler/ScreenshotNames`,
  used by `PageNavigator` and `CrawlService`); the `CrawlServiceFullCrawlTest` artifact
  assertion guards the two from drifting. The artifact assertion counts *reachable*
  snapshots — an unreachable page leaves no screenshot.
- **`CrawlRunExecutorTest` crawls once in `@BeforeAll`** (collapsed from per-test crawls by
  Task 6). Plan 4's pipeline assertions should extend that class or `PageCheckAcceptanceTest`
  (also one crawl per class) — a new browser-suite class is a third Chromium sweep.
- **The verification pass has no defensive catch**; if `UrlVerificationService` throws
  (cache-DB hiccup), `RunWorker.executeLeased`'s existing try/catch marks the run `FAILED`
  with the error. Deliberate: §14 wants run-level failures visible, not swallowed.
- **`truncate(String, int)` now exists in four `crawler` classes** (`CrawlService`,
  `PageNavigator`, `UrlVerifier`, `TlsProbe`). Flagged in final review as the one
  duplicated logic; Plan 4 may centralise it if it adds a fifth.
- **The fixture's new hreflang failure modes are proven only by Task 6's acceptance test**,
  not by `PageNavigatorTest` (plan-intended). `/en/index.html` has `fr` → `localhost:9`
  (dead) + `de` → `/`; `leistungen.html` has one-way `en` → `/en/index.html`.

Open questions Plan 4 inherits however execution goes:

- **Site-wide promotion meets the cache's dependents.** §8.1 says a URL that turns dead produces
  findings on every dependent site; nothing here acts on `dependent_site_ids`. Plan 4 decides
  whether materialisation fans out across sites or a later sweep does.
- **`UNVERIFIABLE` at `INFO` versus the notification threshold** (§11.1: only `ERROR` notifies) —
  the same question 3a left open for `REDIRECT_CHAIN.loop` at `WARN`.
- **Source-less media findings still merge at materialisation** (3a's finding, unchanged): two
  source-less elements on one page share `(type, subjectKey, locationKey)`.
- **The blocked-iframe signal still compares a failed document request's *final* URL against the
  frame's declared `src`** (3a's finding): a frame whose document redirects and is then refused
  by `X-Frame-Options`/CSP matches nothing and is silently missed. Recording the frame's resolved
  URL at extraction is the fix nobody has needed enough yet.
- **The snapshot memory bound is soft in the all-unreachable corner** (p2b's carry-over): `room`
  counts reachable pages.

## What Plan 4 consumes from this plan

- `model.UrlVerification` / `UrlVerifications` and `crawler.UrlVerifier` — the input to
  end-of-run **re-verification** (§8): Plan 4 re-runs the verifier (already bounded per host) over
  the subjects of failed findings only, with backoff, and drops those that recover before any
  becomes a `Finding`.
- `ExternalUrlCacheJdbcRepository.store(...)` — re-verification must write the corrected result
  back, or the cache keeps a transient failure for its full TTL.
- `CheckEngine.evaluateRun` + `evaluateSite` — the two lists Plan 4 fingerprints.
- `CheckFinding.locationKey()` — already `"*"` for a site-scoped finding, exactly the site-wide
  form §6.2 promotes page findings into.
- `RunFacts` — settled at seven components; Plan 4 should not need an eighth.
