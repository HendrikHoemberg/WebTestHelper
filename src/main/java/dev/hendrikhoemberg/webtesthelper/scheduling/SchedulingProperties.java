package dev.hendrikhoemberg.webtesthelper.scheduling;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties("webtesthelper.scheduling")
public record SchedulingProperties(Duration tickInterval, boolean tickEnabled, int batchSize) {
    // tickInterval and tickEnabled feed only the annotation placeholders on ScheduleTick
    // (@Scheduled fixedDelayString, @ConditionalOnProperty); batchSize is the only field the
    // dispatcher actually reads. Keep them bound so the annotations resolve at runtime.
}

