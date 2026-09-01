package dev.hendrikhoemberg.webtesthelper.runner;

import com.microsoft.playwright.Page;
import dev.hendrikhoemberg.webtesthelper.checks.CheckAbstainedException;
import dev.hendrikhoemberg.webtesthelper.checks.CheckConfig;
import dev.hendrikhoemberg.webtesthelper.checks.CheckRegistry;
import dev.hendrikhoemberg.webtesthelper.checks.CookieBannerCheck;
import dev.hendrikhoemberg.webtesthelper.checks.InteractionCheck;
import dev.hendrikhoemberg.webtesthelper.checks.InteractionTargets;
import dev.hendrikhoemberg.webtesthelper.crawler.BrowserPool;
import dev.hendrikhoemberg.webtesthelper.crawler.CrawlerProperties;
import dev.hendrikhoemberg.webtesthelper.model.CheckFinding;
import dev.hendrikhoemberg.webtesthelper.model.CheckSetting;
import dev.hendrikhoemberg.webtesthelper.model.CheckType;
import dev.hendrikhoemberg.webtesthelper.model.CrawlBudget;
import dev.hendrikhoemberg.webtesthelper.model.Evidence;
import dev.hendrikhoemberg.webtesthelper.model.NormalizedUrl;
import dev.hendrikhoemberg.webtesthelper.model.PageSnapshot;
import dev.hendrikhoemberg.webtesthelper.model.RunFacts;
import dev.hendrikhoemberg.webtesthelper.model.RunScope;
import dev.hendrikhoemberg.webtesthelper.model.RunSnapshots;
import dev.hendrikhoemberg.webtesthelper.model.Severity;
import dev.hendrikhoemberg.webtesthelper.model.SiteContext;
import dev.hendrikhoemberg.webtesthelper.model.SoftNotFoundProbe;
import dev.hendrikhoemberg.webtesthelper.model.UrlNormalizer;
import dev.hendrikhoemberg.webtesthelper.support.FixtureSite;
import dev.hendrikhoemberg.webtesthelper.support.Snapshots;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("browser")
@org.junit.jupiter.api.parallel.ResourceLock("browser")
class InteractionRunnerTest {

    private static FixtureSite fixtureSite;
    private static BrowserPool pool;
    private static CrawlerProperties crawlerProperties;

    @BeforeAll
    static void start(@TempDir Path tempDir) {
        fixtureSite = FixtureSite.start();
        crawlerProperties = new CrawlerProperties(1, 10, Duration.ofSeconds(5),
                Duration.ZERO, tempDir, true, false);
        pool = new BrowserPool(crawlerProperties);
    }

    @AfterAll
    static void stop() {
        if (pool != null) {
            pool.close();
        }
        if (fixtureSite != null) {
            fixtureSite.close();
        }
    }

    private static Map<CheckType, CheckSetting> allEnabled() {
        Map<CheckType, CheckSetting> settings = new EnumMap<>(CheckType.class);
        for (CheckType type : CheckType.values()) {
            settings.put(type, CheckSetting.defaultEnabled());
        }
        return settings;
    }

    private SiteContext siteContext(NormalizedUrl baseUrl) {
        return new SiteContext(1L, "Fixture", baseUrl,
                CrawlBudget.DEFAULT, List.of(), List.of(), List.of(), true, null, allEnabled());
    }

    @Test
    void stubbornBannerProducesFindingWithScreenshotAndCoverage(@TempDir Path tempArtifacts) {
        String url = fixtureSite.url("interaktiv/banner-hartnaeckig.html");
        NormalizedUrl home = Snapshots.url(url);
        SiteContext site = siteContext(home);
        PageSnapshot homeSnapshot = Snapshots.page(url).build();
        RunSnapshots snapshots = new RunSnapshots(1L, site, List.of(homeSnapshot), SoftNotFoundProbe.NONE);
        RunFacts facts = RunFacts.of(snapshots, RunScope.FULL, Instant.now());
        CheckRegistry registry = new CheckRegistry(List.of(), List.of(), List.of(new CookieBannerCheck()));
        InteractionRunner runner = new InteractionRunner(pool, registry, crawlerProperties,
                new InteractionProperties(3, Duration.ofSeconds(10)));

        InteractionOutcome outcome = runner.run(snapshots, site, facts, tempArtifacts);

        assertThat(outcome.findings()).hasSize(1);
        CheckFinding finding = outcome.findings().get(0);
        assertThat(finding.type()).isEqualTo(CheckType.COOKIE_BANNER);
        assertThat(finding.evidence().screenshotPath()).isNotNull();
        assertThat(tempArtifacts.resolve(finding.evidence().screenshotPath())).exists();
        assertThat(outcome.drivenTypes()).containsExactly(CheckType.COOKIE_BANNER);
        assertThat(outcome.drivenUrls()).containsExactly(home.value());
    }

    @Test
    void fakeCheckThatThrowsDoesNotFailRunnerAndLeavesTypeOutOfDrivenTypes(@TempDir Path tempArtifacts) {
        String url = fixtureSite.url("interaktiv/ohne-banner.html");
        NormalizedUrl home = Snapshots.url(url);
        SiteContext site = siteContext(home);
        PageSnapshot homeSnapshot = Snapshots.page(url).build();
        RunSnapshots snapshots = new RunSnapshots(1L, site, List.of(homeSnapshot), SoftNotFoundProbe.NONE);
        RunFacts facts = RunFacts.of(snapshots, RunScope.FULL, Instant.now());

        InteractionCheck throwingCheck = new InteractionCheck() {
            @Override
            public CheckType type() {
                return CheckType.MIXED_CONTENT;
            }

            @Override
            public Severity defaultSeverity() {
                return Severity.WARN;
            }

            @Override
            public Set<String> messageKeys() {
                return Set.of();
            }

            @Override
            public List<NormalizedUrl> targets(RunSnapshots s, SiteContext sc, int max) {
                return InteractionTargets.homepage(s, sc);
            }

            @Override
            public List<CheckFinding> evaluate(Page page, SiteContext sc, CheckConfig config) {
                throw new RuntimeException("Simulated crash in interaction check");
            }
        };

        CheckRegistry registry = new CheckRegistry(List.of(), List.of(),
                List.of(new CookieBannerCheck(), throwingCheck));
        InteractionRunner runner = new InteractionRunner(pool, registry, crawlerProperties,
                new InteractionProperties(3, Duration.ofSeconds(10)));

        InteractionOutcome outcome = runner.run(snapshots, site, facts, tempArtifacts);

        assertThat(outcome.findings()).isEmpty();
        assertThat(outcome.drivenTypes()).containsExactly(CheckType.COOKIE_BANNER);
        assertThat(outcome.drivenTypes()).doesNotContain(CheckType.MIXED_CONTENT);
        assertThat(outcome.drivenUrls()).containsExactly(home.value());
    }

    /**
     * D86: A check that cannot judge a page throws {@link CheckAbstainedException}. The runner
     * logs at INFO (unlike unhandled RuntimeExceptions logged at WARN) and excludes the check from
     * drivenTypes while preserving candidateTypes, without failing the run.
     */
    @Test
    void fakeCheckThatAbstainsLeavesTypeOutOfDrivenTypesWhilePreservingCandidateTypes(@TempDir Path tempArtifacts) {
        String url = fixtureSite.url("interaktiv/ohne-banner.html");
        NormalizedUrl home = Snapshots.url(url);
        SiteContext site = siteContext(home);
        PageSnapshot homeSnapshot = Snapshots.page(url).build();
        RunSnapshots snapshots = new RunSnapshots(1L, site, List.of(homeSnapshot), SoftNotFoundProbe.NONE);
        RunFacts facts = RunFacts.of(snapshots, RunScope.FULL, Instant.now());

        InteractionCheck abstainingCheck = new InteractionCheck() {
            @Override
            public CheckType type() {
                return CheckType.LANGUAGE_SWITCHER;
            }

            @Override
            public Severity defaultSeverity() {
                return Severity.ERROR;
            }

            @Override
            public Set<String> messageKeys() {
                return Set.of();
            }

            @Override
            public List<NormalizedUrl> targets(RunSnapshots s, SiteContext sc, int max) {
                return InteractionTargets.homepage(s, sc);
            }

            @Override
            public List<CheckFinding> evaluate(Page page, SiteContext sc, CheckConfig config) {
                throw new CheckAbstainedException(type(), page.url(), "Page DOM did not settle");
            }
        };

        CheckRegistry registry = new CheckRegistry(List.of(), List.of(),
                List.of(new CookieBannerCheck(), abstainingCheck));
        InteractionRunner runner = new InteractionRunner(pool, registry, crawlerProperties,
                new InteractionProperties(3, Duration.ofSeconds(10)));

        InteractionOutcome outcome = runner.run(snapshots, site, facts, tempArtifacts);

        assertThat(outcome.findings()).isEmpty();
        assertThat(outcome.drivenTypes()).containsExactly(CheckType.COOKIE_BANNER);
        assertThat(outcome.drivenTypes()).doesNotContain(CheckType.LANGUAGE_SWITCHER);
        assertThat(outcome.candidateTypes()).containsExactlyInAnyOrder(CheckType.COOKIE_BANNER, CheckType.LANGUAGE_SWITCHER);
        assertThat(outcome.drivenUrls()).containsExactly(home.value());
    }

    @Test
    void fakeCheckThatSleepsPastTimeoutIsDroppedFromDrivenTypes(@TempDir Path tempArtifacts) {
        String url = fixtureSite.url("interaktiv/ohne-banner.html");
        NormalizedUrl home = Snapshots.url(url);
        SiteContext site = siteContext(home);
        PageSnapshot homeSnapshot = Snapshots.page(url).build();
        RunSnapshots snapshots = new RunSnapshots(1L, site, List.of(homeSnapshot), SoftNotFoundProbe.NONE);
        RunFacts facts = RunFacts.of(snapshots, RunScope.FULL, Instant.now());

        InteractionCheck sleepingCheck = new InteractionCheck() {
            @Override
            public CheckType type() {
                return CheckType.CONSOLE_ERRORS;
            }

            @Override
            public Severity defaultSeverity() {
                return Severity.WARN;
            }

            @Override
            public Set<String> messageKeys() {
                return Set.of();
            }

            @Override
            public List<NormalizedUrl> targets(RunSnapshots s, SiteContext sc, int max) {
                return InteractionTargets.homepage(s, sc);
            }

            @Override
            public List<CheckFinding> evaluate(Page page, SiteContext sc, CheckConfig config) {
                try {
                    Thread.sleep(400);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return List.of(new CheckFinding(type(), Severity.WARN, "test",
                        UrlNormalizer.normalize(page.url()).orElse(null), "msg", List.of(), Evidence.NONE));
            }
        };

        CheckRegistry registry = new CheckRegistry(List.of(), List.of(),
                List.of(new CookieBannerCheck(), sleepingCheck));
        InteractionRunner runner = new InteractionRunner(pool, registry, crawlerProperties,
                new InteractionProperties(3, Duration.ofMillis(150)));

        InteractionOutcome outcome = runner.run(snapshots, site, facts, tempArtifacts);

        assertThat(outcome.findings()).isEmpty();
        assertThat(outcome.drivenTypes()).contains(CheckType.COOKIE_BANNER);
        assertThat(outcome.drivenTypes()).doesNotContain(CheckType.CONSOLE_ERRORS);
    }

    @Test
    void fakeCheckWithCustomTimeoutKeepsFindingsWhenSleepingWithinItsOwnBudget(@TempDir Path tempArtifacts) {
        String url = fixtureSite.url("interaktiv/ohne-banner.html");
        NormalizedUrl home = Snapshots.url(url);
        SiteContext site = siteContext(home);
        PageSnapshot homeSnapshot = Snapshots.page(url).build();
        RunSnapshots snapshots = new RunSnapshots(1L, site, List.of(homeSnapshot), SoftNotFoundProbe.NONE);
        RunFacts facts = RunFacts.of(snapshots, RunScope.FULL, Instant.now());

        InteractionCheck longRunningCheck = new InteractionCheck() {
            @Override
            public CheckType type() {
                return CheckType.CONTACT_FORM;
            }

            @Override
            public Severity defaultSeverity() {
                return Severity.ERROR;
            }

            @Override
            public Set<String> messageKeys() {
                return Set.of();
            }

            @Override
            public Duration timeout() {
                return Duration.ofSeconds(5);
            }

            @Override
            public List<NormalizedUrl> targets(RunSnapshots s, SiteContext sc, int max) {
                return InteractionTargets.homepage(s, sc);
            }

            @Override
            public List<CheckFinding> evaluate(Page page, SiteContext sc, CheckConfig config) {
                try {
                    Thread.sleep(300);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return List.of(new CheckFinding(type(), Severity.ERROR, "test",
                        UrlNormalizer.normalize(page.url()).orElse(null), "msg", List.of(), Evidence.NONE));
            }
        };

        CheckRegistry registry = new CheckRegistry(List.of(), List.of(),
                List.of(new CookieBannerCheck(), longRunningCheck));
        // Default timeout is 150ms, which would drop longRunningCheck without the timeout() override
        InteractionRunner runner = new InteractionRunner(pool, registry, crawlerProperties,
                new InteractionProperties(3, Duration.ofMillis(150)));

        InteractionOutcome outcome = runner.run(snapshots, site, facts, tempArtifacts);

        assertThat(outcome.findings()).hasSize(1);
        assertThat(outcome.drivenTypes()).containsExactlyInAnyOrder(CheckType.COOKIE_BANNER, CheckType.CONTACT_FORM);
    }

    @Test
    void fakeCheckRunningAfterCookieBannerCheckInSameContextSeesNoBanner(@TempDir Path tempArtifacts) {
        String url = fixtureSite.url("interaktiv/banner.html");
        NormalizedUrl home = Snapshots.url(url);
        SiteContext site = siteContext(home);
        PageSnapshot homeSnapshot = Snapshots.page(url).build();
        RunSnapshots snapshots = new RunSnapshots(1L, site, List.of(homeSnapshot), SoftNotFoundProbe.NONE);
        RunFacts facts = RunFacts.of(snapshots, RunScope.FULL, Instant.now());

        AtomicBoolean bannerVisibleInFollowUpCheck = new AtomicBoolean(true);

        InteractionCheck followUpCheck = new InteractionCheck() {
            @Override
            public CheckType type() {
                return CheckType.IFRAME_EMBED;
            }

            @Override
            public Severity defaultSeverity() {
                return Severity.INFO;
            }

            @Override
            public Set<String> messageKeys() {
                return Set.of();
            }

            @Override
            public List<NormalizedUrl> targets(RunSnapshots s, SiteContext sc, int max) {
                return InteractionTargets.homepage(s, sc);
            }

            @Override
            public List<CheckFinding> evaluate(Page page, SiteContext sc, CheckConfig config) {
                boolean visible = page.locator("[data-wth-banner]").isVisible();
                bannerVisibleInFollowUpCheck.set(visible);
                return List.of();
            }
        };

        CheckRegistry registry = new CheckRegistry(List.of(), List.of(),
                List.of(new CookieBannerCheck(), followUpCheck));
        InteractionRunner runner = new InteractionRunner(pool, registry, crawlerProperties,
                new InteractionProperties(3, Duration.ofSeconds(10)));

        InteractionOutcome outcome = runner.run(snapshots, site, facts, tempArtifacts);

        assertThat(bannerVisibleInFollowUpCheck.get()).isFalse();
        assertThat(outcome.drivenTypes()).containsExactlyInAnyOrder(CheckType.COOKIE_BANNER, CheckType.IFRAME_EMBED);
        assertThat(outcome.drivenUrls()).containsExactly(home.value());
    }

    @Test
    void fakeCheckWithoutCookieBannerCheckDirectlyDismissesBannerOnSetupPage(@TempDir Path tempArtifacts) {
        String url = fixtureSite.url("interaktiv/banner.html");
        NormalizedUrl home = Snapshots.url(url);
        SiteContext site = siteContext(home);
        PageSnapshot homeSnapshot = Snapshots.page(url).build();
        RunSnapshots snapshots = new RunSnapshots(1L, site, List.of(homeSnapshot), SoftNotFoundProbe.NONE);
        RunFacts facts = RunFacts.of(snapshots, RunScope.FULL, Instant.now());

        AtomicBoolean bannerVisibleInFollowUpCheck = new AtomicBoolean(true);

        InteractionCheck followUpCheck = new InteractionCheck() {
            @Override
            public CheckType type() {
                return CheckType.IFRAME_EMBED;
            }

            @Override
            public Severity defaultSeverity() {
                return Severity.INFO;
            }

            @Override
            public Set<String> messageKeys() {
                return Set.of();
            }

            @Override
            public List<NormalizedUrl> targets(RunSnapshots s, SiteContext sc, int max) {
                return InteractionTargets.homepage(s, sc);
            }

            @Override
            public List<CheckFinding> evaluate(Page page, SiteContext sc, CheckConfig config) {
                boolean visible = page.locator("[data-wth-banner]").isVisible();
                bannerVisibleInFollowUpCheck.set(visible);
                return List.of();
            }
        };

        CheckRegistry registry = new CheckRegistry(List.of(), List.of(), List.of(followUpCheck));
        InteractionRunner runner = new InteractionRunner(pool, registry, crawlerProperties,
                new InteractionProperties(3, Duration.ofSeconds(10)));

        InteractionOutcome outcome = runner.run(snapshots, site, facts, tempArtifacts);

        assertThat(bannerVisibleInFollowUpCheck.get()).isFalse();
        assertThat(outcome.drivenTypes()).containsExactly(CheckType.IFRAME_EMBED);
        assertThat(outcome.drivenUrls()).containsExactly(home.value());
    }

    @Test
    void disabledCheckIsSkipped(@TempDir Path tempArtifacts) {
        String url = fixtureSite.url("interaktiv/banner-hartnaeckig.html");
        NormalizedUrl home = Snapshots.url(url);
        Map<CheckType, CheckSetting> settings = new EnumMap<>(CheckType.class);
        settings.put(CheckType.COOKIE_BANNER, new CheckSetting(false, null, Map.of()));
        SiteContext site = new SiteContext(1L, "Fixture", home,
                CrawlBudget.DEFAULT, List.of(), List.of(), List.of(), true, null, settings);
        PageSnapshot homeSnapshot = Snapshots.page(url).build();
        RunSnapshots snapshots = new RunSnapshots(1L, site, List.of(homeSnapshot), SoftNotFoundProbe.NONE);
        RunFacts facts = RunFacts.of(snapshots, RunScope.FULL, Instant.now());

        CheckRegistry registry = new CheckRegistry(List.of(), List.of(), List.of(new CookieBannerCheck()));
        InteractionRunner runner = new InteractionRunner(pool, registry, crawlerProperties,
                new InteractionProperties(3, Duration.ofSeconds(10)));

        InteractionOutcome outcome = runner.run(snapshots, site, facts, tempArtifacts);

        assertThat(outcome.findings()).isEmpty();
        assertThat(outcome.drivenTypes()).isEmpty();
        assertThat(outcome.drivenUrls()).isEmpty();
    }

    @Test
    void unreachableTargetDoesNotCrashRunner(@TempDir Path tempArtifacts) {
        // Invalid host that cannot be connected to
        NormalizedUrl unreachable = Snapshots.url("http://127.0.0.1:9/unreachable");
        SiteContext site = siteContext(unreachable);
        PageSnapshot homeSnapshot = Snapshots.page(unreachable.value()).build();
        RunSnapshots snapshots = new RunSnapshots(1L, site, List.of(homeSnapshot), SoftNotFoundProbe.NONE);
        RunFacts facts = RunFacts.of(snapshots, RunScope.FULL, Instant.now());

        CheckRegistry registry = new CheckRegistry(List.of(), List.of(), List.of(new CookieBannerCheck()));
        // Short navigation timeout for the unreachable test
        CrawlerProperties shortProperties = new CrawlerProperties(1, 10, Duration.ofMillis(500),
                Duration.ZERO, tempArtifacts, true, false);
        InteractionRunner runner = new InteractionRunner(pool, registry, shortProperties,
                new InteractionProperties(3, Duration.ofMillis(500)));

        InteractionOutcome outcome = runner.run(snapshots, site, facts, tempArtifacts);

        assertThat(outcome.findings()).isEmpty();
        assertThat(outcome.drivenTypes()).isEmpty();
        assertThat(outcome.drivenUrls()).isEmpty();
        // D74/D79: the check was in scope and enabled, it simply could not see. Coverage still has
        // to know it is an interaction type, or the crawl-scoped resolve will claim its findings.
        assertThat(outcome.candidateTypes()).containsExactly(CheckType.COOKIE_BANNER);
    }
}
