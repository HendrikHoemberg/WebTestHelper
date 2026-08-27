package dev.hendrikhoemberg.webtesthelper.model;

import java.util.Collection;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.Map;
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
 * pages, so its resolution scope cannot be the crawl's. The two halves answer different questions
 * and both are load-bearing:
 * <ul>
 *   <li>{@link #interactionCheckTypes} is every interaction type this run was <em>allowed</em> to
 *       drive. It exists to keep those types out of the crawl-scoped resolve statement — a type
 *       whose every target failed is still an interaction type, and resolving it against 300
 *       crawled URLs is exactly the silent false-resolution D74 and D79 forbid.</li>
 *   <li>{@link #interactionLocationKeys} maps each type to the pages it was <em>actually</em>
 *       driven on. It is a map rather than one flat set because a flat set is a cartesian product
 *       again at smaller scale: with check A driven on / and check B on /kontakt, a single
 *       {@code types x pages} pair would let A resolve its finding on /kontakt, which it never
 *       looked at.</li>
 * </ul>
 * A type present in {@link #interactionCheckTypes} but absent (or empty) in
 * {@link #interactionLocationKeys} therefore resolves nothing at all, which is the honest answer
 * for a check that could not see.
 */
public record RunCoverage(Set<CheckType> checkTypes,
                          Set<String> locationKeys,
                          boolean wholeSite,
                          Set<CheckType> interactionCheckTypes,
                          Map<CheckType, Set<String>> interactionLocationKeys) {

    /**
     * Copies all sets: coverage decides what a run is allowed to resolve, so a caller holding
     * on to the collections it passed must not be able to widen that scope afterwards.
     */
    public RunCoverage {
        checkTypes = Set.copyOf(checkTypes);
        locationKeys = Set.copyOf(locationKeys);
        interactionCheckTypes = Set.copyOf(interactionCheckTypes);
        Map<CheckType, Set<String>> copiedLocations = new EnumMap<>(CheckType.class);
        interactionLocationKeys.forEach((type, keys) -> copiedLocations.put(type, Set.copyOf(keys)));
        interactionLocationKeys = Map.copyOf(copiedLocations);
    }

    public RunCoverage(Set<CheckType> checkTypes, Set<String> locationKeys, boolean wholeSite) {
        this(checkTypes, locationKeys, wholeSite, Set.of(), Map.of());
    }

    public static RunCoverage of(RunScope scope,
                                 Collection<String> checkTypeNames,
                                 Collection<String> coveredUrls,
                                 Collection<String> snapshotUrls,
                                 boolean partialCoverage) {
        return of(scope, checkTypeNames, coveredUrls, snapshotUrls, partialCoverage, Set.of(), Map.of());
    }

    /**
     * The interaction half is passed already typed rather than as persisted names: unlike the
     * crawl's coverage it is never read back from a run row, so there is no historical data to
     * defend against and no reason to reduce a {@link CheckType} to a string and back.
     */
    public static RunCoverage of(RunScope scope,
                                 Collection<String> checkTypeNames,
                                 Collection<String> coveredUrls,
                                 Collection<String> snapshotUrls,
                                 boolean partialCoverage,
                                 Set<CheckType> interactionCheckTypes,
                                 Map<CheckType, ? extends Collection<String>> interactionUrlsByType) {
        Set<CheckType> checkTypes = parseCheckTypes(checkTypeNames);
        Set<String> locationKeys = parseLocationKeys(coveredUrls, snapshotUrls);

        Map<CheckType, Set<String>> interactionLocationKeys = new EnumMap<>(CheckType.class);
        if (interactionUrlsByType != null) {
            interactionUrlsByType.forEach((type, urls) -> {
                if (type != null) {
                    interactionLocationKeys.put(type, parseLocationKeys(urls, null));
                }
            });
        }

        return new RunCoverage(checkTypes, locationKeys, scope.crawlsWholeSite() && !partialCoverage,
                interactionCheckTypes == null ? Set.of() : interactionCheckTypes,
                interactionLocationKeys);
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
            normalized.ifPresent(value -> target.add(value.locationKey()));
        }
    }
}
