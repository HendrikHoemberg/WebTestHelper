package dev.hendrikhoemberg.webtesthelper.model;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LocatorCandidateTest {

    @Test
    void locatorStrategyValuesAreInRankOrderWithTestIdFirstAndCssLast() {
        assertThat(LocatorStrategy.values()).containsExactly(
                LocatorStrategy.TEST_ID,
                LocatorStrategy.ROLE,
                LocatorStrategy.LABEL,
                LocatorStrategy.ID,
                LocatorStrategy.TEXT,
                LocatorStrategy.CSS
        );

        assertThat(LocatorStrategy.TEST_ID.ordinal()).isLessThan(LocatorStrategy.ROLE.ordinal());
        assertThat(LocatorStrategy.ROLE.ordinal()).isLessThan(LocatorStrategy.LABEL.ordinal());
        assertThat(LocatorStrategy.LABEL.ordinal()).isLessThan(LocatorStrategy.ID.ordinal());
        assertThat(LocatorStrategy.ID.ordinal()).isLessThan(LocatorStrategy.TEXT.ordinal());
        assertThat(LocatorStrategy.TEXT.ordinal()).isLessThan(LocatorStrategy.CSS.ordinal());

        assertThat(LocatorStrategy.values()[0]).isEqualTo(LocatorStrategy.TEST_ID);
        assertThat(LocatorStrategy.values()[LocatorStrategy.values().length - 1]).isEqualTo(LocatorStrategy.CSS);
    }

    @Test
    void locatorCandidateRejectsNullStrategy() {
        assertThatThrownBy(() -> new LocatorCandidate(null, "submit-btn", 0))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void locatorCandidateRejectsNullOrBlankValue() {
        assertThatThrownBy(() -> new LocatorCandidate(LocatorStrategy.TEST_ID, null, 0))
                .isInstanceOf(NullPointerException.class);

        assertThatThrownBy(() -> new LocatorCandidate(LocatorStrategy.TEST_ID, "   ", 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void locatorCandidateRejectsNegativeRank() {
        assertThatThrownBy(() -> new LocatorCandidate(LocatorStrategy.TEST_ID, "submit-btn", -1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void locatorCandidateStoresFields() {
        LocatorCandidate candidate = new LocatorCandidate(LocatorStrategy.TEST_ID, "submit-btn", 2);
        assertThat(candidate.strategy()).isEqualTo(LocatorStrategy.TEST_ID);
        assertThat(candidate.value()).isEqualTo("submit-btn");
        assertThat(candidate.rank()).isEqualTo(2);
    }
}
