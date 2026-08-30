package dev.hendrikhoemberg.webtesthelper.checks;

import dev.hendrikhoemberg.webtesthelper.model.CheckFinding;
import dev.hendrikhoemberg.webtesthelper.model.CheckType;
import dev.hendrikhoemberg.webtesthelper.model.Evidence;
import dev.hendrikhoemberg.webtesthelper.model.PageSnapshot;
import dev.hendrikhoemberg.webtesthelper.model.Severity;
import dev.hendrikhoemberg.webtesthelper.model.SimHash;
import dev.hendrikhoemberg.webtesthelper.model.SoftNotFoundProbe;

import java.util.List;
import java.util.Set;

/**
 * 2xx, plus soft-404 detection (spec 7.1).
 *
 * <p>Two anchors are learned at crawl start. The <strong>probe</strong> is {@code {baseUrl}/{uuid}}:
 * a random path cannot be a real page, so whatever answers for it is the site's not-found page.
 * The <strong>root reference</strong> is {@code {baseUrl}/}: the site's best-known real page,
 * what "real" looks like on this very site. A page is a soft 404 only within the absolute cut
 * <strong>and</strong> closer to the not-found fingerprint than to the root.
 *
 * <p>The absolute cutoff is <strong>16</strong> bits of a 64-bit SimHash, measured rather than
 * guessed. Against the fixture site with a real browser: an exact clone of the not-found page
 * scores 0, a not-found page that echoes the requested path 8–20, and the closest unrelated real
 * page 27 (then 31, 33, 35, 37). Sixteen sits in that gap with margin on both sides.
 *
 * <p>The root anchor exists because the gap is site-specific. Where the shared shell dominates
 * every page (a site that answers <em>any</em> path, even "/", with the same frame — measured on
 * theis-feinwerktechnik.de: probe and root fingerprint identically, d = 0), the distances from a
 * real page to probe and root are equal and the check is silent rather than reporting every
 * shell-like page. Where root and not-found page genuinely differ (fixture: d = 36), clones still
 * sit at 0 — clearly closer to the probe — and real pages at 27+ stay clear, so the previous
 * behaviour is unchanged. If the root could not itself be captured (unreachable, not 200, blank),
 * the check falls back to the probe-only rule for that site.
 *
 * <p>Override the absolute cutoff per site with {@code {"maxDistance": 20}} — and re-measure
 * before doing so, since a cutoff that eats real pages is worse than no check at all (spec 8).
 */
public final class PageStatusCheck implements PageCheck {

    static final String HTTP_ERROR = "finding.PAGE_STATUS.httpError";
    static final String SOFT_404 = "finding.PAGE_STATUS.soft404";
    static final int DEFAULT_MAX_DISTANCE = 16;

    @Override
    public CheckType type() {
        return CheckType.PAGE_STATUS;
    }

    @Override
    public Severity defaultSeverity() {
        return Severity.ERROR;
    }

    @Override
    public Set<String> messageKeys() {
        return Set.of(HTTP_ERROR, SOFT_404);
    }

    @Override
    public List<CheckFinding> evaluate(PageSnapshot snapshot, CheckConfig config) {
        if (!snapshot.reachable()) {
            return List.of();                       // PAGE_UNREACHABLE owns a page that never answered
        }
        if (snapshot.httpStatus() >= 400) {
            return List.of(finding(snapshot, config, HTTP_ERROR,
                    List.of(String.valueOf(snapshot.httpStatus()))));
        }
        SoftNotFoundProbe probe = config.facts().softNotFound();
        int maxDistance = config.option("maxDistance", DEFAULT_MAX_DISTANCE);
        if (looksLikeNotFound(snapshot, probe, maxDistance)) {
            return List.of(finding(snapshot, config, SOFT_404, List.of()));
        }
        return List.of();
    }

    static boolean looksLikeNotFound(PageSnapshot snapshot, SoftNotFoundProbe probe,
            int maxDistance) {
        if (snapshot.httpStatus() != 200 || !probe.usable() || snapshot.textContent().isBlank()) {
            return false;
        }
        int distance = SimHash.hammingDistance(snapshot.textSimhash(), probe.simhash());
        if (distance > maxDistance) {
            return false;
        }
        // Two-anchor rule: a page may resemble the not-found shell and still be real (shell-heavy
        // sites whose shared frame dominates every page). It is a soft 404 only if it is ALSO
        // closer to the not-found fingerprint than to the site's own root page — the best-known
        // real page of that site. When the site answers every path, even "/", with the same
        // shell (measured: d(probe, root) = 0), the two distances are equal for every page and
        // the check stays silent: a check that eats real pages is worse than no check at all
        // (spec 8).
        if (probe.referenceUsable()) {
            int rootDistance = SimHash.hammingDistance(
                    snapshot.textSimhash(), probe.referenceSimhash());
            if (rootDistance <= distance) {
                return false;
            }
            return true;
        }
        return true;
    }

    private CheckFinding finding(PageSnapshot snapshot, CheckConfig config, String messageKey,
            List<String> args) {
        return new CheckFinding(type(), config.severity(), snapshot.url().value(), snapshot.url(),
                messageKey, args, Evidence.ofPage(snapshot));
    }
}