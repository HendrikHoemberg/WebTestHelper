package dev.hendrikhoemberg.webtesthelper.crawler;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class HostThrottleTest {

    @Test
    void requestsToOneHostAreSpacedOut() {
        HostThrottle throttle = new HostThrottle();
        long start = System.nanoTime();
        for (int i = 0; i < 3; i++) {
            throttle.await("example.com", Duration.ofMillis(120));
        }
        assertThat(Duration.ofNanos(System.nanoTime() - start))
                .isGreaterThanOrEqualTo(Duration.ofMillis(240));
    }

    @Test
    void differentHostsDoNotWaitForEachOther() {
        HostThrottle throttle = new HostThrottle();
        throttle.await("a.example", Duration.ofMillis(500));
        long start = System.nanoTime();
        throttle.await("b.example", Duration.ofMillis(500));
        assertThat(Duration.ofNanos(System.nanoTime() - start))
                .isLessThan(Duration.ofMillis(200));
    }

    @Test
    void aZeroDelayDoesNotSleep() {
        HostThrottle throttle = new HostThrottle();
        long start = System.nanoTime();
        for (int i = 0; i < 50; i++) {
            throttle.await("example.com", Duration.ZERO);
        }
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofMillis(100));
    }
}