package dev.hendrikhoemberg.webtesthelper.challenger;

import dev.hendrikhoemberg.webtesthelper.crawler.BrowserPool;
import dev.hendrikhoemberg.webtesthelper.crawler.BrowserPool.BrowserTask;
import dev.hendrikhoemberg.webtesthelper.crawler.CrawlCancelledException;
import dev.hendrikhoemberg.webtesthelper.crawler.CrawlRequest;
import dev.hendrikhoemberg.webtesthelper.crawler.CrawlService;
import dev.hendrikhoemberg.webtesthelper.crawler.CrawlTarget;
import dev.hendrikhoemberg.webtesthelper.crawler.CrawlerProperties;
import dev.hendrikhoemberg.webtesthelper.crawler.HostThrottle;
import dev.hendrikhoemberg.webtesthelper.crawler.PageNavigator;
import dev.hendrikhoemberg.webtesthelper.crawler.SiteResourceFetcher;
import dev.hendrikhoemberg.webtesthelper.crawler.persistence.CrawlFrontierJdbcRepository;
import dev.hendrikhoemberg.webtesthelper.model.CrawlBudget;
import dev.hendrikhoemberg.webtesthelper.model.NormalizedUrl;
import dev.hendrikhoemberg.webtesthelper.model.PageSnapshot;
import dev.hendrikhoemberg.webtesthelper.model.RunScope;
import dev.hendrikhoemberg.webtesthelper.model.SiteContext;
import dev.hendrikhoemberg.webtesthelper.model.UrlNormalizer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Empirical stress test harness challenging cooperative cancellation in CrawlService (E3).
 * Verifies that setting cancellation terminates batches immediately without executing
 * remaining items in the batch.
 */
class CrawlServiceCancellationStressTest {

    @TempDir
    Path tempDir;

    private SiteContext testSite() {
        NormalizedUrl base = UrlNormalizer.normalize("https://example.com/").orElseThrow();
        return new SiteContext(1L, "Test Site", base,
                new CrawlBudget(100, 5, Duration.ofMinutes(10)),
                List.of(), List.of(), List.of(), false, "ua", Map.of());
    }

    private List<CrawlTarget> createBatch(int count) {
        List<CrawlTarget> targets = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            targets.add(new CrawlTarget((long) i, "https://example.com/page-" + i, 1));
        }
        return targets;
    }

    private PageSnapshot dummySnapshot(String url) {
        NormalizedUrl norm = UrlNormalizer.normalize(url).orElseThrow();
        return new PageSnapshot(norm, norm.value(), 0, true, null, 200, Map.of(),
                List.of(norm.value()), 0L, "test text", "", "", 0L,
                List.of(), List.of(), List.of(), List.of(),
                List.of(), List.of(), List.of(), List.of(), List.of(), null);
    }

    @Test
    @DisplayName("Pre-cancelled request aborts immediately before claiming any frontier batch")
    void crawl_preCancelled_abortsImmediately() {
        CrawlFrontierJdbcRepository frontier = mock(CrawlFrontierJdbcRepository.class);
        BrowserPool pool = mock(BrowserPool.class);
        PageNavigator navigator = mock(PageNavigator.class);
        SiteResourceFetcher fetcher = mock(SiteResourceFetcher.class);
        HostThrottle throttle = new HostThrottle();

        when(pool.submit(any())).thenAnswer(invocation -> {
            BrowserTask<?> task = invocation.getArgument(0);
            return task.run(null);
        });
        when(navigator.capture(any(), any(), any(), any())).thenAnswer(invocation -> {
            CrawlTarget target = invocation.getArgument(1);
            return dummySnapshot(target.url());
        });

        CrawlerProperties properties = new CrawlerProperties(4, 20, Duration.ofSeconds(10),
                Duration.ZERO, tempDir, true, false);
        CrawlService service = new CrawlService(frontier, pool, navigator, fetcher, properties, throttle);

        CrawlRequest request = new CrawlRequest(42L, testSite(), RunScope.FULL, "worker-1", () -> true);

        assertThatThrownBy(() -> service.crawl(request, (v, f) -> {}))
                .isInstanceOf(CrawlCancelledException.class)
                .hasMessageContaining("Lauf 42 wurde abgebrochen");

        verify(frontier, never()).claimBatch(anyLong(), anyString(), anyInt());
    }

    @Test
    @DisplayName("Immediate batch abort: cancellation during batch terminates before remaining batch items execute")
    void crawl_cancellationMidBatch_abortsRemainingBatchItems() {
        CrawlFrontierJdbcRepository frontier = mock(CrawlFrontierJdbcRepository.class);
        BrowserPool pool = mock(BrowserPool.class);
        PageNavigator navigator = mock(PageNavigator.class);
        SiteResourceFetcher fetcher = mock(SiteResourceFetcher.class);
        HostThrottle throttle = new HostThrottle();

        int batchSize = 20;
        List<CrawlTarget> batch = createBatch(batchSize);
        when(frontier.claimBatch(anyLong(), anyString(), anyInt())).thenReturn(batch);

        AtomicBoolean cancelled = new AtomicBoolean(false);
        AtomicInteger executedCaptures = new AtomicInteger(0);

        when(pool.submit(any())).thenAnswer(invocation -> {
            BrowserTask<?> task = invocation.getArgument(0);
            return task.run(null);
        });

        when(navigator.capture(any(), any(), any(), any())).thenAnswer(invocation -> {
            CrawlTarget target = invocation.getArgument(1);
            if (target.id() == -1L) {
                // Probe snapshot
                return dummySnapshot(target.url());
            }
            int count = executedCaptures.incrementAndGet();
            if (count == 1) {
                // Cancel immediately during first navigation
                cancelled.set(true);
            }
            try {
                Thread.sleep(40);
            } catch (InterruptedException ignored) {}
            return dummySnapshot(target.url());
        });

        CrawlerProperties properties = new CrawlerProperties(4, batchSize, Duration.ofSeconds(10),
                Duration.ZERO, tempDir, true, false);
        CrawlService service = new CrawlService(frontier, pool, navigator, fetcher, properties, throttle);

        CrawlRequest request = new CrawlRequest(99L, testSite(), RunScope.FULL, "worker-1", cancelled::get);

        long start = System.currentTimeMillis();
        assertThatThrownBy(() -> service.crawl(request, (v, f) -> {}))
                .isInstanceOf(CrawlCancelledException.class)
                .hasMessageContaining("Lauf 99 wurde abgebrochen");
        long elapsed = System.currentTimeMillis() - start;

        // Verify that out of 20 items in the batch, remaining items were cancelled before capture
        assertThat(executedCaptures.get())
                .as("Only concurrently started tasks before flag toggle should execute; majority of batch is cancelled")
                .isLessThan(batchSize);

        // Entire aborted crawl finishes much faster than sequential batch execution (20 * 40ms = 800ms)
        assertThat(elapsed).isLessThan(500);

        // Frontier complete should NOT have been called with normal completion
        verify(frontier, never()).complete(any());
    }

    @Test
    @DisplayName("Cancellation between batches prevents claiming next batch")
    void crawl_cancellationBetweenBatches_abortsCleanly() {
        CrawlFrontierJdbcRepository frontier = mock(CrawlFrontierJdbcRepository.class);
        BrowserPool pool = mock(BrowserPool.class);
        PageNavigator navigator = mock(PageNavigator.class);
        SiteResourceFetcher fetcher = mock(SiteResourceFetcher.class);
        HostThrottle throttle = new HostThrottle();

        List<CrawlTarget> batch1 = createBatch(2);
        List<CrawlTarget> batch2 = createBatch(2);
        when(frontier.claimBatch(anyLong(), anyString(), anyInt()))
                .thenReturn(batch1)
                .thenReturn(batch2);

        when(pool.submit(any())).thenAnswer(invocation -> {
            BrowserTask<?> task = invocation.getArgument(0);
            return task.run(null);
        });
        when(navigator.capture(any(), any(), any(), any())).thenAnswer(invocation -> {
            CrawlTarget target = invocation.getArgument(1);
            return dummySnapshot(target.url());
        });

        AtomicBoolean cancelled = new AtomicBoolean(false);

        CrawlerProperties properties = new CrawlerProperties(4, 2, Duration.ofSeconds(10),
                Duration.ZERO, tempDir, true, false);
        CrawlService service = new CrawlService(frontier, pool, navigator, fetcher, properties, throttle);

        CrawlRequest request = new CrawlRequest(101L, testSite(), RunScope.FULL, "worker-1", cancelled::get);

        // Progress listener flips cancellation after batch 1 completes
        assertThatThrownBy(() -> service.crawl(request, (visited, failed) -> {
            cancelled.set(true);
        }))
                .isInstanceOf(CrawlCancelledException.class)
                .hasMessageContaining("Lauf 101 wurde abgebrochen");

        // Batch 2 should never be claimed
        verify(frontier, org.mockito.Mockito.times(1)).claimBatch(anyLong(), anyString(), anyInt());
    }
}
