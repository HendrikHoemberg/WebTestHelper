package dev.hendrikhoemberg.webtesthelper.findings;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Tunables for the findings subsystem (spec 6.2). */
@ConfigurationProperties("webtesthelper.findings")
public record FindingProperties(int siteWideThreshold) {
}
