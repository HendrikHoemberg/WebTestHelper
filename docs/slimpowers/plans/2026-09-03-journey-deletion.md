# Journey Deletion Implementation Plan

**Goal:** Enable deleting recorded journeys from the Web UI with confirmation modals and flash feedback.

**Architecture:** Expose a `POST /websites/{siteId}/journeys/{journeyId}/loeschen` endpoint in `JourneyController` that delegates to the existing `JourneyService.delete(journeyId)`. Integrate delete buttons and confirmation modals (`wth-modal`) into the journey list (`journey/list.html`) and detail view (`journey/detail.html`), using German UI copy from `messages.properties`.

**Tech Stack:** Spring Boot 4, Spring MVC, Spring Security, Thymeleaf, Alpine.js, JUnit 5, MockMvc, AssertJ.

**Spec:** User request: enable deleting recorded journeys.

## Global Constraints

- German-only UI; all copy in `src/main/resources/messages.properties` under `ui.journey.*`.
- View tests use `@WebMvcTest` and assert on text/markup.
- No internal identifiers in rendered HTML.
- Modals follow existing `wth-modal` convention with Alpine.js (`x-show`, `x-cloak`, `@keydown.escape.window`).

---

### Task 1: Controller Endpoint & Unit Tests for Journey Deletion

**Files:**
- Modify: `src/main/java/dev/hendrikhoemberg/webtesthelper/web/JourneyController.java` — add `deleteJourney` method
- Modify: `src/main/resources/messages.properties` — add deletion copy
- Test: `src/test/java/dev/hendrikhoemberg/webtesthelper/web/JourneyControllerTest.java` — test successful deletion, 404 on unknown journey, and site mismatch

**Interfaces:**
- Consumes: `JourneyService.findDefinition(journeyId) -> Optional<JourneyDefinition>`, `JourneyService.delete(journeyId) -> void`
- Produces: `POST /websites/{siteId}/journeys/{journeyId}/loeschen -> redirect:/websites/{siteId}/journeys` with flash message

- [x] **Step 1: Write the failing tests**
  Add test methods to `JourneyControllerTest.java`:
  ```java
  @Test
  @WithMockUser
  void deleteJourney_removesJourneyAndRedirectsWithFlashMessage() throws Exception {
      long siteId = 1L;
      long journeyId = 42L;
      JourneyDefinition journey = new JourneyDefinition(journeyId, siteId, "Checkout Test", true, List.of());
      when(journeyService.findDefinition(journeyId)).thenReturn(Optional.of(journey));

      mvc.perform(post("/websites/{siteId}/journeys/{journeyId}/loeschen", siteId, journeyId)
                      .with(csrf()))
              .andExpect(status().is3xxRedirection())
              .andExpect(redirectedUrl("/websites/1/journeys"))
              .andExpect(flash().attributeExists("flashMessage"));

      org.mockito.Mockito.verify(journeyService).delete(journeyId);
  }

  @Test
  @WithMockUser
  void deleteJourney_whenNotFound_returns404() throws Exception {
      when(journeyService.findDefinition(999L)).thenReturn(Optional.empty());

      mvc.perform(post("/websites/1/journeys/999/loeschen")
                      .with(csrf()))
              .andExpect(status().isNotFound());
  }

  @Test
  @WithMockUser
  void deleteJourney_whenSiteMismatch_returns404() throws Exception {
      long journeyId = 42L;
      JourneyDefinition journey = new JourneyDefinition(journeyId, 2L, "Different Site", true, List.of());
      when(journeyService.findDefinition(journeyId)).thenReturn(Optional.of(journey));

      mvc.perform(post("/websites/1/journeys/{journeyId}/loeschen", journeyId)
                      .with(csrf()))
              .andExpect(status().isNotFound());
  }
  ```

- [x] **Step 2: Run the single test — verify it FAILS**
  Command: `./mvnw test -Dtest=JourneyControllerTest#deleteJourney_removesJourneyAndRedirectsWithFlashMessage -B --no-transfer-progress`
  Expected: FAIL (404/no handler for POST `/websites/1/journeys/42/loeschen`)

- [x] **Step 3: Add message keys and implement `deleteJourney`**
  In `src/main/resources/messages.properties`:
  ```properties
  ui.journey.loeschen=Löschen
  ui.journey.detail.loeschen=Ablauf löschen
  ui.journey.loeschen.dialog.titel=Ablauf „{0}“ löschen
  ui.journey.loeschen.bestaetigung=Möchten Sie diesen Ablauf wirklich löschen? Alle zugehörigen Schritte und Prüfergebnisse werden unwiderruflich entfernt.
  ui.journey.loeschen.bestaetigen=Jetzt löschen
  ui.journey.geloescht=Ablauf „{0}“ wurde gelöscht.
  ```

  In `src/main/java/dev/hendrikhoemberg/webtesthelper/web/JourneyController.java`:
  ```java
  @PostMapping("/websites/{siteId}/journeys/{journeyId}/loeschen")
  public String deleteJourney(@PathVariable("siteId") long siteId,
                              @PathVariable("journeyId") long journeyId,
                              org.springframework.web.servlet.mvc.support.RedirectAttributes redirectAttributes,
                              Locale locale) {
      JourneyDefinition journey = journeyService.findDefinition(journeyId)
              .filter(j -> Objects.equals(j.siteId(), siteId))
              .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Ablauf nicht gefunden: " + journeyId));

      journeyService.delete(journeyId);
      String successMsg = messageSource.getMessage(
              "ui.journey.geloescht", new Object[]{journey.name()}, locale);
      redirectAttributes.addFlashAttribute("flashMessage", successMsg);
      return "redirect:/websites/" + siteId + "/journeys";
  }
  ```

- [x] **Step 4: Run the single test — verify it PASSES**
  Command: `./mvnw test -Dtest=JourneyControllerTest -B --no-transfer-progress`
  Expected: PASS

- [ ] **Step 5: Commit**
  `git commit -m "feat(journey): add journey deletion endpoint and messages"`

---

### Task 2: UI Delete Button & Modal in Journey Detail & List Views

**Files:**
- Modify: `src/main/resources/templates/journey/detail.html` — add "Ablauf löschen" button and confirmation modal to header actions
- Modify: `src/main/resources/templates/journey/list.html` — add "Löschen" button and confirmation modal to table actions
- Modify: `src/main/resources/templates/journey/edit.html` — add "Ablauf löschen" button and confirmation modal
- Test: `src/test/java/dev/hendrikhoemberg/webtesthelper/web/JourneyControllerTest.java` — verify delete modal and button are rendered in list and detail views

**Interfaces:**
- Consumes: `GET /websites/{siteId}/journeys`, `GET /websites/{siteId}/journeys/{journeyId}`
- Produces: HTML markup containing delete action button triggering modal with form POST to `/websites/{siteId}/journeys/{journeyId}/loeschen`

- [x] **Step 1: Write the failing tests**
  In `JourneyControllerTest.java`:
  ```java
  @Test
  @WithMockUser
  void detail_rendersDeleteButtonAndModal() throws Exception {
      long siteId = 1L;
      long journeyId = 42L;
      JourneyDefinition journey = new JourneyDefinition(journeyId, siteId, "Warenkorb", true, List.of());
      when(journeyService.findDefinition(journeyId)).thenReturn(Optional.of(journey));

      mvc.perform(get("/websites/{siteId}/journeys/{journeyId}", siteId, journeyId))
              .andExpect(status().isOk())
              .andExpect(content().string(containsString("Ablauf löschen")))
              .andExpect(content().string(containsString("/websites/1/journeys/42/loeschen")));
  }

  @Test
  @WithMockUser
  void list_rendersDeleteButtonAndModalForEachJourney() throws Exception {
      long siteId = 1L;
      JourneyDefinition journey = new JourneyDefinition(42L, siteId, "Warenkorb", true, List.of());
      when(journeyService.findBySite(siteId)).thenReturn(List.of(journey));

      mvc.perform(get("/websites/{siteId}/journeys", siteId))
              .andExpect(status().isOk())
              .andExpect(content().string(containsString("/websites/1/journeys/42/loeschen")));
  }
  ```

- [x] **Step 2: Run the tests — verify they FAIL**
  Command: `./mvnw test -Dtest=JourneyControllerTest#detail_rendersDeleteButtonAndModal -B --no-transfer-progress`
  Expected: FAIL

- [x] **Step 3: Update `detail.html`, `list.html`, and `edit.html`**
  - In `detail.html`:
    Wrap header actions with `x-data="{ loeschenOffen: false }"`:
    Add button:
    ```html
    <button type="button" class="btn-ui btn-ui-secondary btn-danger-hover button sekundär"
            @click="loeschenOffen = true"
            th:text="#{ui.journey.detail.loeschen}">Ablauf löschen</button>
    ```
    Add modal:
    ```html
    <div x-cloak x-show="loeschenOffen" @keydown.escape.window="loeschenOffen = false" class="wth-modal text-links" style="text-align: left;">
        <div class="wth-modal-backdrop" @click.self="loeschenOffen = false"></div>
        <div class="wth-modal-dialog" role="dialog" aria-modal="true" aria-labelledby="modal-loeschen-titel">
            <div class="wth-modal-kopf">
                <h2 id="modal-loeschen-titel" class="wth-modal-titel" th:text="#{ui.journey.loeschen.dialog.titel(${journey.name})}">Ablauf löschen</h2>
                <button type="button" class="wth-modal-schliessen" @click="loeschenOffen = false"
                        th:attr="aria-label=#{ui.dialog.schliessen}, title=#{ui.dialog.schliessen}">×</button>
            </div>
            <div class="wth-modal-inhalt">
                <p th:text="#{ui.journey.loeschen.bestaetigung}" style="margin-top: 0; margin-bottom: 1.25rem;">
                    Möchten Sie diesen Ablauf wirklich löschen? Alle zugehörigen Schritte und Prüfergebnisse werden unwiderruflich entfernt.
                </p>
                <form th:action="@{/websites/{siteId}/journeys/{journeyId}/loeschen(siteId=${site.siteId},journeyId=${journey.id})}" method="post" class="inline-form" style="display: flex; gap: 0.5rem; justify-content: flex-end;">
                    <button type="button" @click="loeschenOffen = false" class="btn-ui btn-ui-secondary button sekundär" th:text="#{ui.dialog.abbrechen}">Abbrechen</button>
                    <button type="submit" class="btn-ui btn-ui-danger button gefahr" th:text="#{ui.journey.loeschen.bestaetigen}">Jetzt löschen</button>
                </form>
            </div>
        </div>
    </div>
    ```

  - In `list.html`:
    In the actions column `<td>`:
    Wrap with `x-data="{ loeschenOffen: false }"`:
    Add button:
    ```html
    <button type="button" class="btn-ui btn-ui-secondary btn-danger-hover btn-ui-sm"
            @click="loeschenOffen = true"
            style="margin-left: 0.4rem;"
            th:text="#{ui.journey.loeschen}">Löschen</button>
    ```
    Add per-row modal with dynamic ID `modal-loeschen-journey-${journey.id}`.

  - In `edit.html`:
    In header actions, add `Ablauf löschen` modal button as well.

- [x] **Step 4: Run tests — verify they PASS**
  Command: `./mvnw test -Dtest=JourneyControllerTest -B --no-transfer-progress`
  Expected: PASS

- [x] **Step 5: Run full verification**
  Command: `bash -c "set -o pipefail; ./mvnw test -Pfast -B --no-transfer-progress | tail -n 50"`
  Expected: All tests pass.

- [x] **Step 6: Commit**
  `git commit -m "feat(journey): add delete buttons and confirmation modals in UI"`
