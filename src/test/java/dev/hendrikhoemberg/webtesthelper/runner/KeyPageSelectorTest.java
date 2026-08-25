package dev.hendrikhoemberg.webtesthelper.runner;

import dev.hendrikhoemberg.webtesthelper.model.CrawlBudget;
import dev.hendrikhoemberg.webtesthelper.model.PageSnapshot;
import dev.hendrikhoemberg.webtesthelper.model.RunSnapshots;
import dev.hendrikhoemberg.webtesthelper.model.SiteContext;
import dev.hendrikhoemberg.webtesthelper.model.SoftNotFoundProbe;
import dev.hendrikhoemberg.webtesthelper.support.Snapshots;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The pulse set is the constraint on {@code coverage-scoped} resolution (§6.4): a finding's
 * location is compared against a run's visited URLs, so a pulse set that drifted between runs
 * would make findings flicker between resolved and regressed for reasons nobody could explain.
 */
class KeyPageSelectorTest {

    private static final String ORIGIN = "https://example.com";
    private static final String BASE = ORIGIN + "/";

    private static SiteContext site() {
        return new SiteContext(1L, "Beispiel", Snapshots.url(BASE),
                CrawlBudget.DEFAULT, List.of(), List.of(), List.of(), true, null, Map.of());
    }

    private static RunSnapshots snapshots(PageSnapshot... pages) {
        return new RunSnapshots(1L, site(), List.of(pages), SoftNotFoundProbe.NONE);
    }

    @Test
    void aTargetLinkedFromFivePagesOutranksOneLinkedFiveTimesFromOnePage() {
        // D45: the metric is distinct sourcing pages, not raw link count. Five pages linking a
        // target once each beats one page linking it five times — the naive count ties them.
        List<PageSnapshot> pages = new ArrayList<>();
        pages.add(Snapshots.page(BASE).build());
        for (String source : List.of("/a.html", "/b.html", "/c.html", "/d.html", "/e.html")) {
            pages.add(Snapshots.page(ORIGIN + source).link(ORIGIN + "/breit.html", true).build());
        }
        pages.add(Snapshots.page(ORIGIN + "/p.html")
                .link(ORIGIN + "/eng.html", true)
                .link(ORIGIN + "/eng.html", true)
                .link(ORIGIN + "/eng.html", true)
                .link(ORIGIN + "/eng.html", true)
                .link(ORIGIN + "/eng.html", true)
                .build());
        pages.add(Snapshots.page(ORIGIN + "/breit.html").build());
        pages.add(Snapshots.page(ORIGIN + "/eng.html").build());

        List<String> selected = KeyPageSelector.select(snapshots(pages.toArray(new PageSnapshot[0])),
                Snapshots.url(BASE), 10);

        assertThat(selected).containsExactly(BASE, ORIGIN + "/breit.html", ORIGIN + "/eng.html");
    }

    @Test
    void targetsWithoutASuccessfulSnapshotAreExcluded() {
        // Nothing in a pulse set may be a page the run failed on — a pinned broken page would
        // make every pulse run report the same finding forever. Broken and never-crawled targets
        // are counted but dropped.
        List<PageSnapshot> pages = List.of(
                Snapshots.page(BASE).build(),
                Snapshots.page(ORIGIN + "/quelle.html")
                        .link(ORIGIN + "/kaputt.html", true)
                        .link(ORIGIN + "/nicht-besucht.html", true)
                        .link(ORIGIN + "/nicht-da.html", true)
                        .build(),
                Snapshots.page(ORIGIN + "/kaputt.html").status(404).build(),
                Snapshots.page(ORIGIN + "/nicht-da.html").unreachable("Verbindung abgelehnt"));

        List<String> selected = KeyPageSelector.select(snapshots(pages.toArray(new PageSnapshot[0])),
                Snapshots.url(BASE), 10);

        // /nicht-besucht.html has no snapshot at all; /kaputt.html and /nicht-da.html are not 2xx.
        assertThat(selected).containsExactly(BASE);
    }

    @Test
    void theBaseUrlIsAlwaysPresentAndFirstEvenWithNoInboundLinks() {
        List<PageSnapshot> pages = List.of(
                Snapshots.page(BASE).build(),
                Snapshots.page(ORIGIN + "/kontakt.html").build());

        List<String> selected = KeyPageSelector.select(snapshots(pages.toArray(new PageSnapshot[0])),
                Snapshots.url(BASE), 10);

        assertThat(selected).containsExactly(BASE);
    }

    @Test
    void limitCountsTheBaseUrlAsOneOfTheEntriesBeyondIt() {
        List<PageSnapshot> pages = List.of(
                Snapshots.page(BASE).build(),
                Snapshots.page(ORIGIN + "/s1.html").link(ORIGIN + "/y.html", true).build(),
                Snapshots.page(ORIGIN + "/s2.html").link(ORIGIN + "/x.html", true).build(),
                Snapshots.page(ORIGIN + "/x.html").build(),
                Snapshots.page(ORIGIN + "/y.html").build());

        List<String> selected = KeyPageSelector.select(snapshots(pages.toArray(new PageSnapshot[0])),
                Snapshots.url(BASE), 1);

        assertThat(selected).hasSize(2);
        assertThat(selected).containsExactly(BASE, ORIGIN + "/x.html");
    }

    @Test
    void tiesBreakByUrlAscending() {
        List<PageSnapshot> pages = List.of(
                Snapshots.page(BASE).build(),
                Snapshots.page(ORIGIN + "/s1.html").link(ORIGIN + "/b.html", true).build(),
                Snapshots.page(ORIGIN + "/s2.html").link(ORIGIN + "/a.html", true).build(),
                Snapshots.page(ORIGIN + "/a.html").build(),
                Snapshots.page(ORIGIN + "/b.html").build());

        List<String> selected = KeyPageSelector.select(snapshots(pages.toArray(new PageSnapshot[0])),
                Snapshots.url(BASE), 10);

        assertThat(selected).containsExactly(BASE, ORIGIN + "/a.html", ORIGIN + "/b.html");
    }

    @Test
    void emptySnapshotsYieldOnlyTheBaseUrl() {
        List<String> selected = KeyPageSelector.select(snapshots(), Snapshots.url(BASE), 10);

        assertThat(selected).containsExactly(BASE);
    }
}
