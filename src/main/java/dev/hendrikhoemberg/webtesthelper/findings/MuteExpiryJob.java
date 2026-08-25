package dev.hendrikhoemberg.webtesthelper.findings;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * Hourly sweep cron job that drives {@link MuteExpiryService#sweep} (spec 6.3, D49, D50).
 */
@Component
@ConditionalOnProperty(name = "webtesthelper.findings.mute-sweep-enabled", matchIfMissing = true)
public class MuteExpiryJob {

    private final MuteExpiryService service;

    public MuteExpiryJob(MuteExpiryService service) {
        this.service = service;
    }

    @Scheduled(cron = "${webtesthelper.findings.mute-sweep-cron:0 15 * * * *}")
    public void sweep() {
        service.sweep(Instant.now());
    }
}
