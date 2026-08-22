package dev.hendrikhoemberg.webtesthelper.crawler;

import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Politeness, enforced where it belongs (spec 8): a minimum gap between navigations to the
 * same host, independent of how many workers are crawling. Reserving the slot before sleeping
 * means N waiting workers queue up N intervals instead of all waking at once.
 */
@Component
public class HostThrottle {

    private final Map<String, AtomicLong> nextAllowedAt = new ConcurrentHashMap<>();

    public void await(String host, Duration minInterval) {
        if (minInterval == null || minInterval.isZero() || minInterval.isNegative()) {
            return;
        }
        AtomicLong slot = nextAllowedAt.computeIfAbsent(host, ignored -> new AtomicLong(0L));
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
            }
        }
    }
}