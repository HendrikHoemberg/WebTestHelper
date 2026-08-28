package dev.hendrikhoemberg.webtesthelper.runner;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Tracing;
import dev.hendrikhoemberg.webtesthelper.catalog.SecretText;
import dev.hendrikhoemberg.webtesthelper.checks.CookieBanner;
import dev.hendrikhoemberg.webtesthelper.crawler.BrowserPool;
import dev.hendrikhoemberg.webtesthelper.crawler.ScreenshotNames;
import dev.hendrikhoemberg.webtesthelper.model.JourneyDefinition;
import dev.hendrikhoemberg.webtesthelper.model.JourneyStep;
import dev.hendrikhoemberg.webtesthelper.model.SiteContext;
import dev.hendrikhoemberg.webtesthelper.model.StepAction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Replays a journey against a live site in an isolated browser context (§10.4).
 *
 * <p>Lifecycle rules (§10.4, D106):
 * <ol>
 *   <li>One {@link BrowserPool#submit} per journey replay.</li>
 *   <li>Fresh {@link BrowserContext} per replay with viewport and user agent matching the crawl.</li>
 *   <li>Consent established once via {@link CookieBanner#accept} before the first step and after navigations.</li>
 *   <li>Playwright tracing started at context creation; discarded on success/drift, retained on failure.</li>
 *   <li>Execution is strictly ordered: stops at the first {@link StepStatus#FAILED} step.</li>
 *   <li>On failure, writes a screenshot and trace zip to the artifact directory.</li>
 *   <li>All pages and contexts are closed inside the worker task.</li>
 * </ol>
 */
@Service
public class JourneyReplayer {

    private static final Logger log = LoggerFactory.getLogger(JourneyReplayer.class);

    private final BrowserPool pool;
    private final JourneyValueResolver journeyValueResolver;

    public JourneyReplayer(BrowserPool pool, JourneyValueResolver journeyValueResolver) {
        this.pool = Objects.requireNonNull(pool, "pool");
        this.journeyValueResolver = Objects.requireNonNull(journeyValueResolver, "journeyValueResolver");
    }

    /**
     * Replays the given journey against the target site.
     *
     * @param journey   the journey definition to replay
     * @param site      the site context under test
     * @param artifacts the directory where failure screenshots and traces are written (nullable)
     * @return the result of the replay
     */
    public JourneyReplayResult replay(JourneyDefinition journey, SiteContext site, Path artifacts) {
        Objects.requireNonNull(journey, "journey");
        Objects.requireNonNull(site, "site");

        return pool.submit(browser -> driveJourney(browser, journey, site, artifacts));
    }

    /**
     * Resolves the step's value and executes it.
     *
     * <p>An unresolvable {@code {{cred.…}}} reference — a credential renamed or deleted since the
     * journey was authored — fails <em>the step</em>. Letting it escape would abort the replay
     * with no {@link JourneyReplayResult} at all, so a journey with a stale reference could never
     * be recorded, reported or triaged.
     */
    private StepOutcome executeStep(Page page, JourneyDefinition journey, SiteContext site, JourneyStep step) {
        SecretText value;
        try {
            value = journeyValueResolver.resolve(site.siteId(), step);
        } catch (RuntimeException e) {
            // The message carries the {{cred.…}} token, never the secret behind it (D100).
            log.warn("Journey {} Schritt {}: Zugangsdaten nicht auflösbar: {}",
                    journey.id(), step.ordinal(), e.getMessage());
            return StepOutcome.failed(step.id(), StepExecutor.MSG_CREDENTIAL,
                    List.of(e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()));
        }

        log.debug("Lauf Journey {} Step {}: action={}, val={}",
                journey.id(), step.ordinal(), step.action(), value);
        return StepExecutor.execute(page, step, value.expose());
    }

    private JourneyReplayResult driveJourney(
            Browser browser,
            JourneyDefinition journey,
            SiteContext site,
            Path artifacts
    ) {
        try (BrowserContext context = browser.newContext(new Browser.NewContextOptions()
                .setUserAgent(site.effectiveUserAgent())
                .setViewportSize(1366, 900)
                .setIgnoreHTTPSErrors(true)
                .setLocale("de-DE"))) {

            context.tracing().start(new Tracing.StartOptions()
                    .setScreenshots(true)
                    .setSnapshots(true));
            boolean tracingStopped = false;

            try (Page page = context.newPage()) {
                try {
                    CookieBanner.accept(page, CookieBanner.DISMISSAL_WAIT);
                } catch (RuntimeException e) {
                    log.warn("Cookie-Banner Accept vor Journey {} fehlgeschlagen: {}", journey.id(), e.getMessage());
                }

                List<StepOutcome> outcomes = new ArrayList<>();
                int driftCount = 0;
                boolean failed = false;
                String failureScreenshotName = null;
                String failureTraceName = null;

                for (JourneyStep step : journey.steps()) {
                    StepOutcome outcome = executeStep(page, journey, site, step);
                    outcomes.add(outcome);

                    if (step.action() == StepAction.GOTO && outcome.status() != StepStatus.FAILED) {
                        try {
                            CookieBanner.accept(page, CookieBanner.DISMISSAL_WAIT);
                        } catch (RuntimeException e) {
                            log.warn("Cookie-Banner Accept nach GOTO in Journey {} fehlgeschlagen: {}", journey.id(), e.getMessage());
                        }
                    }

                    if (outcome.drifted()) {
                        driftCount++;
                    }

                    if (outcome.status() == StepStatus.FAILED) {
                        failed = true;
                        if (artifacts != null) {
                            try {
                                Files.createDirectories(artifacts);
                                long jId = journey.id() != null ? journey.id() : 0L;
                                String discriminator = "journey-" + jId + "-step-" + step.ordinal();
                                String sName = ScreenshotNames.screenshotName(site.baseUrl().value(), discriminator);
                                String tName = sName.substring(0, sName.length() - 4) + ".zip";

                                page.screenshot(new Page.ScreenshotOptions()
                                        .setPath(artifacts.resolve(sName))
                                        .setFullPage(true));
                                context.tracing().stop(new Tracing.StopOptions()
                                        .setPath(artifacts.resolve(tName)));
                                tracingStopped = true;

                                failureScreenshotName = sName;
                                failureTraceName = tName;
                            } catch (IOException | RuntimeException e) {
                                log.warn("Fehler beim Erfassen von Screenshot/Trace für Journey {}: {}",
                                        journey.id(), e.getMessage(), e);
                            }
                        }
                        break;
                    }
                }

                if (!tracingStopped) {
                    try {
                        context.tracing().stop();
                        tracingStopped = true;
                    } catch (RuntimeException ignored) {
                    }
                }

                ReplayStatus status;
                if (failed) {
                    status = ReplayStatus.FAILED;
                } else if (driftCount > 0) {
                    status = ReplayStatus.DRIFTED;
                } else {
                    status = ReplayStatus.PASSED;
                }

                long jId = journey.id() != null ? journey.id() : 0L;
                return new JourneyReplayResult(
                        jId,
                        journey.name(),
                        status,
                        outcomes,
                        driftCount,
                        Optional.ofNullable(failureScreenshotName),
                        Optional.ofNullable(failureTraceName)
                );
            } finally {
                if (!tracingStopped) {
                    try {
                        context.tracing().stop();
                    } catch (RuntimeException ignored) {
                    }
                }
            }
        }
    }
}
