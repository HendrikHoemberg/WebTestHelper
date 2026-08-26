package dev.hendrikhoemberg.webtesthelper.reporting;

import dev.hendrikhoemberg.webtesthelper.findings.OpenFindingCounts;
import dev.hendrikhoemberg.webtesthelper.runner.LastRun;
import dev.hendrikhoemberg.webtesthelper.scheduling.Schedule;

/**
 * One site's row on the dashboard grid.
 *
 * @param lastRun the newest terminal run, or null if the site never finished one
 * @param nextRun the next scheduled occurrence, or null if the site is disabled or has no
 *                enabled tier that fires again
 */
public record SiteTile(long siteId, String name, String baseUrl, boolean enabled, TrafficLight light,
                       LastRun lastRun, OpenFindingCounts counts, Schedule nextRun) {
}
