package dev.hendrikhoemberg.webtesthelper.crawler;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class HostThrottleTest {

    @Test
    void requestsToOneHostAreSpacedOut() {
        HostThrottle throttle = new HostThrottle();
        long start = System.nanoTime();
        for (int i = 0; i < 3; i++) {
            throttle.await("example.com", Duration.ofMillis(120));
        }
        // Two intervals of 120ms, minus a millisecond of slack: HostThrottle schedules on
        // System.currentTimeMillis() while this measures with nanoTime(), so the truncation
        // can land the last wake-up a fraction under the theoretical 240ms. The margin is far
        // too small to pass if the throttle stopped spacing requests at all.
        assertThat(Duration.ofNanos(System.nanoTime() - start))
                .isGreaterThanOrEqualTo(Duration.ofMillis(235));
    }

    @Test
    void differentHostsDoNotWaitForEachOther() {
        HostThrottle throttle = new HostThrottle();
        throttle.await("a.example", Duration.ofMillis(1000));
        long start = System.nanoTime();
        throttle.await("b.example", Duration.ofMillis(1000));
        assertThat(Duration.ofNanos(System.nanoTime() - start))
                .isLessThan(Duration.ofMillis(450));
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

    @Test
    void anInterruptedWaitAbortsInsteadOfContinuingToNavigate() throws Exception {
        HostThrottle throttle = new HostThrottle();
        throttle.await("slow.example", Duration.ofMillis(400));

        AtomicReference<Throwable> thrown = new AtomicReference<>();
        Thread victim = new Thread(() -> {
            try {
                throttle.await("slow.example", Duration.ofMillis(400));
            } catch (Throwable t) {
                thrown.set(t);
            }
        });
        victim.start();

        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (victim.getState() != Thread.State.TIMED_WAITING && System.nanoTime() < deadline) {
            Thread.sleep(1);
        }
        victim.interrupt();
        victim.join(2000);

        assertThat(thrown.get()).isInstanceOf(CrawlCancelledException.class);
        assertThat(victim.isAlive()).isFalse();
    }
}