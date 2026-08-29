# WebTestHelper UI-Bugfixes Implementation Plan

**Goal:** Fix the four findings from the browser audit of 2026-08-29: the login redirect loop (critical), the unstyled login page, the raw 500 on duplicate base URLs, and the permanently visible empty help boxes.

**Architecture:** Three independent bugfixes, one task each. The login loop's root cause is the missing `/favicon.ico`: every real browser requests it, Tomcat 404s it, the error dispatch trips Spring Security's request cache, and the post-login success handler redirects to the poisoned saved request `/error?continue`, which 500s. Fix it twice (ship a favicon + make the request cache ignore error dispatches) and guard it with a real-browser acceptance test. The other two bugs are view/CSS and controller-validation fixes with MockMvc and unit tests.

**Tech Stack:** Spring Boot 4.1.1, Spring Security 7.1, Thymeleaf, Postgres 17 (Testcontainers), Playwright for Java (bundled Chromium, `@Tag("browser")` suite), Maven.

**Spec:** `docs/superpowers/specs/2026-08-21-webtesthelper-design.md` (§12 Web UI — local assets, no CDN; §6.3 triage; §1 trust).

## Global Constraints

- German UI only; every visible string goes through `src/main/resources/messages.properties`.
- No new runtime dependencies; favicon is a checked-in binary, generated once with Python (stdlib only).
- `mvnw test` runs everything including browser tests; `mvnw test -Pfast` skips `@Tag("browser")` only.
- Web-layer tests follow the existing conventions: `@WebMvcTest` slices with `@Import`, acceptance tests extend `AbstractPostgresTest` (real Postgres via Testcontainers).
- The favicon loop regression test must be a browser test in the `browser` tag (MockMvc cannot reproduce error-dispatch behavior).

---

### Task 1: Fix the login redirect loop (`/favicon.ico` 404 → `/error?continue` 500)

**Files:**
- Create: `src/main/resources/static/favicon.ico` (binary, generated once with the snippet in Step 3)
- Create: `src/main/java/dev/hendrikhoemberg/webtesthelper/web/SkipErrorDispatchRequestCache.java`
- Modify: `src/main/java/dev/hendrikhoemberg/webtesthelper/web/SecurityConfig.java`
- Create: `src/test/java/dev/hendrikhoemberg/webtesthelper/web/SkipErrorDispatchRequestCacheTest.java`
- Create: `src/test/java/dev/hendrikhoemberg/webtesthelper/web/LoginFlowBrowserAcceptanceTest.java`

**Interfaces:**
- Produces: `SkipErrorDispatchRequestCache` (extends `HttpSessionRequestCache`, skips `saveRequest` on error dispatches); wired in `SecurityConfig` via `http.requestCache(rc -> rc.requestCache(cache))` (Spring Security 7 API verified against `RequestCacheConfigurer`).
- `GET /favicon.ico` returns 200 from static resources (no save/error dispatch ever happens on it).

- [ ] **Step 1: Write the failing browser test — the loop must not reproduce**

`src/test/java/dev/hendrikhoemberg/webtesthelper/web/LoginFlowBrowserAcceptanceTest.java`:

```java
package dev.hendrikhoemberg.webtesthelper.web;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import dev.hendrikhoemberg.webtesthelper.catalog.AppRole;
import dev.hendrikhoemberg.webtesthelper.catalog.AppUserService;
import dev.hendrikhoemberg.webtesthelper.support.AbstractPostgresTest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression test for a real-browser failure MockMvc cannot show: Chromium requests
 * /favicon.ico on page load, the app had none, the 404's error dispatch was saved by
 * Spring Security's request cache, and the login success handler redirected into
 * /error?continue (HTTP 500). Real browser, real Postgres, real server.
 */
@Tag("browser")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LoginFlowBrowserAcceptanceTest extends AbstractPostgresTest {

    @LocalServerPort
    int port;

    @Autowired
    AppUserService appUserService;

    Playwright playwright;
    Browser browser;

    @BeforeAll
    void setUserAndLaunch() {
        appUserService.create("browser-login", "test-pass-42", AppRole.ADMIN);
        playwright = Playwright.create();
        browser = playwright.chromium().launch(new BrowserType.LaunchOptions().setHeadless(true));
    }

    @AfterAll
    void closeBrowser() {
        if (browser != null) {
            browser.close();
        }
        if (playwright != null) {
            playwright.close();
        }
    }

    private String base() {
        return "http://localhost:" + port;
    }

    @Test
    void faviconIsServed() {
        Page page = browser.newPage();
        assertThat(page.request().get(base() + "/favicon.ico").status()).isEqualTo(200);
        page.close();
    }

    @Test
    void loginWithRealBrowserLandsOnDashboard() {
        Page page = browser.newPage();
        page.navigate(base() + "/anmelden");
        page.fill("input[name='username']", "browser-login");
        page.fill("input[name='password']", "test-pass-42");
        page.click("button[type='submit']");
        page.waitForURL("**/", new Page.WaitForURLOptions().setTimeout(15000));
        assertThat(page.url()).startsWith(base() + "/");
        assertThat(page.url()).doesNotContain("/error");
        assertThat(page.locator(".anmelden-karte").count()).isEqualTo(1);
        assertThat(page.locator(".hinweis:empty").first()
                .evaluate("el => getComputedStyle(el).display")).isEqualTo("none");
        page.close();
    }
}
```

(Note: the `.anmelden-karte` and `.hinweis:empty` assertions belong to Task 2; they are added to the file here so the browser test is written once and only extended later.)

- [ ] **Step 2: Run test — verify it FAILS**

`./mvnw test -Dtest=LoginFlowBrowserAcceptanceTest` → expected: FAIL. `loginWithRealBrowserLandsOnDashboard` times out on `waitForURL("**/")` or the `doesNotContain("/error")` assertion fires (page lands on `/error?continue`); `faviconIsServed` fails with 404. This is the loop reproduced under test.

- [ ] **Step 3: Ship the favicon**

From the repo root, run once (stdlib Python, 16×16 PNG-in-ICO, brand colour `#3f3f8c` — a designer may replace the asset later, the regression test only checks 200):

```bash
python3 - <<'EOF'
import struct, zlib

def chunk(tag, data):
    c = tag + data
    return struct.pack('>I', len(data)) + c + struct.pack('>I', zlib.crc32(c) & 0xffffffff)

rows = b''.join(b'\x00' + bytes([0x3f, 0x3f, 0x8c, 0xff]) * 16 for _ in range(16))
png = (b'\x89PNG\r\n\x1a\n'
       + chunk(b'IHDR', struct.pack('>IIBBBBB', 16, 16, 8, 6, 0, 0, 0))
       + chunk(b'IDAT', zlib.compress(rows))
       + chunk(b'IEND', b''))
ico = struct.pack('<HHH', 0, 1, 1) + struct.pack('<BBBBHHII', 16, 16, 0, 0, 1, 1, len(png), 22) + png
with open('src/main/resources/static/favicon.ico', 'wb') as f:
    f.write(ico)
print('written: src/main/resources/static/favicon.ico')
EOF
```

Verify: `file src/main/resources/static/favicon.ico` reports `MS Windows icon resource - 1 icon, 16x16`.

- [ ] **Step 4: Harden the request cache against error dispatches**

`src/main/java/dev/hendrikhoemberg/webtesthelper/web/SkipErrorDispatchRequestCache.java`:

```java
package dev.hendrikhoemberg.webtesthelper.web;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.web.savedrequest.HttpSessionRequestCache;

import java.io.IOException;

/**
 * A request cache that never saves error dispatches. The {@code ExceptionTranslationFilter}
 * saves the original request when an anonymous user hits a protected URL; for an error
 * dispatch (a 404 beneath a missing asset, say) the saved value is a bogus {@code /error}
 * URL that the login success handler then redirects to — the classic login loop. Real user
 * requests are unaffected; only internal error/async dispatches are skipped.
 */
public class SkipErrorDispatchRequestCache extends HttpSessionRequestCache {

    @Override
    public void saveRequest(HttpServletRequest request, HttpServletResponse response) throws IOException {
        if (request.getDispatcherType() == DispatcherType.ERROR) {
            return;
        }
        super.saveRequest(request, response);
    }
}
```

`src/test/java/dev/hendrikhoemberg/webtesthelper/web/SkipErrorDispatchRequestCacheTest.java`:

```java
package dev.hendrikhoemberg.webtesthelper.web;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;

import static org.assertj.core.api.Assertions.assertThat;

class SkipErrorDispatchRequestCacheTest {

    private final SkipErrorDispatchRequestCache cache = new SkipErrorDispatchRequestCache();
    private static final String SAVED =
            "SPRING_SECURITY_SAVED_REQUEST";

    @Test
    void errorDispatchIsNeverSaved() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/");
        request.setDispatcherType(jakarta.servlet.DispatcherType.ERROR);
        request.setSession(new MockHttpSession());

        cache.saveRequest(request, new MockHttpServletResponse());

        assertThat(request.getSession().getAttribute(SAVED)).isNull();
    }

    @Test
    void normalRequestIsSavedAsBefore() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/befunde");
        request.setDispatcherType(jakarta.servlet.DispatcherType.REQUEST);
        request.setSession(new MockHttpSession());

        cache.saveRequest(request, new MockHttpServletResponse());

        assertThat(request.getSession().getAttribute(SAVED)).isNotNull();
    }
}
```

- [ ] **Step 5: Wire the cache into the security chain**

In `src/main/java/dev/hendrikhoemberg/webtesthelper/web/SecurityConfig.java`, inside `filterChain(HttpSecurity http)`, add the request cache before `authorizeHttpRequests`:

```java
http.requestCache(rc -> rc.requestCache(new SkipErrorDispatchRequestCache()))
    .authorizeHttpRequests(...)
```

(API verified: `HttpSecurity.requestCache(Customizer<RequestCacheConfigurer>)` is the only 7.x overload; `RequestCacheConfigurer.requestCache(RequestCache)` takes the instance.)

- [ ] **Step 6: Run the tests — verify they PASS**

`./mvnw test -Dtest=LoginFlowBrowserAcceptanceTest,SkipErrorDispatchRequestCacheTest` → expected: PASS (browser test needs Chromium; run without `-Pfast`). The second test fails if the same login-validates-correctly path could regress.

- [ ] **Step 7: Commit**

`git commit -m "fix(web): prevent login redirect loop caused by missing favicon"`

---

### Task 2: Style the login page and hide empty help boxes

**Files:**
- Modify: `src/main/resources/templates/anmelden.html`
- Modify: `src/main/resources/static/css/app.css`
- Modify: `src/test/java/dev/hendrikhoemberg/webtesthelper/web/SecurityRulesTest.java`
- (extends the browser test from Task 1 — `.anmelden-karte` and `.hinweis:empty` assertions were already added there)

**Interfaces:**
- Produces: `/anmelden` renders with `app.css` loaded and the `.anmelden-karte` wrapper; any empty `.hinweis` is hidden via CSS (the HTMX `?` affordance targets — they are empty until clicked, so they must not render a visible green box).

- [ ] **Step 1: Write the failing test**

In `src/test/java/dev/hendrikhoemberg/webtesthelper/web/SecurityRulesTest.java`, extend `anonymousAnmeldenReturnsOk` and add the stylesheet assertion:

```java
@Test
void anonymousAnmeldenReturnsOk() throws Exception {
    mvc.perform(get("/anmelden"))
            .andExpect(status().isOk())
            .andExpect(content().string(containsString("/css/app.css")));
}
```

Add the import; the existing test body is replaced entirely:

```java
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
```

- [ ] **Step 2: Run test — verify it FAILS**

`./mvnw test -Dtest=SecurityRulesTest` → expected: FAIL (`/css/app.css` not in the rendered login page).

- [ ] **Step 3: Rewrite the login template**

`src/main/resources/templates/anmelden.html` becomes:

```html
<!DOCTYPE html>
<html lang="de" xmlns:th="http://www.thymeleaf.org">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title th:text="#{ui.anmelden.titel}">Anmelden</title>
    <link rel="stylesheet" th:href="@{/css/app.css}">
</head>
<body>
    <main class="anmelden-karte">
        <h1 th:text="#{ui.anmelden.titel}">Anmelden</h1>
        <div th:if="${param.error}" class="fehler" th:text="#{ui.anmelden.fehler}">Benutzername oder Passwort ist nicht korrekt.</div>
        <div th:if="${param.abgemeldet}" class="hinweis" th:text="#{ui.anmelden.abgemeldet}">Sie wurden erfolgreich abgemeldet.</div>
        <form th:action="@{/anmelden}" method="post">
            <div class="form-gruppe">
                <label for="username" th:text="#{ui.anmelden.benutzer}">Benutzername</label>
                <input type="text" id="username" name="username" required autofocus>
            </div>
            <div class="form-gruppe">
                <label for="password" th:text="#{ui.anmelden.passwort}">Passwort</label>
                <input type="password" id="password" name="password" required>
            </div>
            <div class="form-aktionen">
                <button type="submit" class="button primär" th:text="#{ui.anmelden.absenden}">Anmelden</button>
            </div>
        </form>
    </main>
</body>
</html>
```

- [ ] **Step 4: Add the CSS**

Append to `src/main/resources/static/css/app.css` (reusing the existing colour variables and radius values):

```css
/* Anmeldeseite */
.anmelden-karte {
    max-width: 380px;
    margin: 6rem auto 0;
    padding: 2rem;
    background-color: var(--card-bg);
    border-radius: 8px;
    box-shadow: 0 1px 4px rgba(0, 0, 0, 0.15);
}

/* Help boxes: empty until the ?-affordance loads its HTMX fragment — never show an empty box. */
.hinweis:empty {
    display: none;
}
```

- [ ] **Step 5: Run the tests — verify they PASS**

`./mvnw test -Dtest=SecurityRulesTest` → expected: PASS.

`./mvnw test -Dtest=LoginFlowBrowserAcceptanceTest` → expected: PASS overall (the `.anmelden-karte` selector and the computed `display: none` for `.hinweis:empty` assertions in Task 1's browser test now hold).

- [ ] **Step 6: Commit**

`git commit -m "fix(web): style login page and hide empty inline help boxes"`

---

### Task 3: Duplicate site base URL renders a field error instead of a 500

**Files:**
- Modify: `src/main/java/dev/hendrikhoemberg/webtesthelper/catalog/SiteService.java`
- Modify: `src/main/java/dev/hendrikhoemberg/webtesthelper/web/SiteController.java`
- Modify: `src/main/resources/messages.properties`
- Modify: `src/test/java/dev/hendrikhoemberg/webtesthelper/catalog/SiteServiceTest.java`
- Modify: `src/test/java/dev/hendrikhoemberg/webtesthelper/web/SiteControllerTest.java`

**Interfaces:**
- Produces: `SiteService.baseUrlTaken(String baseUrl)` and `... (String baseUrl, long excludeSiteId)` return `true` when a *normalised* URL is already stored (mirrors how `applyForm` stores `normalized.value()` and the DB constraint `ux_site_base_url`).
- `POST /websites` (create) and `POST /websites/{id}` (update) reject the duplicate with `rejectValue("baseUrl", ...)` and re-render `websites/formular` (a 500 is never user-visible).

- [ ] **Step 1: Write the failing tests**

`src/test/java/dev/hendrikhoemberg/webtesthelper/catalog/SiteServiceTest.java`, two new tests using the existing `form()` helper and `@Transactional` rollback:

```java
@Test
void baseUrlTakenIsTrueForAnExistingNormalisedUrl() {
    long id = sites.create(form());

    assertThat(sites.baseUrlTaken("https://www.kunde-mueller.de")).isTrue();
    assertThat(sites.baseUrlTaken("https://www.kunde-mueller.de/")).isTrue();
}

@Test
void baseUrlTakenIgnoresTheSiteItself() {
    long id = sites.create(form());

    assertThat(sites.baseUrlTaken("https://www.kunde-mueller.de/", id)).isFalse();
    assertThat(sites.baseUrlTaken("https://www.kunde-mueller.de/", id + 1)).isTrue();
}
```

`src/test/java/dev/hendrikhoemberg/webtesthelper/web/SiteControllerTest.java`, one new test (mock the new service method; reuse the file's existing `@WithMockUser` + csrf import style):

```java
@Test
@WithMockUser(roles = "ADMIN")
void createWithDuplicatedBaseUrlRerendersFormWithFieldError() throws Exception {
    when(siteService.baseUrlTaken("http://localhost:8090/")).thenReturn(true);

    mvc.perform(post("/websites")
                    .param("name", "Fixture")
                    .param("baseUrl", "http://localhost:8090")
                    .param("maxPages", "200")
                    .param("maxDepth", "10")
                    .param("maxDurationMinutes", "30")
                    .with(csrf()))
            .andExpect(status().isOk())
            .andExpect(view().name("websites/formular"))
            .andExpect(model().attributeHasFieldErrors("form", "baseUrl"));
}
```

- [ ] **Step 2: Run tests — verify they FAIL**

`./mvnw test -Dtest=SiteServiceTest,SiteControllerTest` → expected: FAIL (compile / `400`/unexpected view — the service method and field error do not exist yet).

- [ ] **Step 3: Implement the service check**

In `src/main/java/dev/hendrikhoemberg/webtesthelper/catalog/SiteService.java`, add (after `update`):

```java
/**
 * Whether a normalised base URL is already used by any site. Null/blank URLs (which the
 * form-level {@code @Pattern} will reject anyway) are not "taken" here.
 */
public boolean baseUrlTaken(String baseUrl) {
    return baseUrlTaken(baseUrl, 0L);
}

public boolean baseUrlTaken(String baseUrl, long excludeSiteId) {
    NormalizedUrl normalized = UrlNormalizer.normalize(baseUrl).orElse(null);
    if (normalized == null) {
        return false;
    }
    return sites.findByBaseUrl(normalized.value())
            .map(site -> site.getId() != excludeSiteId)
            .orElse(false);
}
```

(`findByBaseUrl` already exists on `SiteRepository`; `0` is not a valid site id, so the single-arg overload never excludes anything.)

- [ ] **Step 4: Guard both controller paths**

In `src/main/java/dev/hendrikhoemberg/webtesthelper/web/SiteController.java`, in `create`, immediately after the `@Valid`/`BindingResult` signature block and before `if (bindingResult.hasErrors())` — and the same in `update` with its `siteId`:

```java
if (siteService.baseUrlTaken(form.baseUrl())) {
    bindingResult.rejectValue("baseUrl", "ui.websites.formular.fehler.baseUrl.vergeben",
            "Eine Website mit dieser Adresse ist bereits angelegt.");
    return "websites/formular";
}
```

```java
if (siteService.baseUrlTaken(form.baseUrl(), id)) {
    bindingResult.rejectValue("baseUrl", "ui.websites.formular.fehler.baseUrl.vergeben",
            "Eine Website mit dieser Adresse ist bereits angelegt.");
    model.addAttribute("siteId", id);
    return "websites/formular";
}
```

- [ ] **Step 5: Add the message key**

In `src/main/resources/messages.properties`, next to the other `ui.websites.formular.*` keys (line ~193):

```properties
ui.websites.formular.fehler.baseUrl.vergeben=Eine Website mit dieser Adresse ist bereits angelegt.
```

- [ ] **Step 6: Run the tests — verify they PASS**

`./mvnw test -Dtest=SiteServiceTest,SiteControllerTest` → expected: PASS.

Sanity-check the whole web slice once: `./mvnw test -Dtest=SiteControllerTest,SecurityRulesTest,ScheduleControllerTest`.

- [ ] **Step 7: Commit**

`git commit -m "fix(web): friendly field error for duplicate site base URL"`

---

## Verification

1. Full test suite: `./mvnw test` (includes the new browser test; roughly 1.5 min).
2. Manual browser pass (same setup as the audit — Postgres via compose, fixture on :8093, app on :8091):
   - Open `http://localhost:8091/anmelden` in Chromium — styled card, no loop; login lands on `/`.
   - `curl -i http://localhost:8091/favicon.ico` → `HTTP/1.1 200 OK`.
   - Create a site for `http://localhost:8093` — succeeds. Create a second site with the same URL — form re-renders with
     *"Eine Website mit dieser Adresse ist bereits angelegt."* under Basis-URL (no Whitelabel 500).
   - Dashboard, Stummschaltungen, Befund detail: the `?` boxes are invisible until clicked; clicking one expands the HTMX help fragment in place.

## Post-run notes

- Screenshots of the fixed login screen live in `/tmp/opencode/shots-verified/` after step 2 of Verification.
- The favicon is a placeholder asset; swapping it later is a pure asset change (no test impact beyond 200 + content type).
