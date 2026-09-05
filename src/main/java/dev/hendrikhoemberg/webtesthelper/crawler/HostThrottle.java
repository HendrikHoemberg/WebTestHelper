package dev.hendrikhoemberg.webtesthelper.crawler;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Politeness, enforced where it belongs (spec 8): a minimum gap between navigations to the
 * same host, independent of how many workers are crawling. Reserving the slot before sleeping
 * means N waiting workers queue up N intervals instead of all waking at once.
 *
 * <p>An interrupted wait aborts with {@link CrawlCancelledException} (interrupt status restored)
 * so a cancelled run never proceeds to navigation on a thread that was asked to stop.
 */
@Component
public class HostThrottle {

    private final Cache<String, AtomicLong> nextAllowedAt = Caffeine.newBuilder()
            .maximumSize(10_000)
            .expireAfterAccess(Duration.ofHours(1))
            .build();

    public void await(String host, Duration minInterval) {
        if (minInterval == null || minInterval.isZero() || minInterval.isNegative()) {
            return;
        }
        AtomicLong slot = nextAllowedAt.get(host, ignored -> new AtomicLong(0L));
        long waitUntil;
        synchronized (slot) {
            long now = System.currentTimeMillis();
            waitUntil = Math.max(slot.get(), now);
            slot.set(waitUntil + minInterval.toMillis());
        }
        long sleepFor = waitUntil - System.currentTimeMillis();
        if (sleepFor > 0) {
            try {
                Thread.sleep(sleepFor);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new CrawlCancelledException("Politeness-Wartezeit auf " + host + " wurde unterbrochen");
            }
        }
    }
}