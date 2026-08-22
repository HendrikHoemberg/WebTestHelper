package dev.hendrikhoemberg.webtesthelper.model;

import java.time.Instant;
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
                       SoftNotFoundProbe softNotFound) {

    public RunFacts {
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(startedAt, "startedAt");
        softNotFound = softNotFound == null ? SoftNotFoundProbe.NONE : softNotFound;
    }

    public static RunFacts of(RunSnapshots snapshots, RunScope scope, Instant startedAt) {
        return new RunFacts(snapshots.runId(), scope, startedAt, snapshots.softNotFound());
    }
}