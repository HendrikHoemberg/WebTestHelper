package dev.hendrikhoemberg.webtesthelper.runner;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * The dashboard's polling cadence (D64). Lives here, not in reporting, because the only reader is
 * {@link CapacityService}, which reports it inside {@link SystemCapacity} — and runner must never
 * depend on reporting. The default is the plan's decided constant.
 *
 * @param pollInterval how often the dashboard polls; default 30 s (D64)
 */
@ConfigurationProperties("webtesthelper.dashboard")
public record DashboardProperties(Duration pollInterval) {
    public DashboardProperties {
        if (pollInterval == null) {
            pollInterval = Duration.ofSeconds(30);
        }
    }
}
