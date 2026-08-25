package dev.hendrikhoemberg.webtesthelper.runner;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Background daemon thread polling the queue and executing claimed runs (spec 5.3, D33).
 */
@Component
@ConditionalOnProperty(name = "webtesthelper.runner.poller-enabled", matchIfMissing = true)
public class RunPoller implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(RunPoller.class);
    private static final Duration STOP_BUDGET = Duration.ofSeconds(10);

    private final RunWorker worker;
    private final RunnerProperties properties;
    private volatile boolean running;
    private Thread thread;

    public RunPoller(RunWorker worker, RunnerProperties properties) {
        this.worker = worker;
        this.properties = properties;
    }

    @Override
    public synchronized void start() {
        if (running) {
            return;
        }
        running = true;
        thread = Thread.ofPlatform().daemon(true).name("run-poller").unstarted(this::loop);
        thread.start();
    }

    @Override
    public synchronized void stop() {
        if (!running) {
            return;
        }
        running = false;
        if (thread != null) {
            thread.interrupt();
            try {
                thread.join(STOP_BUDGET.toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    private void loop() {
        while (running) {
            try {
                if (!worker.workOnce()) {
                    Thread.sleep(properties.pollInterval());
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception keepPolling) {
                log.error("Warteschlange konnte nicht abgearbeitet werden", keepPolling);
                // Wait before retrying. A failure that persists — an unreachable database, say —
                // otherwise spins the thread at full speed and repeats one stack trace thousands
                // of times a second, which buries the very log line that explains the outage.
                if (!sleepPollInterval()) {
                    return;
                }
            }
        }
    }

    /** Sleeps one poll interval; false means the thread was interrupted and the loop must end. */
    private boolean sleepPollInterval() {
        try {
            Thread.sleep(properties.pollInterval());
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
