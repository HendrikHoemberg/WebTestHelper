package dev.hendrikhoemberg.webtesthelper.reporting;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/**
 * Outbox backlog (spec 14). Spec 11.5 already puts failed sends in front of a person as a health
 * banner; this is the other half — a backlog that grows without anything having failed yet means
 * the dispatcher itself stopped, which no banner would show.
 */
@Component
class OutboxMetrics {

    OutboxMetrics(OutboxService outbox, MeterRegistry meters) {
        Gauge.builder("webtesthelper.outbox.backlog", outbox, OutboxService::backlogCount)
                .description("Nachrichten, die noch nicht zugestellt sind")
                .register(meters);
    }
}
