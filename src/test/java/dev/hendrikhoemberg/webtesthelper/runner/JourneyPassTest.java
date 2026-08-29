package dev.hendrikhoemberg.webtesthelper.runner;

import dev.hendrikhoemberg.webtesthelper.catalog.JourneyHealth;
import dev.hendrikhoemberg.webtesthelper.catalog.JourneyHealthService;
import dev.hendrikhoemberg.webtesthelper.catalog.JourneyService;
import dev.hendrikhoemberg.webtesthelper.findings.MaterialisedFinding;
import dev.hendrikhoemberg.webtesthelper.model.CheckType;
import dev.hendrikhoemberg.webtesthelper.model.CrawlBudget;
import dev.hendrikhoemberg.webtesthelper.model.JourneyDefinition;
import dev.hendrikhoemberg.webtesthelper.model.JourneyReplayResult;
import dev.hendrikhoemberg.webtesthelper.model.JourneyStep;
import dev.hendrikhoemberg.webtesthelper.model.LocatorCandidate;
import dev.hendrikhoemberg.webtesthelper.model.LocatorStrategy;
import dev.hendrikhoemberg.webtesthelper.model.NormalizedUrl;
import dev.hendrikhoemberg.webtesthelper.model.ReplayStatus;
import dev.hendrikhoemberg.webtesthelper.model.RunScope;
import dev.hendrikhoemberg.webtesthelper.model.SiteContext;
import dev.hendrikhoemberg.webtesthelper.model.StepAction;
import dev.hendrikhoemberg.webtesthelper.model.StepOutcome;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class JourneyPassTest {

    @Mock
    private JourneyService journeyService;

    @Mock
    private JourneyReplayer journeyReplayer;

    @Mock
    private JourneyHealthService journeyHealthService;

    private JourneyPass journeyPass;
    private SiteContext site;
    private Path artifactsPath;

    @BeforeEach
    void setUp() {
        journeyPass = new JourneyPass(journeyService, journeyReplayer, journeyHealthService);
        site = new SiteContext(
                42L,
                "Test Site",
                new NormalizedUrl("https", "example.com", 443, "/", null),
                CrawlBudget.DEFAULT,
                List.of(),
                List.of(),
                List.of(),
                true,
                null,
                Map.of()
        );
        artifactsPath = Path.of("/tmp/artifacts/run-1");
    }

    @Test
    void fullRunReplaysEnabledJourneysAndSkipsDisabledOnes() {
        JourneyStep step1 = new JourneyStep(UUID.randomUUID(), 0, StepAction.GOTO,
                List.of(new LocatorCandidate(LocatorStrategy.CSS, "body", 0)), "https://example.com/", null, false, 5000);
        JourneyDefinition enabledJourney = new JourneyDefinition(101L, site.siteId(), "Checkout Flow", true, List.of(step1));
        JourneyDefinition disabledJourney = new JourneyDefinition(102L, site.siteId(), "Login Flow", false, List.of(step1));

        JourneyReplayResult replayResult = new JourneyReplayResult(101L, "Checkout Flow", ReplayStatus.PASSED, List.of(StepOutcome.passed(step1.id(), null)), 0, Optional.empty(), Optional.empty());
        when(journeyService.findBySite(site.siteId())).thenReturn(List.of(enabledJourney, disabledJourney));
        when(journeyReplayer.replay(eq(enabledJourney), eq(site), eq(artifactsPath)))
                .thenReturn(replayResult);
        when(journeyHealthService.record(101L, replayResult))
                .thenReturn(new JourneyHealth(null, 0, 0, List.of()));

        JourneyPassResult result = journeyPass.run(site, RunScope.FULL, artifactsPath);

        verify(journeyReplayer).replay(enabledJourney, site, artifactsPath);
        verify(journeyReplayer, never()).replay(eq(disabledJourney), any(), any());
        verify(journeyHealthService).record(101L, replayResult);
        verify(journeyHealthService, never()).record(eq(102L), any());

        assertThat(result.completedJourneyIds()).containsExactly(101L);
        assertThat(result.completedJourneyIds()).doesNotContain(102L);
        assertThat(result.findings()).isEmpty();
    }

    @Test
    void pulseRunReplaysNone() {
        JourneyStep step1 = new JourneyStep(UUID.randomUUID(), 0, StepAction.GOTO,
                List.of(new LocatorCandidate(LocatorStrategy.CSS, "body", 0)), "https://example.com/", null, false, 5000);
        JourneyDefinition journey = new JourneyDefinition(101L, site.siteId(), "Checkout Flow", true, List.of(step1));

        JourneyPassResult result = journeyPass.run(site, RunScope.PULSE, artifactsPath);

        verify(journeyService, never()).findBySite(any(Long.class));
        verify(journeyReplayer, never()).replay(any(), any(), any());
        verify(journeyHealthService, never()).record(anyLong(), any());

        assertThat(result.completedJourneyIds()).isEmpty();
        assertThat(result.findings()).isEmpty();
    }

    @Test
    void replayerThrowingIsCaughtAndOmittedFromCompletedWithoutAbortingPass() {
        JourneyStep step1 = new JourneyStep(UUID.randomUUID(), 0, StepAction.GOTO,
                List.of(new LocatorCandidate(LocatorStrategy.CSS, "body", 0)), "https://example.com/", null, false, 5000);
        JourneyDefinition brokenJourney = new JourneyDefinition(201L, site.siteId(), "Broken Journey", true, List.of(step1));
        JourneyDefinition healthyJourney = new JourneyDefinition(202L, site.siteId(), "Healthy Journey", true, List.of(step1));

        JourneyReplayResult healthyResult = new JourneyReplayResult(202L, "Healthy Journey", ReplayStatus.PASSED, List.of(StepOutcome.passed(step1.id(), null)), 0, Optional.empty(), Optional.empty());
        when(journeyService.findBySite(site.siteId())).thenReturn(List.of(brokenJourney, healthyJourney));
        when(journeyReplayer.replay(eq(brokenJourney), eq(site), eq(artifactsPath)))
                .thenThrow(new RuntimeException("Playwright browser crash"));
        when(journeyReplayer.replay(eq(healthyJourney), eq(site), eq(artifactsPath)))
                .thenReturn(healthyResult);
        when(journeyHealthService.record(202L, healthyResult))
                .thenReturn(new JourneyHealth(null, 0, 0, List.of()));

        JourneyPassResult result = journeyPass.run(site, RunScope.FULL, artifactsPath);

        verify(journeyReplayer).replay(brokenJourney, site, artifactsPath);
        verify(journeyReplayer).replay(healthyJourney, site, artifactsPath);
        verify(journeyHealthService, never()).record(eq(201L), any());
        verify(journeyHealthService).record(202L, healthyResult);

        assertThat(result.completedJourneyIds()).containsExactly(202L);
        assertThat(result.completedJourneyIds()).doesNotContain(201L);
        assertThat(result.findings()).isEmpty();
    }

    @Test
    void findingsFromAllReplaysAreReturnedTogether() {
        UUID step1Id = UUID.randomUUID();
        UUID step2Id = UUID.randomUUID();
        JourneyStep step1 = new JourneyStep(step1Id, 0, StepAction.CLICK,
                List.of(new LocatorCandidate(LocatorStrategy.CSS, "button#submit", 0)), null, null, false, 5000);
        JourneyStep step2 = new JourneyStep(step2Id, 0, StepAction.FILL,
                List.of(new LocatorCandidate(LocatorStrategy.CSS, "input#search", 0)), "search text", null, false, 5000);

        JourneyDefinition journey1 = new JourneyDefinition(301L, site.siteId(), "Order Flow", true, List.of(step1));
        JourneyDefinition journey2 = new JourneyDefinition(302L, site.siteId(), "Search Flow", true, List.of(step2));

        when(journeyService.findBySite(site.siteId())).thenReturn(List.of(journey1, journey2));

        JourneyReplayResult result1 = new JourneyReplayResult(
                301L,
                "Order Flow",
                ReplayStatus.FAILED,
                List.of(StepOutcome.failed(step1Id, "error.step", List.of("button"))),
                0,
                Optional.of("failure-screenshot.png"),
                Optional.of("failure-trace.zip")
        );
        JourneyReplayResult result2 = new JourneyReplayResult(
                302L,
                "Search Flow",
                ReplayStatus.DRIFTED,
                List.of(StepOutcome.drifted(step2Id, new LocatorCandidate(LocatorStrategy.TEXT, "Search", 1))),
                1,
                Optional.empty(),
                Optional.empty()
        );

        when(journeyReplayer.replay(eq(journey1), eq(site), eq(artifactsPath))).thenReturn(result1);
        when(journeyReplayer.replay(eq(journey2), eq(site), eq(artifactsPath))).thenReturn(result2);
        when(journeyHealthService.record(301L, result1)).thenReturn(new JourneyHealth(null, 1, 0, List.of()));
        when(journeyHealthService.record(302L, result2)).thenReturn(new JourneyHealth(null, 0, 1, List.of()));

        JourneyPassResult passResult = journeyPass.run(site, RunScope.FULL, artifactsPath);

        verify(journeyHealthService).record(301L, result1);
        verify(journeyHealthService).record(302L, result2);

        assertThat(passResult.completedJourneyIds()).containsExactlyInAnyOrder(301L, 302L);
        assertThat(passResult.findings()).hasSize(2);
        assertThat(passResult.findings()).extracting(MaterialisedFinding::type)
                .containsExactlyInAnyOrder(CheckType.JOURNEY_STEP_FAILED, CheckType.SELECTOR_DRIFT);
    }

    @Test
    void needsRerecordingSuppressesStepFailedButKeepsSelectorDriftAndRecordsHealth() {
        UUID failedStepId = UUID.randomUUID();
        UUID driftedStepId = UUID.randomUUID();
        JourneyStep failedStep = new JourneyStep(failedStepId, 0, StepAction.CLICK,
                List.of(new LocatorCandidate(LocatorStrategy.CSS, "button#submit", 0)), null, null, false, 5000);
        JourneyStep driftedStep = new JourneyStep(driftedStepId, 1, StepAction.FILL,
                List.of(new LocatorCandidate(LocatorStrategy.CSS, "input#search", 0)), "q", null, false, 5000);
        JourneyDefinition journey = new JourneyDefinition(401L, site.siteId(), "Stale Flow", true,
                List.of(failedStep, driftedStep));

        JourneyReplayResult result = new JourneyReplayResult(
                401L,
                "Stale Flow",
                ReplayStatus.FAILED,
                List.of(StepOutcome.failed(failedStepId, "error.step", List.of("b")),
                        StepOutcome.drifted(driftedStepId, new LocatorCandidate(LocatorStrategy.CSS, "input#search.v2", 1))),
                1,
                Optional.of("failure-screenshot.png"),
                Optional.empty());

        when(journeyService.findBySite(site.siteId())).thenReturn(List.of(journey));
        when(journeyReplayer.replay(eq(journey), eq(site), eq(artifactsPath))).thenReturn(result);
        // Health transitioned this run: the journey now needs re-recording (3 consecutive failures, drift present)
        when(journeyHealthService.record(401L, result)).thenReturn(new JourneyHealth(null, 3, 1, List.of()));

        JourneyPassResult passResult = journeyPass.run(site, RunScope.FULL, artifactsPath);

        verify(journeyHealthService).record(401L, result);
        assertThat(passResult.completedJourneyIds()).containsExactly(401L);
        // The flagged journey is threaded through to the resolve step so its findings stay ACTIVE
        assertThat(passResult.journeysNeedingRerecording()).containsExactly(401L);
        assertThat(passResult.findings()).hasSize(1);
        assertThat(passResult.findings()).extracting(MaterialisedFinding::type)
                .containsExactly(CheckType.SELECTOR_DRIFT);
    }
}

