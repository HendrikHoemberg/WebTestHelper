package dev.hendrikhoemberg.webtesthelper.model;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * Run-scoped facts a page check needs but a single {@link PageSnapshot} cannot carry
 * (deviation D3). The soft-404 probe is the motivating case: whether a 200 response is really
 * the site's not-found page is a fact about the run, learned once at crawl start.
 *
 * <p>Plan 3b extends this record with the URL verification results that {@code DEAD_LINK} and
 * {@code FILE_DOWNLOAD} consume.
 */
public record RunFacts(long runId, RunScope scope, Instant startedAt,
                       SoftNotFoundProbe softNotFound, UrlVerifications verifications,
                       TlsCertificateFact tlsCertificate, List<String> sitemapUrls) {

    public RunFacts {
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(startedAt, "startedAt");
        softNotFound = softNotFound == null ? SoftNotFoundProbe.NONE : softNotFound;
        verifications = verifications == null ? UrlVerifications.EMPTY : verifications;
        tlsCertificate = tlsCertificate == null ? TlsCertificateFact.NONE : tlsCertificate;
        sitemapUrls = sitemapUrls == null ? List.of() : List.copyOf(sitemapUrls);
    }

    public static RunFacts of(RunSnapshots snapshots, RunScope scope, Instant startedAt) {
        return new RunFacts(snapshots.runId(), scope, startedAt, snapshots.softNotFound(),
                UrlVerifications.EMPTY, TlsCertificateFact.NONE, List.of());
    }

    public static RunFacts of(RunSnapshots snapshots, RunScope scope, Instant startedAt,
            UrlVerifications verifications, TlsCertificateFact tlsCertificate,
            List<String> sitemapUrls) {
        return new RunFacts(snapshots.runId(), scope, startedAt, snapshots.softNotFound(),
                verifications, tlsCertificate, sitemapUrls);
    }
}
