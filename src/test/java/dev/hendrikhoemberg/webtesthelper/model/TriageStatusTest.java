package dev.hendrikhoemberg.webtesthelper.model;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class TriageStatusTest {

    @Test
    void silencingContainsMutedAndWontFixAndMatchesSilencesMethod() {
        assertThat(TriageStatus.SILENCING).containsExactlyInAnyOrder(TriageStatus.MUTED, TriageStatus.WONT_FIX);
        assertThat(TriageStatus.MUTED.silences()).isTrue();
        assertThat(TriageStatus.WONT_FIX.silences()).isTrue();
        assertThat(TriageStatus.UNTRIAGED.silences()).isFalse();
        assertThat(TriageStatus.ACKNOWLEDGED.silences()).isFalse();
    }

    @Test
    void everyConstantIsExhaustivelyClassifiedAsSilencingOrNonSilencing() {
        Set<TriageStatus> nonSilencing = Set.of(TriageStatus.UNTRIAGED, TriageStatus.ACKNOWLEDGED);

        Set<TriageStatus> union = new HashSet<>(TriageStatus.SILENCING);
        union.addAll(nonSilencing);

        assertThat(union)
                .as("SILENCING and nonSilencing must partition all TriageStatus constants")
                .containsExactlyInAnyOrder(TriageStatus.values());

        assertThat(TriageStatus.SILENCING)
                .as("SILENCING and nonSilencing must be disjoint")
                .doesNotContainAnyElementsOf(nonSilencing);

        for (TriageStatus status : TriageStatus.values()) {
            assertThat(status.silences())
                    .as("status.silences() must match whether status is in SILENCING")
                    .isEqualTo(TriageStatus.SILENCING.contains(status));
        }
    }
}
