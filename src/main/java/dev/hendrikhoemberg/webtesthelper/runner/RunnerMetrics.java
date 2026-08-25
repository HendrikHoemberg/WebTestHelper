package dev.hendrikhoemberg.webtesthelper.runner;

import dev.hendrikhoemberg.webtesthelper.runner.persistence.RunLeaseJdbcRepository;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/**
 * Queue depth (spec 14): runs that are queued and not yet claimed. A depth that stops falling
 * is the first visible symptom of a stuck or dead worker — the failure mode spec 11.1 calls
 * worse than any broken link it would have found.
 *
 * <p>Polled on scrape rather than tracked in memory: the queue is a table several workers write
 * to, so a counter maintained by this process would drift the moment a second one exists.
 */
@Component
class RunnerMetrics {

    RunnerMetrics(RunLeaseJdbcRepository leases, MeterRegistry meters) {
        Gauge.builder("webtesthelper.runs.queued", leases, RunLeaseJdbcRepository::queuedCount)
                .description("Prüfläufe, die auf einen Worker warten")
                .register(meters);
    }
}
