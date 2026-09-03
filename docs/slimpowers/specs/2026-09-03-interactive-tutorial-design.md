# Interactive Tutorial (Onboarding Spotlight Tour) — Design Specification

**Date:** 2026-09-03  
**Status:** Approved  
**Author:** Antigravity & Hendrik  

---

## 1. Context & Motivation

WebTestHelper is a developer- and QA-centric web test and monitoring application with rich functionality (crawling, multi-factor health checks, triage, user journeys, muting rules, and schedule planning). First-time users need an intuitive orientation that highlights key areas of the application and guides them through their first setup action without forcing test data creation or getting in their way.

### Goals
1. Provide an interactive, spotlight-focused onboarding tour for first-time users.
2. Guide users across the Dashboard to the Website overview and setup capabilities with clear German explanations.
3. Automatically launch on a user's very first login, but allow dismissing/skipping at any time.
4. Adapt the tour steps to user permissions (e.g., explaining website creation for `ADMIN` users vs. viewing runs/findings for standard `USER` accounts).
5. Persist completion/dismissal in the PostgreSQL database per user account so it remains consistent across devices and browsers.
6. Provide an easy way to manually re-launch the tour at any time from the sidebar or Help Center.

---

## 2. Architecture & Library Selection

### Selected Approach: Vendored Driver.js
* **Library**: Driver.js (v1.x, MIT license, zero dependencies, ~5 KB gzipped).
* **Storage**: Vendored in `src/main/resources/static/vendor/driver.js` and `driver.css`, fitting WebTestHelper's established vendoring pattern (`alpine.min.js`, `htmx.min.js`).
* **Styling**: Overridden in `src/main/resources/static/css/app.css` using the existing design system variables (`--surface-card`, `--border-subtle`, `--btn-ui-*`, `--text-main`, `--text-body`).
* **Coordination**: A dedicated `src/main/resources/static/js/tutorial.js` coordinates Driver.js step definitions, multi-page page transitions via `sessionStorage`, and CSRF-protected completion requests.

---

## 3. Data Model & Backend API

### 3.1 Database Migration
New Flyway migration: `src/main/resources/db/migration/V29__app_user_tutorial_abgeschlossen.sql`
```sql
ALTER TABLE app_user ADD COLUMN tutorial_abgeschlossen BOOLEAN NOT NULL DEFAULT FALSE;
```

### 3.2 Domain & Persistence Layer
* **`AppUserEntity`** (`dev.hendrikhoemberg/webtesthelper/web/persistence/AppUserEntity.java`):
  * Add field `private boolean tutorialAbgeschlossen = false;`
  * Add getter `isTutorialAbgeschlossen()` and setter `setTutorialAbgeschlossen(boolean)`.
* **`AppUserService`** (`dev.hendrikhoemberg/webtesthelper/web/AppUserService.java`):
  * `boolean isTutorialAbgeschlossen(String username)`: returns current status.
  * `void setTutorialAbgeschlossen(String username, boolean abgeschlossen)`: updates database record.

### 3.3 Controller Endpoints
Dedicated controller `dev.hendrikhoemberg.webtesthelper.web.TutorialController`:
* **`POST /tutorial/abschliessen`**:
  * Called when user completes the tour or clicks "Tour beenden" / skips.
  * Updates `tutorial_abgeschlossen = true` for the authenticated user.
  * Returns HTTP `204 No Content`.
* **`POST /tutorial/neustarten`**:
  * Called when user triggers "Tour neu starten" from the sidebar or `/hilfe`.
  * Updates `tutorial_abgeschlossen = false` for the authenticated user.
  * Redirects to `/?tour=start`.

### 3.4 Security Configuration
In `dev.hendrikhoemberg.webtesthelper.web.SecurityConfig`:
* Allow `POST /tutorial/**` for all authenticated users:
  ```java
  .requestMatchers("/tutorial/**").authenticated()
  ```

### 3.5 Global Controller Advice
In `dev.hendrikhoemberg.webtesthelper.web.HealthBannerAdvice` (or a dedicated `TutorialAdvice`):
* Inject `tutorialOffen` (`boolean`) into the Thymeleaf `Model` for authenticated users.

---

## 4. Tour Flow, Routing & Role Awareness

### 4.1 Multi-Page Step Sequence

| Step | Page | Target Selector | Content Focus | Navigation Action on "Weiter" |
|---|---|---|---|---|
| **1. Willkommen** | `/` (Dashboard) | `.brand-title-text` / Center | Introduction to WebTestHelper's purpose. | Advance to Step 2 |
| **2. Hauptnavigation** | `/` (Dashboard) | `.sidebar-nav-scroll` | Explains navigation groups: Überwachung, Analyse & Regeln, System. | Advance to Step 3 |
| **3. Websites** | `/` (Dashboard) | `a[href='/websites']` | Introduces the central website management section. | Sets `sessionStorage('wth_tour_step', 'websites')` & navigates to `/websites` |
| **4. Website-Aktionen** | `/websites` | **ADMIN:** `a[href='/websites/neu']`<br/>**USER:** `.site-liste` / table | **ADMIN:** Explains adding new sites, auto-crawl, and check generation.<br/>**USER:** Explains inspecting runs, findings, and journeys. | Advance to Step 5 |
| **5. Regeln & Hilfe** | `/websites` | Sidebar: Stummschaltungen & Hilfe | Explains muting false positives and the built-in manual. | Advance to Step 6 |
| **6. Abschluss** | `/websites` | Center Popover | Congratulates user, explains that the tour can be restarted anytime. | Clicks "Loslegen!" -> sends `POST /tutorial/abschliessen` |

### 4.2 Cross-Page State Handling
* When advancing from Step 3 on `/`, `tutorial.js` writes `sessionStorage.setItem('wth_tour_step', 'websites')` and triggers `window.location.href = '/websites'`.
* On `/websites`, `tutorial.js` checks `sessionStorage.getItem('wth_tour_step')`. If set to `'websites'`, it immediately mounts Driver.js starting at Step 4.
* If the user presses "Tour beenden" or <kbd>Esc</kbd> at any point:
  * `sessionStorage.removeItem('wth_tour_step')` is executed.
  * A background POST request to `/tutorial/abschliessen` is sent so the tour does not restart automatically.

---

## 5. User Interface & German Copy

In `src/main/resources/messages.properties`:

```properties
ui.tutorial.schaltflaeche.weiter=Weiter
ui.tutorial.schaltflaeche.zurueck=Zurück
ui.tutorial.schaltflaeche.beenden=Tour beenden
ui.tutorial.schaltflaeche.fertig=Verstanden, loslegen!
ui.tutorial.schaltflaeche.neustart=Tour starten

ui.tutorial.schritt1.titel=Willkommen bei WebTestHelper
ui.tutorial.schritt1.text=WebTestHelper überwacht Ihre Websites automatisch auf Fehler, tote Links, Barrierefreiheit und Ausfälle. Machen wir einen kurzen gemeinsamen Rundgang!

ui.tutorial.schritt2.titel=Die Hauptnavigation
ui.tutorial.schritt2.text=Über die linke Leiste steuern Sie alles: von den Websites und Prüfläufen bis hin zu Stummschaltungsregeln und dem Handbuch.

ui.tutorial.schritt3.titel=Websites aufrufen
ui.tutorial.schritt3.text=Hier sind alle überwachten Webauftritte hinterlegt. Wir wechseln nun kurz in die Übersicht.

ui.tutorial.schritt4.admin.titel=Neue Website anlegen
ui.tutorial.schritt4.admin.text=Hier registrieren Sie neue Websites. Geben Sie einfach die URL ein — der integrierte Assistent ermittelt automatisch passende Prüfungen und Zeitpläne.

ui.tutorial.schritt4.user.titel=Websites einsehen
ui.tutorial.schritt4.user.text=In dieser Tabelle sehen Sie den aktuellen Zustand Ihrer Websites. Ein Klick führt direkt zu den Prüfläufen, offenen Befunden und Journeys.

ui.tutorial.schritt5.titel=Regeln & Handbuch
ui.tutorial.schritt5.text=Erwartete oder bekannte Befunde können Sie unter 'Stummschaltungen' ausblenden. Bei Fragen zu einzelnen Prüfungen hilft Ihnen das 'Handbuch' jederzeit weiter.

ui.tutorial.abschluss.titel=Alles bereit!
ui.tutorial.abschluss.text=Sie kennen nun die wichtigsten Bereiche. Sie können diese Tour jederzeit über den Hilfebereich oder die Seitenleiste erneut starten.
```

---

## 6. Frontend Integration & Layout

### 6.1 Layout Injections (`layout.html`)
1. CSS: `<link rel="stylesheet" th:href="@{/vendor/driver.css}">`
2. JS: `<script defer th:src="@{/vendor/driver.js}"></script>` and `<script defer th:src="@{/js/tutorial.js}"></script>`
3. Configuration element containing data attributes for localized strings, user role, auto-start flag, and CSRF token.
4. Restart trigger in the sidebar footer next to the user profile.

### 6.2 Help Center Integration (`hilfe/index.html`)
* Add a card/button "Geführte Einführung erneut starten" triggering `POST /tutorial/neustarten`.

---

## 7. Verification & Testing

1. **Unit & Slice Tests**:
   * `TutorialControllerTest`: `@WebMvcTest` verifying `POST /tutorial/abschliessen` (204 No Content, sets flag on user) and `POST /tutorial/neustarten` (redirects to `/?tour=start`).
   * `AppUserServiceTest`: Verifying persistence of `tutorialAbgeschlossen` flag in database.
2. **View & Navigation Tests**:
   * Assert `layout.html` renders the tutorial configuration tag when `tutorialOffen` is true.
   * Verify sidebar contains the restart button and `/hilfe` includes the tour restart action.
3. **Full Suite Gate**:
   * `./mvnw test -Pfast -B --no-transfer-progress`
