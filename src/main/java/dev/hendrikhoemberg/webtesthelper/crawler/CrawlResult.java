package dev.hendrikhoemberg.webtesthelper.crawler;

import dev.hendrikhoemberg.webtesthelper.model.RunSnapshots;

import java.util.List;

/** The outcome of one crawl: the snapshots for the check pass and the coverage facts (spec 6.4). */
public record CrawlResult(RunSnapshots snapshots, int pagesVisited, int pagesFailed,
                          List<String> coveredUrls, boolean partialCoverage,
                          String budgetStopReason) {

    public CrawlResult {
        coveredUrls = List.copyOf(coveredUrls);
    }
}