package dev.hendrikhoemberg.webtesthelper.crawler;

import dev.hendrikhoemberg.webtesthelper.model.*;
import dev.hendrikhoemberg.webtesthelper.support.FixtureSite;
import org.junit.jupiter.api.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("browser")
class PageNavigatorTest {

    private static FixtureSite site;
    private static BrowserPool pool;
    private static PageNavigator navigator;
    private static Path artifacts;

    @BeforeAll
    static void start() throws Exception {
        site = FixtureSite.start();
        artifacts = Files.createTempDirectory("wth-navigator");
        // Matches application-test.properties. The fixture's /langsam sleeps 8s, so the
        // timeout case still fires with margin, and no real fixture page comes close.
        CrawlerProperties properties = new CrawlerProperties(1, 10, Duration.ofSeconds(5),
                Duration.ZERO, artifacts, true, false);
        pool = new BrowserPool(properties);
        navigator = new PageNavigator(properties, new HostThrottle());
    }

    @AfterAll
    static void stop() {
        pool.close();
        site.close();
    }

    private PageSnapshot capture(String path, int depth) {
        String url = site.url(path);
        return pool.submit(browser -> navigator.capture(
                browser, new CrawlTarget(1L, url, depth), siteContext(), artifacts));
    }

    /** SiteContext for the fixture: no patterns, robots respected, default budget. */
    private static SiteContext siteContext() {
        return new SiteContext(1L, "Fixture",
                UrlNormalizer.normalize(site.baseUrl()).orElseThrow(),
                CrawlBudget.DEFAULT, List.of(), List.of(), List.of(), true, null, java.util.Map.of());
    }

    @Test
    void theStartPageYieldsStatusTitleLanguageTextAndAScreenshot() {
        PageSnapshot snapshot = capture("", 0);

        assertThat(snapshot.reachable()).isTrue();
        assertThat(snapshot.httpStatus()).isEqualTo(200);
        assertThat(snapshot.title()).contains("Startseite");
        assertThat(snapshot.htmlLang()).isEqualTo("de");
        assertThat(snapshot.textContent()).contains("Startseite");
        assertThat(snapshot.textSimhash()).isNotZero();
        assertThat(snapshot.loadMillis()).isPositive();
        assertThat(snapshot.responseHeaders()).containsKey("content-type");
        assertThat(artifacts.resolve(snapshot.screenshotPath())).exists();
    }

    @Test
    void linksArePartitionedIntoInternalAndExternalWithTheirAnchorText() {
        PageSnapshot snapshot = capture("", 0);

        assertThat(snapshot.internalLinks()).extracting(LinkRef::anchorText)
                .contains("Leistungen", "Kontakt");
        assertThat(snapshot.externalLinks()).extracting(link -> link.target().value())
                .anySatisfy(url -> assertThat(url).contains("localhost:9/tot"));
        assertThat(snapshot.links()).extracting(LinkRef::rawHref).contains("/leistungen.html");
    }

    @Test
    void aBrokenImageIsRecognisedByNaturalWidthRatherThanByStatus() {
        // Spec 7.1: status 200 is not the test — the image must actually render.
        PageSnapshot snapshot = capture("", 0);

        assertThat(snapshot.images())
                .filteredOn(image -> image.rawSource().endsWith("/assets/fehlt.png"))
                .isNotEmpty()
                .allSatisfy(image -> assertThat(image.rendered()).isFalse());
        assertThat(snapshot.images())
                .filteredOn(image -> image.rawSource().endsWith("/assets/logo.png"))
                .allSatisfy(image -> assertThat(image.rendered()).isTrue());
    }

    @Test
    void aLazyImageBelowTheFoldIsNotBroken() {
        PageSnapshot snapshot = capture("faul.html", 1);

        // The above-fold logo must be decoded.
        assertThat(snapshot.images())
                .filteredOn(image -> image.rawSource().equals("/assets/logo.png")
                        && image.origin() == ImageOrigin.IMG)
                .anySatisfy(image -> assertThat(image.state()).isEqualTo(ImageState.DECODED));

        // The lazy image below 2000px of spacer: never triggered by viewport.
        // The probe's new Image() loads the same URL (/assets/lazy-ok.png) independently
        // of lazy loading, so it should resolve as DECODED (local 1×1 PNG, instant).
        // The key assertion: NOT BROKEN.
        assertThat(snapshot.images())
                .filteredOn(image -> image.rawSource().equals("/assets/lazy-ok.png"))
                .allSatisfy(image ->
                        assertThat(image.state()).isNotEqualTo(ImageState.BROKEN));
    }

    @Test
    void aSlowImageThatTimesOutIsUnknownNotBroken() {
        PageSnapshot snapshot = capture("langsam-bild.html", 1);

        assertThat(snapshot.images())
                .filteredOn(image -> image.rawSource().equals("/assets/verspaetet.png"))
                .singleElement()
                .satisfies(image -> assertThat(image.state()).isEqualTo(ImageState.UNKNOWN));
    }

    @Test
    void srcsetCandidatesAndCssBackgroundsAreExtractedAndMeasured() {
        PageSnapshot snapshot = capture("leistungen.html", 1);

        assertThat(snapshot.images()).extracting(ImageRef::origin)
                .contains(ImageOrigin.IMG, ImageOrigin.SRCSET, ImageOrigin.CSS_BACKGROUND);
        assertThat(snapshot.images())
                .filteredOn(image -> image.origin() == ImageOrigin.CSS_BACKGROUND)
                .allSatisfy(image -> assertThat(image.rendered()).isFalse());
    }

    @Test
    void mediaMetadataIsWaitedForSoPlayabilityIsRealNotAssumed() {
        PageSnapshot snapshot = capture("medien.html", 1);

        assertThat(snapshot.media()).filteredOn(media -> media.kind() == MediaKind.AUDIO)
                .singleElement()
                .satisfies(audio -> {
                    assertThat(audio.readyState()).isGreaterThanOrEqualTo(1);
                    assertThat(audio.duration()).isGreaterThan(0.0);
                    assertThat(audio.playable()).isTrue();
                });
        assertThat(snapshot.media()).filteredOn(media -> media.kind() == MediaKind.VIDEO)
                .singleElement()
                .satisfies(video -> assertThat(video.playable()).isFalse());
    }

    @Test
    void iframesAreCapturedIncludingTheirConsoleErrorsAndBlockedState() {
        PageSnapshot snapshot = capture("kontakt.html", 1);

        assertThat(snapshot.frames()).hasSize(2);
        assertThat(snapshot.frames()).extracting(frame -> frame.src().path())
                .contains("/maps/embed/v1/place", "/blockiert");
        // The Maps billing failure is only visible in the console (spec 7.1).
        assertThat(snapshot.consoleMessages()).extracting(ConsoleMessage::text)
                .anySatisfy(text -> assertThat(text).contains("ApiNotActivatedMapError"));
        assertThat(snapshot.frames())
                .filteredOn(frame -> frame.src().path().equals("/blockiert"))
                .singleElement()
                .satisfies(frame -> assertThat(frame.contentTextLength()).isZero());
    }

    @Test
    void aGreyMapCanvasIsNotPaintedForSameOriginAndCrossOriginFrames() {
        // The same-origin path is read by extract.js; the cross-origin (localhost) frame is read
        // through the CDP session in PageNavigator. Both must land on NOT_PAINTED.
        PageSnapshot snapshot = capture("karten-grau.html", 1);

        assertThat(snapshot.frames()).hasSize(2);
        assertThat(snapshot.frames())
                .extracting(frame -> frame.mapPaintState())
                .containsExactlyInAnyOrder(MapPaintState.NOT_PAINTED, MapPaintState.NOT_PAINTED);
    }

    @Test
    void aHealthyMapCanvasIsPaintedForSameOriginAndCrossOriginFrames() {
        PageSnapshot snapshot = capture("karten-gesund.html", 1);

        assertThat(snapshot.frames()).hasSize(2);
        assertThat(snapshot.frames())
                .extracting(frame -> frame.mapPaintState())
                .containsExactlyInAnyOrder(MapPaintState.PAINTED, MapPaintState.PAINTED);
    }

    @Test
    void aHealthyMapWhoseCanvasPaintsLateIsNotReportedAsNotPainted() {
        // The single-shot probe would read the still-blank canvas right after NETWORKIDLE and
        // report a transient NOT_PAINTED — the false ERROR this signal must never introduce. The
        // confirmed second read after the settle is what keeps a slow-but-healthy map healthy.
        PageSnapshot snapshot = capture("karten-spaet.html", 1);

        assertThat(snapshot.frames()).hasSize(2);
        assertThat(snapshot.frames())
                .extracting(frame -> frame.mapPaintState())
                .containsExactlyInAnyOrder(MapPaintState.PAINTED, MapPaintState.PAINTED);
    }

    @Test
    void aTransparentOverlayListedBeforeThePaintedMapDoesNotHideThePaint() {
        // A map may layer a blank overlay canvas ahead of the painted map canvas. The probe must
        // scan every readable canvas, not stop at the first: PAINTED wins even when a later canvas
        // carries the paint.
        PageSnapshot snapshot = capture("karten-zwei.html", 1);

        assertThat(snapshot.frames()).hasSize(2);
        assertThat(snapshot.frames())
                .extracting(frame -> frame.mapPaintState())
                .containsExactlyInAnyOrder(MapPaintState.PAINTED, MapPaintState.PAINTED);
    }

    @Test
    void formFieldsCarryEnoughToClassifyThemLater() {
        PageSnapshot snapshot = capture("kontakt.html", 1);

        assertThat(snapshot.forms()).singleElement().satisfies(form -> {
            assertThat(form.id()).isEqualTo("kontaktformular");
            assertThat(form.method()).isEqualTo("post");
            assertThat(form.fields()).extracting(FormFieldRef::name)
                    .contains("name", "email", "nachricht");
            assertThat(form.fields())
                    .filteredOn(field -> field.name().equals("email"))
                    .singleElement()
                    .satisfies(field -> {
                        assertThat(field.type()).isEqualTo("email");
                        assertThat(field.label()).contains("E-Mail");
                        assertThat(field.autocomplete()).isEqualTo("email");
                        assertThat(field.required()).isTrue();
                    });
        });
    }

    @Test
    void aCloakWrapperAnchorIsFilteredAndOnlyTheInnerLinkIsExtracted() {
        PageSnapshot snapshot = capture("mantel.html", 1);

        // The outer anchor href="/kontakt@example.com" must NOT appear.
        assertThat(snapshot.links())
                .extracting(link -> link.target().path())
                .doesNotContain("/kontakt@example.com");

        // A normal link on the same page must survive the filter.
        assertThat(snapshot.links())
                .extracting(link -> link.target().path())
                .contains("/leistungen.html");
    }

    @Test
    void aRedirectChainIsRecordedFromRequestedToFinalUrl() {
        PageSnapshot snapshot = capture("weiter/1", 1);

        assertThat(snapshot.redirectChain()).hasSize(4);
        assertThat(snapshot.redirectChain().getFirst()).endsWith("/weiter/1");
        assertThat(snapshot.redirectChain().getLast()).endsWith("/ziel.html");
        assertThat(snapshot.url().path()).isEqualTo("/ziel.html");
        assertThat(snapshot.requestedUrl()).endsWith("/weiter/1");
    }

    @Test
    void hreflangAlternatesAreExtractedWithTheirLanguageAndTarget() {
        PageSnapshot snapshot = capture("", 0);

        assertThat(snapshot.alternates()).hasSize(2);
        assertThat(snapshot.alternates())
                .extracting(AlternateRef::hreflang)
                .containsExactlyInAnyOrder("en", "de");
        assertThat(snapshot.alternates())
                .filteredOn(alternate -> alternate.hreflang().equals("en"))
                .singleElement()
                .satisfies(alternate -> assertThat(alternate.target().path())
                        .isEqualTo("/en/index.html"));
    }

    @Test
    void scriptsAndStylesheetsAreExtractedWithTheirKind() {
        PageSnapshot snapshot = capture("mixed-content.html", 1);

        assertThat(snapshot.subresources())
                .filteredOn(subresource -> subresource.kind() == SubresourceKind.SCRIPT)
                .extracting(subresource -> subresource.target().value())
                .anySatisfy(url -> assertThat(url).endsWith("/assets/skript.js"))
                .anySatisfy(url -> assertThat(url).isEqualTo("http://localhost:9/unsicher.js"));
        assertThat(snapshot.subresources())
                .filteredOn(subresource -> subresource.kind() == SubresourceKind.STYLESHEET)
                .extracting(subresource -> subresource.target().value())
                .anySatisfy(url -> assertThat(url).endsWith("/assets/stil.css"))
                .anySatisfy(url -> assertThat(url).isEqualTo("http://localhost:9/unsicher.css"));
    }

    @Test
    void anInlineScriptIsNotASubresourceBecauseItLoadsNothing() {
        // mixed-content.html carries an inline <script> alongside its two src'd ones. Nothing is
        // fetched for it, so it has no scheme and cannot be insecure.
        PageSnapshot snapshot = capture("mixed-content.html", 1);

        assertThat(snapshot.subresources()).hasSize(4);
    }

    @Test
    void aPageWithoutSubresourcesReturnsAnEmptyListRatherThanNull() {
        PageSnapshot snapshot = capture("", 0);

        assertThat(snapshot.subresources()).isNotNull().isEmpty();
    }

    @Test
    void aPageWithoutAlternatesReturnsAnEmptyListRatherThanNull() {
        PageSnapshot snapshot = capture("kontakt.html", 1);

        assertThat(snapshot.alternates()).isNotNull().isEmpty();
    }

    @Test
    void aFailedSubresourceIsRecorded() {
        PageSnapshot snapshot = capture("", 0);

        assertThat(snapshot.failedRequests()).extracting(FailedRequest::url)
                .anySatisfy(url -> assertThat(url).endsWith("/assets/fehlt.png"));
    }

    @Test
    void aPageThatTimesOutBecomesAnUnreachableSnapshotAndNotAnException() {
        // Spec 14: one bad page must never kill a run.
        PageSnapshot snapshot = capture("langsam", 1);

        assertThat(snapshot.reachable()).isFalse();
        assertThat(snapshot.unreachableReason()).isNotBlank();
        assertThat(snapshot.links()).isEmpty();
    }

    @Test
    void aRedirectLoopFailsTheNavigationWithoutFailingTheCrawl() {
        PageSnapshot snapshot = capture("schleife/a", 1);

        assertThat(snapshot.reachable()).isFalse();
        assertThat(snapshot.unreachableReason()).contains("ERR_TOO_MANY_REDIRECTS");
        assertThat(snapshot.unreachableReason()).containsIgnoringCase("redirect");
    }

    @Test
    void twoCapturesOfTheSameSoftNotFoundPageAgreeOnTheirFingerprint() {
        long first = capture("gibt-es-nicht-a", 1).textSimhash();
        long second = capture("gibt-es-nicht-b", 1).textSimhash();

        assertThat(SimHash.hammingDistance(first, second)).isLessThanOrEqualTo(6);
        assertThat(capture("", 0).textSimhash()).isNotEqualTo(first);
    }
}