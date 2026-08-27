package dev.hendrikhoemberg.webtesthelper.runner;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.WaitUntilState;
import dev.hendrikhoemberg.webtesthelper.checks.CheckConfig;
import dev.hendrikhoemberg.webtesthelper.checks.CheckRegistry;
import dev.hendrikhoemberg.webtesthelper.checks.CookieBanner;
import dev.hendrikhoemberg.webtesthelper.checks.InteractionCheck;
import dev.hendrikhoemberg.webtesthelper.crawler.BrowserPool;
import dev.hendrikhoemberg.webtesthelper.crawler.CrawlerProperties;
import dev.hendrikhoemberg.webtesthelper.crawler.ScreenshotNames;
import dev.hendrikhoemberg.webtesthelper.model.CheckFinding;
import dev.hendrikhoemberg.webtesthelper.model.CheckType;
import dev.hendrikhoemberg.webtesthelper.model.Evidence;
import dev.hendrikhoemberg.webtesthelper.model.NormalizedUrl;
import dev.hendrikhoemberg.webtesthelper.model.RunFacts;
import dev.hendrikhoemberg.webtesthelper.model.RunSnapshots;
import dev.hendrikhoemberg.webtesthelper.model.SiteContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Executes interaction checks in isolated browser contexts (spec 5.2, 7.2).
 *
 * <p>Lifecycle rules (spec 7.2, D76, D79):
 * <ol>
 *   <li>Grouped by target URL, one {@link BrowserPool#submit} per target.</li>
 *   <li>One {@link BrowserContext} per target with viewport and user-agent matching the crawl.</li>
 *   <li>Setup page establishes consent (evaluating {@code COOKIE_BANNER} or calling {@code CookieBanner.accept}).</li>
 *   <li>Each subsequent check gets a fresh {@link Page} in the same context.</li>
 *   <li>Timeouts and runtime exceptions are caught per check (D79); failed types are absent from {@code drivenTypes}.</li>
 *   <li>Full-page screenshot taken for findings, naming via {@link ScreenshotNames#screenshotName(String, String)}.</li>
 *   <li>All pages and contexts are closed inside the worker task.</li>
 * </ol>
 */
@Component
public class InteractionRunner {

    private static final Logger log = LoggerFactory.getLogger(InteractionRunner.class);

    private final BrowserPool pool;
    private final CheckRegistry registry;
    private final CrawlerProperties crawlerProperties;
    private final InteractionProperties interactionProperties;

    public InteractionRunner(BrowserPool pool,
                             CheckRegistry registry,
                             CrawlerProperties crawlerProperties,
                             InteractionProperties interactionProperties) {
        this.pool = pool;
        this.registry = registry;
        this.crawlerProperties = crawlerProperties;
        this.interactionProperties = interactionProperties;
    }

    public InteractionOutcome run(RunSnapshots snapshots,
                                  SiteContext site,
                                  RunFacts facts,
                                  Path runArtifacts) {
        List<InteractionCheck> activeChecks = registry.interactionChecks().stream()
                .filter(check -> facts.scope().checkTypes().contains(check.type()) && site.enabled(check.type()))
                .toList();

        if (activeChecks.isEmpty()) {
            return InteractionOutcome.NONE;
        }

        // Recorded before anything is driven: a check that fails on every target still has to be
        // known to coverage as an interaction type, or the crawl-scoped resolve will claim it
        // (D74/D79). This set is what the run was allowed to do; the map below is what it did.
        Set<CheckType> candidateTypes = activeChecks.stream()
                .map(InteractionCheck::type)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));

        Map<NormalizedUrl, List<InteractionCheck>> targetsMap = new LinkedHashMap<>();
        for (InteractionCheck check : activeChecks) {
            List<NormalizedUrl> targets = check.targets(snapshots, site, interactionProperties.maxTargets());
            if (targets != null) {
                for (NormalizedUrl target : targets) {
                    if (target != null) {
                        List<InteractionCheck> list = targetsMap.computeIfAbsent(target, k -> new ArrayList<>());
                        if (!list.contains(check)) {
                            list.add(check);
                        }
                    }
                }
            }
        }

        if (targetsMap.isEmpty()) {
            return new InteractionOutcome(List.of(), candidateTypes, Map.of());
        }

        List<CheckFinding> allFindings = new ArrayList<>();
        Map<CheckType, Set<String>> drivenUrlsByType = new EnumMap<>(CheckType.class);

        for (Map.Entry<NormalizedUrl, List<InteractionCheck>> entry : targetsMap.entrySet()) {
            NormalizedUrl targetUrl = entry.getKey();
            List<InteractionCheck> checksForTarget = entry.getValue();

            TargetOutcome targetOutcome = pool.submit(browser ->
                    driveTarget(browser, targetUrl, checksForTarget, site, facts, runArtifacts));

            if (targetOutcome != null) {
                allFindings.addAll(targetOutcome.findings());
                for (CheckType driven : targetOutcome.drivenTypes()) {
                    drivenUrlsByType.computeIfAbsent(driven, k -> new LinkedHashSet<>())
                            .add(targetUrl.value());
                }
            }
        }

        return new InteractionOutcome(allFindings, candidateTypes, drivenUrlsByType);
    }

    private TargetOutcome driveTarget(Browser browser,
                                      NormalizedUrl targetUrl,
                                      List<InteractionCheck> checks,
                                      SiteContext site,
                                      RunFacts facts,
                                      Path runArtifacts) {
        List<CheckFinding> targetFindings = new ArrayList<>();
        Set<CheckType> targetDrivenTypes = new LinkedHashSet<>();

        try (BrowserContext context = browser.newContext(new Browser.NewContextOptions()
                .setUserAgent(site.effectiveUserAgent())
                .setViewportSize(1366, 900)
                .setIgnoreHTTPSErrors(true)
                .setLocale("de-DE"))) {

            Optional<InteractionCheck> cookieBannerCheck = checks.stream()
                    .filter(c -> c.type() == CheckType.COOKIE_BANNER)
                    .findFirst();

            if (cookieBannerCheck.isPresent()) {
                InteractionCheck cbCheck = cookieBannerCheck.get();
                try (Page setupPage = createAndNavigatePage(context, targetUrl)) {
                    executeCheck(setupPage, cbCheck, targetUrl, site, facts, runArtifacts,
                            targetFindings, targetDrivenTypes);
                } catch (RuntimeException e) {
                    log.warn("Lauf {} Check {} auf {} fehlgeschlagen: {}",
                            facts.runId(), cbCheck.type(), targetUrl.value(), e.getMessage(), e);
                }
            } else {
                try (Page setupPage = createAndNavigatePage(context, targetUrl)) {
                    CookieBanner.accept(setupPage, Duration.ofSeconds(3));
                } catch (RuntimeException e) {
                    log.warn("Lauf {} Cookie-Banner Setup auf {} fehlgeschlagen: {}",
                            facts.runId(), targetUrl.value(), e.getMessage(), e);
                }
            }

            for (InteractionCheck check : checks) {
                if (check.type() == CheckType.COOKIE_BANNER) {
                    continue;
                }
                try (Page page = createAndNavigatePage(context, targetUrl)) {
                    executeCheck(page, check, targetUrl, site, facts, runArtifacts,
                            targetFindings, targetDrivenTypes);
                } catch (RuntimeException e) {
                    log.warn("Lauf {} Check {} auf {} fehlgeschlagen: {}",
                            facts.runId(), check.type(), targetUrl.value(), e.getMessage(), e);
                }
            }
        }

        return new TargetOutcome(targetFindings, targetDrivenTypes);
    }

    private Page createAndNavigatePage(BrowserContext context, NormalizedUrl targetUrl) {
        Page page = context.newPage();
        try {
            page.setDefaultTimeout(interactionProperties.timeout().toMillis());
            page.setDefaultNavigationTimeout(crawlerProperties.navigationTimeout().toMillis());
            page.navigate(targetUrl.value(), new Page.NavigateOptions()
                    .setTimeout(crawlerProperties.navigationTimeout().toMillis())
                    .setWaitUntil(WaitUntilState.LOAD));
            return page;
        } catch (RuntimeException e) {
            page.close();
            throw e;
        }
    }

    private void executeCheck(Page page,
                              InteractionCheck check,
                              NormalizedUrl targetUrl,
                              SiteContext site,
                              RunFacts facts,
                              Path runArtifacts,
                              List<CheckFinding> targetFindings,
                              Set<CheckType> targetDrivenTypes) {
        CheckConfig config = new CheckConfig(
                site.severityFor(check.type(), check.defaultSeverity()),
                site.settingsFor(check.type()),
                facts);

        long start = System.nanoTime();
        List<CheckFinding> rawFindings = check.evaluate(page, site, config);
        long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

        if (elapsedMillis > interactionProperties.timeout().toMillis()) {
            log.warn("Lauf {} Check {} auf {} überschritt Zeitlimit ({} ms > {} ms)",
                    facts.runId(), check.type(), targetUrl.value(), elapsedMillis,
                    interactionProperties.timeout().toMillis());
            return;
        }

        targetDrivenTypes.add(check.type());
        if (rawFindings != null && !rawFindings.isEmpty()) {
            String screenshotName = captureScreenshot(page, targetUrl, check.type(), runArtifacts);
            targetFindings.addAll(enrichFindings(rawFindings, screenshotName));
        }
    }

    private String captureScreenshot(Page page,
                                     NormalizedUrl targetUrl,
                                     CheckType checkType,
                                     Path runArtifacts) {
        if (runArtifacts == null) {
            return null;
        }
        try {
            Files.createDirectories(runArtifacts);
            String name = ScreenshotNames.screenshotName(targetUrl.value(), checkType.name());
            page.screenshot(new Page.ScreenshotOptions()
                    .setPath(runArtifacts.resolve(name))
                    .setFullPage(true));
            return name;
        } catch (IOException | RuntimeException e) {
            log.warn("Screenshot für {} auf {} fehlgeschlagen: {}", checkType, targetUrl.value(), e.getMessage(), e);
            return null;
        }
    }

    private List<CheckFinding> enrichFindings(List<CheckFinding> rawFindings, String screenshotName) {
        List<CheckFinding> enriched = new ArrayList<>(rawFindings.size());
        for (CheckFinding finding : rawFindings) {
            Evidence oldEvidence = finding.evidence();
            Evidence newEvidence = new Evidence(
                    screenshotName,
                    oldEvidence != null ? oldEvidence.httpStatus() : null,
                    oldEvidence != null ? oldEvidence.requestDetail() : null,
                    oldEvidence != null ? oldEvidence.responseDetail() : null,
                    oldEvidence != null ? oldEvidence.consoleExcerpt() : List.of()
            );
            enriched.add(new CheckFinding(
                    finding.type(),
                    finding.severity(),
                    finding.subjectKey(),
                    finding.observedOn(),
                    finding.messageKey(),
                    finding.messageArgs(),
                    newEvidence
            ));
        }
        return enriched;
    }

    private record TargetOutcome(List<CheckFinding> findings, Set<CheckType> drivenTypes) {
    }
}
