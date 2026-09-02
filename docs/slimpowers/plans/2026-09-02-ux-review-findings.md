# UX Review Findings — Implementation Plan

**Goal:** Fix the four actionable findings from the non-technical-user UX review: (1) empty dashboard welcome card, (2) journeys as a first-class 4th tab, (3) `/sites/…` → `/websites/…` route consistency, (4) duplicate "Lauf abbrechen" buttons.

**Architecture:** Four independent UI changes in the Thymeleaf view layer plus small controller route renames. No domain/service changes. The journeys tab links to the renamed route, so the route rename (Task 1) lands first; the tab work (Task 2), welcome card (Task 3), and cancel-button consolidation (Task 4) build on top and are otherwise independent.

**Tech Stack:** Spring Boot modular monolith, Thymeleaf + HTMX + Alpine, Spring Security (thymeleaf-extras `sec:`), `messages.properties` for all German copy. Tests are `@WebMvcTest` + MockMvc asserting rendered text/markup.

**Spec:** n/a — driven directly by the review. Decisions confirmed with stakeholder: tab label **„Abläufe"**, journey pages **also** get the tab bar, **hard rename** (no legacy `/sites/` redirects).

## Global Constraints

- German-only UI; new message keys are `ui.*`; no internal identifiers (enum names, `{0}` placeholders, raw ISO instants) in rendered HTML.
- View tests assert text/markup (`containsString`), not CSS.
- Desktop-only; no mobile breakpoints.
- Verify gate: `./mvnw test -Pfast` (these changes touch templates, `messages.properties`, controllers → the default gate per `AGENTS.md`).
- Single-test command: `./mvnw test -Dtest=<Class>`.

---

## Task 1: Rename journey routes `/sites/{siteId}/journeys*` → `/websites/{siteId}/journeys*`

Routes become consistent with every other website sub-path (`/websites/{id}/laeufe`, `/konfiguration`, `/befunde`, …). No security change needed: `SecurityConfig.java:41` already covers GET journeys via `anyRequest().authenticated()`.

**Files:**
- Modify: `src/main/java/dev/hendrikhoemberg/webtesthelper/web/JourneyController.java` — two GET mappings.
- Modify: `src/main/java/dev/hendrikhoemberg/webtesthelper/web/JourneyEditController.java` — two mappings + one redirect string.
- Modify: `src/main/java/dev/hendrikhoemberg/webtesthelper/recorder/RecorderController.java` — two redirect strings.
- Modify: `src/main/resources/templates/fragments/site-kopf.html` — the ⋯ menu journeys link.
- Modify: `src/main/resources/templates/journey/list.html`, `journey/detail.html`, `journey/edit.html`, `journey/record.html` — breadcrumbs + action links.
- Test: `src/test/java/…/web/JourneyControllerTest.java`, `…/web/JourneyEditControllerTest.java`, `…/recorder/RecorderControllerTest.java`.

**Interfaces:**
- Consumes: none.
- Produces: journey routes reachable only under `/websites/{siteId}/journeys*`; all internal links and redirects point there.

- [ ] **Step 1: Update the tests to the new routes (failing)**

  In `JourneyControllerTest.java` replace every `/sites/1/journeys` with `/websites/1/journeys` (lines 92, 102, 103, 125, 140, 183, 234, 264, 292, 311, 321, 327, 331). Example changes:

  ```java
  mvc.perform(get("/websites/1/journeys"))
  // …
  .andExpect(content().string(containsString("/websites/1/journeys/10")))
  .andExpect(content().string(containsString("/websites/1/journeys/20")))
  ```

  In `JourneyEditControllerTest.java` replace every `get("/sites/1/journeys/10/bearbeiten")` → `get("/websites/1/journeys/10/bearbeiten")`, every `post("/sites/1/journeys/10/bearbeiten")` → `post("/websites/1/journeys/10/bearbeiten")`, and every `redirectedUrl("/sites/1/journeys/10")` → `redirectedUrl("/websites/1/journeys/10")`.

  In `RecorderControllerTest.java` replace `redirectedUrl("/sites/1/journeys")` (line 152) → `redirectedUrl("/websites/1/journeys")` and `redirectedUrl("/sites/1/journeys/42/bearbeiten")` (line 234) → `redirectedUrl("/websites/1/journeys/42/bearbeiten")`.

- [ ] **Step 2: Run the three tests — verify they FAIL**

  `./mvnw test -Dtest=JourneyControllerTest,JourneyEditControllerTest,RecorderControllerTest`
  Expected: FAIL — the new `/websites/…` URLs return 404 (controllers still map `/sites/…`).

- [ ] **Step 3: Rename routes in controllers + templates**

  `JourneyController.java`:

  ```java
  @GetMapping("/websites/{siteId}/journeys")
  public String list(@PathVariable("siteId") long siteId, Model model) { … }

  @GetMapping("/websites/{siteId}/journeys/{journeyId}")
  public String detail(@PathVariable("siteId") long siteId, …) { … }
  ```

  `JourneyEditController.java`:

  ```java
  @GetMapping("/websites/{siteId}/journeys/{journeyId}/bearbeiten")
  @PostMapping("/websites/{siteId}/journeys/{journeyId}/bearbeiten")
  // …
  return "redirect:/websites/" + siteId + "/journeys/" + journeyId;   // line 126
  ```

  `RecorderController.java` (lines 98 and 119):

  ```java
  return "redirect:/websites/" + siteId + "/journeys";
  return "redirect:/websites/" + siteId + "/journeys/" + journeyId + "/bearbeiten";
  ```

  Templates — replace `@{/sites/{siteId}/journeys…}` / `@{/sites/{id}/journeys…}` with `/websites/`:

  - `site-kopf.html:37`: `@{/websites/{id}/journeys(id=${site.siteId})}`
  - `journey/list.html:68,96`: `@{/websites/{siteId}/journeys/{journeyId}(siteId=${site.siteId}, journeyId=${journey.id})}`
  - `journey/detail.html:19`: `@{/websites/{siteId}/journeys(siteId=${site.siteId})}`; `:29`: `@{/websites/{siteId}/journeys/{journeyId}/bearbeiten(siteId=${site.siteId},journeyId=${journey.id})}`; `:30`: `@{/websites/{siteId}/journeys(siteId=${site.siteId})}`
  - `journey/edit.html:19,21,31,32,41,158`: `@{/websites/{siteId}/journeys…}` (same siteId/journeyId params as today, prefix `/sites/` → `/websites/`)
  - `journey/record.html:19,37,49`: `@{/websites/{siteId}/journeys(siteId=${site.siteId})}` and `@{/websites/{id}/journeys(id=${site.siteId})}`

- [ ] **Step 4: Run the three tests — verify they PASS**

  `./mvnw test -Dtest=JourneyControllerTest,JourneyEditControllerTest,RecorderControllerTest`
  Expected: PASS.

- [ ] **Step 5: Commit**

  `git commit -m "refactor(web): move journey routes under /websites/{id}/journeys"`

---

## Task 2: "Abläufe" as a 4th tab + tab bar on journey pages

Remove the journeys entry from the ⋯ dropdown; add a `journeys` tab to `site-tabs.html`; inject the tab bar into the four journey pages so the active tab stays visible after navigating.

**Files:**
- Modify: `src/main/resources/templates/fragments/site-tabs.html` — add 4th tab.
- Modify: `src/main/resources/templates/fragments/site-kopf.html` — remove the ⋯ journeys link.
- Modify: `src/main/resources/messages.properties` — add `ui.websites.detail.tab.journeys`, remove now-unused `ui.websites.detail.journeys`.
- Modify: `src/main/resources/templates/journey/list.html`, `journey/detail.html`, `journey/edit.html`, `journey/record.html` — insert tab bar.
- Test: `src/test/java/…/web/SiteDetailControllerTest.java`.

**Interfaces:**
- Consumes: `tabs(site, aktiv)` fragment (exists); route from Task 1.
- Produces: the journeys tab visible on every website sub-page; the ⋯ menu holds only Einrichtung/Bearbeiten/Löschen.

- [ ] **Step 1: Write the failing test**

  In `SiteDetailControllerTest.java`, add a test (and extend the existing overview test) that asserts the 4th tab renders and the legacy `/sites/` link is gone:

  ```java
  @Test
  @WithMockUser(roles = "USER")
  void getUebersichtRendersJourneysTabAndDropsTheSitesRoute() throws Exception {
      stubCommon();

      mvc.perform(get("/websites/42"))
              .andExpect(status().isOk())
              .andExpect(content().string(containsString("/websites/42/journeys")))
              .andExpect(content().string(containsString("Abläufe")))
              .andExpect(content().string(not(containsString("/sites/42/journeys"))));
  }
  ```

  Also add `containsString("/websites/42/journeys")` to `getUebersichtRendersHealthCardsAndTopFindings` (near the existing `:180-181` laeufe/konfiguration assertions).

- [ ] **Step 2: Run the single test — verify it FAILS**

  `./mvnw test -Dtest=SiteDetailControllerTest`
  Expected: FAIL — `/websites/42/journeys` not present (no tab yet).

- [ ] **Step 3: Implement**

  `fragments/site-tabs.html` — insert between Prüfläufe and Konfiguration:

  ```html
  <a th:classappend="${aktiv == 'journeys'} ? 'aktiv' : ''" th:href="@{/websites/{id}/journeys(id=${site.siteId})}"
     role="tab" th:text="#{ui.websites.detail.tab.journeys}">Abläufe</a>
  ```

  `fragments/site-kopf.html` — delete the journeys `<a>` from the ⋯ menu (current lines 37–38), leaving Einrichtung erneut / Bearbeiten / Löschen.

  `messages.properties` — replace the `ui.websites.detail.journeys` line with:

  ```properties
  ui.websites.detail.tab.journeys=Abläufe
  ```

  `journey/list.html`, `journey/detail.html`, `journey/edit.html`, `journey/record.html` — insert immediately after the closing `</header>` (list.html after line 33, detail.html after line 33, edit.html after its `</header>`, record.html after line 40):

  ```html
  <div th:replace="~{fragments/site-tabs :: tabs(${site}, 'journeys')}"></div>
  ```

  (All four templates already carry `site` in the model — list/detail/edit via the controllers, record via `RecorderController`.)

- [ ] **Step 4: Run the single test — verify it PASSES**

  `./mvnw test -Dtest=SiteDetailControllerTest`
  Expected: PASS.

- [ ] **Step 5: Commit**

  `git commit -m "feat(web): promote journeys to a first-class website tab"`

---

## Task 3: Empty-dashboard welcome card

Replace the bare „Noch keine Websites vorhanden." empty state with a welcome card (title, 3-step explanation, admin CTA → `/websites/neu`) inside the polled tiles fragment so it auto-cleans when the first site is created.

**Files:**
- Modify: `src/main/resources/templates/fragments/kacheln.html` — welcome card (add `xmlns:sec`).
- Modify: `src/main/resources/messages.properties` — welcome-copy keys; remove now-unused `ui.uebersicht.leer`.
- Test: `src/test/java/…/web/DashboardControllerTest.java`.

**Interfaces:**
- Consumes: `DashboardView.tiles()` empty list signals the empty state.
- Produces: a friendly, action-oriented first-run dashboard for admins; explanatory copy (no CTA) for non-admins.

- [ ] **Step 1: Write the failing tests**

  In `DashboardControllerTest.java` add (reusing `new SystemCapacity(2,1,4,1,Duration.ofSeconds(30),5)`):

  ```java
  @Test
  @WithMockUser(roles = "ADMIN")
  void emptyDashboardRendersWelcomeCardAndAdminCta() throws Exception {
      when(dashboardService.overview()).thenReturn(new DashboardView(
              List.of(), new OpenFindingCounts(0, 0, 0, 0), 0, null, false,
              new SystemCapacity(2, 1, 4, 1, Duration.ofSeconds(30), 5)));

      mvc.perform(get("/"))
              .andExpect(status().isOk())
              .andExpect(content().string(containsString("Willkommen bei WebTestHelper")))
              .andExpect(content().string(containsString("/websites/neu")));
  }

  @Test
  @WithMockUser(roles = "USER")
  void emptyDashboardHidesTheAdminCtaFromNonAdmins() throws Exception {
      when(dashboardService.overview()).thenReturn(new DashboardView(
              List.of(), new OpenFindingCounts(0, 0, 0, 0), 0, null, false,
              new SystemCapacity(2, 1, 4, 1, Duration.ofSeconds(30), 5)));

      mvc.perform(get("/"))
              .andExpect(status().isOk())
              .andExpect(content().string(containsString("Willkommen bei WebTestHelper")))
              .andExpect(content().string(not(containsString("/websites/neu"))))
              .andExpect(content().string(containsString("Administrator")));
  }
  ```

- [ ] **Step 2: Run the single test — verify it FAILS**

  `./mvnw test -Dtest=DashboardControllerTest`
  Expected: FAIL — empty tiles currently render only „Noch keine Websites vorhanden.".

- [ ] **Step 3: Implement**

  `fragments/kacheln.html` — change `<html xmlns:th=…>` to add the security namespace, and replace the empty-state `<div>` (lines 9–11) with:

  ```html
  <div th:if="${#lists.isEmpty(uebersicht.tiles)}" class="card-box willkommen-karte"
       style="grid-column: 1 / -1; text-align: center; padding: 3rem 1.5rem;">
      <h2 th:text="#{ui.uebersicht.leer.titel}" style="margin-top: 0;">Willkommen bei WebTestHelper</h2>
      <p class="hinweis-text" style="color: var(--text-muted); margin: 0 0 1.5rem;"
         th:text="#{ui.uebersicht.leer.untertitel}">Überwachen Sie Ihre Websites automatisch – in drei Schritten.</p>
      <ol style="list-style: none; padding: 0; display: flex; gap: 1.5rem; justify-content: center; flex-wrap: wrap; margin: 0 0 1.75rem;">
          <li style="max-width: 16rem;">
              <strong th:text="#{ui.uebersicht.leer.schritt1.titel}">Website anlegen</strong>
              <p class="hinweis-text" style="color: var(--text-muted); margin: 0.25rem 0 0;"
                 th:text="#{ui.uebersicht.leer.schritt1.text}">Name und Adresse genügen – den Rest erledigt die automatische Erkennung.</p>
          </li>
          <li style="max-width: 16rem;">
              <strong th:text="#{ui.uebersicht.leer.schritt2.titel}">Automatische Erkennung</strong>
              <p class="hinweis-text" style="color: var(--text-muted); margin: 0.25rem 0 0;"
                 th:text="#{ui.uebersicht.leer.schritt2.text}">WebTestHelper untersucht die Website und schlägt sinnvolle Prüfungen vor.</p>
          </li>
          <li style="max-width: 16rem;">
              <strong th:text="#{ui.uebersicht.leer.schritt3.titel}">Erster Prüflauf</strong>
              <p class="hinweis-text" style="color: var(--text-muted); margin: 0.25rem 0 0;"
                 th:text="#{ui.uebersicht.leer.schritt3.text}">Der erste Lauf legt den Ausgangsbestand fest – danach sehen Sie nur noch Veränderungen.</p>
          </li>
      </ol>
      <a sec:authorize="hasRole('ADMIN')" th:href="@{/websites/neu}" class="btn-ui btn-ui-primary button primär"
         th:text="#{ui.uebersicht.leer.cta}">Erste Website anlegen</a>
      <p sec:authorize="not hasRole('ADMIN')" class="hinweis-text" style="color: var(--text-muted);"
         th:text="#{ui.uebersicht.leer.keine_rechte}">Bitte wenden Sie sich an eine Administratorin oder einen Administrator, um die erste Website anzulegen.</p>
  </div>
  ```

  `messages.properties` — remove `ui.uebersicht.leer=Noch keine Websites vorhanden.` (line 686, now unused) and add:

  ```properties
  ui.uebersicht.leer.titel=Willkommen bei WebTestHelper
  ui.uebersicht.leer.untertitel=Überwachen Sie Ihre Websites automatisch – in drei Schritten.
  ui.uebersicht.leer.schritt1.titel=Website anlegen
  ui.uebersicht.leer.schritt1.text=Name und Adresse genügen – den Rest erledigt die automatische Erkennung.
  ui.uebersicht.leer.schritt2.titel=Automatische Erkennung
  ui.uebersicht.leer.schritt2.text=WebTestHelper untersucht die Website und schlägt sinnvolle Prüfungen vor.
  ui.uebersicht.leer.schritt3.titel=Erster Prüflauf
  ui.uebersicht.leer.schritt3.text=Der erste Lauf legt den Ausgangsbestand fest – danach sehen Sie nur noch Veränderungen.
  ui.uebersicht.leer.cta=Erste Website anlegen
  ui.uebersicht.leer.keine_rechte=Bitte wenden Sie sich an eine Administratorin oder einen Administrator, um die erste Website anzulegen.
  ```

- [ ] **Step 4: Run the single test — verify it PASSES**

  `./mvnw test -Dtest=DashboardControllerTest`
  Expected: PASS.

- [ ] **Step 5: Commit**

  `git commit -m "feat(web): welcome card on the empty dashboard"`

---

## Task 4: Consolidate the duplicate "Lauf abbrechen" actions

Keep the progress card's button as the single trigger; turn the standalone section into a hidden-only confirmation panel (remove its heading + toggle button). The panel stays in `laeufe/detail.html` (not the 3s-polled fragment) so it never resets mid-confirmation.

**Files:**
- Modify: `src/main/resources/templates/laeufe/detail.html` — strip heading + toggle from the cancel section.
- Modify: `src/main/resources/messages.properties` — remove now-unused `ui.lauf.abbrechen.titel`.
- Test: `src/test/java/…/web/RunControllerTest.java`.

**Interfaces:**
- Consumes: the `abbrechen-offen` Alpine event dispatched by `fragments/fortschritt.html`.
- Produces: exactly one visible „Lauf abbrechen" action; the confirmation panel still POSTs `/laeufe/{id}/abbrechen`.

- [ ] **Step 1: Write the failing test**

  In `RunControllerTest.java`, strengthen `queuedRunDetailRendersTheCancelPanel` to assert exactly one visible button, and add a counting helper:

  ```java
  @Test
  @WithMockUser(roles = "USER")
  void queuedRunDetailRendersTheCancelPanel() throws Exception {
      long runId = 110L;
      long siteId = 42L;
      when(runService.summary(runId)).thenReturn(sampleSummary(runId, siteId, RunStatus.QUEUED, false, false, null));
      when(siteService.contextFor(siteId)).thenReturn(sampleSite(siteId));

      mvc.perform(get("/laeufe/" + runId))
              .andExpect(status().isOk())
              .andExpect(content().string(containsString("Lauf abbrechen")))
              .andExpect(content().string(containsString("Jetzt abbrechen")))
              .andExpect(content().string(containsString("x-data=\"{ offen: false }\"")))
              .andExpect(result -> {
                  String html = result.getResponse().getContentAsString();
                  assertThat(occurrencesOf(html, "Lauf abbrechen")).isEqualTo(1);
              });
  }

  private static long occurrencesOf(String haystack, String needle) {
      int idx = 0;
      long count = 0;
      while ((idx = haystack.indexOf(needle, idx)) != -1) {
          count++;
          idx += needle.length();
      }
      return count;
  }
  ```

  (`assertThat` is already imported in this class via `org.assertj.core.api.Assertions`.)

- [ ] **Step 2: Run the single test — verify it FAILS**

  `./mvnw test -Dtest=RunControllerTest`
  Expected: FAIL — „Lauf abbrechen" appears twice (heading + toggle button in the section, plus the fragment button).

- [ ] **Step 3: Implement**

  `laeufe/detail.html` — replace the current `<section>` (lines 41–59) with a hidden-only panel:

  ```html
  <section th:if="${run.status.name() == 'QUEUED' or run.status.name() == 'RUNNING'}"
           x-data="{ offen: false }" @abbrechen-offen.window="offen = true">
      <div x-show="offen" class="ausgangsbestand-panel" style="display: none; margin-top: 1rem; padding: 1rem; background: var(--status-warning-bg); border: 1px solid var(--status-warning-border); border-radius: 8px;">
          <p th:text="#{ui.lauf.abbrechen.folge}" style="margin-top: 0; color: var(--status-warning-text); font-size: 0.9rem;">
              Der Prüflauf wird gestoppt, sobald der laufende Schritt beendet ist...
          </p>
          <form th:action="@{/laeufe/{id}/abbrechen(id=${run.id})}" method="post" class="inline-form" style="display: flex; gap: 0.5rem;">
              <button type="submit" class="btn-ui btn-ui-danger button primär" th:text="#{ui.lauf.abbrechen.bestaetigen}">Jetzt abbrechen</button>
              <button type="button" @click="offen = false" class="btn-ui btn-ui-secondary button sekundär"
                      th:text="#{ui.lauf.ausgangsbestand.abbrechen}">Abbrechen</button>
          </form>
      </div>
  </section>
  ```

  `messages.properties` — remove `ui.lauf.abbrechen.titel=Lauf abbrechen` (line 455, now unused; the button keeps `ui.lauf.abbrechen.button`).

  `fragments/fortschritt.html` is unchanged (its button remains the single trigger).

- [ ] **Step 4: Run the single test — verify it PASSES**

  `./mvnw test -Dtest=RunControllerTest`
  Expected: PASS — including the unchanged `completedRunDetailDoesNotRenderTheCancelPanel` and `fortschrittForRunningRunOffersTheCancelDispatch`.

- [ ] **Step 5: Commit**

  `git commit -m "fix(web): single cancel-run action in the progress card"`

---

## Final verification

After all four tasks:

- [ ] `./mvnw test -Pfast` — full default gate (templates, `messages.properties`, controllers are all touched; no crawler/runner/checks resource changes, so the default gate applies).

---

## Self-review notes

- **Spec coverage:** review findings 1→Task 3, 2→Task 2, 3→Task 1, 4→Task 4. All covered.
- **Placeholder scan:** none — every step has concrete code/markup.
- **Type consistency:** `tabs(site, aktiv)` signature matches existing fragment (`site` must expose `siteId`, which `SiteContext` does); `DashboardView(List<SiteTile>, OpenFindingCounts, int, Instant, boolean, SystemCapacity)` matches the existing constructor used in `DashboardControllerTest`.
