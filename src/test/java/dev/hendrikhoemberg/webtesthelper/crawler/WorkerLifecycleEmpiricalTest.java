package dev.hendrikhoemberg.webtesthelper.crawler;

import dev.hendrikhoemberg.webtesthelper.recorder.RecorderPool;
import dev.hendrikhoemberg.webtesthelper.recorder.RecorderProperties;
import dev.hendrikhoemberg.webtesthelper.recorder.RecorderWorker;
import dev.hendrikhoemberg.webtesthelper.recorder.RecordingSessionRegistry;
import jakarta.annotation.PreDestroy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.stereotype.Component;

import java.lang.reflect.Field;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Empirical adversarial stress tests for C1 (Lifecycle cleanup) and C2 (Worker shutdown timeouts).
 */
class WorkerLifecycleEmpiricalTest {

    @Test
    @DisplayName("C1: Presence of @Component and @PreDestroy on all three pool singletons")
    void verifyLifecycleAnnotations() throws NoSuchMethodException {
        // BrowserPool
        assertThat(BrowserPool.class.isAnnotationPresent(Component.class))
                .as("BrowserPool must be a Spring @Component").isTrue();
        assertThat(BrowserPool.class.getMethod("close").isAnnotationPresent(PreDestroy.class))
                .as("BrowserPool.close() must be annotated with @PreDestroy").isTrue();

        // RecorderPool
        assertThat(RecorderPool.class.isAnnotationPresent(Component.class))
                .as("RecorderPool must be a Spring @Component").isTrue();
        assertThat(RecorderPool.class.getMethod("close").isAnnotationPresent(PreDestroy.class))
                .as("RecorderPool.close() must be annotated with @PreDestroy").isTrue();

        // RecordingSessionRegistry
        assertThat(RecordingSessionRegistry.class.isAnnotationPresent(Component.class))
                .as("RecordingSessionRegistry must be a Spring @Component").isTrue();
        assertThat(RecordingSessionRegistry.class.getMethod("close").isAnnotationPresent(PreDestroy.class))
                .as("RecordingSessionRegistry.close() must be annotated with @PreDestroy").isTrue();
    }

    @Configuration
    static class SpringContextTestConfig {
        @Bean
        public CrawlerProperties crawlerProperties() {
            CrawlerProperties props = mock(CrawlerProperties.class);
            when(props.browserWorkers()).thenReturn(1);
            when(props.headless()).thenReturn(true);
            when(props.noSandbox()).thenReturn(false);
            when(props.artifactDir()).thenReturn(Path.of("/tmp"));
            return props;
        }

        @Bean
        public BrowserPool browserPool(CrawlerProperties properties) {
            return new BrowserPool(properties);
        }

        @Bean
        public RecorderProperties recorderProperties() {
            RecorderProperties props = mock(RecorderProperties.class);
            when(props.maxSessions()).thenReturn(1);
            when(props.headless()).thenReturn(true);
            when(props.noSandbox()).thenReturn(false);
            when(props.idleTimeout()).thenReturn(Duration.ofMinutes(5));
            return props;
        }

        @Bean
        public RecorderPool recorderPool(RecorderProperties properties) {
            return new RecorderPool(properties);
        }

        @Bean
        public RecordingSessionRegistry sessionRegistry(RecorderPool pool, RecorderProperties properties) {
            return new RecordingSessionRegistry(pool, properties, Clock.systemUTC());
        }
    }

    @Test
    @DisplayName("C1: Spring ApplicationContext shutdown invokes @PreDestroy and closes pools cleanly")
    void springContextShutdownInvokesPreDestroy() {
        AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
        context.register(SpringContextTestConfig.class);
        context.refresh();

        BrowserPool browserPool = context.getBean(BrowserPool.class);
        RecorderPool recorderPool = context.getBean(RecorderPool.class);
        RecordingSessionRegistry registry = context.getBean(RecordingSessionRegistry.class);

        assertThat(browserPool).isNotNull();
        assertThat(recorderPool).isNotNull();
        assertThat(registry).isNotNull();

        // Close context — must invoke @PreDestroy methods without error
        context.close();

        // Verify state after close
        assertThat(browserPool.size()).isEqualTo(0);
        assertThat(recorderPool.allocate()).isEmpty();
        assertThat(registry.activeSessions()).isEqualTo(0);
    }

    @Test
    @DisplayName("C2: BrowserPool.Worker close times out cleanly within bounded time on stuck task")
    void browserWorkerCloseTimesOutCleanly() throws Exception {
        BrowserPool.Worker worker = new BrowserPool.Worker(0, true, false);
        ExecutorService taskRunner = Executors.newSingleThreadExecutor();
        CountDownLatch taskRunning = new CountDownLatch(1);
        CountDownLatch taskInterrupted = new CountDownLatch(1);

        taskRunner.submit(() -> {
            try {
                worker.call(browser -> {
                    taskRunning.countDown();
                    try {
                        Thread.sleep(10_000);
                    } catch (InterruptedException e) {
                        taskInterrupted.countDown();
                        Thread.currentThread().interrupt();
                    }
                    return null;
                });
            } catch (Exception ignored) {
            }
        });

        assertThat(taskRunning.await(5, TimeUnit.SECONDS)).isTrue();

        long start = System.currentTimeMillis();
        worker.close(200, TimeUnit.MILLISECONDS);
        long elapsed = System.currentTimeMillis() - start;

        assertThat(elapsed)
                .as("Worker close must complete near timeout (200ms) and not block for 10s")
                .isGreaterThanOrEqualTo(180)
                .isLessThan(2000);

        // Verify task received interrupt via shutdownNow
        assertThat(taskInterrupted.await(2, TimeUnit.SECONDS))
                .as("Worker thread must receive interrupt within 2s after shutdownNow")
                .isTrue();

        taskRunner.shutdownNow();
    }

    @Test
    @DisplayName("C2: BrowserPool.Worker close times out cleanly even if task ignores interrupts (CPU loop)")
    void browserWorkerCloseTimesOutEvenIfTaskIgnoresInterrupt() throws Exception {
        BrowserPool.Worker worker = new BrowserPool.Worker(0, true, false);
        ExecutorService taskRunner = Executors.newSingleThreadExecutor();
        CountDownLatch taskRunning = new CountDownLatch(1);
        AtomicBoolean stopSpinning = new AtomicBoolean(false);

        taskRunner.submit(() -> {
            try {
                worker.call(browser -> {
                    taskRunning.countDown();
                    // Uninterruptible CPU spin
                    while (!stopSpinning.get()) {
                        // intentionally do not check Thread.interrupted()
                    }
                    return null;
                });
            } catch (Exception ignored) {
            }
        });

        assertThat(taskRunning.await(5, TimeUnit.SECONDS)).isTrue();

        long start = System.currentTimeMillis();
        worker.close(200, TimeUnit.MILLISECONDS);
        long elapsed = System.currentTimeMillis() - start;

        assertThat(elapsed)
                .as("Worker close must return after timeout even if task ignores interrupt")
                .isGreaterThanOrEqualTo(180)
                .isLessThan(2000);

        // Free CPU loop
        stopSpinning.set(true);
        taskRunner.shutdownNow();
    }

    @Test
    @DisplayName("C2: BrowserPool.Worker threads are daemon threads to avoid preventing JVM exit")
    void workerThreadsAreDaemons() throws Exception {
        BrowserPool.Worker worker = new BrowserPool.Worker(1, true, false);
        Field threadField = BrowserPool.Worker.class.getDeclaredField("thread");
        threadField.setAccessible(true);
        ExecutorService executor = (ExecutorService) threadField.get(worker);

        CountDownLatch latch = new CountDownLatch(1);
        AtomicBoolean isDaemon = new AtomicBoolean(false);

        executor.submit(() -> {
            isDaemon.set(Thread.currentThread().isDaemon());
            latch.countDown();
        });

        assertThat(latch.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(isDaemon.get())
                .as("Browser worker thread must be daemon thread")
                .isTrue();

        worker.close(100, TimeUnit.MILLISECONDS);
    }

    @Test
    @DisplayName("C2: BrowserPool close bounded across multiple hung workers")
    void browserPoolMultiWorkerCloseBounded() throws Exception {
        CrawlerProperties props = mock(CrawlerProperties.class);
        when(props.browserWorkers()).thenReturn(2);
        when(props.headless()).thenReturn(true);
        when(props.noSandbox()).thenReturn(false);

        BrowserPool pool = new BrowserPool(props);
        ExecutorService runners = Executors.newFixedThreadPool(2);
        CountDownLatch tasksRunning = new CountDownLatch(2);

        for (int i = 0; i < 2; i++) {
            runners.submit(() -> {
                try {
                    pool.submit(browser -> {
                        tasksRunning.countDown();
                        Thread.sleep(10_000);
                        return null;
                    });
                } catch (Exception ignored) {
                }
            });
        }

        assertThat(tasksRunning.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(pool.busy()).isEqualTo(2);

        long start = System.currentTimeMillis();
        pool.close(200, TimeUnit.MILLISECONDS);
        long elapsed = System.currentTimeMillis() - start;

        assertThat(elapsed)
                .as("Pool close of 2 stuck workers with 200ms timeout must finish in ~400ms and < 2000ms")
                .isGreaterThanOrEqualTo(350)
                .isLessThan(2000);

        assertThat(pool.size()).isEqualTo(0);
        runners.shutdownNow();
    }

    @Test
    @DisplayName("C2: Non-stuck worker closes quickly without waiting for full timeout")
    void nonStuckWorkerClosesImmediately() {
        BrowserPool.Worker worker = new BrowserPool.Worker(0, true, false);

        long start = System.currentTimeMillis();
        // Request 5-second timeout, but since nothing is running, should close immediately
        worker.close(5, TimeUnit.SECONDS);
        long elapsed = System.currentTimeMillis() - start;

        assertThat(elapsed)
                .as("Idle worker close should complete almost immediately, well below 5s")
                .isLessThan(500);
    }

    @Test
    @DisplayName("C2: Double close on BrowserPool and RecorderPool is safe")
    void doubleCloseOnPools() {
        CrawlerProperties crawlerProps = mock(CrawlerProperties.class);
        when(crawlerProps.browserWorkers()).thenReturn(1);
        when(crawlerProps.headless()).thenReturn(true);
        when(crawlerProps.noSandbox()).thenReturn(false);

        BrowserPool browserPool = new BrowserPool(crawlerProps);
        browserPool.close(100, TimeUnit.MILLISECONDS);
        // Second close on pool — workers list is already cleared
        browserPool.close(100, TimeUnit.MILLISECONDS);
        assertThat(browserPool.size()).isEqualTo(0);

        RecorderProperties recorderProps = mock(RecorderProperties.class);
        when(recorderProps.maxSessions()).thenReturn(1);
        when(recorderProps.headless()).thenReturn(true);
        when(recorderProps.noSandbox()).thenReturn(false);

        RecorderPool recorderPool = new RecorderPool(recorderProps);
        recorderPool.close();
        // Second close on recorder pool — guarded by `if (closed) return;`
        recorderPool.close();
        assertThat(recorderPool.allocate()).isEmpty();
    }
}
