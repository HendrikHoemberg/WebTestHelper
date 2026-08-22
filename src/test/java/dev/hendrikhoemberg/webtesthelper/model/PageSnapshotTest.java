package dev.hendrikhoemberg.webtesthelper.model;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PageSnapshotTest {

    @Test
    void collectionsAreCopiedSoACallerCannotMutateASnapshot() {
        List<LinkRef> links = new ArrayList<>();
        links.add(link("https://example.com/a", true));
        PageSnapshot snapshot = snapshotWith(links);
        links.clear();
        assertThat(snapshot.links()).hasSize(1);
        assertThatThrownBy(() -> snapshot.links().add(link("https://example.com/b", true)))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void internalAndExternalLinksArePartitioned() {
        List<LinkRef> links = List.of(
                link("https://example.com/a", true),
                link("https://example.com/b", true),
                link("https://other.example/x", false));
        PageSnapshot snapshot = snapshotWith(links);
        assertThat(snapshot.internalLinks()).hasSize(2);
        assertThat(snapshot.externalLinks()).hasSize(1);
    }

    @Test
    void anImageWithZeroNaturalWidthIsNotRendered() {
        assertThat(new ImageRef("/a.png", url("http://h/a.png"), "", 0, 0, ImageOrigin.IMG)
                .rendered()).isFalse();
        assertThat(new ImageRef("/b.png", url("http://h/b.png"), "", 1, 1, ImageOrigin.IMG)
                .rendered()).isTrue();
    }

    @Test
    void mediaIsPlayableOnlyWithMetadataAndDurationAndNoError() {
        assertThat(new MediaRef(MediaKind.AUDIO, List.of(), 1, 0.5, null).playable()).isTrue();
        assertThat(new MediaRef(MediaKind.AUDIO, List.of(), 0, 0.5, null).playable()).isFalse();
        assertThat(new MediaRef(MediaKind.AUDIO, List.of(), 1, 0.0, null).playable()).isFalse();
        assertThat(new MediaRef(MediaKind.VIDEO, List.of(), 1, 9.0, "MEDIA_ERR_SRC_NOT_SUPPORTED")
                .playable()).isFalse();
    }

    @Test
    void anUnreachableSnapshotCarriesTheReasonAndNoContent() {
        PageSnapshot snapshot = PageSnapshot.unreachable(
                url("http://h/langsam"), "http://h/langsam", 2, "Timeout 30000ms",
                List.of(), List.of());
        assertThat(snapshot.reachable()).isFalse();
        assertThat(snapshot.httpStatus()).isZero();
        assertThat(snapshot.unreachableReason()).contains("Timeout");
        assertThat(snapshot.links()).isEmpty();
    }

    @Test
    void runSnapshotsIndexesByNormalisedUrl() {
        RunSnapshots run = new RunSnapshots(7L, siteContext(),
                List.of(snapshotAt("http://h/"), snapshotAt("http://h/a.html")),
                SoftNotFoundProbe.NONE);
        assertThat(run.byUrl("http://h/a.html")).isPresent();
        assertThat(run.byUrl("http://h/fehlt")).isEmpty();
        assertThat(run.visitedUrls()).containsExactlyInAnyOrder("http://h/", "http://h/a.html");
    }

    @Test
    void aProbeThatReturnedAHard404IsNotUsable() {
        assertThat(new SoftNotFoundProbe(404, 123L, 400).usable()).isFalse();
        assertThat(new SoftNotFoundProbe(200, 123L, 400).usable()).isTrue();
        assertThat(SoftNotFoundProbe.NONE.usable()).isFalse();
    }

    private static LinkRef link(String href, boolean internal) {
        return new LinkRef(href, url(href), "", internal, "");
    }

    private static NormalizedUrl url(String value) {
        return UrlNormalizer.normalize(value).orElseThrow();
    }

    private static PageSnapshot snapshotWith(List<LinkRef> links) {
        return new PageSnapshot(url("http://h/"), "http://h/", 0, true, null, 200,
                Map.of(), List.of("http://h/"), 10L, "Titel", "de", "", 0L,
                links, List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), null);
    }

    private static PageSnapshot snapshotAt(String value) {
        return new PageSnapshot(url(value), value, 0, true, null, 200,
                Map.of(), List.of(value), 10L, "Titel", "de", "", 0L,
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), null);
    }

    private static SiteContext siteContext() {
        return new SiteContext(1L, "Test", url("http://h/"), CrawlBudget.DEFAULT,
                List.of(), List.of(), List.of(), true, "WebTestHelper/1.0", Map.of());
    }
}