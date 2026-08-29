package dev.hendrikhoemberg.webtesthelper.crawler;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.file.Path;
import java.time.Duration;

/**
 * @param browserWorkers    platform threads, each owning one Playwright + Chromium process.
 *                          Four means ~2 GB of Chromium under load (spec 16).
 * @param batchSize         URLs claimed from the frontier per statement (spec 6.5)
 * @param navigationTimeout per-page navigation budget; exceeding it is PAGE_UNREACHABLE, not
 *                          a dead run (spec 14)
 * @param perHostDelay      politeness gap between navigations to the same host (spec 8)
 * @param artifactDir       screenshots land under {artifactDir}/{runId}/ (spec 16)
 * @param noSandbox         pass --no-sandbox to Chromium for container use (default false)
 */
@ConfigurationProperties("webtesthelper.crawler")
public record CrawlerProperties(int browserWorkers, int batchSize, Duration navigationTimeout,
                                Duration perHostDelay, Path artifactDir, boolean headless, boolean noSandbox) {
}