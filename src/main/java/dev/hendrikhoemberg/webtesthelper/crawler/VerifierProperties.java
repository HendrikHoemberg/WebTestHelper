package dev.hendrikhoemberg.webtesthelper.crawler;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties("webtesthelper.verifier")
public record VerifierProperties(int perHostPermits, Duration requestTimeout,
                                 Duration successTtl, Duration failureTtl) {
}