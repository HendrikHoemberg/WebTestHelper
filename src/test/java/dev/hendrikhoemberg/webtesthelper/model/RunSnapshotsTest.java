package dev.hendrikhoemberg.webtesthelper.model;

import dev.hendrikhoemberg.webtesthelper.support.Snapshots;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class RunSnapshotsTest {

    private static SiteContext site() {
        return new SiteContext(1L, "Beispiel", Snapshots.url("https://example.com/"),
                CrawlBudget.DEFAULT, List.of(), List.of(), List.of(), true, null, Map.of());
    }

    private static RunSnapshots snapshots(PageSnapshot... pages) {
        return new RunSnapshots(1L, site(), List.of(pages), SoftNotFoundProbe.NONE);
    }

    @Test
    void theIndexResolvesEveryCrawledPageByItsNormalisedUrl() {
        PageSnapshot start = Snapshots.page("https://example.com/").build();
        PageSnapshot kontakt = Snapshots.page("https://example.com/kontakt.html").build();

        Map<String, PageSnapshot> index = snapshots(start, kontakt).byUrlIndex();

        assertThat(index).containsOnlyKeys("https://example.com/",
                "https://example.com/kontakt.html");
        assertThat(index.get("https://example.com/kontakt.html")).isEqualTo(kontakt);
    }

    @Test
    void twoSnapshotsOfOnePageCollapseToTheFirstJustLikeByUrl() {
        // The frontier dedupes on the requested URL, a snapshot carries the final one, so a page
        // reachable under two addresses is crawled twice. Both lookups must agree on which wins,
        // or a check reading the index would see a different page than one reading byUrl.
        PageSnapshot viaRedirect = Snapshots.page("https://example.com/kontakt.html")
                .redirectChain("https://example.com/kontakt", "https://example.com/kontakt.html")
                .build();
        PageSnapshot direct = Snapshots.page("https://example.com/kontakt.html").build();
        RunSnapshots run = snapshots(viaRedirect, direct);

        assertThat(run.byUrlIndex()).hasSize(1);
        assertThat(run.byUrlIndex().get("https://example.com/kontakt.html"))
                .isEqualTo(viaRedirect)
                .isEqualTo(run.byUrl("https://example.com/kontakt.html").orElseThrow());
    }

    @Test
    void anUnknownUrlIsAbsentRatherThanNull() {
        assertThat(snapshots(Snapshots.page("https://example.com/").build()).byUrlIndex())
                .doesNotContainKey("https://example.com/gibt-es-nicht.html");
    }

    @Test
    void theIndexIsUnmodifiable() {
        // A check is a pure function (spec 5.2); handing it a mutable view of the run's snapshots
        // would let one check change what the next one sees.
        Map<String, PageSnapshot> index = snapshots(Snapshots.page("https://example.com/").build())
                .byUrlIndex();

        assertThat(index).isUnmodifiable();
    }
}
