package dev.hendrikhoemberg.webtesthelper.checks;

import dev.hendrikhoemberg.webtesthelper.model.CheckType;
import dev.hendrikhoemberg.webtesthelper.model.CrawlBudget;
import dev.hendrikhoemberg.webtesthelper.model.NormalizedUrl;
import dev.hendrikhoemberg.webtesthelper.model.PageSnapshot;
import dev.hendrikhoemberg.webtesthelper.model.RunSnapshots;
import dev.hendrikhoemberg.webtesthelper.model.Severity;
import dev.hendrikhoemberg.webtesthelper.model.SiteContext;
import dev.hendrikhoemberg.webtesthelper.model.SoftNotFoundProbe;
import dev.hendrikhoemberg.webtesthelper.support.Snapshots;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class InteractionTargetsTest {

    private static SiteContext site(List<String> pinnedKeyPages) {
        return new SiteContext(1L, "Beispiel", Snapshots.url("https://example.com/"),
                CrawlBudget.DEFAULT, List.of(), List.of(), pinnedKeyPages, true, null, Map.of());
    }

    private static SiteContext site() {
        return site(List.of());
    }

    private static RunSnapshots snapshots(PageSnapshot... pages) {
        return new RunSnapshots(1L, site(), List.of(pages), SoftNotFoundProbe.NONE);
    }

    private static RunSnapshots snapshotsWithSite(SiteContext site, PageSnapshot... pages) {
        return new RunSnapshots(1L, site, List.of(pages), SoftNotFoundProbe.NONE);
    }

    @Test
    void homepageReturnsBaseUrlWhenSnapshotIsReachable() {
        PageSnapshot home = Snapshots.page("https://example.com/").build();
        RunSnapshots run = snapshots(home);

        List<NormalizedUrl> targets = InteractionTargets.homepage(run, site());

        assertThat(targets).containsExactly(Snapshots.url("https://example.com/"));
    }

    @Test
    void homepageReturnsEmptyWhenBaseUrlIsUnreachable() {
        PageSnapshot home = Snapshots.page("https://example.com/").unreachable("503 Service Unavailable");
        RunSnapshots run = snapshots(home);

        List<NormalizedUrl> targets = InteractionTargets.homepage(run, site());

        assertThat(targets).isEmpty();
    }

    @Test
    void homepageReturnsEmptyWhenCrawlDidNotReachBaseUrl() {
        PageSnapshot other = Snapshots.page("https://example.com/kontakt.html").build();
        RunSnapshots run = snapshots(other);

        List<NormalizedUrl> targets = InteractionTargets.homepage(run, site());

        assertThat(targets).isEmpty();
    }

    @Test
    void withFormReturnsOnlyFormBearingPagesTruncatedToMaxTargets() {
        PageSnapshot p1 = Snapshots.page("https://example.com/c-form").form("f1", "/submit", "post").build();
        PageSnapshot p2 = Snapshots.page("https://example.com/a-form").form("f2", "/submit", "post").build();
        PageSnapshot p3 = Snapshots.page("https://example.com/b-form").form("f3", "/submit", "post").build();
        PageSnapshot noForm = Snapshots.page("https://example.com/no-form").build();
        RunSnapshots run = snapshots(p1, noForm, p2, p3);

        List<NormalizedUrl> targets = InteractionTargets.withForm(run, 2);

        // Sorted alphabetically by value: a-form, b-form (truncated to 2)
        assertThat(targets).containsExactly(
                Snapshots.url("https://example.com/a-form"),
                Snapshots.url("https://example.com/b-form")
        );
    }

    @Test
    void withFormReturnsEmptyWhenNoPagesHaveForms() {
        PageSnapshot p1 = Snapshots.page("https://example.com/a").build();
        PageSnapshot p2 = Snapshots.page("https://example.com/b").build();
        RunSnapshots run = snapshots(p1, p2);

        List<NormalizedUrl> targets = InteractionTargets.withForm(run, 5);

        assertThat(targets).isEmpty();
    }

    @Test
    void withFormIgnoresUnreachablePagesEvenIfTheyHaveForms() {
        PageSnapshot unreachableForm = Snapshots.page("https://example.com/form")
                .form("f", "/submit", "post")
                .unreachable("Timeout");
        RunSnapshots run = snapshots(unreachableForm);

        List<NormalizedUrl> targets = InteractionTargets.withForm(run, 5);

        assertThat(targets).isEmpty();
    }

    @Test
    void withFormIsDeterministicAndStableAcrossShuffledInputs() {
        PageSnapshot p1 = Snapshots.page("https://example.com/delta").form("f1", "/sub", "post").build();
        PageSnapshot p2 = Snapshots.page("https://example.com/alpha").form("f2", "/sub", "post").build();
        PageSnapshot p3 = Snapshots.page("https://example.com/gamma").form("f3", "/sub", "post").build();
        PageSnapshot p4 = Snapshots.page("https://example.com/beta").form("f4", "/sub", "post").build();
        PageSnapshot p5 = Snapshots.page("https://example.com/epsilon").form("f5", "/sub", "post").build();

        List<PageSnapshot> list1 = List.of(p1, p2, p3, p4, p5);
        List<PageSnapshot> list2 = new ArrayList<>(list1);
        Collections.shuffle(list2);

        RunSnapshots run1 = new RunSnapshots(1L, site(), list1, SoftNotFoundProbe.NONE);
        RunSnapshots run2 = new RunSnapshots(1L, site(), list2, SoftNotFoundProbe.NONE);

        List<NormalizedUrl> targets1 = InteractionTargets.withForm(run1, 3);
        List<NormalizedUrl> targets2 = InteractionTargets.withForm(run2, 3);

        List<NormalizedUrl> expected = List.of(
                Snapshots.url("https://example.com/alpha"),
                Snapshots.url("https://example.com/beta"),
                Snapshots.url("https://example.com/delta")
        );

        assertThat(targets1).containsExactlyElementsOf(expected);
        assertThat(targets2).containsExactlyElementsOf(expected);
    }

    @Test
    void keyPagesOrHomepageReturnsReachablePinnedPagesSortedAndTruncated() {
        SiteContext site = site(List.of("/ueber-uns", "/kontakt", "/impressum"));
        PageSnapshot home = Snapshots.page("https://example.com/").build();
        PageSnapshot ueberUns = Snapshots.page("https://example.com/ueber-uns").build();
        PageSnapshot kontakt = Snapshots.page("https://example.com/kontakt").build();
        PageSnapshot impressum = Snapshots.page("https://example.com/impressum").build();
        PageSnapshot unpinned = Snapshots.page("https://example.com/other").build();

        RunSnapshots run = snapshotsWithSite(site, home, ueberUns, unpinned, kontakt, impressum);

        List<NormalizedUrl> targets = InteractionTargets.keyPagesOrHomepage(run, site, 2);

        // Pinned matches: impressum, kontakt, ueber-uns. Truncated to 2: impressum, kontakt
        assertThat(targets).containsExactly(
                Snapshots.url("https://example.com/impressum"),
                Snapshots.url("https://example.com/kontakt")
        );
    }

    @Test
    void keyPagesOrHomepageFallsBackToHomepageWhenPinSetIsEmpty() {
        SiteContext site = site(List.of());
        PageSnapshot home = Snapshots.page("https://example.com/").build();
        PageSnapshot kontakt = Snapshots.page("https://example.com/kontakt").build();
        RunSnapshots run = snapshotsWithSite(site, home, kontakt);

        List<NormalizedUrl> targets = InteractionTargets.keyPagesOrHomepage(run, site, 5);

        assertThat(targets).containsExactly(Snapshots.url("https://example.com/"));
    }

    @Test
    void keyPagesOrHomepageFallsBackToHomepageWhenPinSetIsDisjoint() {
        SiteContext site = site(List.of("/nicht-vorhanden", "/auch-nicht"));
        PageSnapshot home = Snapshots.page("https://example.com/").build();
        PageSnapshot kontakt = Snapshots.page("https://example.com/kontakt").build();
        RunSnapshots run = snapshotsWithSite(site, home, kontakt);

        List<NormalizedUrl> targets = InteractionTargets.keyPagesOrHomepage(run, site, 5);

        assertThat(targets).containsExactly(Snapshots.url("https://example.com/"));
    }

    @Test
    void keyPagesOrHomepageFallsBackToHomepageWhenPinnedPagesAreUnreachable() {
        SiteContext site = site(List.of("/kontakt"));
        PageSnapshot home = Snapshots.page("https://example.com/").build();
        PageSnapshot kontakt = Snapshots.page("https://example.com/kontakt").unreachable("500 Internal Server Error");
        RunSnapshots run = snapshotsWithSite(site, home, kontakt);

        List<NormalizedUrl> targets = InteractionTargets.keyPagesOrHomepage(run, site, 5);

        assertThat(targets).containsExactly(Snapshots.url("https://example.com/"));
    }

    @Test
    void keyPagesOrHomepageReturnsEmptyWhenDisjointFallbackHomepageIsUnreachable() {
        SiteContext site = site(List.of("/nicht-vorhanden"));
        PageSnapshot home = Snapshots.page("https://example.com/").unreachable("503 Service Unavailable");
        RunSnapshots run = snapshotsWithSite(site, home);

        List<NormalizedUrl> targets = InteractionTargets.keyPagesOrHomepage(run, site, 5);

        assertThat(targets).isEmpty();
    }

    @Test
    void withFormReturnsEmptyWhenMaxTargetsIsZeroOrNegative() {
        PageSnapshot p1 = Snapshots.page("https://example.com/form").form("f", "/submit", "post").build();
        RunSnapshots run = snapshots(p1);

        assertThat(InteractionTargets.withForm(run, 0)).isEmpty();
        assertThat(InteractionTargets.withForm(run, -1)).isEmpty();
    }

    @Test
    void keyPagesOrHomepageReturnsEmptyWhenMaxTargetsIsZeroOrNegative() {
        SiteContext site = site(List.of("/kontakt"));
        PageSnapshot home = Snapshots.page("https://example.com/").build();
        PageSnapshot kontakt = Snapshots.page("https://example.com/kontakt").build();
        RunSnapshots run = snapshotsWithSite(site, home, kontakt);

        assertThat(InteractionTargets.keyPagesOrHomepage(run, site, 0)).isEmpty();
        assertThat(InteractionTargets.keyPagesOrHomepage(run, site, -1)).isEmpty();
    }

    @Test
    void keyPagesOrHomepageDeduplicatesAndSupportsAbsoluteUrlsInPins() {
        SiteContext site = site(List.of("https://example.com/kontakt", "/kontakt", "/impressum"));
        PageSnapshot home = Snapshots.page("https://example.com/").build();
        PageSnapshot kontakt = Snapshots.page("https://example.com/kontakt").build();
        PageSnapshot impressum = Snapshots.page("https://example.com/impressum").build();
        RunSnapshots run = snapshotsWithSite(site, home, kontakt, impressum);

        List<NormalizedUrl> targets = InteractionTargets.keyPagesOrHomepage(run, site, 5);

        assertThat(targets).containsExactly(
                Snapshots.url("https://example.com/impressum"),
                Snapshots.url("https://example.com/kontakt")
        );
    }

    @Test
    void interactionCheckDefaultMethodReturnsHomepage() {
        InteractionCheck check = new InteractionCheck() {
            @Override
            public CheckType type() {
                return null;
            }

            @Override
            public Severity defaultSeverity() {
                return Severity.INFO;
            }

            @Override
            public java.util.Set<String> messageKeys() {
                return java.util.Set.of();
            }

            @Override
            public List<dev.hendrikhoemberg.webtesthelper.model.CheckFinding> evaluate(
                    com.microsoft.playwright.Page page, SiteContext site, CheckConfig config) {
                return List.of();
            }
        };
        PageSnapshot home = Snapshots.page("https://example.com/").build();
        RunSnapshots run = snapshots(home);

        List<NormalizedUrl> targets = check.targets(run, site(), 5);

        assertThat(targets).containsExactly(Snapshots.url("https://example.com/"));
    }
}
