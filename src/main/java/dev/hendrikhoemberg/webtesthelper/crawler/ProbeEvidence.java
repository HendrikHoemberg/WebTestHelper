package dev.hendrikhoemberg.webtesthelper.crawler;

import java.util.List;
import java.util.Set;

/**
 * What a single {@link SetupProbe#probe} sweep discovered about a site. Every list holds
 * normalised absolute URLs, so the guided-setup screen can link to what it claims to have found.
 */
public record ProbeEvidence(
        boolean reachable,
        String unreachableReason,
        List<String> pagesVisited,
        List<String> formPages,
        List<String> mediaPages,
        List<String> mapPages,
        Set<String> languages,
        List<String> documentLinks,
        boolean sitemapFound,
        boolean secure) {

    public ProbeEvidence {
        pagesVisited = List.copyOf(pagesVisited);
        formPages = List.copyOf(formPages);
        mediaPages = List.copyOf(mediaPages);
        mapPages = List.copyOf(mapPages);
        languages = Set.copyOf(languages);
        documentLinks = List.copyOf(documentLinks);
    }

    /** The evidence for a site whose start page could not be reached: everything empty. */
    public static ProbeEvidence unreachable(String reason) {
        return new ProbeEvidence(false, reason, List.of(), List.of(), List.of(), List.of(),
                Set.of(), List.of(), false, false);
    }
}
