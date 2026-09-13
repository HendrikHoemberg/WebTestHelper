# Befund-Karten Redesign (Idee 3 Monochrom & SVG-Icons) Implementation Plan

**Goal:** Entrümpelung der Befund-Karten auf der Prüflauf-Detailseite (`/laeufe/{id}`) durch Umsetzung von Idee 3 (Split-Card im 100% Monochrom-/Zink-Design) und vollständige Ablösung aller Emojis (`📷`, `💡`, `▸`) durch einheitliche SVG-Icons.

**Architecture:** Thymeleaf-Fragment-Refactoring in `fragments/befundzeile.html`, `fragments/icons.html` und `laeufe/detail.html`. Anpassung des Stylesheets `app.css` zur Reduktion der Kartenhöhe um ~46% und Einführung eines klaren 2-Zonen-Aufbaus (Kopfzeile: Ort, Status, Fehlermeldung, Aktionen; Körper: schlanker monochromer URL-Hero-Streifen mit Kopier-Button).

**Tech Stack:** Spring Boot 3, Thymeleaf, Spring MVC Test / MockMvc, Alpine.js, Lucide-kompatibles SVG, Vanilla CSS.

**Spec:** [`befund-karten-brainstorming.md`](file:///home/hendrik/.gemini/antigravity-cli/brain/760e5c46-2619-4683-b7ea-580dfba6f3e6/befund-karten-brainstorming.md) und Prototyp [`docs/brainstorming/befund-karten-konzepte.html`](file:///home/hendrik/Documents/Coding/WebTestHelper/docs/brainstorming/befund-karten-konzepte.html).

## Global Constraints

- Deutsche UI-Texte, ausschließlich Message-Keys (`ui.*`), keine internen Enum-Namen in HTML.
- Farbschema: Reines Monochrome Carbon / Neutral Zinc (`--surface-subtle: #fafafa`, `--bg-canvas: #f4f4f5`, `--border-subtle: #e4e4e7`, `--text-main: #09090b`), keine blauen Tönungen.
- Alle Icons als SVG (`.svg-icon`, 24x24 viewBox, stroke-width 2, currentColor, aria-hidden="true"). Keine Emojis.
- Desktop-only Layout: Keine Breakpoints oder mobilen Collapse-Elemente hinzufügen.
- Testläufe immer mit `-B --no-transfer-progress` und `set -o pipefail`.

---

### Task 1: Neue SVG-Icons `camera` und `lightbulb` in `fragments/icons.html`

**Files:**
- Modify: `src/main/resources/templates/fragments/icons.html`
- Create/Test: `src/test/java/dev/hendrikhoemberg/webtesthelper/web/IconsFragmentTest.java`

**Interfaces:**
- Exposes: `th:fragment="camera"` and `th:fragment="lightbulb"`

- [x] **Step 1: Write the failing test**
  Erstelle `src/test/java/dev/hendrikhoemberg/webtesthelper/web/IconsFragmentTest.java`, um sicherzustellen, dass die Fragmente `camera` und `lightbulb` existieren und als valide `<svg>`-Elemente mit `class="svg-icon"` gerendert werden.

  ```java
  package dev.hendrikhoemberg.webtesthelper.web;

  import org.junit.jupiter.api.Test;
  import org.springframework.beans.factory.annotation.Autowired;
  import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
  import org.springframework.boot.test.context.SpringBootTest;
  import org.thymeleaf.TemplateEngine;
  import org.thymeleaf.context.Context;

  import static org.assertj.core.api.Assertions.assertThat;

  @SpringBootTest
  class IconsFragmentTest {

      @Autowired
      private TemplateEngine templateEngine;

      @Test
      void cameraAndLightbulbFragmentsRenderSvgIcons() {
          Context context = new Context();
          String html = templateEngine.process(
                  "<div xmlns:th=\"http://www.thymeleaf.org\">" +
                  "<span id=\"cam\" th:replace=\"~{fragments/icons :: camera}\"></span>" +
                  "<span id=\"bulb\" th:replace=\"~{fragments/icons :: lightbulb}\"></span>" +
                  "</div>",
                  context
          );

          assertThat(html).contains("<svg class=\"svg-icon\" viewBox=\"0 0 24 24\"");
          assertThat(html).contains("d=\"M14.5 4h-5L7 7H4"); // Camera path
          assertThat(html).contains("d=\"M15 14c.2-1 .7-1.7"); // Lightbulb path
      }
  }
  ```

- [x] **Step 2: Run the single test — verify it FAILS**
  `./mvnw test -Dtest=IconsFragmentTest -B --no-transfer-progress`
  Erwartetes Ergebnis: FAIL (Fragmente `camera` / `lightbulb` existieren noch nicht).

- [x] **Step 3: Write minimal implementation**
  Ergänze `src/main/resources/templates/fragments/icons.html` um die beiden Fragmente `camera` (Nr. 30) und `lightbulb` (Nr. 31).

- [x] **Step 4: Run the single test — verify it PASSES**
  `./mvnw test -Dtest=IconsFragmentTest -B --no-transfer-progress`
  Erwartetes Ergebnis: PASS.

- [x] **Step 5: Commit**
  `git commit -m "feat(ui): add camera and lightbulb SVG icons to icons fragment"`

---

### Task 2: Emojis in `laeufe/detail.html` durch SVG-Icons ersetzen

**Files:**
- Modify: `src/main/resources/templates/laeufe/detail.html`
- Modify: `src/main/resources/static/css/app.css` (falls Chevron-SVG-Klasse benötigt wird)
- Test: `src/test/java/dev/hendrikhoemberg/webtesthelper/web/RunReportAcceptanceTest.java`

- [x] **Step 1: Write the failing test**
  Erweitere `RunReportAcceptanceTest.java` um eine Assertion, dass das Abhilfe-Banner auf `/laeufe/{id}` kein Emoji `💡` enthält, sondern das SVG-Icon einbindet:

  ```java
  assertThat(html).doesNotContain("💡");
  ```

- [x] **Step 2: Run the single test — verify it FAILS**
  `./mvnw test -Dtest=RunReportAcceptanceTest#fullLifecycleAcrossThreeRuns -B --no-transfer-progress`
  Erwartetes Ergebnis: FAIL (falls `💡` noch im gerenderten HTML vorkommt).

- [x] **Step 3: Write minimal implementation**
  - In `laeufe/detail.html` Zeile 322: Ersetze `<span class="abhilfe-banner-icon">💡</span>` durch `<span class="abhilfe-banner-icon" th:replace="~{fragments/icons :: lightbulb}"></span>`.
  - In `laeufe/detail.html` Zeile 308: Ersetze `<span class="kategorie-chevron">▸</span>` durch ein sauberes SVG-Chevron `<span class="kategorie-chevron" th:replace="~{fragments/icons :: chevron_down}"></span>`.
  - In `app.css`: Passe `.kategorie-chevron` für SVG an (Rotation bei geöffnetem `<details>`: `transform: rotate(180deg)` oder `-90deg`).

- [x] **Step 4: Run the single test — verify it PASSES**
  `./mvnw test -Dtest=RunReportAcceptanceTest -B --no-transfer-progress`
  Erwartetes Ergebnis: PASS.

- [x] **Step 5: Commit**
  `git commit -m "refactor(ui): replace emojis and text chevron in laeufe/detail with SVG icons"`

---

### Task 3: Befund-Karten Redesign (Idee 3 Split-Card Monochrom) & Kamera-SVG in `befundzeile.html`

**Files:**
- Modify: `src/main/resources/templates/fragments/befundzeile.html`
- Modify: `src/main/resources/static/css/app.css`
- Test: `src/test/java/dev/hendrikhoemberg/webtesthelper/web/BefundzeileViewTest.java` (Neuer fokussierter View-Test)

- [x] **Step 1: Write the failing test**
  Erstelle einen `@WebMvcTest` bzw. Template-Test `BefundzeileViewTest.java`, der das Rendering von `befundzeile` prüft:
  1. Kein Emoji `📷`, stattdessen SVG-Kamera (`fragments/icons :: camera`), wenn `screenshotUrl != null`.
  2. Wenn `inGruppe == true`: Kein redundantes `Ungeprüft`-Badge sichtbar, stattdessen kompakte Kopfzeile mit Status, Fundort und Fehlermeldung.
  3. Der URL-Hero-Streifen (`.befund-hero-row` / `.befund-link-zeile`) rendert die Ziel-URL und den Kopier-Button.
  4. Wenn `auswaehlbar == true`: Checkbox bleibt erhalten.

- [x] **Step 2: Run the single test — verify it FAILS**
  `./mvnw test -Dtest=BefundzeileViewTest -B --no-transfer-progress`
  Erwartetes Ergebnis: FAIL.

- [x] **Step 3: Write minimal implementation**
  - Überarbeite `fragments/befundzeile.html`:
    - Kopfzeile: Statuscode / Severity + Fundort-Pill (`Auf: <code class="code-pill">/</code>`) + Meldung (`befund.message`) + Screenshot-Button (`<button type="button" class="btn-ui btn-ui-secondary btn-ui-sm">` mit `<span th:replace="~{fragments/icons :: camera}"></span> Screenshot</button>`) + `Details →` Link.
    - Repetitives `Ungeprüft`-Badge nur anzeigen, wenn `inGruppe != true` oder wenn ein abweichender Triage-Status/Stummschaltung vorliegt.
    - Körper: Schlanker monochromer Hero-Streifen (`.befund-hero-row`) mit `befund.subjectUrl`, externem Link und Kopier-Button (`th:replace="~{fragments/icons :: copy}"`).
  - Überarbeite `app.css`:
    - Schlankere `.befund-karte` (~78 px statt ~145 px).
    - Styling für `.befund-hero-row` mit `var(--surface-subtle): #fafafa`, `var(--border-subtle): #e4e4e7`, `color: var(--text-main): #09090b`.
    - Hover-Zustände im Zinc-Farbraum.
    - Kein Blau!

- [x] **Step 4: Run the single test — verify it PASSES**
  `./mvnw test -Dtest=BefundzeileViewTest -B --no-transfer-progress`
  Erwartetes Ergebnis: PASS.

- [x] **Step 5: Commit**
  `git commit -m "feat(ui): redesign befund cards to monochrome split-card layout and SVG icons"`

---

### Task 4: Gesamtsystem-Verifikation (`verification-before-completion`)

- [x] **Step 1: Schneller Testlauf**
  `bash -c "set -o pipefail; ./mvnw test -Pfast -B --no-transfer-progress | tail -n 50"`
- [x] **Step 2: Vollständige Testsuite inkl. Browser- und UI-Tests**
  `bash -c "set -o pipefail; ./mvnw test -B --no-transfer-progress | tail -n 60"`
- [x] **Step 3: Verifikation im Browser**
  Sicherstellen, dass keine visuellen Regressionen auf `/laeufe/{id}`, `/websites/{id}/befunde` und `/laeufe/{id}/druck` auftreten.
- [x] **Step 4: Commit & Abschlussbericht**
