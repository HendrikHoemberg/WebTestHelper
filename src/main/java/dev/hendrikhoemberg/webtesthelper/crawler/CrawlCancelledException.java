package dev.hendrikhoemberg.webtesthelper.crawler;

/**
 * Thrown when a crawl is stopped cooperatively via cancellation check.
 */
public class CrawlCancelledException extends RuntimeException {

    public CrawlCancelledException(String message) {
        super(message);
    }
}
