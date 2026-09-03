a# Website Detail Tabs Implementation Plan

**Goal:** Split the overloaded website detail page into three real tabs (Übersicht & Feststellungen / Prüfläufe / Konfiguration), restore monitoring-first layout, declutter the header, group the check options by category, use layperson terminology, and fix the bare `/befunde` / `/laeufe` 404.

**Architecture:** Server-rendered Thymeleaf + HTMX/Alpine (no SPA). Three deep-linkable GET routes on `SiteController` render three new templates; shared `site-kopf` and `site-tabs` fragments; the config form POSTs live on `/websites/{id}/konfiguration`. Existing per-check POST binding (`name="aktiv"`, `name="schweregrad[TYPE]"`) is untouched; only the template it lives in and its redirect/re-render target change.

**Tech Stack:** Spring Boot modular monolith, Thymeleaf + HTMX + Alpine, Postgres + Flyway, Maven wrapper. Packages: `web`, `catalog`, `scheduling`, `runner`, `crawler`, `checks`, `findings`, `reporting`, `recorder`.

**Spec:** `docs/slimpowers/specs/2026-09-01-website-detail-tabs-design.md`

## Global Constraints

- German-only UI; all copy via `messages.properties`, message keys `ui.*` (and `check.*`).
- No internal identifiers in rendered HTML (enum names, raw ISO instants, raw cron, `{0}`).
- View tests: `@WebMvcTest` + MockMvc; assertions on text/markup, not CSS.
- One behavior per test; `./mvnw test -Dtest=<Class>` for a single class.
- Each task must leave the whole build green (no half-swapped controllers/templates).
- Commit after each task (`git commit -m "feat: …"`); the git pre-commit hook runs.

---

### Task 1: CheckCategory domain

**Files:**
- Create: `src/main/java/dev/hendrikhoemberg/webtesthelper/model/CheckCategory.java`
- Modify: `src/main/java/dev/hendrikhoemberg/webtesthelper/checks/CheckRegistry.java` (add category map + accessor)
- Modify: `src/main/resources/messages.properties` (add `ui.checkcat.*`)
- Test: `src/test/java/dev/hendrikhoemberg/webtesthelper/checks/CheckCategoryTest.java`

**Interfaces:**
- Consumes: `CheckRegistry.all() → List<CheckDescriptor>`, `CheckDescriptor.type() → CheckType`.
- Produces: `CheckRegistry.category(CheckType) → CheckCategory` (never null for a registered type); `CheckRegistry.categories() → Set<CheckCategory>` in fixed order.

- [ ] **Step 1: Write the failing test**

```java
package dev.hendrikhoemberg.webtesthelper.checks;

import dev.hendrikhoemberg.webtesthelper.model.CheckCategory;
import dev.hendrikhoemberg.webtesthelper.model.CheckType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CheckCategoryTest {

    private final CheckRegistry registry = CheckRegistry.standard();

    @Test
    void everyRegisteredCheckMapsToANonNullCategory() {
        for (CheckDescriptor check : registry.all()) {
            assertThat(registry.category(check.type())).isNotNull();
        }
    }

    @Test
    void categoryAssignmentFollowsTheSpec() {
        assertThat(registry.category(CheckType.COOKIE_BANNER)).isEqualTo(CheckCategory.RECHT);
        assertThat(registry.category(CheckType.CONTACT_FORM)).isEqualTo(CheckCategory.RECHT);
        assertThat(registry.category(CheckType.TLS_CERT)).isEqualTo(CheckCategory.TECHNIK);
        assertThat(registry.category(CheckType.CONSOLE_ERRORS)).isEqualTo(CheckCategory.TECHNIK);
        assertThat(registry.category(CheckType.DEAD_LINK)).isEqualTo(CheckCategory.INHALT);
        assertThat(registry.category(CheckType.PAGE_STATUS)).isEqualTo(CheckCategory.INHALT);
    }

    @Test
    void categoriesComeBackInRenderOrder() {
        assertThat(registry.categories()).containsExactly(
                CheckCategory.INHALT, CheckCategory.TECHNIK, CheckCategory.RECHT);
    }

    @Test
    void configTemplateRendersTheCategoryHeadings() {
        assertThat(registry.categories())
                .map(CheckCategory::name)
                .containsExactly("INHALT", "TECHNIK", "RECHT");
    }
}
```
`CheckDescriptor` needs importing (`dev.hendrikhoemberg.webtesthelper.checks.CheckDescriptor`).

- [ ] **Step 2: Run the single test — verify it FAILS**
  `./mvnw test -Dtest=CheckCategoryTest` → FAIL (no `CheckCategory`, no `category(...)`).

- [ ] **Step 3: Write minimal implementation**

`model/CheckCategory.java`:
```java
package dev.hendrikhoemberg.webtesthelper.model;

/** Display grouping of checks on the site configuration screen. */
public enum CheckCategory { INHALT, TECHNIK, RECHT }
```

`CheckRegistry.java` — add imports `java.util.EnumMap`, `java.util.Map`, `java.util.LinkedHashSet` and:
```java
public CheckCategory category(CheckType type) {
    CheckCategory category = CATEGORIES.get(type);
    return category == null ? CheckCategory.TECHNIK : category;
}

public List<CheckCategory> categories() {
    return List.of(CheckCategory.INHALT, CheckCategory.TECHNIK, CheckCategory.RECHT);
}

private static final Map<CheckType, CheckCategory> CATEGORIES = categories();
private static Map<CheckType, CheckCategory> categories() {
    Map<CheckType, CheckCategory> map = new EnumMap<>(CheckType.class);
    for (CheckType t : List.of(CheckType.PAGE_STATUS, CheckType.PAGE_UNREACHABLE, CheckType.DEAD_LINK,
            CheckType.IMAGE_BROKEN, CheckType.MEDIA_PLAYABLE, CheckType.FILE_DOWNLOAD,
            CheckType.REDIRECT_CHAIN, CheckType.SITEMAP_CONSISTENCY, CheckType.HREFLANG)) {
        map.put(t, CheckCategory.INHALT);
    }
    for (CheckType t : List.of(CheckType.TLS_CERT, CheckType.MIXED_CONTENT,
            CheckType.CONSOLE_ERRORS, CheckType.IFRAME_EMBED)) {
        map.put(t, CheckCategory.TECHNIK);
    }
    for (CheckType t : List.of(CheckType.COOKIE_BANNER, CheckType.CONTACT_FORM,
            CheckType.LANGUAGE_SWITCHER, CheckType.BUTTON_REACHABILITY)) {
        map.put(t, CheckCategory.RECHT);
    }
    return map;
}
```
(Keep the static initializer referencing the method to avoid forward-reference issues; or move `categories()` above the constant.)

`messages.properties` — add:
```
ui.checkcat.INHALT=Inhalt
ui.checkcat.TECHNIK=Technik
ui.checkcat.RECHT=Rechtliches
```

- [ ] **Step 4: Run the single test — verify it PASSES**
  `./mvnw test -Dtest=CheckCategoryTest` → PASS.

- [ ] **Step 5: Commit**
  `git commit -m "feat: add CheckCategory grouping to CheckRegistry"`

---

### Task 2: bare-URL 404 fix

**Files:**
- Modify: `src/main/java/dev/hendrikhoemberg/webtesthelper/web/WebExceptionHandler.java` (add NoHandlerFound handling so unmatched routes render `error.html` 404)
- Test: `src/test/java/dev/hendrikhoemberg/webtesthelper/web/BarePath404Test.java`

**Interfaces:**
- Consumes: none.
- Produces: bare `GET /befunde` and `GET /laeufe` (no id) respond 404 with the `error.html` body, not a stack trace.

**Context:** `FindingController` (`@RequestMapping("/befunde")`) and `RunController` (`@RequestMapping("/laeufe")`) only map `/{id}`; the bare prefixes fall through to Spring's default no-handler 404. `error.html` exists and distinguishes 404 (`ui.fehler.nicht_gefunden`). Reproduce first, then handle.

- [ ] **Step 1: Write the failing test**

```java
package dev.hendrikhoemberg.webtesthelper.web;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;

@WebMvcTest
@WithMockUser(roles = "USER")
class BarePath404Test {

    @Autowired
    MockMvc mvc;

    @Test
    void bareBefundeRendersFriendly404() throws Exception {
        mvc.perform(get("/befunde"))
                .andExpect(status().isNotFound())
                .andExpect(content().string(containsString("Seite nicht gefunden")))
                .andExpect(content().string(not(containsString("Exception"))));
    }

    @Test
    void bareLaeufeRendersFriendly404() throws Exception {
        mvc.perform(get("/laeufe"))
                .andExpect(status().isNotFound())
                .andExpect(content().string(containsString("Seite nicht gefunden")));
    }
}
```
(Adjust if the actual current output differs after reproducing — record the real "friendly 404" body.)

- [ ] **Step 2: Run the single test — verify it FAILS**
  `./mvnw test -Dtest=BarePath404Test` → FAIL (currently returns whitelabel/stack or empty).

- [ ] **Step 3: Write minimal implementation**
  To render `error.html` for unmatched routes, keep the existing `IllegalArgumentException` handler for unknown IDs and add a Spring Boot no-handler controller. In `WebExceptionHandler` or a new `@Controller`:
```java
@Controller
public class ErrorPageController {
    @GetMapping("/error")
    public String handleError(HttpServletRequest request, Model model) {
        Object status = request.getAttribute(RequestDispatcher.ERROR_STATUS_CODE);
        model.addAttribute("status", status);
        return "error";
    }
}
```
Ensure `error.html` is reachable for these (it is; it is not wrapped in `layout`). If the app already routes `/error` via `BasicErrorController`, instead configure `spring.mvc.throw-exception-if-no-handler-found` / add `@ControllerAdvice` for `NoResourceFoundException`. **Pick whichever reproduces the friendly body; add a comment in code naming the mechanism.** (Verify by running the test.)

- [ ] **Step 4: Run the single test — verify it PASSES**
  `./mvnw test -Dtest=BarePath404Test` → PASS.

- [ ] **Step 5: Commit**
  `git commit -m "fix: render friendly 404 for bare /befunde and /laeufe"`

---

### Task 3: Terminology & message keys

**Files:**
- Modify: `src/main/resources/messages.properties` (runscope labels, budget titel/maxDepth, deep.hinweis)
- Modify: `src/test/java/dev/hendrikhoemberg/webtesthelper/reporting/DigestMailRendererTest.java`
- Modify: `src/test/java/dev/hendrikhoemberg/webtesthelper/reporting/DigestServiceTest.java`
- Modify: `src/test/java/dev/hendrikhoemberg/webtesthelper/reporting/DigestAcceptanceTest.java`
- Modify: `src/test/java/dev/hendrikhoemberg/webtesthelper/web/SiteDetailControllerTest.java` (runscope assertions only; still on `websites/detail` until Task 4)

**Interfaces:**
- Consumes: none.
- Produces: layperson labels used consistently on schedule cards, run table, and digest subjects.

- [ ] **Step 1: Write the failing test**
  Update the digest / site-detail assertions to the new wording (below) — they FAIL against the old `messages.properties`.

  New wording (must be final; digest subjects use these):
  - `ui.runscope.PULSE` = `Schnell-Check (wichtigste Seiten)`
  - `ui.runscope.FULL` = `Vollständiger Wochen-Check`
  - `ui.runscope.DEEP` = `Vollständiger Monats-Check`
  - `ui.runscope.PULSE.kurz` = `Täglicher Schnell-Check der wichtigsten Seiten`
  - `ui.runscope.FULL.kurz` = `Wöchentlicher Check der gesamten Website`
  - `ui.runscope.DEEP.kurz` = `Monatlicher Volltest inkl. Kontaktformular-Prüfung`
  - `ui.zeitplan.deep.hinweis` = `Die Tiefenprüfung verschickt Formular-Testnachrichten an die Website.` (drop the "Ausbaustufe 3" clause)
  - `ui.websites.detail.budget.titel` = `Prüfumfang & Grenzen`
  - `ui.websites.detail.budget.maxDepth` = `Wie tief verlinkte Seiten prüfen`

  Examples of test changes in `SiteDetailControllerTest.getSiteDetailRendersThreeTiersWithoutRawCronInProse` (lines 182–187):
  - `"Puls-Prüfung"` → `"Schnell-Check (wichtigste Seiten)"`
  - `"Vollständige Prüfung"` → `"Vollständiger Wochen-Check"`
  - `"Tiefenprüfung"` → `"Vollständiger Monats-Check"`
  - `.kurz` lines unchanged where text is unchanged (`Wöchentlicher Check der gesamten Website`, `Monatlicher Volltest inkl. Kontaktformular-Prüfung`); `"Schneller täglicher Check der wichtigsten Seiten"` → `"Täglicher Schnell-Check der wichtigsten Seiten"`.

  In `DigestMailRendererTest`, `DigestServiceTest`, `DigestAcceptanceTest`, replace the literal subject fragments `Puls-Prüfung` → `Schnell-Check (wichtigste Seiten)`, `Tiefenprüfung` → `Vollständiger Monats-Check`, `Vollständige Prüfung` → `Vollständiger Wochen-Check`.

  `CheckSettingsControllerTest` line 153 / `CredentialControllerTest` et al. do not reference these labels; leave untouched (still `websites/detail` in Task 3).

- [ ] **Step 2: Run the failing tests**
  `./mvnw test -Dtest=SiteDetailControllerTest,DigestMailRendererTest,DigestServiceTest,DigestAcceptanceTest` → FAIL on the label assertions.

- [ ] **Step 3: Write minimal implementation**
  Apply the `messages.properties` key changes above. (`ui.websites.formular.maxDepth` is optionally mirrored; leave unless the review requires the form too — it is out of scope for the detail page.)

- [ ] **Step 4: Run the tests — verify they PASS**
  `./mvnw test -Dtest=SiteDetailControllerTest,DigestMailRendererTest,DigestServiceTest,DigestAcceptanceTest` → PASS.

- [ ] **Step 5: Commit**
  `git commit -m "feat: layperson terminology for runscope and budget labels"`

---

### Task 4: Tab core — fragments, templates, routing, model

**Files:**
- Create: `src/main/resources/templates/fragments/site-kopf.html`
- Create: `src/main/resources/templates/fragments/site-tabs.html`
- Create: `src/main/resources/templates/websites/uebersicht.html`
- Create: `src/main/resources/templates/websites/laeufe.html`
- Create: `src/main/resources/templates/websites/konfiguration.html`
- Modify: `src/main/java/dev/hendrikhoemberg/webtesthelper/web/SiteDetailModel.java` (add `populateOverview/Runs/Config`; keep `populate` until Task 5)
- Modify: `src/main/java/dev/hendrikhoemberg/webtesthelper/web/SiteController.java` (3 GET routes)
- Modify: `src/test/java/dev/hendrikhoemberg/webtesthelper/web/SiteDetailControllerTest.java` (rewrite for new routes)

**Interfaces:**
- Consumes: `SiteService.contextFor(long) → SiteContext`; `RunService.recentForSite(long,int) → List<RunSummary>`; `ScheduleService.forSite(long) → List<Schedule>`; `CheckRegistry.all()/categories()`; `FindingService.search(FindingQuery) → FindingPage`; `FindingService.openCountsBySite() → Map<Long,OpenFindingCounts>`; `FindingViewFactory.of(Finding,Locale) → FindingView`; `reporting.TrafficLight.of(...)`; `OpenFindingCounts.none()`; `FindingQuery` constructor.
- Produces: `websites/uebersicht`, `websites/laeufe`, `websites/konfiguration` views; shared header/tabs fragments. Route `/websites/{id}` now = Übersicht.

- [ ] **Step 1: Write the failing test** — rewrite `SiteDetailControllerTest` (same mock setup, new routes/templates and new model attributes). Key new assertions:

```java
@Test
@WithMockUser(roles = "USER")
void getUebersichtRendersHealthCardsAndTopFindings() throws Exception {
    // context + runSummary as before
    Finding founding = new Finding(900L, 42L, "fp", CheckType.DEAD_LINK, "https://acme.example.com/seed-kapital",
            "https://acme.example.com/seed-kapital", Severity.ERROR, "check.DEAD_LINK.message",
            List.of("https://acme.example.com/seed-kapital"), Evidence.NONE, ObservedStatus.OBSERVED,
            TriageStatus.OPEN, null, 1L, 1L, null, null, 1, 1,
            Instant.parse("2026-08-25T10:02:30Z"), Instant.parse("2026-08-25T10:02:30Z"));
    when(findingService.search(any(FindingQuery.class)))
            .thenReturn(new FindingPage(List.of(founding), 1, 5, 1));
    when(findingService.openCountsBySite()).thenReturn(Map.of(42L, new OpenFindingCounts(0, 1, 2, 0)));
    when(scheduleService.forSite(42L)).thenReturn(defaultSchedules());

    mvc.perform(get("/websites/42"))
            .andExpect(status().isOk())
            .andExpect(view().name("websites/uebersicht"))
            .andExpect(model().attributeExists("site", "trafficLight", "openCounts", "lastRun", "nextRun", "topFindings"))
            .andExpect(content().string(containsString("Tote Links")))
            .andExpect(content().string(containsString("zur Übersicht")))
            .andExpect(content().string(containsString("/websites/42/laeufe")))
            .andExpect(content().string(containsString("/websites/42/konfiguration")));
}
```
Add tests for `GET /websites/42/laeufe` (view `websites/laeufe`, contains `Verlauf der Prüfläufe`, a run id and `Abgeschlossen`) and `GET /websites/42/konfiguration` (view `websites/konfiguration`, contains `Prüfungen speichern`, `name="aktiv"`, `schweregrad[PAGE_STATUS]`, new `ui.checkcat` headings `Inhalt`). Reuse the `defaultSchedules()`/context/`runSummary` fixtures already in the file. Mock `checkRegistry.category(any())` if the template calls it, and `checkRegistry.categories()` to return the three categories.

Remove the old `getSiteDetailRenders…`/`adminOnly…`/`deleteButton` tests or repoint them at the routes that now carry that markup (schedule controls + delete button move to `konfiguration`; `pruefen` stays on `/websites/42`).

- [ ] **Step 2: Run the single tests — verify they FAIL**
  `./mvnw test -Dtest=SiteDetailControllerTest` → FAIL (routes/templates not yet created, `websites/uebersicht` missing).

- [ ] **Step 3: Write minimal implementation**

**`fragments/site-kopf.html`** (fragment `kopf(site, trafficLight)`):
```html
<div th:fragment="kopf(site, trafficLight)" class="page-header-bar seiten-kopf">
  <div>
    <div class="breadcrumbs-trail">
      <a href="/" th:href="@{/}">WebTestHelper</a><span>/</span>
      <a th:href="@{/websites}">Websites</a><span>/</span>
      <span th:text="${site.name}">Website Name</span>
    </div>
    <h1 class="page-main-title" th:text="${site.name}">Website Name</h1>
    <div class="health-line" style="display:flex; align-items:center; gap:0.5rem; margin-top:0.25rem;">
      <span class="status-badge" th:classappend="
            ${trafficLight.name()=='GRUEN' ? 'badge-healthy' :
             (trafficLight.name()=='ROT' ? 'badge-failing severity-CRITICAL' :
             (trafficLight.name()=='GELB' ? 'badge-warning' : 'status-inaktiv'))}"
            th:text="#{${'ui.trafficlight.' + trafficLight.name()}}">Gesund</span>
      <a th:href="${site.baseUrl.value}" th:text="${site.baseUrl.value}" target="_blank" rel="noopener noreferrer"
         class="basis-url-link" style="font-family:var(--font-mono); font-size:0.825rem; color:var(--text-muted);">
      </a>
    </div>
  </div>
  <div class="header-actions-cluster seiten-kopf-aktionen">
    <form th:action="@{/websites/{id}/pruefen(id=${site.siteId})}" method="post" class="inline-form">
      <button type="submit" class="btn-ui btn-ui-primary button primär">
        <span th:replace="~{fragments/icons :: play}"></span>
        <span th:text="#{ui.websites.detail.pruefen}">Jetzt prüfen</span>
      </button>
    </form>
    <details class="mehr-menue">
      <summary class="btn-ui btn-ui-secondary button sekundär">⋯</summary>
      <div class="mehr-menue-inhalt">
        <a th:href="@{/sites/{id}/journeys(id=${site.siteId})}" th:text="#{ui.websites.detail.journeys}">Benutzerabläufe</a>
        <form th:action="@{/websites/{id}/einrichtung/neu(id=${site.siteId})}" method="post" class="inline-form">
          <button type="submit" class="btn-ui btn-ui-secondary button sekundär"
                  th:text="#{ui.websites.detail.einrichtung_erneut}">Automatische Erkennung wiederholen</button>
        </form>
        <a sec:authorize="hasRole('ADMIN')" th:href="@{/websites/{id}/bearbeiten(id=${site.siteId})}"
           class="btn-ui btn-ui-secondary button sekundär" th:text="#{ui.websites.detail.bearbeiten}">Bearbeiten</a>
        <form sec:authorize="hasRole('ADMIN')" th:action="@{/websites/{id}/loeschen(id=${site.siteId})}" method="post" class="inline-form">
          <button type="submit" class="btn-ui btn-ui-danger button link-loeschen"
                  th:attr="onclick='return confirm(\'' + #{ui.websites.loeschen.bestaetigung} + '\')'"
                  th:text="#{ui.websites.loeschen}">Löschen</button>
        </form>
      </div>
    </details>
  </div>
</div>
```
Add minimal `.mehr-menue` styles to `src/main/resources/static/css/app.css` (position relative; `.mehr-menue-inhalt` absolute right, flex column, card surface). Header is desktop-only.

**`fragments/site-tabs.html`** (fragment `tabs(site, aktiv)`):
```html
<div th:fragment="tabs(site, aktiv)" class="site-tabs" role="tablist">
  <a th:classappend="${aktiv=='uebersicht' ? 'aktiv' : ''}" th:href="@{/websites/{id}(id=${site.siteId})}"
     th:text="#{ui.websites.detail.tab.uebersicht}">Übersicht &amp; Feststellungen</a>
  <a th:classappend="${aktiv=='laeufe' ? 'aktiv' : ''}" th:href="@{/websites/{id}/laeufe(id=${site.siteId})}"
     th:text="#{ui.websites.detail.tab.laeufe}">Prüfläufe</a>
  <a th:classappend="${aktiv=='konfiguration' ? 'aktiv' : ''}" th:href="@{/websites/{id}/konfiguration(id=${site.siteId})}"
     th:text="#{ui.websites.detail.tab.konfiguration}">Konfiguration</a>
</div>
```

**`websites/uebersicht.html`** — layout + kopf + tabs + 3 status cards + top-5 preview:
```html
<!DOCTYPE html>
<html th:replace="~{layout :: seite(${site.name} + ' – ' + #{ui.websites.detail.titel}, ~{::main})}"
      xmlns:th="http://www.thymeleaf.org" xmlns:sec="http://www.thymeleaf.org/extras/spring-security">
<head><title th:text="${site.name} + ' – ' + #{ui.websites.detail.titel}">Website-Details</title></head>
<body>
<main>
  <div th:replace="~{fragments/site-kopf :: kopf(${site}, ${trafficLight})}"></div>
  <div th:replace="~{fragments/site-tabs :: tabs(${site}, 'uebersicht')}"></div>

  <div class="uebersicht-kacheln" style="display:grid; grid-template-columns:repeat(3,1fr); gap:1rem; margin-bottom:1.5rem;">
    <section class="card-box detail-bereich">
      <h2 th:text="#{ui.websites.detail.status.offene}">Offene Feststellungen</h2>
      <p style="font-size:1.4rem; font-weight:700;">
        <span th:text="${openCounts.errors()}">0</span> Fehler ·
        <span th:text="${openCounts.warnings()}">0</span> Hinweise
      </p>
      <a th:href="@{/websites/{id}/befunde(id=${site.siteId})}" th:text="#{ui.websites.detail.status.alle}">Zu allen Feststellungen →</a>
    </section>
    <section class="card-box detail-bereich">
      <h2 th:text="#{ui.websites.detail.status.letzte}">Letzte Prüfung</h2>
      <p th:if="${lastRun == null}" th:text="#{ui.websites.detail.status.noch_nie}">Noch keine Prüfung.</p>
      <p th:if="${lastRun != null}">
        <span th:text="${#temporals.format(lastRun.finishedAt(), 'dd.MM.yyyy HH:mm')}">25.08.2026 10:02</span><br>
        <span class="text-gedaempft" th:text="#{ui.websites.detail.status.dauer(${lastRun.finishedAt().getEpochSecond() - lastRun.startedAt().getEpochSecond()})}">Dauer: 4m 12s</span>
      </p>
    </section>
    <section class="card-box detail-bereich">
      <h2 th:text="#{ui.websites.detail.status.naechste}">Nächste Prüfung</h2>
      <p th:if="${nextRun == null}" th:text="#{ui.websites.detail.status.keine}">Keine geplant.</p>
      <p th:if="${nextRun != null}">
        <span th:text="${#temporals.format(nextRun.nextFireAt(), 'dd.MM.yyyy HH:mm')}">27.08.2026 03:00</span><br>
        <span class="text-gedaempft" th:text="#{${'ui.runscope.' + nextRun.scope()}}">Schnell-Check</span>
      </p>
    </section>
  </div>

  <section class="card-box detail-bereich">
    <div class="abschnitt-ueberschrift-zeile" style="border-bottom:1px solid var(--border-subtle); margin-bottom:1rem; padding-bottom:0.5rem;">
      <h2 style="margin:0; border:none; padding:0;" th:text="#{ui.websites.detail.status.wichtigste}">Neueste Feststellungen</h2>
    </div>
    <ul th:if="${not #lists.isEmpty(topFindings)}" class="muster-liste" style="list-style:none; padding-left:0; display:flex; flex-direction:column; gap:0.5rem;">
      <li th:each="fv : ${topFindings}" class="top-befund-zeile" style="display:flex; align-items:center; justify-content:space-between; gap:1rem; padding:0.75rem 1rem; background:var(--surface-subtle); border:1px solid var(--border-subtle); border-radius:6px;">
        <div>
          <span class="status-badge" th:classappend="${fv.severity().name()=='ERROR' ? 'badge-failing severity-CRITICAL' : (fv.severity().name()=='WARN' ? 'badge-warning' : 'status-inaktiv')}"
                th:text="#{${'ui.severity.' + fv.severity().name()}}">Fehler</span>
          <span style="font-weight:600;" th:text="${fv.title()}">Tote Links</span>
          <span class="text-gedaempft" th:text="${fv.locationText()}">/pfad</span>
        </div>
        <a th:href="@{/befunde/{id}(id=${fv.id()})}" class="btn-ui btn-ui-secondary btn-ui-sm" th:text="#{ui.websites.detail.status.details}">Details →</a>
      </li>
    </ul>
    <p th:if="${#lists.isEmpty(topFindings)}" class="text-gedaempft" th:text="#{ui.websites.detail.status.keine_festst}">Keine Feststellungen vorhanden.</p>
  </section>
</main>
</body>
</html>
```
Keep reused `ui.*` keys; add missing ones (see `messages.properties` additions below).

**`websites/laeufe.html`** — kopf + tabs('laeufe') + the run-history table moved verbatim from old `detail.html` lines 152–201 (with header strip).

**`websites/konfiguration.html`** — kopf + tabs('konfiguration') + the budget/Pfadmuster/Schlüsselseiten sections (moved from old `detail.html` lines 47–103, with renamed bottom `h2` for `Prüfumfang & Grenzen`), + the grouped checks accordion, + `zeitplaene`/`empfaenger`/`zugangsdaten` fragments.

Grouped checks accordion (replaces the flat grid at old lines 124–148):
```html
<section class="card-box detail-bereich">
  <div class="abschnitt-ueberschrift-zeile" style="border-bottom:1px solid var(--border-subtle); margin-bottom:1rem; padding-bottom:0.5rem;">
    <h2 style="margin:0; border:none; padding:0;" th:text="#{ui.websites.detail.pruefungen.titel}">Prüfungen</h2>
  </div>
  <form th:action="@{/websites/{id}/pruefungen(id=${site.siteId})}" method="post">
    <p th:if="${checkSettingsError}" class="fehler-text" th:text="${checkSettingsError}">Fehler</p>
    <details th:each="cat : ${checkCategories}" class="pruefung-gruppe" open>
      <summary th:text="#{${'ui.checkcat.' + cat.name()}}">Inhalt</summary>
      <div class="pruefungen-gitter" style="display:grid; grid-template-columns:repeat(auto-fill,minmax(280px,1fr)); gap:1rem;">
        <div th:each="row : ${checkRows}" th:if="${row.category == cat}" class="pruefung-karte" style="background:var(--surface-subtle); border:1px solid var(--border-subtle); border-radius:8px; padding:1rem;">
          <!-- identical per-check markup as old lines 128–142 -->
        </div>
      </div>
    </details>
    <div class="form-aktionen">
      <button type="submit" class="btn-ui btn-ui-primary button primär" th:text="#{ui.websites.detail.pruefungen.speichern}">Prüfungen speichern</button>
    </div>
  </form>
</section>
```
For the `th:if="${row.category == cat}"` to work, `CheckRowView` needs a `category` field. Extend `CheckRowView` to `record CheckRowView(CheckDescriptor check, boolean enabled, Severity severityOverride, CheckCategory category)` and populate it from `checkRegistry.category(check.type())`.

**`SiteDetailModel`** — add fields/wiring and three methods; keep `populate` for Task 4 compiles:
```java
private final FindingService findingService;
private final FindingViewFactory findingViewFactory;
// constructor additions ...

public void populateConfig(long siteId, Model model) {
    populate(siteId, model);
    model.addAttribute("checkCategories", checkRegistry.categories());
}

public void populateRuns(long siteId, Model model) {
    model.addAttribute("site", siteService.contextFor(siteId));
    model.addAttribute("recentRuns", runService.recentForSite(siteId, RECENT_RUNS));
}

public void populateOverview(long siteId, Model model, Locale locale) {
    SiteContext site = siteService.contextFor(siteId);
    model.addAttribute("site", site);
    List<RunSummary> runs = runService.recentForSite(siteId, 1);
    RunSummary lastRun = runs.isEmpty() ? null : runs.get(0);
    LastRun last = lastRun == null ? null
            : new LastRun(siteId, lastRun.id(), lastRun.status(), lastRun.finishedAt(), lastRun.partialCoverage());
    OpenFindingCounts counts = findingService.openCountsBySite().getOrDefault(siteId, OpenFindingCounts.none());
    model.addAttribute("lastRun", lastRun);
    model.addAttribute("openCounts", counts);
    model.addAttribute("trafficLight", TrafficLight.of(site.enabled(), last, counts));
    Schedule nextRun = scheduleService.forSite(siteId).stream()
            .filter(Schedule::enabled)
            .filter(s -> s.nextFireAt() != null)
            .min(java.util.Comparator.comparing(Schedule::nextFireAt))
            .orElse(null);
    model.addAttribute("nextRun", nextRun);
    FindingPage page = findingService.search(new FindingQuery(siteId, Set.of(), Set.of(), null, Set.of(), 1, 5));
    model.addAttribute("topFindings", page.findings().stream()
            .map(f -> findingViewFactory.of(f, locale)).toList());
}
```
Imports: `LastRun` (`reporting`), `OpenFindingCounts` (`findings`), `TrafficLight` (`reporting`), `FindingPage` (`findings`), `FindingQuery` (`findings`), `FindingViewFactory` (`reporting`), `java.util.Locale`, `java.util.Set`, `java.util.Comparator`.

**`SiteController`** GET routes:
```java
@GetMapping("/websites/{id}")
public String uebersicht(@PathVariable("id") long id, Model model, Locale locale) {
    siteDetailModel.populateOverview(id, model, locale);
    return "websites/uebersicht";
}

@GetMapping("/websites/{id}/laeufe")
public String laeufe(@PathVariable("id") long id, Model model) {
    siteDetailModel.populateRuns(id, model);
    return "websites/laeufe";
}

@GetMapping("/websites/{id}/konfiguration")
public String konfiguration(@PathVariable("id") long id, Model model) {
    siteDetailModel.populateConfig(id, model);
    return "websites/konfiguration";
}
```
(`Level Locale` parameter is already imported? Add `import java.util.Locale;`.)

**`messages.properties` additions** (used by the new templates):
```
ui.websites.detail.tab.uebersicht=Übersicht & Feststellungen
ui.websites.detail.tab.laeufe=Prüfläufe
ui.websites.detail.tab.konfiguration=Konfiguration
ui.websites.detail.status.offene=Offene Feststellungen
ui.websites.detail.status.alle=Zu allen Feststellungen →
ui.websites.detail.status.letzte=Letzte Prüfung
ui.websites.detail.status.noch_nie=Noch keine Prüfung.
ui.websites.detail.status.dauer=Dauer: {0}
ui.websites.detail.status.naechste=Nächste Prüfung
ui.websites.detail.status.keine=Keine geplant.
ui.websites.detail.status.wichtigste=Neueste Feststellungen
ui.websites.detail.status.details=Details →
ui.websites.detail.status.keine_festst=Keine Feststellungen vorhanden.
```
Confirm all three `ui.trafficlight.*` keys exist (they do, messages.properties:280–283).

- [ ] **Step 4: Run the tests — verify they PASS**
  `./mvnw test -Dtest=SiteDetailControllerTest` → PASS. Also run the other unaffected tests for this class bundle:
  `./mvnw test -Dtest=SiteDetailControllerTest,CheckSettingsControllerTest,RecipientControllerTest,CredentialControllerTest,ScheduleControllerTest` → all PASS (form controllers still target `websites/detail`, which still exists).

- [ ] **Step 5: Commit**
  `git commit -m "feat: split site detail into uebersicht/laeufe/konfiguration tabs"`

---

### Task 5: Repoint config form controllers to the Konfiguration tab

**Files:**
- Modify: `src/main/java/dev/hendrikhoemberg/webtesthelper/web/CheckSettingsController.java` (redirect `→ /konfiguration`; error re-render `websites/konfiguration` + `populateConfig`)
- Modify: `src/main/java/dev/hendrikhoemberg/webtesthelper/web/ScheduleController.java` (redirect + error path)
- Modify: `src/main/java/dev/hendrikhoemberg/webtesthelper/web/RecipientController.java` (redirect + `reject` → konfiguration)
- Modify: `src/main/java/dev/hendrikhoemberg/webtesthelper/web/CredentialController.java` (redirect + `reject` → konfiguration)
- Modify: `src/main/java/dev/hendrikhoemberg/webtesthelper/web/SiteDetailModel.java` (remove `populate()`; keep only the three)
- Delete: `src/main/resources/templates/websites/detail.html`
- Modify tests: `CheckSettingsControllerTest.java`, `ScheduleControllerTest.java`, `RecipientControllerTest.java`, `CredentialControllerTest.java`, `MailRelayAcceptanceTest.java`

**Interfaces:**
- Consumes: `SiteDetailModel.populateConfig(long,Model)`.
- Produces: all config POSTs land/render on `/websites/{id}/konfiguration`.

- [ ] **Step 1: Write the failing tests** — update each controller test:
  - `view().name("websites/detail")` → `view().name("websites/konfiguration")` (in `CheckSettingsControllerTest:153`, `CredentialControllerTest:101,153,178`, `RecipientControllerTest:104,135,148,176,194`, `ScheduleControllerTest:167,220,242`).
  - `redirectedUrl("/websites/<id>")` → `redirectedUrl("/websites/<id>/konfiguration")` (success paths in the same tests; only where the URL is exactly `/websites/{id}` and reflects a config form save).
  - `MailRelayAcceptanceTest:202` `view().name("websites/detail")` → adjust to the actual route the test exercises (likely config). If the test inspects the schedule/recipient panel, point it to the konfiguration route (check the test's GET — update only the view name it asserts).
  - SiteDetailModel no longer has `populate()`; the error-path tests must also register the new model attributes the fragment error handling needs (`email`, `name`, `benutzername`, `recipientError`, `credentialError`) — these are set by the controllers themselves, so the mock build may need `checkRegistry.categories()` and `checkRegistry.category(...)` stubs.

- [ ] **Step 2: Run the failing tests — verify they FAIL**
  `./mvnw test -Dtest=CheckSettingsControllerTest,ScheduleControllerTest,RecipientControllerTest,CredentialControllerTest,MailRelayAcceptanceTest` → FAIL (view/redirect names differ; `populate` gone).

- [ ] **Step 3: Write minimal implementation**
  In each controller: replace the success `return "redirect:/websites/" + id;` with
  `return "redirect:/websites/" + id + "/konfiguration";` and replace `return "websites/detail";`
  with `return "websites/konfiguration";` and `siteDetailModel.populate(...)` with
  `siteDetailModel.populateConfig(...)`. Then delete `populate()` from `SiteDetailModel` and
  delete `websites/detail.html`. (The `detail.html` content already lives in the new templates.)

- [ ] **Step 4: Run the tests — verify they PASS**
  `./mvnw test -Dtest=CheckSettingsControllerTest,ScheduleControllerTest,RecipientControllerTest,CredentialControllerTest,MailRelayAcceptanceTest` → PASS.

- [ ] **Step 5: Commit**
  `git commit -m "feat: route config form submissions to the konfiguration tab"`

---

### Task 6: Verification

- [ ] **Step 1:** `./mvnw test -Pfast` → all pass (web/domain gate).
- [ ] **Step 2:** If any change touched runner/crawler/checks resource paths, run `./mvnw test` (full, ~6 min) — expected mostly unnecessary since the crawler is untouched; a full run validates the digest/browser acceptance tests against the new labels.
- [ ] **Step 3:** Confirm no `rg "websites/detail" src/` matches remain and no `rg "populate\("` in controllers other than the three new methods.

---

## Self-Review

- **Spec coverage (§4–§10):** routes+templates → Tasks 4; health wiring (TrafficLight/OpenFindingCounts/nextRun/topFindings) → Task 4; check categories → Tasks 1+4; header declutter (⋯ menu) → Task 4 (`site-kopf`); terminology → Task 3; 404 fix → Task 2; form redirects → Task 5. **Gaps:** the review's "Benutzerabläufe" link is kept (in ⋯ menu, Task 4); journeys remain reachable. `SetupController` stays as-is (its `/websites/{id}` already → overview).
- **Placeholder scan:** The `error` handling in Task 2 explicitly says "pick whichever reproduces friendly body" — this is a decision point, not a placeholder; the failing test pins the desired output. Template markup references already-existing `ui.*` keys and the reused check tile block is pointed at old `detail.html` lines to copy.
- **Type consistency:** `populateOverview/Runs/Config(long, Model[, Locale])`, `CheckRegistry.category/categories()`, `CheckRowView(..., CheckCategory)`, `TrafficLight.of(boolean, LastRun, OpenFindingCounts)`, `FindingQuery(siteId, Set, Set, null, Set, 1, 5)`, `FindingViewFactory.of(Finding, Locale)` — all match the sources read.
