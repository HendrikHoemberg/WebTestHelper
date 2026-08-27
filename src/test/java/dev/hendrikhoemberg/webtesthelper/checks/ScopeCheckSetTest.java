package dev.hendrikhoemberg.webtesthelper.checks;

import dev.hendrikhoemberg.webtesthelper.model.CheckType;
import dev.hendrikhoemberg.webtesthelper.model.RunScope;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Spec 9's tier scopes, stated against the registry rather than against a hand-copied enum set:
 * pulse is "page checks only, no submits", full and deep are everything. Asserting it this way is
 * what makes the eleventh page check a build failure until somebody decides which tiers run it —
 * a hand-written {@code EnumSet} would silently keep the old answer.
 */
class ScopeCheckSetTest {

    private static final CheckRegistry REGISTRY = CheckRegistry.standard();

    @Test
    void pulseRunsEveryPageCheck() {
        // Spec 1 names unreachable PDF downloads, non-playing videos and misconfigured Maps embeds
        // among the seven things this product exists to catch. All three are page checks (spec 7.1),
        // and the crawl collects their evidence on every run whatever the tier — a pulse that
        // skipped them threw that evidence away for no saving.
        assertThat(RunScope.PULSE.checkTypes()).containsExactlyInAnyOrderElementsOf(typesOf(REGISTRY.pageChecks()));
    }

    @Test
    void pulseRunsNoSiteCheck() {
        // A site check reasons over the whole crawled set — hreflang reciprocity, sitemap
        // consistency. A dozen pinned pages cannot answer either question.
        assertThat(RunScope.PULSE.checkTypes()).doesNotContainAnyElementsOf(typesOf(REGISTRY.siteChecks()));
    }

    @Test
    void pulseRunsNoInteractionCheck() {
        // Spec 9's "page checks only": PULSE runs no interaction check.
        assertThat(RunScope.PULSE.checkTypes()).doesNotContainAnyElementsOf(typesOf(REGISTRY.interactionChecks()));
    }

    @Test
    void fullAndDeepRunEverything() {
        assertThat(RunScope.FULL.checkTypes()).containsExactlyInAnyOrderElementsOf(Set.of(CheckType.values()));
        assertThat(RunScope.DEEP.checkTypes()).containsExactlyInAnyOrderElementsOf(Set.of(CheckType.values()));
    }

    private static Set<CheckType> typesOf(java.util.List<? extends CheckDescriptor> checks) {
        return checks.stream().map(CheckDescriptor::type).collect(Collectors.toSet());
    }
}
