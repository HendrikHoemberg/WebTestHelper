package dev.hendrikhoemberg.webtesthelper.checks;

import dev.hendrikhoemberg.webtesthelper.model.CheckFinding;
import dev.hendrikhoemberg.webtesthelper.model.CheckType;
import dev.hendrikhoemberg.webtesthelper.model.PageSnapshot;
import dev.hendrikhoemberg.webtesthelper.model.RunFacts;
import dev.hendrikhoemberg.webtesthelper.model.RunSnapshots;
import dev.hendrikhoemberg.webtesthelper.model.SiteContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

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

    private static final Logger log = LoggerFactory.getLogger(CheckEngine.class);

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
        dropPageErrorsAlreadyReportedAsDeadLinks(findings);
        return findings;
    }

    /**
     * A crawled page that answers 404 is also the target of the dead link that led the crawler
     * there. Both findings name the same URL; the dead link is the more useful one, because it
     * says where the broken link sits. The page-status finding survives when nothing links to the
     * page (for example the start URL) or when the dead-link check is off.
     */
    private static void dropPageErrorsAlreadyReportedAsDeadLinks(List<CheckFinding> findings) {
        Set<String> deadTargets = findings.stream()
                .filter(f -> f.type() == CheckType.DEAD_LINK && DeadLinkCheck.DEAD.equals(f.messageKey()))
                .map(CheckFinding::subjectKey)
                .collect(Collectors.toSet());
        if (deadTargets.isEmpty()) {
            return;
        }
        findings.removeIf(f -> f.type() == CheckType.PAGE_STATUS
                && PageStatusCheck.HTTP_ERROR.equals(f.messageKey())
                && deadTargets.contains(f.subjectKey()));
    }

    /**
     * The site pass: each registered {@link SiteCheck}, filtered by scope ∩ enabled exactly like
     * the page pass, with fault containment per check. The site's base URL stands in for the page
     * URL in the log, because a site check has no single page it was evaluating.
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
                log.error("Site-Prüfung {} für {} fehlgeschlagen: {}",
                        check.type(), site.baseUrl().value(), e.getMessage(), e);
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
                log.error("Prüfung {} auf Seite {} fehlgeschlagen: {}",
                        check.type(), snapshot.url().value(), e.getMessage(), e);
            }
        }
        return findings;
    }
}