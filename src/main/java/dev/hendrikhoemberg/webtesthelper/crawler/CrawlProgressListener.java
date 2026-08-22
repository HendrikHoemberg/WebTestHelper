package dev.hendrikhoemberg.webtesthelper.crawler;

/** Receives crawl progress as batches complete, so the run's row stays live (spec 14). */
public interface CrawlProgressListener {

    void onProgress(int visited, int failed);
}