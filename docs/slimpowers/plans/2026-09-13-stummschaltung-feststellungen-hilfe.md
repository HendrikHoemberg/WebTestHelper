# Stummschaltung „Aus Feststellungen übernehmen“ Hilfe-Schaltfläche Implementation Plan

**Goal:** Add an inline help `?` button next to the "Aus Feststellungen übernehmen" button on `/stummschaltungen` and create a dedicated Handbuch topic (`feststellungen-uebernehmen.md`) explaining the feature.

**Architecture:** Spring Boot Thymeleaf UI with HTMX and Alpine. The `?` button uses the existing `.hinweis-schalter` component, requesting `/hilfe/hinweis/feststellungen-uebernehmen` via HTMX into `#hilfe-modal-inhalt` and toggling `hilfeModalOffen = true`. Help content is bundled in `src/main/resources/help/feststellungen-uebernehmen.md` parsed by `HelpService`.

**Tech Stack:** Spring Boot 3, Thymeleaf, HTMX, Alpine.js, CommonMark, JUnit 5 / AssertJ / MockMvc.

**Spec:** User request: "On the /stummschaltungen page, add a ? button to the "aus feststellungen übernehmen" button as this is currently not intuitive enough. Check first if we need to create a whole new Handbuch section for this or if we can use an existing one"

## Global Constraints

- German-only UI; message keys `ui.*`; no internal identifiers or raw enum names.
- All help affordances must use `.hinweis-schalter` and match existing modal integration.
- `HelpTopicsTest` enforces all referenced help topic IDs resolve to existing `.md` files.
- `HelpServiceTest` validates total topic count and exact listing.

---

## Findings on Existing Handbuch Topics

1. **Analysis of existing topics**:
   - Currently there are 17 bundled topics in `src/main/resources/help/`.
   - The topic `stummschaltungen.md` ("Stummschaltungen und Regeln") explains general mutes, wildcard patterns, reasons, and expiration limits. It does not mention or explain the "Aus Feststellungen übernehmen" helper button.
   - The `/stummschaltungen` page header already contains a `?` button linking to `id='stummschaltungen'`.
   - The help modal (`fragments/hinweis.html`) renders the topic's H1 title and its first paragraph (`teaserHtml`).
   - If the new button were wired to `id='stummschaltungen'`, clicking it would display the generic text about mutes and rules, giving zero guidance on what "Aus Feststellungen übernehmen" does.
   - Sub-anchors are not supported by the modal endpoint `/hilfe/hinweis/{id}`.

2. **Decision**:
   - Create a dedicated topic `src/main/resources/help/feststellungen-uebernehmen.md` ("Muster aus Feststellungen übernehmen") so the modal teaser directly explains the feature: loading active findings for the chosen website and prefilling check type and location pattern automatically.
   - Cross-reference the feature in `src/main/resources/help/stummschaltungen.md`.

---

### Task 1: Create Dedicated Help Topic and Update HelpService Tests

**Files:**
- Create: `src/main/resources/help/feststellungen-uebernehmen.md`
- Modify: `src/main/resources/help/stummschaltungen.md`
- Test: `src/test/java/dev/hendrikhoemberg/webtesthelper/web/HelpServiceTest.java`
- Test: `src/test/java/dev/hendrikhoemberg/webtesthelper/web/HelpTopicsTest.java`

**Interfaces:**
- Consumes: Markdown parsing in `HelpService`
- Produces: `feststellungen-uebernehmen` topic in `HelpService.all()` and `HelpService.byId("feststellungen-uebernehmen")`

- [x] **Step 1: Write the failing test update in `HelpServiceTest.java`**
  Update expected count to 18 and add `"feststellungen-uebernehmen"` to `containsExactlyInAnyOrder`:
  ```java
  @Test
  void allFindsAllBundledTopicsSortedByTitle() {
      List<HelpTopic> topics = helpService.all();
      assertThat(topics)
              .hasSize(18)
              .extracting(HelpTopic::title)
              .isSortedAccordingTo(germanOrder);
      assertThat(topics)
              .extracting(HelpTopic::id)
              .containsExactlyInAnyOrder("bericht-lesen", "ausgangsbestand", "smtp-einrichten",
                      "zeitplaene", "stummschaltungen", "benachrichtigungen", "uebersicht",
                      "einrichtung", "cookie-hinweis", "sprachumschalter", "schaltflaechen",
                      "kontaktformular", "pruefpostfach", "zugangsdaten", "reisen",
                      "pruefungen", "webhooks", "feststellungen-uebernehmen");
  }

  @Test
  void searchWithBlankOrNullReturnsAllTopics() {
      assertThat(helpService.search(null)).hasSize(18);
      assertThat(helpService.search("   ")).hasSize(18);
  }
  ```
- [x] **Step 2: Run the test to verify failure**
  `./mvnw test -Dtest=HelpServiceTest` -> FAIL (expected size 18, actual 17).
- [x] **Step 3: Create `feststellungen-uebernehmen.md` and update `stummschaltungen.md`**
  Write `src/main/resources/help/feststellungen-uebernehmen.md` with clear explanation of the feature, requirements, and pattern generation.
  Add note in `src/main/resources/help/stummschaltungen.md` pointing to this helper.
- [x] **Step 4: Run the test to verify pass**
  `./mvnw test -Dtest=HelpServiceTest,HelpTopicsTest` -> PASS.
- [x] **Step 5: Commit**
  `git commit -m "docs(help): add help topic for feststellungen-uebernehmen"`

---

### Task 2: Wire the `?` Button on `/stummschaltungen`

**Files:**
- Modify: `src/main/resources/templates/stummschaltungen/index.html`
- Test: `src/test/java/dev/hendrikhoemberg/webtesthelper/web/MuteRuleControllerTest.java`
- Test: `src/test/java/dev/hendrikhoemberg/webtesthelper/web/HelpTopicsTest.java`

**Interfaces:**
- Consumes: `/hilfe/hinweis/{id}(id='feststellungen-uebernehmen')` endpoint and `wth-hilfe-modal` in `layout.html`
- Produces: `.hinweis-schalter` next to "Aus Feststellungen übernehmen" button

- [x] **Step 1: Write the failing test in `MuteRuleControllerTest.java`**
  Add assertion in `MuteRuleControllerTest.java`:
  ```java
  @Test
  @WithMockUser(username = "alice", roles = "USER")
  void indexPageRendersHelpAffordanceForAusFeststellungenUebernehmen() throws Exception {
      mvc.perform(get("/stummschaltungen"))
              .andExpect(status().isOk())
              .andExpect(content().string(containsString("/hilfe/hinweis/feststellungen-uebernehmen")))
              .andExpect(content().string(containsString("Aus Feststellungen übernehmen")));
  }
  ```
- [x] **Step 2: Run the test to verify failure**
  `./mvnw test -Dtest=MuteRuleControllerTest#indexPageRendersHelpAffordanceForAusFeststellungenUebernehmen` -> FAIL.
- [x] **Step 3: Add the `?` button to `stummschaltungen/index.html`**
  Update the button container:
  ```html
  <div class="form-gruppe" style="margin-bottom: 1rem; display: flex; align-items: center; gap: 0.5rem;">
      <button type="button" class="btn-ui btn-ui-secondary btn-ui-sm button sekundär klein"
              th:attr="data-hx-get=@{/stummschaltungen/auswahl}"
              data-hx-include="closest form"
              data-hx-target="#feststellungsauswahl"
              data-hx-swap="innerHTML"
              th:text="#{ui.stummschaltungen.neu.aus_feststellungen}">Aus Feststellungen übernehmen</button>
      <button type="button" class="hinweis-schalter" th:attr="aria-label=#{ui.hilfe.oeffnen}"
              th:data-hx-get="@{/hilfe/hinweis/{id}(id='feststellungen-uebernehmen')}"
              data-hx-target="#hilfe-modal-inhalt" data-hx-swap="innerHTML" @click="hilfeModalOffen = true">
          <span th:replace="~{fragments/icons :: help}"></span>
      </button>
      <div class="hinweis" aria-live="polite"></div>
  </div>
  ```
- [x] **Step 4: Run the test to verify pass**
  `./mvnw test -Dtest=MuteRuleControllerTest#indexPageRendersHelpAffordanceForAusFeststellungenUebernehmen,HelpTopicsTest` -> PASS.
- [x] **Step 5: Commit**
  `git commit -m "feat(web): add help affordance to aus-feststellungen-uebernehmen button"`

---

### Task 3: Full Verification

- [x] **Step 1: Run default fast verification**
  `bash -c "set -o pipefail; ./mvnw test -Pfast -B --no-transfer-progress | tail -n 50"` (Passed: 1982 tests, 0 failures)
- [x] **Step 2: Run full verification (since templates and help topics changed)**
  `bash -c "set -o pipefail; ./mvnw test -B --no-transfer-progress | tail -n 60"` (Passed: 2232 tests, 0 failures)
