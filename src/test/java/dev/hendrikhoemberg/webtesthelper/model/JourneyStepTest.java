package dev.hendrikhoemberg.webtesthelper.model;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JourneyStepTest {

    private static final UUID STEP_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    @Test
    void nullIdIsRejected() {
        assertThatThrownBy(() -> new JourneyStep(
                null, 0, StepAction.GOTO, List.of(), "https://example.com", null, false, 5000
        )).isInstanceOf(NullPointerException.class);
    }

    @Test
    void nullActionIsRejected() {
        assertThatThrownBy(() -> new JourneyStep(
                STEP_ID, 0, null, List.of(), "https://example.com", null, false, 5000
        )).isInstanceOf(NullPointerException.class);
    }

    @Test
    void locatorCandidatesAreCopiedDefensivelyAndSortedByRankAscending() {
        LocatorCandidate rank3 = new LocatorCandidate(LocatorStrategy.CSS, "button.submit", 3);
        LocatorCandidate rank1 = new LocatorCandidate(LocatorStrategy.TEST_ID, "submit-btn", 1);
        LocatorCandidate rank2 = new LocatorCandidate(LocatorStrategy.ROLE, "Absenden", 2);

        List<LocatorCandidate> mutableList = new ArrayList<>(List.of(rank3, rank1, rank2));

        JourneyStep step = new JourneyStep(
                STEP_ID, 0, StepAction.CLICK, mutableList, null, null, false, 5000
        );

        // Sorted by rank ascending: 1, 2, 3
        assertThat(step.locatorCandidates()).containsExactly(rank1, rank2, rank3);

        // Defensive copy: modifying source list does not affect step
        mutableList.add(new LocatorCandidate(LocatorStrategy.TEXT, "Submit", 4));
        assertThat(step.locatorCandidates()).containsExactly(rank1, rank2, rank3);

        // Immutable: returned list cannot be modified
        assertThatThrownBy(() -> step.locatorCandidates().add(rank1))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void nullLocatorCandidatesDefaultsToEmptyList() {
        JourneyStep step = new JourneyStep(
                STEP_ID, 0, StepAction.GOTO, null, "https://example.com", null, false, 5000
        );
        assertThat(step.locatorCandidates()).isEmpty();
    }

    @Test
    void gotoStepIsValidWithNoCandidatesAndNonBlankValue() {
        JourneyStep step = new JourneyStep(
                STEP_ID, 0, StepAction.GOTO, List.of(), "https://example.com/login", null, false, 5000
        );
        assertThat(step.action()).isEqualTo(StepAction.GOTO);
        assertThat(step.value()).isEqualTo("https://example.com/login");
        assertThat(step.locatorCandidates()).isEmpty();
    }

    @Test
    void gotoStepWithNullOrBlankValueIsRejected() {
        assertThatThrownBy(() -> new JourneyStep(
                STEP_ID, 0, StepAction.GOTO, List.of(), null, null, false, 5000
        )).isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> new JourneyStep(
                STEP_ID, 0, StepAction.GOTO, List.of(), "   ", null, false, 5000
        )).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void clickStepWithNoCandidatesIsRejected() {
        assertThatThrownBy(() -> new JourneyStep(
                STEP_ID, 0, StepAction.CLICK, List.of(), null, null, false, 5000
        )).isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> new JourneyStep(
                STEP_ID, 0, StepAction.CLICK, null, null, null, false, 5000
        )).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void timeoutMsDefaultsToDefaultTimeoutWhenZeroOrNegative() {
        assertThat(JourneyStep.DEFAULT_TIMEOUT_MS).isGreaterThan(0);

        JourneyStep zeroTimeout = new JourneyStep(
                STEP_ID, 0, StepAction.GOTO, List.of(), "https://example.com", null, false, 0
        );
        assertThat(zeroTimeout.timeoutMs()).isEqualTo(JourneyStep.DEFAULT_TIMEOUT_MS);

        JourneyStep negativeTimeout = new JourneyStep(
                STEP_ID, 0, StepAction.GOTO, List.of(), "https://example.com", null, false, -100
        );
        assertThat(negativeTimeout.timeoutMs()).isEqualTo(JourneyStep.DEFAULT_TIMEOUT_MS);

        JourneyStep explicitTimeout = new JourneyStep(
                STEP_ID, 0, StepAction.GOTO, List.of(), "https://example.com", null, false, 12000
        );
        assertThat(explicitTimeout.timeoutMs()).isEqualTo(12000);
    }

    @Test
    void journeyDefinitionStoresFieldsAndDefensivelyCopiesSteps() {
        JourneyStep step = new JourneyStep(
                STEP_ID, 0, StepAction.GOTO, List.of(), "https://example.com", null, false, 5000
        );
        List<JourneyStep> stepList = new ArrayList<>(List.of(step));

        JourneyDefinition definition = new JourneyDefinition(1L, 10L, "Buchungsablauf", true, stepList);

        assertThat(definition.id()).isEqualTo(1L);
        assertThat(definition.siteId()).isEqualTo(10L);
        assertThat(definition.name()).isEqualTo("Buchungsablauf");
        assertThat(definition.enabled()).isTrue();
        assertThat(definition.steps()).containsExactly(step);

        // Defensive copy check
        stepList.clear();
        assertThat(definition.steps()).containsExactly(step);

        assertThatThrownBy(() -> definition.steps().add(step))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void journeyDefinitionRejectsNullOrBlankName() {
        assertThatThrownBy(() -> new JourneyDefinition(1L, 10L, null, true, List.of()))
                .isInstanceOf(NullPointerException.class);

        assertThatThrownBy(() -> new JourneyDefinition(1L, 10L, "  ", true, List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void stepAssertionStoresTypeAndExpected() {
        StepAssertion assertion = new StepAssertion(AssertionType.TEXT_CONTAINS, "Willkommen");
        assertThat(assertion.type()).isEqualTo(AssertionType.TEXT_CONTAINS);
        assertThat(assertion.expected()).isEqualTo("Willkommen");

        assertThatThrownBy(() -> new StepAssertion(null, "foo"))
                .isInstanceOf(NullPointerException.class);
    }
}
