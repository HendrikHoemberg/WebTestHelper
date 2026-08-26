package dev.hendrikhoemberg.webtesthelper.reporting;

import dev.hendrikhoemberg.webtesthelper.model.RunScope;
import dev.hendrikhoemberg.webtesthelper.runner.RunSummary;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static java.util.Comparator.naturalOrder;

public record DigestWindow(RunScope scope, List<RunSummary> runs, Instant closedAt) {

    public List<Long> runIds() {
        return runs.stream().map(RunSummary::id).toList();
    }

    public static Optional<DigestWindow> close(
            RunScope scope,
            List<RunSummary> undigested,
            boolean inFlight,
            Instant now,
            Duration settle,
            Duration maxWait
    ) {
        if (undigested.isEmpty()) return Optional.empty();
        Instant newest = undigested.stream().map(RunSummary::finishedAt).max(naturalOrder()).orElseThrow();
        Instant oldest = undigested.stream().map(RunSummary::finishedAt).min(naturalOrder()).orElseThrow();
        boolean quiet   = !inFlight && !newest.isAfter(now.minus(settle));
        boolean overdue = oldest.isBefore(now.minus(maxWait));
        return (quiet || overdue) ? Optional.of(new DigestWindow(scope, undigested, now)) : Optional.empty();
    }
}
