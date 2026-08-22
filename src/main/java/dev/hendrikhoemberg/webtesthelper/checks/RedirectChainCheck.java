package dev.hendrikhoemberg.webtesthelper.checks;

import dev.hendrikhoemberg.webtesthelper.model.CheckFinding;
import dev.hendrikhoemberg.webtesthelper.model.CheckType;
import dev.hendrikhoemberg.webtesthelper.model.Evidence;
import dev.hendrikhoemberg.webtesthelper.model.PageSnapshot;
import dev.hendrikhoemberg.webtesthelper.model.Severity;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * No loops and no long hop chains (spec 7.1).
 *
 * <p>{@code redirectChain} lists the requested URL first and the final URL last, so a size of 1
 * means no redirect at all and the hop count is {@code size - 1}. The default limit is three
 * because http to https to www to page is three legitimate hops on a real site; the fixture's
 * chain is exactly three, which is why its test drives the limit down per site instead.
 *
 * <p>A loop reaches this check as an <em>unreachable</em> page: measured, Chromium abandons the
 * navigation with {@code net::ERR_TOO_MANY_REDIRECTS} and there is no chain left to inspect.
 * This check owns those pages, and {@code PAGE_UNREACHABLE} steps aside, so one broken page
 * produces one finding that says what is actually wrong.
 */
public final class RedirectChainCheck implements PageCheck {

    static final String TOO_MANY_HOPS = "finding.REDIRECT_CHAIN.tooManyHops";
    static final String LOOP = "finding.REDIRECT_CHAIN.loop";
    static final int DEFAULT_MAX_HOPS = 3;

    @Override
    public CheckType type() {
        return CheckType.REDIRECT_CHAIN;
    }

    @Override
    public Severity defaultSeverity() {
        return Severity.WARN;
    }

    @Override
    public Set<String> messageKeys() {
        return Set.of(TOO_MANY_HOPS, LOOP);
    }

    @Override
    public List<CheckFinding> evaluate(PageSnapshot snapshot, CheckConfig config) {
        if (!snapshot.reachable()) {
            String reason = snapshot.unreachableReason() == null ? "" : snapshot.unreachableReason();
            return reason.contains(PageUnreachableCheck.REDIRECT_LOOP_MARKER)
                    ? List.of(finding(snapshot, config, LOOP, List.of(), reason))
                    : List.of();
        }
        List<String> chain = snapshot.redirectChain();
        int hops = chain.size() - 1;
        if (hops <= 0) {
            return List.of();
        }
        Set<String> seen = new HashSet<>();
        if (chain.stream().anyMatch(url -> !seen.add(url))) {
            return List.of(finding(snapshot, config, LOOP, List.of(), String.join(" -> ", chain)));
        }
        if (hops <= config.option("maxHops", DEFAULT_MAX_HOPS)) {
            return List.of();
        }
        return List.of(finding(snapshot, config, TOO_MANY_HOPS,
                List.of(String.valueOf(hops), chain.getLast()), String.join(" -> ", chain)));
    }

    /**
     * The subject is the URL that was <em>requested</em>, not the one that answered: that is the
     * address someone wrote into a link and the thing they would have to change.
     */
    private CheckFinding finding(PageSnapshot snapshot, CheckConfig config, String messageKey,
            List<String> args, String detail) {
        return new CheckFinding(type(), config.severity(), snapshot.redirectChain().getFirst(),
                snapshot.url(), messageKey, args,
                new Evidence(snapshot.screenshotPath(), null, null, detail, List.of()));
    }
}