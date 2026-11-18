# Review Findings Fixes Implementation Plan

**Goal:** Fix the 8 bugs and the duplicate-findings issue found in the browser usability review.

**Architecture:** Nine small, independent fixes, each in the module that owns the behaviour.
None of them adds a new table, a new module dependency or a new public route. One build tweak (Task 0) comes first so that
the suite runs on the host JDK 27.

**Tech Stack:** Spring Boot 4.1.1, Thymeleaf + HTMX + Alpine, Driver.js, Playwright 1.62 (Java), JUnit 5, MockMvc, Postgres/Flyway.

**Spec:** the review findings from this session (no separate spec doc). Items:
1. A journey `TEXT_CONTAINS` assertion on a navigating CLICK times out.
2. Closed recorder tabs keep blocking a recorder slot for 15 minutes.
3. The triage form ignores the finding's current status and reason.
4. The baseline dialog announces the total count instead of the untriaged count (said 7, moved 6).
5. Validation errors in the collapsed "Erweiterte Einstellungen" section are invisible.
6. The tour shows "3 / 3 · Verstanden, loslegen!" mid-tour, restarts after being left, and its copy promises accessibility and outage checks.
7. The "Prüflauf abgeschlossen" toast appears on every visit to a finished run.
8. The mute preview says "1 Feststellungen".
9. A crawled 404 page is reported twice: once as PAGE_STATUS and once as DEAD_LINK.

## Global Constraints

- German-only UI. Message keys are `ui.*`. No enum names, `{0}` placeholders or ISO instants in visible text.
- View tests use `@WebMvcTest` + MockMvc and assert on text/markup, never on CSS.
- Desktop-only. No responsive breakpoints.
- Keep recorder locator candidates and the 0/2/4 worker pool sizes unchanged.
- Test output is always run with `-B --no-transfer-progress`, `set -o pipefail` and `| tail`.
- Do not edit `data/`, `target/` or `.env`. Do not commit screenshots or test data.
- Work happens on a branch `fix/review-findings` (currently on `main`). Commit only after the user OKs it.
  Every commit ends with `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.

## Commands used in this plan

Tests need the test Postgres (`wth-pg-test` on port 5433), so every command sets `DB=WTH_DB_URL=jdbc:postgresql://localhost:5433/webtesthelper`.

- Single test: `bash -c "set -o pipefail; $DB ./mvnw test -B --no-transfer-progress -Dtest=<Class> 2>&1 | tail -n 40"`
- Fast gate: `bash -c "set -o pipefail; $DB ./mvnw test -Pfast -B --no-transfer-progress | tail -n 50"`
- Full gate (required here, because templates, messages, controllers, recorder, checks and journeys change):
  `bash -c "set -o pipefail; $DB ./mvnw test -B --no-transfer-progress | tail -n 60"`

**Baseline:** the fast gate on host JDK 27 with Lombok 1.18.48 gives `Tests run: 1996, Failures: 0, Errors: 0`. The full-gate
baseline is `Tests run: 2247, Failures: 1`. The one failure is `IntentCaptureTest.textareaInputReportsInputWithValue`
(10 s timeout, "Expecting actual not to be empty"). Run on its own it passes 11/11, so it is timing-flaky under full-suite load and pre-existing.

---

### Task 0: Build on JDK 27 (Lombok bump)

The host JDK 27 cannot compile with the Lombok version that Boot pins (`ExceptionInInitializerError: com.sun.tools.javac.tree.EndPosTable`).
Lombok 1.18.48 fixes this, which I verified with `-Dlombok.version=1.18.48` (BUILD SUCCESS, fast suite green).

**Files:**
- Modify: `pom.xml` — add `<lombok.version>1.18.48</lombok.version>` to `<properties>` (it overrides the Boot-managed version).

- [x] **Step 1:** `./mvnw -B --no-transfer-progress -q test-compile` on a clean checkout → FAIL (EndPosTable).
- [x] **Step 2:** Add the property.
- [x] **Step 3:** Run the same command → BUILD SUCCESS.
- [x] **Step 4:** Commit `build: bump Lombok to 1.18.48 so the build compiles on JDK 27`.

---

### Task 1: Don't report a crawled 404 page twice (checks)

**Files:**
- Modify: `src/main/java/dev/hendrikhoemberg/webtesthelper/checks/CheckEngine.java` — post-filter in `evaluateRun`.
- Test: `src/test/java/dev/hendrikhoemberg/webtesthelper/checks/CheckEngineTest.java`

**Interfaces:**
- Consumes: `PageStatusCheck.HTTP_ERROR` and `DeadLinkCheck.DEAD` (both package-private `static final String`, same package).
- Produces: `evaluateRun` no longer returns a `PAGE_STATUS`/`httpError` finding when a `DEAD_LINK`/`dead` finding has the same `subjectKey`.
  Kept as before: soft-404s, a 404 page nobody links to (for example the start URL), and everything when DEAD_LINK is disabled.

- [x] **Step 1: Failing tests**
```java
@Test
void aCrawled404PageThatIsAlsoADeadLinkIsReportedOnlyAsTheDeadLink() {
    UrlVerification dead = new UrlVerification("https://example.com/tot", UrlStatus.DEAD, 404,
            "text/html", 0, null, "Not Found", Instant.EPOCH);
    RunFacts facts = new RunFacts(1L, RunScope.FULL, Instant.EPOCH, SoftNotFoundProbe.NONE,
            UrlVerifications.of(List.of(dead)), TlsCertificateFact.NONE, List.of());
    PageSnapshot start = Snapshots.page("https://example.com/").status(200)
            .link("https://example.com/tot", true).build();
    PageSnapshot gone = Snapshots.page("https://example.com/tot").status(404).build();
    RunSnapshots run = new RunSnapshots(1L, site(allEnabled()), List.of(start, gone), SoftNotFoundProbe.NONE);

    List<CheckFinding> forGonePage = engine.evaluateRun(run, site(allEnabled()), facts).stream()
            .filter(f -> f.subjectKey().equals("https://example.com/tot")).toList();

    assertThat(forGonePage).extracting(CheckFinding::type)
            .contains(CheckType.DEAD_LINK)
            .doesNotContain(CheckType.PAGE_STATUS);
}

@Test
void a404PageNobodyLinksToKeepsItsPageStatusFinding() {
    PageSnapshot gone = Snapshots.page("https://example.com/tot").status(404).build();
    RunSnapshots run = new RunSnapshots(1L, site(allEnabled()), List.of(gone), SoftNotFoundProbe.NONE);

    assertThat(engine.evaluateRun(run, site(allEnabled()), facts(RunScope.FULL)))
            .extracting(CheckFinding::type).contains(CheckType.PAGE_STATUS);
}
```
- [x] **Step 2:** Run `-Dtest=CheckEngineTest`. Expected: the first test FAILS (PAGE_STATUS present). The second passes, as a guard.
- [x] **Step 3: Implementation**
```java
public List<CheckFinding> evaluateRun(RunSnapshots snapshots, SiteContext site, RunFacts facts) {
    List<CheckFinding> findings = new ArrayList<>();
    for (PageSnapshot snapshot : snapshots.snapshots()) {
        findings.addAll(evaluatePage(snapshot, site, facts));
    }
    dropPageErrorsAlreadyReportedAsDeadLinks(findings);
    return findings;
}

/**
 * A crawled page that answers 404 is also the target of the dead link that led the crawler there.
 * Both findings name the same URL; the dead link is the more useful one, because it says where the
 * broken link sits. The page-status finding survives when nothing links to the page.
 */
private static void dropPageErrorsAlreadyReportedAsDeadLinks(List<CheckFinding> findings) {
    Set<String> deadTargets = findings.stream()
            .filter(f -> f.type() == CheckType.DEAD_LINK && DeadLinkCheck.DEAD.equals(f.messageKey()))
            .map(CheckFinding::subjectKey)
            .collect(Collectors.toSet());
    if (deadTargets.isEmpty()) {
        return;
    }
    findings.removeIf(f -> f.type() == CheckType.PAGE_STATUS
            && PageStatusCheck.HTTP_ERROR.equals(f.messageKey())
            && deadTargets.contains(f.subjectKey()));
}
```
- [x] **Step 4:** Run `-Dtest=CheckEngineTest` → PASS. Then run `-Dtest='*Check*Test,FindingMaterializer*Test'` to catch any test that expected both findings.
  Expect: existing PAGE_STATUS findings for these URLs show up as "behoben" once, in the next full run.
- [x] **Step 5:** Commit `fix(checks): report a linked 404 page once, as a dead link`.

---

### Task 2: Journey text assertion after a navigating click (runner)

**Files:**
- Modify: `src/main/java/dev/hendrikhoemberg/webtesthelper/runner/StepExecutor.java` — `withAssertion`, plus a new page-level text check that waits.
- Test: `src/test/java/dev/hendrikhoemberg/webtesthelper/runner/StepExecutorTest.java` (`@Tag("browser")`, fixture `reise/start.html`, where `#reise-start-link` goes to `schritt2.html`, whose h1 is "Reiseangaben").

**Interfaces:**
- Produces: when an action takes the page away from the element it acted on (the URL changed, or the element is gone), a `TEXT_CONTAINS` assertion
  checks the page body instead and polls for up to `timeoutMs`. A miss is reported as `MSG_ASSERTION`, not `MSG_TIMEOUT`.
  Element-scoped assertions on actions that stay on the page work exactly as before.

- [x] **Step 1: Failing tests**
```java
@Test
void textAssertionOnANavigatingClickIsCheckedOnTheNewPage() {
    LocatorCandidate link = new LocatorCandidate(LocatorStrategy.CSS, "#reise-start-link", 0);
    JourneyStep step = createStep(StepAction.CLICK, List.of(link), null,
            new StepAssertion(AssertionType.TEXT_CONTAINS, "Reiseangaben"), false, 5000);

    StepOutcome outcome = StepExecutor.execute(page, step, null);

    assertThat(outcome.status()).isEqualTo(StepStatus.PASSED);
}

@Test
void aMissingTextAfterANavigatingClickIsAnAssertionFailureNotATimeout() {
    LocatorCandidate link = new LocatorCandidate(LocatorStrategy.CSS, "#reise-start-link", 0);
    JourneyStep step = createStep(StepAction.CLICK, List.of(link), null,
            new StepAssertion(AssertionType.TEXT_CONTAINS, "Nicht auf der Seite"), false, 2000);

    StepOutcome outcome = StepExecutor.execute(page, step, null);

    assertThat(outcome.status()).isEqualTo(StepStatus.FAILED);
    assertThat(outcome.failureMessageKey()).isEqualTo(StepExecutor.MSG_ASSERTION);
    assertThat(outcome.failureArgs()).contains("Nicht auf der Seite");
}
```
- [x] **Step 2:** Run `-Dtest=StepExecutorTest`. Expected: both FAIL with `journey.step.failed.timeout`, because `innerText` waits on the detached link.
- [x] **Step 3: Implementation**
```java
/** Runs the action, then evaluates the step's assertion if it carries one. */
private static StepOutcome withAssertion(
        Page page, JourneyStep step, LocatorMatch match, String resolvedValue, Runnable action) {
    String urlBefore = page.url();
    action.run();
    if (step.assertion() == null) {
        return outcomeFor(step, match);
    }
    if (step.assertion().type() == AssertionType.TEXT_CONTAINS && leftTheElement(page, match, urlBefore)) {
        return evaluatePageTextContains(page, step, match, step.assertion(), resolvedValue);
    }
    return evaluateAssertion(page, step, match, resolvedValue);
}

/** The action navigated away from, or removed, the element it acted on — e.g. "click 'Absenden', expect 'Danke'". */
private static boolean leftTheElement(Page page, LocatorMatch match, String urlBefore) {
    if (match == null) {
        return false;
    }
    return !page.url().equals(urlBefore) || match.locator().count() == 0;
}

/** The new page may still be loading, so the body text is polled until the step timeout. */
private static StepOutcome evaluatePageTextContains(
        Page page, JourneyStep step, LocatorMatch match, StepAssertion assertion, String resolvedValue) {
    String expected = assertion.expected() != null ? assertion.expected() : orEmpty(resolvedValue);
    try {
        page.waitForCondition(() -> bodyText(page).contains(expected),
                new Page.WaitForConditionOptions().setTimeout(step.timeoutMs()));
        return outcomeFor(step, match);
    } catch (TimeoutError e) {
        return failure(step, match, MSG_ASSERTION, List.of(expected, bodyText(page)));
    }
}

/** Empty while the old document is torn down mid-navigation; the next poll sees the new one. */
private static String bodyText(Page page) {
    try {
        return orEmpty(page.innerText("body", new Page.InnerTextOptions().setTimeout(1000)));
    } catch (RuntimeException e) {
        return "";
    }
}
```
- [x] **Step 4:** Run `-Dtest=StepExecutorTest` → PASS. Also run `-Dtest='Journey*AcceptanceTest'` (replay/run end-to-end).
- [x] **Step 5:** Commit `fix(runner): check text assertions on the new page after a navigating click`.

---

### Task 3: Release a recorder slot shortly after the tab closes (recorder)

**Files:**
- Modify: `src/main/java/dev/hendrikhoemberg/webtesthelper/recorder/RecordingSession.java` — add `markDisconnected()`, `markConnected()` and `disconnectedAt()`.
- Modify: `src/main/java/dev/hendrikhoemberg/webtesthelper/recorder/RecordingSessionRegistry.java` — `reapIdle()` also reaps sessions disconnected for at least `DISCONNECT_GRACE` (60 s).
- Modify: `src/main/java/dev/hendrikhoemberg/webtesthelper/recorder/RecorderSocketHandler.java` — mark the session connected in `afterConnectionEstablished` and disconnected in `afterConnectionClosed`.
- Test: `src/test/java/dev/hendrikhoemberg/webtesthelper/recorder/RecordingSessionRegistryTest.java` (existing `TestClock`)
- Test (new): `src/test/java/dev/hendrikhoemberg/webtesthelper/recorder/RecorderSocketDisconnectTest.java`

**Why the grace period:** the save POST races the page unload, and a reload reconnects. Closing on the first disconnect would lose recordings.
The reaper runs every 30 s, so a slot comes back 60–90 s after the tab closes instead of 15 min.
`RecorderProperties` stays untouched, because 5 test call sites use its canonical constructor.

- [x] **Step 1: Failing tests**
```java
// RecordingSessionRegistryTest
@Test
void aSessionWhoseTabWentAwayIsReapedAfterTheGracePeriod() {
    RecorderWorker worker = mock(RecorderWorker.class);
    when(pool.allocate()).thenReturn(Optional.of(worker));
    RecordingSession session = registry.open(1L, "https://example.com/start", "alice");

    session.markDisconnected();
    clock.advance(Duration.ofSeconds(59));
    registry.reapIdle();
    assertThat(registry.find(session.sessionId(), "alice")).isPresent();

    clock.advance(Duration.ofSeconds(2));
    registry.reapIdle();
    assertThat(registry.find(session.sessionId(), "alice")).isEmpty();
    verify(pool).release(worker);
}

@Test
void aReconnectWithinTheGracePeriodKeepsTheSession() {
    RecorderWorker worker = mock(RecorderWorker.class);
    when(pool.allocate()).thenReturn(Optional.of(worker));
    RecordingSession session = registry.open(1L, "https://example.com/start", "alice");

    session.markDisconnected();
    clock.advance(Duration.ofSeconds(30));
    session.markConnected();
    clock.advance(Duration.ofSeconds(60));
    registry.reapIdle();

    assertThat(registry.find(session.sessionId(), "alice")).isPresent();
    verify(pool, never()).release(worker);
}

// RecorderSocketDisconnectTest
class RecorderSocketDisconnectTest {
    @Test
    void closingTheSocketMarksTheRecordingSessionDisconnected() throws Exception {
        RecordingSession recording = new RecordingSession(UUID.randomUUID(), 1L, "https://example.com/",
                "alice", null, null, null, Clock.systemUTC());
        WebSocketSession socket = mock(WebSocketSession.class);
        when(socket.getId()).thenReturn("s1");
        when(socket.getAttributes()).thenReturn(new HashMap<>(
                Map.of(RecorderHandshakeInterceptor.RECORDING_SESSION_ATTR, recording)));

        new RecorderSocketHandler(null).afterConnectionClosed(socket, CloseStatus.GOING_AWAY);

        assertThat(recording.disconnectedAt()).isPresent();
    }
}
```
- [x] **Step 2:** Run `-Dtest='RecordingSessionRegistryTest,RecorderSocketDisconnectTest'`. Expected: compilation FAILS (`markDisconnected` and friends don't exist yet), which counts as the red step.
- [x] **Step 3: Implementation**
```java
// RecordingSession
private volatile Instant disconnectedAt;

/** The browser tab's WebSocket closed; the registry reaps the session if it does not come back. */
public void markDisconnected() { this.disconnectedAt = clock.instant(); }

/** A (re)connected tab cancels a pending disconnect. */
public void markConnected() { this.disconnectedAt = null; }

public Optional<Instant> disconnectedAt() { return Optional.ofNullable(disconnectedAt); }

// RecordingSessionRegistry
/** How long a session whose tab went away is kept for a late save POST or a reload. */
static final Duration DISCONNECT_GRACE = Duration.ofSeconds(60);

public void reapIdle() {
    Instant now = clock.instant();
    Duration idleTimeout = properties.idleTimeout();
    List<UUID> toReap = new ArrayList<>();
    for (RecordingSession session : sessions.values()) {
        boolean idle = Duration.between(session.lastActivity(), now).compareTo(idleTimeout) >= 0;
        boolean abandoned = session.disconnectedAt()
                .map(at -> Duration.between(at, now).compareTo(DISCONNECT_GRACE) >= 0)
                .orElse(false);
        if (idle || abandoned) {
            toReap.add(session.sessionId());
        }
    }
    for (UUID id : toReap) {
        log.info("Bereinige inaktive Aufnahmesitzung {}", id);
        close(id);
    }
}

// RecorderSocketHandler.afterConnectionEstablished, inside `if (recordingSession != null && !recordingSession.isClosed())`, first line:
recordingSession.markConnected();
// RecorderSocketHandler.afterConnectionClosed, after the log line:
if (recordingSession != null) {
    recordingSession.markDisconnected();
}
```
- [x] **Step 4:** Run the two classes → PASS. Then `-Dtest='Recorder*Test,Recording*Test'`.
- [x] **Step 5:** Commit `fix(recorder): free a recorder slot 60 s after its tab disconnects`.

---

### Task 4: The triage form starts at the finding's current decision (web)

**Files:**
- Modify: `src/main/java/dev/hendrikhoemberg/webtesthelper/web/FindingController.java` — the `detail` handler (`GET /befunde/{id}`, view `befunde/detail`) overrides `triageDefaultAction` and adds `triageCurrentReason` and `triageCurrentMutedUntil`.
- Modify: `src/main/resources/templates/fragments/bewertung.html` — the `bewertung(actionUrl, isBulk, findingId)` fragment reads the two new data attributes.
- Test: `src/test/java/dev/hendrikhoemberg/webtesthelper/web/FindingControllerTest.java`

**Interfaces:**
- Produces: for a triaged finding, the select starts at its status and the reason/"Stumm bis" fields start at its values. An untriaged finding keeps the
  `ACKNOWLEDGED` default from `TriageUiAdvice` (preselecting "ungeprüft" would be useless). The bulk form on `websites/befunde.html` is unchanged, because the attributes are absent there.

- [x] **Step 1: Failing tests** (`finding(...)` is a local helper built on the long `Finding` constructor, same values as the existing test except triage fields)
```java
private static Finding finding(TriageStatus triage, String reason, Instant mutedUntil) {
    Instant t = Instant.parse("2026-08-25T10:00:00Z");
    return new Finding(1L, 42L, "fp-1", CheckType.DEAD_LINK, "https://example.com/dead",
            "https://example.com/source", Severity.ERROR, "finding.DEAD_LINK.dead",
            List.of("https://example.com/dead", "404 Not Found"), Evidence.NONE, ObservedStatus.ACTIVE,
            triage, reason, reason != null ? "otto" : null, mutedUntil, null, null,
            10L, 10L, null, null, 1, 1, t, t);
}

@Test
@WithMockUser(roles = "USER")
void aTriagedFindingPrefillsTheTriageFormWithItsCurrentDecision() throws Exception {
    when(findingService.byId(1L)).thenReturn(Optional.of(
            finding(TriageStatus.MUTED, "Bis zum Relaunch egal", Instant.parse("2026-12-31T12:00:00Z"))));
    when(findingService.occurrencesOfLastRun(1L, 50)).thenReturn(List.of());

    mvc.perform(get("/befunde/1"))
            .andExpect(status().isOk())
            .andExpect(content().string(containsString("data-default-action=\"MUTED\"")))
            .andExpect(content().string(containsString("data-default-reason=\"Bis zum Relaunch egal\"")))
            .andExpect(content().string(containsString("data-default-muted-until=\"2026-12-31\"")));
}

@Test
@WithMockUser(roles = "USER")
void anUntriagedFindingKeepsTheAcknowledgeDefault() throws Exception {
    when(findingService.byId(1L)).thenReturn(Optional.of(finding(TriageStatus.UNTRIAGED, null, null)));
    when(findingService.occurrencesOfLastRun(1L, 50)).thenReturn(List.of());

    mvc.perform(get("/befunde/1"))
            .andExpect(content().string(containsString("data-default-action=\"ACKNOWLEDGED\"")))
            .andExpect(content().string(not(containsString("data-default-reason"))));
}
```
- [x] **Step 2:** Run `-Dtest=FindingControllerTest`. Expected: the first test FAILS (action is `ACKNOWLEDGED`, no reason attribute). The second passes, as a guard.
- [x] **Step 3: Implementation**
```java
// FindingController
private static final ZoneId DEFAULT_ZONE = ZoneId.of("Europe/Berlin"); // same zone TriageController uses to parse "Stumm bis"

// in detail(), before return:
if (finding.triage() != TriageStatus.UNTRIAGED) {
    model.addAttribute("triageDefaultAction", finding.triage().name());
    model.addAttribute("triageCurrentReason", finding.triageReason());
    if (finding.mutedUntil() != null) {
        model.addAttribute("triageCurrentMutedUntil",
                LocalDate.ofInstant(finding.mutedUntil(), DEFAULT_ZONE).toString());
    }
}
```
```html
<!-- bewertung.html: two attributes on the fragment root -->
th:data-default-reason="${triageCurrentReason}"
th:data-default-muted-until="${triageCurrentMutedUntil}"
<!-- x-data -->
grund: $el.dataset.defaultReason || '',
stummBis: $el.dataset.defaultMutedUntil || (function() {
    const d = new Date();
    d.setDate(d.getDate() + 90);
    return d.toISOString().split('T')[0];
})(),
```
- [x] **Step 4:** Run `-Dtest='FindingControllerTest,TriageControllerTest'` → PASS.
- [x] **Step 5:** Commit `fix(triage): prefill the triage form with the finding's current decision`.

---

### Task 5: The baseline dialog counts what it will actually move (findings + web)

**Files:**
- Modify: `src/main/java/dev/hendrikhoemberg/webtesthelper/findings/RunDiff.java` — add `untriagedOpenCount()`.
- Modify: `src/main/resources/templates/laeufe/detail.html` line 212 — use it instead of `observedTotal()`.
- Test (new): `src/test/java/dev/hendrikhoemberg/webtesthelper/findings/RunDiffTest.java`
- Test: `src/test/java/dev/hendrikhoemberg/webtesthelper/web/RunControllerTest.java`

**Interfaces:**
- Produces: `int RunDiff.untriagedOpenCount()`. It counts `UNTRIAGED` findings in every section except `FIXED`, which is the same set
  `FindingStore.ACCEPT_BASELINE_SQL` moves (UNTRIAGED + observed in the run).

- [x] **Step 1: Failing tests**
```java
// RunDiffTest
class RunDiffTest {
    private static Finding finding(long id, TriageStatus triage) {
        Instant t = Instant.parse("2026-08-25T10:00:00Z");
        return new Finding(id, 42L, "fp-" + id, CheckType.DEAD_LINK, "https://example.com/" + id,
                "https://example.com/", Severity.ERROR, "finding.DEAD_LINK.dead", List.of(),
                Evidence.NONE, ObservedStatus.ACTIVE, triage, null, 10L, 10L, null, null, 1, 1, t, t);
    }

    @Test
    void untriagedOpenCountSkipsTriagedAndFixedFindings() {
        RunDiff diff = new RunDiff(10L, Map.of(
                ReportSection.NEW, List.of(finding(1, TriageStatus.UNTRIAGED), finding(2, TriageStatus.UNTRIAGED)),
                ReportSection.KNOWN, List.of(finding(3, TriageStatus.ACKNOWLEDGED)),
                ReportSection.FIXED, List.of(finding(4, TriageStatus.UNTRIAGED))));

        assertThat(diff.observedTotal()).isEqualTo(3);
        assertThat(diff.untriagedOpenCount()).isEqualTo(2);
    }
}

// RunControllerTest (same mocking as completedRunRendersDiffSummaryLine; RunDiffTest's finding() helper copied as a private helper)
@Test
@WithMockUser(roles = "ADMIN")
void baselineDialogAnnouncesOnlyTheUntriagedFindings() throws Exception {
    long runId = 101L;
    long siteId = 42L;
    RunDiff diff = new RunDiff(runId, Map.of(
            ReportSection.NEW, List.of(finding(1, TriageStatus.UNTRIAGED), finding(2, TriageStatus.UNTRIAGED)),
            ReportSection.KNOWN, List.of(finding(3, TriageStatus.ACKNOWLEDGED))));
    when(runService.summary(runId)).thenReturn(sampleSummary(runId, siteId, RunStatus.COMPLETED, false, false, null));
    when(siteService.contextFor(siteId)).thenReturn(sampleSite(siteId));
    when(findingService.diffForReport(siteId, runId)).thenReturn(diff);
    when(findingViewFactory.of(eq(diff), any(Locale.class))).thenReturn(Map.of());

    mvc.perform(get("/laeufe/" + runId))
            .andExpect(content().string(containsString("Hiermit werden alle 2 noch ungeprüften Feststellungen")));
}
```
- [x] **Step 2:** Run `-Dtest='RunDiffTest,RunControllerTest'`. Expected: `RunDiffTest` fails to compile (no method). After a stub, the view test FAILS showing "alle 3".
- [x] **Step 3: Implementation**
```java
/** What "Ausgangsbestand festlegen" will move: the findings of this run nobody has triaged yet. */
public int untriagedOpenCount() {
    int total = 0;
    for (Map.Entry<ReportSection, List<Finding>> e : bySection.entrySet()) {
        if (e.getKey() == ReportSection.FIXED) {
            continue;
        }
        for (Finding f : e.getValue()) {
            if (f.triage() == TriageStatus.UNTRIAGED) {
                total++;
            }
        }
    }
    return total;
}
```
```html
th:text="#{ui.lauf.ausgangsbestand.folge(${diff != null ? diff.untriagedOpenCount() : run.findingsTotal})}"
```
- [x] **Step 4:** Run both classes → PASS.
- [x] **Step 5:** Commit `fix(runs): baseline dialog announces the untriaged count it will move`.

---

### Task 6: Open "Erweiterte Einstellungen" when one of its fields has an error (web)

**Files:**
- Modify: `src/main/resources/templates/websites/formular.html` line 82 — the `x-data` toggle. All 9 fields inside the section (lines 87–168) are listed.
- Test: `src/test/java/dev/hendrikhoemberg/webtesthelper/web/SiteControllerTest.java` (`POST /websites`, `GET /websites/neu`, view `websites/formular`)

- [x] **Step 1: Failing tests**
```java
@Test
@WithMockUser(roles = "ADMIN")
void aMissingPageLimitOpensTheAdvancedSettingsSoTheErrorIsVisible() throws Exception {
    mvc.perform(post("/websites").with(csrf())
                    .param("name", "Test")
                    .param("baseUrl", "https://example.com/")
                    .param("maxPages", "")
                    .param("maxDepth", "3")
                    .param("maxDurationMinutes", "10"))
            .andExpect(status().isOk())
            .andExpect(model().attributeHasFieldErrors("form", "maxPages"))
            .andExpect(content().string(containsString("x-data=\"{ erweitert: true }\"")));
}

@Test
@WithMockUser(roles = "ADMIN")
void aFreshNewSiteFormKeepsTheAdvancedSettingsClosed() throws Exception {
    mvc.perform(get("/websites/neu"))
            .andExpect(content().string(containsString("x-data=\"{ erweitert: false }\"")));
}
```
- [x] **Step 2:** Run `-Dtest=SiteControllerTest`. Expected: the first test FAILS (`erweitert: false`).
- [x] **Step 3: Implementation** (`th:with` runs before `th:attr`)
```html
<div th:with="erweitertFehler=${#fields.hasErrors('maxPages') or #fields.hasErrors('maxDepth')
                or #fields.hasErrors('maxDurationMinutes') or #fields.hasErrors('includePatterns')
                or #fields.hasErrors('excludePatterns') or #fields.hasErrors('userAgent')
                or #fields.hasErrors('respectRobots') or #fields.hasErrors('enabled')
                or #fields.hasErrors('formTestMode')}"
     th:attr="x-data=|{ erweitert: ${siteId != null or erweitertFehler} }|" style="margin: 1rem 0;">
```
- [x] **Step 4:** Run `-Dtest='SiteControllerTest,SiteFormModelTest'` → PASS.
- [x] **Step 5:** Commit `fix(sites): open advanced settings when they contain a validation error`.

---

### Task 7: The completion banner appears only right after a run finishes (web)

**Mechanism:** today, `GET /laeufe/{id}/fortschritt` answers a terminal run with `HX-Refresh: true`. That reloads the same URL,
so the page cannot tell "just finished" apart from "opened later". Instead it answers with `HX-Redirect: /laeufe/{id}?abgeschlossen=1`.
The detail handler turns the parameter into `frischAbgeschlossen`, and the banner renders only then. `x-init` strips the parameter
with `history.replaceState`, so a reload doesn't show it again.

**Files:**
- Modify: `src/main/java/dev/hendrikhoemberg/webtesthelper/web/RunController.java` — `detail` (`GET /laeufe/{id}`) and `fortschritt` (`GET /laeufe/{id}/fortschritt`).
- Modify: `src/main/resources/templates/laeufe/detail.html` lines 13–15 — the banner condition and `x-init`.
- Test: `src/test/java/dev/hendrikhoemberg/webtesthelper/web/RunControllerTest.java` — replace `fortschrittForTerminalRunReturnsHxRefreshHeader` and add two detail tests.

- [x] **Step 1: Failing tests**
```java
@Test
@WithMockUser(roles = "USER")
void fortschrittForTerminalRunRedirectsToTheReportMarkedAsJustFinished() throws Exception {
    long runId = 106L;
    when(runService.summary(runId)).thenReturn(sampleSummary(runId, 42L, RunStatus.COMPLETED, false, false, null));

    mvc.perform(get("/laeufe/" + runId + "/fortschritt"))
            .andExpect(status().isOk())
            .andExpect(header().string("HX-Redirect", "/laeufe/106?abgeschlossen=1"))
            .andExpect(header().doesNotExist("HX-Refresh"))
            .andExpect(content().string(not(containsString("hx-trigger"))))
            .andExpect(content().string(not(containsString("Lauf abbrechen"))));
}

@Test
@WithMockUser(roles = "USER")
void openingAFinishedRunLaterShowsNoCompletionBanner() throws Exception {
    stubCompletedRun(101L, 42L);   // private helper: the four `when(...)` lines of completedRunRendersDiffSummaryLine
    mvc.perform(get("/laeufe/101"))
            .andExpect(content().string(not(containsString("Der Prüflauf wurde abgeschlossen."))));
}

@Test
@WithMockUser(roles = "USER")
void arrivingFromTheLiveProgressShowsTheCompletionBanner() throws Exception {
    stubCompletedRun(101L, 42L);
    mvc.perform(get("/laeufe/101").param("abgeschlossen", "1"))
            .andExpect(content().string(containsString("Der Prüflauf wurde abgeschlossen.")));
}
```
- [x] **Step 2:** Run `-Dtest=RunControllerTest`. Expected: the redirect test and the "later" test FAIL.
- [x] **Step 3: Implementation**
```java
@GetMapping("/{id}")
public String detail(@PathVariable("id") long id,
                     @RequestParam(name = "abgeschlossen", required = false) String abgeschlossen,
                     Model model, Locale locale) {
    RunSummary run = runService.summary(id);
    // ... unchanged ...
    model.addAttribute("frischAbgeschlossen", abgeschlossen != null && run.status().isTerminal());
    model.addAttribute("run", run);
    model.addAttribute("site", site);
    return "laeufe/detail";
}

@GetMapping("/{id}/fortschritt")
public String fortschritt(@PathVariable("id") long id, Model model,
                          HttpServletRequest request, HttpServletResponse response) {
    RunSummary run = runService.summary(id);
    model.addAttribute("run", run);
    if (run.status().isTerminal()) {
        response.setHeader("HX-Redirect", request.getContextPath() + "/laeufe/" + id + "?abgeschlossen=1");
    }
    return "fragments/fortschritt :: fortschritt";
}
```
```html
<div th:if="${frischAbgeschlossen}"
     x-data="{ sichtbar: true }"
     x-init="history.replaceState(null, '', window.location.pathname); setTimeout(() => sichtbar = false, 7000)"
```
(The `th:styleappend` and text still switch on COMPLETED vs FAILED.)
- [x] **Step 4:** Run `-Dtest=RunControllerTest` → PASS. `grep -rn HX-Refresh src` should only find the negative assertion.
- [x] **Step 5:** Commit `fix(runs): show the completion banner only right after the run finishes`.

---

### Task 8: Tour counts 6 steps, doesn't restart after being left, honest copy (web/static)

**Files:**
- Modify: `src/main/resources/static/js/tutorial.js`
- Modify: `src/main/resources/messages.properties:1090` — `ui.tutorial.schritt1.text`
- Test (new, `@Tag("browser")`): `src/test/java/dev/hendrikhoemberg/webtesthelper/web/TutorialTourBrowserAcceptanceTest.java`
- Test: `src/test/java/dev/hendrikhoemberg/webtesthelper/web/LayoutTutorialRenderingTest.java` — copy assertion.

**Behaviour:**
- Progress reads 1–3 / 6 on `/` and 4–6 / 6 on `/websites`, using Driver.js per-step `progressText`.
- Step 3's button says "Weiter" (per-step `doneBtnText`), not "Verstanden, loslegen!".
- Starting phase 1 POSTs `/tutorial/abschliessen` right away (`keepalive`). After that, `autoStart` is false on the next page, so leaving mid-tour never restarts it.
  Phase 2 still runs, because it keys off `sessionStorage`. Phase 2 removes the `sessionStorage` key when it starts, so leaving it doesn't loop either.
  "Rundgang erneut starten" (`/tutorial/neustarten`) still works.
- Copy: `ui.tutorial.schritt1.text=WebTestHelper prüft Ihre Websites regelmäßig auf Fehler, tote Links und fehlende Inhalte. Machen wir einen kurzen gemeinsamen Rundgang!`

- [x] **Step 1: Failing tests**
```java
// LayoutTutorialRenderingTest.authenticatedLayoutIncludesDriverAssetsAndConfig, add:
.andExpect(content().string(not(containsString("Barrierefreiheit"))))
.andExpect(content().string(containsString("tote Links und fehlende Inhalte")))

// TutorialTourBrowserAcceptanceTest — class annotations exactly as LoginFlowBrowserAcceptanceTest
// (@Tag("browser"), @ResourceLock("browser"), RANDOM_PORT, @ActiveProfiles("test"), PER_CLASS, extends AbstractPostgresTest)
@Test
void theTourCountsAllSixStepsAndDoesNotRestartAfterBeingLeft() {
    String user = "tour" + System.nanoTime();
    appUserService.create(user, "test-pass-42", AppRole.ADMIN);
    BrowserContext ctx = SharedBrowser.browser().newContext();
    try {
        Page page = ctx.newPage();
        page.navigate(base() + "/");
        page.fill("input[name='username']", user);
        page.fill("input[name='password']", "test-pass-42");
        page.click("button[type='submit']");

        Locator progress = page.locator(".driver-popover-progress-text");
        Locator next = page.locator(".driver-popover-next-btn");
        PlaywrightAssertions.assertThat(progress).hasText("1 / 6");
        next.click();
        next.click();
        PlaywrightAssertions.assertThat(progress).hasText("3 / 6");
        PlaywrightAssertions.assertThat(next).containsText("Weiter");

        next.click();
        page.waitForURL(url -> url.endsWith("/websites"));
        PlaywrightAssertions.assertThat(progress).hasText("4 / 6");

        // Walk away mid-tour: a fresh tab on the dashboard must not start it again.
        Page fresh = ctx.newPage();
        fresh.navigate(base() + "/");
        fresh.waitForLoadState(LoadState.NETWORKIDLE);
        assertThat(fresh.locator(".driver-popover").count()).isZero();
    } finally {
        ctx.close();
    }
}
```
- [x] **Step 2:** Run `-Dtest='LayoutTutorialRenderingTest,TutorialTourBrowserAcceptanceTest'`. Expected: FAIL on the copy, on "1 / 3", and on the restart.
- [x] **Step 3: Implementation (tutorial.js)**
```js
const TOTAL_STEPS = 6;

function postTutorialFinished() {
    const token = document.querySelector('meta[name="_csrf"]')?.getAttribute('content');
    const header = document.querySelector('meta[name="_csrf_header"]')?.getAttribute('content');
    const headers = { 'Content-Type': 'application/json' };
    if (token && header) {
        headers[header] = token;
    }
    fetch(finishUrl, { method: 'POST', headers: headers, keepalive: true }).catch(function () {
        // Ignore network failure when marking complete
    });
}

function markTutorialComplete() {
    sessionStorage.removeItem('wth_tour_step');
    postTutorialFinished();
}

// driverFactory({...}): progressText: '{{current}} / ' + TOTAL_STEPS

// Phase 1, after the `if (autoStart || tourStep === 'dashboard')` check, before drive(0):
//   seen once is enough — leaving mid-tour must not restart it on the next page
postTutorialFinished();
// Phase 1, step 3 popover: add  doneBtnText: i18n.next

// Phase 2 condition simplified to: path.indexOf('/websites') === 0 && tourStep === 'websites'
// Phase 2, first statement inside the branch:
sessionStorage.removeItem('wth_tour_step');
// Phase 2 popovers: progressText: '4 / ' + TOTAL_STEPS, '5 / ' + TOTAL_STEPS, '6 / ' + TOTAL_STEPS
```
Plus the new copy in `messages.properties:1090`.
- [x] **Step 4:** Run both classes, plus `TutorialControllerTest` and `TutorialAdviceTest` → PASS.
- [x] **Step 5:** Commit `fix(tour): count six steps, don't restart after leaving, drop non-goal copy`.

---

### Task 9: Singular in the mute preview (messages)

**Files:**
- Modify: `src/main/resources/messages.properties:806`
- Test: `src/test/java/dev/hendrikhoemberg/webtesthelper/web/MuteRuleControllerTest.java` (Postgres-backed, has `createFinding(siteId, subject, location)`; route `GET /stummschaltungen/vorschau`, fragment `fragments/regelvorschau :: regelvorschau`)

- [x] **Step 1: Failing test**
```java
@Test
@WithMockUser(roles = "USER")
void previewSpeaksProperGermanForOneAndForNone() throws Exception {
    createFinding(siteA, "https://linkedin.com/solo", "/page1");

    mvc.perform(get("/stummschaltungen/vorschau")
                    .param("siteId", String.valueOf(siteA))
                    .param("subjectPattern", "*linkedin.com/solo*"))
            .andExpect(content().string(containsString("Trifft aktuell auf 1 Feststellung zu.")));
    mvc.perform(get("/stummschaltungen/vorschau")
                    .param("siteId", String.valueOf(siteA))
                    .param("subjectPattern", "*gibt-es-nicht*"))
            .andExpect(content().string(containsString("Trifft aktuell auf keine Feststellungen zu.")));
}
```
- [x] **Step 2:** Run `-Dtest=MuteRuleControllerTest`. Expected: FAIL ("1 Feststellungen").
- [x] **Step 3: Implementation**
```properties
ui.stummschaltungen.vorschau.treffer=Trifft aktuell auf {0,choice,0#keine Feststellungen|1#1 Feststellung|1<{0} Feststellungen} zu.
```
- [x] **Step 4:** Run `-Dtest=MuteRuleControllerTest` → PASS.
- [x] **Step 5:** Commit `fix(mute-rules): singular and zero wording in the rule preview`.

---

## Final verification

1. Fast gate → expect `Failures: 0, Errors: 0` with a test count of 1996 plus the new tests.
2. Full gate → compare the failure list with the pre-change full baseline. No new failures allowed.
3. Spot-check in the real app (Docker image rebuilt, or `spring-boot:run` on JDK 27):
   - the run toast
   - the tour
   - the triage prefill
   - the advanced-settings error
   - the recorder slot coming back about 1 min after closing the tab

## Self-review

- **Coverage:** items 1–9 map to Tasks 2, 3, 4, 5, 6, 8, 7, 9 and 1 respectively. Task 0 is the build prerequisite.
- **Placeholders:** none. Every step carries the code or the command.
- **Names:** `untriagedOpenCount`, `markDisconnected`/`markConnected`/`disconnectedAt`, `DISCONNECT_GRACE`, `frischAbgeschlossen`,
  `triageCurrentReason` and `triageCurrentMutedUntil` are used identically across tasks, templates and tests.
