package dev.hendrikhoemberg.webtesthelper.checks;

import dev.hendrikhoemberg.webtesthelper.model.CheckFinding;
import dev.hendrikhoemberg.webtesthelper.model.CheckSetting;
import dev.hendrikhoemberg.webtesthelper.model.CheckType;
import dev.hendrikhoemberg.webtesthelper.model.CrawlBudget;
import dev.hendrikhoemberg.webtesthelper.model.Evidence;
import dev.hendrikhoemberg.webtesthelper.model.PageSnapshot;
import dev.hendrikhoemberg.webtesthelper.model.RunFacts;
import dev.hendrikhoemberg.webtesthelper.model.RunScope;
import dev.hendrikhoemberg.webtesthelper.model.RunSnapshots;
import dev.hendrikhoemberg.webtesthelper.model.Severity;
import dev.hendrikhoemberg.webtesthelper.model.SiteContext;
import dev.hendrikhoemberg.webtesthelper.model.SoftNotFoundProbe;
import dev.hendrikhoemberg.webtesthelper.model.TlsCertificateFact;
import dev.hendrikhoemberg.webtesthelper.model.UrlVerifications;
import dev.hendrikhoemberg.webtesthelper.support.Snapshots;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ConcurrentModificationException;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Empirical adversarial stress test for E1 (CheckEngine fault isolation).
 */
class CheckEngineEmpiricalTest {

    private static SiteContext site(Map<CheckType, CheckSetting> settings) {
        return new SiteContext(1L, "Beispiel", Snapshots.url("https://example.com/"),
                CrawlBudget.DEFAULT, List.of(), List.of(), List.of(), true, null, settings);
    }

    private static Map<CheckType, CheckSetting> allEnabled() {
        Map<CheckType, CheckSetting> settings = new EnumMap<>(CheckType.class);
        for (CheckType type : CheckType.values()) {
            settings.put(type, CheckSetting.defaultEnabled());
        }
        return settings;
    }

    private static RunFacts facts(RunScope scope) {
        return new RunFacts(1L, scope, Instant.EPOCH, SoftNotFoundProbe.NONE,
                UrlVerifications.EMPTY, TlsCertificateFact.NONE, List.of());
    }

    private static PageSnapshot page(String url) {
        return Snapshots.page(url).status(200).build();
    }

    @Test
    @DisplayName("E1: CheckEngine isolates NullPointerException and preserves other findings")
    void isolatesNullPointerExceptionAndPreservesOtherFindings() {
        CheckFinding finding1 = new CheckFinding(CheckType.PAGE_STATUS, Severity.ERROR,
                "status:200:/1", Snapshots.url("https://example.com/1"), "finding.PAGE_STATUS.x", List.of(), Evidence.NONE);
        CheckFinding finding3 = new CheckFinding(CheckType.CONSOLE_ERRORS, Severity.WARN,
                "console:error", Snapshots.url("https://example.com/1"), "finding.CONSOLE.x", List.of(), Evidence.NONE);

        CheckEngine engine = new CheckEngine(new CheckRegistry(List.of(
                new PageCheck() {
                    @Override public CheckType type() { return CheckType.PAGE_STATUS; }
                    @Override public Severity defaultSeverity() { return Severity.ERROR; }
                    @Override public Set<String> messageKeys() { return Set.of(); }
                    @Override public List<CheckFinding> evaluate(PageSnapshot s, CheckConfig c) {
                        return List.of(finding1);
                    }
                },
                new PageCheck() {
                    @Override public CheckType type() { return CheckType.IMAGE_BROKEN; }
                    @Override public Severity defaultSeverity() { return Severity.ERROR; }
                    @Override public Set<String> messageKeys() { return Set.of(); }
                    @Override public List<CheckFinding> evaluate(PageSnapshot s, CheckConfig c) {
                        throw new NullPointerException("Simulated NPE on element access");
                    }
                },
                new PageCheck() {
                    @Override public CheckType type() { return CheckType.CONSOLE_ERRORS; }
                    @Override public Severity defaultSeverity() { return Severity.WARN; }
                    @Override public Set<String> messageKeys() { return Set.of(); }
                    @Override public List<CheckFinding> evaluate(PageSnapshot s, CheckConfig c) {
                        return List.of(finding3);
                    }
                }
        ), List.of()));

        List<CheckFinding> findings = engine.evaluatePage(page("https://example.com/1"), site(allEnabled()), facts(RunScope.FULL));

        assertThat(findings)
                .as("Findings from healthy checks should be preserved in order despite throwing check")
                .containsExactly(finding1, finding3);
    }

    @Test
    @DisplayName("E1: CheckEngine isolates multiple different RuntimeExceptions in sequence")
    void isolatesMultipleDifferentRuntimeExceptions() {
        CheckFinding findingA = new CheckFinding(CheckType.PAGE_STATUS, Severity.ERROR,
                "findingA", Snapshots.url("https://example.com/test"), "keyA", List.of(), Evidence.NONE);
        CheckFinding findingB = new CheckFinding(CheckType.REDIRECT_CHAIN, Severity.INFO,
                "findingB", Snapshots.url("https://example.com/test"), "keyB", List.of(), Evidence.NONE);
        CheckFinding findingC = new CheckFinding(CheckType.MEDIA_PLAYABLE, Severity.WARN,
                "findingC", Snapshots.url("https://example.com/test"), "keyC", List.of(), Evidence.NONE);

        CheckEngine engine = new CheckEngine(new CheckRegistry(List.of(
                new PageCheck() {
                    @Override public CheckType type() { return CheckType.PAGE_STATUS; }
                    @Override public Severity defaultSeverity() { return Severity.ERROR; }
                    @Override public Set<String> messageKeys() { return Set.of(); }
                    @Override public List<CheckFinding> evaluate(PageSnapshot s, CheckConfig c) {
                        return List.of(findingA);
                    }
                },
                new PageCheck() {
                    @Override public CheckType type() { return CheckType.IMAGE_BROKEN; }
                    @Override public Severity defaultSeverity() { return Severity.ERROR; }
                    @Override public Set<String> messageKeys() { return Set.of(); }
                    @Override public List<CheckFinding> evaluate(PageSnapshot s, CheckConfig c) {
                        throw new IllegalArgumentException("Bad argument in check");
                    }
                },
                new PageCheck() {
                    @Override public CheckType type() { return CheckType.DEAD_LINK; }
                    @Override public Severity defaultSeverity() { return Severity.WARN; }
                    @Override public Set<String> messageKeys() { return Set.of(); }
                    @Override public List<CheckFinding> evaluate(PageSnapshot s, CheckConfig c) {
                        throw new IndexOutOfBoundsException("Out of bounds in check");
                    }
                },
                new PageCheck() {
                    @Override public CheckType type() { return CheckType.REDIRECT_CHAIN; }
                    @Override public Severity defaultSeverity() { return Severity.INFO; }
                    @Override public Set<String> messageKeys() { return Set.of(); }
                    @Override public List<CheckFinding> evaluate(PageSnapshot s, CheckConfig c) {
                        return List.of(findingB);
                    }
                },
                new PageCheck() {
                    @Override public CheckType type() { return CheckType.IFRAME_EMBED; }
                    @Override public Severity defaultSeverity() { return Severity.ERROR; }
                    @Override public Set<String> messageKeys() { return Set.of(); }
                    @Override public List<CheckFinding> evaluate(PageSnapshot s, CheckConfig c) {
                        throw new ConcurrentModificationException("Concurrent modification in check");
                    }
                },
                new PageCheck() {
                    @Override public CheckType type() { return CheckType.MEDIA_PLAYABLE; }
                    @Override public Severity defaultSeverity() { return Severity.WARN; }
                    @Override public Set<String> messageKeys() { return Set.of(); }
                    @Override public List<CheckFinding> evaluate(PageSnapshot s, CheckConfig c) {
                        return List.of(findingC);
                    }
                }
        ), List.of()));

        List<CheckFinding> findings = engine.evaluatePage(page("https://example.com/test"), site(allEnabled()), facts(RunScope.FULL));

        assertThat(findings)
                .as("All healthy findings should survive interleaved runtime exceptions")
                .containsExactly(findingA, findingB, findingC);
    }

    @Test
    @DisplayName("E1: Check returning null does not abort evaluation")
    void checkReturningNullDoesNotAbortEvaluation() {
        CheckFinding healthyFinding = new CheckFinding(CheckType.PAGE_STATUS, Severity.ERROR,
                "healthy", Snapshots.url("https://example.com/x"), "key", List.of(), Evidence.NONE);

        CheckEngine engine = new CheckEngine(new CheckRegistry(List.of(
                new PageCheck() {
                    @Override public CheckType type() { return CheckType.IMAGE_BROKEN; }
                    @Override public Severity defaultSeverity() { return Severity.ERROR; }
                    @Override public Set<String> messageKeys() { return Set.of(); }
                    @Override public List<CheckFinding> evaluate(PageSnapshot s, CheckConfig c) {
                        // Returning null would cause findings.addAll(null) -> NPE
                        return null;
                    }
                },
                new PageCheck() {
                    @Override public CheckType type() { return CheckType.PAGE_STATUS; }
                    @Override public Severity defaultSeverity() { return Severity.ERROR; }
                    @Override public Set<String> messageKeys() { return Set.of(); }
                    @Override public List<CheckFinding> evaluate(PageSnapshot s, CheckConfig c) {
                        return List.of(healthyFinding);
                    }
                }
        ), List.of()));

        List<CheckFinding> findings = engine.evaluatePage(page("https://example.com/x"), site(allEnabled()), facts(RunScope.FULL));

        assertThat(findings).containsExactly(healthyFinding);
    }

    @Test
    @DisplayName("E1: Fault isolation in evaluateSite across multiple throwing SiteChecks")
    void faultIsolationInEvaluateSite() {
        CheckFinding finding1 = new CheckFinding(CheckType.HREFLANG, Severity.WARN,
                "finding1", null, "key1", List.of(), Evidence.NONE);
        CheckFinding finding2 = new CheckFinding(CheckType.SITEMAP_CONSISTENCY, Severity.INFO,
                "finding2", null, "key2", List.of(), Evidence.NONE);

        CheckEngine engine = new CheckEngine(new CheckRegistry(List.of(), List.of(
                new SiteCheck() {
                    @Override public CheckType type() { return CheckType.TLS_CERT; }
                    @Override public Severity defaultSeverity() { return Severity.ERROR; }
                    @Override public Set<String> messageKeys() { return Set.of(); }
                    @Override public List<CheckFinding> evaluate(RunSnapshots s, SiteContext c, CheckConfig cfg) {
                        throw new NullPointerException("NPE in TLS check");
                    }
                },
                new SiteCheck() {
                    @Override public CheckType type() { return CheckType.HREFLANG; }
                    @Override public Severity defaultSeverity() { return Severity.WARN; }
                    @Override public Set<String> messageKeys() { return Set.of(); }
                    @Override public List<CheckFinding> evaluate(RunSnapshots s, SiteContext c, CheckConfig cfg) {
                        return List.of(finding1);
                    }
                },
                new SiteCheck() {
                    @Override public CheckType type() { return CheckType.DEAD_LINK; }
                    @Override public Severity defaultSeverity() { return Severity.ERROR; }
                    @Override public Set<String> messageKeys() { return Set.of(); }
                    @Override public List<CheckFinding> evaluate(RunSnapshots s, SiteContext c, CheckConfig cfg) {
                        return null; // triggers NPE on addAll
                    }
                },
                new SiteCheck() {
                    @Override public CheckType type() { return CheckType.SITEMAP_CONSISTENCY; }
                    @Override public Severity defaultSeverity() { return Severity.INFO; }
                    @Override public Set<String> messageKeys() { return Set.of(); }
                    @Override public List<CheckFinding> evaluate(RunSnapshots s, SiteContext c, CheckConfig cfg) {
                        return List.of(finding2);
                    }
                }
        )));

        RunSnapshots snapshots = new RunSnapshots(1L, site(allEnabled()), List.of(), SoftNotFoundProbe.NONE);
        List<CheckFinding> findings = engine.evaluateSite(snapshots, site(allEnabled()), facts(RunScope.FULL));

        assertThat(findings).containsExactly(finding1, finding2);
    }

    @Test
    @DisplayName("E1: Multi-page run evaluation continues when checks throw on different pages")
    void multiPageRunEvaluationContinuesAcrossPages() {
        CheckFinding findingP1 = new CheckFinding(CheckType.PAGE_STATUS, Severity.ERROR,
                "p1", Snapshots.url("https://example.com/p1"), "k1", List.of(), Evidence.NONE);
        CheckFinding findingP2 = new CheckFinding(CheckType.PAGE_STATUS, Severity.ERROR,
                "p2", Snapshots.url("https://example.com/p2"), "k2", List.of(), Evidence.NONE);
        CheckFinding findingP3 = new CheckFinding(CheckType.PAGE_STATUS, Severity.ERROR,
                "p3", Snapshots.url("https://example.com/p3"), "k3", List.of(), Evidence.NONE);

        CheckEngine engine = new CheckEngine(new CheckRegistry(List.of(
                new PageCheck() {
                    @Override public CheckType type() { return CheckType.PAGE_STATUS; }
                    @Override public Severity defaultSeverity() { return Severity.ERROR; }
                    @Override public Set<String> messageKeys() { return Set.of(); }
                    @Override public List<CheckFinding> evaluate(PageSnapshot s, CheckConfig c) {
                        if (s.url().value().contains("p1")) return List.of(findingP1);
                        if (s.url().value().contains("p2")) return List.of(findingP2);
                        return List.of(findingP3);
                    }
                },
                new PageCheck() {
                    @Override public CheckType type() { return CheckType.IMAGE_BROKEN; }
                    @Override public Severity defaultSeverity() { return Severity.ERROR; }
                    @Override public Set<String> messageKeys() { return Set.of(); }
                    @Override public List<CheckFinding> evaluate(PageSnapshot s, CheckConfig c) {
                        if (s.url().value().contains("p2")) {
                            throw new NullPointerException("NPE on page 2 image check");
                        }
                        return List.of();
                    }
                }
        ), List.of()));

        RunSnapshots snapshots = new RunSnapshots(1L, site(allEnabled()),
                List.of(page("https://example.com/p1"), page("https://example.com/p2"), page("https://example.com/p3")),
                SoftNotFoundProbe.NONE);

        List<CheckFinding> findings = engine.evaluateRun(snapshots, site(allEnabled()), facts(RunScope.FULL));

        assertThat(findings)
                .as("Findings across all pages should be preserved even when one page throws on a check")
                .containsExactly(findingP1, findingP2, findingP3);
    }
}
