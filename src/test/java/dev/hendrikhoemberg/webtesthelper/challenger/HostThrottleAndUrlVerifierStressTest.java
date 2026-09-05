package dev.hendrikhoemberg.webtesthelper.challenger;

import com.github.benmanes.caffeine.cache.Cache;
import dev.hendrikhoemberg.webtesthelper.crawler.CrawlerProperties;
import dev.hendrikhoemberg.webtesthelper.crawler.HostThrottle;
import dev.hendrikhoemberg.webtesthelper.crawler.UrlVerifier;
import dev.hendrikhoemberg.webtesthelper.crawler.VerifierProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Empirical stress test harness challenging Caffeine cache boundedness, concurrency safety,
 * race conditions, and memory leak absence in HostThrottle and UrlVerifier (C4 / C6).
 */
class HostThrottleAndUrlVerifierStressTest {

    @SuppressWarnings("unchecked")
    private Cache<String, AtomicLong> getThrottleCache(HostThrottle throttle) throws Exception {
        Field f = HostThrottle.class.getDeclaredField("nextAllowedAt");
        f.setAccessible(true);
        return (Cache<String, AtomicLong>) f.get(throttle);
    }

    @SuppressWarnings("unchecked")
    private Cache<String, Semaphore> getVerifierCache(UrlVerifier verifier) throws Exception {
        Field f = UrlVerifier.class.getDeclaredField("permits");
        f.setAccessible(true);
        return (Cache<String, Semaphore>) f.get(verifier);
    }

    @Test
    @DisplayName("HostThrottle Caffeine cache strictly bounds entries to 10,000 under massive concurrent insertion")
    void hostThrottle_cacheBoundedAt10000_underHighConcurrency() throws Exception {
        HostThrottle throttle = new HostThrottle();
        Cache<String, AtomicLong> cache = getThrottleCache(throttle);

        int threadCount = 20;
        int hostsPerThread = 1500; // 30,000 distinct hosts total (3x the 10,000 capacity)
        CountDownLatch startLatch = new CountDownLatch(1);
        AtomicInteger successCount = new AtomicInteger(0);
        List<Throwable> errors = new ArrayList<>();

        try (ExecutorService executor = Executors.newFixedThreadPool(threadCount)) {
            List<Future<?>> futures = new ArrayList<>();
            for (int t = 0; t < threadCount; t++) {
                final int threadId = t;
                futures.add(executor.submit(() -> {
                    try {
                        startLatch.await();
                        for (int i = 0; i < hostsPerThread; i++) {
                            String host = "host-" + threadId + "-" + i + ".example.com";
                            throttle.await(host, Duration.ofNanos(1));
                            successCount.incrementAndGet();
                        }
                    } catch (Throwable e) {
                        synchronized (errors) {
                            errors.add(e);
                        }
                    }
                }));
            }
            startLatch.countDown();
            for (Future<?> f : futures) {
                f.get(30, TimeUnit.SECONDS);
            }
        }

        assertThat(errors).isEmpty();
        assertThat(successCount.get()).isEqualTo(threadCount * hostsPerThread);

        // Force Caffeine background maintenance
        cache.cleanUp();

        long estimatedSize = cache.estimatedSize();
        assertThat(estimatedSize)
                .as("Caffeine cache in HostThrottle must enforce maximumSize(10,000)")
                .isLessThanOrEqualTo(10_000);
    }

    @Test
    @DisplayName("HostThrottle serializes concurrent delays on identical host without race conditions or lost updates")
    void hostThrottle_serializesConcurrentDelaysOnSameHost() throws Exception {
        HostThrottle throttle = new HostThrottle();
        Cache<String, AtomicLong> cache = getThrottleCache(throttle);

        String host = "contended-target.org";
        int callers = 15;
        Duration perCallDelay = Duration.ofMillis(10);
        CountDownLatch readyLatch = new CountDownLatch(callers);
        CountDownLatch goLatch = new CountDownLatch(1);
        AtomicLong minStart = new AtomicLong(Long.MAX_VALUE);

        try (ExecutorService executor = Executors.newFixedThreadPool(callers)) {
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < callers; i++) {
                futures.add(executor.submit(() -> {
                    readyLatch.countDown();
                    try {
                        goLatch.await();
                        minStart.accumulateAndGet(System.currentTimeMillis(), Math::min);
                        throttle.await(host, perCallDelay);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }));
            }
            readyLatch.await(5, TimeUnit.SECONDS);
            goLatch.countDown();
            for (Future<?> f : futures) {
                f.get(10, TimeUnit.SECONDS);
            }
        }

        AtomicLong slot = cache.getIfPresent(host);
        assertThat(slot).isNotNull();

        // All 15 callers added 10ms serialized delay: final slot must be >= minStart + 150ms
        long finalSlotTime = slot.get();
        long expectedMinSlot = minStart.get() + (callers * perCallDelay.toMillis());
        assertThat(finalSlotTime)
                .as("All sequential slot increments on the same host must accumulate without lost updates")
                .isGreaterThanOrEqualTo(expectedMinSlot);
    }

    @Test
    @DisplayName("HostThrottle survives continuous key churn of 50,000 keys without unbounded memory growth")
    void hostThrottle_memoryBoundsUnderChurn() throws Exception {
        HostThrottle throttle = new HostThrottle();
        Cache<String, AtomicLong> cache = getThrottleCache(throttle);

        for (int i = 0; i < 50_000; i++) {
            throttle.await("churn-host-" + (i % 25_000) + ".com", Duration.ZERO);
        }
        cache.cleanUp();

        assertThat(cache.estimatedSize())
                .as("Cache size must not exceed 10,000 after 50,000 operations")
                .isLessThanOrEqualTo(10_000);
    }

    @Test
    @DisplayName("UrlVerifier Caffeine permits cache strictly bounds entries to 10,000 under concurrent permit acquisition")
    void urlVerifier_permitsCacheBoundedAt10000_underHighConcurrency() throws Exception {
        VerifierProperties vProps = new VerifierProperties(2, Duration.ofSeconds(5),
                Duration.ofHours(1), Duration.ofMinutes(5), 2, Duration.ofSeconds(1));
        CrawlerProperties cProps = new CrawlerProperties(4, 20, Duration.ofSeconds(10),
                Duration.ofMillis(250), Path.of("target"), true, false);
        HostThrottle throttle = new HostThrottle();
        UrlVerifier verifier = new UrlVerifier(vProps, throttle, cProps);

        Cache<String, Semaphore> permits = getVerifierCache(verifier);

        int threads = 20;
        int hostsPerThread = 1500; // 30,000 distinct hosts
        CountDownLatch startLatch = new CountDownLatch(1);
        AtomicInteger successCount = new AtomicInteger();

        try (ExecutorService executor = Executors.newFixedThreadPool(threads)) {
            List<Future<?>> futures = new ArrayList<>();
            for (int t = 0; t < threads; t++) {
                final int threadId = t;
                futures.add(executor.submit(() -> {
                    try {
                        startLatch.await();
                        for (int i = 0; i < hostsPerThread; i++) {
                            String host = "sub" + i + "-t" + threadId + ".verifier-test.com";
                            Semaphore sem = permits.get(host, ignored -> new Semaphore(vProps.perHostPermits()));
                            sem.acquire();
                            try {
                                successCount.incrementAndGet();
                            } finally {
                                sem.release();
                            }
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }));
            }
            startLatch.countDown();
            for (Future<?> f : futures) {
                f.get(30, TimeUnit.SECONDS);
            }
        }

        assertThat(successCount.get()).isEqualTo(threads * hostsPerThread);

        permits.cleanUp();
        assertThat(permits.estimatedSize())
                .as("UrlVerifier permits cache must stay bounded at <= 10,000")
                .isLessThanOrEqualTo(10_000);
    }

    @Test
    @DisplayName("UrlVerifier semaphore permits eviction while permit is held does not crash or corrupt subsequent requests")
    void urlVerifier_evictionWhilePermitHeld_safe() throws Exception {
        VerifierProperties vProps = new VerifierProperties(2, Duration.ofSeconds(5),
                Duration.ofHours(1), Duration.ofMinutes(5), 2, Duration.ofSeconds(1));
        CrawlerProperties cProps = new CrawlerProperties(4, 20, Duration.ofSeconds(10),
                Duration.ofMillis(250), Path.of("target"), true, false);
        HostThrottle throttle = new HostThrottle();
        UrlVerifier verifier = new UrlVerifier(vProps, throttle, cProps);
        Cache<String, Semaphore> permits = getVerifierCache(verifier);

        String host = "eviction-race.org";
        Semaphore sem1 = permits.get(host, ignored -> new Semaphore(vProps.perHostPermits()));
        sem1.acquire();

        // Invalidate entry while sem1 is acquired
        permits.invalidate(host);
        permits.cleanUp();

        // Another thread accesses the same host -> receives a fresh Semaphore
        Semaphore sem2 = permits.get(host, ignored -> new Semaphore(vProps.perHostPermits()));
        assertThat(sem2).isNotSameAs(sem1);

        sem2.acquire();
        sem2.release();

        // Original thread releases its permit without error
        sem1.release();

        assertThat(sem1.availablePermits()).isEqualTo(2);
        assertThat(sem2.availablePermits()).isEqualTo(2);
    }
}
