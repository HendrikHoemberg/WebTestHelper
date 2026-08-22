package dev.hendrikhoemberg.webtesthelper.checks;

import dev.hendrikhoemberg.webtesthelper.model.CheckFinding;
import dev.hendrikhoemberg.webtesthelper.model.CheckType;
import dev.hendrikhoemberg.webtesthelper.model.PageSnapshot;
import dev.hendrikhoemberg.webtesthelper.model.RunFacts;
import dev.hendrikhoemberg.webtesthelper.model.RunSnapshots;
import dev.hendrikhoemberg.webtesthelper.model.SiteContext;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Runs the applicable page checks over the snapshots a crawl produced (deviation D2: one
 * post-crawl pass, never inline in the crawl loop, so no check can influence crawl order or
 * another check's input).
 *
 * <p>A check applies when the run's scope includes its type <em>and</em> the site has it
 * enabled. Both conditions matter and for different reasons: the scope decides what a pulse is
 * allowed to look at, and it becomes the run's coverage, which is what makes resolution safe
 * (spec 6.4). The site setting is the human's switch.
 *
 * <p>The site arrives as an explicit parameter rather than being read off {@code RunSnapshots},
 * so the same snapshots can be evaluated under different configuration without re-crawling —
 * which is spec 5.2's whole promise.
 */
public final class CheckEngine {

    private final CheckRegistry registry;

    public CheckEngine(CheckRegistry registry) {
        this.registry = registry;
    }

    public List<CheckFinding> evaluateRun(RunSnapshots snapshots, SiteContext site,
            RunFacts facts) {
        List<CheckFinding> findings = new ArrayList<>();
        for (PageSnapshot snapshot : snapshots.snapshots()) {
            findings.addAll(evaluatePage(snapshot, site, facts));
        }
        return findings;
    }

    /**
     * The site pass: each registered {@link SiteCheck}, filtered by scope ∩ enabled exactly like
     * the page pass, with the same failure wrapping. The site's base URL stands in for the page
     * URL in the exception, because a site check has no single page it was evaluating.
     */
    public List<CheckFinding> evaluateSite(RunSnapshots snapshots, SiteContext site, RunFacts facts) {
        List<CheckFinding> findings = new ArrayList<>();
        for (SiteCheck check : registry.siteChecks()) {
            if (!facts.scope().checkTypes().contains(check.type()) || !site.enabled(check.type())) {
                continue;
            }
            CheckConfig config = new CheckConfig(
                    site.severityFor(check.type(), check.defaultSeverity()),
                    site.settingsFor(check.type()), facts);
            try {
                findings.addAll(check.evaluate(snapshots, site, config));
            } catch (RuntimeException e) {
                throw new CheckEvaluationException(check.type(), site.baseUrl().value(), e);
            }
        }
        return findings;
    }

    /**
     * The check types this engine can actually run (spec 6.4): a run's coverage may not claim a
     * check the registry does not implement, or resolving would trust checks that never ran.
     */
    public Set<CheckType> coveredTypes() {
        return registry.coveredTypes();
    }

    public List<CheckFinding> evaluatePage(PageSnapshot snapshot, SiteContext site,
            RunFacts facts) {
        List<CheckFinding> findings = new ArrayList<>();
        for (PageCheck check : registry.pageChecks()) {
            if (!facts.scope().checkTypes().contains(check.type()) || !site.enabled(check.type())) {
                continue;
            }
            CheckConfig config = new CheckConfig(
                    site.severityFor(check.type(), check.defaultSeverity()),
                    site.settingsFor(check.type()), facts);
            try {
                findings.addAll(check.evaluate(snapshot, config));
            } catch (RuntimeException e) {
                throw new CheckEvaluationException(check.type(), snapshot.url().value(), e);
            }
        }
        return findings;
    }
}