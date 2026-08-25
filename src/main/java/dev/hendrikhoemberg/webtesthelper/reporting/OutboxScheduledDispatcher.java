package dev.hendrikhoemberg.webtesthelper.reporting;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "webtesthelper.reporting.dispatcher-enabled", matchIfMissing = true)
public class OutboxScheduledDispatcher {

    private final OutboxDispatcher dispatcher;

    public OutboxScheduledDispatcher(OutboxDispatcher dispatcher) {
        this.dispatcher = dispatcher;
    }

    @Scheduled(fixedDelayString = "${webtesthelper.reporting.dispatch-interval:30s}")
    public void schedule() {
        dispatcher.dispatchCycle();
    }
}
