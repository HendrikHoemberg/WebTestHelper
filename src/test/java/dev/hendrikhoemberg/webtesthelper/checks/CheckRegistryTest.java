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
        // Plan 3b implements these five; delete them from this set as they land, and the test
        // starts demanding them. Spec 7.3: adding a check must not require touching the runner,
        // and this is what makes forgetting to register one impossible to miss.
        Set<CheckType> pendingInPlan3b = EnumSet.of(CheckType.DEAD_LINK, CheckType.FILE_DOWNLOAD,
                CheckType.TLS_CERT, CheckType.HREFLANG, CheckType.SITEMAP_CONSISTENCY);
        Set<CheckType> expected = EnumSet.allOf(CheckType.class);
        expected.removeAll(pendingInPlan3b);

        assertThat(registry.coveredTypes()).containsExactlyInAnyOrderElementsOf(expected);
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