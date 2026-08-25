package dev.hendrikhoemberg.webtesthelper.findings;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param siteWideThreshold occurrences on more than this many pages promote a finding to
 *                          site-wide (spec 6.2); default 5.
 * @param maxMuteDays       maximum allowed mute duration in days; default 365.
 * @param pageSize          number of findings per page in the triage list; default 50.
 */
@ConfigurationProperties("webtesthelper.findings")
public record FindingProperties(int siteWideThreshold, int maxMuteDays, int pageSize) {
    public FindingProperties {
        if (siteWideThreshold <= 0) {
            siteWideThreshold = 5;
        }
        if (maxMuteDays <= 0) {
            maxMuteDays = 365;
        }
        if (pageSize <= 0) {
            pageSize = 50;
        }
    }
}
