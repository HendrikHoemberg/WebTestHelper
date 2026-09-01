# WebTestHelper UI Overhaul — Design Specification

**Date:** 2026-09-01  
**Status:** Approved  
**Author:** Antigravity & Hendrik  

---

## 1. Context & Motivation

WebTestHelper's current UI is functional but visually primitive:
1. **Navigation**: A flat, monolithic top bar with unstyled links, lacking clear active states, grouped hierarchy, or structured navigation.
2. **Layout & Density**: A fixed `max-width: 1100px` container that feels cramped on modern displays—especially for multi-column triage, journey editing, and live browser screencasts.
3. **Data Presentation**: Basic HTML tables without visual trends (e.g. run history sparklines), flat cards with minimal surface depth, and standard unstyled browser inputs.
4. **Iconography**: Relies on raw Unicode text characters (`?`, `▲`, `▼`, `✕`, `·`), giving a prototype-like feel.

### Goal
Perform a uniform, professional UI overhaul that establishes a distinctive, high-craft developer/QA tool identity (inspired by modern engineering consoles like Linear and Vercel), with a **Pure High-Contrast Monochrome Carbon** aesthetic and a **Left Sidebar Navigation** architecture.

---

## 2. Design System & Visual Language

### 2.1 Pure High-Contrast Monochrome Carbon Palette
The UI chrome, navigation, cards, typography, and controls use an uncompromising, high-contrast monochrome foundation. Color is strictly quarantined and reserved for **test outcome data** (Pass, Fail, Warning).

```css
:root {
    /* Canvas & Surfaces */
    --bg-canvas: #f4f4f5;         /* Neutral zinc canvas */
    --surface-card: #ffffff;      /* Pure white card surface */
    --surface-subtle: #fafafa;    /* Subtle tinted section */
    --surface-hover: #f4f4f5;

    /* Borders & Depth */
    --border-subtle: #e4e4e7;
    --border-strong: #d4d4d8;
    --border-dark: #27272a;
    --shadow-subtle: 0 1px 2px rgba(0, 0, 0, 0.04);
    --shadow-card: 0 1px 3px rgba(0, 0, 0, 0.04), 0 6px 12px -2px rgba(0, 0, 0, 0.02);
    --shadow-elevated: 0 4px 8px -1px rgba(0, 0, 0, 0.08), 0 2px 4px -2px rgba(0, 0, 0, 0.04);

    /* Typography */
    --text-main: #09090b;
    --text-body: #27272a;
    --text-muted: #71717a;
    --text-faint: #a1a1aa;
    --font-sans: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif;
    --font-mono: ui-monospace, "SF Mono", "Cascadia Code", Menlo, monospace;

    /* Sidebar Shell (Pitch Black & Charcoal) */
    --sidebar-width: 250px;
    --sidebar-bg: #09090b;
    --sidebar-border: #27272a;
    --sidebar-text: #a1a1aa;
    --sidebar-hover-text: #ffffff;
    --sidebar-active-bg: #27272a;
    --sidebar-active-text: #ffffff;

    /* Semantic Test Health Signals (Strictly for Data) */
    --status-success-bg: #ecfdf5;
    --status-success-border: #a7f3d0;
    --status-success-text: #065f46;
    --status-success-dot: #10b981;

    --status-error-bg: #fff1f2;
    --status-error-border: #fecdd3;
    --status-error-text: #9f1239;
    --status-error-dot: #f43f5e;

    --status-warning-bg: #fffbeb;
    --status-warning-border: #fde68a;
    --status-warning-text: #92400e;
    --status-warning-dot: #f59e0b;
}
```

### 2.2 Typography & Tabular Numerals
- **Hierarchy**: Bold headings (`font-weight: 700`, `letter-spacing: -0.025em`), distinct uppercase micro-eyebrows (`font-size: 0.75rem`, `font-weight: 700`, `letter-spacing: 0.05em`).
- **Tabular Figures**: All timestamps, durations, crawl counts, and finding metrics use `font-variant-numeric: tabular-nums` for rock-solid column alignment.
- **Code & Metadata**: URLs, HTTP methods, JSON payloads, and check types styled in monospace with high-contrast subtle badges (`.code-pill`).

### 2.3 Iconography System
- Reusable inline SVG vector fragments stored in `src/main/resources/templates/fragments/icons.html` (e.g. `th:replace="~{fragments/icons :: icon(name='search')}"` or clean reusable SVGs).
- Icons cover: Dashboard, Globe (Websites), Bell/Volume-X (Mute Rules), Book/Help, Settings, Mail (Outbox), Play, Refresh, Plus, Trash, Check, Alert-Triangle, X, Chevron-Up, Chevron-Down, External-Link, Copy.
- Replaces all raw Unicode character symbols across templates.

---

## 3. Structural & Layout Architecture

### 3.1 App Shell (`layout.html`)
- **Left Sidebar (`<aside class="app-sidebar">`)**:
  - **Brand Header**: White on black logo mark (`W`) + `WebTestHelper` title.
  - **Grouped Navigation**:
    - **Überwachung**: Übersicht (`/`), Websites (`/websites`).
    - **Analyse & Regeln**: Stummschaltungen (`/stummschaltungen`), Handbuch & Hilfe (`/hilfe`).
    - **System & Admin** (Admin-guarded): Einstellungen (`/einstellungen`), Postausgang (`/postausgang`).
  - **Bottom Profile Bar**: User avatar initials, username, role tag (`ADMIN` / `BENUTZER`), and a clean sign-out button.
- **Main Wrapper (`<div class="app-main-wrapper">`)**:
  - Global status banner at top (mail delivery failure warning or global scheduling pause status).
  - Main workspace container (`<main class="workspace-content">`) with `max-width: 1400px` and responsive auto-padding.

### 3.2 Standardized Page Header Pattern
Every page uses a consistent context header:
- **Breadcrumbs**: `WebTestHelper / Websites / Acme Shop / Befunde`
- **Title Row**: Large heading with contextual live status badge (`.status-badge.badge-healthy`).
- **Action Cluster**: Right-aligned primary (`.btn-ui.btn-ui-primary`) and secondary (`.btn-ui.btn-ui-secondary`) action buttons.

---

## 4. Screen-by-Screen Overhaul Scope

### 4.1 Dashboard (`uebersicht/index.html`, `fragments/kacheln.html`)
- **Hero Metrics Strip**: 4 KPI cards (Erfolgsquote, Offene Befunde, Geprüfte Seiten, Worker-Kapazität mit Fortschrittsbalken).
- **Target Status Tiles**: Grid of monitored websites featuring:
  - Header with site title, domain URL, and animated pulse status badge (`● Fehlerfrei` / `● 3 Fehler`).
  - **Run History Sparkline**: 8 mini-pips showing visual pass/fail/warn history per target.
  - Metric row (Letzter Lauf, Seiten, Dauer) with tabular figures.
  - Footer with next scheduled run time and direct action button.

### 4.2 Websites Catalog & Details (`websites/liste.html`, `detail.html`, `formular.html`)
- **Websites List**: High-contrast card grid / clean data table with target health, schedule tiers, active checks count, and instant run triggers.
- **Site Detail**: Structured card sections for Crawl-Budget, Path Patterns, Key Pages, Schedule Tiers (`fragments/zeitplaene.html`), Recipients (`fragments/empfaenger.html`), Stored Credentials (`fragments/zugangsdaten.html`), Active Check Toggles, and Run History table with duration and finding counters.
- **Form**: Modern styled form controls, clear toggle switches, path pattern textareas with monospace helper guidance, and collapsible advanced crawler settings.

### 4.3 Findings & Triage (`websites/befunde.html`, `befunde/detail.html`, `fragments/befundfilter.html`, `fragments/befundzeile.html`, `fragments/bewertung.html`)
- **Findings List**: Split layout with filter sidebar chips, bulk triage action bar with select-all checkbox, and finding cards with URL copy buttons, mute status annotations, and remediation snippets.
- **Finding Detail**: Structured 3-section guidance (*"Was geprüft"*, *"Was gefunden"*, *"Was zu tun ist"*), triage decision card with live date calculation, multi-page occurrences list, and syntax-highlighted HTTP request/response evidence blocks.

### 4.4 Run Reports (`laeufe/detail.html`, `fragments/fortschritt.html`)
- **Live Run Progress**: Animated segmented progress bar during running jobs (`fragments/fortschritt.html`).
- **Run Overview**: Summary card with execution scope, visited page count, duration, and baseline comparison action.
- **Diff Sections**: Distinct status pill sections for `FIXED` (Green), `NEW` (Rose), `REGRESSED` (Rose), `KNOWN` (Amber), and `STILL_OPEN` with expandable finding cards.

### 4.5 Journey Studio (`journey/list.html`, `detail.html`, `edit.html`, `record.html`)
- **Journey List**: Health indicators, last run duration, failure count, and selector drift warnings.
- **Journey Steps**: Step table with action badges (Klick, Eingabe, Navigation, Assertion), ranked locator candidate strengths, and selector drift alerts.
- **Step Editor**: Drag/reorder arrows with vector SVG icons, inline step action editors, assertion inputs.
- **Live Recorder**: Studio-like screencast canvas container with dark bezel, live stream indicator, and recorded steps sidebar.

### 4.6 Mute Rules & Admin (`stummschaltungen/`, `einstellungen/`, `postausgang/`, `hilfe/`, `anmelden.html`)
- **Mute Rules**: Live regex matching preview (`fragments/regelvorschau.html`), active vs expired rule badges.
- **Settings & User Management**: System capacity meter (`fragments/systemlast.html`), SMTP/IMAP configuration cards, user list with promote/demote and typed-confirmation delete modal.
- **Outbox**: Clean log table with state badges (`PENDING`, `SENT`, `FAILED`), retry attempt counts, and error inspector.
- **Help / Manual**: Clean markdown article reader with sidebar topic navigation.
- **Login (`anmelden.html`)**: Standalone, centered high-contrast card with brand mark, clean inputs, and error alerts.

---

## 5. Non-Functional & Quality Constraints

1. **Strict Automated Tests Compatibility**:
   - `UiMessageKeyTest`: Every German string must resolve from `src/main/resources/messages.properties` using `ui.*` or `check.*` keys.
   - `EnumLabelsTest`: No internal enum constants exposed as raw text.
   - `VendoredAssetsTest`: No external CDN links. All styles in `app.css`, scripts in `/vendor/`.
   - `ModularityTest` & `@WebMvcTest` suite: All existing view assertions (text presence, HTMX attributes, Alpine bindings) must continue to pass seamlessly.
2. **Performance**: Zero external network requests; minimal CSS footprint; fast DOM rendering without heavy client-side frameworks.
