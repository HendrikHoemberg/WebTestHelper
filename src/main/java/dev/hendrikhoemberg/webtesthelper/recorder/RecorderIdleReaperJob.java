package dev.hendrikhoemberg.webtesthelper.recorder;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Periodically drives {@link RecordingSessionRegistry#reapIdle()} to release workers
 * from abandoned sessions (spec 10.1, D109).
 */
@Component
@ConditionalOnProperty(name = "webtesthelper.recorder.idle-reaper-enabled", matchIfMissing = true)
public class RecorderIdleReaperJob {

    private final RecordingSessionRegistry registry;

    public RecorderIdleReaperJob(RecordingSessionRegistry registry) {
        this.registry = registry;
    }

    @Scheduled(fixedDelayString = "${webtesthelper.recorder.idle-reaper-interval:30s}")
    public void reapIdle() {
        registry.reapIdle();
    }
}
