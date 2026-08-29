package dev.hendrikhoemberg.webtesthelper.runner;

import dev.hendrikhoemberg.webtesthelper.runner.persistence.RunLeaseJdbcRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Startup and periodic sweep (spec 14): every interval, expired run leases are reclaimed —
 * requeued, or superseded when a queued run already replaces them. {@code fixedDelay} runs the
 * first pass at startup, so a restarted instance picks up orphans left by its predecessor; the
 * 5s worker poll reclaims lazily in normal operation, so this sweep is the safety net that
 * bounds orphaned-RUNNING latency when no worker is polling or the fleet is warming up.
 */
@Component
@ConditionalOnProperty(name = "webtesthelper.runner.lease-reclaim-enabled", matchIfMissing = true)
public class LeaseReclaimJob {

    private static final Logger log = LoggerFactory.getLogger(LeaseReclaimJob.class);

    private final RunLeaseJdbcRepository leases;

    public LeaseReclaimJob(RunLeaseJdbcRepository leases) {
        this.leases = leases;
    }

    @Scheduled(fixedDelayString = "${webtesthelper.runner.lease-reclaim-interval:60s}")
    public void reclaimExpiredLeases() {
        try {
            leases.reclaimExpiredLeases();
        } catch (RuntimeException e) {
            // fixedDelay uses scheduleWithFixedDelay, which suppresses further executions once the
            // task throws — so one transient DB error must not escape and stop the safety net.
            log.error("Lease-Reclaim-Sweep fehlgeschlagen", e);
        }
    }
}
