# WebTestHelper Plan 5 — The Web UI, Security and the Mail Relay

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Put a face on the four plans behind it. A colleague logs in, adds a website, presses
*Jetzt prüfen*, watches the run advance, reads what changed in German, accepts the first run as
the baseline — and an administrator configures the mail relay and proves it works with one
button, before any report depends on it.

**Architecture:** Two new modules. `web` is Thymeleaf plus HTMX, server-rendered, and is the only
module that authenticates; it may read every other module and nothing may read it. `reporting`
owns the outbox and the sender, depends on `model` and `catalog`, and knows nothing about runs —
Phase 1 gives it exactly one message to carry, the test mail, which is the whole point of
proving the relay early (§17). `catalog` grows the global settings store and the AES-GCM key
that §11.4 shares with the Phase-3 credential store. `runner` grows the production poll loop its
`RunWorker` javadoc has been promising since plan 1.

**Tech Stack:** Java 25, Spring Boot 4.1.1, Spring Security 7 (local accounts, BCrypt),
Thymeleaf, HTMX 2 + Alpine 3 **vendored, never a CDN** (§12), PostgreSQL 17 via Testcontainers,
GreenMail for the relay tests (already a test dependency). **One new dependency:**
`org.commonmark:commonmark`, because §13.6 specifies Markdown.

**Spec:** `docs/superpowers/specs/2026-08-21-webtesthelper-design.md`; §-references point there.
**Roadmap:** `…-phase-1-roadmap.md` — plan 5 of 5, the last. **Predecessors:** p1, p2a, p2b, p3a,
p3b, p4, all executed and on `main`; written from p4's "What Plan 5 consumes" section, under
`CLAUDE.md`'s plan calibration rules, which override `superpowers:writing-plans`' "No
Placeholders" section: paths, signatures and acceptance *assertions* are exact, obvious bodies
are not written out.

**Ends with:** a login, a site you can add, a manual run that actually executes because something
finally polls the queue, a run report whose five sections read in German, a baseline button that
says what it will do before it does it, `?` affordances that explain themselves in place, and a
test mail that arrives in GreenMail — or fails visibly in the outbox with the relay's own error.

**This plan is long — nine tasks, ~900 lines.** It is the only plan that ships a user interface,
and a screen costs a controller, a template, its German keys and a slice test each. If execution
budget runs short, the split is at **Task 7**: tasks 1–6 are the UI and need no mail, tasks 7–9
are settings and the relay and need no new screen vocabulary.

---

## Deviations and constraints

The roadmap's deviation table (D1–D28) and everything p1–p4 established apply unchanged and are
**not** restated here. `CLAUDE.md` holds the test rules. Eight deviations are new:

- **D29 — live run progress is an HTMX poll, not SSE.** §12 asks for SSE via HTMX's SSE
  extension. An emitter registry has to be created when a run starts, fed from the crawl's
  progress callback on a browser worker thread, and torn down on every terminal path including
  the ones that throw — for one number on one screen that changes every few seconds. A
  `hx-trigger="every 3s"` on a fragment costs one indexed query per viewer and cannot leak. SSE
  arrives with the Phase-2 dashboard, where several live regions justify the machinery.
- **D30 — global settings and the AES-GCM key live in `catalog`.** §5.1 lists no settings module,
  and §11.4 says the SMTP password is encrypted "with the same AES-GCM key as journey
  credentials" — credentials are `catalog`'s (§5.1). Putting the key anywhere else guarantees two
  key readers by Phase 3. `reporting` therefore depends on `{model, catalog}`.
- **D31 — user accounts live in `web`, and Phase 1 ships no user-management screen.** `web` is
  the only module that authenticates and no other module reads a user. The admin account is
  bootstrapped from the environment (§16); §12's users screen is Phase 2's Settings work. The
  two roles are real and enforced from day one — the tests prove both sides of every rule — the
  screen to create a second one is what waits.
- **D32 — technical strings in a finding's message arguments are translated at render time, and
  the untranslated original appears only under *Technische Details*.** This closes the item the
  plan-3 review left open and p4 handed forward: `PAGE_UNREACHABLE.navigation` carries Chromium's
  `net::ERR_…`, `DEAD_LINK.dead` and `TLS_CERT.handshakeFailed` carry a Java exception string,
  and §13.1 says no internal identifier reaches the screen. The checks cannot fix this themselves
  — they are pure functions with no `MessageSource` (§5.1) — so the renderer does it. §13.2 puts
  the technical evidence *below* the three plain-language paragraphs, which is where the raw
  string belongs and is allowed to stay.
- **D33 — the run poller is a dedicated daemon thread; only the outbox dispatcher uses
  `@Scheduled`.** `workOnce()` runs a whole crawl and blocks for minutes; on Spring's shared
  single-threaded scheduler it would stall the outbox behind every run. Both are disabled by
  property in `application-test.properties` — a poller that wakes up during a repository test
  and claims its fixture run is a debugging session nobody enjoys.
- **D34 — Phase 1's only mail is the test mail.** §17 scopes Phase 1 to "SMTP settings + outbox +
  sender + test-mail button"; the notification *policy* of §11.1 and the digest content of §11.2
  are Phase 2. The `Notifier` interface, the outbox and the retry are all built and proven, so
  Phase 2 adds a message, not a mechanism.
- **D35 — the mail-health banner lives in the shared layout.** §11.5 puts it on the dashboard;
  the dashboard is Phase 2. A banner nobody can see because its screen does not exist yet is the
  failure §11.5 is guarding against, so it renders on every page until the dashboard claims it.
- **D36 — `org.commonmark:commonmark` is the one new dependency.** §13.6 specifies Markdown in
  `src/main/resources/help/`. Hand-rolling a Markdown subset is how help text acquires a dialect.

New constraints this plan introduces:

- **Nothing may depend on `web`.** `ModularityTest` enforces it once `web/package-info.java`
  declares the module; the completion check greps for it too.
- **All UI copy goes through the message bundle** (§12: bundles from day one). Keys are prefixed
  `ui.` — **never `check.` or `finding.`**, because `CheckDocumentationTest`'s
  `theBundleCarriesNoKeysForChecksThatDoNotExist` scans exactly those two prefixes and would
  report every UI key as an orphan.
- **No template may reference an off-host asset.** §12: this runs on a host that may have no
  outbound internet. `VendoredAssetsTest` (Task 2) fails the build on a CDN reference.
- **Every mutating request is a POST with the CSRF token.** Spring Security's default is on;
  each controller slice test asserts the tokenless request is rejected, because a template that
  forgets `th:action` silently loses the token and the button "just stops working".
- **The German is written once, in `messages.properties`.** A template that hardcodes a German
  word is a key that can never be found again.

## Decided constants

| Constant | Value | Why |
|---|---|---|
| run poll interval | 5 s, `webtesthelper.runner.poll-interval` | idle cost is one `SKIP LOCKED` claim attempt |
| poller enabled | `webtesthelper.runner.poller-enabled`, default true, **false in tests** | D33 |
| progress refresh | 3 s, only while the run is `RUNNING` | D29 |
| outbox dispatch interval | 30 s, `webtesthelper.reporting.dispatch-interval` | a report is not a chat message |
| outbox max attempts | 5, then `FAILED` | §11.3 |
| outbox backoff | `1 min × 2^(attempts−1)`, capped at 1 h | reaches ~16 min at attempt 5 — a relay down longer than that is a person's problem |
| test mail dispatch | enqueued **and sent inline**, result shown as a flash | §11.4 wants "immediate, unambiguous confirmation"; the row still lands in the outbox |
| BCrypt | Spring's `delegatingPasswordEncoder` default (`{bcrypt}`, strength 10) | upgradeable in place |
| AES-GCM | 256-bit key, fresh 12-byte IV per value, 128-bit tag, `base64(iv‖ct)` | §11.4 |
| keyfile | `{webtesthelper.data-dir}/keyfile`, mode 0600, generated on first start | §16 |
| help topics | three: `bericht-lesen`, `ausgangsbestand`, `smtp-einrichten` | D13's minimal footprint: mechanism + 3 topics + test |
| run history page size | 20 | one query per site detail |
| occurrences per finding | 50, with the full count beside them | a site-wide finding on 312 pages must not render 312 rows |

## URL vocabulary

German paths, because the copy is German and a `/findings/` next to a *Befunde* heading is the
same drift as an untranslated identifier. `USER` means authenticated; `ADMIN` is a superset.

| Path | Method | Role | Screen |
|---|---|---|---|
| `/anmelden` | GET, POST | anonymous | Login |
| `/abmelden` | POST | any | Logout |
| `/` | GET | USER | Websites-Übersicht (Phase 1's landing page; the dashboard is Phase 2) |
| `/websites/neu`, `/websites` | GET, POST | ADMIN | New site |
| `/websites/{id}` | GET | USER | Site detail: settings, active checks, run history |
| `/websites/{id}/bearbeiten` GET, `/websites/{id}` POST | | ADMIN | Edit site |
| `/websites/{id}/pruefen` | POST | USER | Manual run → redirect to the run |
| `/laeufe/{id}` | GET | USER | Run detail: the five sections |
| `/laeufe/{id}/fortschritt` | GET | USER | HTMX progress fragment |
| `/laeufe/{id}/ausgangsbestand` | POST | USER | Accept as baseline |
| `/befunde/{id}` | GET | USER | Finding detail |
| `/artefakte/{runId}/{name}` | GET | USER | Screenshot |
| `/hilfe`, `/hilfe/{topic}` | GET | USER | Handbook |
| `/hilfe/hinweis/{topic}` | GET | USER | `?` affordance fragment |
| `/einstellungen` | GET, POST | ADMIN | SMTP, Basis-URL, Umleitung |
| `/einstellungen/testmail` | POST | ADMIN | Test mail |
| `/postausgang` | GET | ADMIN | Outbox with errors |
| `/actuator/**` | GET | ADMIN | health, info, metrics (already exposed) |

---

### Task 1: Accounts, login and the security rules

**Files:**
- Create: `src/main/resources/db/migration/V11__app_user.sql`
- Create: `web/package-info.java`, `web/AppRole.java`, `web/SecurityConfig.java`,
  `web/AppUserService.java`, `web/AdminBootstrap.java`, `web/AdminProperties.java`,
  `web/persistence/AppUserEntity.java`, `web/persistence/AppUserRepository.java`
- Create: `src/main/resources/templates/anmelden.html`
- Modify: `src/main/resources/application.properties`, `src/main/resources/messages.properties`
- Test: `web/SecurityRulesTest.java`, `web/AdminBootstrapTest.java`

**Interfaces (produces):**
- `enum AppRole { ADMIN, USER }` — authorities are `ROLE_ADMIN` / `ROLE_USER`.
- `AppUserEntity` (Lombok `@Getter @Setter`, JPA, table `app_user`): `Long id`, `String username`,
  `String passwordHash`, `AppRole role` (`@Enumerated(STRING)`), `boolean enabled`,
  `Instant createdAt`, `long version` (`@Version`).
- `AppUserService implements UserDetailsService` with `long create(String username,
  String rawPassword, AppRole role)` and `boolean isEmpty()`.
- `record AdminProperties(String username, String password)` bound to `webtesthelper.admin`.
- `SecurityConfig` exposes `SecurityFilterChain filterChain(HttpSecurity)` and
  `PasswordEncoder passwordEncoder()`.

- [ ] **Step 1: Write `V11__app_user.sql`.** The unique index is on `lower(username)`: two
      accounts differing only in case is a support call, not a feature.

```sql
-- Local accounts, BCrypt hashes, two roles (spec 12). No external identity provider: this runs
-- on an internal host that may have no outbound internet at all.
CREATE TABLE app_user (
    id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    username TEXT NOT NULL,
    password_hash TEXT NOT NULL,
    role TEXT NOT NULL CHECK (role IN ('ADMIN','USER')),
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    version BIGINT NOT NULL DEFAULT 0
);

CREATE UNIQUE INDEX ux_app_user_username ON app_user (lower(username));
```

- [ ] **Step 2: `SecurityRulesTest`, red first.** `@WebMvcTest(SecurityConfig.class)` with
      `@MockitoBean AppUserService`. The controllers it names do not exist yet, and that is what
      makes the assertions precise: **an authorised request to a missing screen is 404, an
      unauthorised one is 403.** Later tasks turn the 404s into 200s.
- anonymous `GET /` redirects to `/anmelden`; `GET /anmelden` is 200.
- anonymous `GET /vendor/htmx.min.js` is not redirected (403/404, never a login redirect).
- `@WithMockUser(roles = "USER")`: `GET /einstellungen` is 403, `GET /postausgang` is 403,
  `GET /laeufe/1` is 404 (the rule allows it).
- `@WithMockUser(roles = "ADMIN")`: `GET /einstellungen` is 404 — allowed, not yet built.
- `POST /websites/1/pruefen` as USER **without** a CSRF token is 403; with `csrf()` it is 404.
- `POST /abmelden` with `csrf()` as any user redirects to `/anmelden?abgemeldet`.

- [ ] **Step 3: Implement `SecurityConfig`.** The rules are the security boundary and the one
      place a mistake is a vulnerability rather than a bug, so they are written out:

```java
http.authorizeHttpRequests(auth -> auth
        .requestMatchers("/anmelden", "/vendor/**", "/css/**", "/favicon.ico").permitAll()
        .requestMatchers("/einstellungen/**", "/postausgang", "/actuator/**",
                "/websites/neu", "/websites/*/bearbeiten").hasRole("ADMIN")
        .requestMatchers(HttpMethod.POST, "/websites").hasRole("ADMIN")
        .anyRequest().authenticated())
    .formLogin(login -> login.loginPage("/anmelden").defaultSuccessUrl("/", false).permitAll())
    .logout(out -> out.logoutUrl("/abmelden").logoutSuccessUrl("/anmelden?abgemeldet"));
```

CSRF and the session fixation defaults stay on — do not touch them. `passwordEncoder()` returns
`PasswordEncoderFactories.createDelegatingPasswordEncoder()`.

- [ ] **Step 4: `AppUserService` and the login template.** `loadUserByUsername` looks the row up
      case-insensitively and maps to Spring's `User` with `ROLE_` + `role.name()`; a disabled or
      missing row throws `UsernameNotFoundException`. `anmelden.html` is a plain form posting
      `username`/`password` to `/anmelden` with the CSRF hidden field, showing
      `#{ui.anmelden.fehler}` when `param.error` is present. Message keys: `ui.anmelden.titel`,
      `.benutzer`, `.passwort`, `.absenden`, `.fehler`, `.abgemeldet`.

- [ ] **Step 5: `AdminBootstrapTest`, red.** Extends `AbstractPostgresTest`, clears `app_user` in
      `@BeforeEach`. Assertions:
- with `webtesthelper.admin.password` set, running the bootstrap creates exactly one enabled
  `ADMIN` whose stored hash is not the plaintext and which `PasswordEncoder.matches` accepts.
- running it a second time creates nothing (the guard is `isEmpty()`, not "user absent").
- with the property blank and the table empty, an account is still created and the generated
  password is logged once — a self-hosted app that cannot be logged into on first boot is
  unusable, and this is exactly what Spring Boot itself does.

- [ ] **Step 6: Implement `AdminBootstrap`** (`ApplicationRunner`, ordered before nothing else in
      particular) and add to `application.properties`:
      `webtesthelper.admin.username=${WTH_ADMIN_USER:admin}` and
      `webtesthelper.admin.password=${WTH_ADMIN_PASSWORD:}`.

- [ ] **Step 7: Declare the module.** `web/package-info.java`:
      `@ApplicationModule(displayName = "Web", allowedDependencies = {"model", "catalog",
      "checks", "runner", "findings"})` — `reporting` is added in Task 8. `checks` is on the list
      because the renderer resolves a finding's three explanation keys through `CheckDescriptor`
      (D14) rather than re-deriving `"check." + type + ".title"` in a second place, which is how
      a convention acquires two implementations that disagree.

- [ ] **Step 8: Run and commit.** `./mvnw test -Pfast`. `FlywayMigrationTest` and
      `ddl-auto=validate` prove V11 and the entity agree; `ModularityTest` proves the new module.

---

### Task 2: The German shell and the site list

**Files:**
- Create: `src/main/resources/static/vendor/htmx.min.js`, `…/vendor/alpine.min.js`,
  `src/main/resources/static/css/app.css`
- Create: `src/main/resources/templates/layout.html`,
  `templates/websites/liste.html`, `templates/websites/formular.html`
- Create: `web/SiteController.java`, `web/SiteFormModel.java`
- Modify: `src/main/resources/messages.properties`
- Test: `web/VendoredAssetsTest.java`, `web/UiMessageKeyTest.java`, `web/SiteControllerTest.java`

**Interfaces (produces):**
- `record SiteFormModel(String name, String baseUrl, int maxPages, int maxDepth,
  int maxDurationMinutes, String includePatterns, String excludePatterns, boolean respectRobots,
  String userAgent)` with `SiteForm toForm()` and `static SiteFormModel of(SiteContext)`. The two
  pattern fields are newline-separated textareas — a non-technical colleague types one glob per
  line, not a JSON array. Bean validation: `@NotBlank name`, `@NotBlank @Pattern` http(s) base
  URL, `@Min(1) maxPages`, `@Min(0) maxDepth`, `@Min(1) maxDurationMinutes`.
- `SiteController`: `GET /` → `websites/liste`, `GET /websites/neu`, `POST /websites`,
  `GET /websites/{id}/bearbeiten`, `POST /websites/{id}` — the last two reuse `formular.html`.

- [ ] **Step 1: Vendor HTMX and Alpine.** Download `htmx.min.js` (2.x) and `alpine.min.js` (3.x)
      into `src/main/resources/static/vendor/`. **If the download fails, stop and ask** — there
      is no CDN fallback, ever (§12), and a vendored copy is the whole point.

- [ ] **Step 2: `VendoredAssetsTest`, red.** Browser-free, no Spring. Assertions:
- `src/main/resources/static/vendor/htmx.min.js` and `alpine.min.js` exist and each exceed 10 KB.
- no file under `src/main/resources/templates/` contains `//unpkg.com`, `//cdn.`,
  `//ajax.googleapis.com` or `https://cdn` — the CDN reference this project must never grow.

- [ ] **Step 3: Write `layout.html`.** One parameterised fragment, no layout-dialect dependency.
      This is the mechanism every later template repeats, so it is written out once:

```html
<!-- layout.html -->
<html th:fragment="seite(titel, inhalt)" lang="de">
<head><title th:text="${titel}">…</title>
  <link rel="stylesheet" th:href="@{/css/app.css}">
  <script defer th:src="@{/vendor/alpine.min.js}"></script>
  <script th:src="@{/vendor/htmx.min.js}"></script></head>
<body><nav>…</nav><main th:replace="${inhalt}"></main></body></html>

<!-- every page template -->
<html th:replace="~{layout :: seite(#{ui.titel.websites}, ~{::main})}"><main>…</main></html>
```

The nav carries *Websites*, *Hilfe*, *Einstellungen* (`sec:authorize="hasRole('ADMIN')"` via
`thymeleaf-extras-springsecurity6`, already on the classpath through the Thymeleaf starter — if
it is not, use `${#authorization}` and say so in the execution findings), the logged-in username
and a `POST /abmelden` button.

- [ ] **Step 4: `UiMessageKeyTest`, red.** §13.7's spirit applied to the UI: a missing key renders
      as `??ui.foo_de??` on screen and nothing else notices. Browser-free, no Spring.
- scan every `.html` under `src/main/resources/templates/` for `#{...}` literals, and assert each
  resolves in `ResourceBundle.getBundle("messages", Locale.GERMAN)`.
- keys built by concatenation (`#{'ui.lauf.status.' + ...}`) are skipped by the scanner and
  covered by `EnumLabelsTest` in Task 3 instead — record that in the test's javadoc so the gap is
  documented rather than discovered.
- assert no template key starts with `check.` or `finding.` **except** the two the catalog owns
  (`check.*.title`, `check.*.description`, `check.*.remediation`), which the site detail and the
  finding detail legitimately render.

- [ ] **Step 5: `SiteControllerTest`, red.** `@WebMvcTest(SiteController.class)` with
      `@MockitoBean SiteService` (and `AppUserService` for the security config). Assertions:
- `GET /` as USER is 200, uses view `websites/liste`, and the model holds the summaries.
- `GET /websites/neu` as USER is 403, as ADMIN is 200.
- `POST /websites` with a blank name re-renders `websites/formular` with a field error and never
  calls `SiteService.create`.
- `POST /websites` with valid input calls `create` with a `SiteForm` whose `includePatterns` is
  the split, trimmed, blank-free list of the textarea's lines, and redirects to `/websites/{id}`.
- `POST /websites` without CSRF is 403.

- [ ] **Step 6: Implement the controller, the two templates and `app.css`.** The CSS is plain,
      hand-written and small — a traffic-light colour per severity, a section heading, a table.
      No framework: this UI has seven screens and a design system would outweigh them.

- [ ] **Step 7: Run and commit.** `./mvnw test -Pfast`.

---

### Task 3: Site detail, the manual run, and the poller that finally runs it

**Files:**
- Create: `src/main/resources/templates/websites/detail.html`
- Create: `runner/RunPoller.java`, `runner/RunnerProperties.java`
- Modify: `web/SiteController.java`, `src/main/resources/application.properties`,
  `src/test/resources/application-test.properties`, `messages.properties`
- Test: `runner/RunPollerTest.java`, `web/SiteDetailControllerTest.java`, `web/EnumLabelsTest.java`

**Interfaces (produces):**
- `record RunnerProperties(Duration pollInterval, boolean pollerEnabled)` bound to
  `webtesthelper.runner`.
- `RunPoller implements SmartLifecycle` — `start()` spawns one daemon platform thread,
  `stop()` sets the flag, interrupts and joins with a 10 s budget.
- `SiteController` gains `GET /websites/{id}` → `websites/detail` and
  `POST /websites/{id}/pruefen` → `RunService.enqueue(id, RunTrigger.MANUAL, RunScope.FULL)`,
  redirecting to `/laeufe/{runId}`.

- [ ] **Step 1: `RunPollerTest`, red.** Browser-free, no Spring, Mockito-stubbed `RunWorker`.
      Assertions:
- `start()` then a short await: `workOnce()` was called at least once.
- a `workOnce()` that returns `true` is called again immediately — a queue with three runs must
  not take three poll intervals to drain.
- a `workOnce()` that throws does **not** end the loop: the next call still happens. This is the
  assertion that matters. A poller that dies on one bad run turns the product off silently, and
  the only symptom is runs that stay `QUEUED` forever.
- `stop()` returns within the budget and no further calls happen afterwards.

- [ ] **Step 2: Implement `RunPoller`.** The loop is four lines and every one of them is a trap
      (D33 — a `@Scheduled` method here blocks the outbox behind a crawl):

```java
private void loop() {
    while (running) {
        try {
            if (!worker.workOnce()) { Thread.sleep(properties.pollInterval()); }
        } catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
        catch (Exception keepPolling) { log.error("Warteschlange konnte nicht…", keepPolling); }
    }
}
```

The `@ConditionalOnProperty("webtesthelper.runner.poller-enabled")` carries
`matchIfMissing = true`. Add to `application.properties`:
`webtesthelper.runner.poll-interval=5s`, `webtesthelper.runner.poller-enabled=true`; add
`webtesthelper.runner.poller-enabled=false` to `application-test.properties` — **without this
line the poller claims other tests' fixture runs mid-suite.**

- [ ] **Step 3: `EnumLabelsTest`, red.** Browser-free, no Spring. For every constant of
      `RunStatus`, `RunScope`, `RunTrigger`, `ReportSection`, `Severity` and `TriageStatus`,
      assert `ui.<simpleName-lowercased>.<CONSTANT>` resolves in the German bundle — the keys the
      templates build by concatenation, which `UiMessageKeyTest` cannot see. Also assert none of
      the resolved German strings *is* the constant name: `ui.lauf.status.FAILED=FAILED` would
      pass a resolution test and fail §13.1.

- [ ] **Step 4: `SiteDetailControllerTest`, red.** `@WebMvcTest`, mocked `SiteService` and
      `RunService`. The screen reads `SiteService.contextFor(id)` for the budget, the patterns and
      the per-check settings — `SiteSummary` carries only name, base URL and a count — and
      `RunService.recentForSite(id, 20)` for the history. Assertions:
- `GET /websites/{id}` renders the site's budget, its patterns, its run history rows, and the
  German title of every check `SiteContext.enabled(type)` returns true for (from
  `check.TYPE.title` via `CheckRegistry`).
- an unknown id is 404, not a 500 — `SiteService.summary` throws `IllegalArgumentException`, so
  the controller advice added here maps it to 404.
- `POST /websites/{id}/pruefen` calls `enqueue(id, MANUAL, FULL)` and redirects to the returned
  run's detail page.
- pressing it twice enqueues once — the assertion is on `RunService` being called twice and the
  same id coming back, which is p1's dedupe, restated here because the button is where a user
  discovers it.

- [ ] **Step 5: Implement the detail screen and the two handlers.** The check list is read-only
      in Phase 1: it shows which checks are active with their German titles and descriptions.
      Editing them is §12's site-detail work in Phase 2 — `SiteService.setCheckEnabled` already
      exists and stays UI-less for now. State that on the screen in one sentence rather than
      rendering disabled checkboxes that look broken.

- [ ] **Step 6: Run and commit.** `./mvnw test -Pfast`.

---

### Task 4: The run report — five sections, live progress, baseline acceptance

**Files:**
- Create: `src/main/resources/templates/laeufe/detail.html`,
  `templates/fragments/fortschritt.html`, `templates/fragments/befundzeile.html`
- Create: `web/RunController.java`, `web/FindingView.java`, `web/FindingViewFactory.java`
- Modify: `messages.properties`
- Test: `web/FindingViewFactoryTest.java`, `web/RunControllerTest.java`

**Interfaces (consumes):** `FindingService.diffOf(siteId, runId)` → `RunDiff`;
`RunService.summary(runId)` → `RunSummary`; `RunService.acceptBaseline(runId)` → `int`.

**Interfaces (produces):**
- `record FindingView(long id, String title, String message, String remediation,
  String locationText, boolean siteWide, int pageCount, Severity severity, TriageStatus triage)`
  — **it deliberately carries no `CheckType`.** Keeping the internal identifier out of the view
  type makes §13.1 structurally true instead of a review habit, and the CSS class comes from
  `severity`.
- `FindingViewFactory` (a `@Component` over `MessageSource` + `CheckRegistry`):
  `FindingView of(Finding finding, Locale locale)` and
  `Map<ReportSection, List<FindingView>> of(RunDiff diff, Locale locale)`, preserving
  `ReportSection`'s declaration order.
- `RunController`: `GET /laeufe/{id}`, `GET /laeufe/{id}/fortschritt`,
  `POST /laeufe/{id}/ausgangsbestand`.

- [ ] **Step 1: `FindingViewFactoryTest`, red.** Browser-free, real `ResourceBundleMessageSource`,
      hand-built `Finding` records. Assertions:
- `title` is `check.DEAD_LINK.title`'s German, `remediation` is the remediation key's, and
  `message` is `messageKey` resolved with `messageArgs`.
- a site-wide finding (`locationKey == "*"`, `pageCount == 312`) gets `siteWide == true` and a
  `locationText` reading *"auf 312 Seiten"*; a page-scoped one gets its location key.
- `of(RunDiff)` returns the sections in `ReportSection` order and omits nothing that the diff
  holds — including an empty list for a section the diff has as empty.
- **`noRenderedTextCarriesAnInternalIdentifier`:** for every `CheckType`, no component of the
  view contains that constant's name. This is the §13.1 gate that `CheckDocumentationTest` cannot
  reach, because it scans the static German and this scans the rendered result.

- [ ] **Step 2: Implement `FindingViewFactory`** against those assertions. `TechnicalText` (Task
      5) is not wired yet; leave the raw arg in place and let Task 5's test turn red on it.

- [ ] **Step 3: `RunControllerTest`, red.** `@WebMvcTest(RunController.class)`, mocked
      `RunService` and `FindingService`. Assertions:
- a run detail with findings in `NEW`, `FIXED` and `KNOWN` renders three section headings with
  their counts; a section the diff holds as empty renders **no heading at all** — an empty
  *Regressionen* block on every report teaches people to stop reading the page.
- the coverage line renders `pagesVisited`, `pagesFailed` and, when `partialCoverage` is true, a
  German sentence saying the run did not reach everything and resolved nothing it did not see
  (§6.4 — the user has to know why a fixed-looking finding is still listed).
- a `FAILED` run renders `errorMessage` in the technical block, not as the headline.
- `GET /laeufe/{id}/fortschritt` for a `RUNNING` run returns the fragment containing
  `hx-trigger="every 3s"`; for a terminal run it returns the response header `HX-Refresh: true`
  — the browser reloads and gets the finished report with no polling state machine in the page.
- `POST /laeufe/{id}/ausgangsbestand` calls `acceptBaseline` and redirects to the run with a
  flash message carrying the moved count; without CSRF it is 403.
- the baseline button is absent when `RunSummary.baselineAccepted()` is true.

- [ ] **Step 4: Implement the controller, `detail.html` and the two fragments.** The baseline
      control is §13.4's consequence-before-the-click, and it is the one place in this plan where
      the copy is load-bearing: an Alpine-toggled panel (`x-data="{ offen: false }"`) states, in
      words, that this acknowledges every still-unassessed finding **of this run**, that they
      move to *Bekannt* and stop appearing as new, and that it cannot be undone in bulk — then
      offers the POST. Key `ui.lauf.ausgangsbestand.folge`, and it names the count.

- [ ] **Step 5: Run and commit.** `./mvnw test -Pfast`.

---

### Task 5: Finding detail — evidence, occurrences, and the technical-text rule

**Files:**
- Create: `web/FindingController.java`, `web/FindingDetailView.java`, `web/TechnicalText.java`,
  `web/ArtifactController.java`
- Create: `src/main/resources/templates/befunde/detail.html`
- Modify: `findings/FindingStore.java`, `findings/FindingService.java`,
  `web/FindingViewFactory.java`, `messages.properties`
- Test: `web/TechnicalTextTest.java`, `web/FindingControllerTest.java`,
  `web/ArtifactControllerTest.java`, `findings/FindingStoreReadTest.java`

**Interfaces (produces):**
- `FindingStore` gains `Optional<Finding> byId(long id)` and
  `List<FindingOccurrence> occurrencesOfLastRun(long findingId, int limit)` — the occurrences of
  the finding's own `last_seen_run`, ordered by `page_url` with nulls first, limited.
  `FindingService` re-exposes both. `findings` gains no new dependency; both are reads.
- `record FindingDetailView(FindingView summary, String description, String technicalDetail,
  String rawTechnicalDetail, Integer httpStatus, String requestDetail, String responseDetail,
  List<String> consoleExcerpt, String screenshotUrl, List<String> pages, int pageTotal,
  Instant firstSeenAt, Instant lastSeenAt, String triageReason)`.
- `TechnicalText`: `static String humanise(String raw, MessageSource messages, Locale locale)`
  and `static boolean isTechnical(String raw)`.

- [ ] **Step 1: `TechnicalTextTest`, red.** Browser-free (D32). The inputs are the exact strings
      p3 and p4 measured, not invented ones. Assertions:
- `net::ERR_NAME_NOT_RESOLVED`, `net::ERR_CONNECTION_REFUSED`, `net::ERR_CONNECTION_TIMED_OUT`,
  `net::ERR_TOO_MANY_REDIRECTS`, `net::ERR_BLOCKED_BY_RESPONSE`, `net::ERR_ABORTED` and
  `net::ERR_CERT_DATE_INVALID` each humanise to a German sentence that contains neither `net::`
  nor `ERR_`.
- `java.net.ConnectException: Connection refused` humanises with no `java.` anywhere in the
  result; a bare `java.net.SocketTimeoutException` (no message) still produces a sentence.
- an unmapped `net::ERR_SOMETHING_NEW` returns the generic `ui.technisch.unbekannt` sentence —
  it must not fall through to the raw string, because the whole failure mode here is a code
  nobody has seen yet reaching a colleague's screen.
- `isTechnical` is true for all of the above and false for `https://kunde.de/fehlt.pdf` — a URL
  is not a technical identifier and must survive untouched into the sentence.
- every `ui.technisch.*` key the mapping names resolves in the German bundle.

- [ ] **Step 2: Implement `TechnicalText` and wire it into `FindingViewFactory`.** Each message
      argument passes through `isTechnical`; a technical one is replaced by its humanised
      sentence before the message is formatted, and the original is kept for the detail view's
      *Technische Details* block. Add the `noRenderedTextCarriesAnInternalIdentifier` case that
      feeds a `PAGE_UNREACHABLE` finding with `messageArgs = ["net::ERR_TOO_MANY_REDIRECTS"]` —
      it should now pass where Task 4 left it failing.

- [ ] **Step 3: `FindingStoreReadTest`, red.** Extends `AbstractPostgresTest`. Assertions:
- `byId` returns a materialised finding with its `messageArgs` and `evidence` round-tripped out
  of `jsonb`, and `Optional.empty()` for an unknown id.
- `occurrencesOfLastRun` returns only the last run's rows — seed the same finding across two runs
  and assert the earlier run's page is absent.
- the site-scoped occurrence (`page_url IS NULL`) comes back with a null `pageUrl` rather than
  being dropped by the row mapper.
- `limit` truncates, and the caller can still learn the true total from
  `Finding.pageCount()`.

- [ ] **Step 4: `ArtifactControllerTest`, red.** `@WebMvcTest`. The screenshot name is p2b's
      32-hex-plus-`.png` (`ScreenshotNames`), which makes the guard exact rather than a
      best-effort sanitiser. Assertions:
- a real file under `{artifactDir}/{runId}/` streams with `Content-Type: image/png`.
- `..%2Fetc%2Fpasswd`, a name with a `/`, and a name that does not match `[0-9a-f]{32}\.png` are
  all 404 — one rule, no path arithmetic on user input.
- an anonymous request is redirected to `/anmelden`: a screenshot of a customer's page is not
  public.

- [ ] **Step 5: `FindingControllerTest`, red.** `@WebMvcTest`, mocked `FindingService`.
      Assertions:
- the page renders §13.2's three paragraphs in order: *Was wir geprüft haben* (description),
  *Was wir gefunden haben* (message), *Was zu tun ist* (remediation).
- the raw technical string appears **only** inside the `technische-details` block and never in
  the three paragraphs — asserted by locating the raw string's index in the body and the block's,
  not by a bare `contains`.
- a site-wide finding renders *"auf 312 Seiten"* and the occurrence list beneath it, capped at 50
  with the true total shown.
- a finding with `screenshotPath == null` renders no broken `<img>`.
- an unknown id is 404.

- [ ] **Step 6: Implement the controller, the view and `befunde/detail.html`.** The screenshot URL
      is `/artefakte/{lastSeenRun}/{screenshotPath}` — `Evidence.screenshotPath` is a bare file
      name (p2b), so the run id comes from the finding, not from the evidence.

- [ ] **Step 7: Run and commit.** `./mvnw test -Pfast`.

---

### Task 6: Inline help and the bundled handbook

**Files:**
- Modify: `pom.xml`
- Create: `web/HelpTopic.java`, `web/HelpService.java`, `web/HelpController.java`
- Create: `src/main/resources/templates/hilfe/index.html`, `templates/hilfe/thema.html`,
  `templates/fragments/hinweis.html`
- Create: `src/main/resources/help/bericht-lesen.md`, `help/ausgangsbestand.md`,
  `help/smtp-einrichten.md`
- Modify: `templates/laeufe/detail.html`, `templates/einstellungen/index.html` (Task 7 adds the
  file; wire its affordance there), `messages.properties`
- Test: `web/HelpServiceTest.java`, `web/HelpTopicsTest.java`

**Interfaces (produces):**
- `record HelpTopic(String id, String title, String html, String teaserHtml)` — `title` is the
  first `# ` heading, `teaserHtml` the first rendered paragraph.
- `HelpService` (`@Component`, scans `classpath:help/*.md` once at construction):
  `List<HelpTopic> all()`, `Optional<HelpTopic> byId(String id)`.
- `HelpController`: `GET /hilfe`, `GET /hilfe/{id}`, `GET /hilfe/hinweis/{id}` → the fragment.

- [ ] **Step 1: Add commonmark to `pom.xml`** — `org.commonmark:commonmark`, version property
      `${commonmark.version}` = `0.22.0` (present in the local repository, so the build still
      resolves offline; a newer 0.2x is fine if it resolves).

- [ ] **Step 2: Write the three topics** (D13's minimal footprint — mechanism, three topics, one
      test). They are the three places this plan asks a colleague to make a judgment call:
- `bericht-lesen.md` — what *Neu*, *Regression*, *Behoben*, *Weiterhin offen* and *Bekannt* mean,
  and why a run with partial coverage resolves nothing it did not reach (§6.4).
- `ausgangsbestand.md` — what accepting a baseline does, why the first run against an existing
  site produces 200 findings, and why run two is the one that matters (§6.3).
- `smtp-einrichten.md` — the five SMTP fields in plain words, what the test mail proves, and what
  *Alle Nachrichten umleiten* is for on a staging instance (§11.4).

- [ ] **Step 3: `HelpServiceTest`, red.** Browser-free, no Spring. Assertions:
- `all()` finds all three topics, sorted by title.
- `byId("bericht-lesen").title()` is the file's `# ` heading, not the filename.
- the rendered HTML of every bundled topic is non-empty and contains no `<script` — help text is
  authored in this repository, but rendering Markdown to HTML and dropping it into a page with
  `th:utext` is exactly the habit that later swallows a topic somebody pasted in.
- `byId("gibt-es-nicht")` is empty, not an exception.

- [ ] **Step 4: `HelpTopicsTest`, red — §13.7 enforcement 2.** Browser-free, no Spring. This is
      the second of the two build-failing documentation gates the spec names, and it is the one
      that did not exist before this plan:
- scan every `.html` under `src/main/resources/templates/` for `/hilfe/hinweis/<id>` and
  `/hilfe/<id>` and assert `src/main/resources/help/<id>.md` exists for each. A `?` that expands
  to nothing is worse than no `?` at all.
- assert at least three affordances are wired, so the test cannot pass by nobody using the
  mechanism.
- the reverse direction: every bundled `.md` is reachable from `/hilfe`, which `HelpService.all()`
  guarantees — assert `all()` covers the directory listing exactly, so a topic file that was
  renamed does not become invisible.

- [ ] **Step 5: Implement the service, the controller and the affordance.** The markup is the
      mechanism every future `?` copies, so it is written out once — note `data-hx-get`, because
      `th:hx-get` is not a Thymeleaf attribute and HTMX reads the `data-` prefixed form:

```html
<button type="button" class="hinweis-schalter" th:attr="aria-label=#{ui.hilfe.oeffnen}"
        th:data-hx-get="@{/hilfe/hinweis/{id}(id=${themaId})}"
        data-hx-target="next .hinweis" data-hx-swap="innerHTML">?</button>
<div class="hinweis" aria-live="polite"></div>
```

The fragment renders the teaser plus a *Mehr dazu* link to `/hilfe/{id}` — §13.5's "never a link
that navigates away and loses the user's context" is about the `?` itself, not about refusing to
offer the long form.

- [ ] **Step 6: Wire the three affordances** — `ausgangsbestand` beside the baseline button,
      `bericht-lesen` beside the run report's section headings, `smtp-einrichten` beside the SMTP
      form (Task 7). Task 7 must not be committed without its affordance or `HelpTopicsTest`'s
      "at least three" assertion is doing no work.

- [ ] **Step 7: Run and commit.** `./mvnw test -Pfast`.

---

### Task 7: Settings — the encrypted store, SMTP, base URL, redirect-all-mail

**Files:**
- Create: `catalog/SecretBox.java`, `catalog/AppSettings.java`, `catalog/SmtpSettings.java`,
  `catalog/TlsMode.java`, `catalog/SettingsBootstrap.java`,
  `catalog/persistence/AppSettingEntity.java`, `catalog/persistence/AppSettingRepository.java`
- Create: `web/SettingsController.java`, `web/SettingsForm.java`,
  `src/main/resources/templates/einstellungen/index.html`
- Modify: `src/main/resources/application.properties`, `messages.properties`
- Test: `catalog/SecretBoxTest.java`, `catalog/AppSettingsTest.java`,
  `web/SettingsControllerTest.java`

**Interfaces (produces):**
- `enum TlsMode { NONE, STARTTLS, SSL }`.
- `record SmtpSettings(String host, int port, TlsMode tls, String username, String password,
  String fromAddress)` with `boolean configured()` (host and from-address both present).
- `AppSettings` (`@Service`): `SmtpSettings smtp()`, `void saveSmtp(SmtpSettings)`,
  `String baseUrl()`, `void saveBaseUrl(String)`, `Optional<String> redirectAllMailTo()`,
  `void saveRedirectAllMailTo(String)`. Keys: `smtp.host`, `smtp.port`, `smtp.tls`,
  `smtp.username`, `smtp.password` (`encrypted = true`), `smtp.from`, `mail.base-url`,
  `mail.redirect-all-to`.
- `SecretBox` (`@Component`): `String encrypt(String plaintext)`, `String decrypt(String stored)`.
- `AppSettingEntity` maps the existing `app_setting` table from V1 — **no migration in this
  task**; the table has been waiting since plan 1.
- `SettingsForm` — the SMTP fields plus `baseUrl` and `redirectAllMailTo`, with `password`
  write-only: blank means unchanged.

- [ ] **Step 1: `SecretBoxTest`, red.** Browser-free; point `webtesthelper.data-dir` at a JUnit
      `@TempDir`. Assertions:
- round-trip: `decrypt(encrypt(s))` equals `s` for ASCII and for `"Paßwort mit Ümlaut"`.
- encrypting the same plaintext twice gives **different** ciphertext — a fresh IV per value is
  the property, and a constant IV is the classic AES-GCM catastrophe.
- ciphertext is base64 and contains neither the plaintext nor any substring of it.
- a tampered ciphertext (flip one base64 character) throws rather than returning garbage — the
  GCM tag is the point of choosing GCM.
- the key file is created on first use with owner-only permissions and is **reused** on the next
  construction: a regenerated key silently invalidates every stored password.

- [ ] **Step 2: Implement `SecretBox`.** The IV handling is the algorithm and the part a reader
      would otherwise re-invent differently:

```java
private static final int IV_BYTES = 12, TAG_BITS = 128;

public String encrypt(String plaintext) {
    byte[] iv = new byte[IV_BYTES];
    RANDOM.nextBytes(iv);                                  // SecureRandom, one per instance
    Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
    c.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
    byte[] ct = c.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
    return Base64.getEncoder().encodeToString(ByteBuffer.allocate(iv.length + ct.length)
            .put(iv).put(ct).array());                     // iv‖ct, so decrypt needs no state
}
```

`decrypt` splits the first `IV_BYTES` back off. The key is read from
`{webtesthelper.data-dir}/keyfile`, or generated (`KeyGenerator.getInstance("AES")`, 256 bit) and
written with `PosixFilePermissions.fromString("rw-------")` when absent.

- [ ] **Step 3: `AppSettingsTest`, red.** Extends `AbstractPostgresTest`, clears `app_setting`.
      Assertions:
- `saveSmtp` then `smtp()` round-trips every field including the password.
- the stored `setting_value` for `smtp.password` is **not** the plaintext and its row has
  `encrypted = true`; every other key's row has `encrypted = false`.
- `smtp()` on an empty table returns a record with `configured() == false` and no exception —
  the settings screen has to render before anything is configured.
- `saveBaseUrl` normalises a trailing slash away, so a deep link never becomes `…//befunde/1`.
- `redirectAllMailTo()` is empty for a blank value, not `Optional.of("")`.

- [ ] **Step 4: Implement `AppSettings`, `AppSettingEntity` and `SettingsBootstrap`.** The
      bootstrap is an `ApplicationRunner` that writes only keys that are **absent**, from
      `WTH_SMTP_HOST`, `WTH_SMTP_PORT`, `WTH_SMTP_TLS`, `WTH_SMTP_USER`, `WTH_SMTP_PASSWORD`,
      `WTH_SMTP_FROM` and `WTH_BASE_URL` (§16: environment variables bootstrap first start).
      Absent, not blank: a colleague who deliberately clears the username in the UI must not have
      it restored on the next container restart.

- [ ] **Step 5: `SettingsControllerTest`, red.** `@WebMvcTest`, mocked `AppSettings`. Assertions:
- `GET /einstellungen` as ADMIN is 200 and the rendered password field is **empty** even though
  `AppSettings.smtp()` returns one — a password that renders into HTML is a password in every
  browser cache and proxy log on the way.
- posting with a blank password calls `saveSmtp` with the *existing* password, not with `""`.
- posting with a blank `baseUrl` re-renders with a field error and calls nothing: §11.4 makes it
  required, and getting it wrong ships reports full of `http://localhost:8080` links.
- posting a `baseUrl` without a scheme is rejected the same way.
- `GET /einstellungen` as USER is 403; `POST` without CSRF is 403.
- the redirect-all-mail field renders §13.4's consequence sentence, and the test asserts the
  sentence is present when a value is set — a staging instance that quietly mails real colleagues
  is the failure this field exists to prevent, and a field with no warning invites it.

- [ ] **Step 6: Implement the controller and `einstellungen/index.html`**, including the
      `smtp-einrichten` `?` affordance from Task 6. Alpine hides the username/password pair when
      `tls == NONE` and no username is set — §12's "conditional field display in Settings" is
      exactly the case Alpine exists for here.

- [ ] **Step 7: Run and commit.** `./mvnw test -Pfast`.

---

### Task 8: The outbox — table, sender, retry, screen, health banner, test mail

**Files:**
- Create: `src/main/resources/db/migration/V12__notification.sql`
- Create: `reporting/package-info.java`, `reporting/OutboundMail.java`,
  `reporting/NotificationState.java`, `reporting/Notifier.java`, `reporting/EmailNotifier.java`,
  `reporting/MailDeliveryException.java`, `reporting/OutboxEntry.java`,
  `reporting/OutboxService.java`, `reporting/OutboxDispatcher.java`,
  `reporting/ReportingProperties.java`, `reporting/MailHealth.java`, `reporting/MailRenderer.java`,
  `reporting/persistence/NotificationEntity.java`, `reporting/persistence/NotificationRepository.java`,
  `reporting/persistence/OutboxClaimJdbcRepository.java`
- Create: `src/main/resources/templates/mail/testmail.html`, `templates/mail/testmail.txt`,
  `templates/postausgang/index.html`
- Create: `web/OutboxController.java`, `web/HealthBannerAdvice.java`
- Modify: `web/SettingsController.java`, `web/package-info.java`, `templates/layout.html`,
  `application.properties`, `application-test.properties`, `messages.properties`
- Test: `reporting/MailRendererTest.java`, `reporting/OutboxServiceTest.java`,
  `reporting/OutboxDispatcherTest.java`, `web/OutboxControllerTest.java`

**Interfaces (produces):**
- `record OutboundMail(String recipient, String subject, String html, String text)` — §11.5's
  multipart, both parts always present.
- `enum NotificationState { PENDING, SENT, FAILED }`.
- `interface Notifier { void deliver(OutboundMail mail) throws MailDeliveryException; }` —
  §11.5's one abstraction, `EmailNotifier` its only implementation.
- `OutboxService` (`@Service`): `long enqueue(OutboundMail)`, `DeliveryResult sendNow(long id)`,
  `List<OutboxEntry> recent(int limit)`, `int failedCount()`, `Optional<String> lastError()`.
- `record OutboxEntry(long id, String recipient, String subject, NotificationState state,
  int attempts, Instant createdAt, Instant sentAt, Instant nextAttemptAt, String lastError)`.
- `MailRenderer`: `OutboundMail testMail(String recipient, String baseUrl)` — Thymeleaf, both
  parts.
- `record ReportingProperties(Duration dispatchInterval, int maxAttempts, boolean dispatcherEnabled)`
  bound to `webtesthelper.reporting`.
- `web/package-info.java` adds `"reporting"` to `allowedDependencies`.

- [ ] **Step 1: Write `V12__notification.sql`.** `next_attempt_at` is what makes the backoff a
      property of the row rather than of a sleeping thread — a container restart must not reset
      it, and a `FOR UPDATE SKIP LOCKED` claim needs something to order by.

```sql
-- The outbox (spec 11.3): a run must never fail because the mail relay is down, and a failed
-- mail must be visible in the UI rather than lost in a log.
CREATE TABLE notification (
    id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    recipient TEXT NOT NULL,
    subject TEXT NOT NULL,
    body_html TEXT NOT NULL,
    body_text TEXT NOT NULL,
    state TEXT NOT NULL DEFAULT 'PENDING' CHECK (state IN ('PENDING','SENT','FAILED')),
    attempts INTEGER NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_error TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    sent_at TIMESTAMPTZ,
    version BIGINT NOT NULL DEFAULT 0
);

CREATE INDEX ix_notification_due ON notification (state, next_attempt_at);
CREATE INDEX ix_notification_recent ON notification (created_at DESC);
```

- [ ] **Step 2: `MailRendererTest`, red.** Browser-free; a real Thymeleaf `TemplateEngine`.
      Assertions: the test mail's subject and both parts are non-empty; the HTML part contains
      the base URL as a link and the text part contains it as bare text (§11.5 — corporate
      clients strip HTML more often than expected); neither part contains a Thymeleaf error
      marker or an unresolved `??key??`.

- [ ] **Step 3: `OutboxServiceTest`, red.** Extends `AbstractPostgresTest`, clears
      `notification`. Assertions: `enqueue` returns an id and the row is `PENDING` with
      `attempts = 0` and `next_attempt_at <= now()`; `recent(20)` is newest-first; `failedCount`
      counts only `FAILED`.

- [ ] **Step 4: `OutboxDispatcherTest`, red.** Extends `AbstractPostgresTest` **plus GreenMail**
      (`@RegisterExtension GreenMailExtension` on a free SMTP port), with `AppSettings` pointed
      at it. Assertions:
- one dispatch of a pending mail delivers it: GreenMail holds one message, with both a `text/html`
  and a `text/plain` part and the configured from-address; the row is `SENT` with `sent_at` set.
- with `mail.redirect-all-to` set, the message arrives at the redirect address and **not** at the
  original recipient, and the outbox row still records who it was meant for — otherwise the
  staging instance's outbox is a list of lies.
- against a dead port, one dispatch leaves the row `PENDING` with `attempts = 1`, a non-null
  `last_error` and `next_attempt_at` roughly one minute out; a second leaves `attempts = 2` and
  roughly two minutes out.
- at `attempts == maxAttempts` the row flips to `FAILED` and is never claimed again.
- a mail whose `next_attempt_at` is in the future is not claimed.
- two dispatchers running concurrently deliver a row once, not twice.

- [ ] **Step 5: Implement the dispatcher.** The claim reuses p2b's hard-won frontier lesson —
      **the CTE shape, never `WHERE id IN (SELECT … LIMIT … FOR UPDATE SKIP LOCKED)`**, which the
      planner is free to unnest into a semi-join that drops the LIMIT:

```sql
WITH due AS (
    SELECT id FROM notification
     WHERE state = 'PENDING' AND next_attempt_at <= now()
     ORDER BY next_attempt_at
     LIMIT ? FOR UPDATE SKIP LOCKED
)
UPDATE notification n SET attempts = n.attempts + 1
  FROM due WHERE n.id = due.id
 RETURNING n.id, n.recipient, n.subject, n.body_html, n.body_text, n.attempts
```

and the backoff, applied after a failed `deliver`:

```java
Duration wait = Duration.ofMinutes(1).multipliedBy(1L << Math.min(attempts - 1, 6));
if (wait.compareTo(Duration.ofHours(1)) > 0) { wait = Duration.ofHours(1); }
```

`@Scheduled(fixedDelayString = "${webtesthelper.reporting.dispatch-interval}")` guarded by
`@ConditionalOnProperty("webtesthelper.reporting.dispatcher-enabled")`, `matchIfMissing = true`,
and set **false** in `application-test.properties` (D33). `@EnableScheduling` goes on
`WebtesthelperApplication`.

- [ ] **Step 6: Implement `EmailNotifier`.** It builds a `JavaMailSenderImpl` from
      `AppSettings.smtp()` **per dispatch cycle**, not once at startup — §11.4's whole point is
      that changing the relay must not require a redeploy. `TlsMode` maps to
      `mail.smtp.starttls.enable` / `mail.smtp.ssl.enable`; an unconfigured `SmtpSettings` throws
      `MailDeliveryException` with a German sentence rather than a `NullPointerException` three
      frames down.

- [ ] **Step 7: The outbox screen, the banner and the test-mail button.** `OutboxController`
      renders `recent(50)` with state, attempts, next attempt and the error text.
      `HealthBannerAdvice` is a `@ControllerAdvice` adding `mailFailures` and `mailLastError` to
      every model; `layout.html` renders the banner and links to `/postausgang` when the count is
      non-zero (D35). `POST /einstellungen/testmail` enqueues the rendered test mail, dispatches
      it **inline**, and flashes either *"Testnachricht zugestellt."* or the relay's own error —
      §11.4 wants an immediate, unambiguous answer, and "in die Warteschlange gelegt" is neither.

- [ ] **Step 8: `OutboxControllerTest`, red then green.** `@WebMvcTest`: the screen is
      ADMIN-only; a `FAILED` row renders its `last_error`; the banner appears in the rendered
      layout when `failedCount > 0` and is absent at zero.

- [ ] **Step 9: Run and commit.** `./mvnw test -Pfast`.

---

### Task 9: The two acceptance tests

**Files:**
- Test: `web/RunReportAcceptanceTest.java`, `reporting/MailRelayAcceptanceTest.java`

Both are browser-free. The crawl is already proven three times over in `CrawlRunExecutorTest`
(p4) and adding a fourth Chromium class to prove that HTML renders would cost ninety seconds to
learn nothing (`CLAUDE.md`: crawl once per class, not once per test).

- [ ] **Step 1: `RunReportAcceptanceTest`** — `@SpringBootTest` + `@AutoConfigureMockMvc`,
      extends `AbstractPostgresTest`, `@WithMockUser(roles = "ADMIN")`. It drives the product's
      central promise, *what changed since last time*, through the screens:
- create a site through `POST /websites`; assert it appears on `GET /`.
- materialise run 1 through `FindingService.record` with three findings and full coverage;
  `GET /laeufe/{1}` shows three under **Neu**, nothing under **Behoben**, and the baseline button.
- `POST /laeufe/{1}/ausgangsbestand` redirects and reports three moved; re-rendering the page no
  longer offers the button.
- materialise run 2 with two of the three findings and full coverage; `GET /laeufe/{2}` shows
  **zero** under *Neu*, one under **Behoben** and two under **Bekannt** — the acknowledged ones
  are still broken and still listed, quietly, which is §6.3's whole reason for two status axes.
- a finding's *Befund* link resolves and its detail page carries all three §13.2 paragraphs.
- materialise run 3 with partial coverage touching one page only; the run page renders the
  partial-coverage sentence and the finding on the untouched page is **not** under *Behoben*
  (§6.4, rendered — the unit test for the rule is p4's, this is the user seeing it).

- [ ] **Step 2: `MailRelayAcceptanceTest`** — same base, plus GreenMail. It proves §11.4's
      promise that the relay is working weeks before a report depends on it:
- `POST /einstellungen` with GreenMail's host and port, a from-address and a base URL, as ADMIN.
- `POST /einstellungen/testmail`: GreenMail receives one multipart message; the flash says
  delivered; `GET /postausgang` shows the row as `SENT`.
- reconfigure to a dead port, enqueue another mail, dispatch `maxAttempts` times: the row is
  `FAILED`, `/postausgang` shows the relay's error text, and **the health banner is now on the
  run list too** (D35) — §11.5's "the system cannot email about a broken email system" made
  visible.
- the whole time, no run failed and nothing was lost: assert the site's runs are untouched
  (§11.3 — a run must never fail because the mail relay is down).

- [ ] **Step 3: Full suite and commit.** `./mvnw test` — browser tests included. Expect ~427 + the
      new tests; record the real number in the execution findings rather than predicting it here.

---

## Plan 5 completion check

- [ ] `./mvnw test` passes with browser tests included, and `-Pfast` passes too — every test this
      plan adds is browser-free, so `-Pfast` proves all of it
- [ ] Nine task commits landed, plus whatever the reviews add
- [ ] `ModularityTest` passes and nothing depends on `web`:
      `grep -rn "webtesthelper.web" src/main/java --include='*.java' | grep -v "/web/"` returns
      nothing
- [ ] `V11` and `V12` are the only new migrations and `ddl-auto=validate` still starts the app
- [ ] `CheckDocumentationTest` is still green — no UI key uses the `check.` or `finding.` prefix
- [ ] **Both §13.7 gates exist:** `CheckDocumentationTest` (enforcement 1, since 3a) and
      `HelpTopicsTest` (enforcement 2, new here), plus `UiMessageKeyTest` and `EnumLabelsTest`
      covering the copy the spec did not name a gate for
- [ ] `grep -rn "unpkg\|cdn\." src/main/resources/templates/` returns nothing (§12)
- [ ] `webtesthelper.runner.poller-enabled=false` and
      `webtesthelper.reporting.dispatcher-enabled=false` are both in
      `src/test/resources/application-test.properties`
- [ ] A manual smoke run: `docker compose up -d` (Postgres) then `./mvnw spring-boot:run` — there
      is no application image yet, see below. Log in as the bootstrapped admin, add a real
      internal site, press *Jetzt prüfen*, watch the progress fragment advance, read the report,
      accept the baseline, configure SMTP and send a test mail
- [ ] Phase 1 is complete. The roadmap's status line, `CLAUDE.md`'s measured test count, and the
      deviation table's D29–D36 rows all need updating in the same commit

## Deliberately not in this plan

Named here so a reviewer can tell a gap from a decision:

- **Dashboard, triage actions, bulk triage, mute rules, digest content and notification policy**
  — Phase 2 (§17). Baseline acceptance is the one triage action Phase 1 ships, because §6.3 calls
  it "required, not a convenience".
- **User management, per-site recipients, IMAP settings, concurrency settings** — §12's Settings
  screen in full is Phase 2. Phase 1 configures what Phase 1 uses.
- **Guided site setup** (§13.3) — Phase 2; it needs a probe that does not exist yet.
- **SSE** (D29), **journeys and the recorder** (Phase 4), **artifact pruning after 12 runs**
  (§6.5 — no installation has 12 runs yet; it is a scheduled job, and Phase 2 owns the scheduler).
- **The `IFRAME_EMBED` canvas-paint gap, blocked-iframe URL attribution and Maps-error
  attribution** — inherited open from the plan-3 review and p4, unchanged. Each needs a
  measurement against a hand-built fixture page, which belongs in front of a plan, not inside one.
- **§14's kill switches — global pause and per-site disable.** Both mean "do not *schedule* this",
  and Phase 1 has no scheduler: every run in it is a person pressing a button, which is its own
  pause. `site.enabled` already exists as a column and stays unread until Phase 2 gives it
  something to stop. Shipping a toggle that silently does nothing is worse than not shipping one.
- **§16's application image.** `compose.yaml` runs Postgres only; the JRE-25-plus-Chromium image
  and the second compose service are not built here. §16 itself still lists two deployment inputs
  as unconfirmed (which SMTP relay, which IMAP mailbox), and the image pins the Chromium build to
  the Playwright version — that is a packaging task with its own verification, not a tail end of
  a UI plan. Phase 1's software is complete and runs with `./mvnw spring-boot:run`; the container
  is the first thing to build once the relay question is answered.
