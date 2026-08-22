package dev.hendrikhoemberg.webtesthelper.checks;

import dev.hendrikhoemberg.webtesthelper.model.CheckType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CheckRegistryTest {

    private final CheckRegistry registry = CheckRegistry.standard();

    @Test
    void theStandardRegistryHoldsThePageChecksThatShipToday() {
        assertThat(registry.coveredTypes())
                .contains(CheckType.PAGE_STATUS, CheckType.PAGE_UNREACHABLE);
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