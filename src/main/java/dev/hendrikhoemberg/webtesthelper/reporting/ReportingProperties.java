package dev.hendrikhoemberg.webtesthelper.reporting;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "webtesthelper.reporting")
public record ReportingProperties(
        Duration dispatchInterval,
        int maxAttempts,
        boolean dispatcherEnabled,
        Duration digestSettle,
        Duration digestMaxWait,
        Duration digestInterval,
        boolean digestEnabled,
        int digestMaxFindings
) {
    public ReportingProperties {
        if (dispatchInterval == null) {
            dispatchInterval = Duration.ofSeconds(30);
        }
        if (maxAttempts <= 0) {
            maxAttempts = 5;
        }
        if (digestSettle == null) {
            digestSettle = Duration.ofMinutes(5);
        }
        if (digestMaxWait == null) {
            digestMaxWait = Duration.ofHours(6);
        }
        if (digestInterval == null) {
            digestInterval = Duration.ofMinutes(2);
        }
        if (digestMaxFindings <= 0) {
            digestMaxFindings = 10;
        }
    }
}
