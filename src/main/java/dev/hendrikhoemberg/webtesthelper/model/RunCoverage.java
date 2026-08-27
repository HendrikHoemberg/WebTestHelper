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
 * <p>Interaction checks are recorded separately (D74): an interaction check runs on a handful of
 * pages, so {@link #interactionCheckTypes} and {@link #interactionLocationKeys} carry the types and
 * pages this run actually drove.
 */
public record RunCoverage(Set<CheckType> checkTypes,
                          Set<String> locationKeys,
                          boolean wholeSite,
                          Set<CheckType> interactionCheckTypes,
                          Set<String> interactionLocationKeys) {

    /**
     * Copies all sets: coverage decides what a run is allowed to resolve, so a caller holding
     * on to the collections it passed must not be able to widen that scope afterwards.
     */
    public RunCoverage {
        checkTypes = Set.copyOf(checkTypes);
        locationKeys = Set.copyOf(locationKeys);
        interactionCheckTypes = Set.copyOf(interactionCheckTypes);
        interactionLocationKeys = Set.copyOf(interactionLocationKeys);
    }

    public RunCoverage(Set<CheckType> checkTypes, Set<String> locationKeys, boolean wholeSite) {
        this(checkTypes, locationKeys, wholeSite, Set.of(), Set.of());
    }

    public static RunCoverage of(RunScope scope,
                                 Collection<String> checkTypeNames,
                                 Collection<String> coveredUrls,
                                 Collection<String> snapshotUrls,
                                 boolean partialCoverage) {
        return of(scope, checkTypeNames, coveredUrls, snapshotUrls, partialCoverage, java.util.List.of(), java.util.List.of());
    }

    public static RunCoverage of(RunScope scope,
                                 Collection<String> checkTypeNames,
                                 Collection<String> coveredUrls,
                                 Collection<String> snapshotUrls,
                                 boolean partialCoverage,
                                 Collection<String> interactionCheckTypeNames,
                                 Collection<String> interactionUrls) {
        Set<CheckType> checkTypes = parseCheckTypes(checkTypeNames);
        Set<String> locationKeys = parseLocationKeys(coveredUrls, snapshotUrls);
        Set<CheckType> interactionCheckTypes = parseCheckTypes(interactionCheckTypeNames);
        Set<String> interactionLocationKeys = parseLocationKeys(interactionUrls, null);

        return new RunCoverage(checkTypes, locationKeys, scope.crawlsWholeSite() && !partialCoverage,
                interactionCheckTypes, interactionLocationKeys);
    }

    private static Set<CheckType> parseCheckTypes(Collection<String> names) {
        Set<CheckType> types = new LinkedHashSet<>();
        if (names != null) {
            for (String name : names) {
                if (name == null) {
                    continue;
                }
                try {
                    types.add(CheckType.valueOf(name));
                } catch (IllegalArgumentException ignored) {
                }
            }
        }
        return types;
    }

    private static Set<String> parseLocationKeys(Collection<String> urls1, Collection<String> urls2) {
        Set<String> keys = new LinkedHashSet<>();
        parseLocationKeysInto(urls1, keys);
        parseLocationKeysInto(urls2, keys);
        return keys;
    }

    private static void parseLocationKeysInto(Collection<String> urls, Set<String> target) {
        if (urls == null) {
            return;
        }
        for (String url : urls) {
            if (url == null) {
                continue;
            }
            Optional<NormalizedUrl> normalized = UrlNormalizer.normalize(url);
            if (normalized.isPresent()) {
                target.add(normalized.get().locationKey());
            } else if (url.startsWith("/")) {
                target.add(url);
            }
        }
    }
}
