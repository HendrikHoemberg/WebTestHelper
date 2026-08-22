package dev.hendrikhoemberg.webtesthelper.model;

import java.util.List;
import java.util.Map;

/**
 * Everything a single page visit produced. Immutable and browser-free: a check receives one of
 * these and never a {@code Page}. {@code navigate once, check many} (spec 5.2) means the crawler
 * produces exactly one snapshot per URL and every page check is a pure function over it.
 */
public record PageSnapshot(
        NormalizedUrl url,              // normalised final URL, after redirects
        String requestedUrl,            // what the frontier asked for, unnormalised
        int depth,
        boolean reachable,              // false => navigation failed; most fields are empty
        String unreachableReason,       // null when reachable
        int httpStatus,                 // 0 when unreachable
        Map<String, String> responseHeaders,   // lowercase keys
        List<String> redirectChain,     // requested URL first, final URL last; size 1 = no redirect
        long loadMillis,
        String title,
        String htmlLang,                // <html lang>, "" when absent
        String textContent,
        long textSimhash,               // SimHash.of(textContent)
        List<LinkRef> links,
        List<ImageRef> images,
        List<MediaRef> media,
        List<FrameRef> frames,
        List<AlternateRef> alternates,
        List<FormRef> forms,
        List<ConsoleMessage> consoleMessages,
        List<FailedRequest> failedRequests,
        String screenshotPath) {        // relative to the run's artifact directory; null if none

    public PageSnapshot {
        responseHeaders = Map.copyOf(responseHeaders);
        redirectChain = List.copyOf(redirectChain);
        links = List.copyOf(links);
        images = List.copyOf(images);
        media = List.copyOf(media);
        frames = List.copyOf(frames);
        alternates = List.copyOf(alternates);
        forms = List.copyOf(forms);
        consoleMessages = List.copyOf(consoleMessages);
        failedRequests = List.copyOf(failedRequests);
    }

    public boolean isSecure() {
        return url.isSecure();
    }

    public List<LinkRef> internalLinks() {
        return links.stream().filter(LinkRef::internal).toList();
    }

    public List<LinkRef> externalLinks() {
        return links.stream().filter(l -> !l.internal()).toList();
    }

    public List<ConsoleMessage> errors() {
        return consoleMessages.stream()
                .filter(m -> "error".equalsIgnoreCase(m.level()))
                .toList();
    }

    public static PageSnapshot unreachable(NormalizedUrl url, String requestedUrl, int depth,
            String reason, List<ConsoleMessage> console, List<FailedRequest> failed) {
        return new PageSnapshot(url, requestedUrl, depth, false, reason, 0,
                Map.of(), List.of(requestedUrl), 0L, "", "", "", 0L,
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), console, failed, null);
    }
}