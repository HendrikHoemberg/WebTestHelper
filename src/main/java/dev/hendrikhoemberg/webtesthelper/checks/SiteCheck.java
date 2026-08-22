package dev.hendrikhoemberg.webtesthelper.checks;

import dev.hendrikhoemberg.webtesthelper.model.CheckFinding;
import dev.hendrikhoemberg.webtesthelper.model.RunSnapshots;
import dev.hendrikhoemberg.webtesthelper.model.SiteContext;

import java.util.List;

/**
 * A check that needs cross-page knowledge and therefore runs once, after the crawl (spec 5.2).
 * Plan 3b implements the three of them: hreflang reciprocity, sitemap consistency and TLS.
 */
public interface SiteCheck extends CheckDescriptor {

    List<CheckFinding> evaluate(RunSnapshots snapshots, SiteContext site, CheckConfig config);
}