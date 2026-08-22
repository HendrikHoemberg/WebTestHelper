package dev.hendrikhoemberg.webtesthelper.crawler;

public record CrawlOutcome(long id, CrawlItemStatus status, Integer httpStatus, String errorMessage) {
}