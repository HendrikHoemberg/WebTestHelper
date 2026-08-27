package dev.hendrikhoemberg.webtesthelper.model;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;

/**
 * The set of checks and pages a run actually exercised. Distinct from a run's own coverage
 * columns (spec 6.4): this is the value type later tasks derive resolution scope from.
 *
 * <p>Every URL is reduced through {@link UrlNormalizer#normalize} and its {@code locationKey};
 * an unparseable URL is dropped rather than throwing, and the two sources are unioned so a
 * page reached by both the live crawl and the snapshot is counted once (D25). A check-type
 * name that is not a {@link CheckType} is ignored — the column is historical data, and a
 * renamed enum constant must not make old runs unreadable.
 *
 * <p>{@code wholeSite} answers one question and only that one: may this run's silence resolve a
 * <em>site-wide</em> finding, the {@code locationKey = "*"} of spec 6.2? Per-page findings are
 * scoped by {@link #locationKeys}, which needs no such flag. Two conditions have to hold, and
 * the second one is easy to lose: the run must have crawled the whole site
 * ({@link RunScope#crawlsWholeSite()}) <em>and</em> it must have finished doing so. A budget-capped
 * full crawl saw an arbitrary slice; a pulse run saw a pinned list of a dozen URLs and drained its
 * frontier doing it, so "the frontier ran dry" alone says nothing about site coverage.
 */
public record RunCoverage(Set<CheckType> checkTypes, Set<String> locationKeys, boolean wholeSite) {

    /**
     * Copies both sets: coverage decides what a run is allowed to resolve, so a caller holding
     * on to the collections it passed must not be able to widen that scope afterwards.
     */
    public RunCoverage {
        checkTypes = Set.copyOf(checkTypes);
        locationKeys = Set.copyOf(locationKeys);
    }

    public static RunCoverage of(RunScope scope,
                                 Collection<String> checkTypeNames,
                                 Collection<String> coveredUrls,
                                 Collection<String> snapshotUrls,
                                 boolean partialCoverage) {
        Set<CheckType> checkTypes = new LinkedHashSet<>();
        if (checkTypeNames != null) {
            for (String name : checkTypeNames) {
                if (name == null) {
                    continue;
                }
                try {
                    checkTypes.add(CheckType.valueOf(name));
                } catch (IllegalArgumentException ignored) {
                }
            }
        }
        Set<String> locationKeys = new LinkedHashSet<>();
        if (coveredUrls != null) {
            coveredUrls.stream().map(UrlNormalizer::normalize).filter(Optional::isPresent)
                    .map(o -> o.get().locationKey()).forEach(locationKeys::add);
        }
        if (snapshotUrls != null) {
            snapshotUrls.stream().map(UrlNormalizer::normalize).filter(Optional::isPresent)
                    .map(o -> o.get().locationKey()).forEach(locationKeys::add);
        }
        return new RunCoverage(checkTypes, locationKeys, scope.crawlsWholeSite() && !partialCoverage);
    }
}
