package dev.hendrikhoemberg.webtesthelper.runner;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import dev.hendrikhoemberg.webtesthelper.model.JourneyStep;
import dev.hendrikhoemberg.webtesthelper.model.LocatorCandidate;
import dev.hendrikhoemberg.webtesthelper.model.LocatorStrategy;
import dev.hendrikhoemberg.webtesthelper.model.StepAction;
import dev.hendrikhoemberg.webtesthelper.support.FixtureSite;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("browser")
class LocatorResolverTest {

    private static FixtureSite fixtureSite;
    private static Playwright playwright;
    private static Browser browser;
    private static Page page;

    @BeforeAll
    static void start() {
        fixtureSite = FixtureSite.start();
        playwright = Playwright.create();
        browser = playwright.chromium().launch(new BrowserType.LaunchOptions().setHeadless(true));
        page = browser.newPage();
        page.navigate(fixtureSite.url("reise/schritt2.html"));
    }

    @AfterAll
    static void stop() {
        if (browser != null) {
            browser.close();
        }
        if (playwright != null) {
            playwright.close();
        }
        if (fixtureSite != null) {
            fixtureSite.close();
        }
    }

    private static JourneyStep stepWith(List<LocatorCandidate> candidates) {
        return new JourneyStep(
                UUID.randomUUID(),
                0,
                StepAction.CLICK,
                candidates,
                null,
                null,
                false,
                5000
        );
    }

    @Test
    void resolvesSingleMatchingTestIdWithoutDrift() {
        LocatorCandidate candidate = new LocatorCandidate(LocatorStrategy.TEST_ID, "reise-name", 0);
        JourneyStep step = stepWith(List.of(candidate));

        Optional<LocatorMatch> match = LocatorResolver.resolve(page, step);

        assertThat(match).isPresent();
        assertThat(match.get().winner()).isEqualTo(candidate);
        assertThat(match.get().drifted()).isFalse();
        assertThat(match.get().locator().count()).isEqualTo(1);
        assertThat(match.get().locator().getAttribute("id")).isEqualTo("reise-name");
    }

    @Test
    void fallsBackToRoleWhenTestIdMatchesNothingAndReportsDrift() {
        LocatorCandidate missingTestId = new LocatorCandidate(LocatorStrategy.TEST_ID, "non-existent-id", 0);
        LocatorCandidate matchingRole = new LocatorCandidate(
                LocatorStrategy.ROLE,
                "button[name='Buchung abschließen']",
                0
        );
        JourneyStep step = stepWith(List.of(missingTestId, matchingRole));

        Optional<LocatorMatch> match = LocatorResolver.resolve(page, step);

        assertThat(match).isPresent();
        assertThat(match.get().winner()).isEqualTo(matchingRole);
        assertThat(match.get().drifted()).isTrue();
        assertThat(match.get().locator().count()).isEqualTo(1);
        assertThat(match.get().locator().getAttribute("id")).isEqualTo("reise-submit");
    }

    @Test
    void returnsEmptyWhenNoCandidateMatches() {
        LocatorCandidate missingTestId = new LocatorCandidate(LocatorStrategy.TEST_ID, "does-not-exist", 0);
        LocatorCandidate missingId = new LocatorCandidate(LocatorStrategy.ID, "also-missing", 0);
        JourneyStep step = stepWith(List.of(missingTestId, missingId));

        Optional<LocatorMatch> match = LocatorResolver.resolve(page, step);

        assertThat(match).isEmpty();
    }

    @Test
    void returnsEmptyWhenStepHasNoCandidates() {
        JourneyStep step = new JourneyStep(
                UUID.randomUUID(),
                0,
                StepAction.GOTO,
                List.of(),
                "http://example.com",
                null,
                false,
                5000
        );

        Optional<LocatorMatch> match = LocatorResolver.resolve(page, step);

        assertThat(match).isEmpty();
    }

    @Test
    void rejectsAmbiguousCandidateMatchingMultipleElements() {
        // "input" CSS matches both "reise-name" and ":r7:" on schritt2.html
        LocatorCandidate ambiguousCss = new LocatorCandidate(LocatorStrategy.CSS, "input", 0);
        JourneyStep step = stepWith(List.of(ambiguousCss));

        Optional<LocatorMatch> match = LocatorResolver.resolve(page, step);

        assertThat(match).isEmpty();
    }

    @Test
    void skipsAmbiguousCandidateAndFallsBackToSpecificOne() {
        // "textbox" role without name matches both text inputs on schritt2.html -> count == 2 -> ambiguous
        LocatorCandidate ambiguousRole = new LocatorCandidate(LocatorStrategy.ROLE, "textbox", 0);
        LocatorCandidate specificLabel = new LocatorCandidate(LocatorStrategy.LABEL, "Name", 0);
        JourneyStep step = stepWith(List.of(ambiguousRole, specificLabel));

        Optional<LocatorMatch> match = LocatorResolver.resolve(page, step);

        assertThat(match).isPresent();
        assertThat(match.get().winner()).isEqualTo(specificLabel);
        assertThat(match.get().drifted()).isTrue();
        assertThat(match.get().locator().count()).isEqualTo(1);
        assertThat(match.get().locator().getAttribute("id")).isEqualTo("reise-name");
    }

    @Test
    void respectsStrategyOrdinalOverInputOrder() {
        LocatorCandidate css = new LocatorCandidate(LocatorStrategy.CSS, "#reise-name", 0);
        LocatorCandidate testId = new LocatorCandidate(LocatorStrategy.TEST_ID, "reise-name", 0);
        // Passed in reverse order: CSS before TEST_ID
        JourneyStep step = stepWith(List.of(css, testId));

        Optional<LocatorMatch> match = LocatorResolver.resolve(page, step);

        assertThat(match).isPresent();
        // TEST_ID has higher priority (ordinal 0 vs 5), so it must be evaluated first and win
        assertThat(match.get().winner()).isEqualTo(testId);
        assertThat(match.get().drifted()).isFalse();
    }

    @Test
    void respectsRankWithinSameStrategy() {
        LocatorCandidate wrongLabel = new LocatorCandidate(LocatorStrategy.LABEL, "Nicht vorhanden", 0);
        LocatorCandidate correctLabel = new LocatorCandidate(LocatorStrategy.LABEL, "Name", 1);
        JourneyStep step = stepWith(List.of(wrongLabel, correctLabel));

        Optional<LocatorMatch> match = LocatorResolver.resolve(page, step);

        assertThat(match).isPresent();
        assertThat(match.get().winner()).isEqualTo(correctLabel);
        assertThat(match.get().drifted()).isTrue();
    }

    @Test
    void resolvesAllSixStrategies() {
        // 1. TEST_ID
        Optional<LocatorMatch> testIdMatch = LocatorResolver.resolve(page,
                stepWith(List.of(new LocatorCandidate(LocatorStrategy.TEST_ID, "reise-ziel", 0))));
        assertThat(testIdMatch).isPresent();
        assertThat(testIdMatch.get().locator().getAttribute("id")).isEqualTo("reise-ziel");

        // 2. ROLE
        Optional<LocatorMatch> roleMatch = LocatorResolver.resolve(page,
                stepWith(List.of(new LocatorCandidate(LocatorStrategy.ROLE, "combobox[name='Reiseziel']", 0))));
        assertThat(roleMatch).isPresent();
        assertThat(roleMatch.get().locator().getAttribute("id")).isEqualTo("reise-ziel");

        // 3. LABEL (email has no test id)
        Optional<LocatorMatch> labelMatch = LocatorResolver.resolve(page,
                stepWith(List.of(new LocatorCandidate(LocatorStrategy.LABEL, "E-Mail", 0))));
        assertThat(labelMatch).isPresent();
        assertThat(labelMatch.get().locator().getAttribute("id")).isEqualTo(":r7:");

        // 4. ID (generated id :r7:)
        Optional<LocatorMatch> idMatch = LocatorResolver.resolve(page,
                stepWith(List.of(new LocatorCandidate(LocatorStrategy.ID, ":r7:", 0))));
        assertThat(idMatch).isPresent();
        assertThat(idMatch.get().locator().getAttribute("type")).isEqualTo("email");

        // 5. TEXT
        Optional<LocatorMatch> textMatch = LocatorResolver.resolve(page,
                stepWith(List.of(new LocatorCandidate(LocatorStrategy.TEXT, "Buchung abschließen", 0))));
        assertThat(textMatch).isPresent();
        assertThat(textMatch.get().locator().getAttribute("id")).isEqualTo("reise-submit");

        // 6. CSS
        Optional<LocatorMatch> cssMatch = LocatorResolver.resolve(page,
                stepWith(List.of(new LocatorCandidate(LocatorStrategy.CSS, "form#reise-form", 0))));
        assertThat(cssMatch).isPresent();
        assertThat(cssMatch.get().locator().getAttribute("action")).isEqualTo("ziel.html");
    }

    @Test
    void resolvesOnStartAndZielFixturePages() {
        page.navigate(fixtureSite.url("reise/start.html"));
        Optional<LocatorMatch> startLinkMatch = LocatorResolver.resolve(page,
                stepWith(List.of(new LocatorCandidate(LocatorStrategy.TEST_ID, "reise-start", 0))));
        assertThat(startLinkMatch).isPresent();
        assertThat(startLinkMatch.get().locator().getAttribute("href")).isEqualTo("schritt2.html");

        page.navigate(fixtureSite.url("reise/ziel.html"));
        Optional<LocatorMatch> zielHeadingMatch = LocatorResolver.resolve(page,
                stepWith(List.of(new LocatorCandidate(LocatorStrategy.ROLE, "heading[name='Buchung bestätigt']", 0))));
        assertThat(zielHeadingMatch).isPresent();
        assertThat(zielHeadingMatch.get().locator().textContent()).isEqualTo("Buchung bestätigt");

        // Return page back to schritt2 for any other tests
        page.navigate(fixtureSite.url("reise/schritt2.html"));
    }
}
