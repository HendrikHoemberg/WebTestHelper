# Interactive Tutorial (Onboarding Spotlight Tour) Implementation Plan

**Goal:** Build an interactive spotlight onboarding tour for first-time users using vendored Driver.js, persisted per user in the database, with multi-page navigation across the dashboard and websites list, role-aware guidance, and manual restart options.

**Architecture:** A lightweight vendored Driver.js setup styled with the app's monochrome design system. Backend state (`tutorial_abgeschlossen`) stored in PostgreSQL on `app_user` via Flyway migration. `TutorialController` provides CSRF-aware endpoints to dismiss or restart the tour. `TutorialAdvice` injects the tutorial status into Thymeleaf models. Cross-page tour navigation between `/` and `/websites` is coordinated via `sessionStorage` in `tutorial.js`.

**Tech Stack:** Java 21, Spring Boot 3.4.x, Spring Security, Spring Data JPA, PostgreSQL 17, Flyway, Thymeleaf, Alpine.js, Driver.js 1.3.x.

**Spec:** [`docs/slimpowers/specs/2026-09-03-interactive-tutorial-design.md`](file:///home/hendrik/Documents/Coding/WebTestHelper/docs/slimpowers/specs/2026-09-03-interactive-tutorial-design.md)

## Global Constraints
- German-only UI; message keys `ui.*`; no internal identifiers (enum names, `{0}` placeholders, raw ISO instants) in rendered HTML.
- View tests: `@WebMvcTest` + MockMvc; assertions on text/markup, not on CSS.
- Desktop-only UI: mobile/responsive layout is out of scope.
- Test hygiene: always use `-B --no-transfer-progress` and pipe with `set -o pipefail` and `tail`.

---

### Task 1: Database Migration & Persistence Layer

**Files:**
- Create: `src/main/resources/db/migration/V29__app_user_tutorial_abgeschlossen.sql`
- Modify: `src/main/java/dev/hendrikhoemberg/webtesthelper/web/persistence/AppUserEntity.java`
- Modify: `src/main/java/dev/hendrikhoemberg/webtesthelper/web/AppUserService.java`
- Test: `src/test/java/dev/hendrikhoemberg/webtesthelper/web/AppUserServiceTest.java`

**Interfaces:**
- Consumes: `AppUserRepository`, `AppUserEntity`
- Produces: `boolean isTutorialAbgeschlossen(String username)` and `void setTutorialAbgeschlossen(String username, boolean abgeschlossen)` on `AppUserService`.

- [x] **Step 1: Write the failing test**
  Add `tutorialAbgeschlossenFlagDefaultAndToggle` to `AppUserServiceTest.java`:
  ```java
  @Test
  void tutorialAbgeschlossenFlagDefaultAndToggle() {
      long id = appUserService.create("tutorialUser", "password123", AppRole.USER);
      assertThat(appUserService.isTutorialAbgeschlossen("tutorialUser")).isFalse();

      appUserService.setTutorialAbgeschlossen("tutorialUser", true);
      assertThat(appUserService.isTutorialAbgeschlossen("tutorialUser")).isTrue();

      appUserService.setTutorialAbgeschlossen("tutorialUser", false);
      assertThat(appUserService.isTutorialAbgeschlossen("tutorialUser")).isFalse();
  }
  ```

- [x] **Step 2: Run the single test — verify it FAILS**
  Command: `./mvnw test -Dtest=AppUserServiceTest#tutorialAbgeschlossenFlagDefaultAndToggle -B --no-transfer-progress`
  Expected: Compilation failure or method not found.

- [x] **Step 3: Write minimal implementation**
  1. Create `V29__app_user_tutorial_abgeschlossen.sql`:
     ```sql
     ALTER TABLE app_user ADD COLUMN tutorial_abgeschlossen BOOLEAN NOT NULL DEFAULT FALSE;
     ```
  2. In `AppUserEntity.java`:
     Add field `private boolean tutorialAbgeschlossen = false;` with getter `isTutorialAbgeschlossen()` and setter `setTutorialAbgeschlossen(boolean)`.
  3. In `AppUserService.java`:
     Implement:
     ```java
     @Transactional(readOnly = true)
     public boolean isTutorialAbgeschlossen(String username) {
         return userRepository.findByUsernameIgnoreCase(username)
                 .map(AppUserEntity::isTutorialAbgeschlossen)
                 .orElse(false);
     }

     @Transactional
     public void setTutorialAbgeschlossen(String username, boolean abgeschlossen) {
         userRepository.findByUsernameIgnoreCase(username)
                 .ifPresent(u -> u.setTutorialAbgeschlossen(abgeschlossen));
     }
     ```

- [x] **Step 4: Run the single test — verify it PASSES**
  Command: `./mvnw test -Dtest=AppUserServiceTest#tutorialAbgeschlossenFlagDefaultAndToggle -B --no-transfer-progress`
  Expected: PASS.

- [x] **Step 5: Commit**
  `git commit -m "feat(tutorial): add tutorial_abgeschlossen persistence to app_user"`

---

### Task 2: TutorialController & Security Configuration

**Files:**
- Create: `src/main/java/dev/hendrikhoemberg/webtesthelper/web/TutorialController.java`
- Modify: `src/main/java/dev/hendrikhoemberg/webtesthelper/web/SecurityConfig.java`
- Test: `src/test/java/dev/hendrikhoemberg/webtesthelper/web/TutorialControllerTest.java`

**Interfaces:**
- Consumes: `AppUserService`
- Produces: `POST /tutorial/abschliessen` (HTTP 204), `POST /tutorial/neustarten` (HTTP 302 redirect to `/?tour=start`)

- [x] **Step 1: Write the failing test**
  Create `TutorialControllerTest.java`:
  ```java
  package dev.hendrikhoemberg.webtesthelper.web;

  import org.junit.jupiter.api.Test;
  import org.springframework.beans.factory.annotation.Autowired;
  import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
  import org.springframework.security.test.context.support.WithMockUser;
  import org.springframework.test.context.bean.override.mockito.MockitoBean;
  import org.springframework.test.web.servlet.MockMvc;

  import static org.mockito.Mockito.verify;
  import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
  import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
  import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
  import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
  import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

  @org.junit.jupiter.api.parallel.ResourceLock("spring-context")
  @WebMvcTest(TutorialController.class)
  class TutorialControllerTest {

      @Autowired
      MockMvc mvc;

      @MockitoBean
      AppUserService appUserService;

      @Test
      @WithMockUser(username = "hans")
      void abschliessenReturns204AndMarksComplete() throws Exception {
          mvc.perform(post("/tutorial/abschliessen").with(csrf()))
                  .andExpect(status().isNoContent());

          verify(appUserService).setTutorialAbgeschlossen("hans", true);
      }

      @Test
      @WithMockUser(username = "hans")
      void neustartenResetsFlagAndRedirects() throws Exception {
          mvc.perform(post("/tutorial/neustarten").with(csrf()))
                  .andExpect(status().is3xxRedirection())
                  .andExpect(redirectedUrl("/?tour=start"));

          verify(appUserService).setTutorialAbgeschlossen("hans", false);
      }

      @Test
      void unauthenticatedRejected() throws Exception {
          mvc.perform(post("/tutorial/abschliessen").with(csrf()))
                  .andExpect(status().is3xxRedirection())
                  .andExpect(header().string("Location", org.hamcrest.Matchers.containsString("/anmelden")));
      }
  }
  ```

- [x] **Step 2: Run the single test — verify it FAILS**
  Command: `./mvnw test -Dtest=TutorialControllerTest -B --no-transfer-progress`
  Expected: FAIL (class `TutorialController` does not exist).

- [x] **Step 3: Write minimal implementation**
  1. Create `TutorialController.java`:
     ```java
     package dev.hendrikhoemberg.webtesthelper.web;

     import org.springframework.http.ResponseEntity;
     import org.springframework.stereotype.Controller;
     import org.springframework.web.bind.annotation.PostMapping;
     import org.springframework.web.bind.annotation.RequestMapping;

     import java.security.Principal;

     @Controller
     @RequestMapping("/tutorial")
     public class TutorialController {

         private final AppUserService userService;

         public TutorialController(AppUserService userService) {
             this.userService = userService;
         }

         @PostMapping("/abschliessen")
         public ResponseEntity<Void> abschliessen(Principal principal) {
             if (principal != null) {
                 userService.setTutorialAbgeschlossen(principal.getName(), true);
             }
             return ResponseEntity.noContent().build();
         }

         @PostMapping("/neustarten")
         public String neustarten(Principal principal) {
             if (principal != null) {
                 userService.setTutorialAbgeschlossen(principal.getName(), false);
             }
             return "redirect:/?tour=start";
         }
     }
     ```
  2. Update `SecurityConfig.java`: ensure `/tutorial/**` is covered by `.authenticated()`.

- [x] **Step 4: Run the single test — verify it PASSES**
  Command: `./mvnw test -Dtest=TutorialControllerTest -B --no-transfer-progress`
  Expected: PASS.

- [x] **Step 5: Commit**
  `git commit -m "feat(tutorial): add TutorialController for tour dismissal and restart"`

---

### Task 3: Global Controller Advice & German Localization

**Files:**
- Create: `src/main/java/dev/hendrikhoemberg/webtesthelper/web/TutorialAdvice.java`
- Modify: `src/main/resources/messages.properties`
- Test: `src/test/java/dev/hendrikhoemberg/webtesthelper/web/TutorialAdviceTest.java`

**Interfaces:**
- Consumes: `AppUserService`, `Principal`, `@RequestParam(name = "tour")`
- Produces: `tutorialOffen` boolean attribute in Thymeleaf models; localized keys in `messages.properties`.

- [x] **Step 1: Write the failing test**
  Create `TutorialAdviceTest.java`:
  ```java
  package dev.hendrikhoemberg.webtesthelper.web;

  import org.junit.jupiter.api.Test;
  import org.springframework.beans.factory.ObjectProvider;
  import org.springframework.ui.ConcurrentModel;
  import org.springframework.ui.Model;

  import java.security.Principal;

  import static org.assertj.core.api.Assertions.assertThat;
  import static org.mockito.Mockito.mock;
  import static org.mockito.Mockito.when;

  class TutorialAdviceTest {

      @Test
      void unauthenticatedYieldsFalse() {
          @SuppressWarnings("unchecked")
          ObjectProvider<AppUserService> provider = mock(ObjectProvider.class);
          TutorialAdvice advice = new TutorialAdvice(provider);
          Model model = new ConcurrentModel();

          advice.tutorialModel(model, null, null);
          assertThat(model.getAttribute("tutorialOffen")).isEqualTo(false);
      }

      @Test
      void authenticatedIncompleteYieldsTrue() {
          AppUserService service = mock(AppUserService.class);
          when(service.isTutorialAbgeschlossen("anna")).thenReturn(false);

          @SuppressWarnings("unchecked")
          ObjectProvider<AppUserService> provider = mock(ObjectProvider.class);
          when(provider.getIfAvailable()).thenReturn(service);

          TutorialAdvice advice = new TutorialAdvice(provider);
          Model model = new ConcurrentModel();
          Principal principal = () -> "anna";

          advice.tutorialModel(model, principal, null);
          assertThat(model.getAttribute("tutorialOffen")).isEqualTo(true);
      }

      @Test
      void tourStartParamOverridesCompletedStatus() {
          AppUserService service = mock(AppUserService.class);
          when(service.isTutorialAbgeschlossen("anna")).thenReturn(true);

          @SuppressWarnings("unchecked")
          ObjectProvider<AppUserService> provider = mock(ObjectProvider.class);
          when(provider.getIfAvailable()).thenReturn(service);

          TutorialAdvice advice = new TutorialAdvice(provider);
          Model model = new ConcurrentModel();
          Principal principal = () -> "anna";

          advice.tutorialModel(model, principal, "start");
          assertThat(model.getAttribute("tutorialOffen")).isEqualTo(true);
      }
  }
  ```

- [x] **Step 2: Run the single test — verify it FAILS**
  Command: `./mvnw test -Dtest=TutorialAdviceTest -B --no-transfer-progress`
  Expected: FAIL (class `TutorialAdvice` does not exist).

- [x] **Step 3: Write minimal implementation**
  1. Create `TutorialAdvice.java`:
     ```java
     package dev.hendrikhoemberg.webtesthelper.web;

     import org.springframework.beans.factory.ObjectProvider;
     import org.springframework.ui.Model;
     import org.springframework.web.bind.annotation.ControllerAdvice;
     import org.springframework.web.bind.annotation.ModelAttribute;
     import org.springframework.web.bind.annotation.RequestParam;

     import java.security.Principal;

     @ControllerAdvice
     public class TutorialAdvice {

         private final ObjectProvider<AppUserService> userServiceProvider;

         public TutorialAdvice(ObjectProvider<AppUserService> userServiceProvider) {
             this.userServiceProvider = userServiceProvider;
         }

         @ModelAttribute
         public void tutorialModel(Model model, Principal principal,
                                   @RequestParam(name = "tour", required = false) String tourParam) {
             if (principal == null) {
                 model.addAttribute("tutorialOffen", false);
                 return;
             }
             if ("start".equalsIgnoreCase(tourParam)) {
                 model.addAttribute("tutorialOffen", true);
                 return;
             }
             AppUserService service = userServiceProvider.getIfAvailable();
             boolean abgeschlossen = service != null && service.isTutorialAbgeschlossen(principal.getName());
             model.addAttribute("tutorialOffen", !abgeschlossen);
         }
     }
     ```
  2. Add `ui.tutorial.*` message keys to `src/main/resources/messages.properties`.

- [x] **Step 4: Run the single test — verify it PASSES**
  Command: `./mvnw test -Dtest=TutorialAdviceTest -B --no-transfer-progress`
  Expected: PASS.

- [x] **Step 5: Commit**
  `git commit -m "feat(tutorial): add TutorialAdvice and localized messages for onboarding tour"`

---

### Task 4: Vendored Driver.js Assets & Styling Overrides

**Files:**
- Create: `src/main/resources/static/vendor/driver.js`
- Create: `src/main/resources/static/vendor/driver.css`
- Modify: `src/main/resources/static/css/app.css`

**Interfaces:**
- Produces: `window.driver.js.driver` available in browser; `.driver-popover` styled to match WebTestHelper monochrome carbon design system.

- [x] **Step 1: Download/create vendored assets**
  Download Driver.js v1.3.1 IIFE build to `src/main/resources/static/vendor/driver.js` and `driver.css` to `src/main/resources/static/vendor/driver.css`.

- [x] **Step 2: Add theme overrides to `app.css`**
  Add styles in `src/main/resources/static/css/app.css` overriding `.driver-popover`, `.driver-popover-title`, `.driver-popover-description`, and navigation buttons using CSS custom properties (`--bg-canvas`, `--surface-card`, `--border-subtle`, `--text-main`, `--text-body`, `--btn-ui-*`).

- [x] **Step 3: Verify build / assets presence**
  Command: `test -f src/main/resources/static/vendor/driver.js && test -f src/main/resources/static/vendor/driver.css`
  Expected: Exit code 0.

- [x] **Step 4: Commit**
  `git commit -m "feat(tutorial): vendor driver.js and add theme overrides to app.css"`

---

### Task 5: Frontend Tour Script & Layout / Help Integration

**Files:**
- Create: `src/main/resources/static/js/tutorial.js`
- Modify: `src/main/resources/templates/layout.html`
- Modify: `src/main/resources/templates/hilfe/index.html`
- Test: `src/test/java/dev/hendrikhoemberg/webtesthelper/web/LayoutTutorialRenderingTest.java`

**Interfaces:**
- Consumes: `#wth-tutorial-config` data attributes, `driver.js`
- Produces: Interactive spotlight tour with cross-page navigation between `/` and `/websites`, manual restart button in sidebar and help center.

- [x] **Step 1: Write the failing test**
  Create `LayoutTutorialRenderingTest.java` testing layout rendering when user is authenticated:
  Asserts that `layout.html` renders `<div id="wth-tutorial-config"` with `data-auto-start`, includes `vendor/driver.js` and `js/tutorial.js`, and renders the "Tour starten" button in the sidebar footer.

- [x] **Step 2: Run the single test — verify it FAILS**
  Command: `./mvnw test -Dtest=LayoutTutorialRenderingTest -B --no-transfer-progress`
  Expected: FAIL (missing tutorial markup/scripts in layout).

- [x] **Step 3: Write minimal implementation**
  1. In `src/main/resources/templates/layout.html`:
     - In `<head>`: include `<link rel="stylesheet" th:href="@{/vendor/driver.css}">`.
     - Before `</body>`: include `<script defer th:src="@{/vendor/driver.js}"></script>` and `<script defer th:src="@{/js/tutorial.js}"></script>`.
     - Render hidden `<div id="wth-tutorial-config" ...>` with localized strings and flags.
     - In sidebar footer (`.sidebar-user-footer`), add a button/form to restart the tour:
       ```html
       <form th:action="@{/tutorial/neustarten}" method="post" class="inline-form">
           <button type="submit" class="btn-sidebar-logout" th:title="#{ui.tutorial.schaltflaeche.neustart}">
               <span th:replace="~{fragments/icons :: help}"></span>
           </button>
       </form>
       ```
  2. In `src/main/resources/templates/hilfe/index.html`:
     - Add a "Geführte Tour" quick action card at the top allowing users to trigger `POST /tutorial/neustarten`.
  3. Create `src/main/resources/static/js/tutorial.js`:
     - Initialize Driver.js when `data-auto-start="true"` or `sessionStorage.getItem('wth_tour_step')` is active.
     - Step 1 (Dashboard): Welcome modal popover.
     - Step 2 (Dashboard): Sidebar navigation (`.sidebar-nav-scroll`).
     - Step 3 (Dashboard): Websites navigation link (`a[href='/websites']`). On Next: `sessionStorage.setItem('wth_tour_step', 'websites'); window.location.href = '/websites';`.
     - Step 4 (`/websites`): If admin, highlight `a[href='/websites/neu']`; if user, highlight `.site-liste`.
     - Step 5 (`/websites`): Highlight Stummschaltungen & Hilfe in sidebar.
     - Step 6 (`/websites`): Congratulations popover. On finish/close: clear `sessionStorage` and send `POST /tutorial/abschliessen`.

- [x] **Step 4: Run the single test — verify it PASSES**
  Command: `./mvnw test -Dtest=LayoutTutorialRenderingTest -B --no-transfer-progress`
  Expected: PASS.

- [x] **Step 5: Commit**
  `git commit -m "feat(tutorial): implement tutorial.js tour orchestration and integrate into layout and help center"`

---

### Task 6: Full Verification Suite

- [x] **Step 1: Run project verification gate**
  Command: `bash -c "set -o pipefail; ./mvnw test -Pfast -B --no-transfer-progress | tail -n 50"`
  Expected: All fast suite tests pass, 0 failures, 0 errors.

- [x] **Step 2: Commit final review**
  `git commit -m "chore(tutorial): complete interactive onboarding tutorial verification"`
