package dev.hendrikhoemberg.webtesthelper.checks;

import dev.hendrikhoemberg.webtesthelper.model.CheckFinding;
import dev.hendrikhoemberg.webtesthelper.model.CheckType;
import dev.hendrikhoemberg.webtesthelper.model.Evidence;
import dev.hendrikhoemberg.webtesthelper.model.PageSnapshot;
import dev.hendrikhoemberg.webtesthelper.model.Severity;

import java.util.List;
import java.util.Set;

/**
 * A page that timed out or crashed the tab (spec 14). The crawl already survived it — one bad
 * page never kills a run — and this check is what turns that survival into something a person
 * can act on.
 *
 * <p>A redirect loop is deliberately left to {@code REDIRECT_CHAIN}. Measured: Chromium fails
 * a loop with {@code net::ERR_TOO_MANY_REDIRECTS}, so the page arrives here as unreachable, and
 * reporting it under two names would be exactly the noise spec 8 spends its effort avoiding.
 */
public final class PageUnreachableCheck implements PageCheck {

    static final String NAVIGATION = "finding.PAGE_UNREACHABLE.navigation";

    /** Chromium's marker for a redirect loop. {@code REDIRECT_CHAIN} owns those pages. */
    static final String REDIRECT_LOOP_MARKER = "ERR_TOO_MANY_REDIRECTS";

    @Override
    public CheckType type() {
        return CheckType.PAGE_UNREACHABLE;
    }

    @Override
    public Severity defaultSeverity() {
        return Severity.ERROR;
    }

    @Override
    public Set<String> messageKeys() {
        return Set.of(NAVIGATION);
    }

    @Override
    public List<CheckFinding> evaluate(PageSnapshot snapshot, CheckConfig config) {
        if (snapshot.reachable()) {
            return List.of();
        }
        String reason = snapshot.unreachableReason() == null ? "" : snapshot.unreachableReason();
        if (reason.contains(REDIRECT_LOOP_MARKER)) {
            return List.of();
        }
        return List.of(new CheckFinding(type(), config.severity(), snapshot.url().value(),
                snapshot.url(), NAVIGATION, List.of(firstLine(reason)),
                new Evidence(null, null, null, reason, List.of())));
    }

    /** Playwright's error is a multi-line dump; the first line is the part a human reads. */
    private static String firstLine(String reason) {
        int newline = reason.indexOf('\n');
        return (newline < 0 ? reason : reason.substring(0, newline)).trim();
    }
}