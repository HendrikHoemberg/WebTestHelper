package dev.hendrikhoemberg.webtesthelper.reporting;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "webtesthelper.reporting")
public record ReportingProperties(
        Duration dispatchInterval,
        int maxAttempts,
        boolean dispatcherEnabled
) {
    public ReportingProperties {
        if (dispatchInterval == null) {
            dispatchInterval = Duration.ofSeconds(30);
        }
        if (maxAttempts <= 0) {
            maxAttempts = 5;
        }
    }
}
