package dev.hendrikhoemberg.webtesthelper.runner;

import dev.hendrikhoemberg.webtesthelper.crawler.BrowserPool;
import dev.hendrikhoemberg.webtesthelper.model.RunStatus;
import dev.hendrikhoemberg.webtesthelper.runner.persistence.RunRepository;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CapacityServiceTest {

    @Test
    void pollIntervalDefaultsToThirtySecondsWhenThePropertyIsAbsent() {
        BrowserPool pool = mock(BrowserPool.class);
        RunRepository runs = mock(RunRepository.class);
        when(pool.size()).thenReturn(4);
        when(pool.busy()).thenReturn(1);
        when(runs.countByStatus(RunStatus.QUEUED)).thenReturn(3L);

        CapacityService service = new CapacityService(pool, runs, new DashboardProperties(null), 5);

        SystemCapacity capacity = service.current(7);

        assertThat(capacity.browserWorkersTotal()).isEqualTo(4);
        assertThat(capacity.browserWorkersBusy()).isEqualTo(1);
        assertThat(capacity.queuedRuns()).isEqualTo(3);
        assertThat(capacity.failedMails()).isEqualTo(7);
        assertThat(capacity.pollInterval()).isEqualTo(Duration.ofSeconds(30));
        assertThat(capacity.schedulerThreads()).isEqualTo(5);
    }
}
