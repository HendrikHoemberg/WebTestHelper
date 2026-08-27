package dev.hendrikhoemberg.webtesthelper.checks;

import dev.hendrikhoemberg.webtesthelper.model.CheckFinding;
import dev.hendrikhoemberg.webtesthelper.model.CheckSetting;
import dev.hendrikhoemberg.webtesthelper.model.CheckType;
import dev.hendrikhoemberg.webtesthelper.model.CrawlBudget;
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
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CheckEngineTest {

    private final CheckEngine engine = new CheckEngine(CheckRegistry.standard());

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

    private static RunFacts facts(RunScope scope, TlsCertificateFact tls) {
        return new RunFacts(1L, scope, Instant.EPOCH, SoftNotFoundProbe.NONE,
                UrlVerifications.EMPTY, tls, List.of());
    }

    private static RunFacts factsNow(RunScope scope, TlsCertificateFact tls) {
        return new RunFacts(1L, scope, Instant.now(), SoftNotFoundProbe.NONE,
                UrlVerifications.EMPTY, tls, List.of());
    }

    private static RunSnapshots snapshots() {
        return new RunSnapshots(1L, site(allEnabled()), List.of(), SoftNotFoundProbe.NONE);
    }

    private static PageSnapshot brokenPage() {
        return Snapshots.page("https://example.com/x").status(500)
                .image("https://example.com/fehlt.png", 0).build();
    }

    @Test
    void coveredTypesIsExactlyWhatTheRegistryImplements() {
        // Spec 6.4: a run may only claim coverage for checks that exist. With Plan 3b landed the
        // registry implements every CheckType, so coverage is the whole enum.
        assertThat(engine.coveredTypes())
                .containsExactlyInAnyOrderElementsOf(EnumSet.allOf(CheckType.class));
    }

    @Test
    void everyEnabledCheckContributesToTheSamePage() {
        List<CheckFinding> findings =
                engine.evaluatePage(brokenPage(), site(allEnabled()), facts(RunScope.FULL));

        assertThat(findings).extracting(CheckFinding::type)
                .containsExactlyInAnyOrder(CheckType.PAGE_STATUS, CheckType.IMAGE_BROKEN);
    }

    @Test
    void aCheckTheSiteDisabledDoesNotRun() {
        Map<CheckType, CheckSetting> settings = allEnabled();
        settings.put(CheckType.IMAGE_BROKEN, CheckSetting.defaultDisabled());

        assertThat(engine.evaluatePage(brokenPage(), site(settings), facts(RunScope.FULL)))
                .extracting(CheckFinding::type).containsExactly(CheckType.PAGE_STATUS);
    }

    @Test
    void aPulseRunsThePageChecksSpecNineGivesIt() {
        // Spec 9's pulse is "page checks only, no submits", and spec 1 counts a non-playing video
        // among the seven things this product exists to catch. The crawl captures the media state
        // into the snapshot whatever the tier, so a pulse that skipped the check would discard
        // evidence it had already paid for. The scope filter still bites on the site checks —
        // see aSiteCheckOutsideTheRunScopeDoesNotRun, which is where spec 6.4's coverage
        // argument now lives.
        PageSnapshot page = Snapshots.page("https://example.com/medien")
                .media(dev.hendrikhoemberg.webtesthelper.model.MediaKind.VIDEO,
                        "https://example.com/fehlt.mp4", 0, 0.0, "MEDIA_ERR_SRC_NOT_SUPPORTED")
                .build();

        assertThat(engine.evaluatePage(page, site(allEnabled()), facts(RunScope.FULL)))
                .extracting(CheckFinding::type).contains(CheckType.MEDIA_PLAYABLE);
        assertThat(engine.evaluatePage(page, site(allEnabled()), facts(RunScope.PULSE)))
                .extracting(CheckFinding::type).contains(CheckType.MEDIA_PLAYABLE);
    }

    @Test
    void aSiteSeverityOverrideReachesTheFinding() {
        Map<CheckType, CheckSetting> settings = allEnabled();
        settings.put(CheckType.IMAGE_BROKEN, new CheckSetting(true, Severity.INFO, Map.of()));

        assertThat(engine.evaluatePage(brokenPage(), site(settings), facts(RunScope.FULL)))
                .filteredOn(finding -> finding.type() == CheckType.IMAGE_BROKEN)
                .singleElement()
                .satisfies(finding -> assertThat(finding.severity()).isEqualTo(Severity.INFO));
    }

    @Test
    void aWholeRunIsEvaluatedPageByPage() {
        RunSnapshots snapshots = new RunSnapshots(1L, site(allEnabled()),
                List.of(brokenPage(), Snapshots.page("https://example.com/y").status(500).build()),
                SoftNotFoundProbe.NONE);

        assertThat(engine.evaluateRun(snapshots, site(allEnabled()), facts(RunScope.FULL)))
                .hasSize(3);
    }

    @Test
    void aCheckThatThrowsNamesItselfInsteadOfFailingAnonymously() {
        // Spec 14 is about a bad page not killing a run. A deterministically broken check is a
        // different animal: it would fail every run of every site until someone fixed it, so it
        // fails loudly and says which check and which page.
        CheckEngine broken = new CheckEngine(new CheckRegistry(List.of(new PageCheck() {
            @Override public CheckType type() { return CheckType.PAGE_STATUS; }
            @Override public Severity defaultSeverity() { return Severity.ERROR; }
            @Override public Set<String> messageKeys() { return Set.of("finding.PAGE_STATUS.x"); }
            @Override public List<CheckFinding> evaluate(PageSnapshot s, CheckConfig c) {
                throw new IllegalStateException("kaputt");
            }
        }), List.of()));

        assertThatThrownBy(() ->
                broken.evaluatePage(brokenPage(), site(allEnabled()), facts(RunScope.FULL)))
                .isInstanceOf(CheckEvaluationException.class)
                .hasMessageContaining("PAGE_STATUS")
                .hasMessageContaining("https://example.com/x")
                .hasRootCauseMessage("kaputt");
    }

    @Test
    void aSiteCheckOutsideTheRunScopeDoesNotRun() {
        TlsCertificateFact expired = new TlsCertificateFact("example.com", true, null,
                Instant.EPOCH, Instant.now().minus(1, java.time.temporal.ChronoUnit.DAYS),
                "CN=example.com");

        assertThat(engine.evaluateSite(snapshots(), site(allEnabled()), factsNow(RunScope.FULL, expired)))
                .extracting(CheckFinding::type).contains(CheckType.TLS_CERT);
        assertThat(engine.evaluateSite(snapshots(), site(allEnabled()), factsNow(RunScope.PULSE, expired)))
                .extracting(CheckFinding::type).doesNotContain(CheckType.TLS_CERT);
    }

    @Test
    void aDisabledSiteCheckDoesNotRun() {
        TlsCertificateFact expired = new TlsCertificateFact("example.com", true, null,
                Instant.EPOCH, Instant.now().minus(1, java.time.temporal.ChronoUnit.DAYS),
                "CN=example.com");
        Map<CheckType, CheckSetting> settings = allEnabled();
        settings.put(CheckType.TLS_CERT, CheckSetting.defaultDisabled());

        assertThat(engine.evaluateSite(snapshots(), site(settings), factsNow(RunScope.FULL, expired)))
                .extracting(CheckFinding::type).doesNotContain(CheckType.TLS_CERT);
    }

    @Test
    void aSiteCheckThatThrowsNamesItselfInsteadOfFailingAnonymously() {
        CheckEngine broken = new CheckEngine(new CheckRegistry(List.of(), List.of(new SiteCheck() {
            @Override public CheckType type() { return CheckType.TLS_CERT; }
            @Override public Severity defaultSeverity() { return Severity.ERROR; }
            @Override public Set<String> messageKeys() { return Set.of("finding.TLS_CERT.x"); }
            @Override public List<CheckFinding> evaluate(RunSnapshots s, SiteContext c,
                    CheckConfig cfg) {
                throw new IllegalStateException("kaputt");
            }
        })));

        assertThatThrownBy(() ->
                broken.evaluateSite(snapshots(), site(allEnabled()), facts(RunScope.FULL)))
                .isInstanceOf(CheckEvaluationException.class)
                .hasMessageContaining("TLS_CERT")
                .hasMessageContaining("https://example.com/")
                .hasRootCauseMessage("kaputt");
    }
}