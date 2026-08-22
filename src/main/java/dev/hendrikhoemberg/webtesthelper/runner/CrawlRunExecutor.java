package dev.hendrikhoemberg.webtesthelper.runner;

import dev.hendrikhoemberg.webtesthelper.catalog.SiteService;
import dev.hendrikhoemberg.webtesthelper.checks.CheckEngine;
import dev.hendrikhoemberg.webtesthelper.crawler.CrawlRequest;
import dev.hendrikhoemberg.webtesthelper.crawler.CrawlResult;
import dev.hendrikhoemberg.webtesthelper.crawler.CrawlService;
import dev.hendrikhoemberg.webtesthelper.crawler.TlsProbe;
import dev.hendrikhoemberg.webtesthelper.crawler.UrlVerificationService;
import dev.hendrikhoemberg.webtesthelper.model.CheckFinding;
import dev.hendrikhoemberg.webtesthelper.model.RunFacts;
import dev.hendrikhoemberg.webtesthelper.model.SiteContext;
import dev.hendrikhoemberg.webtesthelper.model.TlsCertificateFact;
import dev.hendrikhoemberg.webtesthelper.model.UrlVerifications;
import dev.hendrikhoemberg.webtesthelper.runner.persistence.RunLeaseJdbcRepository;
import dev.hendrikhoemberg.webtesthelper.runner.persistence.RunResultJdbcRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
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
    private final UrlVerificationService verifier;
    private final TlsProbe tlsProbe;
    private final SiteService sites;
    private final RunResultJdbcRepository results;
    private final RunLeaseJdbcRepository leases;
    private final WorkerIdentity identity;

    public CrawlRunExecutor(CrawlService crawler, CheckEngine checks,
            UrlVerificationService verifier, TlsProbe tlsProbe, SiteService sites,
            RunResultJdbcRepository results, RunLeaseJdbcRepository leases,
            WorkerIdentity identity) {
        this.crawler = crawler;
        this.checks = checks;
        this.verifier = verifier;
        this.tlsProbe = tlsProbe;
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

        // The check pass runs outside the crawl's heartbeat callback; one more extension closes
        // the stale-lease window so the sweep cannot reclaim a run that is still working (spec 14).
        leases.heartbeat(lease.runId(), identity.name(), LEASE_EXTENSION);

        // Verification: ping every link the crawl did not itself navigate, and probe the base URL's
        // certificate. A large site's verification pass is minutes of blocking I/O, so the lease is
        // extended once more before it starts — a healthy run must not be reclaimed mid-verify (§14).
        leases.heartbeat(lease.runId(), identity.name(), LEASE_EXTENSION);
        UrlVerifications verifications = verifier.verify(site, result.snapshots(),
                result.verificationCandidates());
        TlsCertificateFact tls = tlsProbe.probe(site.baseUrl());
        RunFacts facts = RunFacts.of(result.snapshots(), lease.scope(), startedAt,
                verifications, tls, result.sitemapUrls());

        List<CheckFinding> pageFindings = checks.evaluateRun(result.snapshots(), site, facts);
        List<CheckFinding> siteFindings = checks.evaluateSite(result.snapshots(), site, facts);
        List<CheckFinding> findings = new ArrayList<>(pageFindings.size() + siteFindings.size());
        findings.addAll(pageFindings);
        findings.addAll(siteFindings);

        log.info("Lauf {}: {} Befunde ({} Seiten, {} verifiziert, {} TLS), {} geprüfte URLs",
                lease.runId(), findings.size(), result.snapshots().pageCount(),
                verifications.size(), tls.host() == null ? 0 : 1,
                result.verificationCandidates().size());

        List<String> coveredCheckTypes = lease.scope().checkTypes().stream()
                .filter(site::enabled)
                .filter(checks.coveredTypes()::contains)
                .map(Enum::name)
                .sorted()
                .toList();
        results.saveCrawlOutcome(lease.runId(), result, coveredCheckTypes,
                result.snapshots().softNotFound(), findings.size());
    }
}
