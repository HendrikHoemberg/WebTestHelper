package dev.hendrikhoemberg.webtesthelper.runner;

import dev.hendrikhoemberg.webtesthelper.support.AbstractPostgresTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.ApplicationContext;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The lease-reclaim wiring: the unit tests drive {@link LeaseReclaimJob#reclaimExpiredLeases()}
 * directly, which stays green even if nothing ever calls it on a schedule — so the bean's
 * presence and its {@link Scheduled} hook are asserted here, mirroring
 * {@code RecorderSocketHandshakeIntegrationTest#theIdleReaperIsActuallyScheduled}.
 */
@TestPropertySource(properties = "webtesthelper.runner.lease-reclaim-enabled=true")
class LeaseReclaimWiringTest extends AbstractPostgresTest {

    @Autowired
    ApplicationContext context;

    @Test
    void theLeaseReclaimIsAScheduledLiveBean() throws Exception {
        assertThat(context.getBeansOfType(LeaseReclaimJob.class))
                .as("The lease-reclaim job is a live bean in the running application")
                .isNotEmpty();
        assertThat(LeaseReclaimJob.class.getMethod("reclaimExpiredLeases")
                .getAnnotation(Scheduled.class))
                .as("...and something drives it on a schedule")
                .isNotNull();
    }

    @Test
    void theLeaseReclaimBeanIsOmittedWhenDisabled() {
        new ApplicationContextRunner()
                .withUserConfiguration(LeaseReclaimJob.class)
                .withPropertyValues("webtesthelper.runner.lease-reclaim-enabled=false")
                .run(context -> assertThat(context).doesNotHaveBean(LeaseReclaimJob.class));
    }
}
