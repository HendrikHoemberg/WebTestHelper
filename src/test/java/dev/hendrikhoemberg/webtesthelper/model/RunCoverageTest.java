package dev.hendrikhoemberg.webtesthelper.model;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class RunCoverageTest {

    @Test
    void unifiesCrawledAndSnapshotUrlsAsLocationKeys() {
        RunCoverage coverage = RunCoverage.of(
                List.of("PAGE_STATUS"),
                List.of("https://example.com/kontakt.html"),
                List.of("https://example.com/kontakt"),
                false);
        assertThat(coverage.locationKeys()).containsExactlyInAnyOrder("/kontakt.html", "/kontakt");
    }

    @Test
    void keepsTheQueryOnALocationKey() {
        RunCoverage coverage = RunCoverage.of(
                List.of(), List.of("https://example.com/suche?q=x"), List.of(), false);
        assertThat(coverage.locationKeys()).containsExactly("/suche?q=x");
    }

    @Test
    void partialCoverageIsIncomplete() {
        RunCoverage coverage = RunCoverage.of(
                List.of(), List.of("/a"), List.of(), true);
        assertThat(coverage.complete()).isFalse();
    }

    @Test
    void dropsUnparseableUrlsRatherThanThrowing() {
        RunCoverage coverage = RunCoverage.of(
                List.of(), List.of("mailto:hallo@example.com"), List.of(), false);
        assertThat(coverage.locationKeys()).isEmpty();
    }

    @Test
    void ignoresUnknownCheckTypeNames() {
        RunCoverage coverage = RunCoverage.of(
                List.of("PAGE_STATUS", "NOT_A_REAL_CHECK"), List.of(), List.of(), false);
        assertThat(coverage.checkTypes()).containsExactly(CheckType.PAGE_STATUS);
    }
}
