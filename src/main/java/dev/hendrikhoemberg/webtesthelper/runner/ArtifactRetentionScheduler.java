package dev.hendrikhoemberg.webtesthelper.runner;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * The cron timer that drives {@link ArtifactRetentionService#prune}. 04:30 sits after §9's 03:00
 * report window and before anyone is at a desk, so a leftover run never has its screenshots
 * deleted before the report that references them is generated.
 */
@Component
@ConditionalOnProperty(name = "webtesthelper.runner.retention-enabled", matchIfMissing = true)
public class ArtifactRetentionScheduler {

    private final ArtifactRetentionService retention;

    public ArtifactRetentionScheduler(ArtifactRetentionService retention) {
        this.retention = retention;
    }

    @Scheduled(cron = "${webtesthelper.runner.retention-cron:0 30 4 * * *}")
    public void prune() {
        retention.prune();
    }
}
