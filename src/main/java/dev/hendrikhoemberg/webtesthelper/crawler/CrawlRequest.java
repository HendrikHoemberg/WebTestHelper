package dev.hendrikhoemberg.webtesthelper.crawler;

import dev.hendrikhoemberg.webtesthelper.model.RunScope;
import dev.hendrikhoemberg.webtesthelper.model.SiteContext;

import java.util.function.BooleanSupplier;

/** Everything the crawl pipeline needs to know about one run. */
public record CrawlRequest(long runId, SiteContext site, RunScope scope, String owner,
                           BooleanSupplier cancellationCheck) {

    public CrawlRequest(long runId, SiteContext site, RunScope scope, String owner) {
        this(runId, site, scope, owner, () -> false);
    }
}