package dev.hendrikhoemberg.webtesthelper.runner;

import dev.hendrikhoemberg.webtesthelper.catalog.SiteService;
import dev.hendrikhoemberg.webtesthelper.checks.CheckEngine;
import dev.hendrikhoemberg.webtesthelper.crawler.CrawlRequest;
import dev.hendrikhoemberg.webtesthelper.crawler.CrawlResult;
import dev.hendrikhoemberg.webtesthelper.crawler.CrawlService;
import dev.hendrikhoemberg.webtesthelper.model.CheckFinding;
import dev.hendrikhoemberg.webtesthelper.model.RunFacts;
import dev.hendrikhoemberg.webtesthelper.model.SiteContext;
import dev.hendrikhoemberg.webtesthelper.runner.persistence.RunLeaseJdbcRepository;
import dev.hendrikhoemberg.webtesthelper.runner.persistence.RunResultJdbcRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * The sole {@link RunExecutor}: crawl the leased site, evaluate the page checks over what the
 * crawl saw, then record coverage, the soft-404 probe and the finding count on the run row.
 *
 * <p>The check pass sits here rather than in the crawler because the crawler evaluates nothing
 * (spec 5.2) — it produces snapshots, which is why {@link CrawlResult} carries the whole
 * {@code RunSnapshots} rather than counts.
 *
 * <p>Plan 4 replaces {@code findings.size()} with materialisation: fingerprinting, site-wide
 * promotion, occurrences and the coverage-scoped diff (spec 6.2). Until then the findings are
 * computed, counted and dropped — which is enough to prove the pass runs and avoids inventing
 * a findings schema that materialisation would immediately replace.
 */
@Component
public class CrawlRunExecutor implements RunExecutor {

    private static final Logger log = LoggerFactory.getLogger(CrawlRunExecutor.class);

    /**
     * A crawl outlives the 30-minute lease it was claimed under; without the heartbeat the
     * sweep would reclaim a run that is perfectly healthy (spec 14).
     */
    private static final Duration LEASE_EXTENSION = Duration.ofMinutes(30);

    private final CrawlService crawler;
    private final CheckEngine checks;
    private final SiteService sites;
    private final RunResultJdbcRepository results;
    private final RunLeaseJdbcRepository leases;
    private final WorkerIdentity identity;

    public CrawlRunExecutor(CrawlService crawler, CheckEngine checks, SiteService sites,
            RunResultJdbcRepository results, RunLeaseJdbcRepository leases,
            WorkerIdentity identity) {
        this.crawler = crawler;
        this.checks = checks;
        this.sites = sites;
        this.results = results;
        this.leases = leases;
        this.identity = identity;
    }

    @Override
    public void execute(RunLease lease) {
        SiteContext site = sites.contextFor(lease.siteId());
        Instant startedAt = Instant.now();
        CrawlResult result = crawler.crawl(
                new CrawlRequest(lease.runId(), site, lease.scope(), identity.name()),
                (visited, failed) -> {
                    // A crawl outlives the 30-minute lease it was claimed under; without this
                    // the sweep would reclaim a run that is perfectly healthy (spec 14).
                    leases.heartbeat(lease.runId(), identity.name(), LEASE_EXTENSION);
                    results.updateProgress(lease.runId(), visited, failed);
                });

        RunFacts facts = RunFacts.of(result.snapshots(), lease.scope(), startedAt);
        List<CheckFinding> findings = checks.evaluateRun(result.snapshots(), site, facts);
        log.info("Lauf {}: {} Befunde auf {} Seiten", lease.runId(), findings.size(),
                result.snapshots().pageCount());

        List<String> coveredCheckTypes = lease.scope().checkTypes().stream()
                .filter(site::enabled)
                .map(Enum::name)
                .sorted()
                .toList();
        results.saveCrawlOutcome(lease.runId(), result, coveredCheckTypes,
                result.snapshots().softNotFound(), findings.size());
    }
}
