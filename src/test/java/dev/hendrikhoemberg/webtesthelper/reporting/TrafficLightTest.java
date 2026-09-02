package dev.hendrikhoemberg.webtesthelper.reporting;

import dev.hendrikhoemberg.webtesthelper.findings.OpenFindingCounts;
import dev.hendrikhoemberg.webtesthelper.model.RunStatus;
import dev.hendrikhoemberg.webtesthelper.runner.LastRun;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.time.Instant;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The traffic light, tested as a pure function: one table over the four rows plus the three
 * precedence collisions that actually decide the rule, and the D62 case proving the light never
 * re-decides what the findings query already filtered.
 */
class TrafficLightTest {

    static Stream<Arguments> precedenceTable() {
        return Stream.of(
                Arguments.of("a disabled site is grey",
                        false, null, new OpenFindingCounts(0, 0, 0, 0), TrafficLight.GRAU),
                Arguments.of("a failed last run is red",
                        true, run(RunStatus.FAILED, false), OpenFindingCounts.none(), TrafficLight.ROT),
                Arguments.of("an open error is red",
                        true, run(RunStatus.COMPLETED, false), new OpenFindingCounts(1, 0, 0, 0), TrafficLight.ROT),
                Arguments.of("an open warning is yellow",
                        true, run(RunStatus.COMPLETED, false), new OpenFindingCounts(0, 1, 0, 0), TrafficLight.GELB),
                Arguments.of("a partial run is yellow",
                        true, run(RunStatus.COMPLETED, true), OpenFindingCounts.none(), TrafficLight.GELB),
                Arguments.of("a site that never finished a run is neutral new",
                        true, null, OpenFindingCounts.none(), TrafficLight.NEU),
                Arguments.of("a site with only info findings is green",
                        true, run(RunStatus.COMPLETED, false), new OpenFindingCounts(0, 0, 1, 0), TrafficLight.GRUEN),
                Arguments.of("a disabled site with five errors is still grey, not red",
                        false, null, new OpenFindingCounts(5, 0, 0, 0), TrafficLight.GRAU),
                Arguments.of("a failed run with no findings is red, not yellow",
                        true, run(RunStatus.FAILED, false), OpenFindingCounts.none(), TrafficLight.ROT),
                Arguments.of("a partial run with one error is red, not yellow",
                        true, run(RunStatus.COMPLETED, true), new OpenFindingCounts(1, 0, 0, 0), TrafficLight.ROT));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("precedenceTable")
    void appliesThePrecedenceRule(String name, boolean enabled, LastRun lastRun,
                                  OpenFindingCounts counts, TrafficLight expected) {
        assertThat(TrafficLight.of(enabled, lastRun, counts)).isEqualTo(expected);
    }

    @Test
    void aSilencedErrorExcludedUpstreamRendersGreen() {
        assertThat(TrafficLight.of(true, run(RunStatus.COMPLETED, false), new OpenFindingCounts(0, 0, 0, 0)))
                .isEqualTo(TrafficLight.GRUEN);
    }

    @Test
    void siteWithOnlyAcknowledgedBaselineErrorsIsGreen() {
        OpenFindingCounts counts = new OpenFindingCounts(5, 0, 0, 0, 0, 0, 5);
        assertThat(TrafficLight.of(true, run(RunStatus.COMPLETED, false), counts))
                .isEqualTo(TrafficLight.GRUEN);
    }

    @Test
    void siteWithUntriagedErrorIsRed() {
        OpenFindingCounts counts = new OpenFindingCounts(5, 0, 0, 1, 1, 0, 4);
        assertThat(TrafficLight.of(true, run(RunStatus.COMPLETED, false), counts))
                .isEqualTo(TrafficLight.ROT);
    }

    private static LastRun run(RunStatus status, boolean partialCoverage) {
        return new LastRun(1L, 1L, status, Instant.parse("2026-08-26T10:00:00Z"), partialCoverage);
    }
}
