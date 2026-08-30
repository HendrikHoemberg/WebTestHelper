# Website-Löschung anbinden — Implementation Plan

**Goal:** Websites können im Frontend (Liste und Detailseite) durch Admins gelöscht werden; die vorhandene `SiteService.delete(id)` wird an Controller, Sicherheitsregeln und Templates angeschlossen.

**Architecture:** Ein neuer `POST /websites/{id}/loeschen`-Handler in `SiteController` ruft `siteService.delete(id)` auf und leitet mit Flash-Meldung auf `/websites` um. Die Löschung der abhängigen Zeilen (Credentials, Journeys, Runs, Findings, Mute-Rules, Schedules, Empfänger, Check-Settings) übernimmt das bestehende `ON DELETE CASCADE` in Postgres — dafür ist keine weitere Codebasis-Änderung nötig. Die UI bekommt Lösch-Buttons mit `confirm()` (ADMIN-only, wie „Bearbeiten“).

**Tech Stack:** Spring Boot Modulare Monolith, Thymeleaf + HTMX + Alpine, `messages.properties` (deutsch), `@WebMvcTest` + MockMvc für View-Tests, `AbstractPostgresTest` (Testcontainers) für Akzeptanztests.

**Spec:** `help/zugangsdaten.md` („Wird eine Website gelöscht, werden auch alle zugehörigen Zugangsdaten automatisch entfernt.“), `help/reisen.md` analog für Abläufe; UI-Konventionen aus AGENTS.md.

## Global Constraints

- Deutsche UI-Texte nur über `ui.*`-Keys in `messages.properties`; keine Klartext-Literale in Templates außerhalb `th:text="#{…}"`.
- Löschen nur für ADMIN (wie Anlegen/Bearbeiten): SecurityConfig-Neuzuordnung UND `sec:authorize` im Template.
- View-Tests: `@WebMvcTest` + MockMvc, Assertions auf Text/Markup; Akzeptanztests mit echtem Postgres.
- `SiteService.delete(id)` bleibt unverändert; keine neuen Service-Methoden benötigt.

---

### Task 1: Lösch-Endpoint mit Flash-Meldung

**Files:**
- Modify: `src/main/java/dev/hendrikhoemberg/webtesthelper/web/SiteController.java` — neuer `POST /websites/{id}/loeschen`-Handler; Konstruktor bekommt `MessageSource`.
- Modify: `src/main/java/dev/hendrikhoemberg/webtesthelper/web/SecurityConfig.java` — `/websites/*/loeschen` in die ADMIN-POST-Matcherliste.
- Modify: `src/main/resources/messages.properties` — neue Keys `ui.websites.geloescht`.
- Test: `src/test/java/dev/hendrikhoemberg/webtesthelper/web/SiteControllerTest.java`

**Interfaces:**
- Consumes: `SiteService.delete(long)`, `SiteService.summary(long)` (für den Namen der Flash-Meldung), `MessageSource.getMessage(...)`.
- Produces: POST `/websites/{id}/loeschen` → 302 `/websites` (ADMIN) bzw. 403 (USER), Flash-Key `flashMessage`.

- [ ] **Step 1: Failing tests** — in `SiteControllerTest`:
  1. `postWebsitesDeleteAsUserIsForbidden`: USER + csrf POST `/websites/42/loeschen` → `status().isForbidden()`; `verify(siteService, never()).delete(anyLong());`
  2. `postWebsitesDeleteAsAdminDeletesAndRedirects`: `when(siteService.summary(42L)).thenReturn(new SiteSummary(42L, "Kunde A", "https://example.com/", true, 0))`; ADMIN + csrf POST `/websites/42/loeschen` → 302, `redirectedUrl("/websites")`; `verify(siteService).delete(42L);` und `flash().attribute("flashMessage", containsString("Kunde A"))`.
  Imports: `org.hamcrest.Matchers.containsString`, `MockMvcResultMatchers.flash`, `verify(siteService, never())`, `anyLong()`.
- [ ] **Step 2: `./mvnw test -Dtest=SiteControllerTest`** — erwartet: FAIL (Endpoint fehlt → POST wird von keinem Handler behandelt, 405/403-Verhalten weicht ab; Test 1 könnte zufällig grün werden, Test 2 schlägt sicher fehl).
- [ ] **Step 3: Implementierung** — in `SiteController`:

```java
@PostMapping("/websites/{id}/loeschen")
public String delete(@PathVariable("id") long id,
                     RedirectAttributes redirectAttributes,
                     Locale locale) {
    String name = siteService.summary(id).name();
    siteService.delete(id);
    String successMsg = messageSource.getMessage(
            "ui.websites.geloescht", new Object[]{name}, locale);
    redirectAttributes.addFlashAttribute("flashMessage", successMsg);
    return "redirect:/websites";
}
```

Konstruktor: Feld `private final MessageSource messageSource;`, Param ergänzen. Imports: `Locale`, `MessageSource`, `RedirectAttributes`.
SecurityConfig — in die ADMIN-POST-Liste (Zeile 31-33) nach `"/websites/*"` einfügen: `"/websites/*/loeschen"`.
messages.properties (nach Zeile 187, `ui.websites.aktionen.bearbeiten`):
```
ui.websites.geloescht=Website „{0}“ wurde gelöscht.
```
- [ ] **Step 4: `./mvnw test -Dtest=SiteControllerTest`** — erwartet: PASS.
- [ ] **Step 5: Commit** — `feat(web): Website-Löschung über POST-Endpoint anbinden` (nur wenn vom Nutzer erbeten).

---

### Task 2: Lösch-Buttons + Flash-Anzeige in Liste und Detailseite

**Files:**
- Modify: `src/main/resources/templates/websites/liste.html` — Flash-Div nach `<header>`, Lösch-Button in der Spalte „Aktionen“.
- Modify: `src/main/resources/templates/websites/detail.html` — Lösch-Button in `seiten-kopf-aktionen`.
- Modify: `src/main/resources/messages.properties` — `ui.websites.loeschen`, `ui.websites.loeschen.bestaetigung`.
- Test: `src/test/java/dev/hendrikhoemberg/webtesthelper/web/SiteControllerTest.java` (Liste), `src/test/java/dev/hendrikhoemberg/webtesthelper/web/SiteDetailControllerTest.java` (Detail).

**Interfaces:**
- Consumes: Route `/websites/{id}/loeschen` (Task 1), `#{ui.websites.loeschen}`, `#{ui.websites.loeschen.bestaetigung}`.
- Produces: Für ADMIN sichtbare Lösch-Buttons mit `confirm()`; Flash-Meldung auf `/websites`; keine Lösch-Affordance für USER.

- [ ] **Step 1: Failing tests**
  - `SiteControllerTest` neu: `siteListOffersDeleteToAdmin` — ADMIN GET `/websites` mit Mock `summaries()` → `content().string(containsString("/websites/42/loeschen"))` und `containsString("Möchten Sie diese Website wirklich löschen?")`. `siteListHidesDeleteFromUser` — USER → `not(containsString("/websites/42/loeschen"))`.
  - `SiteDetailControllerTest` neu: `getSiteDetailOffersTheDeleteButtonToAnAdmin` — ADMIN GET `/websites/42` (gleiche Stubs wie bestehende Tests) → `content().string(containsString("/websites/42/loeschen"))`; und in bestehendem Test `adminOnlyAffordancesAreHiddenFromAUserAndTheSecNamespaceIsNeverEmitted` zusätzlich `.andExpect(content().string(not(containsString("/websites/42/loeschen"))))`.
- [ ] **Step 2: `./mvnw test -Dtest=SiteControllerTest,SiteDetailControllerTest`** — erwartet: FAIL (Buttons fehlen).
- [ ] **Step 3: Implementierung**
  messages.properties (nach `ui.websites.geloescht`):
  ```
  ui.websites.loeschen=Löschen
  ui.websites.loeschen.bestaetigung=Möchten Sie diese Website wirklich löschen? Abläufe, Zeitpläne, Zugangsdaten, Empfänger und der gesamte Prüfverlauf werden ebenfalls entfernt.
  ```
  `liste.html` — direkt nach `</header>` (Zeile 13):
  ```html
  <div th:if="${flashMessage}" class="erfolgs-meldung" role="status" th:text="${flashMessage}"></div>
  ```
  In der „Aktionen“-Zelle (Zeile 42-44) nach dem Bearbeiten-Link:
  ```html
  <form sec:authorize="hasRole('ADMIN')" th:action="@{/websites/{id}/loeschen(id=${site.id})}" method="post" class="inline-form">
      <button type="submit" class="button link-loeschen"
              th:attr="onclick='return confirm(\'' + #{ui.websites.loeschen.bestaetigung} + '\')'"
              th:text="#{ui.websites.loeschen}">Löschen</button>
  </form>
  ```
  `detail.html` — in `seiten-kopf-aktionen` nach dem Bearbeiten-Link (Zeile 22):
  ```html
  <form sec:authorize="hasRole('ADMIN')" th:action="@{/websites/{id}/loeschen(id=${site.siteId})}" method="post" class="inline-form">
      <button type="submit" class="button link-loeschen"
              th:attr="onclick='return confirm(\'' + #{ui.websites.loeschen.bestaetigung} + '\')'"
              th:text="#{ui.websites.loeschen}">Löschen</button>
  </form>
  ```
- [ ] **Step 4: `./mvnw test -Dtest=SiteControllerTest,SiteDetailControllerTest`** — erwartet: PASS.
- [ ] **Step 5: Commit** — `feat(ui): Lösch-Buttons für Websites in Liste und Detail` (nur wenn vom Nutzer erbeten).

---

### Task 3: Akzeptanz — Kaskade und Rollen (echtes Postgres)

**Files:**
- Create: `src/test/java/dev/hendrikhoemberg/webtesthelper/web/SiteDeletionAcceptanceTest.java`
- Test: dieselbe Datei.

**Interfaces:**
- Consumes: `@AutoConfigureMockMvc` + `AbstractPostgresTest`; `SiteService`, `CredentialService`/jdbc, `MuteRuleService`, `FindingService`, `RunRepository`, `ScheduleService`, `JdbcTemplate`.
- Produces: Nachweis, dass Website-Löschung Credentials, Runs, Findings, site-gebundene Mute-Rules, Schedules und Empfänger entfernt, globale Mute-Rules überleben, und USER 403 bekommen.

- [ ] **Step 1: Failing tests** — `SiteDeletionAcceptanceTest`:
  - `@BeforeEach`: Reihenfolge-feste Cleanups `DELETE FROM notification`, `notification_recipient`, `crawl_queue_item`, `finding_occurrence`, `finding`, `mute_rule`, `run`, `schedule`, `site_check_setting`, `credential`, `journey`, `site`. Danach Site A anlegen (`siteService.create`), `scheduleService.seedDefaults`, `credential`-Zeile per jdbc (`INSERT INTO credential (site_id, name, username, secret) VALUES (?, ?, 'alice@example.com', 'cipher')`), `notification_recipient` per jdbc, ein RUN + Finding wie im Helper von `MuteRuleControllerTest`, site-gebundene Mute-Rule via `muteRuleService.create`, globale Mute-Rule ebenfalls.
  - `adminDeletingSiteRemovesAllRelatedRows`: ADMIN + csrf POST `/websites/{siteId}/loeschen` → 302 `/websites`. Danach: jdbc-Zähler für `credential`, `notification_recipient`, `run`, `finding`, `mute_rule WHERE site_id IS NOT NULL`, `schedule` = 0; `mute_rule WHERE site_id IS NULL` = 1; `site` = 0. Dann GET `/websites` → `content().string(containsString("wurde gelöscht"))`, `content().string(containsString(siteName))` (Flash), `content().string(not(containsString(">Test Kunde<")))` hmm — besser: Liste enthält die Website nicht mehr: `mvc.get("/websites")` → NOT `containsString(siteName)` wegen Flash? Flash enthält den Namen! Also Aspekt: Flash zeigt unter anderem den Namen. Design: Flash-Text „Website „{0}“ wurde gelöscht.“ — dann enthält die Liste den Namen im Flash. Assertion: `containsString("wurde gelöscht")` reicht; für „nicht mehr erscheint“ prüfe, dass kein Tabellen-Link `/websites/{siteId}` mehr in der Liste ist. Einfacher: `not(containsString("/websites/" + siteId))`.
  - `userDeletingWebsiteIsForbidden`: USER + csrf → 403; `jdbc`-Zähler `site` = 1; `credential` = 1.
- [ ] **Step 2: `./mvnw test -Dtest=SiteDeletionAcceptanceTest`** — erwartet: FAIL (Endpoint existiert erst nach Task 1+2; 403/302-Verhalten stimmt, Kaskade nicht prüfbar).
- [ ] **Step 3: Implementierung** — Testcode wie oben; keine Produktionsänderung nötig (Endpoints/Kaskade aus Tasks 1-2).
- [ ] **Step 4: `./mvnw test -Dtest=SiteDeletionAcceptanceTest`** — erwartet: PASS.
- [ ] **Step 5: Commit** — `feat(web): Akzeptanztest Website-Löschung mit Kaskaden` (nur wenn vom Nutzer erbeten).

---

## Self-Review

1. **Spec-Abdeckung:** Hilfe-Text verspricht Löschung samt Credentials → Task 3 verifiziert Kaskade; Frontend-Anbindung → Tasks 1-2; Rollen → Task 1+3. Keine Lücken.
2. **Platzhalter-Scan:** Keine TBD/TODO; alle Steps enthalten konkrete Codes/Assertions.
3. **Typprüfung:** `SiteService.delete(long)`, `summary(long)` existieren; `flash`-Attribut-Name `flashMessage` wie in `MuteRuleController`; Route `POST /websites/{id}/loeschen` konsistent in Controller + SecurityConfig + Templates (eine Stelle je Task definiert).
