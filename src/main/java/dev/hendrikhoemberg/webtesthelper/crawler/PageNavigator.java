package dev.hendrikhoemberg.webtesthelper.crawler;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Frame;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.PlaywrightException;
import com.microsoft.playwright.Request;
import com.microsoft.playwright.Response;
import com.microsoft.playwright.options.LoadState;
import com.microsoft.playwright.options.WaitUntilState;
import dev.hendrikhoemberg.webtesthelper.model.ConsoleMessage;
import dev.hendrikhoemberg.webtesthelper.model.FailedRequest;
import dev.hendrikhoemberg.webtesthelper.model.FormFieldRef;
import dev.hendrikhoemberg.webtesthelper.model.FormRef;
import dev.hendrikhoemberg.webtesthelper.model.AlternateRef;
import dev.hendrikhoemberg.webtesthelper.model.SubresourceKind;
import dev.hendrikhoemberg.webtesthelper.model.SubresourceRef;
import dev.hendrikhoemberg.webtesthelper.model.FrameRef;
import dev.hendrikhoemberg.webtesthelper.model.MapPaintState;
import dev.hendrikhoemberg.webtesthelper.model.ImageOrigin;
import dev.hendrikhoemberg.webtesthelper.model.ImageRef;
import dev.hendrikhoemberg.webtesthelper.model.LinkRef;
import dev.hendrikhoemberg.webtesthelper.model.MediaKind;
import dev.hendrikhoemberg.webtesthelper.model.MediaRef;
import dev.hendrikhoemberg.webtesthelper.model.NormalizedUrl;
import dev.hendrikhoemberg.webtesthelper.model.PageSnapshot;
import dev.hendrikhoemberg.webtesthelper.model.SimHash;
import dev.hendrikhoemberg.webtesthelper.model.SiteContext;
import dev.hendrikhoemberg.webtesthelper.model.UrlNormalizer;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * One navigation, one script evaluation, one screenshot — and out comes a {@link PageSnapshot}
 * (spec 5.2). Each round-trip to the browser costs milliseconds that multiply by page count, so
 * the extraction script collects links, images, media, frames, forms, text and language in a
 * single {@code page.evaluate} pass.
 *
 * <p>Capture is called only from inside {@link BrowserPool#submit}. Playwright objects are
 * thread-confined: the {@code Browser} arrives from the pool's worker thread, everything derived
 * from it stays in this method. Only the {@link PageSnapshot} crosses threads.
 *
 * <p>The context is fresh <em>per page</em>, where spec 5.4 says per batch: a batch fans out
 * across every pool worker, so it never corresponds to one thread and cannot own one context.
 * Per-page is what actually delivers what that paragraph asks for — clean cookies on every page,
 * bounded memory, reproducible runs — at the cost of one context creation per visit.
 */
@Component
public class PageNavigator {

    private static final String EXTRACT_JS;      // extraction + the inlined map-paint probe
    private static final String MAP_PAINT_JS;    // the single map-paint probe source
    private static final String MAP_PAINT_INVOCATION;   // the probe run on one frame's document
    private static final String MAP_PAINT_MARKER = "// [[MAP_PAINT_PROBE]]";

    static {
        try {
            String extract = new ClassPathResource("crawler/extract.js")
                    .getContentAsString(StandardCharsets.UTF_8);
            MAP_PAINT_JS = new ClassPathResource("crawler/mapPaint.js")
                    .getContentAsString(StandardCharsets.UTF_8);
            // Same-origin path: extract.js references mapPaintOf, so the probe is inlined into it.
            EXTRACT_JS = extract.replace(MAP_PAINT_MARKER, MAP_PAINT_JS);
            // Cross-origin path: the parent page's script cannot read the frame, so run the same
            // probe in the frame's own context (spec 7.1's Maps case).
            MAP_PAINT_INVOCATION = "(() => {\n" + MAP_PAINT_JS + "\nreturn mapPaintOf(document);\n})()";
        } catch (IOException e) {
            throw new IllegalStateException(
                    "crawler/extract.js oder crawler/mapPaint.js fehlt im Klassenpfad", e);
        }
    }

    private final CrawlerProperties properties;
    private final HostThrottle throttle;

    public PageNavigator(CrawlerProperties properties, HostThrottle throttle) {
        this.properties = properties;
        this.throttle = throttle;
    }

    public PageSnapshot capture(Browser browser, CrawlTarget target, SiteContext site,
            Path runArtifactDir) {
        NormalizedUrl requested = UrlNormalizer.normalize(target.url()).orElse(null);
        if (requested == null) {
            return PageSnapshot.unreachable(fallbackUrl(), target.url(), target.depth(),
                    "Nicht als URL interpretierbar", List.of(), List.of());
        }
        throttle.await(requested.host(), properties.perHostDelay());

        List<ConsoleMessage> console = Collections.synchronizedList(new ArrayList<>());
        List<FailedRequest> failed = Collections.synchronizedList(new ArrayList<>());
        long startedAt = System.nanoTime();

        try (BrowserContext context = browser.newContext(new Browser.NewContextOptions()
                .setUserAgent(site.effectiveUserAgent())
                .setViewportSize(1366, 900)
                .setIgnoreHTTPSErrors(true)
                .setLocale("de-DE"))) {

            Page page = context.newPage();
            page.onConsoleMessage(message -> console.add(new ConsoleMessage(
                    message.type(), truncate(message.text(), 500), message.location())));
            page.onPageError(error -> console.add(new ConsoleMessage(
                    "error", truncate(error, 500), target.url())));
            page.onRequestFailed(request -> failed.add(new FailedRequest(
                    request.url(), request.method(), request.resourceType(), null,
                    request.failure())));
            page.onResponse(response -> {
                if (response.status() >= 400) {
                    failed.add(new FailedRequest(response.url(), response.request().method(),
                            response.request().resourceType(), response.status(), null));
                }
            });

            Response response = page.navigate(target.url(), new Page.NavigateOptions()
                    .setTimeout(properties.navigationTimeout().toMillis())
                    .setWaitUntil(WaitUntilState.LOAD));
            try {
                page.waitForLoadState(LoadState.NETWORKIDLE,
                        new Page.WaitForLoadStateOptions().setTimeout(5000));
            } catch (PlaywrightException stillBusy) {
                // A page with a poll or a live widget never goes idle. Not a failure — extract anyway.
            }

            NormalizedUrl pageUrl = UrlNormalizer.normalize(page.url()).orElse(requested);
            Extracted extracted = map(page.evaluate(EXTRACT_JS), site);
            List<FrameRef> frames = refetchCrossOriginMapsPaint(extracted.frames(), page);
            String screenshotPath = screenshot(page, requested, runArtifactDir);

            Map<String, String> headers = new HashMap<>();
            if (response != null) {
                for (Map.Entry<String, String> header : response.allHeaders().entrySet()) {
                    headers.put(header.getKey().toLowerCase(Locale.ROOT), header.getValue());
                }
            }

            List<String> redirectChain = new ArrayList<>();
            Request current = response == null ? null : response.request();
            while (current != null) {
                redirectChain.add(current.url());
                current = current.redirectedFrom();
            }
            Collections.reverse(redirectChain);
            if (redirectChain.isEmpty()) {
                redirectChain.add(pageUrl.value());
            }

            long loadMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);

            return new PageSnapshot(pageUrl, target.url(), target.depth(), true, null,
                    response == null ? 0 : response.status(), headers, redirectChain, loadMillis,
                    extracted.title(), extracted.lang(), extracted.text(),
                    SimHash.of(extracted.text()), extracted.links(), extracted.images(),
                    extracted.media(), frames, extracted.alternates(),
                    extracted.subresources(), extracted.forms(),
                    List.copyOf(console), List.copyOf(failed), screenshotPath);
        } catch (PlaywrightException e) {
            return PageSnapshot.unreachable(requested, target.url(), target.depth(),
                    truncate(e.getMessage(), 500), List.copyOf(console), List.copyOf(failed));
        }
    }

    /**
     * The single place where the script vocabulary (raw/abs/w/h/textLength/error…) meets the model
     * vocabulary (rawSource/target/naturalWidth/naturalHeight/contentTextLength/errorCode).
     */
    private Extracted map(Object raw, SiteContext site) {
        if (!(raw instanceof Map<?, ?> root)) {
            return Extracted.EMPTY;
        }
        Map<String, Object> data = cast(root);

        List<LinkRef> links = new ArrayList<>();
        for (Object item : listOf(data.get("links"))) {
            Map<String, Object> link = cast(item);
            UrlNormalizer.normalize(asString(link.get("abs"))).ifPresent(target -> links.add(
                    new LinkRef(asString(link.get("raw")), target, asString(link.get("text")),
                            site.baseUrl().sameSiteAs(target), asString(link.get("rel")))));
        }

        List<ImageRef> images = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Object item : listOf(data.get("images"))) {
            Map<String, Object> image = cast(item);
            String abs = asString(image.get("abs"));
            String origin = asString(image.get("origin"));
            UrlNormalizer.normalize(abs).ifPresent(target -> {
                String key = target.value() + '\u0000' + origin;
                if (seen.add(key)) {
                    images.add(new ImageRef(asString(image.get("raw")), target,
                            asString(image.get("alt")), intOf(image.get("w")), intOf(image.get("h")),
                            ImageOrigin.valueOf(origin)));
                }
            });
        }

        List<MediaRef> media = new ArrayList<>();
        for (Object item : listOf(data.get("media"))) {
            Map<String, Object> entry = cast(item);
            List<NormalizedUrl> sources = new ArrayList<>();
            for (Object source : listOf(entry.get("sources"))) {
                UrlNormalizer.normalize(asString(source)).ifPresent(sources::add);
            }
            media.add(new MediaRef(MediaKind.valueOf(asString(entry.get("kind"))), sources,
                    intOf(entry.get("readyState")), doubleOf(entry.get("duration")),
                    asString(entry.get("error"))));
        }

        List<FrameRef> frames = new ArrayList<>();
        for (Object item : listOf(data.get("frames"))) {
            Map<String, Object> frame = cast(item);
            UrlNormalizer.normalize(asString(frame.get("src"))).ifPresent(src -> frames.add(
                    new FrameRef(src, asString(frame.get("title")), boolOf(frame.get("loaded")),
                            intOf(frame.get("textLength")), boolOf(frame.get("sameOrigin")),
                            paintStateOf(asString(frame.get("paintState"))))));
        }

        List<AlternateRef> alternates = new ArrayList<>();
        for (Object item : listOf(data.get("alternates"))) {
            Map<String, Object> alternate = cast(item);
            UrlNormalizer.normalize(asString(alternate.get("abs"))).ifPresent(target ->
                    alternates.add(new AlternateRef(asString(alternate.get("lang")), target)));
        }

        List<SubresourceRef> subresources = new ArrayList<>();
        for (Object item : listOf(data.get("subresources"))) {
            Map<String, Object> subresource = cast(item);
            SubresourceKind kind = "script".equals(asString(subresource.get("kind")))
                    ? SubresourceKind.SCRIPT
                    : SubresourceKind.STYLESHEET;
            UrlNormalizer.normalize(asString(subresource.get("abs"))).ifPresent(target ->
                    subresources.add(new SubresourceRef(kind, target)));
        }

        List<FormRef> forms = new ArrayList<>();
        for (Object item : listOf(data.get("forms"))) {
            Map<String, Object> form = cast(item);
            List<FormFieldRef> fields = new ArrayList<>();
            for (Object field : listOf(form.get("fields"))) {
                Map<String, Object> entry = cast(field);
                fields.add(new FormFieldRef(asString(entry.get("name")),
                        asString(entry.get("type")), asString(entry.get("label")),
                        asString(entry.get("autocomplete")), boolOf(entry.get("required"))));
            }
            forms.add(new FormRef(asString(form.get("id")), asString(form.get("action")),
                    asString(form.get("method")), fields));
        }

        return new Extracted(asString(data.get("title")), asString(data.get("lang")),
                asString(data.get("text")), links, images, media, frames, alternates,
                subresources, forms);
    }

    /** A screenshot failure downgrades to {@code screenshotPath = null}; it never fails the page. */
    private String screenshot(Page page, NormalizedUrl requested, Path runArtifactDir) {
        try {
            Files.createDirectories(runArtifactDir);
            String name = ScreenshotNames.screenshotName(requested.value());
            page.screenshot(new Page.ScreenshotOptions()
                    .setPath(runArtifactDir.resolve(name))
                    .setFullPage(false));
            return name;
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /**
     * For frames the parent page's script could not read (cross-origin), drive the frame directly
     * through its Playwright {@link Frame} and ask its own document whether the map painted. A
     * frame that cannot be read stays {@link MapPaintState#UNKNOWN} — absence of a signal, never a
     * finding (spec 7.1's Maps case).
     */
    private static List<FrameRef> refetchCrossOriginMapsPaint(List<FrameRef> frames, Page page) {
        Map<String, MapPaintState> resolved = new HashMap<>();
        for (FrameRef frame : frames) {
            if (frame.isMapsEmbed() && !frame.sameOrigin()
                    && frame.mapPaintState() == MapPaintState.UNKNOWN) {
                resolved.put(frame.src().value(), MapPaintState.UNKNOWN);
            }
        }
        if (resolved.isEmpty()) {
            return frames;
        }
        for (Frame pwFrame : page.frames()) {
            try {
                if (pwFrame.equals(page.mainFrame())) {
                    continue;
                }
                UrlNormalizer.normalize(pwFrame.url()).ifPresent(url -> {
                    if (resolved.containsKey(url.value())) {
                        resolved.put(url.value(), mapPaint(pwFrame));
                    }
                });
            } catch (RuntimeException e) {
                // A detached or navigating frame must never fail the page; it stays UNKNOWN.
            }
        }
        return frames.stream()
                .map(frame -> {
                    MapPaintState state = resolved.get(frame.src().value());
                    return state == null || state == frame.mapPaintState() ? frame
                            : frame.withMapPaintState(state);
                })
                .toList();
    }

    private static MapPaintState mapPaint(Frame frame) {
        try {
            Object result = frame.evaluate(MAP_PAINT_INVOCATION);
            return result == null ? MapPaintState.UNKNOWN : paintStateOf(result.toString());
        } catch (PlaywrightException e) {
            return MapPaintState.UNKNOWN;
        }
    }

    private static MapPaintState paintStateOf(String raw) {
        if (raw == null) {
            return MapPaintState.UNKNOWN;
        }
        try {
            return MapPaintState.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return MapPaintState.UNKNOWN;
        }
    }

    private static String truncate(String value, int max) {
        if (value == null || value.length() <= max) {
            return value;
        }
        return value.substring(0, max);
    }

    /** A referent for a URL that cannot even be interpreted — never points anywhere real. */
    private static NormalizedUrl fallbackUrl() {
        return new NormalizedUrl("http", "ungueltig", 80, "/", null);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> cast(Object value) {
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> listOf(Object value) {
        return value instanceof List<?> list ? (List<Object>) list : List.of();
    }

    private static String asString(Object value) {
        return value == null ? null : value.toString();
    }

    private static boolean boolOf(Object value) {
        return value instanceof Boolean b && b;
    }

    /** Playwright hands back Integer for whole numbers and Double otherwise. */
    private static int intOf(Object value) {
        return value instanceof Number n ? n.intValue() : 0;
    }

    private static double doubleOf(Object value) {
        return value instanceof Number n ? n.doubleValue() : 0;
    }

    private record Extracted(String title, String lang, String text,
                             List<LinkRef> links, List<ImageRef> images, List<MediaRef> media,
                             List<FrameRef> frames, List<AlternateRef> alternates,
                             List<SubresourceRef> subresources, List<FormRef> forms) {

        private static final Extracted EMPTY =
                new Extracted("", "", "", List.of(), List.of(), List.of(), List.of(),
                        List.of(), List.of(), List.of());
    }
}