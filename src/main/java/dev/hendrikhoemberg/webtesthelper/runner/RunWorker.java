package dev.hendrikhoemberg.webtesthelper.runner;

import dev.hendrikhoemberg.webtesthelper.model.RunStatus;
import dev.hendrikhoemberg.webtesthelper.runner.persistence.RunLeaseJdbcRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Polls the queue and executes claimed runs. {@code workOnce()} is one claim attempt and
 * exists so tests can drive the loop deterministically; in production {@link RunPoller} calls
 * it on its own daemon thread (D33).
 */
@Component
public class RunWorker {

    private static final Logger log = LoggerFactory.getLogger(RunWorker.class);
    private static final Duration LEASE = Duration.ofMinutes(30);

    private final RunLeaseJdbcRepository leases;
    private final WorkerIdentity identity;
    private RunExecutor executor;

    public RunWorker(RunLeaseJdbcRepository leases, WorkerIdentity identity, RunExecutor executor) {
        this.leases = leases;
        this.identity = identity;
        this.executor = executor;
    }

    /** Test seam only. */
    void withExecutorForTest(RunExecutor executor) {
        this.executor = executor;
    }

    /** One claim attempt. Returns true if a run was claimed and executed. */
    public boolean workOnce() {
        return leases.claimNext(identity.name(), LEASE)
                .map(this::executeLeased)
                .orElse(false);
    }

    private boolean executeLeased(RunLease lease) {
        // Spec 14: one run's complete history has to be greppable. The crawl sets this for its
        // own span; verification, the check passes, re-verification and materialisation all run
        // here, and so does every line the run's failure path writes.
        MDC.put("runId", String.valueOf(lease.runId()));
        MDC.put("siteId", String.valueOf(lease.siteId()));
        try {
            log.info("Run {} gestartet (site {})", lease.runId(), lease.siteId());
            executor.execute(lease);
            if (leases.finish(lease.runId(), identity.name(), RunStatus.COMPLETED, null)) {
                log.info("Run {} abgeschlossen", lease.runId());
            } else {
                log.warn("Run {} beendet, aber nicht mehr Eigentümer", lease.runId());
            }
        } catch (Exception e) {
            String message = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            log.error("Run {} fehlgeschlagen", lease.runId(), e);
            leases.finish(lease.runId(), identity.name(), RunStatus.FAILED, message);
        } finally {
            MDC.remove("runId");
            MDC.remove("siteId");
        }
        return true;
    }
}
