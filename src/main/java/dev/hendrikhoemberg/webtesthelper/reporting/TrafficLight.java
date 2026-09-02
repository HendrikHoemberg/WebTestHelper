package dev.hendrikhoemberg.webtesthelper.reporting;

import dev.hendrikhoemberg.webtesthelper.findings.OpenFindingCounts;
import dev.hendrikhoemberg.webtesthelper.model.RunStatus;
import dev.hendrikhoemberg.webtesthelper.runner.LastRun;

/**
 * The dashboard's per-site health signal (spec 14). A pure function — no Spring, no state — so it
 * can be a table of test cases. Precedence is fixed: disabled wins over everything, a failure or
 * an open error beats a warning, and a warning, partial coverage or the absence of any terminal run
 * beats green. It never re-decides what {@code openCountsBySite()} already filtered: a silenced
 * error excluded upstream is simply absent from {@code counts}, so the light sees no error to be
 * red about (D62).
 */
public enum TrafficLight {
    GRUEN, GELB, ROT, GRAU, NEU;

    public static TrafficLight of(boolean siteEnabled, LastRun lastRun, OpenFindingCounts counts) {
        if (!siteEnabled) {
            return GRAU;
        }
        if (lastRun == null) {
            return NEU;
        }
        if (lastRun.status() == RunStatus.FAILED) {
            return ROT;
        }
        if (counts.errors() > 0) {
            return ROT;
        }
        if (counts.warnings() > 0) {
            return GELB;
        }
        if (lastRun.partialCoverage()) {
            return GELB;
        }
        return GRUEN;
    }
}
