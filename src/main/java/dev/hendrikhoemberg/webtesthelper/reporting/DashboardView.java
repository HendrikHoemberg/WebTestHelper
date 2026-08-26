package dev.hendrikhoemberg.webtesthelper.reporting;

import dev.hendrikhoemberg.webtesthelper.findings.OpenFindingCounts;
import dev.hendrikhoemberg.webtesthelper.runner.SystemCapacity;

import java.time.Instant;
import java.util.List;

/**
 * The dashboard screen's read model: the tile grid, the enabled-sites-only totals, the work in
 * flight, and the next time anything fires — null when scheduling is paused (D41).
 */
public record DashboardView(List<SiteTile> tiles, OpenFindingCounts totals, int runsInFlight,
                            Instant nextFireAt, boolean schedulingPaused, SystemCapacity capacity) {
}
