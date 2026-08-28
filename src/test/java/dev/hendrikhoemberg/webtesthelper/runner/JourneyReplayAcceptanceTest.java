package dev.hendrikhoemberg.webtesthelper.runner;

import dev.hendrikhoemberg.webtesthelper.catalog.CredentialService;
import dev.hendrikhoemberg.webtesthelper.catalog.SecretBox;
import dev.hendrikhoemberg.webtesthelper.catalog.persistence.CredentialRepository;
import dev.hendrikhoemberg.webtesthelper.catalog.persistence.SiteRepository;
import dev.hendrikhoemberg.webtesthelper.crawler.BrowserPool;
import dev.hendrikhoemberg.webtesthelper.crawler.CrawlerProperties;
import dev.hendrikhoemberg.webtesthelper.model.AssertionType;
import dev.hendrikhoemberg.webtesthelper.model.CrawlBudget;
import dev.hendrikhoemberg.webtesthelper.model.JourneyDefinition;
import dev.hendrikhoemberg.webtesthelper.model.JourneyStep;
import dev.hendrikhoemberg.webtesthelper.model.LocatorCandidate;
import dev.hendrikhoemberg.webtesthelper.model.LocatorStrategy;
import dev.hendrikhoemberg.webtesthelper.model.SiteContext;
import dev.hendrikhoemberg.webtesthelper.model.StepAction;
import dev.hendrikhoemberg.webtesthelper.model.StepAssertion;
import dev.hendrikhoemberg.webtesthelper.support.FixtureSite;
import dev.hendrikhoemberg.webtesthelper.support.Snapshots;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Acceptance test for {@link JourneyReplayer} (§10.2, §10.4, Task 6).
 */
@Tag("browser")
class JourneyReplayAcceptanceTest {

    private static FixtureSite fixtureSite;
    private static BrowserPool pool;
    private static JourneyReplayer replayer;
    private static CrawlerProperties crawlerProperties;

    @BeforeAll
    static void start(@TempDir Path tempDir) {
        fixtureSite = FixtureSite.start();
        crawlerProperties = new CrawlerProperties(1, 10, Duration.ofSeconds(5),
                Duration.ZERO, tempDir, true);
        pool = new BrowserPool(crawlerProperties);

        CredentialRepository credentials = mock(CredentialRepository.class);
        SiteRepository sites = mock(SiteRepository.class);
        SecretBox secretBox = mock(SecretBox.class);
        CredentialService credentialService = new CredentialService(credentials, sites, secretBox);
        JourneyValueResolver valueResolver = new JourneyValueResolver(credentialService);

        replayer = new JourneyReplayer(pool, valueResolver);
    }

    @AfterAll
    static void stop() {
        if (pool != null) {
            pool.close();
        }
        if (fixtureSite != null) {
            fixtureSite.close();
        }
    }

    private SiteContext siteContext() {
        return new SiteContext(
                1L,
                "Fixture Site",
                Snapshots.url(fixtureSite.url("reise/start.html")),
                CrawlBudget.DEFAULT,
                List.of(),
                List.of(),
                List.of(),
                true,
                null,
                Map.of()
        );
    }

    private static JourneyStep step(
            int ordinal,
            StepAction action,
            List<LocatorCandidate> candidates,
            String value,
            StepAssertion assertion
    ) {
        return new JourneyStep(
                UUID.randomUUID(),
                ordinal,
                action,
                candidates,
                value,
                assertion,
                false,
                5000
        );
    }

    @Test
    void replaysPassedWhenAllStepsMatchPrimary(@TempDir Path artifacts) {
        // (a) Fixture journey: start -> click -> fill name -> fill email -> submit -> assert confirmation
        JourneyStep step0 = step(0, StepAction.GOTO, List.of(), fixtureSite.url("reise/start.html"), null);
        JourneyStep step1 = step(1, StepAction.CLICK, List.of(
                new LocatorCandidate(LocatorStrategy.TEST_ID, "reise-start", 0),
                new LocatorCandidate(LocatorStrategy.ROLE, "link[name='Reise buchen']", 0)
        ), null, null);
        JourneyStep step2 = step(2, StepAction.FILL, List.of(
                new LocatorCandidate(LocatorStrategy.TEST_ID, "reise-name", 0),
                new LocatorCandidate(LocatorStrategy.LABEL, "Name", 0)
        ), "Erika Mustermann", null);
        JourneyStep step3 = step(3, StepAction.FILL, List.of(
                new LocatorCandidate(LocatorStrategy.LABEL, "E-Mail", 0),
                new LocatorCandidate(LocatorStrategy.ID, ":r7:", 0)
        ), "erika@example.com", null);
        JourneyStep step4 = step(4, StepAction.CLICK, List.of(
                new LocatorCandidate(LocatorStrategy.TEST_ID, "reise-submit", 0),
                new LocatorCandidate(LocatorStrategy.ROLE, "button[name='Buchung abschließen']", 0)
        ), null, null);
        JourneyStep step5 = step(5, StepAction.ASSERT, List.of(
                new LocatorCandidate(LocatorStrategy.TEST_ID, "bestaetigung", 0),
                new LocatorCandidate(LocatorStrategy.ROLE, "heading[name='Buchung bestätigt']", 0)
        ), null, new StepAssertion(AssertionType.TEXT_CONTAINS, "Buchung bestätigt"));

        JourneyDefinition journey = new JourneyDefinition(
                100L,
                1L,
                "Erfolgreiche Buchungsreise",
                true,
                List.of(step0, step1, step2, step3, step4, step5)
        );

        JourneyReplayResult result = replayer.replay(journey, siteContext(), artifacts);

        assertThat(result.journeyId()).isEqualTo(100L);
        assertThat(result.journeyName()).isEqualTo("Erfolgreiche Buchungsreise");
        assertThat(result.status()).isEqualTo(ReplayStatus.PASSED);
        assertThat(result.driftCount()).isZero();
        assertThat(result.outcomes()).hasSize(6);

        for (StepOutcome outcome : result.outcomes()) {
            assertThat(outcome.status()).isEqualTo(StepStatus.PASSED);
            assertThat(outcome.drifted()).isFalse();
            assertThat(outcome.failureMessageKey()).isNull();
        }

        assertThat(result.screenshotName()).isEmpty();
        assertThat(result.traceName()).isEmpty();
    }

    @Test
    void replaysDriftedWhenPrimaryCandidateRenamed(@TempDir Path artifacts) {
        // (b) Same journey, but step 1 TEST_ID candidate is changed to a value on no page.
        // Fallback to ROLE succeeds -> status = DRIFTED, driftCount = 1, winner = ROLE.
        JourneyStep step0 = step(0, StepAction.GOTO, List.of(), fixtureSite.url("reise/start.html"), null);
        LocatorCandidate brokenTestId = new LocatorCandidate(LocatorStrategy.TEST_ID, "non-existent-reise-start", 0);
        LocatorCandidate fallbackRole = new LocatorCandidate(LocatorStrategy.ROLE, "link[name='Reise buchen']", 0);
        JourneyStep step1 = step(1, StepAction.CLICK, List.of(brokenTestId, fallbackRole), null, null);
        JourneyStep step2 = step(2, StepAction.FILL, List.of(
                new LocatorCandidate(LocatorStrategy.TEST_ID, "reise-name", 0)
        ), "Erika Mustermann", null);
        JourneyStep step3 = step(3, StepAction.FILL, List.of(
                new LocatorCandidate(LocatorStrategy.LABEL, "E-Mail", 0)
        ), "erika@example.com", null);
        JourneyStep step4 = step(4, StepAction.CLICK, List.of(
                new LocatorCandidate(LocatorStrategy.TEST_ID, "reise-submit", 0)
        ), null, null);
        JourneyStep step5 = step(5, StepAction.ASSERT, List.of(
                new LocatorCandidate(LocatorStrategy.TEST_ID, "bestaetigung", 0)
        ), null, new StepAssertion(AssertionType.TEXT_CONTAINS, "Buchung bestätigt"));

        JourneyDefinition journey = new JourneyDefinition(
                101L,
                1L,
                "Gedriftete Buchungsreise",
                true,
                List.of(step0, step1, step2, step3, step4, step5)
        );

        JourneyReplayResult result = replayer.replay(journey, siteContext(), artifacts);

        assertThat(result.status()).isEqualTo(ReplayStatus.DRIFTED);
        assertThat(result.driftCount()).isEqualTo(1);
        assertThat(result.outcomes()).hasSize(6);

        StepOutcome step1Outcome = result.outcomes().get(1);
        assertThat(step1Outcome.status()).isEqualTo(StepStatus.DRIFTED);
        assertThat(step1Outcome.drifted()).isTrue();
        assertThat(step1Outcome.winner()).isEqualTo(fallbackRole);

        // Remaining steps pass normally without drift
        for (int i : List.of(0, 2, 3, 4, 5)) {
            assertThat(result.outcomes().get(i).status()).isEqualTo(StepStatus.PASSED);
            assertThat(result.outcomes().get(i).drifted()).isFalse();
        }

        assertThat(result.screenshotName()).isEmpty();
        assertThat(result.traceName()).isEmpty();
    }

    @Test
    void replaysFailedAndStopsAtFailingStep(@TempDir Path artifacts) {
        // (c) Journey whose third step (index 2) targets an element on no page.
        // Replays FAILED, stops at that step, subsequent steps are absent.
        JourneyStep step0 = step(0, StepAction.GOTO, List.of(), fixtureSite.url("reise/start.html"), null);
        JourneyStep step1 = step(1, StepAction.CLICK, List.of(
                new LocatorCandidate(LocatorStrategy.TEST_ID, "reise-start", 0)
        ), null, null);
        JourneyStep step2 = step(2, StepAction.FILL, List.of(
                new LocatorCandidate(LocatorStrategy.TEST_ID, "non-existent-input", 0)
        ), "Erika Mustermann", null);
        JourneyStep step3 = step(3, StepAction.CLICK, List.of(
                new LocatorCandidate(LocatorStrategy.TEST_ID, "reise-submit", 0)
        ), null, null);
        JourneyStep step4 = step(4, StepAction.ASSERT, List.of(
                new LocatorCandidate(LocatorStrategy.TEST_ID, "bestaetigung", 0)
        ), null, new StepAssertion(AssertionType.TEXT_CONTAINS, "Buchung bestätigt"));

        JourneyDefinition journey = new JourneyDefinition(
                102L,
                1L,
                "Fehlschlagende Buchungsreise",
                true,
                List.of(step0, step1, step2, step3, step4)
        );

        JourneyReplayResult result = replayer.replay(journey, siteContext(), artifacts);

        assertThat(result.status()).isEqualTo(ReplayStatus.FAILED);
        assertThat(result.outcomes()).hasSize(3);

        assertThat(result.outcomes().get(0).status()).isEqualTo(StepStatus.PASSED);
        assertThat(result.outcomes().get(1).status()).isEqualTo(StepStatus.PASSED);

        StepOutcome failingOutcome = result.outcomes().get(2);
        assertThat(failingOutcome.status()).isEqualTo(StepStatus.FAILED);
        assertThat(failingOutcome.failureMessageKey()).isNotNull();
    }

    @Test
    void failedReplayCapturesScreenshotAndTrace(@TempDir Path artifacts) throws IOException {
        // (d) Failed replay writes a screenshot (.png) and a trace (.zip) to artifacts directory
        JourneyStep step0 = step(0, StepAction.GOTO, List.of(), fixtureSite.url("reise/start.html"), null);
        JourneyStep step1 = step(1, StepAction.CLICK, List.of(
                new LocatorCandidate(LocatorStrategy.TEST_ID, "reise-start", 0)
        ), null, null);
        JourneyStep step2 = step(2, StepAction.CLICK, List.of(
                new LocatorCandidate(LocatorStrategy.TEST_ID, "non-existent-button", 0)
        ), null, null);

        JourneyDefinition journey = new JourneyDefinition(
                103L,
                1L,
                "Fehlschlag mit Artefakten",
                true,
                List.of(step0, step1, step2)
        );

        JourneyReplayResult result = replayer.replay(journey, siteContext(), artifacts);

        assertThat(result.status()).isEqualTo(ReplayStatus.FAILED);
        assertThat(result.screenshotName()).isPresent();
        assertThat(result.traceName()).isPresent();

        String screenshotFilename = result.screenshotName().get();
        String traceFilename = result.traceName().get();

        assertThat(screenshotFilename).matches("^[0-9a-f]{32}\\.png$");
        assertThat(traceFilename).matches("^[0-9a-f]{32}\\.zip$");

        Path screenshotFile = artifacts.resolve(screenshotFilename);
        Path traceFile = artifacts.resolve(traceFilename);

        assertThat(screenshotFile).exists();
        assertThat(Files.size(screenshotFile)).isGreaterThan(0L);
        assertThat(traceFile).exists();
        assertThat(Files.size(traceFile)).isGreaterThan(0L);
    }
}
