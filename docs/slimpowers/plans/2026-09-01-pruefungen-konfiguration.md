# Prüfungen-Konfiguration (An/Aus + Schweregrad) Implementation Plan

**Goal:** Auf der Website-Detailseite werden die Prüfungen pro Website konfigurierbar (aktiv/inaktiv + Schweregrad-Override), der Hinweis „…erfolgt in einer späteren Ausbaustufe“ entfällt.

**Architecture:** Die Persistenz existiert bereits (`site_check_setting` mit `enabled`, `severity_override`, `config`; V3-Migration). Neu sind ein Service-Setter für enabled+severity in `catalog`, ein POST-Endpoint mit Form-Binding nach dem `SetupForm`-Muster, ein Formular in `websites/detail.html` (Abschnitt „Aktive Prüfungen“ wird zum Formular) und die dazugehörigen deutschen Meldungen. Alle angemeldeten Rollen dürfen speichern (wie die Einrichtungs-Bestätigung); es ist **keine** Änderung an `SecurityConfig` nötig, da `anyRequest().authenticated()` den neuen POST-Route bereits abdeckt.

**Tech Stack:** Spring Boot 3, Thymeleaf, JUnit 5 + MockMvc (`@WebMvcTest`), Postgres (Testcontainers via `AbstractPostgresTest`), Maven (`./mvnw test`).

**Spec:** Brainstorming-Design, vom Nutzer freigegeben am 2026-09-01 (Umfang: An/Aus + Schweregrad; UI direkt auf der Detailseite; alle Rollen dürfen bearbeiten).

## Global Constraints

- Deutsche UI-Texte ausschließlich über `messages.properties`, Keys `ui.*`; interne Bezeichner (Enum-Namen, Roh-Cron, ISO-Instants) nie als sichtbare Labels — als Form-*Werte* erlaubt (Muster: `SetupForm`, `SetupCheckView`, §13.1).
- View-Tests: `@WebMvcTest` + MockMvc, Assertions auf Text/Markup, nicht auf CSS.
- Journey-Typen (`JOURNEY_STEP_FAILED`, `SELECTOR_DRIFT`) sind nicht im `CheckRegistry` und werden vom Formular nicht berührt; Konfiguration gilt nur für die 17 Registry-Prüfungen.
- Worker-Pool-Größen (0/2/4) und `data/`, `target/`, `.env`, `compose.yaml` nicht anfassen.

---

### Task 1: `SiteService.updateCheckSetting` (catalog)

**Files:**
- Modify: `src/main/java/dev/hendrikhoemberg/webtesthelper/catalog/SiteService.java` — neue Methode `updateCheckSetting(siteId, type, enabled, severityOverride)`; import `dev.hendrikhoemberg.webtesthelper.model.Severity`.
- Test: `src/test/java/dev/hendrikhoemberg/webtesthelper/catalog/SiteServiceTest.java` — zwei neue Tests.

**Interfaces:**
- Consumes: `SiteCheckSettingRepository.findBySiteIdAndCheckType`, `requireSite`.
- Produces: `public void updateCheckSetting(long siteId, CheckType type, boolean enabled, Severity severityOverride)` — Upsert der Zeile; `null`-Override löscht den Override.

- [ ] **Step 1: Write the failing test**

  Append to `SiteServiceTest` (imports `dev.hendrikhoemberg.webtesthelper.model.Severity`):

```java
    @Test
    void updateCheckSettingPersistsEnabledAndSeverityOverride() {
        long id = sites.create(form());

        sites.updateCheckSetting(id, CheckType.PAGE_STATUS, false, Severity.WARN);

        SiteContext context = sites.contextFor(id);
        assertThat(context.enabled(CheckType.PAGE_STATUS)).isFalse();
        assertThat(context.severityFor(CheckType.PAGE_STATUS, Severity.ERROR))
                .isEqualTo(Severity.WARN);
    }

    @Test
    void updateCheckSettingWithNullSeverityClearsTheOverride() {
        long id = sites.create(form());
        sites.updateCheckSetting(id, CheckType.PAGE_STATUS, true, Severity.WARN);

        sites.updateCheckSetting(id, CheckType.PAGE_STATUS, true, null);

        assertThat(sites.contextFor(id).severityFor(CheckType.PAGE_STATUS, Severity.ERROR))
                .isEqualTo(Severity.ERROR);
    }
```

- [ ] **Step 2: Run the single test — verify it FAILS**

  `./mvnw test -Dtest=SiteServiceTest` → FAIL: `cannot find symbol: method updateCheckSetting`.

- [ ] **Step 3: Write minimal implementation**

  In `SiteService.java`, add the import and the method (after `setCheckEnabled`):

```java
    public void updateCheckSetting(long siteId, CheckType type, boolean enabled, Severity severityOverride) {
        requireSite(siteId);
        SiteCheckSettingEntity setting = checkSettings.findBySiteIdAndCheckType(siteId, type)
                .orElseGet(() -> newSetting(siteId, type));
        setting.setEnabled(enabled);
        setting.setSeverityOverride(severityOverride);
        checkSettings.save(setting);
    }
```

- [ ] **Step 4: Run the single test — verify it PASSES**

  `./mvnw test -Dtest=SiteServiceTest` → PASS.

- [ ] **Step 5: Commit**

  `git commit -m "feat(catalog): add updateCheckSetting for enabled state and severity override"`

---

### Task 2: Form, Controller, `SiteDetailModel.checkRows` (web)

**Files:**
- Create: `src/main/java/dev/hendrikhoemberg/webtesthelper/web/CheckSettingsForm.java` — `aktiv` (List&lt;CheckType&gt;) + `schweregrad` (Map&lt;String,String&gt;, Schlüssel = CheckType-Name, Wert = Severity-Name oder leer).
- Create: `src/main/java/dev/hendrikhoemberg/webtesthelper/web/CheckSettingsController.java` — `POST /websites/{id}/pruefungen`.
- Modify: `src/main/java/dev/hendrikhoemberg/webtesthelper/web/SiteDetailModel.java` — `activeChecks` ersetzen durch `checkRows` (`CheckRowView(check, enabled, severityOverride)`).
- Test: Create `src/test/java/dev/hendrikhoemberg/webtesthelper/web/CheckSettingsControllerTest.java`.
- Modify: `src/test/java/dev/hendrikhoemberg/webtesthelper/web/SiteDetailControllerTest.java` — Model-Assertion `activeChecks` → `checkRows` (Zeile 141).

**Interfaces:**
- Consumes: `SiteService.updateCheckSetting` (Task 1), `CheckRegistry.all()`, `SiteDetailModel.populate`, `SiteContext.checkSettings()`.
- Produces: `POST /websites/{id}/pruefungen` → `redirect:/websites/{id}` mit Flash `flashMessage`; bei ungültigem Schweregrad Re-Render `websites/detail` mit Attribut `checkSettingsError`.

- [ ] **Step 1: Write the failing test**

  Create `CheckSettingsControllerTest` (Muster: `RecipientControllerTest`, gleiche MockitoBeans):

```java
package dev.hendrikhoemberg.webtesthelper.web;

import dev.hendrikhoemberg.webtesthelper.catalog.AppSettings;
import dev.hendrikhoemberg.webtesthelper.catalog.CredentialService;
import dev.hendrikhoemberg.webtesthelper.catalog.RecipientService;
import dev.hendrikhoemberg.webtesthelper.catalog.SiteService;
import dev.hendrikhoemberg.webtesthelper.catalog.SiteSummary;
import dev.hendrikhoemberg.webtesthelper.checks.CheckRegistry;
import dev.hendrikhoemberg.webtesthelper.model.CheckType;
import dev.hendrikhoemberg.webtesthelper.model.CrawlBudget;
import dev.hendrikhoemberg.webtesthelper.model.Severity;
import dev.hendrikhoemberg.webtesthelper.model.SiteContext;
import dev.hendrikhoemberg.webtesthelper.model.UrlNormalizer;
import dev.hendrikhoemberg.webtesthelper.runner.RunService;
import dev.hendrikhoemberg.webtesthelper.scheduling.ScheduleService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

@org.junit.jupiter.api.parallel.ResourceLock("spring-context")
@WebMvcTest(CheckSettingsController.class)
@Import(SiteDetailModel.class)
class CheckSettingsControllerTest {

    private static final long SITE_ID = 42L;

    @Autowired
    MockMvc mvc;

    @MockitoBean
    SiteService siteService;

    @MockitoBean
    RunService runService;

    @MockitoBean
    CheckRegistry checkRegistry;

    @MockitoBean
    ScheduleService scheduleService;

    @MockitoBean
    RecipientService recipientService;

    @MockitoBean
    CredentialService credentialService;

    @MockitoBean
    AppSettings appSettings;

    @MockitoBean
    AppUserService appUserService;

    private SiteContext testSite;

    @BeforeEach
    void setUp() {
        testSite = new SiteContext(
                SITE_ID,
                "Acme Shop",
                UrlNormalizer.normalize("https://acme.example.com/").orElseThrow(),
                new CrawlBudget(120, 3, Duration.ofMinutes(15)),
                List.of(), List.of(), List.of(),
                true, "AcmeBot/2.0", Map.of());
    }

    @Test
    @WithMockUser(roles = "USER")
    void savePersistsEnabledStateAndSeverityOverrideForEveryCheck() throws Exception {
        when(siteService.summary(SITE_ID)).thenReturn(new SiteSummary(SITE_ID, "Acme Shop",
                "https://acme.example.com/", true, 17));
        when(checkRegistry.all()).thenReturn(CheckRegistry.standard().all());

        mvc.perform(post("/websites/42/pruefungen")
                        .with(csrf())
                        .param("aktiv", "PAGE_STATUS")
                        .param("aktiv", "DEAD_LINK")
                        .param("schweregrad[PAGE_STATUS]", "WARN")
                        .param("schweregrad[DEAD_LINK]", ""))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/websites/42"))
                .andExpect(flash().attributeExists("flashMessage"));

        verify(siteService).updateCheckSetting(SITE_ID, CheckType.PAGE_STATUS, true, Severity.WARN);
        verify(siteService).updateCheckSetting(SITE_ID, CheckType.DEAD_LINK, true, null);
        verify(siteService).updateCheckSetting(eq(SITE_ID), eq(CheckType.TLS_CERT), eq(false), isNull());
        verify(siteService, times(CheckRegistry.standard().all().size()))
                .updateCheckSetting(eq(SITE_ID), any(CheckType.class), anyBoolean(), nullable(Severity.class));
    }

    @Test
    @WithMockUser(roles = "USER")
    void emptyFormDisablesAllChecksWithoutOverrides() throws Exception {
        when(siteService.summary(SITE_ID)).thenReturn(new SiteSummary(SITE_ID, "Acme Shop",
                "https://acme.example.com/", true, 17));
        when(checkRegistry.all()).thenReturn(CheckRegistry.standard().all());

        mvc.perform(post("/websites/42/pruefungen").with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/websites/42"));

        verify(siteService).updateCheckSetting(eq(SITE_ID), eq(CheckType.PAGE_STATUS), eq(false), isNull());
        verify(siteService).updateCheckSetting(eq(SITE_ID), eq(CheckType.TLS_CERT), eq(false), isNull());
    }

    @Test
    @WithMockUser(roles = "USER")
    void anInvalidSeverityReRendersTheDetailPageWithAnErrorAndSavesNothing() throws Exception {
        when(siteService.summary(SITE_ID)).thenReturn(new SiteSummary(SITE_ID, "Acme Shop",
                "https://acme.example.com/", true, 17));
        when(checkRegistry.all()).thenReturn(CheckRegistry.standard().all());
        when(siteService.contextFor(SITE_ID)).thenReturn(testSite);
        when(runService.recentForSite(SITE_ID, 20)).thenReturn(List.of());
        when(scheduleService.forSite(SITE_ID)).thenReturn(List.of());
        when(recipientService.list(SITE_ID)).thenReturn(List.of());
        when(credentialService.list(SITE_ID)).thenReturn(List.of());
        when(appSettings.fallbackRecipients()).thenReturn(List.of());

        mvc.perform(post("/websites/42/pruefungen")
                        .with(csrf())
                        .param("aktiv", "PAGE_STATUS")
                        .param("schweregrad[PAGE_STATUS]", "KATASTROPHAL"))
                .andExpect(status().isOk())
                .andExpect(view().name("websites/detail"))
                .andExpect(content().string(containsString("ungültig")))
                .andExpect(content().string(not(containsString("KATASTROPHAL"))));

        verify(siteService, never()).updateCheckSetting(anyLong(), any(), anyBoolean(), any());
    }

    @Test
    @WithMockUser(roles = "USER")
    void unknownSiteReturns404() throws Exception {
        when(siteService.summary(999L)).thenThrow(new IllegalArgumentException("Site existiert nicht: 999"));

        mvc.perform(post("/websites/999/pruefungen").with(csrf()))
                .andExpect(status().isNotFound());
    }
}
```

  In `SiteDetailControllerTest` Zeile 141 ersetzen:
  `model().attributeExists("site", "recentRuns", "activeChecks")` → `model().attributeExists("site", "recentRuns", "checkRows")`.

- [ ] **Step 2: Run the single test — verify it FAILS**

  `./mvnw test -Dtest=CheckSettingsControllerTest` → FAIL: `CheckSettingsController` existiert nicht (Bean not found).
  `./mvnw test -Dtest=SiteDetailControllerTest` → FAIL: Model-Attribut `checkRows` fehlt.

- [ ] **Step 3: Write minimal implementation**

  `CheckSettingsForm.java`:

```java
package dev.hendrikhoemberg.webtesthelper.web;

import dev.hendrikhoemberg.webtesthelper.model.CheckType;

import java.util.List;
import java.util.Map;

/**
 * The per-check configuration form on the site detail page: which checks are active and
 * which severity each overrides. The map keys and the {@code aktiv} values are internal
 * {@link CheckType} names — machine-only values that are never rendered as labels (§13.1),
 * same contract as {@link SetupForm}.
 */
public class CheckSettingsForm {

    private List<CheckType> aktiv = List.of();

    private Map<String, String> schweregrad = Map.of();

    public List<CheckType> getAktiv() {
        return aktiv;
    }

    public void setAktiv(List<CheckType> aktiv) {
        this.aktiv = aktiv != null ? aktiv : List.of();
    }

    public Map<String, String> getSchweregrad() {
        return schweregrad;
    }

    public void setSchweregrad(Map<String, String> schweregrad) {
        this.schweregrad = schweregrad != null ? schweregrad : Map.of();
    }
}
```

  `CheckSettingsController.java`:

```java
package dev.hendrikhoemberg.webtesthelper.web;

import dev.hendrikhoemberg.webtesthelper.catalog.SiteService;
import dev.hendrikhoemberg.webtesthelper.checks.CheckDescriptor;
import dev.hendrikhoemberg.webtesthelper.checks.CheckRegistry;
import dev.hendrikhoemberg.webtesthelper.model.CheckType;
import dev.hendrikhoemberg.webtesthelper.model.Severity;
import org.springframework.context.MessageSource;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;

/**
 * Per-check configuration on the site detail page: the active set plus a severity override
 * per check. Every authenticated role may save it, like the guided-setup confirmation; only
 * the registry checks are touched, journey types are not.
 */
@Controller
public class CheckSettingsController {

    private final SiteService siteService;
    private final CheckRegistry checkRegistry;
    private final SiteDetailModel siteDetailModel;
    private final MessageSource messageSource;

    public CheckSettingsController(SiteService siteService, CheckRegistry checkRegistry,
                                   SiteDetailModel siteDetailModel, MessageSource messageSource) {
        this.siteService = siteService;
        this.checkRegistry = checkRegistry;
        this.siteDetailModel = siteDetailModel;
        this.messageSource = messageSource;
    }

    @PostMapping("/websites/{id}/pruefungen")
    public String speichern(@PathVariable("id") long id,
                            @ModelAttribute CheckSettingsForm form,
                            Model model,
                            RedirectAttributes redirectAttributes,
                            Locale locale) {
        siteService.summary(id); // unknown site → 404

        Set<CheckType> aktiv = form.getAktiv().isEmpty()
                ? EnumSet.noneOf(CheckType.class)
                : EnumSet.copyOf(form.getAktiv());
        EnumMap<CheckType, Severity> overrides = new EnumMap<>(CheckType.class);
        for (CheckDescriptor check : checkRegistry.all()) {
            String value = form.getSchweregrad().get(check.type().name());
            if (value == null || value.isBlank()) {
                continue;
            }
            try {
                overrides.put(check.type(), Severity.valueOf(value));
            } catch (IllegalArgumentException e) {
                siteDetailModel.populate(id, model);
                model.addAttribute("checkSettingsError", messageSource.getMessage(
                        "ui.websites.detail.pruefungen.fehler.schweregrad", null, locale));
                return "websites/detail";
            }
        }

        for (CheckDescriptor check : checkRegistry.all()) {
            siteService.updateCheckSetting(id, check.type(),
                    aktiv.contains(check.type()), overrides.get(check.type()));
        }
        redirectAttributes.addFlashAttribute("flashMessage", messageSource.getMessage(
                "ui.websites.detail.pruefungen.gespeichert", null, locale));
        return "redirect:/websites/" + id;
    }
}
```

  `SiteDetailModel.java` — Imports ergänzen (`CheckSetting`, `Severity`), Block ersetzen:

```java
        List<CheckRowView> checkRows = checkRegistry.all().stream()
                .map(check -> {
                    CheckSetting setting = site.checkSettings().get(check.type());
                    return new CheckRowView(check,
                            setting != null && setting.enabled(),
                            setting == null ? null : setting.severityOverride());
                })
                .toList();
```

  und `model.addAttribute("activeChecks", activeChecks);` → `model.addAttribute("checkRows", checkRows);`.

  Neues Nested-Record in `SiteDetailModel`:

```java
    /** One editable row of the per-check configuration: the check plus its site state. */
    public record CheckRowView(CheckDescriptor check, boolean enabled, Severity severityOverride) {
    }
```

- [ ] **Step 4: Run the single tests — verify they PASS**

  `./mvnw test -Dtest=CheckSettingsControllerTest,SiteDetailControllerTest` → PASS.
  (Beide auf einmal; die View-Assertions zu Form-Markup folgen in Task 3, da das Template noch das alte ist.)

- [ ] **Step 5: Commit**

  `git commit -m "feat(web): add check-settings form and POST endpoint for per-site check configuration"`

---

### Task 3: Template-Formular + deutsche Meldungen (view)

> **Hinweis:** In Task 2 eingeflossen — das Entfernen von `activeChecks` aus `SiteDetailModel` bricht die alte View sofort, daher wurden Template und Messages im selben Task umgesetzt (ein grüner Commit). Form-Binding brauchte zudem mutable Collections in `CheckSettingsForm` (Spring wächst die Collections über den Getter).

**Files:**
- Modify: `src/main/resources/templates/websites/detail.html` — Abschnitt „Aktive Prüfungen“ (Zeilen 115–125) ersetzen.
- Modify: `src/main/resources/messages.properties` — Titel anpassen, Hinweis-Key löschen, 6 Keys ergänzen.
- Test: `src/test/java/dev/hendrikhoemberg/webtesthelper/web/SiteDetailControllerTest.java` — neue Assertions im ersten Test.

**Interfaces:**
- Consumes: `checkRows` (Task 2), `checkSettingsError`, Keys `check.*.title/description` (vorhanden), `ui.severity.*` (vorhanden).
- Produces: Formular `POST /websites/{id}/pruefungen` mit Checkbox `aktiv` (Wert = CheckType-Name) und Select `schweregrad[<CheckType-Name>]` (Werte ``, `ERROR`, `WARN`, `INFO`).

- [ ] **Step 1: Write the failing test**

  In `SiteDetailControllerTest.getSiteDetailRendersBudgetPatternsHistoryAndActiveChecks` nach Zeile 155 (`not(containsString("2026-08-25T10:00:00Z"))`) ergänzen:

```java
                .andExpect(content().string(containsString("Prüfungen speichern")))
                .andExpect(content().string(containsString("name=\"aktiv\"")))
                .andExpect(content().string(containsString("schweregrad[PAGE_STATUS]")))
                .andExpect(content().string(not(containsString("späteren Ausbaustufe"))));
```

- [ ] **Step 2: Run the single test — verify it FAILS**

  `./mvnw test -Dtest=SiteDetailControllerTest` → FAIL: `Prüfungen speichern`, `name="aktiv"`, `schweregrad[PAGE_STATUS]` nicht im HTML; Hinweistext noch vorhanden.

- [ ] **Step 3: Write minimal implementation**

  `detail.html`, Zeilen 115–125 ersetzen durch:

```html
    <!-- Prüfungen -->
    <section class="card-box detail-bereich">
        <h2 th:text="#{ui.websites.detail.pruefungen.titel}">Prüfungen</h2>
        <form th:action="@{/websites/{id}/pruefungen(id=${site.siteId})}" method="post">
            <p th:if="${checkSettingsError}" class="fehler-text" th:text="${checkSettingsError}">Fehler</p>
            <div class="pruefungen-gitter" style="display: grid; grid-template-columns: repeat(auto-fill, minmax(280px, 1fr)); gap: 1rem; margin-bottom: 1rem;">
                <div th:each="row : ${checkRows}" class="pruefung-karte" style="background: var(--surface-subtle); border: 1px solid var(--border-subtle); border-radius: 8px; padding: 1rem;">
                    <h3 style="font-size: 0.95rem; font-weight: 700; margin-bottom: 0.35rem; color: var(--text-main);" th:text="#{${row.check.titleKey}}">Prüfungs-Titel</h3>
                    <p style="font-size: 0.825rem; color: var(--text-muted); margin: 0 0 0.75rem; line-height: 1.4;" th:text="#{${row.check.descriptionKey}}">Prüfungs-Beschreibung</p>
                    <label style="display: flex; align-items: center; gap: 0.5rem; font-size: 0.85rem; margin-bottom: 0.5rem;">
                        <input type="checkbox" name="aktiv" th:value="${row.check.type}" th:checked="${row.enabled}">
                        <span th:text="#{ui.websites.detail.pruefungen.aktiviert}">Prüfung aktiviert</span>
                    </label>
                    <label style="display: flex; align-items: center; gap: 0.5rem; font-size: 0.85rem;">
                        <span th:text="#{ui.websites.detail.pruefungen.schweregrad}">Schweregrad</span>
                        <select th:attr="name=|schweregrad[${row.check.type}]|" class="form-select" style="flex: 1;">
                            <option value="" th:selected="${row.severityOverride == null}" th:text="#{ui.websites.detail.pruefungen.schweregrad.standard}">Standard</option>
                            <option value="ERROR" th:selected="${row.severityOverride?.name() == 'ERROR'}" th:text="#{ui.severity.ERROR}">Fehler</option>
                            <option value="WARN" th:selected="${row.severityOverride?.name() == 'WARN'}" th:text="#{ui.severity.WARN}">Warnung</option>
                            <option value="INFO" th:selected="${row.severityOverride?.name() == 'INFO'}" th:text="#{ui.severity.INFO}">Hinweis</option>
                        </select>
                    </label>
                </div>
            </div>
            <div class="form-aktionen">
                <button type="submit" class="btn-ui btn-ui-primary button primär" th:text="#{ui.websites.detail.pruefungen.speichern}">Prüfungen speichern</button>
            </div>
        </form>
    </section>
```

  `messages.properties` (Abschnitt Website-Detailseite):

  - Zeile `ui.websites.detail.pruefungen.titel=Aktive Prüfungen` → `ui.websites.detail.pruefungen.titel=Prüfungen`
  - Zeile `ui.websites.detail.pruefungen.hinweis=…` löschen.
  - Nach der Titel-Zeile ergänzen:

```properties
ui.websites.detail.pruefungen.aktiviert=Prüfung aktiviert
ui.websites.detail.pruefungen.schweregrad=Schweregrad
ui.websites.detail.pruefungen.schweregrad.standard=Standard
ui.websites.detail.pruefungen.speichern=Prüfungen speichern
ui.websites.detail.pruefungen.gespeichert=Die Konfiguration der Prüfungen wurde gespeichert.
ui.websites.detail.pruefungen.fehler.schweregrad=Ein übermittelter Schweregrad ist ungültig. Die Konfiguration wurde nicht gespeichert.
```

  Kein `sec:authorize` nötig: alle Rollen dürfen speichern.

- [ ] **Step 4: Run the single test — verify it PASSES**

  `./mvnw test -Dtest=SiteDetailControllerTest,CheckSettingsControllerTest,UiMessageKeyTest,EnumLabelsTest` → PASS.

- [ ] **Step 5: Commit**

  `git commit -m "feat(ui): replace check hint with editable per-check configuration form"`

---

### Task 4: Gesamtverifikation

- [ ] **Step 1: Komplette Suite**

  `./mvnw test` → PASS (inkl. `@Tag("browser")`-Akzeptanztests).

- [ ] **Step 2: Aufräumen prüfen**

  Keine Reste des Hinweis-Keys: `rg "pruefungen.hinweis|Ausbaustufe|activeChecks" src/` → nur noch Treffer, die erwartet sind (keine).

- [ ] **Step 3: Finaler Commit**

  Nur falls Step 1/2 Änderungen nötig machten; sonst ist der Stand bereits committet.

---

## Self-Review

- **Spec coverage:** Umfang An/Aus + Schweregrad → Task 1 (Setter), Task 2 (POST + Binding), Task 3 (Formular); UI auf Detailseite → Task 3; alle Rollen → `@WithMockUser(roles = "USER")`-Tests + kein `sec:`/SecurityConfig-Change; Hinweis entfernt → Task 3 (`pruefungen.hinweis` gelöscht, Assertion `not(containsString("späteren Ausbaustufe"))`).
- **Placeholder scan:** Keine — jeder Task enthält vollständigen Code.
- **Type consistency:** `updateCheckSetting(long, CheckType, boolean, Severity)` durchgängig; `CheckRowView(CheckDescriptor, boolean, Severity)`; Form-Feldnamen `aktiv`/`schweregrad[X]` deckungsgleich zwischen Template, `CheckSettingsForm` und Controller.
