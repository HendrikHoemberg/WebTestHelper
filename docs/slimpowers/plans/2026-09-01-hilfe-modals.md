# Hilfe-Modals Implementation Plan

**Goal:** Replace the inline expanding help cards (triggered by every `?` icon) with a single reusable modal that closes on backdrop click or a dedicated close button, has rounded corners matching the app, and a blurred backdrop.

**Architecture:** The `?` buttons keep their HTMX `hx-get` to `/hilfe/hinweis/{id}`, but the target changes from the per-page `.hinweis` container to a single global modal shell rendered once in `layout.html`. The modal shell is Alpine-controlled (`x-data`/`x-show`) for open/close; on click the button opens the modal (`@click`) and HTMX swaps the teaser fragment into `#hilfe-modal-inhalt`. The topic title is delivered to the modal header via an HTMX `hx-swap-oob` element nested in the fragment, so it needs no extra endpoint.

**Tech Stack:** Spring Boot + Thymeleaf + HTMX 2.0.4 + Alpine.js 3 (no CSS framework). CSS in `src/main/resources/static/css/app.css`. German UI messages in `src/main/resources/messages.properties`.

**Spec:** User request — all `?`-triggered help messages render as modals, dismissible by clicking outside or via a close button, rounded corners, blur backdrop.

## Global Constraints

- German-only UI; message keys prefixed `ui.*`. No internal identifiers (enum names, placeholders, raw instants) in rendered HTML.
- Keep `class="hinweis-schalter"` on every `?` button and keep the `@{/hilfe/hinweis/{id}(id='...')}` Thymeleaf URL in source — `HelpTopicsTest` scans templates for both.
- Keep the `/hilfe/hinweis/{id}` fragment output as a bare fragment (no `<!DOCTYPE>`, no `<body>`) — `HelpControllerTest` asserts this.
- Keep the `z-index` of the modal above the fixed sidebar (`z-index: 100` at `app.css:109`).
- Do not edit `data/`, `target/`, `.env`, `compose.yaml`.
- Do not touch the `Hilfe` navbar link (it navigates to the manual page, not a `?` icon).

---

### Task 1: Add the helpful German close-label message key

**Files:**
- Modify: `src/main/resources/messages.properties` — add `ui.hilfe.schliessen`

- [ ] Add `ui.hilfe.schliessen=Schließen` under the existing `ui.hilfe.*` block (~line 474).

### Task 2: Global modal shell in the layout

**Files:**
- Modify: `src/main/resources/templates/layout.html` — add `x-data` scope on `app-main-wrapper` and the modal shell as its last child.
- Modify: `src/main/resources/static/css/app.css` — add `.wth-hilfe-modal-*` styles.

**Interfaces:**
- Consumes: `hilfeModalOffen` (boolean, Alpine scope on `app-main-wrapper`).
- Produces: `#hilfe-modal-titel` (header h2, updated by OOB), `#hilfe-modal-inhalt` (swap target), and a `Schließen` button.

- [ ] Add `x-data="{ hilfeModalOffen: false }"` to `<div class="app-main-wrapper">`.
- [ ] Before the closing `</div>` of `app-main-wrapper`, render:
  - Backdrop `x-show="hilfeModalOffen"` overlay with blur; `@click.self` on backdrop closes; `role="dialog" aria-modal="true" aria-labelledby="hilfe-modal-titel"`.
  - Dialog card with rounded corners: header (`h2#hilfe-modal-titel` + close button `@click="hilfeModalOffen = false"`, `aria-label=#{ui.hilfe.schliessen}`) and `div#hilfe-modal-inhalt`.
  - `@keydown.escape.window` closes.
- [ ] CSS: `.wth-hilfe-modal-overlay` fixed, `inset: 0`, `z-index: 1000`, centered flex; `.wth-hilfe-modal-backdrop` full-screen rgba + `backdrop-filter: blur(4px)`; `.wth-hilfe-modal-dialog` card surface, `border-radius: 10px`, `box-shadow: var(--shadow-elevated)`, `max-width: 34rem`; header/close-button/title/content styles.

### Task 3: Rewrite the help fragment to a content-only swap + OOB title

**Files:**
- Modify: `src/main/resources/templates/fragments/hinweis.html` — keep the `th:fragment="hinweis"` wrapper, drop the inline card styling, output teaser + link, plus a nested `hx-swap-oob` title h2.

- [ ] Content: `<h2 id="hilfe-modal-titel" class="wth-hilfe-modal-titel" th:text="${thema.title}" hx-swap-oob="true"></h2>`, `<div>` with `th:utext="${thema.teaserHtml}"`, and the `Mehr dazu im Handbuch` link (`th:text=#{ui.hilfe.mehr_dazu}`, href `@{/hilfe/{id}(id=${thema.id})}`). Keep `<html><body>` wrapper so `:: hinweis` still yields only the fragment div.

### Task 4: Repoint every `?` button at the global modal

**Files:**
- Modify (14 templates): `uebersicht/index.html`, `einrichtung/index.html`, `stummschaltungen/index.html`, `websites/befunde.html`, `websites/detail.html` (x2), `befunde/detail.html`, `laeufe/detail.html` (x4), `journey/list.html`, `journey/detail.html`, `journey/edit.html`, `einstellungen/index.html` (x3), `fragments/empfaenger.html`, `fragments/zugangsdaten.html`, `fragments/zeitplaene.html`.

- [ ] Replace `data-hx-target="next .hinweis"` with `data-hx-target="#hilfe-modal-inhalt"` and add `@click="hilfeModalOffen = true"` on each `?` button. Keep `class="hinweis-schalter"` and `th:data-hx-get="@{/hilfe/hinweis/{id}(id='...')}"`.
- [ ] Leave each `.hinweis` empty container in place (keeps `LoginFlowBrowserAcceptanceTest` `.hinweis:empty` assertion green).

### Task 5: Verify

**Files:**
- Test: existing `HelpControllerTest`, `HelpTopicsTest`, `SiteDetailControllerTest`.

- [ ] `./mvnw test -Dtest=HelpControllerTest,HelpTopicsTest,SiteDetailControllerTest` — expect PASS.
- [ ] `./mvnw test -Pfast` — full non-browser suite, expect PASS.

## Self-Review

- Spec coverage: modals (Task 2/3), close-outside (Task 2 `@click.self`), close button (Task 2), rounded corners (Task 2 CSS), blur backdrop (Task 2 CSS). All covered.
- Placeholder scan: no TODO/TBD; every step has concrete content.
- Type consistency: `hilfeModalOffen` boolean consistent across Task 2/4; fragment endpoint unchanged (`/hilfe/hinweis/{id}` → `fragments/hinweis :: hinweis`).
