package dev.hendrikhoemberg.webtesthelper.catalog;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class JourneyHealthTest {

    @Test
    void needsRerecordingDerivedCorrectly() {
        Instant now = Instant.now();

        // 0 failures, 0 drift -> false
        assertThat(new JourneyHealth(now, 0, 0).needsRerecording()).isFalse();

        // 3 failures, 0 drift -> false (site broken, not stale recording)
        assertThat(new JourneyHealth(now, 3, 0).needsRerecording()).isFalse();
        assertThat(new JourneyHealth(now, 5, 0).needsRerecording()).isFalse();

        // 0 failures, 1 drift -> false (drift alone means one selector moved, but test passed)
        assertThat(new JourneyHealth(now, 0, 1).needsRerecording()).isFalse();

        // 2 failures, 1 drift -> false (not yet reached 3 consecutive failures)
        assertThat(new JourneyHealth(now, 2, 1).needsRerecording()).isFalse();

        // 3 failures, 1 drift -> true (repeated failure + drift = stale recording)
        assertThat(new JourneyHealth(now, 3, 1).needsRerecording()).isTrue();

        // 4 failures, 2 drift -> true
        assertThat(new JourneyHealth(now, 4, 2).needsRerecording()).isTrue();
    }
}
