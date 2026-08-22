package dev.hendrikhoemberg.webtesthelper.checks;

import dev.hendrikhoemberg.webtesthelper.model.CheckType;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class CheckRegistryTest {

    private final CheckRegistry registry = CheckRegistry.standard();

    @Test
    void everyCheckTypeThatShipsInPhaseOneHasExactlyOneImplementation() {
        // Spec 7.3: adding a check must not require touching the runner, and this is what makes
        // forgetting to register one impossible to miss. Plan 3b landed TLS_CERT, HREFLANG and
        // SITEMAP_CONSISTENCY, so every CheckType now has exactly one implementation.
        assertThat(registry.coveredTypes())
                .containsExactlyInAnyOrderElementsOf(EnumSet.allOf(CheckType.class));
    }

    @Test
    void noCheckTypeIsImplementedTwice() {
        List<CheckType> types = registry.all().stream().map(CheckDescriptor::type).toList();
        assertThat(types).doesNotHaveDuplicates();
    }

    @Test
    void everyCheckDeclaresAtLeastOneFindingMessageKeyForItsOwnType() {
        assertThat(registry.all()).allSatisfy(check -> {
            assertThat(check.messageKeys()).isNotEmpty();
            assertThat(check.messageKeys()).allSatisfy(key ->
                    assertThat(key).startsWith("finding." + check.type().name() + "."));
        });
    }

    @Test
    void theThreeCatalogKeysAreDerivedFromTheTypeSoTheyCannotDrift() {
        assertThat(registry.all()).allSatisfy(check -> {
            assertThat(check.titleKey()).isEqualTo("check." + check.type().name() + ".title");
            assertThat(check.descriptionKey())
                    .isEqualTo("check." + check.type().name() + ".description");
            assertThat(check.remediationKey())
                    .isEqualTo("check." + check.type().name() + ".remediation");
        });
    }
}