package dev.hendrikhoemberg.webtesthelper.runner;

import java.time.Duration;

/**
 * The queue's and the browser pool's live state, as shown on the dashboard and the settings
 * screen. {@code failedMails} is filled in by the caller, not read here — the runner must not
 * learn that mail exists (D53); the rest is what runner and crawler already own.
 */
public record SystemCapacity(int browserWorkersTotal, int browserWorkersBusy, int queuedRuns,
                             int failedMails, Duration pollInterval, int schedulerThreads) {
}
