package dev.hendrikhoemberg.webtesthelper.model;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class RunCoverageTest {

    @Test
    void unifiesCrawledAndSnapshotUrlsAsLocationKeys() {
        RunCoverage coverage = RunCoverage.of(RunScope.FULL,
                List.of("PAGE_STATUS"),
                List.of("https://example.com/kontakt.html"),
                List.of("https://example.com/kontakt"),
                false);
        assertThat(coverage.locationKeys()).containsExactlyInAnyOrder("/kontakt.html", "/kontakt");
    }

    @Test
    void keepsTheQueryOnALocationKey() {
        RunCoverage coverage = RunCoverage.of(RunScope.FULL,
                List.of(), List.of("https://example.com/suche?q=x"), List.of(), false);
        assertThat(coverage.locationKeys()).containsExactly("/suche?q=x");
    }

    @Test
    void aBudgetCappedFullCrawlDoesNotCoverTheWholeSite() {
        RunCoverage coverage = RunCoverage.of(RunScope.FULL,
                List.of(), List.of("/a"), List.of(), true);
        assertThat(coverage.wholeSite()).isFalse();
    }

    @Test
    void aCompletedFullCrawlCoversTheWholeSite() {
        RunCoverage coverage = RunCoverage.of(RunScope.FULL,
                List.of(), List.of("https://example.com/a"), List.of(), false);
        assertThat(coverage.wholeSite()).isTrue();
    }

    @Test
    void aPulseRunNeverCoversTheWholeSiteEvenWhenItsFrontierDrains() {
        // A pulse frontier is the pinned key-page set and nothing else, so it always runs dry and
        // always reports partialCoverage=false. Reading that as whole-site coverage would let a
        // dozen pages resolve a finding the full crawl saw on six hundred (spec 9, spec 6.4).
        RunCoverage coverage = RunCoverage.of(RunScope.PULSE,
                List.of(), List.of("https://example.com/a"), List.of(), false);
        assertThat(coverage.wholeSite()).isFalse();
    }

    @Test
    void dropsUnparseableUrlsRatherThanThrowing() {
        RunCoverage coverage = RunCoverage.of(RunScope.FULL,
                List.of(), List.of("mailto:hallo@example.com"), List.of(), false);
        assertThat(coverage.locationKeys()).isEmpty();
    }

    @Test
    void ignoresUnknownCheckTypeNames() {
        RunCoverage coverage = RunCoverage.of(RunScope.FULL,
                List.of("PAGE_STATUS", "NOT_A_REAL_CHECK"), List.of(), List.of(), false);
        assertThat(coverage.checkTypes()).containsExactly(CheckType.PAGE_STATUS);
    }

    @Test
    void doesNotAliasTheSetsItWasConstructedWith() {
        // Coverage decides what a run may resolve (spec 6.4). A caller that keeps hold of the
        // collections it passed must not be able to widen that scope afterwards.
        java.util.Set<CheckType> types = new java.util.LinkedHashSet<>(java.util.Set.of(CheckType.PAGE_STATUS));
        java.util.Set<String> keys = new java.util.LinkedHashSet<>(java.util.Set.of("/a"));

        RunCoverage coverage = new RunCoverage(types, keys, true);
        types.add(CheckType.DEAD_LINK);
        keys.add("/b");

        assertThat(coverage.checkTypes()).containsExactly(CheckType.PAGE_STATUS);
        assertThat(coverage.locationKeys()).containsExactly("/a");
    }

    @Test
    void normalisesInteractionUrlsAndDropsUnparseableOnes() {
        RunCoverage coverage = RunCoverage.of(RunScope.FULL,
                List.of(), List.of(), List.of(), false,
                List.of("DEAD_LINK"),
                List.of("https://example.com/kontakt.html", "mailto:hallo@example.com", "https://example.com/a?q=1"));
        assertThat(coverage.interactionLocationKeys())
                .containsExactlyInAnyOrder("/kontakt.html", "/a?q=1");
        assertThat(coverage.interactionCheckTypes()).containsExactly(CheckType.DEAD_LINK);
    }

    @Test
    void ignoresUnknownInteractionCheckTypes() {
        RunCoverage coverage = RunCoverage.of(RunScope.FULL,
                List.of(), List.of(), List.of(), false,
                List.of("DEAD_LINK", "UNKNOWN_CHECK", "INVALID"),
                List.of());
        assertThat(coverage.interactionCheckTypes()).containsExactly(CheckType.DEAD_LINK);
    }

    @Test
    void wholeSiteIsUnaffectedByInteractionSets() {
        RunCoverage fullWithInteractions = RunCoverage.of(RunScope.FULL,
                List.of(), List.of("https://example.com/a"), List.of(), false,
                List.of("DEAD_LINK"), List.of("https://example.com/a"));
        assertThat(fullWithInteractions.wholeSite()).isTrue();

        RunCoverage partialWithInteractions = RunCoverage.of(RunScope.FULL,
                List.of(), List.of("https://example.com/a"), List.of(), true,
                List.of("DEAD_LINK"), List.of("https://example.com/a"));
        assertThat(partialWithInteractions.wholeSite()).isFalse();

        RunCoverage pulseWithInteractions = RunCoverage.of(RunScope.PULSE,
                List.of(), List.of("https://example.com/a"), List.of(), false,
                List.of("DEAD_LINK"), List.of("https://example.com/a"));
        assertThat(pulseWithInteractions.wholeSite()).isFalse();
    }

    @Test
    void doesNotAliasInteractionSets() {
        Set<CheckType> interactionTypes = new java.util.LinkedHashSet<>(Set.of(CheckType.PAGE_STATUS));
        Set<String> interactionKeys = new java.util.LinkedHashSet<>(Set.of("/a"));

        RunCoverage coverage = new RunCoverage(Set.of(), Set.of(), true, interactionTypes, interactionKeys);
        interactionTypes.add(CheckType.DEAD_LINK);
        interactionKeys.add("/b");

        assertThat(coverage.interactionCheckTypes()).containsExactly(CheckType.PAGE_STATUS);
        assertThat(coverage.interactionLocationKeys()).containsExactly("/a");
    }
}
