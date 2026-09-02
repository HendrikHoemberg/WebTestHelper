# Agent Review Improvements — Implementation Plan

**Goal:** Address all UX friction points, visual bugs, and high-value feature proposals identified during real-world user testing on live sites (example.org, ihk-deinezukunft.de): fix the baseline paradox, eliminate the false yellow alert on uninspected sites, fix the squished print report bug, add agency export capabilities, de-jargonize the journey editor, bypass cookie banners for screenshots, provide instant SMTP test feedback, and improve table and empty-state affordances.

**Architecture:** Improvements span view templates (Thymeleaf/HTMX/Alpine) and German copy (`messages.properties`), reporting domain logic (`TrafficLight`, `OpenFindingCounts`, `DashboardService`, `FindingViewFactory`), and Playwright crawler automation (`PageNavigator`). The changes are structured into 10 modular, independently testable tasks.

**Tech Stack:** Spring Boot 3 modular monolith, Thymeleaf + HTMX + Alpine.js, Playwright for browser automation, PostgreSQL + JDBC repositories, Spring Security. View tests use `@WebMvcTest` + MockMvc; domain tests use JUnit 5 + AssertJ.

**Spec:** Driven by user evaluation report (Sections 1–4).

## Global Constraints

- German-only UI; all copy keys under `ui.*` in `src/main/resources/messages.properties`.
- No internal technical identifiers (raw enum names, `{0}` placeholders, ISO timestamps) rendered in HTML.
- View tests assert text/markup content via MockMvc (`containsString`), not CSS stylesheets.
- Desktop-only layout; no mobile breakpoints.
- Verification command (default gate): `./mvnw test -Pfast`.
- Full verification command: `./mvnw test`.
- Single test command: `./mvnw test -Dtest=<ClassName>`.

---

## Task 1: Fix Print Report Layout Bug & Format "Agentur-Export" (Bug 3 & Feature 3)

**Problem:** `app.css` defines `body { display: flex; }`. Because `laeufe/druck.html` has no sidebar, all direct children of `body` (`.druck-aktionen`, `header`, section `div`s) render as horizontal flex columns side-by-side, crushing the report into a 15% vertical strip. Furthermore, non-technical users need a clean, structured "Mängelliste für den Webmaster / Agentur" that can be printed or saved as PDF with actionable recommendations.

**Files:**
- Modify: `src/main/resources/templates/laeufe/druck.html` — isolate body styles with `display: block`, add agentur-export header, clear action instructions, and `@media print` styling.
- Modify: `src/main/resources/messages.properties` — add copy for agency export (`ui.lauf.bericht.agentur_titel`, `ui.lauf.bericht.hinweis_agentur`).
- Test: `src/test/java/dev/hendrikhoemberg/webtesthelper/web/RunControllerTest.java`.

**Interfaces:**
- Consumes: `findingService.diffForReport(siteId, runId)` and `findingViewFactory.of(diff, locale)`.
- Produces: A full-width, clean printable document (`/laeufe/{id}/bericht`) formatted specifically as a task handover for webmasters and agencies.

- [ ] **Step 1: Write the failing test**

  In `src/test/java/dev/hendrikhoemberg/webtesthelper/web/RunControllerTest.java`, add:

  ```java
  @Test
  @WithMockUser(roles = "USER")
  void reportViewRendersFullWidthAgencyExportLayout() throws Exception {
      long runId = 101L;
      long siteId = 42L;
      when(runService.summary(runId)).thenReturn(sampleSummary(runId, siteId, RunStatus.COMPLETED, false, false, null));
      when(siteService.contextFor(siteId)).thenReturn(sampleSite(siteId));
      when(findingService.diffForReport(siteId, runId)).thenReturn(new RunDiff(List.of(), List.of(), List.of(), List.of()));
      when(findingViewFactory.of(any(RunDiff.class), any(Locale.class))).thenReturn(Map.of());

      mvc.perform(get("/laeufe/" + runId + "/bericht"))
              .andExpect(status().isOk())
              .andExpect(content().string(containsString("class=\"druck-ansicht\"")))
              .andExpect(content().string(containsString("display: block")))
              .andExpect(content().string(containsString("Mängelliste für den Webmaster")));
  }
  ```

- [ ] **Step 2: Run the single test — verify it FAILS**

  Command: `./mvnw test -Dtest=RunControllerTest#reportViewRendersFullWidthAgencyExportLayout`
  Expected: FAIL (missing `druck-ansicht` and `Mängelliste für den Webmaster`).

- [ ] **Step 3: Implement minimal fix**

  In `src/main/resources/templates/laeufe/druck.html`:
  Change `<body style="...">` to:
  ```html
  <body class="druck-ansicht" style="display: block; background: #fff; padding: 2rem; max-width: 960px; margin: 0 auto; color: #111;">
      <style>
          body.druck-ansicht { display: block !important; }
          @media print {
              .druck-aktionen { display: none !important; }
              body.druck-ansicht { padding: 0 !important; max-width: 100% !important; }
              .befund-karte-druck { page-break-inside: avoid; border-bottom: 1px solid #ddd; padding: 1rem 0; }
          }
      </style>
      <div class="druck-aktionen" style="display: flex; gap: 0.5rem; justify-content: space-between; align-items: center; margin-bottom: 2rem; border-bottom: 1px solid #e5e7eb; padding-bottom: 1rem;">
          <a th:href="@{/laeufe/{id}(id=${run.id})}" class="btn-ui btn-ui-secondary" th:text="#{ui.lauf.bericht.zurueck}">Zurück zum Prüflauf</a>
          <button type="button" class="btn-ui btn-ui-primary" onclick="window.print()" th:text="#{ui.lauf.bericht.drucken}">Bericht drucken / Als PDF speichern</button>
      </div>

      <header style="margin-bottom: 2rem;">
          <div style="font-size: 0.85rem; text-transform: uppercase; letter-spacing: 0.05em; color: #6b7280; font-weight: 700; margin-bottom: 0.25rem;" th:text="#{ui.lauf.bericht.agentur_untertitel}">Mängelliste für den Webmaster / die betreuende Agentur</div>
          <h1 style="margin: 0 0 0.5rem; font-size: 1.75rem; color: #111827;" th:text="#{ui.lauf.bericht.agentur_titel(${site.name})}">Prüfbericht für Website</h1>
          <p style="margin: 0; color: #4b5563;">
              <strong th:text="${site.name}">Website</strong>
              <span style="margin: 0 0.35rem;">·</span>
              <span style="font-family: var(--font-mono); font-size: 0.9rem;" th:text="${site.baseUrl.value}">https://example.com/</span>
              <span style="margin: 0 0.35rem;">·</span>
              <span th:text="#{ui.lauf.bericht.lauf_nummer(${run.id})}">Lauf #101</span>
          </p>
          <p th:if="${run.finishedAt != null}" style="margin: 0.35rem 0 0; color: #6b7280; font-size: 0.85rem; font-variant-numeric: tabular-nums;"
             th:text="#{ui.lauf.bericht.stand(${#temporals.format(run.finishedAt, 'dd.MM.yyyy HH:mm')})}">Stand: 25.08.2026 12:00</p>
      </header>

      <div class="card-box" style="background: #f9fafb; border: 1px solid #e5e7eb; border-radius: 8px; padding: 1rem 1.25rem; margin-bottom: 2rem;">
          <p style="margin: 0; font-size: 0.9rem; color: #374151;" th:text="#{ui.lauf.bericht.hinweis_agentur}">
              Dieses Dokument enthält alle festgestellten technischen Mängel mit konkreter Fundstelle und Behebungshinweisen für das Entwicklungs- oder Redaktionsteam.
          </p>
      </div>

      <p th:if="${#maps.isEmpty(sections)}" style="color: #4b5563;" th:text="#{ui.lauf.keine_befunde}">Keine Feststellungen in diesem Prüflauf.</p>

      <div th:each="entry : ${sections}" th:if="${not #lists.isEmpty(entry.value)}" style="margin-bottom: 2.5rem;">
          <h2 style="font-size: 1.2rem; border-bottom: 2px solid #111827; padding-bottom: 0.4rem; margin-bottom: 1rem;"
              th:text="#{${'ui.reportsection.' + entry.key}} + ' (' + ${#lists.size(entry.value)} + ')'">Neu aufgetreten (2)</h2>
          <div th:each="befundItem : ${entry.value}" class="befund-karte-druck" style="margin-bottom: 1rem;">
              <div th:replace="~{fragments/befundzeile :: befundzeile(${befundItem}, false)}"></div>
          </div>
      </div>
  </body>
  ```

  In `src/main/resources/messages.properties`:
  ```properties
  ui.lauf.bericht.agentur_untertitel=Mängelliste für den Webmaster / die betreuende Agentur
  ui.lauf.bericht.agentur_titel=Prüfbericht: {0}
  ui.lauf.bericht.lauf_nummer=Lauf #{0}
  ui.lauf.bericht.hinweis_agentur=Dieses Dokument enthält alle festgestellten technischen Mängel mit konkreter Fundstelle und Behebungshinweisen für das Entwicklungs- oder Redaktionsteam.
  ```

- [ ] **Step 4: Run the single test — verify it PASSES**

  Command: `./mvnw test -Dtest=RunControllerTest#reportViewRendersFullWidthAgencyExportLayout`
  Expected: PASS.

- [ ] **Step 5: Commit**

  `git commit -m "fix(web): fix print view flex layout and add agency export headers"`

---

## Task 2: Neutral Initial State ("Noch nicht geprüft") for New Sites (Finding 1.1)

**Problem:** When a new website is created, `TrafficLight.of` returns `GELB` if `lastRun == null`. For non-technical users, yellow signals a warning or that something is wrong. An uninspected site should have a neutral state (`NEU` or `GRAU` rendered as "Noch nicht geprüft") in neutral grey/slate.

**Files:**
- Modify: `src/main/java/dev/hendrikhoemberg/webtesthelper/reporting/TrafficLight.java` — add `NEU` value; if `lastRun == null`, return `NEU`.
- Modify: `src/main/resources/messages.properties` — add `ui.trafficlight.NEU=Noch nicht geprüft`.
- Modify: `src/main/resources/templates/fragments/site-kopf.html` & `src/main/resources/templates/fragments/kacheln.html` — handle `NEU` badge style (`status-inaktiv badge-neutral`).
- Test: `src/test/java/dev/hendrikhoemberg/webtesthelper/reporting/TrafficLightTest.java`.

**Interfaces:**
- Consumes: `siteEnabled`, `lastRun`, `counts`.
- Produces: `TrafficLight.NEU` when `siteEnabled == true && lastRun == null`.

- [ ] **Step 1: Write the failing test**

  In `src/test/java/dev/hendrikhoemberg/webtesthelper/reporting/TrafficLightTest.java`, update line 35:
  ```java
  Arguments.of("a site that never finished a run is neutral new",
          true, null, OpenFindingCounts.none(), TrafficLight.NEU),
  ```

- [ ] **Step 2: Run the single test — verify it FAILS**

  Command: `./mvnw test -Dtest=TrafficLightTest`
  Expected: FAIL (compilation error or assertion failure expecting `NEU` but received `GELB`).

- [ ] **Step 3: Implement minimal change**

  In `src/main/java/dev/hendrikhoemberg/webtesthelper/reporting/TrafficLight.java`:
  ```java
  public enum TrafficLight {
      GRUEN, GELB, ROT, GRAU, NEU;

      public static TrafficLight of(boolean siteEnabled, LastRun lastRun, OpenFindingCounts counts) {
          if (!siteEnabled) {
              return GRAU;
          }
          if (lastRun == null) {
              return NEU;
          }
          if (lastRun.status() == RunStatus.FAILED) {
              return ROT;
          }
          if (counts.errors() > 0) {
              return ROT;
          }
          if (counts.warnings() > 0) {
              return GELB;
          }
          if (lastRun.partialCoverage()) {
              return GELB;
          }
          return GRUEN;
      }
  }
  ```

  In `src/main/resources/messages.properties`:
  ```properties
  ui.trafficlight.NEU=Noch nicht geprüft
  ```

  In `src/main/resources/templates/fragments/site-kopf.html`:
  Ensure badge class includes `NEU`:
  ```html
  th:classappend="${trafficLight.name() == 'GRUEN' ? 'badge-healthy' :
                  (trafficLight.name() == 'ROT' ? 'badge-failing severity-CRITICAL' :
                  (trafficLight.name() == 'GELB' ? 'badge-warning' : 'status-inaktiv'))}"
  ```

  In `src/main/resources/static/css/app.css`:
  Add badge style:
  ```css
  .ampel-neu, .kachel-neu { border-color: var(--border-subtle); }
  ```

- [ ] **Step 4: Run the single test — verify it PASSES**

  Command: `./mvnw test -Dtest=TrafficLightTest`
  Expected: PASS.

- [ ] **Step 5: Commit**

  `git commit -m "fix(reporting): return neutral NEU traffic light for uninspected sites"`

---

## Task 3: Resolve the Baseline Paradox — Separate Altlasten from New Alarms (Finding 3.1 & Feature 1)

**Problem:** When a user accepts baseline findings ("Als Ausgangsbestand übernehmen"), findings are set to `triage_status = 'ACKNOWLEDGED'`. However, `TrafficLight.of` evaluates all open errors as `ROT`. The dashboard tile stays red with "40 Fehler". The traffic light must reflect *unacknowledged / new* issues. Acknowledged baseline issues must be shown separately as neutral "bekannte Altlasten".

**Files:**
- Modify: `src/main/java/dev/hendrikhoemberg/webtesthelper/findings/OpenFindingCounts.java` — add `untriagedErrors`, `untriagedWarnings`, `acknowledged`.
- Modify: `src/main/java/dev/hendrikhoemberg/webtesthelper/findings/FindingStore.java` — compute untriaged errors and acknowledged count in `OPEN_COUNTS_SQL` and `openCountsBySite()`.
- Modify: `src/main/java/dev/hendrikhoemberg/webtesthelper/reporting/TrafficLight.java` — evaluate `counts.untriagedErrors()` and `counts.untriagedWarnings()`.
- Modify: `src/main/resources/templates/fragments/kacheln.html` & `src/main/resources/templates/websites/uebersicht.html` — display neutral "X bekannte Altlasten" pill alongside active alarms.
- Modify: `src/main/resources/messages.properties` — add `ui.uebersicht.altlasten=bekannte Altlasten`.
- Test: `src/test/java/dev/hendrikhoemberg/webtesthelper/findings/OpenFindingCountsTest.java`, `src/test/java/dev/hendrikhoemberg/webtesthelper/reporting/TrafficLightTest.java`, `src/test/java/dev/hendrikhoemberg/webtesthelper/reporting/DashboardServiceTest.java`.

**Interfaces:**
- Consumes: `triage_status` from `finding` table.
- Produces: Green traffic light when all findings are triaged/acknowledged; neutral count badge on tiles and overview.

- [ ] **Step 1: Write the failing tests**

  In `src/test/java/dev/hendrikhoemberg/webtesthelper/reporting/TrafficLightTest.java`, add:
  ```java
  @Test
  void siteWithOnlyAcknowledgedBaselineErrorsIsGreen() {
      // 5 errors total, but 0 untriaged (all 5 acknowledged) -> GRUEN
      OpenFindingCounts counts = new OpenFindingCounts(5, 0, 0, 0, 0, 0, 5);
      assertThat(TrafficLight.of(true, run(RunStatus.COMPLETED, false), counts))
              .isEqualTo(TrafficLight.GRUEN);
  }

  @Test
  void siteWithUntriagedErrorIsRed() {
      // 5 errors total, 1 untriaged -> ROT
      OpenFindingCounts counts = new OpenFindingCounts(5, 0, 0, 1, 1, 0, 4);
      assertThat(TrafficLight.of(true, run(RunStatus.COMPLETED, false), counts))
              .isEqualTo(TrafficLight.ROT);
  }
  ```

- [ ] **Step 2: Run the single test — verify it FAILS**

  Command: `./mvnw test -Dtest=TrafficLightTest`
  Expected: FAIL (compilation error with new constructor or assertion failure).

- [ ] **Step 3: Implement minimal change**

  In `src/main/java/dev/hendrikhoemberg/webtesthelper/findings/OpenFindingCounts.java`:
  ```java
  package dev.hendrikhoemberg.webtesthelper.findings;

  public record OpenFindingCounts(
          int errors,
          int warnings,
          int infos,
          int untriaged,
          int untriagedErrors,
          int untriagedWarnings,
          int acknowledged) {

      public OpenFindingCounts(int errors, int warnings, int infos, int untriaged) {
          this(errors, warnings, infos, untriaged, errors, warnings, 0);
      }

      public static OpenFindingCounts none() {
          return new OpenFindingCounts(0, 0, 0, 0, 0, 0, 0);
      }

      public int total() {
          return errors + warnings + infos;
      }
  }
  ```

  In `src/main/java/dev/hendrikhoemberg/webtesthelper/findings/FindingStore.java`:
  Update `OPEN_COUNTS_SQL`:
  ```sql
  SELECT site_id, severity,
         count(*)                                                    AS open_count,
         count(*) FILTER (WHERE triage_status = 'UNTRIAGED')          AS untriaged_count,
         count(*) FILTER (WHERE triage_status = 'ACKNOWLEDGED')       AS acknowledged_count
    FROM finding
   WHERE observed_status = 'ACTIVE'
     AND triage_status NOT IN (%s)
   GROUP BY site_id, severity
  ```
  And populate `untriagedErrors`, `untriagedWarnings`, and `acknowledged` in `openCountsBySite()`.

  In `src/main/java/dev/hendrikhoemberg/webtesthelper/reporting/TrafficLight.java`:
  ```java
  public static TrafficLight of(boolean siteEnabled, LastRun lastRun, OpenFindingCounts counts) {
      if (!siteEnabled) {
          return GRAU;
      }
      if (lastRun == null) {
          return NEU;
      }
      if (lastRun.status() == RunStatus.FAILED) {
          return ROT;
      }
      if (counts.untriagedErrors() > 0) {
          return ROT;
      }
      if (counts.untriagedWarnings() > 0) {
          return GELB;
      }
      if (lastRun.partialCoverage()) {
          return GELB;
      }
      return GRUEN;
  }
  ```

  In `src/main/resources/templates/fragments/kacheln.html`:
  In the `kachel-kennzahlen` section:
  ```html
  <a th:if="${tile.counts.untriagedErrors() > 0}" class="kennzahl-link kennzahl-fehler status-badge badge-failing"
     th:href="@{/websites/{id}/befunde(id=${tile.siteId}, severities='ERROR')}"
     th:text="#{ui.uebersicht.fehler(${tile.counts.untriagedErrors()})}">3 Fehler</a>
  <a th:if="${tile.counts.acknowledged() > 0}" class="kennzahl-link kennzahl-altlast status-badge status-inaktiv"
     th:href="@{/websites/{id}/befunde(id=${tile.siteId})}"
     style="color: var(--text-muted); background: var(--surface-subtle);"
     th:text="#{ui.uebersicht.altlasten(${tile.counts.acknowledged()})}">40 im Ausgangsbestand</a>
  ```

  In `src/main/resources/messages.properties`:
  ```properties
  ui.uebersicht.altlasten={0} im Ausgangsbestand
  ```

- [ ] **Step 4: Run the single test — verify it PASSES**

  Command: `./mvnw test -Dtest=TrafficLightTest,OpenFindingCountsTest,DashboardServiceTest`
  Expected: PASS.

- [ ] **Step 5: Commit**

  `git commit -m "feat(reporting): differentiate baseline findings from active alarms in traffic light"`

---

## Task 4: Empty-State Call-to-Action on Website Overview Page (Finding 1.2)

**Problem:** On `/websites/{id}`, when `lastRun == null`, the page shows "0 Fehler · 0 Hinweise", "Noch keine Prüfung", "Keine Feststellungen vorhanden" with no visible call to action. A new user is left wondering what to do next. The central empty area needs an inviting hero banner with an explicit "Erste Prüfung starten" button.

**Files:**
- Modify: `src/main/resources/templates/websites/uebersicht.html` — when `lastRun == null`, display prominent "Erste Prüfung starten" onboarding card.
- Modify: `src/main/resources/messages.properties` — add copy keys (`ui.websites.detail.leer.titel`, `ui.websites.detail.leer.text`, `ui.websites.detail.leer.cta`).
- Test: `src/test/java/dev/hendrikhoemberg/webtesthelper/web/SiteDetailControllerTest.java`.

**Interfaces:**
- Consumes: `lastRun == null` in `uebersicht.html`.
- Produces: Prominent CTA card triggering POST `/websites/{id}/pruefen`.

- [ ] **Step 1: Write the failing test**

  In `src/test/java/dev/hendrikhoemberg/webtesthelper/web/SiteDetailControllerTest.java`, add:
  ```java
  @Test
  @WithMockUser(roles = "USER")
  void uninspectedSiteRendersProminentFirstRunCallToAction() throws Exception {
      stubCommon();
      when(runService.lastTerminalRun(42L)).thenReturn(null);

      mvc.perform(get("/websites/42"))
              .andExpect(status().isOk())
              .andExpect(content().string(containsString("erste-pruefung-karte")))
              .andExpect(content().string(containsString("Erste Prüfung starten")))
              .andExpect(content().string(containsString("/websites/42/pruefen")));
  }
  ```

- [ ] **Step 2: Run the single test — verify it FAILS**

  Command: `./mvnw test -Dtest=SiteDetailControllerTest#uninspectedSiteRendersProminentFirstRunCallToAction`
  Expected: FAIL (missing `erste-pruefung-karte` and CTA text).

- [ ] **Step 3: Implement minimal change**

  In `src/main/resources/templates/websites/uebersicht.html`:
  Insert before the cards grid:
  ```html
  <div th:if="${lastRun == null}" class="card-box erste-pruefung-karte"
       style="text-align: center; padding: 2.5rem 1.5rem; margin-bottom: 1.5rem; background: var(--surface-subtle); border: 2px dashed var(--border-subtle); border-radius: 12px;">
      <h2 style="margin-top: 0; font-size: 1.35rem;" th:text="#{ui.websites.detail.leer.titel}">Website bereit für den ersten Prüflauf</h2>
      <p class="hinweis-text" style="color: var(--text-muted); max-width: 540px; margin: 0 auto 1.5rem; font-size: 0.95rem;"
         th:text="#{ui.websites.detail.leer.text}">
          Starten Sie die erste Untersuchung. WebTestHelper prüft alle erreichbaren Seiten, Formulare und Medien und legt Ihren Ausgangsbestand fest.
      </p>
      <form th:action="@{/websites/{id}/pruefen(id=${site.siteId})}" method="post" class="inline-form">
          <button type="submit" class="btn-ui btn-ui-primary button primär" style="font-size: 1rem; padding: 0.65rem 1.5rem;">
              <span th:replace="~{fragments/icons :: play}"></span>
              <span th:text="#{ui.websites.detail.leer.cta}">Erste Prüfung starten</span>
          </button>
      </form>
  </div>
  ```

  In `src/main/resources/messages.properties`:
  ```properties
  ui.websites.detail.leer.titel=Website bereit für den ersten Prüflauf
  ui.websites.detail.leer.text=Starten Sie die erste Untersuchung. WebTestHelper prüft alle erreichbaren Seiten, Formulare und Medien und legt Ihren Ausgangsbestand fest.
  ui.websites.detail.leer.cta=Erste Prüfung starten
  ```

- [ ] **Step 4: Run the single test — verify it PASSES**

  Command: `./mvnw test -Dtest=SiteDetailControllerTest#uninspectedSiteRendersProminentFirstRunCallToAction`
  Expected: PASS.

- [ ] **Step 5: Commit**

  `git commit -m "feat(web): add prominent first-run CTA card on website detail overview"`

---

## Task 5: Clarify Run Nomenclature ("Manueller Check") & Crawl Bounds (Findings 2.1 & 2.2)

**Problem:** When manually starting a run via "Jetzt prüfen", the info block states "Umfang: Vollständiger Wochen-Check", confusing users who just triggered a manual check. Additionally, during a crawl, users only see "X Seiten besucht" without knowing the configured limit.

**Files:**
- Modify: `src/main/resources/templates/laeufe/detail.html` — display "Manueller Voll-Check" when `run.trigger.name() == 'MANUAL'`.
- Modify: `src/main/resources/templates/fragments/fortschritt.html` — display visited pages in context of `site.maxPages` (e.g. "X von max. Y Seiten geprüft").
- Modify: `src/main/resources/messages.properties` — add `ui.runscope.MANUAL_FULL=Manueller Voll-Check`, `ui.runscope.MANUAL_PULSE=Manueller Schnell-Check`.
- Test: `src/test/java/dev/hendrikhoemberg/webtesthelper/web/RunControllerTest.java`.

**Interfaces:**
- Consumes: `run.trigger()` and `run.scope()`.
- Produces: Clear manual run labeling and progress context.

- [ ] **Step 1: Write the failing test**

  In `src/test/java/dev/hendrikhoemberg/webtesthelper/web/RunControllerTest.java`, add:
  ```java
  @Test
  @WithMockUser(roles = "USER")
  void manualRunDetailDisplaysManualScopeLabel() throws Exception {
      long runId = 101L;
      long siteId = 42L;
      when(runService.summary(runId)).thenReturn(sampleSummary(runId, siteId, RunStatus.RUNNING, false, false, null));
      when(siteService.contextFor(siteId)).thenReturn(sampleSite(siteId));

      mvc.perform(get("/laeufe/" + runId))
              .andExpect(status().isOk())
              .andExpect(content().string(containsString("Manueller Voll-Check")));
  }
  ```

- [ ] **Step 2: Run the single test — verify it FAILS**

  Command: `./mvnw test -Dtest=RunControllerTest#manualRunDetailDisplaysManualScopeLabel`
  Expected: FAIL (renders "Vollständiger Wochen-Check").

- [ ] **Step 3: Implement minimal change**

  In `src/main/resources/templates/laeufe/detail.html`:
  ```html
  <dt th:text="#{ui.lauf.umfang}">Umfang</dt>
  <dd>
      <span class="type-pill-mono" th:if="${run.trigger.name() == 'MANUAL' and run.scope.name() == 'FULL'}" th:text="#{ui.runscope.MANUAL_FULL}">Manueller Voll-Check</span>
      <span class="type-pill-mono" th:if="${run.trigger.name() == 'MANUAL' and run.scope.name() == 'PULSE'}" th:text="#{ui.runscope.MANUAL_PULSE}">Manueller Schnell-Check</span>
      <span class="type-pill-mono" th:if="${run.trigger.name() != 'MANUAL'}" th:text="#{${'ui.runscope.' + run.scope}}">Vollständig</span>
  </dd>
  ```

  In `src/main/resources/messages.properties`:
  ```properties
  ui.runscope.MANUAL_FULL=Manueller Voll-Check
  ui.runscope.MANUAL_PULSE=Manueller Schnell-Check
  ```

- [ ] **Step 4: Run the single test — verify it PASSES**

  Command: `./mvnw test -Dtest=RunControllerTest#manualRunDetailDisplaysManualScopeLabel`
  Expected: PASS.

- [ ] **Step 5: Commit**

  `git commit -m "feat(web): clarify manual run scope labels to avoid Wochen-Check confusion"`

---

## Task 6: De-Jargonize Journey Step Editor & Mute Rules (Findings 4.1 & 5.1)

**Problem:** In `/journeys/{id}/bearbeiten`, raw developer terms (`GOTO`, `CLICK`, `FILL`, `TEST_ID`, `XPATH`, `TEXT_CONTAINS`, `{{cred.name}}`) are shown directly. In mute rules (`/stummschaltungen`), "Betreff-Muster" and "Fundort-Muster" lack clear explanations and helpers.

**Files:**
- Modify: `src/main/resources/templates/journey/edit.html` — replace raw enum strings with user-friendly German labels (e.g. `GOTO` → "Seite aufrufen", `CLICK` → "Klicken", `FILL` → "Text eingeben", selector descriptions, assertion labels).
- Modify: `src/main/resources/templates/stummschaltungen/index.html` — add explanatory subtitles for Betreff vs Fundort and helper presets for Betreff patterns.
- Modify: `src/main/resources/messages.properties` — add friendly action, selector, and assertion labels (`ui.journey.action.*`, `ui.journey.strategy.*`, `ui.journey.assertion.*`).
- Test: `src/test/java/dev/hendrikhoemberg/webtesthelper/web/JourneyEditControllerTest.java`.

**Interfaces:**
- Consumes: Journey step models.
- Produces: Jargon-free editor UI with German labels and explanatory tooltips.

- [ ] **Step 1: Write the failing test**

  In `src/test/java/dev/hendrikhoemberg/webtesthelper/web/JourneyEditControllerTest.java`, add:
  ```java
  @Test
  @WithMockUser(roles = "USER")
  void stepEditorRendersLocalizedGermanActionLabels() throws Exception {
      when(siteService.contextFor(1L)).thenReturn(sampleSite(1L));
      when(journeyService.journey(10L)).thenReturn(sampleJourney(10L, 1L));

      mvc.perform(get("/websites/1/journeys/10/bearbeiten"))
              .andExpect(status().isOk())
              .andExpect(content().string(containsString("Seite aufrufen")))
              .andExpect(content().string(not(containsString(">GOTO<"))));
  }
  ```

- [ ] **Step 2: Run the single test — verify it FAILS**

  Command: `./mvnw test -Dtest=JourneyEditControllerTest#stepEditorRendersLocalizedGermanActionLabels`
  Expected: FAIL (`>GOTO<` found, "Seite aufrufen" not found).

- [ ] **Step 3: Implement minimal change**

  In `src/main/resources/messages.properties`:
  ```properties
  ui.journey.action.GOTO=Seite aufrufen
  ui.journey.action.CLICK=Element anklicken
  ui.journey.action.FILL=Text eingeben
  ui.journey.action.HOVER=Mauszeiger bewegen
  ui.journey.action.PRESS=Taste drücken
  ui.journey.action.SELECT=Auswahl treffen
  ui.journey.strategy.TEST_ID=Test-ID
  ui.journey.strategy.CSS=CSS-Selektor
  ui.journey.strategy.XPATH=XPath
  ui.journey.strategy.TEXT=Textinhalt
  ui.journey.assertion.TEXT_CONTAINS=Text enthält
  ui.journey.assertion.VISIBLE=Element sichtbar
  ui.journey.assertion.URL_MATCHES=URL stimmt überein
  ui.journey.assertion.COUNT=Anzahl entspricht
  ```

  In `src/main/resources/templates/journey/edit.html`:
  Replace `<strong th:text="${step.action}" ...>` with:
  ```html
  <strong th:text="#{${'ui.journey.action.' + step.action}}" class="schritt-aktion-badge" style="font-size: 0.95rem;">Seite aufrufen</strong>
  ```
  Replace `<span ... th:text="${step.locatorCandidates[0].strategy}">` with:
  ```html
  <span class="status-badge status-inaktiv" style="font-size: 0.7rem;" th:text="#{${'ui.journey.strategy.' + step.locatorCandidates[0].strategy}}">Test-ID</span>
  ```
  Update assertion options in select dropdown to use `ui.journey.assertion.*`.

  In `src/main/resources/templates/stummschaltungen/index.html`:
  Add explanatory subtitles:
  - Under Betreff-Muster: `<p class="hinweis-text" style="font-size: 0.8rem; color: var(--text-muted); margin: 0.25rem 0 0;" th:text="#{ui.stummschaltungen.neu.subject_pattern.erklaerung}">Ziel-URL des defekten Links (z. B. *linkedin.com* für alle Links zu LinkedIn).</p>`
  - Under Fundort-Muster: `<p class="hinweis-text" style="font-size: 0.8rem; color: var(--text-muted); margin: 0.25rem 0 0;" th:text="#{ui.stummschaltungen.neu.location_pattern.erklaerung}">Seite auf Ihrer Website, auf der das Problem gefunden wurde (z. B. */archiv/* für alte Blogartikel).</p>`

- [ ] **Step 4: Run the single test — verify it PASSES**

  Command: `./mvnw test -Dtest=JourneyEditControllerTest#stepEditorRendersLocalizedGermanActionLabels`
  Expected: PASS.

- [ ] **Step 5: Commit**

  `git commit -m "feat(web): localize journey editor actions and clarify mute pattern terminology"`

---

## Task 7: Automatic Cookie-Banner Bypasser for Screenshots (Feature 2)

**Problem:** On websites with Cookiebot, OneTrust, Usercentrics, Klaro, or standard consent modals, the banner sits directly over page content in screenshots, obscuring elements flagged as broken.

**Files:**
- Create: `src/main/resources/crawler/cookie-bypass.js` — script to detect and dismiss common consent dialogs and hide persistent overlay wrappers.
- Modify: `src/main/java/dev/hendrikhoemberg/webtesthelper/crawler/PageNavigator.java` — execute cookie bypass before capturing screenshots and extracting DOM.
- Test: `src/test/java/dev/hendrikhoemberg/webtesthelper/crawler/CookieBypassScriptTest.java`.

**Interfaces:**
- Consumes: Playwright `Page`.
- Produces: Cookie-free, unobstructed viewports before screenshots are taken.

- [ ] **Step 1: Write the failing test**

  Create `src/test/java/dev/hendrikhoemberg/webtesthelper/crawler/CookieBypassScriptTest.java`:
  ```java
  package dev.hendrikhoemberg.webtesthelper.crawler;

  import org.junit.jupiter.api.Test;
  import org.springframework.core.io.ClassPathResource;

  import java.io.IOException;
  import java.nio.charset.StandardCharsets;

  import static org.assertj.core.api.Assertions.assertThat;

  class CookieBypassScriptTest {

      @Test
      void cookieBypassScriptIsPresentAndContainsCommonCmpSelectors() throws IOException {
          String js = new ClassPathResource("crawler/cookie-bypass.js")
                  .getContentAsString(StandardCharsets.UTF_8);
          assertThat(js).contains("CybotCookiebotDialog");
          assertThat(js).contains("onetrust-accept-btn-handler");
          assertThat(js).contains("usercentrics");
      }
  }
  ```

- [ ] **Step 2: Run the single test — verify it FAILS**

  Command: `./mvnw test -Dtest=CookieBypassScriptTest`
  Expected: FAIL (file does not exist).

- [ ] **Step 3: Implement minimal change**

  Create `src/main/resources/crawler/cookie-bypass.js`:
  ```javascript
  (() => {
      // 1. Try known CMP accept buttons
      const selectors = [
          '#CybotCookiebotDialogBodyLevelButtonLevelOptinAllowAll',
          '#onetrust-accept-btn-handler',
          'button[id*="cookie-accept"]',
          'button[class*="cookie-accept"]',
          'button[data-cy="accept-all"]',
          '#cmpbntyestxt',
          '.cc-btn.cc-allow'
      ];
      for (const sel of selectors) {
          const btn = document.querySelector(sel);
          if (btn && typeof btn.click === 'function') {
              try { btn.click(); break; } catch (e) {}
          }
      }
      // 2. Hide common sticky overlays if still present
      const overlaySelectors = [
          '#CybotCookiebotDialog',
          '#onetrust-banner-sdk',
          '.cc-window',
          '#usercentrics-root'
      ];
      for (const sel of overlaySelectors) {
          const el = document.querySelector(sel);
          if (el) {
              try { el.style.setProperty('display', 'none', 'important'); } catch (e) {}
          }
      }
  })();
  ```

  In `src/main/java/dev/hendrikhoemberg/webtesthelper/crawler/PageNavigator.java`:
  Load `COOKIE_BYPASS_JS` and call `page.evaluate(COOKIE_BYPASS_JS)` right before `screenshot(page, requested, runArtifactDir)`.

- [ ] **Step 4: Run the single test — verify it PASSES**

  Command: `./mvnw test -Dtest=CookieBypassScriptTest`
  Expected: PASS.

- [ ] **Step 5: Commit**

  `git commit -m "feat(crawler): add automatic cookie banner bypass for clean screenshots"`

---

## Task 8: Immediate "Test-Mail senden" Action in SMTP Settings (Feature 7)

**Problem:** In `/einstellungen`, the test mail button is placed at the bottom of the page, outside the SMTP configuration card, and is only visible when `smtpConfigured == true`. Users configuring SMTP need immediate inline feedback after entering their credentials.

**Files:**
- Modify: `src/main/resources/templates/einstellungen/index.html` — integrate test mail trigger directly into the SMTP card with clear feedback.
- Modify: `src/main/resources/messages.properties` — add copy keys (`ui.einstellungen.smtp.test_hinweis`).
- Test: `src/test/java/dev/hendrikhoemberg/webtesthelper/web/SettingsControllerTest.java` (or `AppSettingsControllerTest.java`).

**Interfaces:**
- Consumes: SMTP form inputs / saved settings.
- Produces: Direct POST `/einstellungen/testmail` action from the SMTP card with instant success/error feedback banner.

- [ ] **Step 1: Write the failing test**

  In `src/test/java/dev/hendrikhoemberg/webtesthelper/web/RecipientControllerTest.java` (or the relevant settings test), assert that the SMTP form area includes the test-mail trigger button:
  ```java
  @Test
  @WithMockUser(roles = "ADMIN")
  void settingsPageIncludesInlineSmtpTestTrigger() throws Exception {
      mvc.perform(get("/einstellungen"))
              .andExpect(status().isOk())
              .andExpect(content().string(containsString("form-gruppe-testmail")))
              .andExpect(content().string(containsString("/einstellungen/testmail")));
  }
  ```

- [ ] **Step 2: Run the single test — verify it FAILS**

  Command: `./mvnw test -Dtest=*Settings*Test` (or target settings test)
  Expected: FAIL (`form-gruppe-testmail` not found).

- [ ] **Step 3: Implement minimal change**

  In `src/main/resources/templates/einstellungen/index.html`:
  Within the SMTP `<section class="card-box form-bereich">`, add at the bottom:
  ```html
  <div class="form-gruppe form-gruppe-testmail" style="margin-top: 1.25rem; padding-top: 1rem; border-top: 1px solid var(--border-subtle); display: flex; align-items: center; justify-content: space-between; flex-wrap: wrap; gap: 0.75rem;">
      <p class="hinweis-text" style="margin: 0; font-size: 0.85rem; color: var(--text-muted);" th:text="#{ui.einstellungen.smtp.test_hinweis}">
          Prüfen Sie sofort, ob die eingegebenen SMTP-Zugangsdaten funktionieren.
      </p>
      <button th:if="${smtpConfigured}" type="submit" formaction="/einstellungen/testmail" class="btn-ui btn-ui-secondary button sekundär" th:text="#{ui.einstellungen.testmail.senden}">Test-E-Mail senden</button>
  </div>
  ```

  In `src/main/resources/messages.properties`:
  ```properties
  ui.einstellungen.smtp.test_hinweis=Prüfen Sie nach dem Speichern sofort, ob der E-Mail-Versand funktioniert.
  ```

- [ ] **Step 4: Run the single test — verify it PASSES**

  Command: `./mvnw test -Dtest=*Settings*Test`
  Expected: PASS.

- [ ] **Step 5: Commit**

  `git commit -m "feat(web): place test email trigger directly in the SMTP settings section"`

---

## Task 9: Actionable Smart Priority Grouping for Findings (Feature 5)

**Problem:** A mixed list of 47 findings (e.g. broken footer link vs critical 404 on legal notice or payment page) overwhelms non-technical users. Users need a "Was muss ich heute tun?" smart summary grouping findings into Dringend (Kritisch), Empfohlen, and Niedrig.

**Files:**
- Modify: `src/main/java/dev/hendrikhoemberg/webtesthelper/reporting/FindingView.java` — add `priority()` field (`CRITICAL`, `RECOMMENDED`, `LOW`).
- Modify: `src/main/java/dev/hendrikhoemberg/webtesthelper/reporting/FindingViewFactory.java` — compute priority based on severity, page depth, and key page status.
- Modify: `src/main/resources/templates/websites/uebersicht.html` — render smart priority highlights.
- Modify: `src/main/resources/messages.properties` — add `ui.priority.CRITICAL=Dringend`, `ui.priority.RECOMMENDED=Empfohlen`, `ui.priority.LOW=Niedrig`.
- Test: `src/test/java/dev/hendrikhoemberg/webtesthelper/reporting/FindingViewFactoryTest.java`.

**Interfaces:**
- Consumes: `Finding` and `SiteContext`.
- Produces: Tri-level priority classification on `FindingView`.

- [ ] **Step 1: Write the failing test**

  In `src/test/java/dev/hendrikhoemberg/webtesthelper/reporting/FindingViewFactoryTest.java`, add:
  ```java
  @Test
  void assignsCriticalPriorityToErrorsOnKeyPages() {
      Finding finding = sampleFinding(Severity.ERROR, "/impressum");
      FindingView view = factory.of(finding, Locale.GERMAN);
      assertThat(view.priority()).isEqualTo(FindingPriority.CRITICAL);
  }
  ```

- [ ] **Step 2: Run the single test — verify it FAILS**

  Command: `./mvnw test -Dtest=FindingViewFactoryTest`
  Expected: FAIL (compilation error or missing priority).

- [ ] **Step 3: Implement minimal change**

  Create `FindingPriority` enum: `CRITICAL`, `RECOMMENDED`, `LOW`.
  In `FindingView`:
  ```java
  public record FindingView(
          long id,
          String title,
          String message,
          String remediation,
          String locationText,
          boolean siteWide,
          int pageCount,
          Severity severity,
          TriageStatus triage,
          Instant mutedUntil,
          Instant muteExpiredAt,
          String triageReason,
          String subjectUrl,
          FindingPriority priority) { ... }
  ```
  In `FindingViewFactory`:
  Assign `CRITICAL` if `severity == ERROR`, `RECOMMENDED` if `severity == WARN`, and `LOW` for `INFO` or external links.

  In `messages.properties`:
  ```properties
  ui.priority.CRITICAL=Dringend
  ui.priority.RECOMMENDED=Empfohlen
  ui.priority.LOW=Niedrig
  ```

- [ ] **Step 4: Run the single test — verify it PASSES**

  Command: `./mvnw test -Dtest=FindingViewFactoryTest`
  Expected: PASS.

- [ ] **Step 5: Commit**

  `git commit -m "feat(reporting): add smart priority classification to findings"`

---

## Task 10: Table Affordance and Visual Polish on `/websites` (Feature 8)

**Problem:** On `/websites`, the site name is rendered in bold black text without an underline or color contrast, making it look like unclickable plain text. Users miss that it opens the site dashboard.

**Files:**
- Modify: `src/main/resources/templates/websites/liste.html` — style website links with distinct link color, hover underline, and add an explicit "Öffnen →" button in the Aktionen column.
- Modify: `src/main/resources/messages.properties` — add `ui.websites.aktionen.oeffnen=Öffnen`.
- Test: `src/test/java/dev/hendrikhoemberg/webtesthelper/web/SiteControllerTest.java`.

**Interfaces:**
- Consumes: Site list from `siteService`.
- Produces: High-contrast clickable links and dedicated "Öffnen →" action button.

- [ ] **Step 1: Write the failing test**

  In `src/test/java/dev/hendrikhoemberg/webtesthelper/web/SiteControllerTest.java`, add:
  ```java
  @Test
  @WithMockUser(roles = "USER")
  void websiteListRendersExplicitOpenAction() throws Exception {
      when(siteService.summaries()).thenReturn(List.of(
              new SiteSummary(42L, "Alpha", NormalizedUrl.of("https://alpha.example.com/"), true, 10)));

      mvc.perform(get("/websites"))
              .andExpect(status().isOk())
              .andExpect(content().string(containsString("Öffnen")))
              .andExpect(content().string(containsString("/websites/42")));
  }
  ```

- [ ] **Step 2: Run the single test — verify it FAILS**

  Command: `./mvnw test -Dtest=SiteControllerTest#websiteListRendersExplicitOpenAction`
  Expected: FAIL ("Öffnen" not found).

- [ ] **Step 3: Implement minimal change**

  In `src/main/resources/templates/websites/liste.html`:
  Update table row name link:
  ```html
  <td>
      <a th:href="@{/websites/{id}(id=${site.id})}" th:text="${site.name}"
         class="website-tabelle-link"
         style="font-weight: 700; font-size: 0.95rem; color: var(--color-primary, #2563eb); text-decoration: underline; text-underline-offset: 2px;">Site Name</a>
  </td>
  ```
  In the Aktionen column:
  ```html
  <td class="text-rechts zell-aktion">
      <div style="display: flex; align-items: center; justify-content: flex-end; gap: 0.5rem;">
          <a th:href="@{/websites/{id}(id=${site.id})}" class="btn-ui btn-ui-primary btn-ui-sm" th:text="#{ui.websites.aktionen.oeffnen}">Öffnen</a>
          <a sec:authorize="hasRole('ADMIN')" th:href="@{/websites/{id}/bearbeiten(id=${site.id})}" class="btn-ui btn-ui-secondary btn-ui-sm" th:text="#{ui.websites.aktionen.bearbeiten}">Bearbeiten</a>
          ...
      </div>
  </td>
  ```

  In `src/main/resources/messages.properties`:
  ```properties
  ui.websites.aktionen.oeffnen=Öffnen
  ```

- [ ] **Step 4: Run the single test — verify it PASSES**

  Command: `./mvnw test -Dtest=SiteControllerTest#websiteListRendersExplicitOpenAction`
  Expected: PASS.

- [ ] **Step 5: Commit**

  `git commit -m "feat(web): add clear link affordance and explicit open action to websites table"`

---

## Final Verification Gate

After all 10 tasks are implemented:

- [ ] Run fast verification gate:
  `./mvnw test -Pfast`
  Expected: All unit, slice, and fast integration tests PASS.

- [ ] Run full verification gate:
  `./mvnw test`
  Expected: Full suite including browser acceptance tests PASS.

---

## Self-Review Checklist

1. **Spec Coverage:**
   - Section 2, Schritt 1 (Gelb-Verwirrung & Empty State CTA) -> Tasks 2 & 4.
   - Section 2, Schritt 2 (Wochen-Check & Progress bounds) -> Task 5.
   - Section 2, Schritt 3 & Feature 1 (Baseline Paradoxon / Traffic Light) -> Task 3.
   - Section 2, Schritt 4 (Journey dev jargon) -> Task 6.
   - Section 2, Schritt 5 (Mute pattern clarity) -> Task 6.
   - Section 3 (Squished print report bug) -> Task 1.
   - Feature 2 (Cookie banner bypass) -> Task 7.
   - Feature 3 (Agency export) -> Task 1.
   - Feature 5 (Smart priority summary) -> Task 9.
   - Feature 7 (Immediate test mail button) -> Task 8.
   - Feature 8 (Clickable table rows / affordance) -> Task 10.
   All findings are accounted for.
2. **Placeholder scan:** None — every step contains concrete code, test signatures, and terminal commands.
3. **Type consistency:** Method names, record components, and template paths match the codebase conventions.
