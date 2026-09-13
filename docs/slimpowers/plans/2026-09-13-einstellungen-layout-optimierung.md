# Einstellungen Layout Optimierung Implementation Plan

**Goal:** Transform the `/einstellungen` settings page into a spacious, uncluttered, and well-structured interface using a 2-column duo grid for SMTP/IMAP, generous micro-whitespace, constrained input widths, toggle boxes, and in-context test triggers.

**Architecture:** Refactor Thymeleaf template `src/main/resources/templates/einstellungen/index.html` and supporting styles in `src/main/resources/static/css/app.css`. Maintain full Spring MVC form submission and CSRF compatibility. Move isolated bottom test buttons into their respective cards as in-context actions.

**Tech Stack:** Spring Boot, Thymeleaf, Alpine.js, Vanilla CSS, JUnit 5, MockMvc, Playwright (for visual verification).

**Spec:** [UX & Design Review](/home/hendrik/.gemini/antigravity-cli/brain/45f0717c-9aeb-45c9-9aa6-4c0d9341e628/einstellungen_ux_review.md) — Vorschlag 1 (2-Spalten Card Grid & Kontextuelle Test-Aktionen).

## Global Constraints

- Desktop-first UI layout: do not add mobile breakpoints or collapsible sidebars (per AGENTS.md).
- German-only UI copy: message keys `ui.*`, no internal raw identifiers (per AGENTS.md).
- Test execution: `-B --no-transfer-progress` and output bundled with `set -o pipefail` and `tail` (per AGENTS.md).
- Verify command: `bash -c "set -o pipefail; ./mvnw test -B --no-transfer-progress | tail -n 60"`.

---

### Task 1: CSS Framework & Classes for Settings Page Spacing & Layout

**Files:**
- Modify: `src/main/resources/static/css/app.css` (add `.einstellungen-duo-grid`, `.form-input-constrained`, `.toggle-card-box`, `.card-action-footer`, and improve settings micro-whitespace)
- Test: `src/test/java/dev/hendrikhoemberg/webtesthelper/web/SettingsControllerTest.java`

**Interfaces:**
- Consumes: Existing CSS design tokens (`--surface-card`, `--surface-subtle`, `--border-subtle`, `--text-main`, `--text-muted`, etc.)
- Produces: Layout utility and component classes for the 2-column duo layout, in-context action footers, and toggle cards.

- [ ] **Step 1: Write the failing test**
  In `SettingsControllerTest.java`, add test asserting that `/einstellungen` markup includes the new layout classes `.einstellungen-duo-grid` and `.toggle-card-box`:
  ```java
  @Test
  @WithMockUser(roles = "ADMIN")
  void getSettingsRendersDuoGridAndToggleCardBox() throws Exception {
      when(appSettings.smtp()).thenReturn(new SmtpSettings(
              "smtp.example.com", 587, TlsMode.STARTTLS, "admin", "secret", "alerts@example.com"
      ));
      when(appSettings.imap()).thenReturn(new ImapSettings(
              "imap.example.com", 993, TlsMode.SSL, "admin-imap", "secret-imap", "INBOX", "verify@example.com"
      ));
      when(appSettings.baseUrl()).thenReturn("https://webtesthelper.example.com");
      when(appSettings.redirectAllMailTo()).thenReturn(Optional.empty());

      mvc.perform(get("/einstellungen"))
              .andExpect(status().isOk())
              .andExpect(content().string(containsString("einstellungen-duo-grid")))
              .andExpect(content().string(containsString("toggle-card-box")));
  }
  ```
- [x] **Step 1: Write the failing test**
- [x] **Step 2: Run the single test — verify it FAILS**
- [x] **Step 3: Write minimal CSS in `app.css`**
- [x] **Step 4: Update `einstellungen/index.html` structure (initial skeleton)**
- [x] **Step 5: Run the single test — verify it PASSES**
- [x] **Step 6: Commit**

---

### Task 2: Complete Template Refactor for `/einstellungen`

**Files:**
- Modify: `src/main/resources/templates/einstellungen/index.html`
- Test: `src/test/java/dev/hendrikhoemberg/webtesthelper/web/SettingsControllerTest.java`

**Interfaces:**
- Consumes: `SettingsForm form`, `tlsModes`, `smtpConfigured`, `imapConfigured`, `systemlast`
- Produces: Restructured settings page with:
  - 2-column duo grid for SMTP & IMAP
  - Contextual test triggers (`testmail` in SMTP, `postfach-test` in IMAP, `webhook-test` in Webhook card)
  - Constrained input widths (`.form-input-constrained`)
  - Elimination of redundant bottom action bar (`Benutzerverwaltung` removed from bottom, detached buttons removed)
  - Styled toggle cards for `schedulingPaused` and `webhookEnabled`

- [x] **Step 1: Write failing tests for contextual buttons**
- [x] **Step 2: Run the test — verify it FAILS**
- [x] **Step 3: Update `src/main/resources/templates/einstellungen/index.html`**
- [x] **Step 4: Run single test — verify it PASSES**
- [x] **Step 5: Commit**

---

### Task 3: Visual Verification & Final Full Suite

**Files:**
- Test: `scratch/take_shots.js` (capture new screenshots of `/einstellungen`)
- Review: Visual comparison between old and new layout

- [x] **Step 1: Capture updated screenshots**
- [x] **Step 2: Inspect visually via `view_file`**
- [ ] **Step 3: Run full verification suite**
  `bash -c "set -o pipefail; ./mvnw test -B --no-transfer-progress | tail -n 60"`
  Expected: BUILD SUCCESS.
- [ ] **Step 4: Commit any polish / cleanups**

