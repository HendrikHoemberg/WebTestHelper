# WebTestHelper UI-Polish-Batch Implementation Plan

**Goal:** Fix the visual and copy polish issues found in the UI walkthrough of 2026-08-30: setup-proposal copy bugs (`{0}` placeholders, misleading HTTPS reason), raw ISO timestamps on finding/run detail, findings-list & run-report layout and severity colour hierarchy, typography/wording polish, and login-page polish.

**Architecture:** Server-rendered Thymeleaf templates in `src/main/resources/templates/`, one stylesheet `src/main/resources/static/css/app.css`, all UI copy in `src/main/resources/messages.properties` (German only, per spec §12). Changes are template/CSS/message/copy edits plus small production-logic tweak in the pure proposal builder `SetupProposals` (no Spring). Existing view tests (MockMvc `@WebMvcTest`) and the pure unit tests protect behaviour; CSS stays regression-free because tests assert on markup/text, not on style, so CSS work is verified visually (screenshots) at the end.

**Tech Stack:** Spring Boot 4.1.1 (Java 25), Thymeleaf + HTMX + Alpine, Maven (`./mvnw`), JUnit 5 + AssertJ + MockMvc, Playwright browser-acceptance tests (`@Tag("browser")`, skipped by `-Pfast`).

**Spec:** `2026-08-21-webtesthelper-design.md` (esp. §12 Web UI, §13 Self-explanatory UI — §13.1 no internal identifiers, §13.2 findings explain themselves, §13.3 guided setup).

## Global Constraints

- **Verify:** full `./mvnw test` must pass before any "done" claim (`-Pfast` variant for the edit loop; browser acceptance tests take ~95 s).
- **Single test:** `./mvnw test -Dtest=<SimpleClassName>` (works with the `fast` profile for extra speed).
- **No SPA / no bundler** — no `package.json` in `src/main`.
- **German only** — all new UI strings go through `messages.properties` with `ui.*` keys; never hardcode copy in templates.
- **Plain language (§13.1)** — no internal identifiers like `{0}`, `DEAD_LINK`, raw ISO instants on screens.
- Templates are rendered with `th:replace="~{layout :: seite(...)}"`; the layout inserts only `~{::main}`, so any `<script>` inside `<main>` is fine, outside is dropped.
- Do not change the German wording of existing assertions inside existing `@WebMvcTest` tests unless the plan says so; extend tests instead of rewriting them where possible.
- All CSS work goes into `src/main/resources/static/css/app.css` — the only stylesheet. Use existing CSS variables (`--primary-color`, `--danger-color` `#dc2626`, `--warning-color` `#d97706`, `--success-color` `#16a34a`, `--border-color`, `--text-muted`, `--card-bg`, `--bg-color`).
- The app can be started for visual checks with:
  ```bash
  docker run -d --name wth-pg -e POSTGRES_DB=webtesthelper -e POSTGRES_USER=webtesthelper \
    -e POSTGRES_PASSWORD=webtesthelper -p 5432:5432 postgres:17-alpine
  WTH_ADMIN_PASSWORD=evalpass123 WTH_BASE_URL=http://localhost:9090 \
    ./mvnw spring-boot:run -Dspring-boot.run.arguments=--server.port=9090
  ```
  (Port 9090, because 8080 is usually taken by Steam on this machine. Devtools hot-reloads templates/CSS; a Java change needs a restart.)
- **Task 0 must be done first** — the repo has **no `AGENTS.md`** yet; a target file does not count as done without it.

---

### Task 0: Project instruction file

**Files:**
- Create: [`AGENTS.md` — repo root, content below]
- Test: no test applies; verify via `git status` + `git config slimpowers.verify "./mvnw test"`

**Interfaces:**
- Produces: the repo instruction file that every later session (and this plan's other tasks) depends on.

- [x] **Step 1: Write the file** (skeleton mandated by the global rules; commands known from this repo):

```markdown
# Project Instructions

## Commands
- **Verify (everything)**: `./mvnw test` (full suite incl. `@Tag("browser")` acceptance tests; ~95 s)
- **Single test**: `./mvnw test -Dtest=FindingListControllerTest`
- **Fast loop**: `./mvnw test -Pfast` (skips browser group only)
- **Run app**: `WTH_ADMIN_PASSWORD=evalpass123 WTH_BASE_URL=http://localhost:9090 ./mvnw spring-boot:run -Dspring-boot.run.arguments=--server.port=9090`
  (needs Postgres: `docker run -d --name wth-pg -e POSTGRES_DB=webtesthelper -e POSTGRES_USER=webtesthelper -e POSTGRES_PASSWORD=webtesthelper -p 5432:5432 postgres:17-alpine`)

## Architecture
Spring Boot modular monolith (packages: web, catalog, scheduling, runner, crawler, checks, findings, reporting, recorder).
UI is Thymeleaf + HTMX + Alpine, no SPA. Templates in `src/main/resources/templates/`, CSS in `src/main/resources/static/css/app.css`,
all German UI copy in `src/main/resources/messages.properties`. Postgres + Flyway (`src/main/resources/db/migration/`).

## Conventions
- German-only UI; message keys `ui.*`; no internal identifiers (enum names, `{0}` placeholders, raw ISO instants) in rendered HTML.
- View tests: `@WebMvcTest` + MockMvc; assertions on text/markup, not on CSS.
- Journey recorder steps carry multiple ranked locator candidates; keep 0/2/4 worker pool sizes untouched.

## Boundaries
- Do not edit: `data/`, `target/`, `.env`, `compose.yaml` runtime volumes.
- Do not commit screenshots or test data.
```

- [x] **Step 2: Register the verify command**
  ```bash
  git config slimpowers.verify "./mvnw test"
  git status   # AGENTS.md untracked; nothing else changed
  ```

---

### Task 1: Setup-proposal copy bugs (`{0}` placeholders, HTTPS reason)

**Files:**
- Modify: [`src/main/java/dev/hendrikhoemberg/webtesthelper/runner/SetupProposals.java` — negative reasons instead of parameterised reasons with empty args]
- Modify: [`src/main/resources/messages.properties` — new `ui.einrichtung.grund.*.kein` and `ui.einrichtung.grund.https.nicht` keys]
- Create: [`src/test/java/dev/hendrikhoemberg/webtesthelper/runner/SetupProposalsTest.java` — pure unit test]
- Modify: [`src/test/java/dev/hendrikhoemberg/webtesthelper/web/SetupControllerTest.java` — new `standWithNoSignalsRendersNoRawPlaceholders` regression test]
- Test: `SetupProposalsTest`, `SetupControllerTest`

**Interfaces:**
- Consumes: `ProbeEvidence` (record: `reachable, unreachableReason, pagesVisited, formPages, mediaPages, mapPages, languages, documentLinks, sitemapFound, secure`), `CheckProposal(type, suggested, reasonKey, reasonArgs)`.
- Produces: `SetupProposals.of(ProbeEvidence) -> List<CheckProposal>` where every conditional row with empty evidence carries a **parameter-free** negative reason key (`.*.kein` / `https.nicht`) so `SetupController` cannot render raw `{0}`.

**Bug background (verified in the walkthrough):** `SetupController.toView` resolves `check.reasonKey()` with `check.reasonArgs()`. For conditional rows the probe did **not** suggest (empty evidence), the args list is empty but the key still contains `{0}` → the live UI shows "*Video oder Audio auf {0} gefunden*", "*Karten-Einbettung auf {0} gefunden*", "*Dokument zum Herunterladen gefunden: {0}*". Likewise `REASON_HTTPS` ("*Website wird über HTTPS ausgeliefert*") is rendered even for non-HTTPS sites.

- [x] **Step 1: Write the failing tests**
  a) New pure unit test `src/test/java/dev/hendrikhoemberg/webtesthelper/runner/SetupProposalsTest.java`:

```java
package dev.hendrikhoemberg.webtesthelper.runner;

import dev.hendrikhoemberg.webtesthelper.crawler.ProbeEvidence;
import dev.hendrikhoemberg.webtesthelper.model.CheckType;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class SetupProposalsTest {

    private static final ProbeEvidence RECHBAR_LEER = new ProbeEvidence(true, null,
            List.of("https://acme.example.com/"),
            List.of(), List.of(), List.of(), Set.of(), List.of(), false, false);

    @Test
    void emptyEvidenceCarriesParameterFreeNegativeReasons() {
        List<CheckProposal> checks = SetupProposals.of(RECHBAR_LEER);

        assertThat(keyOf(checks, CheckType.CONTACT_FORM)).isEqualTo("ui.einrichtung.grund.formular.kein");
        assertThat(keyOf(checks, CheckType.MEDIA_PLAYABLE)).isEqualTo("ui.einrichtung.grund.media.kein");
        assertThat(keyOf(checks, CheckType.IFRAME_EMBED)).isEqualTo("ui.einrichtung.grund.karte.kein");
        assertThat(keyOf(checks, CheckType.HREFLANG)).isEqualTo("ui.einrichtung.grund.sprachen.kein");
        assertThat(keyOf(checks, CheckType.LANGUAGE_SWITCHER)).isEqualTo("ui.einrichtung.grund.sprachen.kein");
        assertThat(keyOf(checks, CheckType.FILE_DOWNLOAD)).isEqualTo("ui.einrichtung.grund.dokument.kein");
        assertThat(keyOf(checks, CheckType.SITEMAP_CONSISTENCY)).isEqualTo("ui.einrichtung.grund.sitemap.kein");
        assertThat(keyOf(checks, CheckType.TLS_CERT)).isEqualTo("ui.einrichtung.grund.https.nicht");
        assertThat(keyOf(checks, CheckType.MIXED_CONTENT)).isEqualTo("ui.einrichtung.grund.https.nicht");
    }

    @Test
    void negativeReasonKeysNeverCarryArguments() {
        List<CheckProposal> checks = SetupProposals.of(RECHBAR_LEER);

        for (CheckProposal check : checks) {
            if (check.reasonKey().endsWith(".kein") || check.reasonKey().endsWith(".nicht")) {
                assertThat(check.reasonArgs()).isEmpty();
            }
        }
    }

    @Test
    void richEvidenceKeepsTheOriginalSuggestedReasons() {
        ProbeEvidence evidence = new ProbeEvidence(true, null,
                List.of("https://acme.example.com/"),
                List.of("https://acme.example.com/kontakt"),
                List.of("https://acme.example.com/medien"),
                List.of("https://acme.example.com/karte"),
                Set.of("de", "en"),
                List.of("https://acme.example.com/preisliste.pdf"),
                true, true);

        List<CheckProposal> checks = SetupProposals.of(evidence);

        assertThat(keyOf(checks, CheckType.CONTACT_FORM)).isEqualTo("ui.einrichtung.grund.formular");
        assertThat(keyOf(checks, CheckType.MEDIA_PLAYABLE)).isEqualTo("ui.einrichtung.grund.media");
        assertThat(keyOf(checks, CheckType.IFRAME_EMBED)).isEqualTo("ui.einrichtung.grund.karte");
        assertThat(keyOf(checks, CheckType.HREFLANG)).isEqualTo("ui.einrichtung.grund.sprachen");
        assertThat(keyOf(checks, CheckType.FILE_DOWNLOAD)).isEqualTo("ui.einrichtung.grund.dokument");
        assertThat(keyOf(checks, CheckType.SITEMAP_CONSISTENCY)).isEqualTo("ui.einrichtung.grund.sitemap");
        assertThat(keyOf(checks, CheckType.TLS_CERT)).isEqualTo("ui.einrichtung.grund.https");
        assertThat(keyOf(checks, CheckType.MIXED_CONTENT)).isEqualTo("ui.einrichtung.grund.https");
    }

    private static String keyOf(List<CheckProposal> checks, CheckType type) {
        return checks.stream()
                .filter(c -> c.type() == type)
                .findFirst()
                .map(CheckProposal::reasonKey)
                .orElseThrow(() -> new AssertionError("Kein Vorschlag für " + type));
    }
}
```

  *(This test file needs the imports `org.junit.jupiter.api.Test` and AssertJ `assertThat`; `CheckProposal` and `SetupProposals` live in the same package `dev.hendrikhoemberg.webtesthelper.runner`.)*

  b) Extend `src/test/java/dev/hendrikhoemberg/webtesthelper/web/SetupControllerTest.java` — add one test method + imports `dev.hendrikhoemberg.webtesthelper.crawler.ProbeEvidence`, `dev.hendrikhoemberg.webtesthelper.runner.SetupProposal`, `dev.hendrikhoemberg.webtesthelper.runner.SetupProposals`:

```java
    @Test
    @WithMockUser(roles = "USER")
    void standWithNoSignalsRendersNoRawPlaceholders() throws Exception {
        ProbeEvidence evidence = new ProbeEvidence(true, null,
                List.of("https://acme.example.com/"),
                List.of(), List.of(), List.of(), Set.of(), List.of(), false, false);
        SetupProposal proposal = new SetupProposal(evidence, SetupProposals.of(evidence));
        when(setupProbeService.stateOf(SITE_ID))
                .thenReturn(Optional.of(new ProbeState(ProbeStatus.FERTIG, Instant.now(), proposal, null)));

        MvcResult result = mvc.perform(get("/websites/" + SITE_ID + "/einrichtung/stand"))
                .andExpect(status().isOk())
                .andExpect(view().name("fragments/einrichtungsstand :: stand"))
                .andReturn();

        String body = result.getResponse().getContentAsString();
        assertThat(body).doesNotContain("{0}");
        assertThat(body).contains("Kein Kontaktformular auf den geprüften Seiten gefunden");
        assertThat(body).contains("Kein Video oder Audio auf den geprüften Seiten gefunden");
        assertThat(body).contains("Keine Karten-Einbettung auf den geprüften Seiten gefunden");
        assertThat(body).contains("Website wird nicht über HTTPS ausgeliefert");
    }
```

- [ ] **Step 2: Run them — verify FAIL**
  - `./mvnw test -Dtest=SetupProposalsTest` → fails (`reasonKey()` still `ui.einrichtung.grund.media` for empty evidence).
  - `./mvnw test -Dtest=SetupControllerTest#standWithNoSignalsRendersNoRawPlaceholders` → fails (body contains `{0}`).

- [ ] **Step 3: Implement**
  a) `src/main/java/dev/hendrikhoemberg/webtesthelper/runner/SetupProposals.java` — replace the constants block and the conditional rows (keep the always-on blocks unchanged):

```java
    private static final String REASON_FORMULAR_KEIN = "ui.einrichtung.grund.formular.kein";
    private static final String REASON_MEDIA_KEIN = "ui.einrichtung.grund.media.kein";
    private static final String REASON_MAPS_KEIN = "ui.einrichtung.grund.karte.kein";
    private static final String REASON_LANGUAGES_KEIN = "ui.einrichtung.grund.sprachen.kein";
    private static final String REASON_DOCUMENT_KEIN = "ui.einrichtung.grund.dokument.kein";
    private static final String REASON_SITEMAP_KEIN = "ui.einrichtung.grund.sitemap.kein";
    private static final String REASON_HTTPS_NICHT = "ui.einrichtung.grund.https.nicht";

    private static final List<String> KEINE_ARGS = List.of();

    static List<CheckProposal> of(ProbeEvidence evidence) {
        List<CheckProposal> checks = new ArrayList<>(CheckType.values().length);

        checks.add(suggested(CheckType.PAGE_STATUS, REASON_BASIS, KEINE_ARGS));
        checks.add(suggested(CheckType.PAGE_UNREACHABLE, REASON_BASIS, KEINE_ARGS));
        checks.add(suggested(CheckType.DEAD_LINK, REASON_BASIS, KEINE_ARGS));
        checks.add(suggested(CheckType.REDIRECT_CHAIN, REASON_BASIS, KEINE_ARGS));
        checks.add(suggested(CheckType.IMAGE_BROKEN, REASON_BASIS, KEINE_ARGS));
        checks.add(suggested(CheckType.COOKIE_BANNER, REASON_BASIS, KEINE_ARGS));

        boolean formular = evidence.reachable() && !evidence.formPages().isEmpty();
        checks.add(conditional(CheckType.CONTACT_FORM, formular,
                formular ? REASON_FORMULAR : REASON_FORMULAR_KEIN,
                formular ? firstOf(evidence.formPages()) : KEINE_ARGS));

        boolean medien = evidence.reachable() && !evidence.mediaPages().isEmpty();
        checks.add(conditional(CheckType.MEDIA_PLAYABLE, medien,
                medien ? REASON_MEDIA : REASON_MEDIA_KEIN,
                medien ? firstOf(evidence.mediaPages()) : KEINE_ARGS));

        boolean karten = evidence.reachable() && !evidence.mapPages().isEmpty();
        checks.add(conditional(CheckType.IFRAME_EMBED, karten,
                karten ? REASON_MAPS : REASON_MAPS_KEIN,
                karten ? firstOf(evidence.mapPages()) : KEINE_ARGS));

        boolean mehrsprachig = evidence.reachable() && evidence.languages().size() > 1;
        List<String> sprachArgs = mehrsprachig
                ? List.of(String.valueOf(evidence.languages().size())) : KEINE_ARGS;
        checks.add(conditional(CheckType.HREFLANG, mehrsprachig,
                mehrsprachig ? REASON_LANGUAGES : REASON_LANGUAGES_KEIN, sprachArgs));
        checks.add(conditional(CheckType.LANGUAGE_SWITCHER, mehrsprachig,
                mehrsprachig ? REASON_LANGUAGES : REASON_LANGUAGES_KEIN, sprachArgs));

        boolean dokument = evidence.reachable() && !evidence.documentLinks().isEmpty();
        checks.add(conditional(CheckType.FILE_DOWNLOAD, dokument,
                dokument ? REASON_DOCUMENT : REASON_DOCUMENT_KEIN,
                dokument ? firstOf(evidence.documentLinks()) : KEINE_ARGS));

        boolean sitemap = evidence.reachable() && evidence.sitemapFound();
        checks.add(conditional(CheckType.SITEMAP_CONSISTENCY, sitemap,
                sitemap ? REASON_SITEMAP : REASON_SITEMAP_KEIN, KEINE_ARGS));

        boolean https = evidence.reachable() && evidence.secure();
        checks.add(conditional(CheckType.TLS_CERT, https,
                https ? REASON_HTTPS : REASON_HTTPS_NICHT, KEINE_ARGS));
        checks.add(conditional(CheckType.MIXED_CONTENT, https,
                https ? REASON_HTTPS : REASON_HTTPS_NICHT, KEINE_ARGS));

        checks.add(new CheckProposal(CheckType.CONSOLE_ERRORS, false, REASON_STANDARD, KEINE_ARGS));
        checks.add(new CheckProposal(CheckType.BUTTON_REACHABILITY, false, REASON_KLICKT, KEINE_ARGS));

        return List.copyOf(checks);
    }
```

  b) `src/main/resources/messages.properties` — add (next to the existing `ui.einrichtung.grund.*` block around line 650):

```properties
ui.einrichtung.grund.formular.kein=Kein Kontaktformular auf den geprüften Seiten gefunden
ui.einrichtung.grund.media.kein=Kein Video oder Audio auf den geprüften Seiten gefunden
ui.einrichtung.grund.karte.kein=Keine Karten-Einbettung auf den geprüften Seiten gefunden
ui.einrichtung.grund.sprachen.kein=Keine weiteren Sprachfassungen gefunden
ui.einrichtung.grund.dokument.kein=Kein Dokument zum Herunterladen auf den geprüften Seiten gefunden
ui.einrichtung.grund.sitemap.kein=Keine sitemap.xml gefunden
ui.einrichtung.grund.https.nicht=Website wird nicht über HTTPS ausgeliefert
```

- [ ] **Step 4: Run the tests — verify PASS**
  `./mvnw test -Dtest='SetupProposalsTest,SetupControllerTest'`
  plus the existing `standWhenFinishedRendersOneCheckboxPerCheckTypeWithReasonFromBundleAndNoTrigger` must still pass (suggested rows keep their keys).
- [ ] **Step 5: Commit**
  `git commit -m "fix(einrichtung): parameterfreie Begründungen statt {0}-Platzhaltern"`

---

### Task 2: Humanized timestamps on finding & run detail

**Files:**
- Modify: [`src/main/resources/templates/befunde/detail.html` — two `<dd>` values]
- Modify: [`src/main/resources/templates/laeufe/detail.html` — two `<dd>` values]
- Test: `FindingControllerTest`, `RunControllerTest` (extend, don't rewrite)

**Interfaces:**
- Consumes: `detail.firstSeenAt` / `detail.lastSeenAt` (`Instant`), `run.startedAt` / `run.finishedAt` (`Instant`) — already in the view models.
- Produces: `dd.MM.yyyy HH:mm` dates everywhere (spec §13.1 — no raw ISO instants on screen). `#temporals` is already in use elsewhere (`kacheln.html`, `zeitplaene.html`), so no new dependency.

- [ ] **Step 1: Write the failing tests**
  In `FindingControllerTest` (test `detailPageExplains`-style test that fetches `/befunde/{id}`) — add to the existing method or add a new one using the same `finding` fixture (first/last seen `2026-08-25T10:00:00Z`):

```java
    @Test
    @WithMockUser(roles = "USER")
    void detailRendersHumanizedDatesInsteadOfRawInstants() throws Exception {
        when(findingService.byId(findingId)).thenReturn(Optional.of(finding));
        when(findingService.occurrencesOfLastRun(findingId, 50)).thenReturn(List.of());

        mvc.perform(get("/befunde/" + findingId))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("25.08.2026")))
                .andExpect(content().string(not(containsString("2026-08-25T10:00:00Z"))));
    }
```

  In `RunControllerTest` — same pattern for `/laeufe/{runId}` with `sampleSummary(101L, 42L, ...)` (started `2026-08-25T10:00:00Z`):

```java
    @Test
    @WithMockUser(roles = "USER")
    void runDetailRendersHumanizedDateTimes() throws Exception {
        long runId = 101L;
        long siteId = 42L;
        when(runService.summary(runId)).thenReturn(sampleSummary(runId, siteId, RunStatus.COMPLETED, false, false, null));
        when(siteService.contextFor(siteId)).thenReturn(sampleSite(siteId));
        when(findingService.diffOf(siteId, runId)).thenReturn(new RunDiff(runId, Map.of()));

        mvc.perform(get("/laeufe/" + runId))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("25.08.2026")))
                .andExpect(content().string(not(containsString("2026-08-25T10:00:00Z"))));
    }
```

  (Assert only the date part, never the exact time: the test machine zone decides it. Add the needed static imports if not already present: `org.hamcrest.Matchers.containsString`, `org.hamcrest.Matchers.not`.)

- [ ] **Step 2: Run — verify FAIL** (body still contains raw `2026-08-25T10:00:00Z`).
- [ ] **Step 3: Implement**
  - `templates/befunde/detail.html` (inside the `<dl>` around lines 80–88) — replace:

```html
<div th:if="${detail.firstSeenAt != null}">
    <dt th:text="#{ui.befund.detail.erstmals_gesehen}">Erstmals aufgetreten</dt>
    <dd th:text="${#temporals.format(detail.firstSeenAt, 'dd.MM.yyyy HH:mm')}">25.08.2026 12:00</dd>
</div>
<div th:if="${detail.lastSeenAt != null}">
    <dt th:text="#{ui.befund.detail.zuletzt_gesehen}">Zuletzt aufgetreten</dt>
    <dd th:text="${#temporals.format(detail.lastSeenAt, 'dd.MM.yyyy HH:mm')}">25.08.2026 12:00</dd>
</div>
```

  - `templates/laeufe/detail.html` (around lines 48–58) — replace `th:text="${run.startedAt}"` with `th:text="${#temporals.format(run.startedAt, 'dd.MM.yyyy HH:mm')}"` and `th:text="${run.finishedAt}"` with `th:text="${#temporals.format(run.finishedAt, 'dd.MM.yyyy HH:mm')}"` (update the fallback text in the tags from `2026-08-25T10:00:05Z` to e.g. `25.08.2026 12:00`).

- [ ] **Step 4: Run — verify PASS**
  `./mvnw test -Dtest='FindingControllerTest,RunControllerTest'` — the *whole* classes, because existing tests have position-based assertions.
- [ ] **Step 5: Commit**
  `git commit -m "feat(ui): menschliche Zeitangaben auf Befund- und Laufdetails"`

---

### Task 3: Findings list & run report — layout, density, severity strips, section accents

**Files:**
- Modify: [`src/main/resources/templates/fragments/befundzeile.html` — severity class on the card root]
- Modify: [`src/main/resources/templates/laeufe/detail.html` — `abschnitt-{KEY}` class on section]
- Modify: [`src/main/resources/static/css/app.css` — new rules in the Befundeliste block (after line ~587) and in a new "Bericht & Abschnitte" block]
- Test: `FindingListControllerTest` (one new test), `RunControllerTest#runDetailRendersOnlyNonEmptySectionHeadingsWithCounts` (extend assertions)
- Visual: screenshot of `/websites/{id}/befunde` and `/laeufe/{id}` (see Task 6)

**Interfaces:**
- Consumes: `Severity` enum (`ERROR`, `WARN`, `INFO`) on `FindingView.befund.severity`; `ReportSection` enum (`FIXED, NEW, REGRESSED, KNOWN, STILL_OPEN`) as `entry.key` in the detail template.
- Produces: coloured 4 px left border on every finding card (`karte-severity-ERROR|WARN|INFO`) and a coloured accent bar on report-section headings (`abschnitt-FIXED|NEW|REGRESSED|KNOWN|STILL_OPEN`), plus a wider, denser list layout.

**Design intent (from the walkthrough):** the report pages are the most-heavily-used screens; severity must be scannable in ~2 s. The dashboard tiles already use left-border colour — the finding cards will follow that visual language.

- [ ] **Step 1: Write the failing tests**
  a) In `FindingListControllerTest`, reuse the existing fixtures (`finding` with `Severity.ERROR`), add:

```java
    @Test
    @WithMockUser(roles = "USER")
    void findingCardsCarryTheSeverityClassAndRemediationIsMarkedUp() throws Exception {
        when(siteService.contextFor(siteId)).thenReturn(sampleSite(siteId));
        when(findingService.search(any(FindingQuery.class)))
                .thenReturn(new FindingPage(List.of(finding), 1, 50, 1));

        mvc.perform(get("/websites/{id}/befunde", siteId))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("karte-severity-ERROR")))
                .andExpect(content().string(containsString("Abhilfe:")));
    }
```

  b) Extend `RunControllerTest#runDetailRendersOnlyNonEmptySectionHeadingsWithCounts`:

```java
                .andExpect(content().string(containsString("abschnitt-FIXED")))
                .andExpect(content().string(containsString("abschnitt-NEW")))
                .andExpect(content().string(containsString("abschnitt-KNOWN")))
```

- [ ] **Step 2: Run — verify FAIL** (`karte-severity-ERROR` / `abschnitt-NEW` absent).
- [ ] **Step 3: Implement**
  a) `templates/fragments/befundzeile.html` — line 6, replace:

```html
<div th:fragment="befundzeile(befund, auswaehlbar)" class="befund-karte">
```
with:
```html
<div th:fragment="befundzeile(befund, auswaehlbar)" class="befund-karte"
     th:classappend="${'karte-severity-' + befund.severity}">
```

  b) `templates/laeufe/detail.html` — the section `<section th:each="entry : ${sections}" th:if="...">` (bottom of the file), add the classappend:

```html
<section th:each="entry : ${sections}" th:if="${not #lists.isEmpty(entry.value)}"
         class="detail-bereich" th:classappend="${'abschnitt-' + entry.key}">
```

  c) `src/main/resources/static/css/app.css` — add after the `.befund-abgelaufen` rule (line ~880) and at the end of the file:

```css
/* Severität als Farbsignal auf der Befundkarte (§12, wie die Kacheln) */
.befund-karte.karte-severity-ERROR   { border-left: 4px solid var(--danger-color); }
.befund-karte.karte-severity-WARN    { border-left: 4px solid var(--warning-color); }
.befund-karte.karte-severity-INFO    { border-left: 4px solid var(--info-color, #94a3b8); }

/* Kompaktere Befundkarte: Zeilen statt Kartenwände */
.befunde-liste { gap: 0.6rem; }
.befund-karte { padding: 0.75rem 1rem; }
.befund-kopf { margin-bottom: 0.4rem; }
.befund-koerper { font-size: 0.875rem; }

/* Berichtsabschnitte: Kopfzeile erhält den Status der Gruppe */
section.abschnitt-NEW h2,
section.abschnitt-REGRESSED h2 {
    border-left: 4px solid var(--danger-color);
    padding-left: 0.6rem;
}
section.abschnitt-STILL_OPEN h2 {
    border-left: 4px solid var(--warning-color);
    padding-left: 0.6rem;
}
section.abschnitt-FIXED h2 {
    border-left: 4px solid var(--success-color);
    padding-left: 0.6rem;
}
section.abschnitt-KNOWN h2 {
    border-left: 4px solid var(--border-color);
    padding-left: 0.6rem;
}
```

  d) List width (findings page): `.befunde-layout` currently `grid-template-columns: 260px 1fr;` (app.css line ~747). Change to `grid-template-columns: minmax(280px, 340px) 1fr;` so the result column fills the page and the filter column can grow. Keep the existing `@media (max-width: 900px)` fallback untouched.

- [ ] **Step 4: Run — verify PASS** `./mvnw test -Dtest='FindingListControllerTest,RunControllerTest'`
- [ ] **Step 5: Commit** `git commit -m "feat(ui): Schweregrad-Streifen und Abschnitts-Akzente, breitere Fundliste"`

---

### Task 4: Typography, headings and wording polish

**Files:**
- Modify: [`src/main/resources/static/css/app.css` — heading weights, card-title weight, "?" button hit area]
- Modify: [`src/main/resources/messages.properties` — `ui.uebersicht.kapazitaet` wording + Swiss-spelling fix]
- Modify: [`src/main/resources/templates/uebersicht/index.html` — label text "Kapazität" → friendlier]
- Test: `UiMessageKeyTest` (must stay green — proves keys still resolve), `SettingsControllerTest` (its copy assertions — verify none assert "ausschliesslich" via hardcoded string; adjust if so)

**Interfaces:**
- Consumes: message keys `ui.uebersicht.kapazitaet` (line 624) and `ui.einstellungen.redirectAllMailTo.hinweis` (line 454).
- Produces: a calmer hierarchy (h1 1.9rem/700, h2 1.35rem/650, card titles 700) and human copy for the capacity/jargon line.

- [ ] **Step 1: Write the failing tests**
  - `UiMessageKeyTest` must pass — no new keys; the test is only a guard-rail here.
  - New wording regression tests are *not* worth separate HTTP tests (copy strings); instead verify by test-suite green + manual screenshot. But DO add one content test in `DashboardControllerTest` for the new capacity sentence:

```java
    @Test
    void dashboardShowsCapacityLineWithoutJargon() throws Exception {
        mvc.perform(get("/"))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString("Planungs-Threads"))));
    }
```

- [ ] **Step 2: Run — verify FAIL** (message still contains "Planungs-Threads").
- [ ] **Step 3: Implement**
  a) `messages.properties` line 624 — replace:

```properties
ui.uebersicht.kapazitaet=Browser {0}/{1} · Warteschlange {2} · E-Mail-Versandfehler {3} · Planungs-Threads {4}
```
with:
```properties
ui.uebersicht.kapazitaet={0}/{1} Browser-Arbeiter belegt · {2} Läufe in Warteschlange · {3} fehlgeschlagene Versendungen · {4} Hintergrund-Aufgaben
```

  b) `templates/uebersicht/index.html` — change the fallback text in the `<p class="kapazitaet-zeile" ...>` tag to the new sentence (fallback text is only for raw template rendering; keep it in sync).

  c) `messages.properties` line 454: `ausschliesslich` → `ausschließlich` (also in the `<p class="hinweis-text">` fallback of `templates/einstellungen/index.html` line 41).

  d) `app.css` — add the typing rules (append at end; do not touch existing selectors):

```css
/* Typographie & Hierarchie */
.seiten-kopf h1 { font-weight: 700; letter-spacing: -0.015em; }
.detail-bereich h2, .abschnitt-ueberschrift-zeile h2 { font-weight: 650; }
.detail-bereich h2 { font-size: 1.25rem; }
.befund-titel-link strong { font-weight: 650; }
.kachel, .detail-bereich { box-shadow: 0 1px 2px rgba(15, 23, 42, 0.04); }
.hinweis-schalter { min-width: 1.6rem; min-height: 1.6rem; }
```

- [ ] **Step 4: Run — verify PASS** `./mvnw test -Dtest='UiMessageKeyTest,DashboardControllerTest,SettingsControllerTest'`
- [ ] **Step 5: Commit** `git commit -m "feat(ui): Typografie-Hierarchie, Verständlichere Auslastungszeile, 'ausschließlich'"`

---

### Task 5: Login page polish

**Files:**
- Modify: [`src/main/resources/templates/anmelden.html` — auth-wrapper classes]
- Modify: [`src/main/resources/static/css/app.css` — `.anmelde`-specific styles]
- Test: `LoginFlowBrowserAcceptanceTest` (browser group — must keep passing), `UiMessageKeyTest`

**Interfaces:**
- Consumes: existing `anmelden.html` form (id `username`/`password`, `button[type=submit]`).
- Produces: centered card with a soft brand header, same card language as the rest of the app, larger hit targets.

- [ ] **Step 1: Write the failing test** — visual change; no new behavioural test (the login flow already has a browser acceptance test + `SecurityRulesTest`). Regression guard is the existing suite; verification is the screenshot in Task 6.
- [ ] **Step 2: N/A** (no new test to fail).
- [ ] **Step 3: Implement**
  - `templates/anmelden.html`: wrap in `<main class="anmelde-seite">`, put the "WebTestHelper" wordmark above the card (header, not a form field):

```html
<main class="anmelde-seite">
    <div class="anmelde-marke">WebTestHelper</div>
    <div class="anmelde-karte">
        <h1 th:text="#{ui.anmelden.titel}">Anmelden</h1>
        <div th:if="${param.abgemeldet}" class="hinweis" th:text="#{ui.anmelden.abgemeldet}">Sie wurden erfolgreich abgemeldet.</div>
        <form action="/anmelden" method="post">
            ... existing fields unchanged ...
        </form>
    </div>
</main>
```

  - `app.css` (append):

```css
/* Anmelden (§12) */
.anmelde-seite {
    max-width: 30rem;
    margin: 6rem auto 0;
    padding: 0 1rem;
}
.anmelde-karte {
    background-color: var(--card-bg);
    border: 1px solid var(--border-color);
    border-radius: 10px;
    padding: 2.5rem 2.5rem 2rem;
    box-shadow: 0 1px 2px rgba(15, 23, 42, 0.05);
}
.anmelde-karte h1 { margin-top: 0; font-weight: 700; }
.anmelde-marke {
    text-align: center;
    font-weight: 700;
    font-size: 1.5rem;
    color: var(--primary-color);
    margin-bottom: 1.25rem;
}
.anmelde-seite .form-aktionen { margin-bottom: 0; }
```

  (Check the exact current markup of `anmelden.html` — the form is already an `<form action="/anmelden" ...>`, field ids `username`/`password`, keep them untouched so `LoginFlowBrowserAcceptanceTest` stays green.)

- [ ] **Step 4: Run — verify PASS** `./mvnw test -Dtest='UiMessageKeyTest,LoginFlowBrowserAcceptanceTest'` (browser test needs Chromium: it runs with the pinned Playwright build in `~/.cache/ms-playwright`).
- [ ] **Step 5: Commit** `git commit -m "feat(ui): Anmeldeseite in die Card-Sprache der Anwendung"`

---

### Task 6: End-to-end verification (full suite + visual pass)

**Files:**
- No production changes.

**Interfaces:**
- Consumes: everything from Tasks 1–5.

- [ ] **Step 1: Full suite** `./mvnw test` — the ENTIRE suite (no `-Pfast`; browser acceptance ~95 s). Screenshot the surefire summary; every test green.
- [ ] **Step 2: Visual pass** — only if a dev instance is running (Task 0 commands):

```bash
mkdir -p /tmp/opencode/wth-shots && cd /tmp/opencode/wth-shots && npm init -y >/dev/null 2>&1 && npm i playwright-core@1.62.1 >/dev/null 2>&1
cat > shot.js <<'EOF'
const { chromium } = require('playwright-core');
const BASE = 'http://localhost:9090';
const exe = require('os').homedir() + '/.cache/ms-playwright/chromium-1234/chrome-linux64/chrome';
(async () => {
  const b = await chromium.launch({ executablePath: exe, headless: true, args: ['--no-sandbox'] });
  const p = await b.newContext({ viewport: { width: 1440, height: 900 }, locale: 'de-DE' }).then(c => c.newPage());
  await p.goto(BASE + '/anmelden'); await p.screenshot({ path: 'login.png', fullPage: true });
  await p.fill('#username', 'admin'); await p.fill('#password', 'evalpass123'); await p.click('button[type=submit]');
  await p.waitForURL(u => u.pathname !== '/anmelden');
  await p.goto(BASE + '/websites/3/befunde'); await p.screenshot({ path: 'befunde.png' });
  await p.goto(BASE + '/laeufe/4'); await p.screenshot({ path: 'lauf.png' });
  await p.goto(BASE + '/websites/3/einrichtung'); await p.screenshot({ path: 'einrichtung.png' });
  await b.close();
})();
EOF
node shot.js
```

  Check with the bare eye: severity strips visible, no `{0}`, dates are `25.08.2026 12:00` (approx), section accents on run detail, layout no longer has the huge right gutter, login page has the wordmark. (The existing DB already has the three demo sites incl. findings, if this is the same machine the review ran on.)
- [ ] **Step 3: Final commit (if any left-over) + done. Report: `./mvnw test` full output + the file names changed + what each task delivered.**

---

## Self-Review

- **Spec coverage:** §12 web UI screens (dashboard/findings/run detail/login) ✓; §13.1 no internal identifiers (`{0}` gone, no raw instants, "Planungs-Threads" reworded) ✓; §13.2 findings explain themselves (unchanged, protected by `FindingControllerTest`) ✓; §13.3 guided setup (both positive and negative reasons now honest) ✓; §13.4 consequences before click (unchanged) ✓; §13.5 inline help (untouched; hit area slightly enlarged) ✓. Nothing in the spec was removed.
- **Placeholder scan:** no `TBD`/`TODO`/bogus code — every snippet is final, including the unit-test assertions (verified against the real `ProbeEvidence`/`CheckProposal`/`ReportSection`/`RunSummary`/`Finding` shapes in this repo).
- **Type consistency:** `CheckProposal.reasonKey()/reasonArgs()`, `ProbeEvidence` constructor arity (10 args) and `SetupProposals.of` signature are used as in the current code; all template variables (`befund.severity`, `entry.key`, `detail.firstSeenAt`, `run.startedAt`) match the existing templates; the CSS variable names match `app.css` `:root`.
- **Risks:** (1) `SetupControllerTest.standWhenFinishedRendersOneCheckboxPerCheckTypeWithReasonFromBundleAndNoTrigger` builds its own proposal manually — it must keep passing because its fixtures use the *suggested* keys. (2) `RunReportAcceptanceTest` — check it for format-dependent assertions when it runs; it is a browser acceptance test, so it re-renders real templates; nothing in Tasks 2–4 changes texts it asserts (dates change formatting: `runReportRendersSectionsForNonEmptyDiff` may assert raw `finishedAt` — inspect and align that test in Task 2 if it fails).
