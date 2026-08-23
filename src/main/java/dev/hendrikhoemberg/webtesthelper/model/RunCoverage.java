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
 */
public record RunCoverage(Set<CheckType> checkTypes, Set<String> locationKeys, boolean complete) {

    public static RunCoverage of(Collection<String> checkTypeNames,
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
        return new RunCoverage(checkTypes, locationKeys, !partialCoverage);
    }
}
