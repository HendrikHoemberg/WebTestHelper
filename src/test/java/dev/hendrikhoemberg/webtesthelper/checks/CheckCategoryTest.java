package dev.hendrikhoemberg.webtesthelper.checks;

import dev.hendrikhoemberg.webtesthelper.model.CheckCategory;
import dev.hendrikhoemberg.webtesthelper.model.CheckType;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CheckCategoryTest {

    private final CheckRegistry registry = CheckRegistry.standard();

    @Test
    void everyRegisteredCheckMapsToANonNullCategory() {
        for (CheckDescriptor check : registry.all()) {
            assertThat(registry.category(check.type())).isNotNull();
        }
    }

    @Test
    void categoryAssignmentFollowsTheSpec() {
        assertThat(registry.category(CheckType.COOKIE_BANNER)).isEqualTo(CheckCategory.RECHT);
        assertThat(registry.category(CheckType.CONTACT_FORM)).isEqualTo(CheckCategory.RECHT);
        assertThat(registry.category(CheckType.TLS_CERT)).isEqualTo(CheckCategory.TECHNIK);
        assertThat(registry.category(CheckType.CONSOLE_ERRORS)).isEqualTo(CheckCategory.TECHNIK);
        assertThat(registry.category(CheckType.DEAD_LINK)).isEqualTo(CheckCategory.INHALT);
        assertThat(registry.category(CheckType.PAGE_STATUS)).isEqualTo(CheckCategory.INHALT);
    }

    @Test
    void categoriesComeBackInRenderOrder() {
        assertThat(registry.categories()).containsExactly(
                CheckCategory.INHALT, CheckCategory.TECHNIK, CheckCategory.RECHT);
    }
}
