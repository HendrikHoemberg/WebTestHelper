# Review Improvements Follow-up Fixes — Implementation Plan

**Goal:** Address the 4 implementation gaps and design flaws identified in the evaluation of `2026-09-02-agent-review-improvements.md`: (1) align tile warning counts with untriaged warnings and wire correct triage query params into tile links, (2) eliminate the redundant double-badge display in finding rows, (3) prevent crawler click side-effects by switching cookie banner bypass to non-destructive overlay hiding, and (4) allow SMTP testing to seamlessly save and test submitted form inputs without discarding changes.

**Architecture:** Improvements touch view templates (`kacheln.html`, `befundzeile.html`, `einstellungen/index.html`), German copy (`messages.properties`), crawler automation (`PageNavigator.java`), and controller settings logic (`SettingsController.java`). Each task follows strict TDD.

**Tech Stack:** Spring Boot 3, Thymeleaf + HTMX + Alpine.js, Playwright, JUnit 5 + MockMvc + AssertJ.

**Spec:** Driven by review evaluation of `2026-09-02-agent-review-improvements.md`.

## Global Constraints

- German-only UI; message keys under `ui.*` in `src/main/resources/messages.properties`.
- No internal identifiers in rendered HTML.
- Desktop-only layout; no mobile breakpoints.
- View tests assert text/markup content via MockMvc (`containsString`), not CSS stylesheets.
- Verification command (default gate): `bash -c "set -o pipefail; ./mvnw test -Pfast -B --no-transfer-progress | tail -n 50"`.
- Single test command: `./mvnw test -Dtest=<ClassName>`.

---

### Task 1: Align Tile Warnings Count and Wire Triage Filter Parameters

**Files:**
- Modify: `src/main/resources/templates/fragments/kacheln.html` — check `tile.counts.untriagedWarnings() > 0` instead of `tile.counts.warnings() > 0`, and pass `triageStatuses='UNTRIAGED'` or `triageStatuses='ACKNOWLEDGED'` to `/websites/{id}/befunde` links.
- Test: `src/test/java/dev/hendrikhoemberg/webtesthelper/web/SiteControllerTest.java`.

**Interfaces:**
- Consumes: `tile.counts.untriagedWarnings()`, `tile.counts.untriagedErrors()`, `tile.counts.acknowledged()`.
- Produces: Correct badge rendering on dashboard tiles matching the traffic light state, with targeted filter links.

- [ ] **Step 1: Write the failing test**

  In `src/test/java/dev/hendrikhoemberg/webtesthelper/web/SiteControllerTest.java`, add a test verifying that tile badges include the specific triage status parameters and untriaged warnings:

  ```java
  @Test
  @WithMockUser(roles = "USER")
  void dashboardTileRendersTriageFilterParametersAndUntriagedWarnings() throws Exception {
      OpenFindingCounts counts = new OpenFindingCounts(5, 3, 0, 2, 2, 0, 6);
      // untriagedErrors: 2, untriagedWarnings: 0, acknowledged: 6
      when(dashboardService.dashboardView()).thenReturn(new DashboardView(
              List.of(new SiteTile(1L, "Alpha", "https://alpha.example.com/", TrafficLight.GRUEN,
                      null, counts, null, true)),
              OpenFindingCounts.none(),
              List.of()
      ));

      mvc.perform(get("/"))
              .andExpect(status().isOk())
              .andExpect(content().string(containsString("triageStatuses=UNTRIAGED")))
              .andExpect(content().string(containsString("triageStatuses=ACKNOWLEDGED")))
              // Since untriagedWarnings == 0, no warning badge should appear even if total warnings > 0
              .andExpect(content().string(not(containsString("kennzahl-warnung"))));
  }
  ```

- [ ] **Step 2: Run the single test — verify it FAILS**

  Command: `./mvnw test -Dtest=SiteControllerTest#dashboardTileRendersTriageFilterParametersAndUntriagedWarnings`
  Expected: FAIL (triageStatuses not present in URLs, and kennzahl-warnung is present).

- [ ] **Step 3: Write minimal implementation**

  In `src/main/resources/templates/fragments/kacheln.html`:
  Update lines 55–68:
  ```html
        <div th:if="${tile.enabled}" class="kachel-kennzahlen" style="padding: 0 1.25rem 0.75rem;">
            <a th:if="${tile.counts.untriagedErrors() > 0}" class="kennzahl-link kennzahl-fehler status-badge badge-failing"
               th:href="@{/websites/{id}/befunde(id=${tile.siteId}, severities='ERROR', triageStatuses='UNTRIAGED')}"
               th:text="#{ui.uebersicht.fehler(${tile.counts.untriagedErrors()})}">3 Fehler</a>
            <a th:if="${tile.counts.acknowledged() > 0}" class="kennzahl-link kennzahl-altlast status-badge status-inaktiv"
               th:href="@{/websites/{id}/befunde(id=${tile.siteId}, triageStatuses='ACKNOWLEDGED')}"
               th:text="#{ui.uebersicht.altlasten(${tile.counts.acknowledged()})}">40 im Ausgangsbestand</a>
            <a th:if="${tile.counts.untriagedWarnings() > 0}" class="kennzahl-link kennzahl-warnung status-badge badge-warning"
               th:href="@{/websites/{id}/befunde(id=${tile.siteId}, severities='WARN', triageStatuses='UNTRIAGED')}">
                <span th:if="${tile.counts.untriagedWarnings() == 1}"
                      th:text="#{ui.uebersicht.warnung.einzahl}">1 Warnung</span>
                <span th:if="${tile.counts.untriagedWarnings() != 1}"
                      th:text="#{ui.uebersicht.warnung.mehrzahl(${tile.counts.untriagedWarnings()})}">2 Warnungen</span>
            </a>
            <a th:if="${tile.counts.infos() > 0}" class="kennzahl-link kennzahl-hinweis status-badge status-inaktiv"
               th:href="@{/websites/{id}/befunde(id=${tile.siteId}, severities='INFO')}">
                <span th:if="${tile.counts.infos() == 1}"
                      th:text="#{ui.uebersicht.hinweis.einzahl}">1 Hinweis</span>
                <span th:if="${tile.counts.infos() != 1}"
                      th:text="#{ui.uebersicht.hinweis.mehrzahl(${tile.counts.infos()})}">2 Hinweise</span>
            </a>
        </div>
  ```

- [ ] **Step 4: Run the single test — verify it PASSES**

  Command: `./mvnw test -Dtest=SiteControllerTest#dashboardTileRendersTriageFilterParametersAndUntriagedWarnings`
  Expected: PASS.

- [ ] **Step 5: Commit**

  `git commit -m "fix(web): align tile warning count with untriaged warnings and wire triage filter params"`

---

### Task 2: Remove Redundant SmartPriority Badge from Finding Row

**Files:**
- Modify: `src/main/resources/templates/fragments/befundzeile.html` — remove the second redundant badge `<span class="status-badge" th:classappend="...smartPriority..." ...>`.
- Test: `src/test/java/dev/hendrikhoemberg/webtesthelper/web/FindingListControllerTest.java`.

**Interfaces:**
- Consumes: `befund.severity`.
- Produces: Clean, uncluttered finding row with single status badge.

- [ ] **Step 1: Write the failing test**

  In `src/test/java/dev/hendrikhoemberg/webtesthelper/web/FindingListControllerTest.java`, verify that `badge-prioritaet-` is not rendered in the findings list:

  ```java
  @Test
  @WithMockUser(roles = "USER")
  void findingRowDoesNotRenderRedundantPriorityBadge() throws Exception {
      when(siteService.contextFor(1L)).thenReturn(sampleSite(1L));
      Finding finding = sampleFinding(100L, 1L, Severity.ERROR);
      when(findingService.search(any())).thenReturn(new FindingPage(List.of(finding), 1, 1, 50));
      when(findingViewFactory.of(eq(finding), any())).thenReturn(sampleFindingView(100L, Severity.ERROR));

      mvc.perform(get("/websites/1/befunde"))
              .andExpect(status().isOk())
              .andExpect(content().string(containsString("badge-failing")))
              .andExpect(content().string(not(containsString("badge-prioritaet-dringend"))))
              .andExpect(content().string(not(containsString("badge-prioritaet-empfohlen"))));
  }
  ```

- [ ] **Step 2: Run the single test — verify it FAILS**

  Command: `./mvnw test -Dtest=FindingListControllerTest#findingRowDoesNotRenderRedundantPriorityBadge`
  Expected: FAIL (`badge-prioritaet-dringend` found in response).

- [ ] **Step 3: Write minimal implementation**

  In `src/main/resources/templates/fragments/befundzeile.html`:
  Remove lines 16–18:
  ```html
  <!-- Remove:
  <span class="status-badge"
        th:classappend="${befund.smartPriority.name() == 'DRINGEND' ? 'badge-prioritaet-dringend' : (befund.smartPriority.name() == 'EMPFOHLEN' ? 'badge-prioritaet-empfohlen' : 'badge-prioritaet-niedrig')}"
        th:text="#{${'ui.priority.' + befund.smartPriority}}">Dringend</span>
  -->
  ```

- [ ] **Step 4: Run the single test — verify it PASSES**

  Command: `./mvnw test -Dtest=FindingListControllerTest#findingRowDoesNotRenderRedundantPriorityBadge`
  Expected: PASS.

- [ ] **Step 5: Commit**

  `git commit -m "refactor(web): remove redundant priority badge from finding rows"`

---

### Task 3: Non-Destructive Cookie Overlay Hiding in PageNavigator

**Files:**
- Modify: `src/main/java/dev/hendrikhoemberg/webtesthelper/crawler/PageNavigator.java` — update `DISMISS_CONSENT_JS` to hide banner overlays via CSS (`display: none !important;`) rather than clicking arbitrary `button[aria-label*="accept"]` or generic selectors.
- Test: `src/test/java/dev/hendrikhoemberg/webtesthelper/crawler/PageNavigatorTest.java`.

**Interfaces:**
- Consumes: Playwright `Page`.
- Produces: Unobstructed screenshot without firing synthetic click events or triggering 3rd party tracker scripts.

- [ ] **Step 1: Write the failing test**

  In `src/test/java/dev/hendrikhoemberg/webtesthelper/crawler/PageNavigatorTest.java`:
  Update `dismissConsentBannersClicksAcceptButtonAndHidesOverlays` (rename to `dismissConsentBannersHidesOverlaysWithoutTriggeringButtonClicks`):

  ```java
  @Test
  void dismissConsentBannersHidesOverlaysWithoutTriggeringButtonClicks() {
      pool.submit(browser -> {
          var page = browser.newPage();
          page.setContent("""
              <!doctype html>
              <html>
              <body>
                  <div id="onetrust-banner-sdk" style="display: block;">
                      <button id="onetrust-accept-btn-handler" onclick="document.body.dataset.accepted = 'true'">Accept</button>
                  </div>
                  <div id="CybotCookiebotDialog" style="display: block;">Cookiebot</div>
                  <div class="cc-window" style="display: block;">Cookie Consent</div>
              </body>
              </html>
          """);
          PageNavigator.dismissConsentBanners(page);
          // Assert overlays are hidden
          assertThat(page.evaluate("document.getElementById('onetrust-banner-sdk').style.display")).isEqualTo("none");
          assertThat(page.evaluate("document.getElementById('CybotCookiebotDialog').style.display")).isEqualTo("none");
          assertThat(page.evaluate("document.querySelector('.cc-window').style.display")).isEqualTo("none");
          // Assert button was NOT clicked (no unintended script execution)
          assertThat(page.evaluate("document.body.dataset.accepted")).isNull();
          page.close();
          return null;
      });
  }
  ```

- [ ] **Step 2: Run the single test — verify it FAILS**

  Command: `./mvnw test -Dtest=PageNavigatorTest#dismissConsentBannersHidesOverlaysWithoutTriggeringButtonClicks`
  Expected: FAIL (`document.body.dataset.accepted` was "true" due to button click).

- [ ] **Step 3: Write minimal implementation**

  In `src/main/java/dev/hendrikhoemberg/webtesthelper/crawler/PageNavigator.java`:
  Update `DISMISS_CONSENT_JS`:
  ```javascript
  static final String DISMISS_CONSENT_JS = """
          (() => {
              const overlays = [
                  '#onetrust-banner-sdk',
                  '#CybotCookiebotDialog',
                  '.cc-window',
                  '.cookie-banner',
                  '#usercentrics-root',
                  '.cmpbox',
                  '.qc-cmp2-container',
                  'div[id*="cookie-law" i]',
                  'div[id*="consent-banner" i]'
              ];
              for (const o of overlays) {
                  try {
                      const els = document.querySelectorAll(o);
                      els.forEach(el => el.style.setProperty('display', 'none', 'important'));
                  } catch (e) {}
              }
              try {
                  const uc = document.querySelector('#usercentrics-root');
                  if (uc && uc.shadowRoot) {
                      const ucOverlay = uc.shadowRoot.querySelector('#uc-main-dialog, .uc-overlay');
                      if (ucOverlay) ucOverlay.style.setProperty('display', 'none', 'important');
                  }
              } catch (e) {}
          })()
          """;
  ```

- [ ] **Step 4: Run the single test — verify it PASSES**

  Command: `./mvnw test -Dtest=PageNavigatorTest#dismissConsentBannersHidesOverlaysWithoutTriggeringButtonClicks`
  Expected: PASS.

- [ ] **Step 5: Commit**

  `git commit -m "fix(crawler): hide cookie banner overlays via CSS without clicking elements"`

---

### Task 4: Fix SMTP Test-Mail to Save Submitted Credentials and Clarify Hints

**Files:**
- Modify: `src/main/java/dev/hendrikhoemberg/webtesthelper/web/SettingsController.java` — in `sendTestMail`, accept `SettingsForm form`, save settings if form inputs are provided, and then dispatch test mail.
- Modify: `src/main/resources/messages.properties` — clarify copy `ui.einstellungen.smtp.test_hinweis`.
- Modify: `src/main/resources/templates/einstellungen/index.html` — keep test button accessible and clear.
- Test: `src/test/java/dev/hendrikhoemberg/webtesthelper/web/SettingsControllerTest.java`.

**Interfaces:**
- Consumes: `SettingsForm form` on `POST /einstellungen/testmail`.
- Produces: Persists updated credentials if present in form, sends test mail, redirects with flash attributes.

- [ ] **Step 1: Write the failing test**

  In `src/test/java/dev/hendrikhoemberg/webtesthelper/web/SettingsControllerTest.java`:
  Add test asserting that submitting form inputs to `/einstellungen/testmail` persists the SMTP settings before sending:

  ```java
  @Test
  @WithMockUser(roles = "ADMIN")
  void postTestMailWithFormInputsPersistsSettingsBeforeSending() throws Exception {
      when(appSettings.smtp()).thenReturn(new SmtpSettings(
              "old.example.com", 25, TlsMode.NONE, "olduser", "oldpass", "old@example.com"
      ));
      when(appSettings.baseUrl()).thenReturn("https://example.com");
      OutboundMail mail = new OutboundMail("new@example.com", "Test Subject", "<p>HTML</p>", "Text");
      when(mailRenderer.testMail(eq("new@example.com"), any())).thenReturn(mail);
      when(outboxService.enqueue(mail)).thenReturn(101L);
      when(outboxService.sendNow(101L)).thenReturn(DeliveryResult.sent("ok"));

      mvc.perform(post("/einstellungen/testmail")
                      .with(csrf())
                      .param("host", "smtp.newhost.com")
                      .param("port", "587")
                      .param("tls", "STARTTLS")
                      .param("username", "newuser")
                      .param("password", "newsecret")
                      .param("fromAddress", "new@example.com")
                      .param("baseUrl", "https://example.com"))
              .andExpect(status().is3xxRedirection())
              .andExpect(redirectedUrl("/einstellungen"))
              .andExpect(flash().attribute("testmailErfolg", true));

      verify(appSettings).saveSmtp(argThat(s ->
              "smtp.newhost.com".equals(s.host()) && "new@example.com".equals(s.fromAddress())));
  }
  ```

- [ ] **Step 2: Run the single test — verify it FAILS**

  Command: `./mvnw test -Dtest=SettingsControllerTest#postTestMailWithFormInputsPersistsSettingsBeforeSending`
  Expected: FAIL (unwanted invocation / `saveSmtp` not called or old credentials used).

- [ ] **Step 3: Write minimal implementation**

  In `src/main/java/dev/hendrikhoemberg/webtesthelper/web/SettingsController.java`:
  Update `sendTestMail`:
  ```java
  @PostMapping("/testmail")
  public String sendTestMail(@ModelAttribute("form") SettingsForm form,
                             BindingResult bindingResult,
                             RedirectAttributes redirectAttributes) {
      if (form != null && form.getHost() != null && !form.getHost().isBlank()) {
          String password = form.getPassword();
          if (password == null || password.isBlank()) {
              SmtpSettings currentSmtp = appSettings.smtp();
              password = (currentSmtp != null) ? currentSmtp.password() : null;
          }
          SmtpSettings smtp = new SmtpSettings(
                  form.getHost().strip(),
                  form.getPort(),
                  form.getTls() != null ? form.getTls() : TlsMode.STARTTLS,
                  form.getUsername() != null ? form.getUsername().strip() : null,
                  password,
                  form.getFromAddress() != null ? form.getFromAddress().strip() : null
          );
          appSettings.saveSmtp(smtp);
          if (form.getBaseUrl() != null && !form.getBaseUrl().isBlank()) {
              appSettings.saveBaseUrl(form.getBaseUrl());
          }
      }

      SmtpSettings smtp = appSettings.smtp();
      if (smtp == null || !smtp.configured()) {
          redirectAttributes.addFlashAttribute("testmailFehler", "Der SMTP-Server ist nicht konfiguriert.");
          return "redirect:/einstellungen";
      }

      String recipient = smtp.fromAddress();
      String baseUrl = appSettings.baseUrl();
      OutboundMail mail = mailRenderer.testMail(recipient, baseUrl);
      long id = outboxService.enqueue(mail);
      DeliveryResult result = outboxService.sendNow(id);

      if (result.success()) {
          redirectAttributes.addFlashAttribute("testmailErfolg", true);
      } else {
          redirectAttributes.addFlashAttribute("testmailFehler", result.error());
      }

      return "redirect:/einstellungen";
  }
  ```

  In `src/main/resources/messages.properties`:
  ```properties
  ui.einstellungen.smtp.test_hinweis=SMTP-Konfiguration direkt überprüfen (speichert Zugangsdaten automatisch):
  ```

- [ ] **Step 4: Run the single test — verify it PASSES**

  Command: `./mvnw test -Dtest=SettingsControllerTest#postTestMailWithFormInputsPersistsSettingsBeforeSending`
  Expected: PASS.

- [ ] **Step 5: Commit**

  `git commit -m "fix(web): persist submitted form inputs when triggering smtp test mail"`

---

## Final Verification Gate

- [ ] Fast suite: `bash -c "set -o pipefail; ./mvnw test -Pfast -B --no-transfer-progress | tail -n 50"`
  Expected: BUILD SUCCESS, 0 failures.
