package dev.hendrikhoemberg.webtesthelper.findings;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param siteWideThreshold occurrences on more than this many pages promote a finding to
 *                          site-wide (spec 6.2); default 5.
 */
@ConfigurationProperties("webtesthelper.findings")
public record FindingProperties(int siteWideThreshold) {
}
