package dev.hendrikhoemberg.webtesthelper.crawler;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/**
 * Browser worker saturation (spec 14). A pool that is permanently at capacity is what a crawl
 * falling behind its window looks like from the outside, and it is invisible without this.
 *
 * <p>The gauge lives in {@code crawler} because {@code crawler} owns the pool; {@code web},
 * where the rest of the operator surface sits, is not allowed to depend on it.
 */
@Component
class CrawlerMetrics {

    CrawlerMetrics(BrowserPool pool, MeterRegistry meters) {
        Gauge.builder("webtesthelper.browser.workers.busy", pool, BrowserPool::busy)
                .description("Browser-Worker, die gerade eine Seite bearbeiten")
                .register(meters);
        Gauge.builder("webtesthelper.browser.workers.total", pool, BrowserPool::size)
                .description("Konfigurierte Browser-Worker")
                .register(meters);
    }
}
