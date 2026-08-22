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

    private static PageSnapshot brokenPage() {
        return Snapshots.page("https://example.com/x").status(500)
                .image("https://example.com/fehlt.png", 0).build();
    }

    @Test
    void coveredTypesIsExactlyWhatTheRegistryImplements() {
        // Spec 6.4: a run may only claim coverage for checks that exist. The remaining Plan-3b
        // checks (TLS_CERT, HREFLANG, SITEMAP_CONSISTENCY) have no implementation here yet.
        assertThat(engine.coveredTypes())
                .contains(CheckType.PAGE_STATUS, CheckType.PAGE_UNREACHABLE,
                        CheckType.REDIRECT_CHAIN, CheckType.IMAGE_BROKEN, CheckType.MEDIA_PLAYABLE,
                        CheckType.IFRAME_EMBED, CheckType.MIXED_CONTENT, CheckType.CONSOLE_ERRORS,
                        CheckType.DEAD_LINK, CheckType.FILE_DOWNLOAD)
                .doesNotContain(CheckType.TLS_CERT, CheckType.HREFLANG,
                        CheckType.SITEMAP_CONSISTENCY);
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
    void aCheckOutsideTheRunScopeDoesNotRun() {
        // Spec 6.4: a run's coverage is the set of check types it ran. A pulse that quietly ran
        // a full crawl's checks would resolve findings it has no business resolving.
        PageSnapshot page = Snapshots.page("https://example.com/medien")
                .media(dev.hendrikhoemberg.webtesthelper.model.MediaKind.VIDEO,
                        "https://example.com/fehlt.mp4", 0, 0.0, "MEDIA_ERR_SRC_NOT_SUPPORTED")
                .build();

        assertThat(engine.evaluatePage(page, site(allEnabled()), facts(RunScope.FULL)))
                .extracting(CheckFinding::type).contains(CheckType.MEDIA_PLAYABLE);
        assertThat(engine.evaluatePage(page, site(allEnabled()), facts(RunScope.PULSE)))
                .isEmpty();
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
}