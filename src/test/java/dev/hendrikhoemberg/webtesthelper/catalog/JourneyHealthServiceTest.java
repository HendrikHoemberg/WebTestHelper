package dev.hendrikhoemberg.webtesthelper.catalog;

import dev.hendrikhoemberg.webtesthelper.model.JourneyReplayResult;
import dev.hendrikhoemberg.webtesthelper.model.JourneyStep;
import dev.hendrikhoemberg.webtesthelper.model.LocatorCandidate;
import dev.hendrikhoemberg.webtesthelper.model.LocatorStrategy;
import dev.hendrikhoemberg.webtesthelper.model.ReplayStatus;
import dev.hendrikhoemberg.webtesthelper.model.StepAction;
import dev.hendrikhoemberg.webtesthelper.model.StepOutcome;
import dev.hendrikhoemberg.webtesthelper.support.AbstractPostgresTest;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Transactional
class JourneyHealthServiceTest extends AbstractPostgresTest {

    @Autowired
    private JourneyHealthService journeyHealthService;

    @Autowired
    private JourneyService journeyService;

    @Autowired
    private SiteService siteService;

    @Autowired
    private EntityManager entityManager;

    private long siteId;
    private long journeyId;
    private UUID stepId;

    @BeforeEach
    void setUp() {
        siteId = siteService.create(new SiteForm(
                "Health Test Site",
                "https://health.test/",
                100,
                3,
                Duration.ofMinutes(10),
                List.of(),
                List.of(),
                true,
                null,
                true
        ));
        stepId = UUID.randomUUID();
        JourneyStep step = new JourneyStep(
                stepId,
                0,
                StepAction.GOTO,
                List.of(new LocatorCandidate(LocatorStrategy.CSS, "body", 0)),
                "https://health.test/start",
                null,
                false,
                5000
        );
        journeyId = journeyService.create(siteId, "Health Journey", List.of(step));
        entityManager.flush();
        entityManager.clear();
    }

    @Test
    void initialHealthOfNewlyCreatedJourney() {
        Optional<JourneyHealth> health = journeyHealthService.health(journeyId);
        assertThat(health).isPresent();
        JourneyHealth h = health.get();
        assertThat(h.lastSuccessAt()).isNull();
        assertThat(h.consecutiveFailures()).isEqualTo(0);
        assertThat(h.driftCount()).isEqualTo(0);
        assertThat(h.needsRerecording()).isFalse();
    }

    @Test
    void passingReplaySetsLastSuccessAtAndZeroesConsecutiveFailures() {
        Instant before = Instant.now().minusSeconds(1);

        JourneyReplayResult passedResult = new JourneyReplayResult(
                journeyId,
                "Health Journey",
                ReplayStatus.PASSED,
                List.of(StepOutcome.passed(stepId, null)),
                0,
                Optional.empty(),
                Optional.empty()
        );

        JourneyHealth health = journeyHealthService.record(journeyId, passedResult);
        entityManager.flush();
        entityManager.clear();

        assertThat(health.lastSuccessAt()).isNotNull();
        assertThat(health.lastSuccessAt()).isAfterOrEqualTo(before);
        assertThat(health.consecutiveFailures()).isEqualTo(0);
        assertThat(health.driftCount()).isEqualTo(0);
        assertThat(health.needsRerecording()).isFalse();

        JourneyHealth queried = journeyHealthService.health(journeyId).orElseThrow();
        assertThat(queried.lastSuccessAt()).isEqualTo(health.lastSuccessAt());
        assertThat(queried.consecutiveFailures()).isEqualTo(0);
        assertThat(queried.driftCount()).isEqualTo(0);
        assertThat(queried.needsRerecording()).isFalse();
    }

    @Test
    void multipleFailingReplaysIncrementConsecutiveFailures() {
        JourneyReplayResult failedResult = new JourneyReplayResult(
                journeyId,
                "Health Journey",
                ReplayStatus.FAILED,
                List.of(StepOutcome.failed(stepId, "error.step", List.of("css=body"))),
                0,
                Optional.empty(),
                Optional.empty()
        );

        JourneyHealth h1 = journeyHealthService.record(journeyId, failedResult);
        assertThat(h1.consecutiveFailures()).isEqualTo(1);
        assertThat(h1.lastSuccessAt()).isNull();
        assertThat(h1.driftCount()).isEqualTo(0);
        assertThat(h1.needsRerecording()).isFalse();

        JourneyHealth h2 = journeyHealthService.record(journeyId, failedResult);
        assertThat(h2.consecutiveFailures()).isEqualTo(2);
        assertThat(h2.lastSuccessAt()).isNull();
        assertThat(h2.driftCount()).isEqualTo(0);
        assertThat(h2.needsRerecording()).isFalse();

        JourneyHealth h3 = journeyHealthService.record(journeyId, failedResult);
        entityManager.flush();
        entityManager.clear();

        assertThat(h3.consecutiveFailures()).isEqualTo(3);
        assertThat(h3.lastSuccessAt()).isNull();
        assertThat(h3.driftCount()).isEqualTo(0);
        // Repeated failure alone without drift means the site is broken, NOT stale recording
        assertThat(h3.needsRerecording()).isFalse();

        JourneyHealth queried = journeyHealthService.health(journeyId).orElseThrow();
        assertThat(queried.consecutiveFailures()).isEqualTo(3);
        assertThat(queried.lastSuccessAt()).isNull();
        assertThat(queried.driftCount()).isEqualTo(0);
        assertThat(queried.needsRerecording()).isFalse();
    }

    @Test
    void driftedReplayCountsAsSuccessForConsecutiveFailuresWhileIncrementingDriftCount() {
        JourneyReplayResult failedResult = new JourneyReplayResult(
                journeyId,
                "Health Journey",
                ReplayStatus.FAILED,
                List.of(StepOutcome.failed(stepId, "error.step", List.of("css=body"))),
                0,
                Optional.empty(),
                Optional.empty()
        );

        // Fail twice
        journeyHealthService.record(journeyId, failedResult);
        journeyHealthService.record(journeyId, failedResult);
        assertThat(journeyHealthService.health(journeyId).orElseThrow().consecutiveFailures()).isEqualTo(2);

        Instant before = Instant.now().minusSeconds(1);
        JourneyReplayResult driftedResult = new JourneyReplayResult(
                journeyId,
                "Health Journey",
                ReplayStatus.DRIFTED,
                List.of(StepOutcome.drifted(stepId, new LocatorCandidate(LocatorStrategy.CSS, "body.v2", 1))),
                2,
                Optional.empty(),
                Optional.empty()
        );

        JourneyHealth health = journeyHealthService.record(journeyId, driftedResult);
        entityManager.flush();
        entityManager.clear();

        // Drifted replay counts as success: resets consecutiveFailures to 0, sets lastSuccessAt
        assertThat(health.consecutiveFailures()).isEqualTo(0);
        assertThat(health.lastSuccessAt()).isNotNull();
        assertThat(health.lastSuccessAt()).isAfterOrEqualTo(before);
        // Increments driftCount by the drift count from result
        assertThat(health.driftCount()).isEqualTo(2);
        assertThat(health.needsRerecording()).isFalse();

        // Another drifted replay adds to drift count and keeps consecutiveFailures at 0
        JourneyReplayResult driftedResult2 = new JourneyReplayResult(
                journeyId,
                "Health Journey",
                ReplayStatus.DRIFTED,
                List.of(StepOutcome.drifted(stepId, new LocatorCandidate(LocatorStrategy.CSS, "body.v3", 2))),
                1,
                Optional.empty(),
                Optional.empty()
        );

        JourneyHealth health2 = journeyHealthService.record(journeyId, driftedResult2);
        entityManager.flush();
        entityManager.clear();

        assertThat(health2.consecutiveFailures()).isEqualTo(0);
        assertThat(health2.driftCount()).isEqualTo(3);
        assertThat(health2.needsRerecording()).isFalse();
    }

    @Test
    void needsRerecordingIsTrueWhenConsecutiveFailuresAtLeastThreeAndDriftCountPositive() {
        // Step 1: Record drift so driftCount > 0
        JourneyReplayResult driftedResult = new JourneyReplayResult(
                journeyId,
                "Health Journey",
                ReplayStatus.DRIFTED,
                List.of(StepOutcome.drifted(stepId, new LocatorCandidate(LocatorStrategy.CSS, "body.v2", 1))),
                1,
                Optional.empty(),
                Optional.empty()
        );
        journeyHealthService.record(journeyId, driftedResult);

        // Step 2: Fail 1st time -> consecutiveFailures = 1, driftCount = 1 -> needsRerecording = false
        JourneyReplayResult failedResult = new JourneyReplayResult(
                journeyId,
                "Health Journey",
                ReplayStatus.FAILED,
                List.of(StepOutcome.failed(stepId, "error.step", List.of("css=body"))),
                0,
                Optional.empty(),
                Optional.empty()
        );
        JourneyHealth h1 = journeyHealthService.record(journeyId, failedResult);
        assertThat(h1.consecutiveFailures()).isEqualTo(1);
        assertThat(h1.driftCount()).isEqualTo(1);
        assertThat(h1.needsRerecording()).isFalse();

        // Step 3: Fail 2nd time -> consecutiveFailures = 2, driftCount = 1 -> needsRerecording = false
        JourneyHealth h2 = journeyHealthService.record(journeyId, failedResult);
        assertThat(h2.consecutiveFailures()).isEqualTo(2);
        assertThat(h2.driftCount()).isEqualTo(1);
        assertThat(h2.needsRerecording()).isFalse();

        // Step 4: Fail 3rd time -> consecutiveFailures = 3, driftCount = 1 -> needsRerecording = true (§10.4 threshold)
        JourneyHealth h3 = journeyHealthService.record(journeyId, failedResult);
        entityManager.flush();
        entityManager.clear();

        assertThat(h3.consecutiveFailures()).isEqualTo(3);
        assertThat(h3.driftCount()).isEqualTo(1);
        assertThat(h3.needsRerecording()).isTrue();

        JourneyHealth queried = journeyHealthService.health(journeyId).orElseThrow();
        assertThat(queried.consecutiveFailures()).isEqualTo(3);
        assertThat(queried.driftCount()).isEqualTo(1);
        assertThat(queried.needsRerecording()).isTrue();

        // Step 5: A successful pass recovers the journey (resets failures)
        JourneyReplayResult passedResult = new JourneyReplayResult(
                journeyId,
                "Health Journey",
                ReplayStatus.PASSED,
                List.of(StepOutcome.passed(stepId, null)),
                0,
                Optional.empty(),
                Optional.empty()
        );
        JourneyHealth hRecovered = journeyHealthService.record(journeyId, passedResult);
        assertThat(hRecovered.consecutiveFailures()).isEqualTo(0);
        assertThat(hRecovered.driftCount()).isEqualTo(1);
        assertThat(hRecovered.needsRerecording()).isFalse();
    }

    @Test
    void nonExistentJourneyHandling() {
        assertThat(journeyHealthService.health(999999L)).isEmpty();

        JourneyReplayResult result = new JourneyReplayResult(
                999999L,
                "Ghost Journey",
                ReplayStatus.PASSED,
                List.of(),
                0,
                Optional.empty(),
                Optional.empty()
        );

        assertThatThrownBy(() -> journeyHealthService.record(999999L, result))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Journey existiert nicht: 999999");
    }
}
