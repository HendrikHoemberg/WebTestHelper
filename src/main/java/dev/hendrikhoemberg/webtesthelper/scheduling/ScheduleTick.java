package dev.hendrikhoemberg.webtesthelper.scheduling;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * The fixed-delay timer that drives one {@link ScheduleDispatcher#tick} every tick interval.
 * Nothing is swallowed: Spring logs a thrown scheduled task and keeps the schedule alive,
 * which is the behaviour wanted — one failed tick must not stop the fleet's scheduler.
 */
@Component
@ConditionalOnProperty(name = "webtesthelper.scheduling.tick-enabled", matchIfMissing = true)
public class ScheduleTick {

    private final ScheduleDispatcher dispatcher;

    public ScheduleTick(ScheduleDispatcher dispatcher) {
        this.dispatcher = dispatcher;
    }

    @Scheduled(fixedDelayString = "${webtesthelper.scheduling.tick-interval:30s}")
    public void tick() {
        dispatcher.tick(Instant.now());
    }
}
