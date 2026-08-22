package dev.hendrikhoemberg.webtesthelper.support;

import dev.hendrikhoemberg.webtesthelper.checks.CheckConfig;
import dev.hendrikhoemberg.webtesthelper.checks.PageCheck;
import dev.hendrikhoemberg.webtesthelper.model.ConsoleMessage;
import dev.hendrikhoemberg.webtesthelper.model.FailedRequest;
import dev.hendrikhoemberg.webtesthelper.model.FormRef;
import dev.hendrikhoemberg.webtesthelper.model.FrameRef;
import dev.hendrikhoemberg.webtesthelper.model.ImageOrigin;
import dev.hendrikhoemberg.webtesthelper.model.ImageRef;
import dev.hendrikhoemberg.webtesthelper.model.LinkRef;
import dev.hendrikhoemberg.webtesthelper.model.MediaKind;
import dev.hendrikhoemberg.webtesthelper.model.MediaRef;
import dev.hendrikhoemberg.webtesthelper.model.NormalizedUrl;
import dev.hendrikhoemberg.webtesthelper.model.PageSnapshot;
import dev.hendrikhoemberg.webtesthelper.model.RunFacts;
import dev.hendrikhoemberg.webtesthelper.model.RunScope;
import dev.hendrikhoemberg.webtesthelper.model.SimHash;
import dev.hendrikhoemberg.webtesthelper.model.SoftNotFoundProbe;
import dev.hendrikhoemberg.webtesthelper.model.UrlNormalizer;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Hand-built {@link PageSnapshot}s for check unit tests.
 *
 * <p>Spec 5.2's payoff is that a page check is a pure function over a snapshot: adding a check
 * costs one class and a unit test with no browser in it. A twenty-one argument constructor
 * would quietly undo that, so every field here carries a sensible default and a test sets only
 * the two or three fields it is actually about.
 */
public final class Snapshots {

    private Snapshots() {
    }

    public static NormalizedUrl url(String value) {
        return UrlNormalizer.normalize(value)
                .orElseThrow(() -> new IllegalArgumentException("Keine URL: " + value));
    }

    public static Builder page(String url) {
        return new Builder(url);
    }

    /** Run facts with no usable soft-404 probe — the common case for a check that ignores it. */
    public static RunFacts facts() {
        return facts(SoftNotFoundProbe.NONE);
    }

    public static RunFacts facts(SoftNotFoundProbe probe) {
        return new RunFacts(1L, RunScope.FULL, Instant.EPOCH, probe);
    }

    public static CheckConfig config(PageCheck check, RunFacts facts) {
        return config(check, facts, Map.of());
    }

    public static CheckConfig config(PageCheck check, RunFacts facts, Map<String, Object> options) {
        return new CheckConfig(check.defaultSeverity(), options, facts);
    }

    public static final class Builder {

        private final NormalizedUrl url;
        private String requestedUrl;
        private int depth = 1;
        private int httpStatus = 200;
        private final Map<String, String> headers = new LinkedHashMap<>();
        private List<String> redirectChain;
        private String text = "Willkommen bei der Firma Beispiel. Wir beraten Sie gerne zu allen Fragen.";
        private final List<LinkRef> links = new ArrayList<>();
        private final List<ImageRef> images = new ArrayList<>();
        private final List<MediaRef> media = new ArrayList<>();
        private final List<FrameRef> frames = new ArrayList<>();
        private final List<ConsoleMessage> console = new ArrayList<>();
        private final List<FailedRequest> failed = new ArrayList<>();

        private Builder(String url) {
            this.url = Snapshots.url(url);
            this.requestedUrl = url;
        }

        public Builder status(int httpStatus) {
            this.httpStatus = httpStatus;
            return this;
        }

        public Builder depth(int depth) {
            this.depth = depth;
            return this;
        }

        public Builder text(String text) {
            this.text = text;
            return this;
        }

        public Builder header(String name, String value) {
            headers.put(name, value);
            return this;
        }

        /** Requested URL first, final URL last — exactly what {@code PageNavigator} records. */
        public Builder redirectChain(String... urls) {
            this.requestedUrl = urls[0];
            this.redirectChain = List.of(urls);
            return this;
        }

        public Builder link(String href, boolean internal) {
            links.add(new LinkRef(href, Snapshots.url(href), "Weiterlesen", internal, ""));
            return this;
        }

        public Builder image(String src, int naturalWidth) {
            return image(src, naturalWidth, ImageOrigin.IMG);
        }

        public Builder image(String src, int naturalWidth, ImageOrigin origin) {
            return image(src, naturalWidth, naturalWidth == 0 ? 0 : 40, origin);
        }

        public Builder image(String src, int naturalWidth, int naturalHeight, ImageOrigin origin) {
            images.add(new ImageRef(src, Snapshots.url(src), "Alt-Text", naturalWidth,
                    naturalHeight, origin));
            return this;
        }

        public Builder image(ImageRef image) {
            images.add(image);
            return this;
        }

        /** A media element without a {@code src} or {@code source} child — a broken element. */
        public Builder media(MediaKind kind, int readyState, double duration, String errorCode) {
            media.add(new MediaRef(kind, List.of(), readyState, duration, errorCode));
            return this;
        }

        public Builder media(MediaKind kind, String src, int readyState, double duration,
                String errorCode) {
            media.add(new MediaRef(kind, List.of(Snapshots.url(src)), readyState, duration,
                    errorCode));
            return this;
        }

        public Builder frame(String src, boolean sameOrigin, int contentTextLength) {
            frames.add(new FrameRef(Snapshots.url(src), "Eingebettet", true, contentTextLength,
                    sameOrigin));
            return this;
        }

        public Builder consoleError(String message) {
            console.add(new ConsoleMessage("error", message, url.value()));
            return this;
        }

        public Builder consoleError(String message, String location) {
            console.add(new ConsoleMessage("error", message, location));
            return this;
        }

        /** What Chromium reports for a frame refused by X-Frame-Options or a CSP. */
        public Builder blockedDocument(String src) {
            failed.add(new FailedRequest(src, "GET", "document", null,
                    "net::ERR_BLOCKED_BY_RESPONSE"));
            return this;
        }

        public PageSnapshot build() {
            return new PageSnapshot(url, requestedUrl, depth, true, null, httpStatus,
                    Map.copyOf(headers),
                    redirectChain == null ? List.of(url.value()) : redirectChain,
                    120L, "Titel", "de", text, SimHash.of(text), links, images, media, frames,
                    List.<FormRef>of(), console, failed, "seite.png");
        }

        public PageSnapshot unreachable(String reason) {
            return PageSnapshot.unreachable(url, requestedUrl, depth, reason,
                    List.copyOf(console), List.copyOf(failed));
        }
    }
}