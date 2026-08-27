package dev.hendrikhoemberg.webtesthelper.runner;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Configuration for the post-crawl interaction check pass (spec 5.2, 7.2).
 *
 * @param maxTargets maximum target pages driven per interaction check
 * @param timeout    per-check timeout budget
 */
@ConfigurationProperties("webtesthelper.checks.interaction")
public record InteractionProperties(int maxTargets, Duration timeout) {
}
