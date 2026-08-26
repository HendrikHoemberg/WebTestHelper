package dev.hendrikhoemberg.webtesthelper.reporting;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * Scheduled job driving {@link DigestService#runCycle} periodically.
 */
@Component
@ConditionalOnProperty(name = "webtesthelper.reporting.digest-enabled", havingValue = "true", matchIfMissing = true)
public class DigestScheduledJob {

    private final DigestService digestService;

    public DigestScheduledJob(DigestService digestService) {
        this.digestService = digestService;
    }

    @Scheduled(fixedDelayString = "${webtesthelper.reporting.digest-interval:2m}")
    public void cycle() {
        digestService.runCycle(Instant.now());
    }
}
