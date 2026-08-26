package dev.hendrikhoemberg.webtesthelper.runner;

import dev.hendrikhoemberg.webtesthelper.crawler.BrowserPool;
import dev.hendrikhoemberg.webtesthelper.model.RunStatus;
import dev.hendrikhoemberg.webtesthelper.runner.persistence.RunRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * The runner's live capacity, for the dashboard and the settings screen (spec 14). Runner is the
 * only module allowed to hold {@link BrowserPool}; {@code failedMails} is the one field it cannot
 * produce itself and is therefore passed in by the caller (D53).
 */
@Service
public class CapacityService {

    private final BrowserPool pool;
    private final RunRepository runs;
    private final DashboardProperties properties;
    private final int schedulerThreads;

    public CapacityService(BrowserPool pool, RunRepository runs, DashboardProperties properties,
                           @Value("${spring.task.scheduling.pool.size}") int schedulerThreads) {
        this.pool = pool;
        this.runs = runs;
        this.properties = properties;
        this.schedulerThreads = schedulerThreads;
    }

    public SystemCapacity current(int failedMails) {
        return new SystemCapacity(pool.size(), pool.busy(),
                (int) runs.countByStatus(RunStatus.QUEUED), failedMails, properties.pollInterval(),
                schedulerThreads);
    }
}
