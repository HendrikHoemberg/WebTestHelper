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

    @Test
    void helperPredicatesAndFormMetadata() {
        assertThat(TriageStatus.MUTED.requiresExpiry()).isTrue();
        assertThat(TriageStatus.WONT_FIX.requiresExpiry()).isFalse();
        assertThat(TriageStatus.ACKNOWLEDGED.requiresExpiry()).isFalse();
        assertThat(TriageStatus.UNTRIAGED.requiresExpiry()).isFalse();

        assertThat(TriageStatus.MUTED.requiresReason()).isTrue();
        assertThat(TriageStatus.WONT_FIX.requiresReason()).isTrue();
        assertThat(TriageStatus.ACKNOWLEDGED.requiresReason()).isFalse();
        assertThat(TriageStatus.UNTRIAGED.requiresReason()).isFalse();

        assertThat(TriageStatus.MUTED.allowsReason()).isTrue();
        assertThat(TriageStatus.WONT_FIX.allowsReason()).isTrue();
        assertThat(TriageStatus.ACKNOWLEDGED.allowsReason()).isTrue();
        assertThat(TriageStatus.UNTRIAGED.allowsReason()).isFalse();

        assertThat(TriageStatus.defaultFormAction()).isEqualTo(TriageStatus.ACKNOWLEDGED);
        assertThat(TriageStatus.formActions())
                .containsExactly(TriageStatus.ACKNOWLEDGED, TriageStatus.MUTED, TriageStatus.WONT_FIX, TriageStatus.UNTRIAGED);

        assertThat(TriageStatus.expiryActionNames()).isEqualTo("MUTED");
        assertThat(TriageStatus.mandatoryReasonActionNames()).isEqualTo("MUTED,WONT_FIX");
        assertThat(TriageStatus.reasonActionNames()).isEqualTo("ACKNOWLEDGED,MUTED,WONT_FIX");
    }
}
