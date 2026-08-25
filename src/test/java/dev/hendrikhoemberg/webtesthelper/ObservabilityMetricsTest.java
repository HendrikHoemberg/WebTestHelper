package dev.hendrikhoemberg.webtesthelper;

import dev.hendrikhoemberg.webtesthelper.model.RunScope;
import dev.hendrikhoemberg.webtesthelper.model.RunTrigger;
import dev.hendrikhoemberg.webtesthelper.reporting.OutboundMail;
import dev.hendrikhoemberg.webtesthelper.reporting.OutboxService;
import dev.hendrikhoemberg.webtesthelper.runner.RunService;
import dev.hendrikhoemberg.webtesthelper.support.AbstractPostgresTest;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Spec 14: "Spring Actuator exposes health and the pool metrics that actually predict trouble:
 * queue depth, browser worker saturation, outbox backlog." A system whose purpose is diagnosing
 * other systems has to be diagnosable itself.
 *
 * <p>Each gauge is registered by the module that owns the thing it measures, so no metrics
 * component has to reach across a Modulith boundary to read a number.
 */
class ObservabilityMetricsTest extends AbstractPostgresTest {

    @Autowired
    MeterRegistry meters;

    @Autowired
    RunService runs;

    @Autowired
    OutboxService outbox;

    @Autowired
    JdbcTemplate jdbc;

    private long siteId;

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM notification");
        jdbc.update("DELETE FROM run");
        jdbc.update("DELETE FROM site_check_setting");
        jdbc.update("DELETE FROM site");
        siteId = jdbc.queryForObject(
                "INSERT INTO site (name, base_url) VALUES ('Metrik', 'https://m.example.com/') RETURNING id",
                Long.class);
    }

    @Test
    void queueDepthCountsTheRunsWaitingForAWorker() {
        assertThat(meters.get("webtesthelper.runs.queued").gauge().value()).isZero();

        runs.enqueue(siteId, RunTrigger.MANUAL, RunScope.FULL);

        assertThat(meters.get("webtesthelper.runs.queued").gauge().value()).isEqualTo(1.0d);
    }

    @Test
    void browserWorkerSaturationIsBusyWorkersOutOfTheConfiguredPool() {
        assertThat(meters.get("webtesthelper.browser.workers.total").gauge().value())
                .isEqualTo(2.0d);   // application-test.properties: browser-workers=2
        assertThat(meters.get("webtesthelper.browser.workers.busy").gauge().value()).isZero();
    }

    @Test
    void outboxBacklogCountsMailsStillWaitingToGoOut() {
        assertThat(meters.get("webtesthelper.outbox.backlog").gauge().value()).isZero();

        outbox.enqueue(new OutboundMail("kollege@example.com", "Betreff", "<p>html</p>", "text"));

        assertThat(meters.get("webtesthelper.outbox.backlog").gauge().value()).isEqualTo(1.0d);
    }
}
