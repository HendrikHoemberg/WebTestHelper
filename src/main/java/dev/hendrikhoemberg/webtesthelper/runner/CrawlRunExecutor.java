package dev.hendrikhoemberg.webtesthelper.runner;

import dev.hendrikhoemberg.webtesthelper.catalog.SiteService;
import dev.hendrikhoemberg.webtesthelper.crawler.CrawlRequest;
import dev.hendrikhoemberg.webtesthelper.crawler.CrawlResult;
import dev.hendrikhoemberg.webtesthelper.crawler.CrawlService;
import dev.hendrikhoemberg.webtesthelper.model.SiteContext;
import dev.hendrikhoemberg.webtesthelper.runner.persistence.RunLeaseJdbcRepository;
import dev.hendrikhoemberg.webtesthelper.runner.persistence.RunResultJdbcRepository;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;

/**
 * The sole {@link RunExecutor}. Crawls the leased site, then records coverage and the soft-404
 * probe on the run row.
 *
 * <p>Plan 3 deliberately not done here: the check pass lands between the crawl and
 * {@code saveCrawlOutcome}, which is why {@link CrawlResult} carries the whole
 * {@code RunSnapshots} rather than just counts.
 */
@Component
public class CrawlRunExecutor implements RunExecutor {

    /**
     * A crawl outlives the 30-minute lease it was claimed under; without the heartbeat the
     * sweep would reclaim a run that is perfectly healthy (spec 14).
     */
    private static final Duration LEASE_EXTENSION = Duration.ofMinutes(30);

    private final CrawlService crawler;
    private final SiteService sites;
    private final RunResultJdbcRepository results;
    private final RunLeaseJdbcRepository leases;
    private final WorkerIdentity identity;

    public CrawlRunExecutor(CrawlService crawler, SiteService sites,
            RunResultJdbcRepository results, RunLeaseJdbcRepository leases,
            WorkerIdentity identity) {
        this.crawler = crawler;
        this.sites = sites;
        this.results = results;
        this.leases = leases;
        this.identity = identity;
    }

    @Override
    public void execute(RunLease lease) {
        SiteContext site = sites.contextFor(lease.siteId());
        CrawlResult result = crawler.crawl(
                new CrawlRequest(lease.runId(), site, lease.scope(), identity.name()),
                (visited, failed) -> {
                    // A crawl outlives the 30-minute lease it was claimed under; without this
                    // the sweep would reclaim a run that is perfectly healthy (spec 14).
                    leases.heartbeat(lease.runId(), identity.name(), LEASE_EXTENSION);
                    results.updateProgress(lease.runId(), visited, failed);
                });

        List<String> coveredCheckTypes = lease.scope().checkTypes().stream()
                .filter(site::enabled)
                .map(Enum::name)
                .sorted()
                .toList();
        results.saveCrawlOutcome(lease.runId(), result, coveredCheckTypes,
                result.snapshots().softNotFound());
    }
}