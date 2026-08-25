package dev.hendrikhoemberg.webtesthelper.runner;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties("webtesthelper.runner")
public record RunnerProperties(Duration pollInterval, boolean pollerEnabled) {
}
