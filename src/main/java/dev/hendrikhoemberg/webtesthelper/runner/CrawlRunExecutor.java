package dev.hendrikhoemberg.webtesthelper.runner;

import dev.hendrikhoemberg.webtesthelper.catalog.SiteService;
import dev.hendrikhoemberg.webtesthelper.checks.CheckEngine;
import dev.hendrikhoemberg.webtesthelper.crawler.CrawlRequest;
import dev.hendrikhoemberg.webtesthelper.crawler.CrawlResult;
import dev.hendrikhoemberg.webtesthelper.crawler.CrawlService;
import dev.hendrikhoemberg.webtesthelper.crawler.FindingReverifier;
import dev.hendrikhoemberg.webtesthelper.crawler.ReverificationOutcome;
import dev.hendrikhoemberg.webtesthelper.crawler.TlsProbe;
import dev.hendrikhoemberg.webtesthelper.crawler.UrlVerificationService;
import dev.hendrikhoemberg.webtesthelper.findings.FindingService;
import dev.hendrikhoemberg.webtesthelper.findings.RunDiff;
import dev.hendrikhoemberg.webtesthelper.findings.ReportSection;
import dev.hendrikhoemberg.webtesthelper.model.RunCoverage;
import dev.hendrikhoemberg.webtesthelper.model.CheckFinding;
import dev.hendrikhoemberg.webtesthelper.model.RunFacts;
import dev.hendrikhoemberg.webtesthelper.model.RunScope;
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
 * <p>The pipeline is crawl → verify → page checks → site checks → re-verify → materialise →
 * diff (plan 4, task 6). Re-verification only ever drops findings (a dead link that answers
 * {@code OK} on a later probe was a transient failure, spec 8); the survivors are materialised
 * into fingerprints, promoted site-wide where the check so decides, and diffed against the
 * previous run so the run row can carry {@code findings_new} and {@code findings_resolved}
 * rather than a bare count (spec 6.4).
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
    private final FindingReverifier reverifier;
    private final FindingService findings;
    private final RunnerProperties properties;

    public CrawlRunExecutor(CrawlService crawler, CheckEngine checks,
            UrlVerificationService verifier, TlsProbe tlsProbe, SiteService sites,
            RunResultJdbcRepository results, RunLeaseJdbcRepository leases,
            WorkerIdentity identity, FindingReverifier reverifier, FindingService findings,
            RunnerProperties properties) {
        this.crawler = crawler;
        this.checks = checks;
        this.verifier = verifier;
        this.tlsProbe = tlsProbe;
        this.sites = sites;
        this.results = results;
        this.leases = leases;
        this.identity = identity;
        this.reverifier = reverifier;
        this.findings = findings;
        this.properties = properties;
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

        // Verification and the check pass both run outside the crawl's heartbeat callback, and a
        // large site's verification pass is minutes of blocking I/O. One extension here closes the
        // stale-lease window so the sweep cannot reclaim a run that is still working (spec 14).
        leases.heartbeat(lease.runId(), identity.name(), LEASE_EXTENSION);

        // Ping every link the crawl did not itself navigate, and probe the base URL's certificate.
        UrlVerifications verifications = verifier.verify(site, result.snapshots(),
                result.verificationCandidates());
        TlsCertificateFact tls = tlsProbe.probe(site.baseUrl());
        RunFacts facts = RunFacts.of(result.snapshots(), lease.scope(), startedAt,
                verifications, tls, result.sitemapUrls());

        List<CheckFinding> pageFindings = checks.evaluateRun(result.snapshots(), site, facts);
        List<CheckFinding> siteFindings = checks.evaluateSite(result.snapshots(), site, facts);
        List<CheckFinding> checkFindings = new ArrayList<>(pageFindings.size() + siteFindings.size());
        checkFindings.addAll(pageFindings);
        checkFindings.addAll(siteFindings);

        // A dead-link finding is a chance for a false positive: re-check every subject the first
        // pass called DEAD and that the crawl did not itself visit. Anything that answers OK on a
        // later probe was transient and never becomes a finding (spec 8). Nothing here swallows a
        // verifier exception — RunWorker's catch marks the run FAILED.
        ReverificationOutcome rechecked = reverifier.reverify(site, result.snapshots(),
                verifications, checkFindings);
        List<CheckFinding> surviving = rechecked.surviving();
        if (rechecked.rechecked() > 0) {
            // Trust is the product (spec 8): a run that quietly discarded findings has to say
            // how many it dropped and out of how many, or the filter cannot be audited at all.
            log.info("Lauf {}: {} Ziele erneut geprüft, {} wieder erreichbar, {} von {} Befunden verworfen",
                    lease.runId(), rechecked.rechecked(), rechecked.recoveredSubjects().size(),
                    checkFindings.size() - surviving.size(), checkFindings.size());
        }

        List<String> coveredCheckTypes = lease.scope().checkTypes().stream()
                .filter(site::enabled)
                .filter(checks.coveredTypes()::contains)
                .map(Enum::name)
                .sorted()
                .toList();

        // Materialise the survivors into fingerprints, promote site-wide where the check decides,
        // and diff against the previous run. observedAt is the run's start so every observation a
        // run makes is stamped with one instant (spec 6.4).
        RunCoverage coverage = RunCoverage.of(coveredCheckTypes, result.coveredUrls(),
                result.snapshots().visitedUrls(), result.partialCoverage());
        RunDiff diff = findings.record(lease.runId(), site.siteId(), surviving, coverage, startedAt);

        log.info("Lauf {}: {} neu, {} behoben, {} weiterhin offen",
                lease.runId(), diff.count(ReportSection.NEW), diff.count(ReportSection.FIXED),
                diff.observedTotal() - diff.count(ReportSection.NEW));

        results.saveCrawlOutcome(lease.runId(), result, coveredCheckTypes,
                result.snapshots().softNotFound(), diff.observedTotal(),
                diff.count(ReportSection.NEW), diff.count(ReportSection.FIXED));

        pinKeyPagesAfterFullCrawl(lease, site, result);
    }

    /**
     * Pins the pulse set after the first full crawl. Coverage-scoped resolution compares a run's
     * visited URLs against a finding's location (§6.4), so a set recomputed each run would make
     * findings flicker between resolved and regressed. Only a FULL scope with no pins yet and a
     * crawl that was not partial may freeze the set: a budget-capped crawl saw an arbitrary slice
     * of the site, and freezing that slice is precisely the drift §9 exists to prevent.
     *
     * <p>A pinning failure must not fail the run — the crawl, the checks and the materialised
     * findings are already on the run row. Same spirit as {@code CrawlService.enqueueDiscovered}.
     */
    private void pinKeyPagesAfterFullCrawl(RunLease lease, SiteContext site, CrawlResult result) {
        if (lease.scope() != RunScope.FULL || !site.pinnedKeyPages().isEmpty()
                || result.partialCoverage()) {
            return;
        }
        try {
            List<String> pages = KeyPageSelector.select(result.snapshots(), site.baseUrl(),
                    properties.keyPages());
            sites.pinKeyPages(site.siteId(), pages);
            log.info("Lauf {}: Schlüsselseiten für Website {} festgehalten: {}", lease.runId(),
                    site.siteId(), pages.size());
        } catch (RuntimeException e) {
            log.warn("Lauf {}: Schlüsselseiten für Website {} nicht festgehalten: {}", lease.runId(),
                    site.siteId(), e.getMessage());
        }
    }
}
