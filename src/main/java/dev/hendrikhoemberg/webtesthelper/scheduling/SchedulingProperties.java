package dev.hendrikhoemberg.webtesthelper.scheduling;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties("webtesthelper.scheduling")
public record SchedulingProperties(Duration tickInterval, boolean tickEnabled, int batchSize) {
}
